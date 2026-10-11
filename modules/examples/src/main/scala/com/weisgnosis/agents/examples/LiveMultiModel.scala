/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.examples

import com.weisgnosis.agents.core.agent.*
import com.weisgnosis.agents.core.client.*
import com.weisgnosis.agents.core.coordination.MsgHub
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolHistory}
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, ToolAuthorizer, Toolkit}
import com.weisgnosis.agents.harness.subagent.*
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

/** Sequential opt-in gateway checks. Results describe assertions, never model self-reports. */
object LiveMultiModel extends ZIOAppDefault:
  private[examples] enum Status derives JsonCodec:
    case Passed, Failed, Unsupported, Unavailable, NotRun

  final private[examples] case class Scenario(
      name: String,
      models: Chunk[String],
      status: Status,
      detail: String
  ) derives JsonCodec

  final private[examples] case class CatalogModel(
      id: String,
      capabilities: Chunk[String],
      status: String,
      endpoint: String
  ) derives JsonCodec

  private val maximumCatalogBytes: Int = 1024 * 1024

  def run: ZIO[Any, Throwable, Unit] = LiveProviderSmoke.configuration.flatMap:
    case None =>
      Console.printLine(
        Scenario("execution", Chunk.empty, Status.NotRun, "Explicit live opt-in is disabled").toJson
      )
    case Some(config) =>
      (for
        peer <- System
          .env("WEISGNOSIS_LIVE_PEER_MODEL")
          .mapError(_ => AgentError.InvalidConfiguration("Cannot read WEISGNOSIS_LIVE_PEER_MODEL"))
        http <- ZIO.service[Client]
        catalog <- discover(http, config).tapError(error =>
          Console
            .printLine(
              Scenario(
                "discovery",
                Chunk(config.model),
                error match
                  case _: AgentError.ModelError => Status.Unavailable
                  case _                        => Status.Failed,
                "Gateway availability, catalog validation or discovery budget failed before model execution"
              ).toJson
            )
            .orDie
        )
        timeout <- LiveProviderSmoke.runTimeout
        _ <- Console.printLine(
          Json
            .Obj(
              "max_tokens"          -> config.maxTokens.fold[Json](Json.Null)(Json.Num(_)),
              "run_timeout_seconds" -> Json.Num(timeout.toSeconds),
              "scheduling"          -> Json.Str("sequential; no admitted turn retry")
            )
            .toJson
        )
        _ <- exercise(http, config, peer, catalog)
      yield ()).provide(Client.default, Server.defaultWithPort(0))

  private[examples] def decodeCatalog(body: String): Either[AgentError, Chunk[CatalogModel]] =
    for
      json <- JsonBoundary
        .decode(body)
        .left
        .map(_ => AgentError.DeserializationError("Malformed model catalog JSON"))
      _ <- Either.cond(
        JsonBoundary.validUnicode(json),
        (),
        AgentError.DeserializationError("Malformed Unicode in model catalog")
      )
      data <- json.asObject
        .flatMap(_.get("data"))
        .flatMap(_.asArray)
        .toRight(
          AgentError.DeserializationError("Model catalog requires a data array")
        )
      _ <- Either.cond(
        data.size <= 256,
        (),
        AgentError.DeserializationError("Model catalog exceeds 256 entries")
      )
      models <- data.foldLeft[Either[AgentError, Chunk[CatalogModel]]](Right(Chunk.empty))(
        (previous, value) =>
          for
            accumulated <- previous
            model <- value
              .as[CatalogModel]
              .left
              .map(_ => AgentError.DeserializationError("Invalid model catalog entry"))
            _ <- Either.cond(
              !model.id.isBlank && model.id.length <= 256 &&
                model.capabilities.size <= 32 && model.capabilities.forall(_.length <= 64) &&
                model.status.length <= 64 && model.endpoint.length <= 512,
              (),
              AgentError.DeserializationError("Invalid bounded model catalog fields")
            )
          yield accumulated :+ model
      )
      _ <- Either.cond(
        models.map(_.id).distinct.size == models.size,
        (),
        AgentError.DeserializationError("Duplicate model catalog identity")
      )
    yield models

  private[examples] def eligibility(
      id: String,
      catalog: Chunk[CatalogModel]
  ): Option[Status] = catalog.find(_.id == id) match
    case None => Some(Status.Unavailable)
    case Some(model)
        if !model.capabilities.contains("chat") ||
          !model.endpoint.endsWith("/chat/completions") =>
      Some(Status.Unsupported)
    case Some(model) if !Set("loaded", "not-loaded", "available").contains(model.status) =>
      Some(Status.Unavailable)
    case Some(_) => None

  private[examples] def discover(
      http: Client,
      config: LlmConfig
  ): IO[AgentError, Chunk[CatalogModel]] =
    for
      parsed <- ZIO
        .attempt(new java.net.URI(config.endpoint))
        .mapError(_ => AgentError.InvalidConfiguration("Invalid live chat endpoint"))
      endpoint <- ZIO
        .fromEither(URL.decode(config.endpoint))
        .mapError(_ => AgentError.InvalidConfiguration("Invalid live chat endpoint"))
      _ <- ZIO
        .fail(
          AgentError.InvalidConfiguration(
            "Live catalog requires an absolute chat/completions endpoint without query or fragment"
          )
        )
        .unless(
          Option(parsed.getScheme).exists(scheme =>
            scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http")
          ) &&
            Option(parsed.getHost).exists(_.nonEmpty) &&
            config.endpoint.endsWith("/chat/completions") &&
            Option(parsed.getRawQuery).isEmpty && Option(parsed.getRawFragment).isEmpty &&
            Option(parsed.getRawUserInfo).isEmpty
        )
      base = config.endpoint.stripSuffix("/chat/completions")
      models <- ZIO
        .fromEither(URL.decode(base + "/models"))
        .mapError(_ => AgentError.InvalidConfiguration("Invalid catalog endpoint"))
      health = endpoint.copy(path = Path.root / "healthz")
      _       <- discoveryRead(http, health, None).unit
      body    <- discoveryRead(http, models, config.apiKey)
      catalog <- ZIO.fromEither(decodeCatalog(body))
    yield catalog

  /** Only discovery is retried; admitted model scenarios below run once. */
  private def discoveryRead(http: Client, url: URL, key: Option[String]): IO[AgentError, String] =
    def once: IO[AgentError, String] = ZIO.scoped:
      for
        response <- Client
          .streaming(
            Request
              .get(url)
              .addHeaders(
                key.fold(Headers.empty)(value => Headers("Authorization" -> s"Bearer $value"))
              )
          )
          .provideSomeEnvironment[Scope](_.add(http))
          .mapError(_ => AgentError.ModelError(0, "Gateway discovery transport unavailable"))
        _ <- ZIO
          .fail(AgentError.ModelError(response.status.code, "Gateway discovery rejected"))
          .unless(response.status.isSuccess)
        bytes <- response.body.asStream
          .take(maximumCatalogBytes.toLong + 1)
          .runCollect
          .mapError(_ => AgentError.ModelError(0, "Gateway discovery body failed"))
        _ <- ZIO
          .fail(AgentError.DeserializationError("Catalog response exceeds byte budget"))
          .when(bytes.size > maximumCatalogBytes)
        text <- ZIO
          .attempt(
            java.nio.charset.StandardCharsets.UTF_8
              .newDecoder()
              .decode(java.nio.ByteBuffer.wrap(bytes.toArray))
              .toString
          )
          .mapError(_ => AgentError.DeserializationError("Invalid catalog UTF-8"))
      yield text
    once
      .timeoutFail(AgentError.ModelError(0, "Gateway discovery deadline exhausted"))(10.seconds)
      .catchAll:
        case error: AgentError.ModelError if error.statusCode == 0 || error.statusCode == 503 =>
          ZIO.sleep(1.second) *> once.timeoutFail(
            AgentError.ModelError(0, "Gateway discovery deadline exhausted")
          )(10.seconds)
        case error => ZIO.fail(error)

  private[examples] def exercise(
      http: Client,
      config: LlmConfig,
      peer: Option[String],
      catalog: Chunk[CatalogModel]
  ): ZIO[Client & Server, AgentError, Chunk[Scenario]] =
    for
      client <- ChatCompletionsClient.make(
        http,
        CompletionTransportLimits(responseBytes = 1024L * 1024)
      )
      selected = Chunk(config.model) ++ Chunk.fromIterable(peer.filter(_ != config.model))
      profiles <- ZIO.foreach(selected)(id =>
        val configured = config.copy(model = id)
        val checks: Chunk[(String, IO[AgentError, Unit])] = Chunk(
          "streaming-tool-chain" -> LiveProviderSmoke.exercise(http, configured),
          "disposable-file"      -> LiveFileTask.exercise(client, configured).unit,
          "native-structured" -> LiveProviderProfiles
            .exerciseProfile(client, configured, LiveProviderProfiles.Profile.Native),
          "synthetic-structured" -> LiveProviderProfiles
            .exerciseProfile(client, configured, LiveProviderProfiles.Profile.Synthetic),
          "joined-local-cancellation" -> LiveProviderProfiles.exerciseProfile(
            client,
            configured,
            LiveProviderProfiles.Profile.Cancellation
          )
        )
        ZIO.foreach(checks)((name, effect) => eligibleScenario(name, Chunk(id), catalog)(effect))
      )
      same <- eligibleScenario("same-model-persistent-hub", Chunk(config.model), catalog)(
        conversation(client, config, config).unit
      )
      mixed <- peer match
        case None =>
          emit(
            Scenario(
              "mixed-model-persistent-hub",
              Chunk(config.model),
              Status.NotRun,
              "Set WEISGNOSIS_LIVE_PEER_MODEL to a distinct catalog chat model"
            )
          )
        case Some(id) if id == config.model =>
          emit(
            Scenario(
              "mixed-model-persistent-hub",
              Chunk(id),
              Status.NotRun,
              "Peer and primary are the same model"
            )
          )
        case Some(id) =>
          eligibleScenario("mixed-model-persistent-hub", Chunk(config.model, id), catalog)(
            conversation(client, config, config.copy(model = id)).unit
          )
      failover <- eligibleScenario(
        "definite-http-rejection-failover",
        Chunk(config.model),
        catalog
      )(definiteFailover(http, config))
      child <- peer match
        case None =>
          emit(
            Scenario(
              "independently-selected-parent-child",
              selected,
              Status.NotRun,
              "Set WEISGNOSIS_LIVE_PEER_MODEL for an independently selected child"
            )
          )
        case Some(id) =>
          eligibleScenario("independently-selected-parent-child", Chunk(config.model, id), catalog)(
            parentChild(client, config, config.copy(model = id))
          )
      parallel <- eligibleScenario("parallel-local-evidence", Chunk(config.model), catalog)(
        LiveParallelTools.exercise(client, config)
      )
    yield profiles.flatten ++ Chunk(same, mixed, failover, child, parallel)

  private def eligibleScenario[R](
      name: String,
      models: Chunk[String],
      catalog: Chunk[CatalogModel]
  )(effect: ZIO[R, AgentError, Unit]): ZIO[R, AgentError, Scenario] =
    models.flatMap(eligibility(_, catalog)).headOption match
      case Some(status) =>
        emit(
          Scenario(
            name,
            models,
            status,
            "Selected identity is absent, non-chat, or unavailable in the catalog"
          )
        )
      case None => record(name, models)(effect)

  private[examples] def record[R](name: String, models: Chunk[String])(
      effect: ZIO[R, AgentError, Unit]
  ): ZIO[R, AgentError, Scenario] = effect.exit.flatMap:
    case Exit.Success(_) => emit(Scenario(name, models, Status.Passed, "Public assertions passed"))
    case Exit.Failure(cause) if cause.isInterrupted || cause.defects.nonEmpty =>
      ZIO.refailCause(cause)
    case Exit.Failure(cause) =>
      val unavailable = cause.failures.exists:
        case error: AgentError.ModelError => Set(0, 429, 502, 503, 504).contains(error.statusCode)
        case _                            => false
      val exhausted = cause.failures.exists:
        case _: AgentError.TimeoutError => true
        case _                          => false
      emit(
        Scenario(
          name,
          models,
          if unavailable then Status.Unavailable else Status.Failed,
          if exhausted then "Run budget exhausted; no admitted request or tool was retried"
          else "Scenario failed; no admitted request or tool was retried"
        )
      )

  /** Publish each completed receipt immediately; a later cancellation cannot erase earlier output. */
  private def emit(scenario: Scenario): UIO[Scenario] =
    Console.printLine(scenario.toJson).orDie.as(scenario)

  /** Three scheduled turns preserve private history and exact peer attribution, without shared tool authority. */
  private[examples] def conversation(
      client: LlmClient,
      firstConfig: LlmConfig,
      secondConfig: LlmConfig
  ): IO[AgentError, Unit] = ZIO.scoped:
    for
      timeout <- LiveProviderSmoke.runTimeout
      alice <- ReActAgent.make(
        AgentConfig(name = "Alice", maxIterations = 2, timeout = timeout),
        client = client,
        llmConfig = firstConfig,
        modelInputTransform = ModelInputTransform.peerAware
      )
      bob <- ReActAgent.make(
        AgentConfig(name = "Bob", maxIterations = 2, timeout = timeout),
        client = client,
        llmConfig = secondConfig,
        modelInputTransform = ModelInputTransform.peerAware
      )
      _ <- MsgHub.make(Chunk(alice, bob))
      first <- alice.run(
        Msg.user("Suggest one benefit of typed errors. Answer briefly; do not call tools.")
      )
      second <- bob.run(Msg.user("Review Alice's peer message. Answer briefly; do not call tools."))
      third <- alice.run(
        Msg.user("Consider Bob's peer message and conclude briefly; do not call tools.")
      )
      aliceHistory <- alice.history
      bobHistory   <- bob.history
      results = Chunk(first, second, third)
      _ <- ZIO
        .fail(AgentError.InvalidMessage("Persistent peer delivery/history assertions failed"))
        .unless(
          results.forall(result =>
            result.state == AgentRunState.Completed &&
              result.reply.text.trim.nonEmpty && result.peerDeliveries.size == 1 &&
              result.peerDeliveries.forall(_.outcome == PeerDeliveryOutcome.Delivered)
          ) &&
            hasPeer(bobHistory, alice.agentId, "Alice", first.reply) &&
            hasPeer(aliceHistory, bob.agentId, "Bob", second.reply) &&
            aliceHistory.contains(first.reply) && bobHistory.contains(second.reply) &&
            ToolHistory.inspect(aliceHistory).isComplete && ToolHistory
              .inspect(bobHistory)
              .isComplete
        )
    yield ()

  private def hasPeer(history: Chunk[Msg], id: String, name: String, message: Msg): Boolean =
    history
      .flatMap(_.content)
      .exists:
        case ContentBlock.PeerObservation(senderId, senderName, observed) =>
          senderId == id && senderName == name && observed == message
        case _ => false

  /** A host-bound parent tool owns and joins a fresh factory-built child. No model-selected client or prompt. */
  private[examples] def parentChild(
      client: LlmClient,
      parentConfig: LlmConfig,
      childConfig: LlmConfig
  ): IO[AgentError, Unit] = ZIO.scoped:
    for
      timeout      <- LiveProviderSmoke.runTimeout
      supervisor   <- TaskSupervisor.open[AgentResult]
      root         <- SubagentSpawner.root(supervisor)
      childResults <- Ref.make(Chunk.empty[AgentResult])
      executions   <- Ref.make(0)
      factory <- ZIO.fromEither(
        SubagentFactory.make(
          AgentConfig(name = "child", maxIterations = 2, timeout = timeout),
          childConfig,
          Toolkit.empty,
          SubagentToolBoundary(),
          SubagentToolBoundary(),
          _ => ZIO.succeed(Toolkit.empty),
          _ => ZIO.succeed(client),
          ToolAuthorizer.allow
        )
      )
      tool = Tool.make(
        ToolDef(
          "consult_child",
          "Ask the host-selected child for one benefit of typed errors. Call exactly once with no arguments.",
          Json.Obj(
            "type"                 -> Json.Str("object"),
            "properties"           -> Json.Obj(),
            "additionalProperties" -> Json.Bool(false)
          ),
          isReadOnly = true
        )
      )((input: Json) =>
        if input != Json.Obj() then
          ZIO.fail(AgentError.ToolExecutionError("consult_child", "No arguments accepted"))
        else
          ZIO.scoped:
            executions.update(_ + 1) *> factory
              .spawn(
                root,
                Msg.user(
                  "Give one benefit of typed errors in one short sentence. Do not call tools."
                )
              )
              .flatMap:
                case TaskAdmission.Closed =>
                  ZIO.fail(AgentError.InvalidConfiguration("Child admission closed"))
                case TaskAdmission.Accepted(task) =>
                  task.await.flatMap:
                    case Exit.Failure(cause) => ZIO.refailCause(cause)
                    case Exit.Success(result) =>
                      ZIO
                        .fail(AgentError.InvalidMessage("Child did not complete visibly"))
                        .unless(
                          result.state == AgentRunState.Completed && result.reply.text.trim.nonEmpty
                        ) *>
                        childResults.update(_ :+ result).as(result.reply.text)
      )
      parent <- ReActAgent.make(
        AgentConfig(name = "parent", maxIterations = 3, timeout = timeout),
        toolkit = Toolkit.single(tool),
        client = client,
        llmConfig = parentConfig
      )
      result <- parent.run(
        Msg.user(
          "Call consult_child exactly once with no arguments, then summarize its returned observation briefly."
        )
      )
      count    <- executions.get
      children <- childResults.get
      observations = result.history.flatMap(_.toolResults)
      _ <- ZIO
        .fail(
          AgentError.InvalidMessage(
            "Parent/child completion or exact observation assertions failed"
          )
        )
        .unless(
          count == 1 && children.size == 1 && result.state == AgentRunState.Completed &&
            result.reply.text.trim.nonEmpty && children.forall(child =>
              observations.exists(observation =>
                observation.name
                  .contains("consult_child") && observation.outputText == child.reply.text
              )
            ) &&
            ToolHistory.inspect(result.history).isComplete
        )
    yield ()

  /** The first HTTP attempt is actually rejected. The runtime alone decides failover safety. */
  private[examples] def definiteFailover(
      http: Client,
      config: LlmConfig
  ): ZIO[Server, AgentError, Unit] =
    ZIO.scoped:
      for
        requests <- Ref.make(0)
        port <- Server
          .install(Routes(Method.POST / "reject" -> handler {
            requests.update(_ + 1).as(Response.status(zio.http.Status.ServiceUnavailable))
          }))
        client  <- ChatCompletionsClient.make(http)
        timeout <- LiveProviderSmoke.runTimeout
        agent <- ReActAgent
          .builder()
          .client(client)
          .llmConfig(config.copy(endpoint = s"http://127.0.0.1:$port/reject", apiKey = None))
          .fallback(ModelTarget(client, config))
          .maxIterations(2)
          .timeout(timeout)
          .build
        result <- agent.run(
          Msg.user("Reply briefly with one benefit of typed errors. Do not call tools.")
        )
        count <- requests.get
        _ <- ZIO
          .fail(AgentError.InvalidMessage("Definite rejection failover assertions failed"))
          .unless(
            count == 1 && result.state == AgentRunState.Completed && result.reply.text.trim.nonEmpty &&
              result.history.flatMap(_.toolUses).isEmpty
          )
      yield ()

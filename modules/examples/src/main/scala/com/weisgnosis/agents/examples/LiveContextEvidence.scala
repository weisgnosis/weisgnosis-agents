/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.examples

import com.weisgnosis.agents.core.agent.*
import com.weisgnosis.agents.core.client.*
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{EventPublisher, ModelStreamContext}
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.*
import com.weisgnosis.agents.harness.eviction.*
import com.weisgnosis.agents.harness.memory.*
import com.weisgnosis.agents.harness.transcript.*
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardOpenOption}
import java.security.MessageDigest
import zio.*
import zio.http.Client
import zio.json.*
import zio.json.ast.Json

/** Opt-in evidence retrieval and explicit compaction with five aggregate model requests at most. */
object LiveContextEvidence extends ZIOAppDefault:
  final private case class ReadInput(path: String) derives JsonCodec
  final private[examples] case class Receipt(
      model: String,
      scenarioAccepted: Boolean,
      requests: Int,
      retrievals: Int,
      payloadHash: String,
      storageVerified: Boolean,
      previewVerified: Boolean,
      summaryVerified: Boolean,
      recallVerified: Boolean,
      pairingVerified: Boolean,
      provenanceVerified: Boolean,
      directoryRemoved: Boolean
  ) derives JsonCodec

  def run: ZIO[Any, Throwable, Unit] = LiveProviderSmoke.configuration.flatMap:
    case None => Console.printLine("Context evidence disabled; enable WEISGNOSIS_LIVE_SMOKE.")
    case Some(config) =>
      ZIO
        .serviceWithZIO[Client](http =>
          (for
            catalog <- LiveMultiModel.discover(http, config)
            _ <- requireEvidence(
              LiveMultiModel.eligibility(config.model, catalog).isEmpty,
              "Configured E5 model is absent, unavailable or not chat-capable"
            )
            client <- ChatCompletionsClient.make(http)
          yield client)
            .flatMap(client =>
              LiveMultiModel.record("context-evidence", Chunk(config.model))(
                exercise(client, config).flatMap(receipt => Console.printLine(receipt.toJson).orDie)
              )
            )
        )
        .provide(Client.default)
        .unit

  private[examples] def exercise(client: LlmClient, config: LlmConfig): IO[AgentError, Receipt] =
    for
      deadline <- LiveProviderSmoke.runTimeout
      completed <- LiveFileTask
        .withFixture: directory =>
          for
            middle     <- Random.nextUUID.map(_.toString)
            earlier    <- Random.nextUUID.map(_.toString)
            requests   <- Ref.make(Chunk.empty[Chunk[Msg]])
            summaries  <- Ref.make(Chunk.empty[Chunk[Msg]])
            saved      <- Ref.make(Option.empty[String])
            retrievals <- Ref.make(0)
            file    = directory.resolve("recipe.txt")
            payload = "H" * 40000 + s"\nMIDDLE_FACT=$middle\n" + "T" * 40000
            store = new ToolResultStore:
              def persist(key: String, content: String): IO[AgentError, String] =
                io(
                  Files.write(
                    file,
                    content.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW
                  )
                ) *>
                  saved.set(Some(key)).as(file.toString)
              def resolve(key: String): IO[AgentError, Option[StoredToolResult]] =
                saved.get.flatMap:
                  case Some(current) if current == key =>
                    io(Files.readString(file, StandardCharsets.UTF_8))
                      .map(text => Some(StoredToolResult(text, file.toString)))
                  case _ => ZIO.succeed(None)
            bounded = new LlmClient:
              def streamTurn(
                  history: Chunk[Msg],
                  tools: Chunk[ToolDef],
                  settings: LlmConfig,
                  hub: EventPublisher,
                  context: ModelStreamContext
              ): ZIO[Scope, AgentError, TurnOutput] =
                requests
                  .updateAndGet(_ :+ history)
                  .flatMap(observed =>
                    if observed.size > 5 then
                      ZIO.fail(AgentError.InvalidConfiguration("E5 request budget exceeded"))
                    else client.streamTurn(history, tools, settings, hub, context)
                  )
            summarize = (history: Chunk[Msg]) =>
              ZIO.scoped:
                for
                  _       <- summaries.update(_ :+ history)
                  reply   <- Random.nextUUID
                  request <- Random.nextUUID
                  data <- ZIO
                    .fromEither(history.toJsonAST)
                    .mapError(AgentError.DeserializationError(_))
                  output <- bounded.streamTurn(
                    Chunk(
                      Msg.system(
                        "Summarize the supplied conversation data. Preserve EARLIER_FACT exactly. Return only a concise summary; no tools."
                      ),
                      Msg.user(data.toJson)
                    ),
                    Chunk.empty,
                    config,
                    EventPublisher.discard,
                    ModelStreamContext(reply.toString, request.toString)
                  )
                  text <- output match
                    case TurnOutput.FinalText(message) if !message.text.isBlank =>
                      ZIO.succeed(message.text)
                    case _ =>
                      ZIO.fail(
                        AgentError.DeserializationError("E5 summary must be nonblank FinalText")
                      )
                yield text
            lookup = Tool.make(
              ToolDef(
                "large_evidence",
                "Read the oversized evidence once.",
                emptySchema,
                isReadOnly = true
              )
            )(input =>
              if input == Json.Obj() then ZIO.succeed(payload)
              else
                ZIO.fail(AgentError.ToolExecutionError("large_evidence", "No arguments accepted"))
            )
            read = Tool.fromFunction[ReadInput, String](
              "read_file",
              "Read only the saved evidence path; returns its bounded middle slice.",
              Json.Obj(
                "type"       -> Json.Str("object"),
                "properties" -> Json.Obj("path" -> Json.Obj("type" -> Json.Str("string"))),
                "required"   -> Json.Arr(Json.Str("path")),
                "additionalProperties" -> Json.Bool(false)
              ),
              isReadOnly = true
            )(input =>
              if input.path != file.toString then
                ZIO.fail(
                  AgentError
                    .PermissionDenied("read_file", "Only the saved evidence path is permitted")
                )
              else
                saved.get.flatMap:
                  case None => ZIO.fail(AgentError.StorageError("Evidence has not been saved"))
                  case Some(key) =>
                    store
                      .resolve(key)
                      .flatMap:
                        case Some(value) =>
                          retrievals.update(_ + 1).as(value.content.slice(39950, 40200))
                        case None => ZIO.fail(AgentError.StorageError("Saved evidence is missing"))
            )
            transcript <- TranscriptStore.inMemory
            dependencies = HostAssembly.Dependencies(
              bounded,
              ToolAuthorizer.allow,
              store,
              HostAssembly.TranscriptSource.Provided(transcript),
              summarize,
              Toolkit.single(lookup).updated(read)
            )
            first <- HostAssembly.open(
              settings(config, CompactorConfig()),
              dependencies,
              Chunk.empty
            )
            result <- first.agent.run(
              Msg.user(
                "Call large_evidence once, then read_file once using its saved path. Return only the exact MIDDLE_FACT value from the retrieved middle slice."
              )
            )
            bytes    <- io(Files.readAllBytes(file)).map(Chunk.fromArray(_))
            hash     <- digest(payload)
            key      <- saved.get
            resolved <- ZIO.foreach(key)(store.resolve).map(_.flatten)
            observed <- requests.get
            count    <- retrievals.get
            previews = observed.lift(1).toList.flatMap(_.flatMap(_.toolResults))
            storage = bytes == Chunk.fromArray(payload.getBytes(StandardCharsets.UTF_8)) &&
              resolved.contains(StoredToolResult(payload, file.toString)) && key.exists(
                _.endsWith(hash)
              )
            preview = previews.exists(value =>
              value.name.contains("large_evidence") && value.text.length < 5000 &&
                value.text.contains("H" * 2000) && value.text.endsWith("T" * 2000) &&
                !value.text.contains(middle) && value.metadata
                  .get("eviction_path")
                  .contains(Json.Str(file.toString))
            )
            _ <- requireEvidence(
              storage && preview && count == 1 && result.state == AgentRunState.Completed &&
                result.reply.text.trim == middle && observed.size == 3,
              "Oversized storage, preview, retrieval or exact answer evidence failed"
            )
            seed = seededHistory(earlier)
            second <- HostAssembly.open(
              settings(
                config,
                CompactorConfig(
                  triggerTokens = 500,
                  keepTokens = 30,
                  preserveTailMessages = 5,
                  protectTokens = 0
                )
              ),
              dependencies.copy(localTools = Toolkit.empty),
              Chunk.empty
            )
            _ <- second.agent.restoreSnapshot(AgentSnapshot.Idle(seed))
            compacted <- second.agent.run(
              Msg.user("Return only the exact EARLIER_FACT value from the conversation summary.")
            )
            summaryInputs <- summaries.get
            allRequests   <- requests.get
            next = allRequests.lastOption.toList
            summaryValid = summaryInputs.size == 1 && summaryInputs.exists(
              _.exists(ConversationCompactor.isSummary)
            ) &&
              next.exists(history =>
                history.count(ConversationCompactor.isSummary) == 1 &&
                  history.filter(ConversationCompactor.isSummary).exists(_.text.contains(earlier))
              )
            recall =
              compacted.state == AgentRunState.Completed && compacted.reply.text.trim == earlier
            paired = ToolHistory.inspect(compacted.history).isComplete &&
              compacted.history.flatMap(_.toolUses).map(_.input) == Chunk(
                Json.Obj("ordinal" -> Json.Str("recent"))
              ) &&
              compacted.history.flatMap(_.toolResults).map(_.text) == Chunk("recent occurrence")
            provenance <- verifyProvenance(compacted.history, seed.filter(_.id.nonEmpty))
            _ <- requireEvidence(
              summaryValid && recall && paired && provenance && allRequests.size == 5,
              "Explicit compaction or exact recall evidence failed"
            )
          yield Receipt(
            config.model,
            true,
            allRequests.size,
            count,
            hash,
            storage,
            preview,
            summaryValid,
            recall,
            paired,
            provenance,
            directoryRemoved = false
          )
        .timeoutFail(AgentError.TimeoutError(deadline.toSeconds, "E5 aggregate deadline exceeded"))(
          deadline
        )
      removed <- io(!Files.exists(completed._1))
      _       <- requireEvidence(removed, "Owned evidence directory remained after cleanup")
    yield completed._2.copy(directoryRemoved = removed)

  private val emptySchema: Json.Obj = Json.Obj(
    "type"                 -> Json.Str("object"),
    "properties"           -> Json.Obj(),
    "additionalProperties" -> Json.Bool(false)
  )
  private def settings(config: LlmConfig, compaction: CompactorConfig): HostAssembly.Settings =
    HostAssembly.Settings(
      "context-evidence",
      config,
      compaction,
      EvictionConfig(),
      AnonymousTranscriptPolicy.RecordEachSnapshot
    )
  private def seededHistory(fact: String): Chunk[Msg] = Chunk(
    Msg.user("Old material " + "P" * 8000),
    Msg.user(s"EARLIER_FACT=$fact").withName(ConversationCompactor.SummaryName),
    Msg.assistant(
      Chunk(ContentBlock.ToolUse("repeated", "search", Json.Obj("ordinal" -> Json.Str("old"))))
    ),
    Msg.tool("repeated", "search", "old occurrence"),
    Msg.assistant(
      Chunk(ContentBlock.ToolUse("repeated", "search", Json.Obj("ordinal" -> Json.Str("recent"))))
    ),
    Msg.tool("repeated", "search", "recent occurrence"),
    Msg
      .user("Retained first user")
      .withId(Some("user-a"))
      .withMetadata("source", Json.Str("first-origin")),
    Msg
      .user("Retained second user")
      .withId(Some("user-b"))
      .withMetadata("source", Json.Str("second-origin"))
  )
  private def verifyProvenance(history: Chunk[Msg], expected: Chunk[Msg]): IO[AgentError, Boolean] =
    for
      restored <- ZIO
        .fromEither(history.toJson.fromJson[Chunk[Msg]])
        .mapError(AgentError.DeserializationError(_))
      flushed <- Ref.make(Chunk.empty[Msg])
      counter = new TokenCounter:
        def count(message: Msg): Long = 1L
      compactor = new ConversationCompactor(
        _ => ZIO.succeed("provenance control summary"),
        CompactorConfig(
          triggerTokens = 1,
          keepTokens = 1,
          preserveTailMessages = 1,
          protectTokens = 0
        ),
        counter,
        flush = messages => flushed.update(_ ++ messages.filter(_.role == Role.User))
      )
      _ <- compactor.compactIfNeeded(restored ++ Chunk.fill(3)(Msg.assistant("control tail")))
      originalUsers <- flushed.get
    yield originalUsers.filter(message =>
      message.id.exists(id => id == "user-a" || id == "user-b")
    ) == expected
  private def requireEvidence(valid: Boolean, reason: String): IO[AgentError, Unit] =
    ZIO.fail(AgentError.InvalidConfiguration(reason)).unless(valid).unit
  private def digest(text: String): IO[AgentError, String] =
    io(
      MessageDigest
        .getInstance("SHA-256")
        .digest(text.getBytes(StandardCharsets.UTF_8))
        .map(byte => f"${byte & 0xff}%02x")
        .mkString
    )
  private def io[A](operation: => A): IO[AgentError, A] =
    ZIO.uninterruptible(
      ZIO.attemptBlocking(operation).mapError(error => AgentError.StorageError(error.toString))
    )

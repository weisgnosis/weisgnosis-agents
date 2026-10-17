/*
 * Copyright 2024-2026 WeisGnosis (https://weisgnosis.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package consumer

import com.weisgnosis.agents.core.agent.{
  AgentConfig,
  AgentRunState,
  ModelInputTransform,
  ReActAgent,
  SuppliedToolResult,
  StructuredMode,
  StructuredRepairPolicy
}
import com.weisgnosis.agents.core.coordination.Pipeline
import com.weisgnosis.agents.harness.questions.*
import com.weisgnosis.agents.harness.procedure.*
import com.weisgnosis.agents.harness.storage.{
  ConditionalWrite,
  ExpectedRevision,
  VersionedJson,
  VersionedJsonStore
}
import com.weisgnosis.agents.harness.memory.VersionedMemoryLimits
import com.weisgnosis.agents.core.client.{
  ChatCompletionsRequest,
  ChatCompletionTokenLimitField,
  LlmClient,
  ScriptedLlmClient,
  LlmConfig,
  CompletionEnvelopeProfile,
  ReasoningEffort,
  StopSequences,
  ToolCallParallelism,
  ThinkingBudget,
  ToolResultFormat,
  TemperaturePolicy,
  TurnOutput
}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{
  AgentEvent,
  EventPublisher,
  FinalAnswerFilter,
  ModelStreamContext
}
import com.weisgnosis.agents.core.json.ExactJson
import com.weisgnosis.agents.core.model.{ChatUsage, ContentBlock, MediaSource, Msg, ToolResultState}
import com.weisgnosis.agents.core.tool.{
  Tool,
  ToolBinding,
  ToolExecutionResult,
  ToolAuthorization,
  ToolAuthorizationRequest,
  ToolAuthorizer,
  ToolDef,
  ToolInvocation,
  ToolResultPayload,
  ToolSchema,
  ToolSchemaStrictness,
  Toolkit
}
import com.weisgnosis.agents.harness.storage.{StorageAddress, StorageRevision}
import com.weisgnosis.agents.harness.permission.{
  PermissionBehavior,
  PermissionEngine,
  UnavailablePermissionAuthorizer
}
import com.weisgnosis.agents.mcp.client.McpClient
import com.weisgnosis.agents.mcp.protocol.{
  McpCallOutcome,
  McpCallResult,
  McpContentBlock,
  McpImplementation,
  McpMessage
}
import com.weisgnosis.agents.mcp.transport.{McpStdioTransport, McpTransport}
import com.weisgnosis.agents.storage.postgresql.PostgresVersionedJsonStore
import com.weisgnosis.agents.otel.AgentTelemetry
import io.opentelemetry.api.OpenTelemetry
import zio.telemetry.opentelemetry.context.ContextStorage
import java.io.File
import java.util.regex.Pattern
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream

/** The neutral package deliberately has no access to private[agents] implementation helpers. */
object ArtifactSmoke extends ZIOAppDefault:
  final private case class Manifest(version: String, sha256: Map[String, String]) derives JsonCodec

  final private case class ExternalInput(
      value: String,
      effort: ReasoningEffort = ReasoningEffort.Low
  ) derives JsonCodec,
        ToolSchema

  private def verifyScriptedClient: ZIO[Scope, AgentError, Unit] =
    for
      calls <- Ref.make(0)
      toolkit <- ZIO.fromEither(
        Toolkit.empty.register(
          Tool.make(ToolDef("script_probe", "Check packaged scripted calls", Json.Obj()))(_ =>
            calls.update(_ + 1).as("observed")
          )
        )
      )
      client <- ScriptedLlmClient.make(
        Chunk(
          TurnOutput.ToolCalls(
            Msg.assistant(Chunk(ContentBlock.ToolUse("probe", "script_probe", Json.Obj())))
          ),
          TurnOutput.FinalText(Msg.assistant("script complete"))
        ),
        maxRecordedRequests = 3
      )
      agent      <- ReActAgent.builder().model(client).toolkit(toolkit).build
      result     <- agent.run(Msg.user("exercise script"))
      _          <- client.verifyConsumed
      records    <- client.requests
      executions <- calls.get
      extra <- client
        .streamTurn(
          Chunk.empty,
          Chunk.empty,
          LlmConfig("probe"),
          EventPublisher.discard,
          ModelStreamContext("reply", "extra")
        )
        .either
      _ <- ZIO
        .fail(failure("Packaged strict scripted client failed"))
        .unless(
          result.reply.text == "script complete" && executions == 1 && records.size == 2 &&
            records
              .drop(1)
              .exists(
                _.input.history.exists(_.role == com.weisgnosis.agents.core.model.Role.Tool)
              ) &&
            extra == Left(
              AgentError.InvalidConfiguration("Script exhausted: unexpected model request")
            )
        )
    yield ()

  private def verifyStructuredRepair: ZIO[Scope, AgentError, Unit] =
    for
      executions <- Ref.make(0)
      toolkit <- ZIO.fromEither(
        Toolkit.empty.register(
          Tool.make(ToolDef("repair_probe", "Read packaged repair evidence", Json.Obj()))(_ =>
            executions.update(_ + 1).as("retained evidence")
          )
        )
      )
      client <- ScriptedLlmClient.make(
        Chunk(
          TurnOutput.ToolCalls(
            Msg.assistant(Chunk(ContentBlock.ToolUse("read", "repair_probe", Json.Obj())))
          ),
          TurnOutput.FinalText(Msg.assistant("-1")),
          TurnOutput.FinalText(Msg.assistant("1"))
        ),
        maxRecordedRequests = 3
      )
      agent <- ReActAgent.builder().model(client).toolkit(toolkit).build
      result <- agent.runStructured[Int](
        Chunk(Msg.user("Return a nonnegative integer")),
        Json.Obj("type" -> Json.Str("integer"), "minimum" -> Json.Num(0)),
        StructuredMode.Native,
        StructuredRepairPolicy.OneOutputOnlyRequest
      )
      _        <- client.verifyConsumed
      requests <- client.requests
      count    <- executions.get
      _ <- ZIO
        .fail(failure("Packaged output-only structured repair failed"))
        .unless(
          result._2 == 1 && count == 1 && requests.size == 3 &&
            requests.lastOption.exists(_.input.tools.isEmpty) &&
            result._1.history.flatMap(_.toolResults).exists(_.id == "read") &&
            result._1.history.exists(_.text == "-1")
        )
    yield ()

  private def verifySchemaAdmission: IO[AgentError, Unit] =
    for
      executions <- Ref.make(0)
      tool = Tool.fromFunction[Json, String](
        "schema_probe",
        "Packaged schema admission",
        schema = Json.Obj(
          "type"    -> Json.Str("integer"),
          "minimum" -> Json.Num(2)
        )
      )(_ => executions.update(_ + 1).as("accepted"))
      derived = Tool.derived[ExternalInput, String]("derived_probe", "Packaged macro expansion")(
        input => executions.update(_ + 1).as(s"${input.value}:${input.effort}")
      )
      registry = Toolkit.single(tool).updated(derived)
      derivedResult <- registry.executeSafe(
        "derived_probe",
        Json.Obj("value" -> Json.Str("accepted"), "effort" -> Json.Str("High"))
      )
      rejected <- registry.executeSafe("schema_probe", Json.Num(1))
      accepted <- registry.executeSafe("schema_probe", Json.Num(2))
      count    <- executions.get
      _ <- ZIO
        .fail(failure("Packaged schema admission or transitive evaluator failed"))
        .unless(
          rejected.state == ToolResultState.Error && accepted.state == ToolResultState.Success && derivedResult == ToolExecutionResult(
            "accepted:High".toJson
          ) && count == 2
        )
    yield ()

  private def verifyTokenLimitField: IO[AgentError, Unit] =
    for
      body <- ZIO.fromEither(
        ChatCompletionsRequest.encode(
          Chunk(Msg.user("hello")),
          Chunk.empty,
          LlmConfig("model", maxTokens = Some(37)),
          tokenLimitField = ChatCompletionTokenLimitField.MaxCompletionTokens
        )
      )
      _ <- ZIO
        .fail(failure("Packaged token-limit wire selection failed"))
        .unless(
          body.get("max_completion_tokens").contains(Json.Num(37)) && body.get("max_tokens").isEmpty
        )
    yield ()

  private def verifyTemperatureOmission: IO[AgentError, Unit] =
    val config = LlmConfig("model", temperaturePolicy = TemperaturePolicy.Omit)
    for
      body <- ZIO.fromEither(
        ChatCompletionsRequest.encode(Chunk(Msg.user("hello")), Chunk.empty, config)
      )
      _ <- ZIO
        .fail(failure("Packaged temperature omission or codec failed"))
        .unless(
          body.get("temperature").isEmpty && config.toJson.fromJson[LlmConfig] == Right(config)
        )
    yield ()

  private def verifyStructuredSchema: ZIO[Scope, AgentError, Unit] =
    val schema = Json.Obj("type" -> Json.Str("integer"), "minimum" -> Json.Num(0))
    for
      client <- LlmClient.mock(
        Chunk(
          TurnOutput.FinalText(Msg.assistant("-1")),
          TurnOutput.FinalText(Msg.assistant("1"))
        )
      )
      agent <- ReActAgent.make(client = client)
      rejected <- agent
        .runStructured[Json](Chunk(Msg.user("answer")), schema, StructuredMode.Native)
        .either
      accepted <- agent
        .runStructured[Json](Chunk(Msg.user("answer")), schema, StructuredMode.Native)
      _ <- ZIO
        .fail(failure("Packaged structured output schema validation failed"))
        .unless(
          rejected.left
            .exists(_.isInstanceOf[AgentError.DeserializationError]) && accepted._2 == Json.Num(1)
        )
    yield ()

  private def verifyScopedTool: IO[AgentError, Unit] =
    for
      closed <- Ref.make(false)
      tool = new Tool[Any, Json, String]:
        def definition: ToolDef = ToolDef("scoped", "Packaged scoped execution", Json.Obj())
        def decodeInput(input: Json): Either[String, Json] = Right(input)
        def encodeOutput(output: String): String           = output
        def execute(input: Json): UIO[String]              = ZIO.succeed("legacy")
        override def executeScoped(input: Json, invocation: ToolInvocation)(
            publish: ToolResultPayload => UIO[Unit]
        ): ZIO[Scope, Throwable, String] =
          ZIO.acquireRelease(ZIO.unit)(_ => closed.set(true)) *>
            publish(ToolResultPayload(Chunk(ContentBlock.Text("working")))).as("scoped final")
      result   <- Toolkit.single(tool).executeSafe("scoped", Json.Obj())
      released <- closed.get
      _ <- ZIO
        .fail(failure("Packaged scoped execution or joined cleanup failed"))
        .unless(result == ToolExecutionResult("scoped final") && released)
    yield ()

  private def verifyMcpDeadlines: ZIO[Scope, AgentError, Unit] =
    val failure = AgentError.McpError("packaged initialization probe")
    val transport = new McpTransport:
      def send(message: McpMessage): ZIO[Scope, AgentError, Unit] = ZIO.fail(failure)
      def incoming: ZStream[Any, AgentError, McpMessage]          = ZStream.never
    for
      deadlines <- ZIO.fromEither(McpClient.Deadlines.make(2.seconds, 1.second))
      rejected  <- McpClient.open(transport, McpImplementation("consumer", "1"), deadlines).exit
      _ <- ZIO
        .fail(AgentError.InvalidMessage("Packaged MCP deadline contract failed"))
        .unless(
          deadlines.initialization == 2.seconds && deadlines.request == 1.second &&
            McpClient.Deadlines.make(Duration.Zero, 1.second).isLeft &&
            rejected.causeOption.exists(_.failures.contains(failure))
        )
    yield ()

  private def verifyExternalBinding: ZIO[Scope, AgentError, Unit] =
    val arguments = Json.Obj("value" -> Json.Str("checked"))
    for
      binding <- ZIO.fromEither(
        ToolBinding.external[ExternalInput](
          ToolDef("external", "Host-executed tool", Json.Obj())
        )
      )
      registry = Toolkit.single(binding)
      client <- LlmClient.mock(
        Chunk(
          TurnOutput.ToolCalls(
            Msg.assistant(Chunk(ContentBlock.ToolUse("external-call", "external", arguments)))
          ),
          TurnOutput.FinalText(Msg.assistant("accepted"))
        )
      )
      agent     <- ReActAgent.make(toolkit = registry, client = client)
      suspended <- agent.run(Msg.user("Request host execution"))
      pending <- suspended.state match
        case AgentRunState.Suspended(value) => ZIO.succeed(value)
        case _                              => ZIO.fail(failure("External binding did not suspend"))
      _ <- ZIO
        .fail(failure("External binding checkpoint differs from checked input"))
        .unless(
          pending.externalRequests.map(_.input) == Chunk(arguments) &&
            pending.externalTools == Chunk("external") && suspended.history
              .flatMap(_.toolResults)
              .isEmpty
        )
      completed <- agent.resume(Chunk(SuppliedToolResult(0, ToolExecutionResult("host result"))))
      _ <- ZIO
        .fail(failure("External binding did not resume from supplied observation"))
        .unless(
          completed.state == AgentRunState.Completed && completed.reply.text == "accepted" &&
            completed.history.flatMap(_.toolResults).map(_.id) == Chunk("external-call")
        )
    yield ()

  private def verifyResponsesBoundary: ZIO[Scope, AgentError, Unit] =
    (for
      http   <- ZIO.service[zio.http.Client]
      client <- com.weisgnosis.agents.core.client.ResponsesLlmClient.make(http)
      agent <- ReActAgent.make(
        client = client,
        llmConfig = LlmConfig(
          model = "consumer-model",
          endpoint = "http://127.0.0.1:1/chat/completions",
          temperaturePolicy = TemperaturePolicy.Omit
        )
      )
      rejected <- agent.run(Msg.user("Validate the endpoint before HTTP acquisition")).exit
      _ <- ZIO
        .fail(failure("Packaged Responses endpoint guard failed"))
        .unless(
          rejected.causeOption.exists(_.failures.exists {
            case AgentError.InvalidConfiguration(message) => message.contains("/responses")
            case _                                        => false
          })
        )
    yield ()).provideSomeLayer[Scope](
      zio.http.Client.default.mapError(_ => failure("Cannot acquire consumer HTTP client"))
    )

  private def verifyUserQuestions: ZIO[Scope, AgentError, Unit] =
    for
      params <- ZIO.fromEither(
        AskUserParams.make(
          Chunk(
            UserQuestion(
              "audience",
              "Who will read this?",
              "Audience",
              Chunk(
                QuestionOption("developers", "Developers"),
                QuestionOption("operators", "Operators")
              )
            )
          )
        )
      )
      questions <- ZIO.fromEither(AskUserTool.make())
      asking <- ScriptedLlmClient.make(
        Chunk(
          TurnOutput.ToolCalls(
            Msg.assistant(
              Chunk(
                ContentBlock.ToolUse("question", questions.binding.definition.name, params.json)
              )
            )
          )
        ),
        maxRecordedRequests = 1
      )
      original   <- ReActAgent.make(client = asking, toolkit = Toolkit.single(questions.binding))
      checkpoint <- original.run(Msg.user("Write release notes"))
      saved <- ZIO.fromEither(
        checkpoint.toJson.fromJson[com.weisgnosis.agents.core.agent.AgentResult].left.map(failure)
      )
      host = QuestionHostBinding("consumer", "checkpoint")
      pending <- ZIO.fromEither(questions.pending(saved, 0, host))
      supplied <- ZIO.fromEither(
        questions.answer(
          saved,
          host,
          pending.key,
          Chunk(
            UserAnswer("audience", AnswerValue.Selected(Chunk("developers")))
          )
        )
      )
      client <- ScriptedLlmClient.make(
        Chunk(TurnOutput.FinalText(Msg.assistant("For developers"))),
        maxRecordedRequests = 1
      )
      restored <- ReActAgent.make(client = client, toolkit = Toolkit.single(questions.binding))
      _        <- restored.restore(saved)
      result   <- restored.resume(Chunk(supplied))
      _        <- asking.verifyConsumed *> client.verifyConsumed
      _ <- ZIO
        .fail(failure("Packaged question restore/resume failed"))
        .unless(
          result.state == AgentRunState.Completed && result.reply.text == "For developers" &&
            result.history.flatMap(_.toolResults).size == 1
        )
    yield ()

  private def verifyProcedure: IO[AgentError, Unit] =
    for
      address <- ZIO.fromEither(StorageAddress.from(Chunk("consumer"), "procedure"))
      record  <- Ref.make(Option.empty[VersionedJson])
      calls   <- Ref.make(0)
      claims  <- Semaphore.make(1)
      store = new VersionedJsonStore:
        def read(candidate: StorageAddress): IO[AgentError, Option[VersionedJson]] =
          if candidate == address then record.get else ZIO.fail(failure("Unexpected address"))
        def compareAndSet(
            candidate: StorageAddress,
            expected: ExpectedRevision,
            value: Json
        ): IO[AgentError, ConditionalWrite] =
          if candidate != address then ZIO.fail(failure("Unexpected address"))
          else
            record
              .modify { previous =>
                val matches = expected match
                  case ExpectedRevision.Absent => previous.isEmpty
                  case ExpectedRevision.AtRevision(revision) =>
                    previous.exists(_.revision == revision)
                if !matches then (Right(ConditionalWrite.Conflict), previous)
                else
                  StorageRevision.fromLong(previous.fold(1L)(_.revision.value + 1L)) match
                    case Left(error) => (Left(error), previous)
                    case Right(revision) =>
                      (
                        Right(ConditionalWrite.Applied(revision)),
                        Some(VersionedJson(value, revision))
                      )
              }
              .flatMap(ZIO.fromEither(_))
      authority = new ProcedureSession.Authority:
        def acquire(candidate: StorageAddress): ZIO[Scope, AgentError, Unit] =
          if candidate == address then claims.withPermitScoped
          else ZIO.fail(failure("Unexpected claim address"))
      definition = ProcedureDefinition(
        "consumer",
        "v1",
        Chunk(
          ProcedureStage(
            "checked",
            1,
            _ => calls.update(_ + 1).as(Json.Str("submitted")),
            (_, output) => ZIO.succeed(ProcedureVerdict.Accepted(output))
          )
        )
      )
      initial = Json.Str("input")
      first <- ZIO.scoped(
        ProcedureSession.open(definition, initial, address, store, authority).flatMap(_.run)
      )
      second <- ZIO.scoped(
        ProcedureSession.open(definition, initial, address, store, authority).flatMap(_.run)
      )
      executions <- calls.get
      _ <- ZIO
        .fail(failure("Packaged procedure replayed an accepted stage"))
        .unless(
          first == ProcedureOutcome
            .Completed(Json.Str("submitted")) && second == first && executions == 1
        )
    yield ()

  private def verifyReliabilityContracts: ZIO[Scope, AgentError, Unit] =
    for
      firstClient  <- LlmClient.mock(Chunk(TurnOutput.FinalText(Msg.assistant("first"))))
      secondClient <- LlmClient.mock(Chunk(TurnOutput.FinalText(Msg.assistant("second"))))
      first        <- ReActAgent.make(client = firstClient)
      second <- ReActAgent.make(
        client = secondClient,
        modelInputTransform = ModelInputTransform.peerAware
      )
      stages  <- Pipeline.sequential(Chunk(first, second), Chunk(Msg.user("handoff")))
      client  <- LlmClient.mock(Chunk(TurnOutput.Truncated(Msg.assistant("partial"))))
      stopped <- ReActAgent.make(client = client)
      result  <- stopped.run(Msg.user("bounded"))
      limits  <- ZIO.fromEither(VersionedMemoryLimits.make(1024, 16))
      _ <- ZIO
        .fail(failure("Packaged reliability contracts failed"))
        .unless(
          stages.map(_.agentId) == Chunk(first.agentId, second.agentId) &&
            stages.map(_.result.reply.text) == Chunk("first", "second") &&
            result.state == AgentRunState.Stopped("Model output token budget exhausted") &&
            result.reply.text == "partial" && limits.maxBytes == 1024 && limits.maxEntries == 16
        )
    yield ()

  private def failure(message: String): AgentError = AgentError.InvalidConfiguration(message)

  private def loadManifest: IO[AgentError, Manifest] =
    for
      configured <- System
        .property("weisgnosis.artifact.manifest")
        .mapError(_ => failure("Cannot read artifact manifest configuration"))
      path <- ZIO.fromOption(configured).orElseFail(failure("Artifact manifest is required"))
      raw <- ZIO
        .attemptBlocking(Files.readString(Path.of(path), StandardCharsets.UTF_8))
        .mapError(_ => failure("Cannot read prepared artifact manifest"))
      manifest <- ZIO.fromEither(
        raw.fromJson[Manifest].left.map(_ => failure("Invalid artifact manifest"))
      )
      expected <- System
        .property("weisgnosis.artifact.version")
        .mapError(_ => failure("Cannot read artifact version configuration"))
        .map(_.getOrElse("0.1.1-SNAPSHOT"))
      candidate = "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)-RC[1-9][0-9]*"
      _ <- ZIO
        .fail(failure("Invalid artifact verification version"))
        .unless(
          expected == "0.1.1-SNAPSHOT" || (expected.length <= 64 && expected.matches(candidate))
        )
      _ <- ZIO
        .fail(failure("Unexpected artifact verification version"))
        .unless(manifest.version == expected)
    yield manifest

  /** Hash the actual resolved module JAR, never the source used to prepare the expected manifest. */
  private def verifyJar(
      module: String,
      loaded: Class[?],
      manifest: Manifest
  ): IO[AgentError, Unit] =
    for
      location <- ZIO
        .attempt(
          Option(loaded.getProtectionDomain)
            .flatMap(domain => Option(domain.getCodeSource))
            .map(_.getLocation.toURI)
        )
        .mapError(_ => failure(s"Cannot locate resolved $module artifact"))
      uri <- ZIO.fromOption(location).orElseFail(failure(s"Missing $module code source"))
      observed <- ZIO
        .attemptBlocking:
          val jar   = Path.of(uri)
          val bytes = Files.readAllBytes(jar)
          val digest = MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .iterator
            .map(byte => f"${byte & 0xff}%02x")
            .mkString
          jar.getFileName.toString -> digest
        .mapError(_ => failure(s"Cannot inspect resolved $module JAR"))
      expected <- ZIO
        .fromOption(manifest.sha256.get(module))
        .orElseFail(failure(s"Missing $module hash"))
      exactName = s"weisgnosis-agents-${module}_3-${manifest.version}.jar"
      timestampedName = java.util.regex.Pattern.quote(
        s"weisgnosis-agents-${module}_3-${manifest.version.stripSuffix("-SNAPSHOT")}"
      ) + "-[0-9]{8}\\.[0-9]{6}-[1-9][0-9]*\\.jar"
      validName = observed._1 == exactName || (
        manifest.version.endsWith("-SNAPSHOT") && observed._1.matches(timestampedName)
      )
      _ <- ZIO
        .fail(failure(s"Resolved $module artifact differs from freshly packaged JAR"))
        .unless(validName && observed._2 == expected)
    yield ()

  private def verifyClasspath: IO[AgentError, Unit] =
    for
      configured <- System
        .property("java.class.path")
        .mapError(_ => failure("Cannot read consumer runtime classpath"))
      raw <- ZIO.fromOption(configured).orElseFail(failure("Missing consumer runtime classpath"))
      ownLocation <- ZIO
        .attempt(
          Option(ArtifactSmoke.getClass.getProtectionDomain)
            .flatMap(domain => Option(domain.getCodeSource))
            .map(_.getLocation.toURI)
        )
        .mapError(_ => failure("Cannot locate consumer classes"))
      ownUri <- ZIO.fromOption(ownLocation).orElseFail(failure("Missing consumer code source"))
      valid <- ZIO
        .attemptBlocking:
          val own = Path.of(ownUri).toRealPath()
          val forbidden = Chunk(
            "zio-test_3-",
            "zio-test-sbt_3-",
            "mcp-core-",
            "mcp-json-jackson2-",
            "tomcat-",
            "postgresql-",
            "opentelemetry-sdk-",
            "opentelemetry-exporter-"
          )
          Chunk
            .fromArray(raw.split(Pattern.quote(File.pathSeparator)))
            .forall: entry =>
              val path = Path.of(entry).toRealPath()
              val name = path.getFileName.toString
              if Files.isDirectory(path) then path == own
              else name.endsWith(".jar") && !forbidden.exists(name.startsWith)
        .mapError(_ => failure("Cannot inspect consumer runtime classpath"))
      _ <- ZIO
        .fail(failure("Consumer classpath contains project classes or test dependencies"))
        .unless(valid)
    yield ()

  def run: ZIO[Any, Throwable, Unit] =
    ZIO.scoped:
      for
        manifest <- loadManifest
        _        <- verifyClasspath
        _        <- verifyScriptedClient
        _        <- verifySchemaAdmission
        _        <- verifyStructuredRepair
        _        <- verifyStructuredSchema
        _        <- verifyTemperatureOmission
        _        <- verifyTokenLimitField
        _        <- verifyScopedTool
        _        <- verifyResponsesBoundary
        _        <- verifyUserQuestions
        _        <- verifyProcedure
        _        <- verifyExternalBinding
        _        <- verifyMcpDeadlines
        _        <- verifyReliabilityContracts
        _        <- verifyJar("core", ReActAgent.getClass, manifest)
        _        <- verifyJar("harness", PermissionEngine.getClass, manifest)
        _        <- verifyJar("mcp", McpCallResult.getClass, manifest)
        _        <- verifyJar("storage-postgresql", PostgresVersionedJsonStore.getClass, manifest)
        _        <- verifyJar("otel", AgentTelemetry.getClass, manifest)
        _ <- ZIO.scoped(
          (for
            contexts <- ZIO.service[ContextStorage]
            tracer   <- ZIO.attempt(OpenTelemetry.noop().getTracer("external-consumer"))
            adapter  <- AgentTelemetry.make(tracer, contexts)
            client   <- LlmClient.mock(Chunk(TurnOutput.FinalText(Msg.assistant("traced"))))
            agent <- ReActAgent
              .builder()
              .config(AgentConfig(emptyThinkingRetryLimit = 1))
              .eventDeliveryBudget(com.weisgnosis.agents.core.event.EventDeliveryBudget.default)
              .emptyThinkingRetryLimit(0)
              .model(client)
              .phaseObserver(adapter.phaseObserver)
              .completionObserver(adapter.completionObserver)
              .toolkit(Toolkit.empty.withDispatchObserver(adapter.dispatchObserver))
              .build
            result <- agent.run(Msg.user("trace request"))
            stats  <- adapter.diagnostics
            _ <- ZIO
              .fail(failure("Packaged telemetry integration failed"))
              .unless(
                result.reply.text == "traced" && agent.config.emptyThinkingRetryLimit == 0 && stats.operationFailures == 0
              )
          yield ()).provideSomeLayer[Scope](zio.telemetry.opentelemetry.OpenTelemetry.contextZIO)
        )
        invalidEnvironment <- ZIO
          .scoped(
            McpStdioTransport.open(
              Chunk("must-not-start"),
              environment = Map("invalid=name" -> "private-value")
            )
          )
          .exit
        _ <- ZIO
          .fail(failure("Packaged stdio environment validation failed"))
          .unless(
            invalidEnvironment.causeOption.exists(_.failures.exists:
              case _: AgentError.InvalidConfiguration => true
              case _                                  => false)
          )
        storageAddress  <- ZIO.fromEither(StorageAddress.from(Chunk("consumer", "state"), "key"))
        storageRevision <- ZIO.fromEither(StorageRevision.fromLong(1L))
        usage <- ZIO.fromEither(
          ChatUsage(inputTokens = 1, outputTokens = 2)
            .combine(ChatUsage(inputTokens = 3, outputTokens = 4))
        )
        total   <- ZIO.fromEither(usage.totalTokens)
        checked <- ZIO.fromEither(usage.validated)
        encoded <- ZIO.fromEither(ExactJson.encode(Json.Num(Long.MinValue)))
        event = AgentEvent.ToolResultStartEvent(
          "call",
          "sum",
          metadata = Map("number" -> Json.Num(Long.MinValue))
        )
        wire <- ZIO.fromEither(ExactJson.encode[AgentEvent](event))
        decoded <- ZIO.fromEither(
          wire.fromJson[AgentEvent].left.map(AgentError.InvalidMessage.apply)
        )
        thinkingBudget <- ZIO.fromEither(ThinkingBudget.fromInt(4096))
        configured = LlmConfig(
          "packaged-model",
          parallelToolCalls = Some(ToolCallParallelism.Disabled),
          stopSequences = Some(StopSequences.one("END", "STOP")),
          reasoningEffort = Some(ReasoningEffort.High),
          thinkingBudget = Some(thinkingBudget)
        )
        definition = ToolDef(
          "sum",
          "Deterministic consumer fixture",
          Json.Obj("type" -> Json.Str("object")),
          schemaStrictness = Some(ToolSchemaStrictness.Strict)
        )
        imageRequest <- ZIO.fromEither(
          ChatCompletionsRequest.encode(
            Chunk(
              Msg.assistant(Chunk(ContentBlock.ToolUse("image", "sum", Json.Obj()))),
              Msg.tool(
                Chunk(
                  ContentBlock.ToolResult(
                    "image",
                    Some("sum"),
                    Chunk(ContentBlock.Image(MediaSource.Base64("image/png", "aGVsbG8=")))
                  )
                )
              )
            ),
            Chunk.empty,
            configured,
            toolResultFormat = ToolResultFormat.ImageParts
          )
        )
        request <- ZIO.fromEither(
          ChatCompletionsRequest.encode(Chunk(Msg.user("question")), Chunk(definition), configured)
        )
        ordinaryRequest <- ZIO.fromEither(
          ChatCompletionsRequest.encodeNonStreaming(
            Chunk(Msg.user("question")),
            Chunk(definition),
            configured
          )
        )
        turns <- Ref.make(0)
        client = new LlmClient:
          def streamTurn(
              history: Chunk[Msg],
              tools: Chunk[ToolDef],
              config: LlmConfig,
              eventHub: EventPublisher,
              context: ModelStreamContext
          ): ZIO[Scope, AgentError, TurnOutput] =
            turns
              .getAndUpdate(_ + 1)
              .map:
                case 0 =>
                  TurnOutput.ToolCalls(
                    Msg.assistant(Chunk(ContentBlock.ToolUse("call", "sum", Json.Obj())))
                  )
                case _ => TurnOutput.FinalText(Msg.assistant("5"))
        approvals <- Ref.make(Chunk.empty[String])
        toolkit <- ZIO.fromEither(
          Toolkit.empty.register(
            Tool.make(definition)(_ => ZIO.succeed("5"))
          )
        )
        agent <- ReActAgent
          .builder()
          .model(client)
          .llmConfig(configured)
          .toolkit(toolkit)
          .toolAuthorizer(
            ToolAuthorizer(request =>
              approvals
                .update(_ :+ request.call.name)
                .as(ToolAuthorization.Approved(request.call.input))
            )
          )
          .maxIterations(3)
          .timeout(1.minute)
          .build
        filter    <- ZIO.fromEither(FinalAnswerFilter.make())
        events    <- agent.streamEvents(Msg.user("Use sum")).via(filter).runCollect
        decisions <- approvals.get
        projected <- ZIO.fromEither(
          McpCallResult.read(
            Json.Obj(
              "content" -> Json.Arr(
                Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str("packaged"))
              )
            )
          )
        )
        unavailable <- UnavailablePermissionAuthorizer(ZIO.succeed(PermissionEngine())).authorize(
          ToolAuthorizationRequest(
            "packaged-approval",
            ModelStreamContext("packaged-reply", "packaged-model"),
            ContentBlock.ToolUse("packaged-call", "read", Json.Obj())
          )
        )
        permission = PermissionEngine().evaluate(
          "read",
          Json.Obj(),
          Some(PermissionBehavior.Allow),
          Some(PermissionBehavior.Deny)
        )
        _ <- ZIO
          .fail(failure("Packaged public API consumer contract failed"))
          .unless(
            encoded == "-9223372036854775808" && decoded == event &&
              storageAddress.toJson.fromJson[StorageAddress] == Right(storageAddress) &&
              storageRevision.value == 1L && total == 10 && checked == usage &&
              ChatUsage(inputTokens = Int.MaxValue).combine(ChatUsage(inputTokens = 1)).isLeft &&
              configured.toJson.fromJson[LlmConfig] == Right(configured) &&
              CompletionEnvelopeProfile.DeltasWithFullMessageSummary.toJson
                .fromJson[CompletionEnvelopeProfile] == Right(
                CompletionEnvelopeProfile.DeltasWithFullMessageSummary
              ) &&
              configured.stopSequences.exists(_.values == Chunk("END", "STOP")) &&
              ordinaryRequest.get("stream").contains(Json.Bool(false)) &&
              ordinaryRequest.get("stream_options").isEmpty &&
              ordinaryRequest.fields.filterNot(_._1 == "stream") == request.fields
                .filterNot(field => field._1 == "stream" || field._1 == "stream_options") &&
              request.get("stop").contains(Json.Arr(Json.Str("END"), Json.Str("STOP"))) &&
              request.get("reasoning_effort").contains(Json.Str("high")) &&
              request.get("thinking_budget").contains(Json.Num(4096)) &&
              imageRequest
                .get("messages")
                .flatMap(_.asArray)
                .flatMap(_.lastOption)
                .contains(
                  Json.Obj(
                    "role"         -> Json.Str("tool"),
                    "tool_call_id" -> Json.Str("image"),
                    "content" -> Json.Arr(
                      Json.Obj(
                        "type"      -> Json.Str("image_url"),
                        "image_url" -> Json.Obj("url" -> Json.Str("data:image/png;base64,aGVsbG8="))
                      )
                    )
                  )
                ) &&
              request
                .get("tools")
                .flatMap(_.asArray)
                .exists(entries =>
                  entries.size == 1 && entries.headOption
                    .flatMap(_.asObject)
                    .flatMap(_.get("function"))
                    .flatMap(_.asObject)
                    .exists(function =>
                      function.get("name").contains(Json.Str(definition.name)) &&
                        function.get("strict").contains(Json.Bool(true)) &&
                        function.get("parameters").contains(definition.parametersSchema)
                    )
                ) &&
              decisions == Chunk(
                "sum"
              ) &&
              events.collect { case result: AgentEvent.AgentResultEvent =>
                result.result.text
              } == Chunk("5") &&
              unavailable == ToolAuthorization
                .Denied("Tool approval is unavailable: no host approval channel is configured") &&
              permission == PermissionBehavior.Deny && projected.outcome == McpCallOutcome.Success &&
              projected.content.map(_.block) == Chunk(McpContentBlock.Text("packaged"))
          )
        _ <- Console.printLine("Fresh packaged artifacts passed: external public API consumer")
      yield ()

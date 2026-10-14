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
package com.weisgnosis.agents.examples

import com.weisgnosis.agents.core.client.{GeminiLlmClient, LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{EventPublisher, ModelStreamContext}
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolResultState}
import com.weisgnosis.agents.core.tool.*
import com.weisgnosis.agents.harness.eviction.{EvictionConfig, StoredToolResult, ToolResultStore}
import com.weisgnosis.agents.harness.memory.CompactorConfig
import com.weisgnosis.agents.harness.transcript.*
import com.weisgnosis.agents.harness.storage.*
import com.weisgnosis.agents.mcp.client.McpClient
import com.weisgnosis.agents.mcp.protocol.*
import com.weisgnosis.agents.mcp.transport.McpTransport
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

object HostAssemblySpec extends ZIOSpecDefault:
  private enum StopCase:
    case Failure, Cancellation

  private enum AssemblyStage:
    case Catalog, Mcp

  private val schema: Json.Obj = Json.Obj("type" -> Json.Str("object"))
  private val settings: HostAssembly.Settings = HostAssembly.Settings(
    "example",
    LlmConfig("scripted"),
    CompactorConfig(triggerTokens = 50, keepTokens = 20, preserveTailMessages = 1),
    EvictionConfig(),
    AnonymousTranscriptPolicy.RecordEachSnapshot
  )

  private def peer(
      text: String,
      released: UIO[Unit],
      resource: Option[Json.Obj] = None
  ): ZIO[Scope, AgentError, McpClient] =
    for
      _     <- ZIO.acquireRelease(ZIO.unit)(_ => released)
      inbox <- ZIO.acquireRelease(Queue.unbounded[McpMessage])(_.shutdown)
      transport = new McpTransport:
        def incoming: ZStream[Any, AgentError, McpMessage] = ZStream.fromQueue(inbox)
        def send(message: McpMessage): ZIO[Scope, AgentError, Unit] = message match
          case McpMessage.Request(id, method, _) =>
            val payload = method match
              case "initialize" =>
                Right(
                  Json.Obj(
                    "protocolVersion" -> Json.Str(McpInitialization.protocolVersion),
                    "serverInfo" -> Json
                      .Obj("name" -> Json.Str("peer"), "version" -> Json.Str("1")),
                    "capabilities" -> Json.Obj("tools" -> Json.Obj())
                  )
                )
              case "tools/list" =>
                Right(
                  Json.Obj(
                    "tools" -> Json.Arr(
                      Chunk("report", "hidden").map(name =>
                        Json.Obj(
                          "name"        -> Json.Str(name),
                          "inputSchema" -> schema
                        )
                      )
                    )
                  )
                )
              case "tools/call" =>
                Right(
                  Json.Obj(
                    "content" -> Json.Arr(
                      Chunk(Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str(text))) ++
                        Chunk.fromIterable(resource)
                    )
                  )
                )
              case _ => Left(AgentError.McpError("Unexpected test method"))
            ZIO
              .fromEither(payload)
              .flatMap(value => inbox.offer(McpMessage.Success(id, value)).unit)
          case _: McpMessage.Notification => ZIO.unit
          case _ => ZIO.fail(AgentError.McpError("Unexpected test message"))
      client <- McpClient.open(transport, McpImplementation("example-test", "1"))
    yield client

  private def registration(acquire: ZIO[Scope, AgentError, McpClient]): HostAssembly.Registration =
    HostAssembly.Registration(
      "peer",
      acquire,
      1,
      remote =>
        Right(
          Option.when(remote.name == "report")(
            ToolDef("report", "Explicit host selection", schema, isReadOnly = true)
          )
        )
    )

  private def resultStore(state: Ref[Map[String, StoredToolResult]]): ToolResultStore =
    new ToolResultStore:
      def persist(key: String, content: String): IO[AgentError, String] =
        val path = "test://results/" + key
        state.update(_.updated(key, StoredToolResult(content, path))).as(path)
      def resolve(key: String): IO[AgentError, Option[StoredToolResult]] = state.get.map(_.get(key))

  private def jsonStore: UIO[VersionedJsonStore] =
    Ref
      .make(Map.empty[StorageAddress, VersionedJson])
      .map: state =>
        new VersionedJsonStore:
          def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
            state.get.map(_.get(address))
          def compareAndSet(
              address: StorageAddress,
              expected: ExpectedRevision,
              value: Json
          ): IO[AgentError, ConditionalWrite] =
            state
              .modify: current =>
                val existing = current.get(address)
                val matches = expected match
                  case ExpectedRevision.Absent => existing.isEmpty
                  case ExpectedRevision.AtRevision(revision) =>
                    existing.exists(_.revision == revision)
                if !matches then (Right(ConditionalWrite.Conflict), current)
                else
                  StorageRevision.fromLong(existing.fold(1L)(_.revision.value + 1L)) match
                    case Left(error) => (Left(error), current)
                    case Right(revision) =>
                      (
                        Right(ConditionalWrite.Applied(revision)),
                        current.updated(address, VersionedJson(value, revision))
                      )
              .flatMap(ZIO.fromEither(_))

  def spec: Spec[TestEnvironment, Any] = suite("HostAssemblySpec")(
    test("embedded MCP resources reach the next native HTTP request with canonical content intact"):
      val resource = Json.Obj(
        "type" -> Json.Str("resource"),
        "resource" -> Json.Obj(
          "uri"      -> Json.Str("test://report"),
          "mimeType" -> Json.Str("text/plain"),
          "text"     -> Json.Str("resource evidence")
        )
      )
      val proposal = Json.Obj(
        "functionCall" -> Json.Obj(
          "id"   -> Json.Str("call"),
          "name" -> Json.Str("report"),
          "args" -> Json.Obj()
        )
      )
      def wire(part: Json): String = "data: " + JsonBoundary.render(
        Json.Obj(
          "candidates" -> Json.Arr(
            Json.Obj(
              "index"        -> Json.Num(0),
              "content"      -> Json.Obj("role" -> Json.Str("model"), "parts" -> Json.Arr(part)),
              "finishReason" -> Json.Str("STOP")
            )
          )
        )
      ) + "\n\n"
      ZIO
        .scoped:
          for
            requests <- Ref.make(Chunk.empty[String])
            port <- Server.install(Routes(Method.POST / "messages" -> handler { (request: Request) =>
              request.body.asString
                .orElseSucceed("")
                .flatMap(body => requests.modify(previous => (previous.size, previous :+ body)))
                .map(index =>
                  Response(
                    headers = Headers("Content-Type" -> "text/event-stream"),
                    body = Body.fromString(
                      wire(if index == 0 then proposal else Json.Obj("text" -> Json.Str("done")))
                    )
                  )
                )
            }))
            http       <- ZIO.service[Client]
            model      <- GeminiLlmClient.make(http)
            stored     <- Ref.make(Map.empty[String, StoredToolResult])
            transcript <- TranscriptStore.inMemory
            assembly <- HostAssembly.open(
              settings.copy(
                model = LlmConfig("fixture", endpoint = s"http://127.0.0.1:$port/messages"),
                compaction = CompactorConfig(triggerTokens = 10000, keepTokens = 1000)
              ),
              HostAssembly.Dependencies(
                model,
                ToolAuthorizer.allow,
                resultStore(stored),
                HostAssembly.TranscriptSource.Provided(transcript),
                _ => ZIO.succeed("unused"),
                Toolkit.empty
              ),
              Chunk(registration(peer("report evidence", ZIO.unit, Some(resource))))
            )
            outcome <- assembly.agent.run(Msg.user("report")).either
            sent    <- requests.get
          yield assertTrue(
            outcome.exists(_.reply.text == "done"),
            outcome.exists(_.history.flatMap(_.toolResults).flatMap(_.output).exists {
              case ContentBlock.Custom("mcp/resource", payload) =>
                payload.asObject.exists(_.get("uri").contains(Json.Str("test://report")))
              case _ => false
            }),
            sent.size == 2,
            sent.lastOption.exists(body =>
              body.contains("Custom tool content:") &&
                body.contains("mcp/resource") && body.contains("test://report") && body.contains(
                  "resource evidence"
                )
            )
          )
        .provide(Client.default, Server.defaultWithPort(0))
    ,
    test(
      "registered layouts record completed turns without granting registration or execution authority"
    ) {
      ZIO
        .foreach(Chunk(TranscriptLayout.Aggregate, TranscriptLayout.Segmented)): layout =>
          ZIO.scoped:
            for
              backend    <- jsonStore
              location   <- ZIO.fromEither(StorageAddress.from(Chunk("example"), "transcript"))
              index      <- ZIO.fromEither(StorageAddress.from(Chunk("example"), "catalog"))
              id         <- ZIO.fromEither(TranscriptTargetId.fromString("session"))
              descriptor <- ZIO.fromEither(TranscriptSession.make(id, location, layout))
              catalog    <- ZIO.fromEither(VersionedTranscriptSessionCatalog.make(backend, index))
              stored     <- Ref.make(Map.empty[String, StoredToolResult])
              calls      <- Ref.make(0)
              model = new LlmClient:
                def streamTurn(
                    history: Chunk[Msg],
                    tools: Chunk[ToolDef],
                    config: LlmConfig,
                    eventHub: EventPublisher,
                    context: ModelStreamContext
                ): ZIO[Scope, AgentError, TurnOutput] =
                  calls
                    .update(_ + 1)
                    .as(
                      TurnOutput.FinalText(
                        Msg.assistant(Chunk(ContentBlock.Text("recorded")), id = Some("answer"))
                      )
                    )
              selection = HostAssembly.TranscriptSource
                .Registered(backend, catalog, id, TranscriptSegmentLimits.default)
              dependencies = HostAssembly.Dependencies(
                model,
                ToolAuthorizer.allow,
                resultStore(stored),
                selection,
                _ => ZIO.succeed("unused"),
                Toolkit.empty
              )
              acquisitions <- Ref.make(0)
              attempted = Chunk(
                registration(acquisitions.update(_ + 1) *> peer("unused", ZIO.unit))
              )
              missing <- HostAssembly.open(settings, dependencies, attempted).either
              duplicateCatalog = new TranscriptSessionCatalog:
                def sessions: IO[AgentError, Chunk[TranscriptSession]] =
                  ZIO.succeed(Chunk(descriptor, descriptor))
                def register(session: TranscriptSession): IO[AgentError, TranscriptRegistration] =
                  ZIO.fail(AgentError.StorageError("Unexpected registration"))
              duplicate <- HostAssembly
                .open(
                  settings,
                  dependencies.copy(
                    transcript = HostAssembly.TranscriptSource
                      .Registered(backend, duplicateCatalog, id, TranscriptSegmentLimits.default)
                  ),
                  attempted
                )
                .either
              acquired      <- acquisitions.get
              empty         <- catalog.sessions
              _             <- catalog.register(descriptor)
              bindingBefore <- backend.read(index)
              assembly      <- HostAssembly.open(settings, dependencies, Chunk.empty)
              result <- assembly.agent
                .run(Chunk(Msg.user(Chunk(ContentBlock.Text("start")), id = Some("input"))))
              reopenedCatalog <- ZIO.fromEither(
                VersionedTranscriptSessionCatalog.make(backend, index)
              )
              reopened <- HostAssembly.TranscriptSource
                .Registered(backend, reopenedCatalog, id, TranscriptSegmentLimits.default)
                .resolve
              entries      <- reopened.entries
              bindingAfter <- backend.read(index)
              record       <- backend.read(location)
              _ <- ZIO.foreachDiscard(record)(value =>
                backend.compareAndSet(
                  location,
                  ExpectedRevision.AtRevision(value.revision),
                  Json.Obj("schemaVersion" -> Json.Num(999))
                )
              )
              corrupted <- HostAssembly
                .open(settings, dependencies, Chunk.empty)
                .flatMap(
                  _.agent.run(Chunk(Msg.user(Chunk(ContentBlock.Text("next")), id = Some("next"))))
                )
                .exit
              invoked <- calls.get
            yield assertTrue(
              missing.left.exists(_.isInstanceOf[AgentError.InvalidConfiguration]),
              duplicate.left.exists(_.isInstanceOf[AgentError.InvalidConfiguration]),
              acquired == 0,
              empty.isEmpty,
              bindingBefore == bindingAfter,
              result.reply.text == "recorded",
              entries.map(_.payload) == Chunk(
                TranscriptPayload.Message(
                  com.weisgnosis.agents.core.model.Role.User,
                  "start",
                  Chunk(TranscriptBlockType.Text)
                ),
                TranscriptPayload.Message(
                  com.weisgnosis.agents.core.model.Role.Assistant,
                  "recorded",
                  Chunk(TranscriptBlockType.Text)
                )
              ),
              entries.lastOption.flatMap(_.parentId) == entries.headOption.map(_.id),
              record.nonEmpty,
              corrupted.causeOption.exists(
                _.failureOption.exists(_.isInstanceOf[AgentError.CompletionObservationError])
              ),
              invoked == 2
            )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    },
    test(
      "selected MCP tools use host authority, prepared history, retrievable eviction and transcript hooks"
    ) {
      ZIO.scoped:
        for
          stored     <- Ref.make(Map.empty[String, StoredToolResult])
          snapshots  <- Ref.make(Chunk.empty[(Chunk[Msg], Chunk[ToolDef])])
          summaries  <- Ref.make(Chunk.empty[Chunk[Msg]])
          authorized <- Ref.make(Chunk.empty[String])
          released   <- Ref.make(false)
          transcript <- TranscriptStore.inMemory
          model = new LlmClient:
            def streamTurn(
                history: Chunk[Msg],
                tools: Chunk[ToolDef],
                config: LlmConfig,
                eventHub: EventPublisher,
                context: ModelStreamContext
            ): ZIO[Scope, AgentError, TurnOutput] =
              snapshots
                .modify(current => (current.size, current :+ (history -> tools)))
                .flatMap:
                  case 0 =>
                    ZIO.succeed(
                      TurnOutput.ToolCalls(
                        Msg.assistant(
                          Chunk(
                            ContentBlock.ToolUse("call", "report", Json.Obj())
                          )
                        )
                      )
                    )
                  case 1 =>
                    ZIO
                      .fromOption(
                        history
                          .flatMap(_.toolResults)
                          .flatMap(_.metadata.get("eviction_path"))
                          .collect { case Json.Str(path) => path }
                          .headOption
                      )
                      .orElseFail(AgentError.InvalidMessage("Missing committed eviction path"))
                      .map(path =>
                        TurnOutput.ToolCalls(
                          Msg.assistant(
                            Chunk(
                              ContentBlock
                                .ToolUse("inspect", "read_file", Json.Obj("path" -> Json.Str(path)))
                            )
                          )
                        )
                      )
                  case _ => ZIO.succeed(TurnOutput.FinalText(Msg.assistant("done")))
          read = Tool.make(
            ToolDef("read_file", "Retrieve persisted output", schema, isReadOnly = true)
          )(input =>
            val path = input.asObject.flatMap(_.get("path")).flatMap(_.asString)
            stored.get.flatMap(values =>
              ZIO
                .fromOption(values.values.find(value => path.contains(value.path)))
                .orElseFail(AgentError.ToolExecutionError("read_file", "Unknown result path"))
                .map(_.content)
            )
          )
          tools <- ZIO.fromEither(Toolkit.empty.register(read))
          dependencies = HostAssembly.Dependencies(
            model,
            ToolAuthorizer(request =>
              authorized
                .update(_ :+ request.call.name)
                .as(ToolAuthorization.Approved(request.call.input))
            ),
            resultStore(stored),
            HostAssembly.TranscriptSource.Provided(transcript),
            history => summaries.update(_ :+ history).as("concise summary"),
            tools
          )
          owner <- ZIO.acquireReleaseExit(Scope.make)((scope, exit) => scope.close(exit))
          assembly <- owner.extend(
            HostAssembly.open(
              settings,
              dependencies,
              Chunk(registration(peer("x" * 80001, released.set(true))))
            )
          )
          result <- owner.extend(
            assembly.agent.run(
              Chunk(Msg.user("old" * 100), Msg.assistant("prior"), Msg.user("start"))
            )
          )
          observed  <- snapshots.get
          flushed   <- summaries.get
          entries   <- transcript.entries
          decisions <- authorized.get
          originals <- stored.get
          preview = result.history.flatMap(_.content.collect {
            case value: ContentBlock.ToolResult => value
          })
          evicted = observed
            .lift(1)
            .flatMap(snapshot => snapshot._1.flatMap(_.toolResults).find(_.id == "call"))
          _      <- owner.close(Exit.unit)
          closed <- released.get
        yield assertTrue(
          result.reply.text == "done",
          observed.size == 3,
          observed.forall(_._2.map(_.name).toSet == Set("report", "read_file")),
          flushed.exists(_.exists(_.text.contains("old"))),
          evicted.exists(value =>
            value.state == ToolResultState.Success && value.text.length < 80001 &&
              value.metadata.contains("eviction_cache_key") && originals.values.exists(stored =>
                value.metadata.get("eviction_path").contains(Json.Str(stored.path))
              )
          ),
          originals.size == 1,
          decisions == Chunk("report", "read_file"),
          preview.exists(value =>
            value.id == "inspect" && value.state == ToolResultState.Success && value.text == "x" * 80001
          ),
          observed.lastOption.exists(snapshot =>
            snapshot._1
              .flatMap(_.toolResults)
              .exists(value => value.id == "inspect" && value.text == "x" * 80001)
          ),
          entries.exists(_.payload.isInstanceOf[TranscriptPayload.ToolUse]),
          entries.exists(_.payload match
            case TranscriptPayload.Message(_, text, _) => text == "done"
            case _                                     => false),
          closed
        )
    },
    test(
      "failed and cancelled assembly join owned cleanup before returning even while caller Scope stays open"
    ) {
      ZIO
        .foreach(for
          stage <- Chunk(AssemblyStage.Catalog, AssemblyStage.Mcp)
          stop  <- Chunk(StopCase.Failure, StopCase.Cancellation)
        yield stage -> stop): (stage, stop) =>
          ZIO.scoped:
            for
              stored         <- Ref.make(Map.empty[String, StoredToolResult])
              transcript     <- TranscriptStore.inMemory
              model          <- LlmClient.mock(Chunk(TurnOutput.FinalText(Msg.assistant("unused"))))
              firstReleased  <- Ref.make(false)
              entered        <- Promise.make[Nothing, Unit]
              cleanupStarted <- Promise.make[Nothing, Unit]
              releaseCleanup <- Promise.make[Nothing, Unit]
              acquisitions   <- Ref.make(0)
              backend        <- jsonStore
              id             <- ZIO.fromEither(TranscriptTargetId.fromString("pending"))
              expectedFailure = AgentError.StorageError("selected acquisition failed")
              cleanup         = cleanupStarted.succeed(()).unit *> releaseCleanup.await
              pending = entered.succeed(()) *> (stop match
                case StopCase.Cancellation => ZIO.never
                case StopCase.Failure      => ZIO.fail(expectedFailure))
              second = ZIO.acquireRelease(ZIO.unit)(_ => cleanup) *> pending
              catalog = new TranscriptSessionCatalog:
                def sessions: IO[AgentError, Chunk[TranscriptSession]] = pending.ensuring(cleanup)
                def register(session: TranscriptSession): IO[AgentError, TranscriptRegistration] =
                  ZIO.fail(AgentError.StorageError("Unexpected registration"))
              selection = stage match
                case AssemblyStage.Mcp => HostAssembly.TranscriptSource.Provided(transcript)
                case AssemblyStage.Catalog =>
                  HostAssembly.TranscriptSource
                    .Registered(backend, catalog, id, TranscriptSegmentLimits.default)
              dependencies = HostAssembly.Dependencies(
                model,
                ToolAuthorizer.allow,
                resultStore(stored),
                selection,
                _ => ZIO.succeed("unused"),
                Toolkit.empty
              )
              running <- HostAssembly
                .open(
                  settings,
                  dependencies,
                  Chunk(
                    registration(
                      acquisitions.update(_ + 1) *> peer("unused", firstReleased.set(true))
                    ),
                    registration(second).copy(name = "second")
                  )
                )
                .forkScoped
              _ <- entered.await
              stopping <- (stop match
                case StopCase.Cancellation => running.interrupt.unit
                case StopCase.Failure      => ZIO.unit
              ).forkScoped
              _        <- cleanupStarted.await
              before   <- running.poll
              _        <- releaseCleanup.succeed(())
              outcome  <- running.await
              _        <- stopping.join
              released <- firstReleased.get
              acquired <- acquisitions.get
            yield assertTrue(
              before.isEmpty,
              released == (stage == AssemblyStage.Mcp),
              acquired == (if stage == AssemblyStage.Mcp then 1 else 0),
              stop match
                case StopCase.Cancellation => outcome.isInterrupted
                case StopCase.Failure =>
                  outcome.causeOption
                    .exists(_.failureOption.contains(expectedFailure))
            )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    }
  )

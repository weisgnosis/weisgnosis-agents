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

import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{AgentEvent, ModelStreamContext}
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolResultState}
import com.weisgnosis.agents.core.tool.*
import com.weisgnosis.agents.harness.eviction.{EvictionConfig, StoredToolResult, ToolResultStore}
import com.weisgnosis.agents.harness.memory.CompactorConfig
import com.weisgnosis.agents.harness.transcript.*
import com.weisgnosis.agents.mcp.client.McpClient
import com.weisgnosis.agents.mcp.protocol.*
import com.weisgnosis.agents.mcp.transport.McpTransport
import zio.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

object HostAssemblySpec extends ZIOSpecDefault:
  private enum StopCase:
    case Failure, Cancellation

  private val schema: Json.Obj = Json.Obj("type" -> Json.Str("object"))
  private val settings: HostAssembly.Settings = HostAssembly.Settings(
    "example",
    LlmConfig("scripted"),
    CompactorConfig(triggerTokens = 50, keepTokens = 20, preserveTailMessages = 1),
    EvictionConfig(),
    AnonymousTranscriptPolicy.RecordEachSnapshot
  )

  private def peer(text: String, released: UIO[Unit]): ZIO[Scope, AgentError, McpClient] =
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
                      Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str(text))
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

  def spec: Spec[TestEnvironment, Any] = suite("HostAssemblySpec")(
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
                eventHub: Hub[AgentEvent],
                context: ModelStreamContext
            ): ZIO[Scope, AgentError, TurnOutput] =
              snapshots
                .modify(current => (current.size, current :+ (history -> tools)))
                .map:
                  case 0 =>
                    TurnOutput.ToolCalls(
                      Msg.assistant(
                        Chunk(
                          ContentBlock.ToolUse("call", "report", Json.Obj())
                        )
                      )
                    )
                  case _ => TurnOutput.FinalText(Msg.assistant("done"))
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
            transcript,
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
          retrieval <- ZIO.foreach(originals.values)(value =>
            tools.executeSafe("read_file", Json.Obj("path" -> Json.Str(value.path)))
          )
          _      <- owner.close(Exit.unit)
          closed <- released.get
        yield assertTrue(
          result.reply.text == "done",
          observed.size == 2,
          observed.forall(_._2.map(_.name).toSet == Set("report", "read_file")),
          flushed.exists(_.exists(_.text.contains("old"))),
          preview.exists(value =>
            value.text.length < 80001 && value.metadata.contains("eviction_cache_key")
          ),
          observed.lastOption.exists(snapshot =>
            snapshot._1
              .flatMap(_.content)
              .exists:
                case value: ContentBlock.ToolResult => preview.contains(value)
                case _                              => false
          ),
          originals.size == 1,
          retrieval.forall(value =>
            value.state == ToolResultState.Success && value.output == "x" * 80001
          ),
          decisions == Chunk("report"),
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
        .foreach(Chunk(StopCase.Failure, StopCase.Cancellation)): stop =>
          ZIO.scoped:
            for
              stored         <- Ref.make(Map.empty[String, StoredToolResult])
              transcript     <- TranscriptStore.inMemory
              model          <- LlmClient.mock(Chunk(TurnOutput.FinalText(Msg.assistant("unused"))))
              firstReleased  <- Ref.make(false)
              entered        <- Promise.make[Nothing, Unit]
              cleanupStarted <- Promise.make[Nothing, Unit]
              releaseCleanup <- Promise.make[Nothing, Unit]
              second = ZIO.acquireRelease(ZIO.unit)(_ =>
                cleanupStarted.succeed(()).unit *> releaseCleanup.await
              ) *>
                entered.succeed(()) *> (stop match
                  case StopCase.Cancellation => ZIO.never
                  case StopCase.Failure      => ZIO.fail(AgentError.McpError("acquisition failed")))
              dependencies = HostAssembly.Dependencies(
                model,
                ToolAuthorizer.allow,
                resultStore(stored),
                transcript,
                _ => ZIO.succeed("unused"),
                Toolkit.empty
              )
              running <- HostAssembly
                .open(
                  settings,
                  dependencies,
                  Chunk(
                    registration(peer("unused", firstReleased.set(true))),
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
            yield assertTrue(
              before.isEmpty,
              released,
              stop match
                case StopCase.Cancellation => outcome.isInterrupted
                case StopCase.Failure =>
                  outcome.causeOption
                    .exists(_.failureOption.contains(AgentError.McpError("acquisition failed")))
            )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    }
  )

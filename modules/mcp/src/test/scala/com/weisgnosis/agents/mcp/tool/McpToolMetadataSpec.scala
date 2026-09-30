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
package com.weisgnosis.agents.mcp.tool

import com.weisgnosis.agents.core.agent.{AgentConfig, ReActAgent, ToolExecution}
import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolResultState}
import com.weisgnosis.agents.core.tool.{ToolDef, Toolkit}
import com.weisgnosis.agents.mcp.client.{McpClient, McpClientManager}
import com.weisgnosis.agents.mcp.protocol.*
import com.weisgnosis.agents.mcp.transport.McpTransport
import zio.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

object McpToolMetadataSpec extends ZIOSpecDefault:
  private val definition: ToolDef = ToolDef(
    "local",
    "Host tool",
    Json.Obj("type" -> Json.Str("object"))
  )
  private val userMeta: Json.Obj = Json.Obj(
    "trace"                    -> Json.Str("host-trace"),
    "nested"                   -> Json.Obj("value" -> Json.Null),
    "io.agentscope/toolCallId" -> Json.Str("host-value")
  )

  final private case class Peer(client: McpClient, requests: Ref[Chunk[McpMessage.Request]])

  private def peer: ZIO[Scope, AgentError, Peer] =
    for
      inbox    <- ZIO.acquireRelease(Queue.unbounded[McpMessage])(_.shutdown)
      requests <- Ref.make(Chunk.empty[McpMessage.Request])
      transport = new McpTransport:
        def incoming: ZStream[Any, AgentError, McpMessage] = ZStream.fromQueue(inbox)
        def send(message: McpMessage): ZIO[Scope, AgentError, Unit] = message match
          case request @ McpMessage.Request(id, method, _) =>
            val result = method match
              case "initialize" =>
                Json.Obj(
                  "protocolVersion" -> Json.Str(McpInitialization.protocolVersion),
                  "serverInfo" -> Json.Obj("name" -> Json.Str("peer"), "version" -> Json.Str("1")),
                  "capabilities" -> Json.Obj("tools" -> Json.Obj())
                )
              case "tools/list" =>
                Json.Obj(
                  "tools" -> Json.Arr(
                    Json.Obj(
                      "name"        -> Json.Str("remote"),
                      "inputSchema" -> definition.parametersSchema
                    )
                  )
                )
              case _ =>
                Json.Obj(
                  "content" -> Json.Arr(
                    Json.Obj(
                      "type" -> Json.Str("text"),
                      "text" -> Json.Str("ok")
                    )
                  )
                )
            requests.update(_ :+ request) *> inbox.offer(McpMessage.Success(id, result)).unit
          case _: McpMessage.Notification => ZIO.unit
          case _ => ZIO.fail(AgentError.McpError("Unexpected metadata fixture message"))
      client <- McpClient.open(transport, McpImplementation("client", "1"))
    yield Peer(client, requests)

  private def metadata(request: McpMessage.Request): Option[Json] =
    request.params.flatMap(_.get("_meta"))

  def spec: Spec[TestEnvironment, Any] = suite("McpToolMetadataSpec")(
    test(
      "registered tools resolve host metadata with actual occurrence identity outside model arguments"
    ) {
      ZIO.scoped:
        for
          server     <- peer
          manager    <- McpClientManager.make()
          identities <- Ref.make(Chunk.empty[McpToolIdentity])
          trace      <- Ref.make(userMeta)
          _ <- manager.registerBindings(
            "peer",
            ZIO.succeed(server.client),
            1,
            _ =>
              Right(
                Some(
                  McpToolBinding(
                    definition,
                    McpToolMetadata.Propagate(identity =>
                      identities.update(_ :+ identity) *> trace.get
                    )
                  )
                )
              )
          )
          tools  <- manager.toolkit
          before <- identities.get
          input = Json.Obj("_meta" -> Json.Obj("trace" -> Json.Str("model-value")))
          call  = ContentBlock.ToolUse("repeated", "local", input)
          model <- LlmClient.mock(
            Chunk(
              TurnOutput.ToolCalls(Msg.assistant(Chunk(call, call))),
              TurnOutput.FinalText(Msg.assistant("done"))
            )
          )
          agent <- ReActAgent.make(
            config = AgentConfig(toolExecution = ToolExecution.Sequential),
            client = model,
            toolkit = tools
          )
          _        <- agent.run(Msg.user("invoke"))
          actual   <- identities.get
          requests <- server.requests.get.map(_.filter(_.method == "tools/call"))
          original <- trace.get
          active = actual.collect { case value: McpToolIdentity.Active => value }
          expected = Json.Obj(
            userMeta.fields.filterNot(_._1 == "io.agentscope/toolCallId") :+
              ("io.agentscope/toolCallId" -> Json.Str("repeated"))
          )
        yield assertTrue(
          before.isEmpty,
          active.size == 2,
          active.map(_.callOrdinal) == Chunk(0, 1),
          active.map(_.callId) == Chunk("repeated", "repeated"),
          active.forall(value => value.replyId.nonEmpty && value.requestId.nonEmpty),
          active.map(value => (value.replyId, value.requestId)).distinct.size == 1,
          requests.map(metadata) == Chunk.fill(2)(Some(expected)),
          requests.forall(_.params.flatMap(_.get("arguments")).contains(input)),
          requests.forall(_.params.flatMap(_.get("name")).contains(Json.Str("remote"))),
          tools.definitions == Chunk(definition),
          original == userMeta
        )
    },
    test(
      "omission skips resolver effects while live snapshots and explicit client metadata stay distinct"
    ) {
      ZIO.scoped:
        for
          server   <- peer
          resolved <- Ref.make(Chunk.empty[McpToolIdentity])
          policy = McpToolMetadata.Propagate(identity =>
            resolved.update(_ :+ identity).as(userMeta)
          )
          bound = McpTool.bind(server.client, "remote", definition, policy)
          _ <- bound.execute(Json.Obj())
          _ <- server.client.setToolMetadataPropagation(McpToolMetadataPropagation.Omit)
          _ <- bound.execute(Json.Obj())
          _ <- server.client.setToolMetadataPropagation(McpToolMetadataPropagation.Allow)
          _ <- McpTool.bind(server.client, "remote", definition).execute(Json.Obj())
          _ <- McpTool
            .bind(
              server.client,
              "remote",
              definition,
              McpToolMetadata.Propagate(_ => ZIO.succeed(Json.Obj()))
            )
            .execute(Json.Obj())
          entered <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          held = McpTool.bind(
            server.client,
            "remote",
            definition,
            McpToolMetadata.Propagate(_ => entered.succeed(()) *> release.await.as(userMeta))
          )
          snapshot <- ZIO.scoped {
            (for
              running <- held.execute(Json.Obj()).forkScoped
              _       <- entered.await
              _       <- server.client.setToolMetadataPropagation(McpToolMetadataPropagation.Omit)
              _       <- release.succeed(())
              result  <- running.join
            yield result).ensuring(release.succeed(()).unit)
          }
          explicit = Json.Obj("explicit" -> Json.Bool(true))
          _           <- server.client.callTool("remote", meta = Some(explicit))
          _           <- server.client.listToolsPage(meta = Some(explicit))
          requests    <- server.requests.get.map(_.drop(1))
          invocations <- resolved.get
        yield assertTrue(
          requests.map(metadata) == Chunk(
            Some(userMeta),
            None,
            None,
            Some(Json.Obj()),
            Some(userMeta),
            Some(explicit),
            Some(explicit)
          ),
          invocations == Chunk(McpToolIdentity.Detached),
          snapshot.outcome == McpCallOutcome.Success
        )
    },
    test(
      "resolver failure and malformed metadata send nothing, and cancellation joins failing cleanup"
    ) {
      ZIO.scoped:
        for
          server <- peer
          failure = AgentError.McpError("Host metadata unavailable")
          failed = McpTool.bind(
            server.client,
            "remote",
            definition,
            McpToolMetadata.Propagate(_ => ZIO.fail(failure))
          )
          direct      <- failed.execute(Json.Obj()).exit
          observation <- Toolkit(Map("local" -> failed)).executeSafe("local", Json.Obj())
          malformed = McpTool.bind(
            server.client,
            "remote",
            definition,
            McpToolMetadata.Propagate(_ =>
              ZIO.succeed(
                Json.Obj(
                  "nested" -> Json.Obj("same" -> Json.Num(1), "same" -> Json.Num(2))
                )
              )
            )
          )
          invalid  <- Toolkit(Map("local" -> malformed)).executeSafe("local", Json.Obj())
          entered  <- Promise.make[Nothing, Unit]
          cleaning <- Promise.make[Nothing, Unit]
          release  <- Promise.make[Nothing, Unit]
          defect = new IllegalStateException("Metadata cleanup defect")
          cancelled = McpTool.bind(
            server.client,
            "remote",
            definition,
            McpToolMetadata.Propagate(_ =>
              (entered.succeed(()) *> ZIO.never).ensuring(
                cleaning.succeed(()) *> release.await *> ZIO.die(defect)
              )
            )
          )
          cancellation <- ZIO.scoped:
            (for
              running <- Toolkit(Map("local" -> cancelled))
                .executeSafe("local", Json.Obj())
                .forkScoped
              _        <- entered.await
              stopping <- running.interrupt.forkScoped
              _        <- cleaning.await
              pending  <- stopping.poll
              _        <- release.succeed(())
              exit     <- stopping.join
            yield (pending, exit)).ensuring(release.succeed(()).unit)
          pending = cancellation._1
          exit    = cancellation._2
          requests <- server.requests.get
        yield assertTrue(
          direct.causeOption.contains(Cause.fail(failure)),
          observation.state == ToolResultState.Error,
          observation.output.contains(failure.message),
          invalid.state == ToolResultState.Error,
          pending.isEmpty,
          exit.isInterrupted,
          exit.causeOption.exists(_.defects == List(defect)),
          requests.map(_.method) == Chunk("initialize")
        )
    }
  ) @@ TestAspect.timeout(10.seconds)

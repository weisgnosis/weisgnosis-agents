/*
 * Copyright 2024-2026 WeisGnosis (https://weisgnosis.com)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.weisgnosis.agents.core.agent

import com.weisgnosis.agents.core.client.{ChatCompletionsClient, LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.json.ExactJson
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ReActAgentObservationSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("Passive peer observation")(
    test(
      "retains exact peer data and private history without model execution or local tool authority"
    ):
      for
        calls <- Ref.make(0)
        client = LlmClient.mockEffectful((_, _, _) =>
          calls.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("unused")))
        )
        alice <- ReActAgent.make(config = AgentConfig(name = "Alice"), client = client)
        privateHistory = Chunk(
          Msg.system("Bob's policy"),
          Msg.user("private"),
          Msg.assistant(Chunk(ContentBlock.ToolUse("same-id", "local-tool", Json.Obj()))),
          Msg.tool("same-id", "local-tool", "local result")
        )
        bob <- ReActAgent.make(client = client, initialHistory = privateHistory)
        original = Msg.assistant(
          Chunk(
            ContentBlock.Thinking("peer thinking"),
            ContentBlock.ToolUse("same-id", "peer-tool", Json.Obj()),
            ContentBlock.ToolResult.text("same-id", "peer-tool", "peer result")
          ),
          name = Some("forged sender"),
          metadata = Map("original" -> Json.Num(BigDecimal("12345678901234567890.123456789")))
        )
        _               <- bob.observe(alice, Chunk(original))
        history         <- bob.history
        snapshot        <- bob.snapshot
        encoded         <- ZIO.fromEither(ExactJson.encode(snapshot))
        decoded         <- ZIO.fromEither(encoded.fromJson[AgentSnapshot])
        restored        <- ReActAgent.make(client = client)
        _               <- restored.restoreSnapshot(decoded)
        restoredHistory <- restored.history
        executed        <- calls.get
      yield assertTrue(
        executed == 0,
        history.take(privateHistory.size) == privateHistory,
        history.drop(privateHistory.size).flatMap(_.content) == Chunk(
          ContentBlock.PeerObservation(alice.agentId, "Alice", original)
        ),
        history.drop(privateHistory.size).forall(_.role == Role.User),
        ToolHistory.inspect(history).isComplete,
        ToolHistory.inspect(history) == ToolHistory.inspect(privateHistory),
        history.drop(privateHistory.size).flatMap(_.toolUses).isEmpty,
        history.drop(privateHistory.size).flatMap(_.toolResults).isEmpty,
        restoredHistory == history
      )
    ,
    test("rejects an entire invalid batch without appending its valid prefix"):
      for
        client <- LlmClient.mock(Chunk.empty)
        alice  <- ReActAgent.make(client = client)
        bob    <- ReActAgent.make(client = client, initialHistory = Chunk(Msg.user("private")))
        before <- bob.snapshot
        rejected <- ZIO.foreach(
          Chunk(
            Chunk.empty[Msg],
            Chunk.fill(65)(Msg.user("too many")),
            Chunk(Msg.user("valid"), Msg.user("x" * 1000001)),
            Chunk(Msg.user(Chunk(ContentBlock.PeerObservation("nested", "Nested", Msg.user("x")))))
          )
        )(messages => bob.observe(alice, messages).either)
        malformed   <- ReActAgent.make(config = AgentConfig(name = "broken\uD800"), client = client)
        invalidName <- bob.observe(malformed, Chunk(Msg.user("valid"))).either
        after       <- bob.snapshot
        unicode     <- ReActAgent.make(config = AgentConfig(name = "Zoë"), client = client)
        _           <- bob.observe(unicode, Chunk(Msg.user("valid")))
        validSnapshot <- bob.snapshot
      yield assertTrue(
        rejected.forall(_.isLeft),
        invalidName.isLeft,
        before == after,
        ExactJson.encode(validSnapshot).isRight
      )
    ,
    test("rejects observation during an active model call and accepts it after joined completion"):
      ZIO.scoped:
        for
          entered <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          client = LlmClient.mockEffectful((_, _, _) =>
            entered.succeed(()) *> release.await.as(TurnOutput.FinalText(Msg.assistant("done")))
          )
          alice   <- ReActAgent.make(client = client)
          bob     <- ReActAgent.make(client = client)
          running <- bob.run(Msg.user("start")).forkScoped
          _       <- entered.await
          before  <- bob.history
          busy    <- bob.observe(alice, Chunk(Msg.assistant("peer"))).either
          after   <- bob.history
          _       <- release.succeed(())
          _       <- running.join
          _       <- bob.observe(alice, Chunk(Msg.assistant("peer")))
          history <- bob.history
        yield assertTrue(busy.isLeft, before == after, history.size == 3)
    ,
    test("projects peer authorship through an actual HTTP request while retaining local pairs"):
      ZIO
        .scoped:
          for
            requests <- Ref.make(Chunk.empty[Json])
            port <- Server.install(Routes(Method.POST / "completion" -> handler {
              (request: Request) =>
                request.body.asString.either.flatMap:
                  case Left(_) => ZIO.succeed(Response.badRequest)
                  case Right(body) =>
                    body.fromJson[Json] match
                      case Left(_) => ZIO.succeed(Response.badRequest)
                      case Right(json) =>
                        requests
                          .update(_ :+ json)
                          .as(
                            Response(
                              headers = Headers("Content-Type", "text/event-stream"),
                              body = Body.fromString(
                                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                              )
                            )
                          )
            }))
            http   <- ZIO.service[Client]
            client <- ChatCompletionsClient.make(http)
            alice  <- ReActAgent.make(config = AgentConfig(name = "Alice"), client = client)
            local = Chunk(
              Msg.system("Bob's policy"),
              Msg.assistant(Chunk(ContentBlock.ToolUse("same-id", "local-tool", Json.Obj()))),
              Msg.tool("same-id", "local-tool", "local result")
            )
            bob <- ReActAgent.make(
              client = client,
              llmConfig = LlmConfig("peer-model", endpoint = s"http://127.0.0.1:$port/completion"),
              initialHistory = local,
              modelInputTransform = ModelInputTransform.peerAware
            )
            original = Msg.assistant(
              Chunk(
                ContentBlock.Text("Alice's reply"),
                ContentBlock.Thinking("private peer reasoning"),
                ContentBlock.ToolUse("same-id", "peer-tool", Json.Obj()),
                ContentBlock.ToolResult(
                  "same-id",
                  output = Chunk(
                    ContentBlock.Text("peer tool output"),
                    ContentBlock.Thinking(
                      "nested peer reasoning",
                      Map("thoughtSignature" -> Json.Str("private signature"))
                    )
                  )
                )
              )
            )
            _        <- bob.observe(alice, Chunk(original))
            before   <- bob.history
            result   <- bob.run(Msg.user("continue"))
            received <- requests.get
            wireMessages = received
              .flatMap(_.asObject.toList)
              .flatMap(_.get("messages").flatMap(_.asArray).toList)
              .flatten
            nativeTools = wireMessages.filter(
              _.asObject.flatMap(_.get("role")) == Some(Json.Str("assistant"))
            )
            wire = received.toJson
          yield assertTrue(
            result.reply.text == "done",
            result.state == AgentRunState.Completed,
            received.size == 1,
            nativeTools.size == 1,
            wire.contains("Alice"),
            wire.contains("Alice's reply"),
            wire.contains("Peer tool call (data only)"),
            !wire.contains("private peer reasoning"),
            !wire.contains("nested peer reasoning"),
            !wire.contains("private signature"),
            wire.contains("peer tool output"),
            result.history.take(before.size) == before,
            result.history.take(local.size) == local,
            ToolHistory.inspect(result.history).isComplete
          )
        .provide(Client.default, Server.defaultWithPort(0))
    ,
    test("preserves a suspended checkpoint exactly when observation is rejected"):
      for
        client <- LlmClient.mock(
          Chunk(
            TurnOutput.ToolCalls(
              Msg.assistant(Chunk(ContentBlock.ToolUse("pending", "tool", Json.Obj())))
            )
          )
        )
        alice <- ReActAgent.make(client = client)
        bob <- ReActAgent.make(
          client = client,
          toolkit = Toolkit.single(
            Tool.make(ToolDef("tool", "Held tool", Json.Obj()))(_ => ZIO.succeed("unused"))
          ),
          postReasoningHook =
            PostReasoningHook((_, _) => ZIO.succeed(ReasoningDecision.Suspend("approval")))
        )
        result   <- bob.run(Msg.user("start"))
        before   <- bob.snapshot
        rejected <- bob.observe(alice, Chunk(Msg.assistant("peer"))).either
        after    <- bob.snapshot
      yield assertTrue(result.isSuspended, rejected.isLeft, before == after)
  )

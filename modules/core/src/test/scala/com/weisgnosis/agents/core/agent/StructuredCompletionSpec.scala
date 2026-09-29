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
package com.weisgnosis.agents.core.agent

import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** Final result preparation must preserve the payload that was actually validated. */
object StructuredCompletionSpec extends ZIOSpecDefault:
  final private case class Answer(value: Int) derives JsonCodec
  private val schema: Json = Json.Obj(
    "type"       -> Json.Str("object"),
    "properties" -> Json.Obj("value" -> Json.Obj("type" -> Json.Str("integer"))),
    "required"   -> Json.Arr(Json.Str("value"))
  )

  private def replies(events: Chunk[AgentEvent]): Chunk[Msg] =
    events.collect { case event: AgentEvent.AgentResultEvent => event.result }

  private def resultBeforeEnd(events: Chunk[AgentEvent]): Boolean =
    events.dropRight(1).lastOption.exists(_.isInstanceOf[AgentEvent.AgentResultEvent]) &&
      events.lastOption.exists(_.isInstanceOf[AgentEvent.AgentEndEvent])

  def spec: Spec[TestEnvironment & Scope, Any] = suite("StructuredCompletionSpec")(
    test("native completion preserves validated metadata when visible text is not JSON") {
      val payload = Json.Obj("value" -> Json.Num(7))
      val reply = Msg
        .assistant("display text")
        .withMetadata(StructuredOutput.MetadataStructuredOutput, payload)
      val model = LlmClient.mockFunction((_, _) => TurnOutput.FinalText(reply))
      for
        agent <- ReActAgent.make(client = model)
        completed <- agent
          .runStructured[Answer](Chunk(Msg.user("answer")), schema, StructuredMode.Native)
        history <- agent.history
      yield assertTrue(
        completed._2 == Answer(7),
        completed._1.reply.metadata
          .get(StructuredOutput.MetadataStructuredOutput)
          .contains(payload),
        completed._1.reply.getStructuredData[Answer] == Right(Answer(7)),
        completed._1.reply.text == "display text",
        history.lastOption.contains(completed._1.reply)
      )
    },
    test("ordinary streaming publishes the committed reply immediately before the end") {
      val reply = Msg.assistant("done").withId(Some("reply"))
      val model = LlmClient.mockFunction((_, _) => TurnOutput.FinalText(reply))
      for
        agent   <- ReActAgent.make(client = model)
        events  <- agent.streamEvents(Msg.user("work")).runCollect
        history <- agent.history
      yield assertTrue(
        replies(events) == Chunk(reply),
        resultBeforeEnd(events),
        history.lastOption.contains(reply)
      )
    },
    test("native structured streaming publishes the validated payload and committed history") {
      val payload = Json.Obj("value" -> Json.Num(7))
      val model =
        LlmClient.mockFunction((_, _) => TurnOutput.FinalText(Msg.assistant(payload.toJson)))
      for
        agent <- ReActAgent.make(client = model)
        events <- agent
          .streamStructuredEvents[Answer](Chunk(Msg.user("answer")), schema, StructuredMode.Native)
          .runCollect
        history <- agent.history
      yield assertTrue(
        replies(events).size == 1,
        resultBeforeEnd(events),
        replies(events).headOption == history.lastOption,
        replies(events).forall(reply =>
          reply.getStructuredData[Answer] == Right(Answer(7)) &&
            reply.metadata.get(StructuredOutput.MetadataStructuredOutput).contains(payload)
        )
      )
    },
    test("synthetic structured streaming emits only the finalized reply with thinking and usage") {
      val payload = Json.Obj("value" -> Json.Num(8))
      val thought = ContentBlock.Thinking("reason", Map("thoughtSignature" -> Json.Str("signed")))
      val usage   = ChatUsage(inputTokens = 3, outputTokens = 5)
      val call =
        ContentBlock.ToolUse("generate", StructuredOutput.ToolName, Json.Obj("response" -> payload))
      val raw   = Msg.assistant(Chunk(thought, call)).withUsage(usage)
      val model = LlmClient.mockFunction((_, _) => TurnOutput.ToolCalls(raw))
      for
        agent <- ReActAgent.make(client = model)
        events <- agent
          .streamStructuredEvents[Answer](
            Chunk(Msg.user("answer")),
            schema,
            StructuredMode.SyntheticTool
          )
          .runCollect
        history <- agent.history
      yield assertTrue(
        replies(events).size == 1,
        resultBeforeEnd(events),
        replies(events).headOption == history.lastOption,
        replies(events).forall(reply =>
          reply.content == Chunk(thought, ContentBlock.Text(payload.toJson)) &&
            reply.usage.contains(usage) && reply.getStructuredData[Answer] == Right(Answer(8))
        ),
        !history.exists(StructuredOutput.isStructuredOutputRelated)
      )
    },
    test(
      "auto fallback retains two attempt boundaries but publishes only the successful final result"
    ) {
      val payload = Json.Obj("value" -> Json.Num(9))
      val call =
        ContentBlock.ToolUse("generate", StructuredOutput.ToolName, Json.Obj("response" -> payload))
      for
        requests <- Ref.make(0)
        model = LlmClient.mockEffectful((_, _, _) =>
          requests
            .updateAndGet(_ + 1)
            .flatMap(count =>
              if count == 1 then ZIO.fail(AgentError.ModelError(422, "unsupported native format"))
              else ZIO.succeed(TurnOutput.ToolCalls(Msg.assistant(Chunk(call))))
            )
        )
        agent   <- ReActAgent.make(client = model)
        events  <- agent.streamStructuredEvents[Answer](Msg.user("answer"), schema).runCollect
        history <- agent.history
        boundaries = events.collect {
          case _: AgentEvent.AgentStartEvent  => "start"
          case _: AgentEvent.AgentResultEvent => "result"
          case _: AgentEvent.AgentEndEvent    => "end"
        }
        starts = events.collect { case event: AgentEvent.AgentStartEvent => event.replyId }
        ends   = events.collect { case event: AgentEvent.AgentEndEvent => event.replyId }
      yield assertTrue(
        boundaries == Chunk("start", "end", "start", "result", "end"),
        starts == ends,
        starts.toSet.size == 2,
        history.count(_.role == Role.User) == 1,
        replies(events).forall(_.getStructuredData[Answer] == Right(Answer(9)))
      )
    },
    test("structured validation failure closes the attempt without a final result event") {
      val raw   = Msg.assistant("""{"wrong":true}""")
      val model = LlmClient.mockFunction((_, _) => TurnOutput.FinalText(raw))
      for
        observed <- Ref.make(Chunk.empty[AgentEvent])
        agent    <- ReActAgent.make(client = model)
        outcome <- agent
          .streamStructuredEvents[Answer](Chunk(Msg.user("answer")), schema, StructuredMode.Native)
          .tap(event => observed.update(_ :+ event))
          .runDrain
          .either
        events  <- observed.get
        history <- agent.history
      yield assertTrue(
        outcome.left.exists(_.isInstanceOf[AgentError.DeserializationError]),
        replies(events).isEmpty,
        events.lastOption.exists(_.isInstanceOf[AgentEvent.AgentEndEvent]),
        history.lastOption.contains(raw)
      )
    },
    test(
      "cooperative interruption after capture preserves recovery without publishing a structured result"
    ) {
      val payload = Json.Obj("value" -> Json.Num(10))
      val capture =
        ContentBlock.ToolUse("generate", StructuredOutput.ToolName, Json.Obj("response" -> payload))
      val slow = ContentBlock.ToolUse("slow", "slow", Json.Obj())
      val model =
        LlmClient.mockFunction((_, _) => TurnOutput.ToolCalls(Msg.assistant(Chunk(capture, slow))))
      for
        entered  <- Promise.make[Nothing, Unit]
        observed <- Ref.make(Chunk.empty[AgentEvent])
        tool = Tool.make(ToolDef("slow", "test", Json.Obj()))(_ => entered.succeed(()) *> ZIO.never)
        toolkit <- ZIO.fromEither(Toolkit(tool))
        agent   <- ReActAgent.make(client = model, toolkit = toolkit)
        fiber <- agent
          .streamStructuredEvents[Answer](
            Chunk(Msg.user("answer")),
            schema,
            StructuredMode.SyntheticTool
          )
          .tap(event => observed.update(_ :+ event))
          .runDrain
          .either
          .forkScoped
        _       <- entered.await
        _       <- agent.interrupt
        outcome <- fiber.join
        events  <- observed.get
        history <- agent.history
      yield assertTrue(
        outcome.left.exists(_.isInstanceOf[AgentError.Interrupted]),
        replies(events).isEmpty,
        events.lastOption.exists(_.isInstanceOf[AgentEvent.AgentEndEvent]),
        history.lastOption.exists(_.generationReason == GenerateReason.Interrupted),
        history
          .flatMap(_.toolResults)
          .exists(result => result.id == "slow" && result.state == ToolResultState.Error)
      )
    },
    test("ordinary cooperative interruption publishes its committed recovery reply") {
      for
        entered <- Promise.make[Nothing, Unit]
        model = LlmClient.mockEffectful((_, _, _) => entered.succeed(()) *> ZIO.never)
        agent   <- ReActAgent.make(client = model)
        fiber   <- agent.streamEvents(Msg.user("work")).runCollect.forkScoped
        _       <- entered.await
        _       <- agent.interrupt
        events  <- fiber.join
        history <- agent.history
      yield assertTrue(
        replies(events).size == 1,
        resultBeforeEnd(events),
        replies(events).headOption == history.lastOption,
        replies(events).forall(_.generationReason == GenerateReason.Interrupted)
      )
    },
    test("early structured stream completion releases owned model resources") {
      for
        released <- Ref.make(false)
        model = LlmClient.mockEffectful((_, _, hub) =>
          ZIO.acquireRelease(ZIO.unit)(_ => released.set(true)) *>
            hub.publish(AgentEvent.TextBlockDeltaEvent("entered", 0)) *> ZIO.never
        )
        agent <- ReActAgent.make(client = model)
        _ <- agent
          .streamStructuredEvents[Answer](Msg.user("answer"), schema)
          .takeUntil(_.isInstanceOf[AgentEvent.TextBlockDeltaEvent])
          .runDrain
        cleaned <- released.get
      yield assertTrue(cleaned)
    }
  )

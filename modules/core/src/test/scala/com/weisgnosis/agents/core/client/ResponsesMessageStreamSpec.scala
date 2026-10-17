/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.tool.ToolDef
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

private[client] object ResponsesFixtures:
  val model: String = "fixture-reasoner"
  val config: LlmConfig = LlmConfig(
    model,
    endpoint = "https://example.invalid/v1/responses",
    temperaturePolicy = TemperaturePolicy.Omit
  )
  val usage: Json.Obj = Json.Obj(
    "input_tokens"  -> Json.Num(10),
    "output_tokens" -> Json.Num(5),
    "total_tokens"  -> Json.Num(15)
  )

  def reasoning(id: String, encrypted: String, summary: String = ""): Json.Obj = Json.Obj(
    "id"   -> Json.Str(id),
    "type" -> Json.Str("reasoning"),
    "summary" -> Json.Arr(
      if summary.isEmpty then Chunk.empty
      else Chunk(Json.Obj("type" -> Json.Str("summary_text"), "text" -> Json.Str(summary)))
    ),
    "encrypted_content" -> Json.Str(encrypted)
  )

  def function(itemId: String, callId: String, arguments: String = "{}"): Json.Obj = Json.Obj(
    "id"        -> Json.Str(itemId),
    "type"      -> Json.Str("function_call"),
    "status"    -> Json.Str("completed"),
    "call_id"   -> Json.Str(callId),
    "name"      -> Json.Str("read_evidence"),
    "arguments" -> Json.Str(arguments)
  )

  def message(text: String, refusal: Boolean = false): Json.Obj = Json.Obj(
    "id"     -> Json.Str("message-1"),
    "type"   -> Json.Str("message"),
    "status" -> Json.Str("completed"),
    "role"   -> Json.Str("assistant"),
    "content" -> Json.Arr(
      Json.Obj(
        "type" -> Json.Str(if refusal then "refusal" else "output_text"),
        (if refusal then "refusal" else "text") -> Json.Str(text)
      )
    )
  )

  def initial(item: Json.Obj): Json.Obj = Json.Obj(item.fields.map {
    case ("arguments", _)         => "arguments"         -> Json.Str("")
    case ("content", _)           => "content"           -> Json.Arr()
    case ("summary", _)           => "summary"           -> Json.Arr()
    case ("status", _)            => "status"            -> Json.Str("in_progress")
    case ("encrypted_content", _) => "encrypted_content" -> Json.Str("incomplete")
    case other                    => other
  })

  def event(kind: String, fields: (String, Json)*): Json.Obj =
    Json.Obj(Chunk("type" -> Json.Str(kind)) ++ Chunk.fromIterable(fields))
  def created: Json.Obj = event(
    "response.created",
    "response" -> Json.Obj("id" -> Json.Str("response-1"), "output" -> Json.Arr())
  )
  def added(index: Int, item: Json.Obj): Json.Obj =
    event("response.output_item.added", "output_index" -> Json.Num(index), "item" -> initial(item))
  def closed(index: Int, item: Json.Obj): Json.Obj =
    event("response.output_item.done", "output_index" -> Json.Num(index), "item" -> item)
  def completed(items: Chunk[Json.Obj]): Json.Obj = event(
    "response.completed",
    "response" -> Json.Obj(
      "id"     -> Json.Str("response-1"),
      "status" -> Json.Str("completed"),
      "output" -> Json.Arr(items),
      "usage"  -> usage
    )
  )

  def frames(events: Chunk[Json.Obj]): Chunk[SseEvent] = events.zipWithIndex.map {
    case (value, index) =>
      val withSequence = Json.Obj(value.fields :+ ("sequence_number" -> Json.Num(index)))
      SseEvent(JsonBoundary.render(withSequence), value.get("type").flatMap(_.asString))
  }

  def events(items: Chunk[Json.Obj]): Chunk[Json.Obj] =
    Chunk(created) ++ items.zipWithIndex.map((item, index) => added(index, item)) ++
      items.zipWithIndex.map((item, index) => closed(index, item)) :+ completed(items)

  def stream(items: Chunk[Json.Obj]): String = frames(events(items))
    .map(frame => s"event: ${frame.event.getOrElse("")}\ndata: ${frame.data}\n\n")
    .mkString

  def reduce(events: Chunk[Json.Obj]): Either[AgentError, ResponsesMessageStream] =
    frames(events).foldLeft[Either[AgentError, ResponsesMessageStream]](
      Right(ResponsesMessageStream.empty(model))
    )((previous, frame) => previous.flatMap(_.accept(frame).map(_._1)))

object ResponsesMessageStreamSpec extends ZIOSpecDefault:
  import ResponsesFixtures.*

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ResponsesMessageStreamSpec")(
    test("completed reasoning items round-trip and replay in exact order with call_id linkage"):
      val items = Chunk(
        reasoning("r-1", "cipher-one"),
        function("item-1", "call-1"),
        reasoning("r-2", "cipher-two", "Checked evidence"),
        function("item-2", "call-2", "")
      )
      for
        state   <- ZIO.fromEither(reduce(events(items)))
        output  <- ZIO.fromEither(state.output)
        decoded <- ZIO.fromEither(output.message.toJson.fromJson[Msg])
        replay  <- ZIO.fromEither(ResponsesNativeReplay.content(decoded, model))
        request <- ZIO.fromEither(
          ResponsesRequest.encode(
            Chunk(
              Msg.user("review"),
              decoded,
              Msg.tool("call-1", "read_evidence", "first"),
              Msg.tool("call-2", "read_evidence", "second")
            ),
            Chunk.empty,
            config
          )
        )
      yield assertTrue(
        output.isInstanceOf[TurnOutput.ToolCalls],
        decoded.hasReasoning,
        decoded.preservesToolArguments,
        decoded.toolUses.map(_.id) == Chunk("call-1", "call-2"),
        decoded.toolUses.forall(_.input == Json.Obj()),
        replay.contains(items),
        request.get("temperature").isEmpty,
        request.get("store").contains(Json.Bool(false)),
        request
          .get("input")
          .flatMap(_.asArray)
          .exists(values =>
            values.slice(1, 5) == items && values
              .drop(5)
              .flatMap(_.asObject.flatMap(_.get("call_id")).flatMap(_.asString)) == Chunk(
              "call-1",
              "call-2"
            )
          ),
        decoded.chatUsage.exists(value => value.inputTokens == 10 && value.outputTokens == 5),
        ResponsesNativeReplay.content(decoded, "another-model").isLeft,
        ResponsesNativeReplay.content(decoded.withTextContent("changed"), model).isLeft
      )
    ,
    test("interleaved arguments remain separate and completed snapshots do not append twice"):
      val first  = function("item-a", "call-a", "{\"value\":1}")
      val second = function("item-b", "call-b", "{\"value\":2}")
      def delta(index: Int, id: String, text: String): Json.Obj = event(
        "response.function_call_arguments.delta",
        "output_index" -> Json.Num(index),
        "item_id"      -> Json.Str(id),
        "delta"        -> Json.Str(text)
      )
      val input = Chunk(
        created,
        added(0, first),
        added(1, second),
        delta(0, "item-a", "{\"value\":"),
        delta(1, "item-b", "{\"value\":2}"),
        delta(0, "item-a", "1}"),
        closed(1, second),
        closed(0, first),
        completed(Chunk(first, second))
      )
      val output = reduce(input).flatMap(_.output)
      assertTrue(
        output.exists(
          _.message.toolUses.map(_.input) == Chunk(
            Json.Obj("value" -> Json.Num(1)),
            Json.Obj("value" -> Json.Num(2))
          )
        )
      )
    ,
    test(
      "argument closures reject late deltas and duplicates while accepting one completed snapshot"
    ):
      val call = function("item-a", "call-a")
      val finished = event(
        "response.function_call_arguments.done",
        "output_index" -> Json.Num(0),
        "item_id"      -> Json.Str("item-a"),
        "arguments"    -> Json.Str("{}")
      )
      val late = event(
        "response.function_call_arguments.delta",
        "output_index" -> Json.Num(0),
        "item_id"      -> Json.Str("item-a"),
        "delta"        -> Json.Str(" ")
      )
      val prefix = Chunk(created, added(0, call), finished)
      assertTrue(
        reduce(prefix :+ late).isLeft,
        reduce(prefix :+ finished).isLeft,
        reduce(prefix ++ Chunk(closed(0, call), completed(Chunk(call)))).flatMap(_.output).isRight
      )
    ,
    test("visible refusal completes without becoming a tool call or empty-response reminder"):
      val output =
        reduce(events(Chunk(message("Cannot provide that", refusal = true)))).flatMap(_.output)
      assertTrue(
        output.exists(value =>
          value.isInstanceOf[TurnOutput.FinalText] && value.message.text == "Cannot provide that"
        ),
        reduce(events(Chunk(message("No", refusal = true), function("item-f", "call-f")))).isLeft
      )
    ,
    test("unfinished, malformed and conflicting protocol state is rejected"):
      val call = function("item-a", "call-a")
      val bad  = function("item-a", "call-a", "{")
      val mismatch = event(
        "response.function_call_arguments.delta",
        "output_index" -> Json.Num(0),
        "item_id"      -> Json.Str("wrong"),
        "delta"        -> Json.Str("{}")
      )
      val noTerminal = reduce(Chunk(created, added(0, call), closed(0, call))).flatMap(_.output)
      assertTrue(
        noTerminal.isLeft,
        reduce(events(Chunk(bad))).isLeft,
        reduce(Chunk(created, added(0, call), mismatch)).isLeft,
        reduce(Chunk(created, added(0, call), completed(Chunk(call)))).isLeft,
        reduce(events(Chunk(call, call))).isLeft,
        ResponsesRequest
          .encode(Chunk(Msg.user("x")), Chunk.empty, config.copy(seed = Some(1L)))
          .isLeft
      )
    ,
    test("interrupted opaque reasoning is marked and cannot acquire another request"):
      for
        state <- ZIO.fromEither(
          reduce(
            Chunk(
              created,
              added(0, reasoning("r-1", "cipher")),
              added(1, function("item-a", "call-a"))
            )
          )
        )
        partial = state.partial.message
      yield assertTrue(
        partial.content.nonEmpty,
        partial.toolUses.map(_.input) == Chunk(Json.Obj()),
        partial.metadata.contains(Msg.MetadataResponsesIncompleteReplay),
        ResponsesRequest
          .encode(
            Chunk(Msg.user("review"), partial, Msg.tool("call-a", "read_evidence", "cancelled")),
            Chunk(ToolDef("read_evidence", "Evidence", Json.Obj())),
            config
          )
          .isLeft
      )
    ,
    test("token exhaustion retains incomplete visible text but neither calls nor replay authority"):
      val reasoningItem  = reasoning("r-1", "cipher")
      val unfinishedCall = function("item-a", "call-a", "{")
      val unfinishedMessage = Json.Obj(message("Partial answer").fields.map {
        case ("status", _) => "status" -> Json.Str("incomplete")
        case other         => other
      })
      val items = Chunk(reasoningItem, unfinishedCall, unfinishedMessage)
      val terminal = event(
        "response.incomplete",
        "response" -> Json.Obj(
          "id"                 -> Json.Str("response-1"),
          "status"             -> Json.Str("incomplete"),
          "incomplete_details" -> Json.Obj("reason" -> Json.Str("max_output_tokens")),
          "output"             -> Json.Arr(items),
          "usage"              -> usage
        )
      )
      val output = reduce(
        Chunk(created) ++ items.zipWithIndex.map((item, index) => added(index, item)) :+ terminal
      ).flatMap(_.output)
      assertTrue(
        output.exists(value =>
          value.isInstanceOf[TurnOutput.Truncated] && value.message.text == "Partial answer" &&
            value.message.toolUses.isEmpty && value.message.metadata.contains(
              Msg.MetadataResponsesIncompleteReplay
            ) &&
            !value.message.metadata.contains(Msg.MetadataResponsesNative)
        )
      )
  )

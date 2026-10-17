/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.model.{ChatUsage, ContentBlock, Msg}
import zio.Chunk
import zio.json.ast.Json

/** Output indices own accumulation; provider item IDs and executable call IDs remain distinct. */
final private[client] case class ResponsesMessageStream private (
    private val model: String,
    private val stage: ResponsesMessageStream.Stage,
    private val items: Chunk[ResponsesMessageStream.Item],
    private val sequence: Option[Int],
    private val nextBlockIndex: Int
):
  import ResponsesMessageStream.*
  import ResponsesNativeReplay.*

  def done: Boolean = stage match
    case Stage.Done(_) => true
    case _             => false

  def output: Either[AgentError, TurnOutput] = stage match
    case Stage.Done(value) => Right(value)
    case _                 => Left(invalid("Responses stream ended without a terminal response"))

  def partial: TurnOutput.Interrupted =
    stage match
      case Stage.Done(value) => TurnOutput.Interrupted(value.message)
      case _                 => unfinishedPartial

  private def unfinishedPartial: TurnOutput.Interrupted =
    val blocks = items.flatMap(item =>
      item.completed match
        case Some(value) => canonicalItem(value).getOrElse(Chunk.empty)
        case None =>
          item.initial.get("type").flatMap(_.asString) match
            case Some("function_call") =>
              val id   = item.initial.get("call_id").flatMap(_.asString)
              val name = item.initial.get("name").flatMap(_.asString)
              val input = JsonBoundary
                .decode(item.fragments.getOrElse(Part("arguments", 0), ""))
                .toOption
                .collect { case value: Json.Obj => value }
                .getOrElse(Json.Obj())
              Chunk.fromIterable(for
                callId   <- id
                toolName <- name
              yield ContentBlock.ToolUse(callId, toolName, input))
            case _ =>
              Chunk.fromIterable(item.fragments.toVector.sortBy(_._1.index).collect {
                case (Part("output_text", _), text)  => ContentBlock.Text(text)
                case (Part("refusal", _), text)      => ContentBlock.Text(text)
                case (Part("summary_text", _), text) => ContentBlock.Thinking(text)
              })
    )
    val incompleteReasoning = items.exists(_.initial.get("type").contains(Json.Str("reasoning")))
    val retained =
      if incompleteReasoning && !blocks.exists(_.isInstanceOf[ContentBlock.Thinking]) then
        blocks :+ ContentBlock.Thinking("", Map(Msg.MetadataResponsesReasoning -> Json.Bool(true)))
      else blocks
    TurnOutput.Interrupted(
      Msg.assistant(
        retained,
        metadata =
          if incompleteReasoning then Map(Msg.MetadataResponsesIncompleteReplay -> Json.Bool(true))
          else Map.empty
      )
    )

  def accept(frame: SseEvent): Either[AgentError, (ResponsesMessageStream, Chunk[LlmChunk])] =
    for
      decoded <- JsonBoundary.decode(frame.data).left.map(AgentError.DeserializationError(_))
      _       <- ensure(JsonBoundary.validUnicode(decoded), "Malformed Unicode in Responses event")
      value   <- obj(decoded)
      kind    <- string(value, "type")
      _ <- ensure(frame.event.forall(_ == kind), "Responses SSE event name differs from its type")
      _ <- ensure(!done, "Responses event after terminal state")
      nextSequence <- integer(value, "sequence_number")
      _ <- ensure(sequence.forall(_ < nextSequence), "Responses sequence numbers must increase")
      result <- event(kind, value)
    yield (result._1.copy(sequence = Some(nextSequence)), result._2)

  private def event(
      kind: String,
      value: Json.Obj
  ): Either[AgentError, (ResponsesMessageStream, Chunk[LlmChunk])] = kind match
    case "response.created" =>
      for
        _        <- ensure(stage == Stage.Awaiting, "Duplicate Responses creation")
        response <- fieldObject(value, "response")
        id       <- string(response, "id")
        _ <- ensure(
          id.nonEmpty && response.get("output").contains(Json.Arr()),
          "Invalid initial Responses object"
        )
      yield (copy(stage = Stage.Active(id)), Chunk.empty)
    case "response.in_progress" | "response.queued" =>
      checkedResponse(value).map(_ => (this, Chunk.empty))
    case "response.output_item.added" =>
      for
        _     <- active
        index <- integer(value, "output_index")
        _ <- ensure(
          index == items.size,
          "Responses output indices must be registered once in order"
        )
        item     <- fieldObject(value, "item")
        id       <- string(item, "id")
        itemType <- string(item, "type")
        _ <- ensure(
          id.nonEmpty && !items.exists(_.initial.get("id").contains(Json.Str(id))),
          "Duplicate or empty Responses item ID"
        )
        _ <- ensure(
          Set("message", "reasoning", "function_call").contains(itemType),
          "Unsupported Responses output item"
        )
        _ <-
          if itemType == "message" then
            ensure(
              item
                .get("role")
                .contains(Json.Str("assistant")) && item.get("content").contains(Json.Arr()),
              "Invalid initial Responses message"
            )
          else if itemType == "reasoning" then
            ensure(
              item.get("summary").contains(Json.Arr()),
              "Invalid initial Responses reasoning item"
            )
          else Right(())
        deltas <-
          if itemType == "function_call" then
            for
              callId    <- string(item, "call_id")
              name      <- string(item, "name")
              arguments <- string(item, "arguments")
              _ <- ensure(
                callId.nonEmpty && name.matches("[A-Za-z0-9_-]{1,64}") && arguments.isEmpty,
                "Invalid initial Responses function call"
              )
              _ <- ensure(
                !items.exists(_.initial.get("call_id").contains(Json.Str(callId))),
                "Duplicate Responses call_id"
              )
            yield Chunk(LlmChunk.ToolCallChunk(index, Some(callId), Some(name)))
          else Right(Chunk.empty)
      yield (copy(items = items :+ Item(item, None, Map.empty, Map.empty, Set.empty)), deltas)
    case "response.output_item.done" =>
      for
        index   <- integer(value, "output_index")
        current <- openItem(index)
        item    <- fieldObject(value, "item")
        _       <- sameIdentity(current.initial, item)
        _ <- ensure(
          item.get("status").forall(_ == Json.Str("completed")),
          "Responses item is not completed"
        )
        _        <- canonicalItem(item)
        expected <- parts(item)
        _ <- ensure(
          current.fragments.forall((part, text) => expected.get(part).contains(text)),
          "Responses completed item differs from streamed fragments"
        )
        updated <- expected.toVector
          .sortBy(entry => (entry._1.index, entry._1.kind))
          .foldLeft[Either[AgentError, (ResponsesMessageStream, Chunk[LlmChunk])]](
            Right((this, Chunk.empty))
          ) { case (previous, (part, text)) =>
            previous.flatMap { case (state, emitted) =>
              if current.fragments.contains(part) then Right((state, emitted))
              else
                state.append(index, part, text).map { case (next, deltas) =>
                  (next, emitted ++ deltas)
                }
            }
          }
        completed <- updated._1.items.lift(index).toRight(invalid("Missing Responses item"))
      yield (
        updated._1
          .copy(items = updated._1.items.updated(index, completed.copy(completed = Some(item)))),
        updated._2
      )
    case "response.output_text.delta"            => delta(value, "output_text", "content_index")
    case "response.refusal.delta"                => delta(value, "refusal", "content_index")
    case "response.reasoning_summary_text.delta" => delta(value, "summary_text", "summary_index")
    case "response.reasoning_text.delta"         => delta(value, "reasoning_text", "content_index")
    case "response.function_call_arguments.delta" =>
      for
        index  <- identifiedIndex(value)
        text   <- string(value, "delta")
        result <- append(index, Part("arguments", 0), text)
      yield result
    case "response.output_text.done" | "response.refusal.done" |
        "response.reasoning_summary_text.done" | "response.reasoning_text.done" |
        "response.function_call_arguments.done" =>
      for
        index   <- identifiedIndex(value)
        current <- openItem(index)
        part <- kind match
          case "response.function_call_arguments.done" => Right(Part("arguments", 0))
          case "response.reasoning_summary_text.done" =>
            integer(value, "summary_index").map(Part("summary_text", _))
          case "response.reasoning_text.done" =>
            integer(value, "content_index").map(Part("reasoning_text", _))
          case "response.refusal.done" => integer(value, "content_index").map(Part("refusal", _))
          case _ => integer(value, "content_index").map(Part("output_text", _))
        text <- string(
          value,
          if part.kind == "arguments" then "arguments"
          else if part.kind == "refusal" then "refusal"
          else "text"
        )
        _ <- ensure(
          current.fragments.get(part).forall(_ == text),
          "Responses done event differs from streamed text"
        )
        result <-
          if current.fragments.contains(part) then Right((this, Chunk.empty))
          else append(index, part, text)
        closed <- result._1.closePart(index, part, Closure.Text)
      yield (closed, result._2)
    case "response.content_part.added" | "response.content_part.done" |
        "response.reasoning_summary_part.added" | "response.reasoning_summary_part.done" =>
      for
        index   <- identifiedIndex(value)
        current <- openItem(index)
        partIndex <- integer(
          value,
          if kind.contains("summary") then "summary_index" else "content_index"
        )
        part     <- fieldObject(value, "part")
        partType <- string(part, "type")
        _ <- ensure(
          Set("output_text", "refusal", "summary_text", "reasoning_text").contains(partType),
          "Unsupported Responses content part"
        )
        key = Part(partType, partIndex)
        result <-
          if kind.endsWith(".done") then
            for
              text <- string(part, if partType == "refusal" then "refusal" else "text")
              _ <- ensure(
                current.fragments.get(key).forall(_ == text),
                "Responses completed content part differs from fragments"
              )
              updated <-
                if current.fragments.contains(key) then Right((this, Chunk.empty))
                else append(index, key, text)
              closed <- updated._1.closePart(index, key, Closure.Container)
            yield (closed, updated._2)
          else
            ensure(
              !current.closed.exists(_._1 == key),
              "Responses content part reopened after closure"
            ).map(_ => (this, Chunk.empty))
      yield result
    case "response.completed" =>
      for
        response <- checkedResponse(value)
        _ <- ensure(
          response.get("status").contains(Json.Str("completed")),
          "Responses completed event has incompatible status"
        )
        raw    <- array(response, "output")
        native <- traverse(raw)(obj)
        _ <- ensure(
          items.size == native.size && items.map(_.completed) == native.map(Some(_)),
          "Responses final output differs from completed items"
        )
        canonical <- canonicalBlocks(native)
        usage     <- usageOf(response)
        message = attach(Msg.assistant(canonical, usage = Some(usage)), model, native)
        output =
          if message.toolUses.nonEmpty then TurnOutput.ToolCalls(message)
          else if message.text.trim.nonEmpty then TurnOutput.FinalText(message)
          else TurnOutput.ThinkingOnly(message)
      yield (copy(stage = Stage.Done(output)), Chunk(LlmChunk.UsageChunk(usage)))
    case "response.incomplete" =>
      for
        response <- checkedResponse(value)
        details  <- fieldObject(response, "incomplete_details")
        _ <- ensure(
          response.get("status").contains(Json.Str("incomplete")) && details
            .get("reason")
            .contains(Json.Str("max_output_tokens")),
          "Unsupported incomplete Responses outcome"
        )
        raw    <- array(response, "output")
        native <- traverse(raw)(obj)
        _ <- ensure(items.size == native.size, "Incomplete Responses output changed item count")
        _ <- traverse(items.zip(native)) { case (current, item) =>
          for
            _ <- sameIdentity(current.initial, item)
            _ <- ensure(
              current.completed.forall(_ == item),
              "Incomplete Responses output changed a completed item"
            )
            expected <- parts(item)
            _ <- ensure(
              current.fragments.forall((part, text) => expected.get(part).contains(text)),
              "Incomplete Responses output differs from streamed fragments"
            )
          yield ()
        }
        _ <- traverse(native)(item =>
          string(item, "type").flatMap(kind =>
            ensure(
              Set("message", "reasoning", "function_call").contains(kind),
              "Unsupported incomplete Responses item"
            )
          )
        )
        text  <- traverse(native.filter(_.get("type").contains(Json.Str("message"))))(canonicalItem)
        usage <- usageOf(response)
        truncated = TurnOutput.tokenExhausted(Msg.assistant(text.flatten, usage = Some(usage)))
        output =
          if native.exists(_.get("type").contains(Json.Str("reasoning"))) then
            truncated.copy(message =
              truncated.message.withMetadata(Msg.MetadataResponsesIncompleteReplay, Json.Bool(true))
            )
          else truncated
      yield (copy(stage = Stage.Done(output)), Chunk(LlmChunk.UsageChunk(usage)))
    case "response.failed" | "error" =>
      Left(AgentError.ModelError(200, "Responses stream reported a failure"))
    case _ => Left(invalid(s"Unsupported Responses streaming event: $kind"))

  private def delta(
      value: Json.Obj,
      kind: String,
      indexField: String
  ): Either[AgentError, (ResponsesMessageStream, Chunk[LlmChunk])] =
    for
      index  <- identifiedIndex(value)
      part   <- integer(value, indexField)
      text   <- string(value, "delta")
      result <- append(index, Part(kind, part), text)
    yield result

  private def append(
      index: Int,
      part: Part,
      text: String
  ): Either[AgentError, (ResponsesMessageStream, Chunk[LlmChunk])] =
    for
      current <- openItem(index)
      _ <- ensure(!current.closed.exists(_._1 == part), "Responses delta after content closure")
      itemType <- string(current.initial, "type")
      _ <- ensure(
        (itemType, part.kind) match
          case ("message", "output_text" | "refusal")           => true
          case ("reasoning", "summary_text" | "reasoning_text") => true
          case ("function_call", "arguments")                   => true
          case _                                                => false,
        "Responses fragment has incompatible item type"
      )
      block = current.blockIndices.getOrElse(part, nextBlockIndex)
      fresh = !current.blockIndices.contains(part)
      _ <- ensure(!fresh || nextBlockIndex < Int.MaxValue, "Responses block index overflow")
      updated = current.copy(
        fragments = current.fragments.updated(part, current.fragments.getOrElse(part, "") + text),
        blockIndices = current.blockIndices.updated(part, block)
      )
      deltas = part.kind match
        case "arguments"      => Chunk(LlmChunk.ToolCallChunk(index, argumentsDelta = text))
        case "summary_text"   => Chunk(LlmChunk.ThinkingDelta(text, block))
        case "reasoning_text" => Chunk.empty
        case _                => Chunk(LlmChunk.TextDelta(text, block))
    yield (
      copy(
        items = items.updated(index, updated),
        nextBlockIndex = if fresh then nextBlockIndex + 1 else nextBlockIndex
      ),
      deltas
    )

  private def closePart(
      index: Int,
      part: Part,
      kind: Closure
  ): Either[AgentError, ResponsesMessageStream] =
    for
      current <- openItem(index)
      _ <- ensure(
        !current.closed.contains((part, kind)) &&
          (kind == Closure.Container || !current.closed.contains((part, Closure.Container))),
        "Duplicate or out-of-order Responses content closure"
      )
    yield copy(items = items.updated(index, current.copy(closed = current.closed + ((part, kind)))))

  private def active: Either[AgentError, Unit] = stage match
    case Stage.Active(_) => Right(())
    case _               => Left(invalid("Responses event outside an active response"))

  private def openItem(index: Int): Either[AgentError, Item] =
    active.flatMap(_ =>
      items
        .lift(index)
        .filter(_.completed.isEmpty)
        .toRight(invalid("Responses event references an unknown or closed item"))
    )

  private def identifiedIndex(value: Json.Obj): Either[AgentError, Int] =
    for
      index <- integer(value, "output_index")
      item  <- openItem(index)
      id    <- string(value, "item_id")
      _ <- ensure(
        item.initial.get("id").contains(Json.Str(id)),
        "Responses fragment item ID mismatch"
      )
    yield index

  private def checkedResponse(value: Json.Obj): Either[AgentError, Json.Obj] =
    for
      response <- fieldObject(value, "response")
      id       <- string(response, "id")
      _ <- stage match
        case Stage.Active(expected) => ensure(id == expected, "Responses response ID changed")
        case _ => Left(invalid("Responses terminal/lifecycle event outside active response"))
    yield response

private[client] object ResponsesMessageStream:
  import ResponsesNativeReplay.*
  private enum Stage:
    case Awaiting
    case Active(id: String)
    case Done(output: TurnOutput)
  final private case class Part(kind: String, index: Int)
  private enum Closure:
    case Text, Container
  final private case class Item(
      initial: Json.Obj,
      completed: Option[Json.Obj],
      fragments: Map[Part, String],
      blockIndices: Map[Part, Int],
      closed: Set[(Part, Closure)]
  )

  def empty(model: String): ResponsesMessageStream =
    ResponsesMessageStream(model, Stage.Awaiting, Chunk.empty, None, 0)

  private def fieldObject(value: Json.Obj, key: String): Either[AgentError, Json.Obj] =
    value.get(key).toRight(invalid(s"Missing Responses object: $key")).flatMap(obj)

  private def sameIdentity(initial: Json.Obj, completed: Json.Obj): Either[AgentError, Unit] =
    ensure(
      Chunk("id", "type", "call_id", "name").forall(key => initial.get(key) == completed.get(key)),
      "Responses item identity changed"
    )

  private def parts(item: Json.Obj): Either[AgentError, Map[Part, String]] =
    string(item, "type").flatMap:
      case "function_call" =>
        string(item, "arguments").map(value => Map(Part("arguments", 0) -> value))
      case kind =>
        val fields = if kind == "reasoning" then Chunk("summary", "content") else Chunk("content")
        traverse(fields)(field =>
          item.get(field) match
            case None if kind == "reasoning" && field == "content" =>
              Right(Chunk.empty[(Part, String)])
            case _ =>
              array(item, field).flatMap(values =>
                traverse(values.zipWithIndex) { case (raw, index) =>
                  for
                    value    <- obj(raw)
                    partType <- string(value, "type")
                    text     <- string(value, if partType == "refusal" then "refusal" else "text")
                  yield (Part(partType, index), text)
                }
              )
        ).map(_.flatten.toMap)

  private def usageOf(response: Json.Obj): Either[AgentError, ChatUsage] =
    for
      usage  <- fieldObject(response, "usage")
      input  <- integer(usage, "input_tokens")
      output <- integer(usage, "output_tokens")
      total  <- integer(usage, "total_tokens")
      _ <- ensure(
        input.toLong + output.toLong == total.toLong,
        "Responses usage total differs from counters"
      )
      cached    <- optionalCount(usage, "input_tokens_details", "cached_tokens")
      reasoning <- optionalCount(usage, "output_tokens_details", "reasoning_tokens")
      checked <- ChatUsage(
        inputTokens = input,
        outputTokens = output,
        cachedTokens = cached,
        reasoningTokens = reasoning
      ).validated
    yield checked

  private def optionalCount(
      value: Json.Obj,
      details: String,
      key: String
  ): Either[AgentError, Int] = value.get(details) match
    case None | Some(Json.Null) => Right(0)
    case Some(raw) =>
      obj(raw).flatMap(value => if value.get(key).isEmpty then Right(0) else integer(value, key))

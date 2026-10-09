/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.model.{ChatUsage, ContentBlock, Msg, ToolResultState}
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/** Native block indices, lifecycle and replay order remain independent of OpenAI assembly. */
final private[client] case class AnthropicMessageStream private (
    private val stage: AnthropicMessageStream.Stage,
    private val blocks: Chunk[AnthropicMessageStream.Block],
    private val nativeBlocks: Chunk[Json.Obj],
    private val webSearch: Option[AnthropicWebSearch],
    closed: Set[Int],
    usage: ChatUsage
):
  import AnthropicMessageStream.*

  private def started: Boolean = stage != Stage.AwaitingMessage
  private def done: Boolean = stage match
    case Stage.Done(_) => true
    case _             => false
  private def stopReason: Option[String] = stage match
    case Stage.Finishing(reason) => Some(reason)
    case Stage.Done(reason)      => Some(reason)
    case _                       => None

  def accept(frame: SseEvent): Either[AgentError, (AnthropicMessageStream, Chunk[LlmChunk])] =
    for
      json <- JsonBoundary.decode(frame.data).left.map(AgentError.DeserializationError(_))
      obj <- json.asObject.toRight(
        AgentError.DeserializationError("Anthropic event must be an object")
      )
      kind <- string(obj, "type")
      _    <- ensure(frame.event.contains(kind), "A matching named Anthropic SSE event is required")
      _    <- ensure(!done, "Event arrived after message_stop")
      result <- event(kind, obj)
    yield result

  private def event(
      kind: String,
      obj: Json.Obj
  ): Either[AgentError, (AnthropicMessageStream, Chunk[LlmChunk])] = kind match
    case "ping" => Right((this, Chunk.empty))
    case "error" =>
      Left(
        AgentError.ModelError(
          200,
          "Anthropic stream reported an error",
          Some(JsonBoundary.render(obj))
        )
      )
    case "message_start" =>
      for
        _       <- ensure(!started, "Duplicate message_start")
        message <- objectField(obj, "message")
        role    <- string(message, "role")
        _ <- ensure(
          role == "assistant" && message.get("content").contains(Json.Arr()),
          "Invalid initial Anthropic message"
        )
        counts    <- objectField(message, "usage")
        nextUsage <- updateUsage(counts, ChatUsage())
      yield (copy(stage = Stage.Content, usage = nextUsage), Chunk(LlmChunk.UsageChunk(nextUsage)))
    case "content_block_start" =>
      for
        _ <- ensure(
          started && stopReason.isEmpty && closed.size == blocks.size,
          "Content start outside native block sequence"
        )
        index <- integer(obj, "index")
        _ <- ensure(index == blocks.size, "Anthropic block indices must be distinct and ordered")
        value <- objectField(obj, "content_block")
        block <- startBlock(value, webSearch)
        _ <- block match
          case Block.Tool(id, _, _, _, _) =>
            ensure(
              !blocks.exists {
                case Block.Tool(other, _, _, _, _) => other == id
                case _                             => false
              },
              "Duplicate Anthropic tool ID"
            )
          case Block.SearchResult(_, result) =>
            ensure(
              blocks.exists {
                case Block.Tool(id, _, _, _, ToolOrigin.WebSearch) => id == result.id
                case _                                             => false
              },
              "Search result precedes its hosted call"
            )
          case _ => Right(())
      yield (
        copy(blocks = blocks :+ block, nativeBlocks = nativeBlocks :+ value),
        initialDeltas(index, block)
      )
    case "content_block_delta" =>
      for
        index <- integer(obj, "index")
        _ <- ensure(
          started && stopReason.isEmpty && !closed.contains(index),
          "Delta outside an open block"
        )
        previous <- blocks
          .lift(index)
          .toRight(AgentError.DeserializationError("Delta references an unknown block"))
        delta <- objectField(obj, "delta")
        kind  <- string(delta, "type")
        next <-
          if kind == "citations_delta" then
            for
              _ <- ensure(
                webSearch.nonEmpty && previous.isInstanceOf[Block.Text],
                "Citations require enabled web search and a text block"
              )
              citation <- objectField(delta, "citation")
            yield (previous, Chunk.empty[LlmChunk], Some(citation))
          else updateBlock(index, previous, delta).map(value => (value._1, value._2, None))
        original <- nativeBlocks
          .lift(index)
          .toRight(AgentError.DeserializationError("Missing native block"))
        raw <- next._3 match
          case None => Right(original)
          case Some(citation) =>
            val citations = original.get("citations").flatMap(_.asArray).getOrElse(Chunk.empty)
            Right(replaceFields(original, Chunk("citations" -> Json.Arr(citations :+ citation))))
      yield (
        copy(
          blocks = blocks.updated(index, next._1),
          nativeBlocks = nativeBlocks.updated(index, raw)
        ),
        next._2
      )
    case "content_block_stop" =>
      for
        index <- integer(obj, "index")
        _ <- blocks
          .lift(index)
          .toRight(AgentError.DeserializationError("Stop references an unknown block"))
        _ <- ensure(
          started && stopReason.isEmpty && !closed.contains(index),
          "Duplicate or late block stop"
        )
      yield (copy(closed = closed + index), Chunk.empty)
    case "message_delta" =>
      for
        _ <- ensure(
          started && closed.size == blocks.size && stopReason.isEmpty,
          "Invalid message_delta sequence"
        )
        delta     <- objectField(obj, "delta")
        reason    <- string(delta, "stop_reason")
        counts    <- objectField(obj, "usage")
        nextUsage <- updateUsage(counts, usage)
      yield (
        copy(stage = Stage.Finishing(reason), usage = nextUsage),
        Chunk(LlmChunk.UsageChunk(nextUsage))
      )
    case "message_stop" =>
      for
        _ <- ensure(
          started && closed.size == blocks.size && stopReason.nonEmpty,
          "Premature message_stop"
        )
        next = copy(stage = Stage.Done(stopReason.getOrElse("")))
        _ <- next.output
      yield (next, Chunk.empty)
    case _ => Left(AgentError.DeserializationError(s"Unsupported Anthropic event: $kind"))

  def output: Either[AgentError, TurnOutput] =
    if done && stopReason.contains("max_tokens") then
      Right(TurnOutput.tokenExhausted(partial.message))
    else
      for
        _ <- ensure(done, "Anthropic stream ended before message_stop")
        content <- blocks.foldLeft[Either[AgentError, Chunk[ContentBlock]]](Right(Chunk.empty))(
          (previous, block) =>
            for values <- previous; value <- completeBlock(block) yield values :+ value
        )
        _ <- validateHostedResults(content)
        hasTools = content.exists(_.isInstanceOf[ContentBlock.ToolUse])
        hasLocal = content.exists {
          case call: ContentBlock.ToolUse => !call.isServerTool
          case _                          => false
        }
        hostedComplete = content
          .collect { case call: ContentBlock.ToolUse if call.isServerTool => call.id }
          .forall(id =>
            content.exists {
              case result: ContentBlock.ToolResult =>
                result.id == id && result.state != ToolResultState.Running
              case _ => false
            }
          )
        _ <- ensure(
          stopReason.exists {
            case "end_turn" | "stop_sequence" => !hasLocal && hostedComplete
            case "tool_use"                   => hasLocal && hostedComplete
            case _                            => false
          },
          s"Incomplete, unsupported or inconsistent Anthropic stop reason: ${stopReason.getOrElse("absent")}"
        )
        raw <- wireBlocks(blocks, nativeBlocks)
        message = AnthropicNativeReplay.attach(
          Msg.assistant(
            content,
            metadata = Map("anthropic_stop_reason" -> Json.Str(stopReason.getOrElse(""))),
            usage = Some(usage)
          ),
          raw
        )
      yield
        if hasTools then TurnOutput.ToolCalls(message)
        else if message.text.isBlank && message.hasReasoning then TurnOutput.ThinkingOnly(message)
        else TurnOutput.FinalText(message)
  def partial: TurnOutput.Interrupted =
    val content = blocks.zipWithIndex.map: (block, index) =>
      block match
        case Block.Text(value) => ContentBlock.Text(value)
        case Block.Thinking(value, signature) =>
          ContentBlock.Thinking(
            value,
            if closed.contains(index) then Map("thoughtSignature" -> Json.Str(signature))
            else Map("anthropic_incomplete_thinking"              -> Json.Bool(true))
          )
        case Block.Tool(id, name, initial, fragments, origin) =>
          val input = arguments(initial, fragments).getOrElse(Json.Obj())
          nativeCall(id, name, input, fragments, origin)
        case Block.SearchResult(_, result) => result
    TurnOutput.Interrupted(Msg.assistant(content, usage = Some(usage)))

private[client] object AnthropicMessageStream:
  private enum Stage:
    case AwaitingMessage, Content
    case Finishing(reason: String)
    case Done(reason: String)

  private enum ToolOrigin:
    case Local, WebSearch

  private enum Block:
    case Text(value: String)
    case Thinking(value: String, signature: String)
    case Tool(id: String, name: String, initial: Json.Obj, fragments: String, origin: ToolOrigin)
    case SearchResult(value: Json.Obj, result: ContentBlock.ToolResult)

  def empty(webSearch: Option[AnthropicWebSearch]): AnthropicMessageStream =
    AnthropicMessageStream(
      Stage.AwaitingMessage,
      Chunk.empty,
      Chunk.empty,
      webSearch,
      Set.empty,
      ChatUsage()
    )

  private def ensure(condition: Boolean, message: String): Either[AgentError, Unit] =
    Either.cond(condition, (), AgentError.DeserializationError(message))

  private def string(obj: Json.Obj, name: String): Either[AgentError, String] =
    obj
      .get(name)
      .toRight(s"Missing Anthropic field: $name")
      .flatMap(_.as[String])
      .left
      .map(AgentError.DeserializationError(_))

  private def integer(obj: Json.Obj, name: String): Either[AgentError, Int] =
    obj
      .get(name)
      .toRight(s"Missing Anthropic integer: $name")
      .flatMap(_.as[Int])
      .flatMap(value => Either.cond(value >= 0, value, "Negative Anthropic index/count"))
      .left
      .map(AgentError.DeserializationError(_))

  private def objectField(obj: Json.Obj, name: String): Either[AgentError, Json.Obj] =
    obj
      .get(name)
      .flatMap(_.asObject)
      .toRight(AgentError.DeserializationError(s"Missing Anthropic object: $name"))

  private def updateUsage(obj: Json.Obj, previous: ChatUsage): Either[AgentError, ChatUsage] =
    def count(name: String, default: Int): Either[AgentError, Int] =
      if obj.get(name).isEmpty then Right(default) else integer(obj, name)
    for
      input    <- count("input_tokens", previous.inputTokens)
      output   <- count("output_tokens", previous.outputTokens)
      cached   <- count("cache_read_input_tokens", previous.cachedTokens)
      creation <- count("cache_creation_input_tokens", previous.cacheCreationTokens)
      next <- previous
        .copy(
          inputTokens = input,
          outputTokens = output,
          cachedTokens = cached,
          cacheCreationTokens = creation
        )
        .validated
    yield next

  private def startBlock(
      obj: Json.Obj,
      webSearch: Option[AnthropicWebSearch]
  ): Either[AgentError, Block] =
    string(obj, "type").flatMap:
      case "text" => string(obj, "text").map(Block.Text(_))
      case "thinking" =>
        for
          thinking <- string(obj, "thinking")
          signature <- obj.get("signature") match
            case None        => Right("")
            case Some(value) => value.as[String].left.map(AgentError.DeserializationError(_))
        yield Block.Thinking(thinking, signature)
      case kind @ ("tool_use" | "server_tool_use") =>
        for
          id   <- string(obj, "id")
          name <- string(obj, "name")
          _    <- ensure(!id.isBlank && !name.isBlank, "Blank Anthropic tool identity")
          origin <-
            if kind == "tool_use" then Right(ToolOrigin.Local)
            else
              ensure(webSearch.nonEmpty && name == "web_search", "Unselected native hosted tool")
                .map(_ => ToolOrigin.WebSearch)
          input <-
            if origin == ToolOrigin.WebSearch && obj.get("input").isEmpty then Right(Json.Obj())
            else objectField(obj, "input")
        yield Block.Tool(id, name, input, "", origin)
      case "web_search_tool_result" =>
        ensure(webSearch.nonEmpty, "Unselected native web-search result").flatMap(_ =>
          searchResult(obj).map(result => Block.SearchResult(obj, result))
        )
      case kind =>
        Left(
          AgentError.DeserializationError(
            s"Unsupported Anthropic block: $kind; redacted thinking and other hosted tools are not enabled"
          )
        )

  private def initialDeltas(index: Int, block: Block): Chunk[LlmChunk] = block match
    case Block.Text(value) => Chunk(LlmChunk.TextDelta(value, index))
    case Block.Thinking(value, signature) =>
      Chunk(LlmChunk.ThinkingDelta(value, index, Option.when(signature.nonEmpty)(signature)))
    case Block.Tool(id, name, _, _, _) => Chunk(LlmChunk.ToolCallChunk(index, Some(id), Some(name)))
    case _: Block.SearchResult         => Chunk.empty

  private def updateBlock(
      index: Int,
      block: Block,
      delta: Json.Obj
  ): Either[AgentError, (Block, Chunk[LlmChunk])] =
    string(delta, "type").flatMap:
      case "text_delta" =>
        block match
          case Block.Text(previous) =>
            string(delta, "text").map(value =>
              (Block.Text(previous + value), Chunk(LlmChunk.TextDelta(value, index)))
            )
          case _ => Left(AgentError.DeserializationError("Text delta in a non-text block"))
      case "thinking_delta" =>
        block match
          case Block.Thinking(previous, signature) if signature.isEmpty =>
            string(delta, "thinking").map(value =>
              (
                Block.Thinking(previous + value, signature),
                Chunk(LlmChunk.ThinkingDelta(value, index))
              )
            )
          case _ =>
            Left(AgentError.DeserializationError("Thinking delta outside unsigned thinking block"))
      case "signature_delta" =>
        block match
          case Block.Thinking(value, previous) =>
            string(delta, "signature").map(signature =>
              (
                Block.Thinking(value, previous + signature),
                Chunk(LlmChunk.ThinkingDelta("", index, Some(signature)))
              )
            )
          case _ => Left(AgentError.DeserializationError("Signature delta in a non-thinking block"))
      case "input_json_delta" =>
        block match
          case Block.Tool(id, name, initial, previous, origin) if initial == Json.Obj() =>
            string(delta, "partial_json").map(value =>
              (
                Block.Tool(id, name, initial, previous + value, origin),
                Chunk(LlmChunk.ToolCallChunk(index, argumentsDelta = value))
              )
            )
          case _ =>
            Left(
              AgentError.DeserializationError("Input delta outside an initially empty tool block")
            )
      case other => Left(AgentError.DeserializationError(s"Unsupported Anthropic delta: $other"))

  private def arguments(initial: Json.Obj, fragments: String): Either[AgentError, Json.Obj] =
    if fragments.isEmpty then Right(initial)
    else
      JsonBoundary
        .decode(fragments)
        .left
        .map(AgentError.DeserializationError(_))
        .flatMap(
          _.asObject
            .toRight(AgentError.DeserializationError("Anthropic tool input must be an object"))
        )

  private def completeBlock(block: Block): Either[AgentError, ContentBlock] = block match
    case Block.Text(value) => Right(ContentBlock.Text(value))
    case Block.Thinking(value, signature) =>
      ensure(!signature.isBlank, "Completed Anthropic thinking lacks a signature")
        .map(_ => ContentBlock.Thinking(value, Map("thoughtSignature" -> Json.Str(signature))))
    case Block.Tool(id, name, initial, fragments, origin) =>
      arguments(initial, fragments).map(input => nativeCall(id, name, input, fragments, origin))
    case Block.SearchResult(_, result) => Right(result)

  private def searchResult(obj: Json.Obj): Either[AgentError, ContentBlock.ToolResult] =
    for
      id <- string(obj, "tool_use_id")
      _  <- ensure(!id.isBlank, "Blank native search-result identity")
      content <- obj
        .get("content")
        .toRight(AgentError.DeserializationError("Native search result lacks content"))
      state <- content match
        case Json.Arr(results) =>
          results
            .foldLeft[Either[AgentError, Unit]](Right(()))((previous, value) =>
              for
                _ <- previous
                entry <- value.asObject
                  .toRight(AgentError.DeserializationError("Search entries must be objects"))
                kind <- string(entry, "type")
                _    <- ensure(kind == "web_search_result", "Unsupported search-result entry")
                _    <- string(entry, "url")
                _    <- string(entry, "title")
                _    <- string(entry, "encrypted_content")
              yield ()
            )
            .map(_ => ToolResultState.Success)
        case error: Json.Obj =>
          for
            kind <- string(error, "type")
            _ <- ensure(kind == "web_search_tool_result_error", "Unsupported search-result error")
            _ <- string(error, "error_code")
          yield ToolResultState.Error
        case _ =>
          Left(
            AgentError.DeserializationError("Search result must contain an array or error object")
          )
    yield ContentBlock.ToolResult(
      id,
      Some("web_search"),
      Chunk(ContentBlock.Text(JsonBoundary.render(content))),
      state,
      Map("agentscope_server_tool" -> Json.Bool(true), "anthropic_web_search_result" -> obj)
    )

  private def validateHostedResults(content: Chunk[ContentBlock]): Either[AgentError, Unit] =
    val calls   = content.collect { case call: ContentBlock.ToolUse => call }
    val results = content.collect { case result: ContentBlock.ToolResult => result }
    for
      _ <- ensure(calls.map(_.id).distinct.size == calls.size, "Duplicate native tool IDs")
      _ <- ensure(
        results.map(_.id).distinct.size == results.size,
        "Duplicate native search results"
      )
      _ <- ensure(
        results.forall(result =>
          calls.exists(call =>
            call.isServerTool && call.id == result.id && call.name == "web_search"
          )
        ),
        "Native result has no corresponding hosted search call"
      )
    yield ()

  private def replaceFields(original: Json.Obj, values: Chunk[(String, Json)]): Json.Obj =
    val keys = values.map(_._1).toSet
    Json.Obj(original.fields.filterNot(value => keys.contains(value._1)) ++ values)

  private def wireBlocks(
      blocks: Chunk[Block],
      originals: Chunk[Json.Obj]
  ): Either[AgentError, Chunk[Json.Obj]] =
    blocks
      .zip(originals)
      .foldLeft[Either[AgentError, Chunk[Json.Obj]]](Right(Chunk.empty)):
        case (previous, (block, original)) =>
          for
            accumulated <- previous
            updated <- block match
              case Block.Text(value) =>
                Right(replaceFields(original, Chunk("text" -> Json.Str(value))))
              case Block.Thinking(value, signature) =>
                Right(
                  replaceFields(
                    original,
                    Chunk("thinking" -> Json.Str(value), "signature" -> Json.Str(signature))
                  )
                )
              case Block.Tool(_, _, initial, fragments, _) =>
                arguments(initial, fragments)
                  .map(input => replaceFields(original, Chunk("input" -> input)))
              case Block.SearchResult(value, _) => Right(value)
          yield accumulated :+ updated

  /** Used both when accepting provider output and when validating immutable native replay. */
  def canonicalBlocks(
      values: Chunk[Json.Obj],
      webSearch: Option[AnthropicWebSearch]
  ): Either[AgentError, Chunk[ContentBlock]] =
    for
      content <- values.foldLeft[Either[AgentError, Chunk[ContentBlock]]](Right(Chunk.empty))(
        (previous, value) =>
          for
            accumulated <- previous
            block       <- startBlock(value, webSearch)
            canonical   <- completeBlock(block)
          yield accumulated :+ canonical
      )
      _ <- validateHostedResults(content)
    yield content

  private def nativeCall(
      id: String,
      name: String,
      input: Json.Obj,
      fragments: String,
      origin: ToolOrigin
  ): ContentBlock.ToolUse =
    val call = ContentBlock.ToolUse(id, name, input, content = Some(fragments))
    origin match
      case ToolOrigin.Local     => call
      case ToolOrigin.WebSearch => call.withServerTool(true)

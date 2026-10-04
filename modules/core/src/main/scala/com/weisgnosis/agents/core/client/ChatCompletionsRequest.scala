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
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, MediaSource, Msg, Role, ToolResultState}
import com.weisgnosis.agents.core.tool.ToolDef
import zio.Chunk
import zio.http.{Scheme, URL}
import zio.json.*
import zio.json.ast.Json

/** Explicit reasoning wire policy shared by response decoding and historical replay. */
enum ReasoningReplay derives JsonCodec:
  case Omit, LatestAssistant, OpenRouterEncryptedDetails, OpenRouterDetails

/** Explicit wire format for opaque per-call signatures; unrelated metadata is never projected. */
enum ToolSignatureFormat derives JsonCodec:
  case Unsupported, GoogleExtraContent

/** Pure serialization of text, user images/inline audio, and function calls; media sources are never fetched. */
object ChatCompletionsRequest:
  private val namePattern = "[A-Za-z0-9_-]{1,64}".r
  private val imageMediaTypes: Set[String] =
    Set("image/png", "image/jpeg", "image/webp", "image/gif")
  private val audioFormats: Map[String, String] = Map("audio/wav" -> "wav", "audio/mpeg" -> "mp3")

  def encode(
      history: Chunk[Msg],
      tools: Chunk[ToolDef],
      config: LlmConfig,
      reasoningReplay: ReasoningReplay = ReasoningReplay.Omit,
      toolSignatureFormat: ToolSignatureFormat = ToolSignatureFormat.Unsupported
  ): Either[AgentError, Json.Obj] =
    val lastAssistant = history.lastIndexWhere(_.role == Role.Assistant)
    for
      _ <- message(history.nonEmpty, "At least one message is required")
      _ <- validatePairing(history)
      _ <- configuration(config.model.trim.nonEmpty, "Model name must not be blank")
      _ <- configuration(
        config.temperature.isFinite && config.temperature >= 0 && config.temperature <= 2,
        "Temperature must be finite and between zero and two"
      )
      _ <- configuration(config.maxTokens.forall(_ > 0), "Maximum tokens must be positive")
      _ <- configuration(
        config.topP.forall(value => value.isFinite && value >= 0 && value <= 1),
        "topP must be finite and between 0 and 1"
      )
      _ <- configuration(
        config.responseFormat.forall(_.isInstanceOf[Json.Obj]),
        "Response format must be an object"
      )
      _ <- configuration(
        tools.map(_.name).distinct.size == tools.size,
        "Tool definitions must have unique names"
      )
      definitions <- traverse(tools)(encodeDefinition)
      choice      <- encodeToolChoice(config.toolChoice, tools)
      messages <- traverse(history.zipWithIndex): (msg, index) =>
        val replay = reasoningReplay match
          case ReasoningReplay.LatestAssistant if index == lastAssistant =>
            ReasoningReplay.LatestAssistant
          case ReasoningReplay.OpenRouterEncryptedDetails =>
            ReasoningReplay.OpenRouterEncryptedDetails
          case ReasoningReplay.OpenRouterDetails => ReasoningReplay.OpenRouterDetails
          case _                                 => ReasoningReplay.Omit
        encodeMessage(msg, replay, toolSignatureFormat)
      body = Json.Obj(
        Chunk(
          "model"          -> Json.Str(config.model),
          "messages"       -> Json.Arr(messages.flatten),
          "stream"         -> Json.Bool(true),
          "stream_options" -> Json.Obj("include_usage" -> Json.Bool(true)),
          "temperature"    -> Json.Num(config.temperature)
        ) ++
          Chunk.fromIterable(config.maxTokens.map(value => "max_tokens" -> Json.Num(value))) ++
          Chunk.fromIterable(config.topP.map(value => "top_p" -> Json.Num(value))) ++
          Chunk.fromIterable(config.responseFormat.map(value => "response_format" -> value)) ++
          Chunk.fromIterable(choice.map(value => "tool_choice" -> value)) ++
          (if definitions.isEmpty then Chunk.empty else Chunk("tools" -> Json.Arr(definitions)))
      )
      _ <- JsonBoundary.validate(body).left.map(AgentError.InvalidConfiguration(_))
    yield body

  private def encodeToolChoice(
      choice: Option[ToolChoice],
      tools: Chunk[ToolDef]
  ): Either[AgentError, Option[Json]] =
    choice match
      case None => Right(None)
      case Some(ToolChoice.Auto) =>
        Right(Option.when(tools.nonEmpty)(Json.Str("auto")))
      case Some(ToolChoice.None) =>
        Right(Option.when(tools.nonEmpty)(Json.Str("none")))
      case Some(ToolChoice.Required) =>
        configuration(tools.nonEmpty, "Required tool choice needs at least one advertised tool")
          .map(_ => Some(Json.Str("required")))
      case Some(ToolChoice.Specific(name)) =>
        configuration(
          namePattern.matches(name) && tools.exists(_.name == name),
          "Specific tool choice must name an advertised function tool"
        ).map(_ =>
          Some(
            Json.Obj(
              "type"     -> Json.Str("function"),
              "function" -> Json.Obj("name" -> Json.Str(name))
            )
          )
        )

  private def validatePairing(history: Chunk[Msg]): Either[AgentError, Unit] =
    history
      .foldLeft[Either[AgentError, Set[String]]](Right(Set.empty))((acc, msg) =>
        acc.flatMap: pending =>
          if msg.role == Role.Tool then
            msg.toolResults.foldLeft[Either[AgentError, Set[String]]](Right(pending))(
              (results, result) =>
                results.flatMap(ids =>
                  Either.cond(
                    ids.contains(result.id),
                    ids - result.id,
                    AgentError.InvalidMessage("Orphan or duplicate tool result")
                  )
                )
            )
          else
            message(
              pending.isEmpty,
              "Tool calls must receive results before the next conversation message"
            )
              .map(_ => msg.toolUses.map(_.id).toSet)
      )
      .flatMap(pending => message(pending.isEmpty, "Conversation ends with unresolved tool calls"))

  private def encodeDefinition(tool: ToolDef): Either[AgentError, Json] =
    for
      _ <- configuration(namePattern.matches(tool.name), "Invalid function tool name")
      _ <- configuration(
        tool.parametersSchema.isInstanceOf[Json.Obj],
        "Function parameters schema must be an object"
      )
    yield Json.Obj(
      "type" -> Json.Str("function"),
      "function" -> Json.Obj(
        "name"        -> Json.Str(tool.name),
        "description" -> Json.Str(tool.description),
        "parameters"  -> tool.parametersSchema
      )
    )

  private def encodeMessage(
      msg: Msg,
      replay: ReasoningReplay,
      toolSignatureFormat: ToolSignatureFormat
  ): Either[AgentError, Chunk[Json]] =
    msg.role match
      case Role.Tool =>
        for
          _ <- message(
            msg.content.nonEmpty && msg.content.forall(_.isInstanceOf[ContentBlock.ToolResult]),
            "Tool messages require explicit tool-result blocks"
          )
          results <- traverse(msg.toolResults)(encodeResult)
        yield results
      case Role.Assistant => encodeAssistant(msg, replay, toolSignatureFormat).map(Chunk(_))
      case Role.System | Role.User =>
        for
          content <-
            if msg.role == Role.User then encodeUserContent(msg)
            else
              message(
                msg.content.forall(_.isInstanceOf[ContentBlock.Text]),
                "This request codec only supports text in system messages"
              ).map(_ => Json.Str(msg.text))
          name <- messageName(msg)
        yield Chunk(
          Json.Obj(
            Chunk(
              "role"    -> Json.Str(if msg.role == Role.System then "system" else "user"),
              "content" -> content
            ) ++ name
          )
        )

  private def encodeUserContent(msg: Msg): Either[AgentError, Json] =
    if msg.content.forall(_.isInstanceOf[ContentBlock.Text]) then Right(Json.Str(msg.text))
    else
      traverse(msg.content):
        case ContentBlock.Text(text) =>
          Right(Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str(text)))
        case ContentBlock.Image(source, _, _) => inputImage(source)
        case ContentBlock.Audio(source, _, _) => inputAudio(source)
        case ContentBlock.Data(source, _, _)  => dataPart(source)
        case _ =>
          Left(
            AgentError.InvalidMessage("User content supports only text, images, and inline audio")
          )
      .map(parts => Json.Arr(parts))

  private def inputImage(source: MediaSource): Either[AgentError, Json] =
    imageUrl(source).map(url =>
      Json.Obj(
        "type"      -> Json.Str("image_url"),
        "image_url" -> Json.Obj("url" -> Json.Str(url))
      )
    )

  private def dataPart(source: MediaSource): Either[AgentError, Json] = source match
    case MediaSource.Url(_, Some(mimeType)) if imageMediaTypes.contains(mimeType) =>
      inputImage(source)
    case MediaSource.Base64(mediaType, _) if imageMediaTypes.contains(mediaType) =>
      inputImage(source)
    case MediaSource.Base64(mediaType, _) if audioFormats.contains(mediaType) => inputAudio(source)
    case _ =>
      Left(
        AgentError.InvalidMessage(
          "Data blocks require supported inline media or an image URL with an explicit supported MIME hint"
        )
      )

  private def inputAudio(source: MediaSource): Either[AgentError, Json] = source match
    case MediaSource.Url(_, _) =>
      Left(
        AgentError.InvalidMessage("Audio requires inline base64 data; audio URLs are unsupported")
      )
    case MediaSource.Base64(mediaType, data) =>
      for
        format <- audioFormats
          .get(mediaType)
          .toRight(
            AgentError.InvalidMessage("Inline audio requires audio/wav or audio/mpeg")
          )
        _ <- message(validBase64(data), "Inline audio data must be nonempty standard base64")
      yield Json.Obj(
        "type"        -> Json.Str("input_audio"),
        "input_audio" -> Json.Obj("data" -> Json.Str(data), "format" -> Json.Str(format))
      )

  private def imageUrl(source: MediaSource): Either[AgentError, String] = source match
    case MediaSource.Url(value, _) =>
      for
        _ <- message(
          value.nonEmpty && value.forall(character =>
            !character.isWhitespace && !Character.isISOControl(character)
          ),
          "Image URLs must not contain raw whitespace or control characters"
        )
        url <- URL.decode(value).left.map(_ => AgentError.InvalidMessage("Invalid image URL"))
        _ <- message(
          url.scheme.exists(scheme => scheme == Scheme.HTTP || scheme == Scheme.HTTPS) &&
            url.host.exists(_.nonEmpty) && url.port.forall(port => port > 0 && port <= 65535),
          "Image URLs must be absolute HTTP or HTTPS URLs with a host and valid port"
        )
      yield value
    case MediaSource.Base64(mediaType, data) =>
      for
        _ <- message(
          imageMediaTypes.contains(mediaType),
          "Inline images require an explicit PNG, JPEG, WEBP, or GIF media type"
        )
        _ <- message(validBase64(data), "Inline image data must be nonempty standard base64")
      yield s"data:$mediaType;base64,$data"

  /** Syntax validation only: no media decoding, animation inspection, or size-limit claim. */
  private def validBase64(value: String): Boolean =
    val firstPadding = value.indexOf('=')
    val contentEnd   = if firstPadding < 0 then value.length else firstPadding
    val padding      = value.length - contentEnd
    value.nonEmpty && value.length % 4 == 0 && contentEnd > 0 && padding <= 2 &&
    value.iterator.zipWithIndex.forall: (character, index) =>
      if index >= contentEnd then character == '='
      else
        (character >= 'A' && character <= 'Z') ||
        (character >= 'a' && character <= 'z') ||
        (character >= '0' && character <= '9') || character == '+' || character == '/'

  private def encodeAssistant(
      msg: Msg,
      replay: ReasoningReplay,
      toolSignatureFormat: ToolSignatureFormat
  ): Either[AgentError, Json] =
    for
      _ <- message(
        msg.content.forall(assistantContent),
        "Unsupported assistant content for the text/function protocol"
      )
      _ <- message(
        msg.toolUses.map(_.id).distinct.size == msg.toolUses.size,
        "Assistant tool call IDs must be unique"
      )
      name  <- messageName(msg)
      calls <- traverse(msg.toolUses)(call => encodeCall(call, toolSignatureFormat))
      details = msg.content
        .collect { case thought: ContentBlock.Thinking => thought }
        .flatMap(_.metadata.get("reasoningDetails"))
      _ <- message(
        details.isEmpty || replay == ReasoningReplay.OpenRouterEncryptedDetails ||
          replay == ReasoningReplay.OpenRouterDetails,
        "Reasoning details require an explicit replay profile"
      )
      reasoning <- replay match
        case ReasoningReplay.Omit => Right(Chunk.empty)
        case ReasoningReplay.LatestAssistant =>
          for _ <- message(
              msg.content
                .collect { case thought: ContentBlock.Thinking => thought }
                .forall(_.metadata.isEmpty),
              "Signed or detailed reasoning requires a provider-specific replay codec"
            )
          yield
            if msg.thinking.isEmpty then Chunk.empty
            else Chunk("reasoning_content" -> Json.Str(msg.thinking))
        case profile @ (ReasoningReplay.OpenRouterEncryptedDetails |
            ReasoningReplay.OpenRouterDetails) =>
          for
            _ <- message(
              msg.content
                .collect { case thought: ContentBlock.Thinking => thought }
                .forall(_.metadata.keySet.subsetOf(Set("reasoningDetails"))),
              "Unsupported thinking metadata for detailed reasoning replay"
            )
            arrays <- traverse(details)(value =>
              val checked = profile match
                case ReasoningReplay.OpenRouterEncryptedDetails =>
                  EncryptedReasoningDetail.decodeArray(value).map(_.map(_.toDetail))
                case _ => ReasoningDetail.decodeArray(value)
              checked.left.map(AgentError.InvalidMessage(_))
            )
          yield
            if details.isEmpty then Chunk.empty
            else Chunk("reasoning_details" -> Json.Arr(arrays.flatten.map(_.value)))
    yield Json.Obj(
      Chunk(
        "role"    -> Json.Str("assistant"),
        "content" -> Json.Str(msg.text)
      ) ++ name ++ reasoning ++
        (if calls.isEmpty then Chunk.empty else Chunk("tool_calls" -> Json.Arr(calls)))
    )

  private def encodeCall(
      call: ContentBlock.ToolUse,
      toolSignatureFormat: ToolSignatureFormat
  ): Either[AgentError, Json] =
    for
      _ <- message(
        call.id.trim.nonEmpty && namePattern.matches(call.name),
        "Tool calls require a nonblank ID and valid function name"
      )
      _ <- message(
        !call.isServerTool && !call.metadata.contains("reasoningDetails"),
        "Server tools and reasoning details require another request codec"
      )
      signature <- call.metadata.get("thoughtSignature") match
        case None => Right(Chunk.empty)
        case Some(Json.Str(value)) if !value.isBlank =>
          toolSignatureFormat match
            case ToolSignatureFormat.Unsupported =>
              Left(
                AgentError.InvalidMessage("Tool thought signatures require an explicit wire format")
              )
            case ToolSignatureFormat.GoogleExtraContent =>
              Right(
                Chunk(
                  "extra_content" -> Json.Obj(
                    "google" -> Json.Obj("thought_signature" -> Json.Str(value))
                  )
                )
              )
        case _ =>
          Left(AgentError.InvalidMessage("Tool thought signature must be a nonblank string"))
      _ <- message(call.input.isInstanceOf[Json.Obj], "Function call arguments must be an object")
      _ <- JsonBoundary.validate(call.input).left.map(AgentError.InvalidMessage(_))
    yield Json.Obj(
      Chunk(
        "id"   -> Json.Str(call.id),
        "type" -> Json.Str("function"),
        "function" -> Json.Obj(
          "name"      -> Json.Str(call.name),
          "arguments" -> Json.Str(call.input.toJson)
        )
      ) ++ signature
    )

  private def encodeResult(result: ContentBlock.ToolResult): Either[AgentError, Json] =
    for
      _ <- message(result.id.trim.nonEmpty, "Tool results require a call ID")
      _ <- message(
        !result.isServerTool && !result.isSuspended,
        "Server or suspended tool results require a different protocol"
      )
      _ <- message(
        result.output.forall(_.isInstanceOf[ContentBlock.Text]),
        "This request codec only supports text tool output"
      )
      output <- result.state match
        case ToolResultState.Success => Right(result.outputText)
        case ToolResultState.Error   => Right(classifiedOutput("ERROR", result.outputText))
        case ToolResultState.Interrupted =>
          Right(classifiedOutput("INTERRUPTED", result.outputText))
        case ToolResultState.Denied => Right(classifiedOutput("DENIED", result.outputText))
        case ToolResultState.Running =>
          Left(
            AgentError.InvalidMessage("In-flight tool results cannot be serialized as observations")
          )
    yield Json.Obj(
      "role"         -> Json.Str("tool"),
      "tool_call_id" -> Json.Str(result.id),
      "content"      -> Json.Str(output)
    )

  private def classifiedOutput(label: String, text: String): String =
    val prefix = s"[$label] "
    if text.startsWith(prefix) then text else prefix + text

  private def assistantContent(block: ContentBlock): Boolean = block match
    case _: ContentBlock.Text | _: ContentBlock.Thinking | _: ContentBlock.ToolUse => true
    case _                                                                         => false

  private def messageName(msg: Msg): Either[AgentError, Chunk[(String, Json)]] =
    message(msg.name.forall(namePattern.matches), "Invalid protocol message name")
      .map(_ => Chunk.fromIterable(msg.name.map(value => "name" -> Json.Str(value))))

  private def configuration(condition: Boolean, reason: String): Either[AgentError, Unit] =
    Either.cond(condition, (), AgentError.InvalidConfiguration(reason))

  private def message(condition: Boolean, reason: String): Either[AgentError, Unit] =
    Either.cond(condition, (), AgentError.InvalidMessage(reason))

  private def traverse[A, B](
      values: Chunk[A]
  )(f: A => Either[AgentError, B]): Either[AgentError, Chunk[B]] =
    values.foldLeft[Either[AgentError, Chunk[B]]](Right(Chunk.empty))((acc, value) =>
      for
        previous <- acc
        next     <- f(value)
      yield previous :+ next
    )

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

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.{ToolDef, ToolReturnMode, ToolSchemaStrictness}
import zio.Chunk
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ChatCompletionsRequestSpec extends ZIOSpecDefault:
  private val config: LlmConfig = LlmConfig("test-model")
  private val schema: Json      = Json.Obj("type" -> Json.Str("object"), "properties" -> Json.Obj())
  private val definition: ToolDef = ToolDef("search", "Search text", schema)

  private def messages(result: Either[AgentError, Json.Obj]): Option[Chunk[Json]] =
    result.toOption.flatMap(_.get("messages")).flatMap(_.asArray)

  private def preservesNonStreamingPayload(
      history: Chunk[Msg],
      tools: Chunk[ToolDef],
      selected: LlmConfig
  ): Boolean =
    ChatCompletionsRequest
      .encodeNonStreaming(history, tools, selected)
      .exists(nonStreaming =>
        nonStreaming.get("stream").contains(Json.Bool(false)) &&
          nonStreaming.get("stream_options").isEmpty &&
          ChatCompletionsRequest
            .encode(history, tools, selected)
            .exists(streaming =>
              streaming.get("stream").contains(Json.Bool(true)) &&
                streaming
                  .get("stream_options")
                  .contains(Json.Obj("include_usage" -> Json.Bool(true))) &&
                Json.Obj(nonStreaming.fields.filterNot(_._1 == "stream")) ==
                Json.Obj(
                  streaming.fields
                    .filterNot(field => field._1 == "stream" || field._1 == "stream_options")
                )
            )
      )

  private def pairedHistory(msg: Msg): Chunk[Msg] = msg.role match
    case Role.Assistant if msg.toolUses.nonEmpty =>
      Chunk(
        msg,
        Msg.tool(
          msg.toolUses
            .map(_.id)
            .distinct
            .map(id => ContentBlock.ToolResult.text(id, "search", "observed"))
        )
      )
    case Role.Tool if msg.toolResults.nonEmpty =>
      Chunk(
        Msg.assistant(
          msg.toolResults
            .map(_.id)
            .distinct
            .map(id => ContentBlock.ToolUse(id, "search", Json.Obj()))
        ),
        msg
      )
    case _ => Chunk(msg)

  def spec: Spec[TestEnvironment, Any] = suite("ChatCompletionsRequestSpec")(
    test(
      "explicit image-part tool profile preserves ordered observations and rejects unsupported output"
    ):
      val image   = ContentBlock.Image(MediaSource.Base64("image/png", "aGVsbG8="))
      val output  = Chunk(ContentBlock.Text("before"), image, ContentBlock.Text("after"))
      val result  = ContentBlock.ToolResult("call_123", Some("search"), output)
      val history = pairedHistory(Msg.tool(Chunk(result)))
      val expected = Json.Obj(
        "role"         -> Json.Str("tool"),
        "tool_call_id" -> Json.Str("call_123"),
        "content" -> Json.Arr(
          Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str("before")),
          Json.Obj(
            "type"      -> Json.Str("image_url"),
            "image_url" -> Json.Obj("url" -> Json.Str("data:image/png;base64,aGVsbG8="))
          ),
          Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str("after"))
        )
      )
      val encoders: Chunk[(Chunk[Msg], ToolResultFormat) => Either[AgentError, Json.Obj]] = Chunk(
        (input, format) =>
          ChatCompletionsRequest.encode(input, Chunk.empty, config, toolResultFormat = format),
        (input, format) =>
          ChatCompletionsRequest.encodeNonStreaming(
            input,
            Chunk.empty,
            config,
            toolResultFormat = format
          )
      )
      val unsupported = Chunk(
        result.copy(state = ToolResultState.Error),
        result.copy(state = ToolResultState.Running),
        result.copy(metadata = Map("agentscope_server_tool" -> Json.Bool(true))),
        result.copy(metadata = Map("agentscope_suspended" -> Json.Bool(true))),
        result.copy(output =
          Chunk(ContentBlock.Audio(MediaSource.Base64("audio/wav", "aGVsbG8=")))
        ),
        result.copy(output =
          Chunk(ContentBlock.Video(MediaSource.Url("https://example.com/video.mp4")))
        ),
        result.copy(output = Chunk(ContentBlock.Data(MediaSource.Base64("image/png", "aGVsbG8=")))),
        result.copy(output =
          Chunk(ContentBlock.Image(MediaSource.Base64("image/png", "!invalid!")))
        ),
        result.copy(output =
          Chunk(ContentBlock.Image(MediaSource.Url("file:///private/image.png")))
        ),
        result.copy(output = Chunk(ContentBlock.ToolUse("nested", "search", Json.Obj())))
      )
      assertTrue(
        encoders.forall(encode =>
          messages(encode(history, ToolResultFormat.ImageParts)).contains(
            Chunk(
              Json.Obj(
                "role"    -> Json.Str("assistant"),
                "content" -> Json.Str(""),
                "name"    -> Json.Str("assistant"),
                "tool_calls" -> Json.Arr(
                  Json.Obj(
                    "id"   -> Json.Str("call_123"),
                    "type" -> Json.Str("function"),
                    "function" -> Json
                      .Obj("name" -> Json.Str("search"), "arguments" -> Json.Str("{}"))
                  )
                )
              ),
              expected
            )
          ) &&
            encode(history, ToolResultFormat.TextOnly).isLeft &&
            unsupported.forall(value =>
              encode(pairedHistory(Msg.tool(Chunk(value))), ToolResultFormat.ImageParts).isLeft
            ) &&
            encode(Chunk(Msg.tool(Chunk(result))), ToolResultFormat.ImageParts).isLeft &&
            encode(
              pairedHistory(
                Msg.tool(Chunk(result.copy(output = Chunk(ContentBlock.Text("plain")))))
              ),
              ToolResultFormat.ImageParts
            ) ==
            encode(
              pairedHistory(
                Msg.tool(Chunk(result.copy(output = Chunk(ContentBlock.Text("plain")))))
              ),
              ToolResultFormat.TextOnly
            )
        ),
        Chunk(ToolResultFormat.TextOnly, ToolResultFormat.ImageParts).forall(value =>
          value.toJson.fromJson[ToolResultFormat] == Right(value)
        )
      )
    ,
    test("serializes system user and assistant text in their original order") {
      val result = ChatCompletionsRequest.encode(
        Chunk(Msg.system("rules"), Msg.user("question"), Msg.assistant("answer")),
        Chunk.empty,
        config
      )
      assertTrue(
        messages(result).contains(
          Chunk(
            Json.Obj(
              "role"    -> Json.Str("system"),
              "content" -> Json.Str("rules"),
              "name"    -> Json.Str("system")
            ),
            Json.Obj(
              "role"    -> Json.Str("user"),
              "content" -> Json.Str("question"),
              "name"    -> Json.Str("user")
            ),
            Json.Obj(
              "role"    -> Json.Str("assistant"),
              "content" -> Json.Str("answer"),
              "name"    -> Json.Str("assistant")
            )
          )
        )
      )
    },
    test("projects ordered user media and text while preserving the text-only wire shape") {
      def imagePart(url: String): Json = Json.Obj(
        "type"      -> Json.Str("image_url"),
        "image_url" -> Json.Obj("url" -> Json.Str(url))
      )
      def videoPart(url: String): Json = Json.Obj(
        "type"      -> Json.Str("video_url"),
        "video_url" -> Json.Obj("url" -> Json.Str(url))
      )
      def audioPart(data: String, format: String): Json = Json.Obj(
        "type"        -> Json.Str("input_audio"),
        "input_audio" -> Json.Obj("data" -> Json.Str(data), "format" -> Json.Str(format))
      )
      def textPart(text: String): Json =
        Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str(text))
      val url   = "https://example.test/image%20name.png?token=opaque%2Bvalue"
      val video = "https://example.test/video%20name.mp4?token=opaque%2Bvalue#part"
      val cases = Chunk[(Chunk[Msg.UserBlock], Json)](
        Chunk(ContentBlock.Video.url("http://example.test/video")) -> Json.Arr(
          videoPart("http://example.test/video")
        ),
        Chunk[Msg.UserBlock](
          ContentBlock.Text("before"),
          ContentBlock.Image.url(url),
          ContentBlock.Video(
            MediaSource.Url(video, Some("application/octet-stream")),
            Some("private-video"),
            Some("private-name")
          ),
          ContentBlock.Text("after")
        ) -> Json.Arr(textPart("before"), imagePart(url), videoPart(video), textPart("after")),
        Chunk.empty                                                    -> Json.Str(""),
        Chunk(ContentBlock.Text("first"), ContentBlock.Text("second")) -> Json.Str("first\nsecond"),
        Chunk(ContentBlock.Image.url("http://example.test/image")) -> Json.Arr(
          imagePart("http://example.test/image")
        ),
        Chunk[Msg.UserBlock](
          ContentBlock.Text("before"),
          ContentBlock
            .Image(MediaSource.Url(url), id = Some("private-image"), name = Some("private-name")),
          ContentBlock.Text("after"),
          ContentBlock.Image.base64("image/png", "AQ=="),
          ContentBlock.Audio(
            MediaSource.Base64("audio/wav", "AQID"),
            id = Some("private-audio"),
            name = Some("private-name")
          ),
          ContentBlock.Text("between audio"),
          ContentBlock.Audio.base64("audio/mpeg", "AQI="),
          ContentBlock.Data(
            MediaSource.Base64("image/jpeg", "AQID"),
            Some("private-data"),
            Some("private-name")
          ),
          ContentBlock.Text("between data"),
          ContentBlock.Data(
            MediaSource.Base64("audio/wav", "AQ=="),
            Some("private-audio-data"),
            Some("private-name")
          ),
          ContentBlock.Data(
            MediaSource.Url("https://example.test/opaque", Some("image/jpeg")),
            Some("private-url-data"),
            Some("private-name")
          )
        ) -> Json.Arr(
          textPart("before"),
          imagePart(url),
          textPart("after"),
          imagePart("data:image/png;base64,AQ=="),
          audioPart("AQID", "wav"),
          textPart("between audio"),
          audioPart("AQI=", "mp3"),
          imagePart("data:image/jpeg;base64,AQID"),
          textPart("between data"),
          audioPart("AQ==", "wav"),
          imagePart("https://example.test/opaque")
        )
      ) ++ Chunk(
        "image/png"  -> "AQID",
        "image/jpeg" -> "AQI=",
        "image/webp" -> "AQ==",
        "image/gif"  -> "/+8="
      ).flatMap((mediaType, data) =>
        Chunk[Msg.UserBlock](
          ContentBlock.Image.base64(mediaType, data),
          ContentBlock.Data.base64(mediaType, data)
        ).map(block => Chunk(block) -> Json.Arr(imagePart(s"data:$mediaType;base64,$data")))
      ) ++ Chunk("image/png", "image/jpeg", "image/webp", "image/gif").map(mimeType =>
        Chunk[Msg.UserBlock](
          ContentBlock.Data(MediaSource.Url("https://example.test/file.mp3", Some(mimeType)))
        ) -> Json.Arr(imagePart("https://example.test/file.mp3"))
      ) ++ Chunk(
        Chunk[Msg.UserBlock](
          ContentBlock.Image(MediaSource.Url(url, Some("application/octet-stream")))
        ) -> Json.Arr(imagePart(url))
      ) ++ Chunk("audio/wav" -> "wav", "audio/mpeg" -> "mp3").flatMap((mediaType, format) =>
        Chunk[Msg.UserBlock](
          ContentBlock.Audio.base64(mediaType, "AQ=="),
          ContentBlock.Data.base64(mediaType, "AQ==")
        ).map(block => Chunk(block) -> Json.Arr(audioPart("AQ==", format)))
      )
      assertTrue(cases.forall { case (content, expected) =>
        preservesNonStreamingPayload(Chunk(Msg.user(content)), Chunk.empty, config) &&
        messages(ChatCompletionsRequest.encode(Chunk(Msg.user(content)), Chunk.empty, config))
          .contains(
            Chunk(
              Json.Obj(
                "role"    -> Json.Str("user"),
                "content" -> expected,
                "name"    -> Json.Str("user")
              )
            )
          )
      })
    },
    test("rejects malformed media sources and unsupported roles without dropping blocks") {
      val invalidUrls = Chunk(
        "",
        " ",
        "/relative/image.png",
        "//example.test/image.png",
        "file:///private/image.png",
        "C:\\private\\image.png",
        "ftp://example.test/image.png",
        "https:/missing-host",
        "https://",
        "https://example.test/image name.png",
        "https://example.test/\nimage.png",
        "https://example.test/image.png?query=raw space",
        "https://example.test/image.png?query=\n",
        "https://example.test:0/image.png",
        "https://example.test:65536/image.png",
        "data:image/png;base64,AQ=="
      )
      val invalidData =
        Chunk("", "A", "AQ", "AQI", "A===", "====", "AA=A", "AQ-_", "AQI=\n", " AQ==", "éAAA")
      val invalidTypes =
        Chunk("", "image/jpg", "image/svg+xml", "text/plain", "image/png;extra=value")
      val sourceFailures =
        invalidUrls.map(ContentBlock.Image.url) ++
          invalidData.map(data => ContentBlock.Image.base64("image/png", data)) ++
          invalidTypes.map(mediaType => ContentBlock.Image.base64(mediaType, "AQ=="))
      val media = ContentBlock.Image.url("https://example.test/image.png")
      val audio = ContentBlock.Audio.base64("audio/wav", "AQ==")
      val audioFailures = invalidData.map(data => ContentBlock.Audio.base64("audio/wav", data)) ++
        Chunk(
          "",
          "audio/mp3",
          "audio/ogg",
          "audio/flac",
          "audio/wav;codec=pcm",
          "Audio/Wav",
          "text/plain"
        )
          .map(mediaType => ContentBlock.Audio.base64(mediaType, "AQ==")) ++
        Chunk(
          "https://example.test/audio.wav",
          "file:///private/audio.wav",
          "C:\\private\\audio.wav",
          ""
        )
          .map(ContentBlock.Audio.url)
      val dataFailures =
        Chunk("https://example.test/image.png", "https://example.test/audio.wav", "").map(url =>
          ContentBlock.Data.url(url)
        ) ++
          invalidUrls.map(url => ContentBlock.Data(MediaSource.Url(url, Some("image/png")))) ++
          (invalidTypes ++ Chunk(" ", "Image/PNG", "audio/wav", "audio/mpeg", "video/mp4"))
            .map(mimeType =>
              ContentBlock.Data(MediaSource.Url("https://example.test/image.png", Some(mimeType)))
            ) ++
          Chunk("image/png", "audio/wav").flatMap(mediaType =>
            invalidData.map(data => ContentBlock.Data.base64(mediaType, data))
          ) ++ (invalidTypes ++ Chunk("audio/mp3", "audio/ogg", "Audio/Wav", "application/pdf"))
            .map(mediaType => ContentBlock.Data.base64(mediaType, "AQ=="))
      val invalidMessages = sourceFailures.map(image =>
        Msg.user(Chunk[Msg.UserBlock](ContentBlock.Text("must not survive alone"), image))
      ) ++ invalidUrls.map(url =>
        Msg.user(
          Chunk[Msg.UserBlock](
            ContentBlock.Text("must not survive alone"),
            ContentBlock.Video.url(url)
          )
        )
      ) ++ audioFailures.map(value =>
        Msg.user(Chunk[Msg.UserBlock](ContentBlock.Text("must not survive alone"), value))
      ) ++ dataFailures.map(value =>
        Msg.user(Chunk[Msg.UserBlock](ContentBlock.Text("must not survive alone"), value))
      ) ++ Chunk(
        ContentBlock.Data.base64("image/png", "AQ=="),
        ContentBlock.Data.base64("audio/mpeg", "AQ=="),
        ContentBlock.Data(MediaSource.Url("https://example.test/opaque", Some("image/png")))
      ).flatMap(data =>
        Chunk(
          Msg.assistant(Chunk(data)),
          Msg.tool(Chunk(ContentBlock.ToolResult("call", output = Chunk(data))))
        )
      ) ++ Chunk(
        Msg.assistant(Chunk(media)),
        Msg.tool(Chunk(ContentBlock.ToolResult("call", output = Chunk(media)))),
        Msg.assistant(Chunk(audio)),
        Msg.tool(Chunk(ContentBlock.ToolResult("call", output = Chunk(audio)))),
        Msg.user(
          Chunk[Msg.UserBlock](
            ContentBlock.Text("text"),
            ContentBlock.Data.url("https://example.test/image.png")
          )
        ),
        Msg.user(Chunk(ContentBlock.Video.base64("video/mp4", "AQ=="))),
        Msg.assistant(Chunk(ContentBlock.Video.url("https://example.test/video.mp4"))),
        Msg.tool(
          Chunk(
            ContentBlock.ToolResult(
              "call",
              output = Chunk(ContentBlock.Video.url("https://example.test/video.mp4"))
            )
          )
        ),
        Msg.user(Chunk(ContentBlock.Audio.base64("audio/opus", "AQ==")))
      )
      assertTrue(
        invalidMessages.forall(msg =>
          ChatCompletionsRequest
            .encode(pairedHistory(msg), Chunk.empty, config)
            .left
            .exists(_.isInstanceOf[AgentError.InvalidMessage])
        )
      )
    },
    test(
      "Google signature replay follows each historical call and rejects malformed or unsupported metadata"
    ) {
      val first = ContentBlock.ToolUse(
        "one",
        "search",
        Json.Obj(),
        metadata = Map(
          "thoughtSignature" -> Json.Str("opaque-one"),
          "private"          -> Json.Str("must-not-project")
        )
      )
      val unsigned = ContentBlock.ToolUse("two", "search", Json.Obj())
      val next =
        first.copy(id = "three", metadata = Map("thoughtSignature" -> Json.Str("opaque-three")))
      val history = Chunk(
        Msg.assistant(Chunk(ContentBlock.Thinking("earlier"), first, unsigned)),
        Msg.tool(
          Chunk(
            ContentBlock.ToolResult.text("one", "search", "done"),
            ContentBlock.ToolResult.text("two", "search", "done")
          )
        ),
        Msg.user("next"),
        Msg.assistant(Chunk(ContentBlock.Thinking("latest"), next)),
        Msg.tool("three", "search", "done")
      )
      def callJson(id: String, signature: Option[String]): Json = Json.Obj(
        Chunk(
          "id"       -> Json.Str(id),
          "type"     -> Json.Str("function"),
          "function" -> Json.Obj("name" -> Json.Str("search"), "arguments" -> Json.Str("{}"))
        ) ++ Chunk.fromIterable(
          signature.map(value =>
            "extra_content" -> Json.Obj(
              "google" -> Json.Obj("thought_signature" -> Json.Str(value))
            )
          )
        )
      )
      val expected = Chunk(
        callJson("one", Some("opaque-one")),
        callJson("two", None),
        callJson("three", Some("opaque-three"))
      )
      val malformed = Chunk[Json](Json.Null, Json.Num(1), Json.Obj(), Json.Str(""), Json.Str(" \t"))
      val forbidden = Chunk(
        first.withServerTool(true),
        first.copy(metadata = first.metadata.updated("reasoningDetails", Json.Arr()))
      )
      val replayModes = Chunk(ReasoningReplay.Omit, ReasoningReplay.LatestAssistant)
      assertTrue(
        replayModes.forall(mode =>
          messages(
            ChatCompletionsRequest.encode(
              history,
              Chunk.empty,
              config,
              mode,
              ToolSignatureFormat.GoogleExtraContent
            )
          )
            .exists(
              _.flatMap(
                _.asObject.flatMap(_.get("tool_calls")).flatMap(_.asArray).getOrElse(Chunk.empty)
              ) == expected
            )
        ),
        ChatCompletionsRequest.encode(history, Chunk.empty, config).isLeft,
        malformed.forall(value =>
          ChatCompletionsRequest
            .encode(
              pairedHistory(
                Msg.assistant(Chunk(first.copy(metadata = Map("thoughtSignature" -> value))))
              ),
              Chunk.empty,
              config,
              toolSignatureFormat = ToolSignatureFormat.GoogleExtraContent
            )
            .left
            .exists(_.isInstanceOf[AgentError.InvalidMessage])
        ),
        forbidden.forall(call =>
          ChatCompletionsRequest
            .encode(
              pairedHistory(Msg.assistant(Chunk(call))),
              Chunk.empty,
              config,
              toolSignatureFormat = ToolSignatureFormat.GoogleExtraContent
            )
            .isLeft
        ),
        ToolSignatureFormat.GoogleExtraContent.toJson.fromJson[ToolSignatureFormat] == Right(
          ToolSignatureFormat.GoogleExtraContent
        )
      )
    },
    test("requests streaming usage and preserves native response format and generation options") {
      val format = Json.Obj(
        "type"        -> Json.Str("json_schema"),
        "json_schema" -> Json.Obj("name" -> Json.Str("reply"), "schema" -> schema)
      )
      val result = ChatCompletionsRequest.encode(
        Chunk(Msg.user("question")),
        Chunk.empty,
        config.copy(
          temperature = 0.25,
          maxTokens = Some(512),
          responseFormat = Some(format),
          topP = Some(0.9),
          frequencyPenalty = Some(0.5),
          presencePenalty = Some(0.3),
          seed = Some(42L)
        )
      )
      assertTrue(
        preservesNonStreamingPayload(
          Chunk(Msg.user("question")),
          Chunk.empty,
          config.copy(
            temperature = 0.25,
            maxTokens = Some(512),
            responseFormat = Some(format),
            topP = Some(0.9),
            frequencyPenalty = Some(0.5),
            presencePenalty = Some(0.3),
            seed = Some(Long.MinValue),
            stopSequences = Some(StopSequences.one("END", "STOP")),
            reasoningEffort = Some(ReasoningEffort.High),
            parallelToolCalls = Some(ToolCallParallelism.Enabled)
          )
        ),
        result.exists(value =>
          value.get("model").contains(Json.Str("test-model")) &&
            value.get("stream").contains(Json.Bool(true)) &&
            value.get("stream_options").contains(Json.Obj("include_usage" -> Json.Bool(true))) &&
            value.get("temperature").contains(Json.Num(0.25)) &&
            value.get("max_tokens").contains(Json.Num(512)) &&
            value.get("top_p").contains(Json.Num(0.9)) &&
            value.get("frequency_penalty").contains(Json.Num(0.5)) &&
            value.get("presence_penalty").contains(Json.Num(0.3)) &&
            value.get("seed").contains(Json.Num(42L)) &&
            value.get("response_format").contains(format) && value.get("tools").isEmpty
        ),
        Chunk(None, Some(0.0), Some(1.0)).forall(topP =>
          ChatCompletionsRequest
            .encode(Chunk(Msg.user("question")), Chunk.empty, config.copy(topP = topP))
            .exists(_.get("top_p") == topP.map(Json.Num(_)))
        ),
        Chunk(None, Some(-2.0), Some(0.0), Some(2.0)).forall(penalty =>
          ChatCompletionsRequest
            .encode(
              Chunk(Msg.user("question")),
              Chunk.empty,
              config.copy(frequencyPenalty = penalty, presencePenalty = penalty)
            )
            .exists(value =>
              value.get("frequency_penalty") == penalty.map(Json.Num(_)) &&
                value.get("presence_penalty") == penalty.map(Json.Num(_))
            )
        ),
        Chunk(
          None,
          Some(StopSequences.one("END")),
          Some(StopSequences.one("END", "STOP")),
          Some(StopSequences.one("", " ", "\n", "END", "END"))
        ).forall(stops =>
          val selected = config.copy(stopSequences = stops)
          ChatCompletionsRequest
            .encode(Chunk(Msg.user("question")), Chunk.empty, selected)
            .exists(_.get("stop") == stops.map(value => Json.Arr(value.values.map(Json.Str(_))))) &&
          selected.toJson.fromJson[LlmConfig] == Right(selected)
        ),
        Chunk(
          None                         -> None,
          Some(ReasoningEffort.Low)    -> Some(Json.Str("low")),
          Some(ReasoningEffort.Medium) -> Some(Json.Str("medium")),
          Some(ReasoningEffort.High)   -> Some(Json.Str("high"))
        ).forall { case (effort, expected) =>
          val selected = config.copy(reasoningEffort = effort)
          selected.toJson.fromJson[LlmConfig] == Right(selected) &&
          ChatCompletionsRequest
            .encode(Chunk(Msg.user("question")), Chunk.empty, selected)
            .exists(_.get("reasoning_effort") == expected)
        },
        Chunk(1, 2048, 4096, Int.MaxValue).forall(tokens =>
          ThinkingBudget
            .fromInt(tokens)
            .exists(budget =>
              val selected = config.copy(thinkingBudget = Some(budget))
              selected.toJson.fromJson[LlmConfig] == Right(selected) &&
              ChatCompletionsRequest
                .encode(Chunk(Msg.user("question")), Chunk.empty, selected)
                .exists(_.get("thinking_budget").contains(Json.Num(tokens))) &&
              ChatCompletionsRequest
                .encodeNonStreaming(Chunk(Msg.user("question")), Chunk.empty, selected)
                .exists(_.get("thinking_budget").contains(Json.Num(tokens)))
            )
        ),
        ChatCompletionsRequest
          .encode(Chunk(Msg.user("question")), Chunk.empty, config)
          .exists(_.get("thinking_budget").isEmpty),
        """{"model":"test-model","thinkingBudget":null}""".fromJson[LlmConfig] == Right(config),
        """{"model":"test-model","thinkingBudget":0}""".fromJson[LlmConfig].isLeft,
        """{"model":"test-model","reasoningEffort":null}""".fromJson[LlmConfig] == Right(config),
        """{"model":"test-model","reasoningEffort":"unknown"}""".fromJson[LlmConfig].isLeft,
        Chunk(None, Some(Json.Obj("type" -> Json.Str("json_object"))), Some(format)).forall(
          format =>
            ChatCompletionsRequest
              .encode(
                Chunk(Msg.user("question")),
                Chunk.empty,
                config.copy(responseFormat = format)
              )
              .exists(_.get("response_format") == format)
        ),
        """{"model":"test-model","stopSequences":null}""".fromJson[LlmConfig] == Right(config),
        """{"model":"test-model","stopSequences":[]}""".fromJson[LlmConfig].isLeft,
        """{"model":"test-model"}""".fromJson[LlmConfig] == Right(LlmConfig("test-model")),
        Chunk(
          None,
          Some(0L),
          Some(42L),
          Some(2147483648L),
          Some(Long.MinValue),
          Some(Long.MaxValue)
        ).forall(seed =>
          val selected = config.copy(seed = seed)
          ChatCompletionsRequest
            .encode(Chunk(Msg.user("question")), Chunk.empty, selected)
            .exists(value =>
              value.get("seed") match
                case Some(Json.Num(number)) =>
                  seed.exists(expected =>
                    number.compareTo(java.math.BigDecimal.valueOf(expected)) == 0
                  )
                case None => seed.isEmpty
                case _    => false
            ) &&
          selected.toJson.fromJson[LlmConfig] == Right(selected)
        ),
        Chunk("1.5", "9223372036854775808", "-9223372036854775809").forall(seed =>
          s"""{"model":"test-model","seed":$seed}""".fromJson[LlmConfig].isLeft
        ),
        """{"model":"test-model","seed":null}""".fromJson[LlmConfig] == Right(
          LlmConfig("test-model")
        ),
        """{"model":"test-model","topP":null}""".fromJson[LlmConfig] == Right(
          LlmConfig("test-model")
        ),
        """{"model":"test-model","frequencyPenalty":null,"presencePenalty":null}"""
          .fromJson[LlmConfig] == Right(
          LlmConfig("test-model")
        ),
        config
          .copy(topP = Some(0.9), frequencyPenalty = Some(-0.5), presencePenalty = Some(0))
          .toJson
          .fromJson[LlmConfig] == Right(
          config.copy(topP = Some(0.9), frequencyPenalty = Some(-0.5), presencePenalty = Some(0))
        )
      )
    },
    test(
      "serializes function definitions and checked host tool choices without changing visibility"
    ) {
      val result =
        ChatCompletionsRequest.encode(Chunk(Msg.user("search")), Chunk(definition), config)
      val described = ChatCompletionsRequest.encode(
        Chunk(Msg.user("search")),
        Chunk(definition.copy(outputSchema = Some(schema), returnMode = ToolReturnMode.Direct)),
        config
      )
      val strictness = Chunk(
        None                               -> None,
        Some(ToolSchemaStrictness.Strict)  -> Some(Json.Bool(true)),
        Some(ToolSchemaStrictness.Relaxed) -> Some(Json.Bool(false))
      )
      val mixedTools = strictness.zipWithIndex.map { case ((mode, _), index) =>
        definition.copy(name = s"search_$index", schemaStrictness = mode)
      }
      val mixed = ChatCompletionsRequest.encode(Chunk(Msg.user("search")), mixedTools, config)
      val choices: Chunk[(Option[ToolChoice], Option[Json])] = Chunk(
        None                      -> None,
        Some(ToolChoice.Auto)     -> Some(Json.Str("auto")),
        Some(ToolChoice.None)     -> Some(Json.Str("none")),
        Some(ToolChoice.Required) -> Some(Json.Str("required")),
        Some(ToolChoice.Specific("search")) -> Some(
          Json.Obj(
            "type"     -> Json.Str("function"),
            "function" -> Json.Obj("name" -> Json.Str("search"))
          )
        )
      )
      assertTrue(
        choices.forall { case (choice, expected) =>
          val configured = config.copy(toolChoice = choice)
          configured.toJson.fromJson[LlmConfig] == Right(configured) &&
          preservesNonStreamingPayload(Chunk(Msg.user("search")), Chunk(definition), configured) &&
          ChatCompletionsRequest
            .encode(Chunk(Msg.user("search")), Chunk(definition), configured)
            .exists(value =>
              value.get("tool_choice") == expected &&
                result.exists(_.get("tools") == value.get("tools"))
            )
        },
        Chunk(
          None                               -> None,
          Some(ToolCallParallelism.Enabled)  -> Some(Json.Bool(true)),
          Some(ToolCallParallelism.Disabled) -> Some(Json.Bool(false))
        ).forall { case (parallelism, expected) =>
          val configured = config.copy(parallelToolCalls = parallelism)
          configured.toJson.fromJson[LlmConfig] == Right(configured) &&
          Chunk(Chunk.empty[ToolDef], Chunk(definition)).forall(tools =>
            ChatCompletionsRequest
              .encode(Chunk(Msg.user("search")), tools, configured)
              .exists(value =>
                value.get("parallel_tool_calls") == expected &&
                  ChatCompletionsRequest
                    .encode(Chunk(Msg.user("search")), tools, config)
                    .exists(_.get("tools") == value.get("tools"))
              )
          )
        },
        """{"model":"test-model","parallelToolCalls":null}""".fromJson[LlmConfig] == Right(config),
        """{"model":"test-model","parallelToolCalls":"other"}""".fromJson[LlmConfig].isLeft,
        Chunk(None, Some(ToolChoice.Auto), Some(ToolChoice.None)).forall(choice =>
          ChatCompletionsRequest
            .encode(Chunk(Msg.user("search")), Chunk.empty, config.copy(toolChoice = choice))
            .exists(_.get("tool_choice").isEmpty)
        ),
        Chunk(
          Chunk.empty[ToolDef] -> ToolChoice.Required,
          Chunk.empty[ToolDef] -> ToolChoice.Specific("search"),
          Chunk(definition)    -> ToolChoice.Specific("hidden"),
          Chunk(definition)    -> ToolChoice.Specific(" "),
          Chunk(definition)    -> ToolChoice.Specific("invalid/name")
        ).forall { case (tools, choice) =>
          ChatCompletionsRequest
            .encode(Chunk(Msg.user("search")), tools, config.copy(toolChoice = Some(choice)))
            .left
            .exists(_.isInstanceOf[AgentError.InvalidConfiguration])
        },
        """{"model":"test-model","toolChoice":null}""".fromJson[LlmConfig] == Right(
          LlmConfig("test-model")
        ),
        preservesNonStreamingPayload(Chunk(Msg.user("search")), mixedTools, config),
        mixed.exists(
          _.get("tools")
            .flatMap(_.asArray)
            .exists(tools =>
              tools.size == strictness.size && tools.zip(strictness).forall {
                case (tool, (_, expected)) =>
                  tool.asObject
                    .flatMap(_.get("function"))
                    .flatMap(_.asObject)
                    .exists(function =>
                      function
                        .get("strict") == expected && function.get("parameters").contains(schema)
                    )
              }
            )
        ),
        described == result,
        result.exists(
          _.get("tools").contains(
            Json.Arr(
              Json.Obj(
                "type" -> Json.Str("function"),
                "function" -> Json.Obj(
                  "name"        -> Json.Str("search"),
                  "description" -> Json.Str("Search text"),
                  "parameters"  -> schema
                )
              )
            )
          )
        )
      )
    },
    test("serializes authoritative tool input rather than a stale raw content cache") {
      val input =
        Json.Obj("query" -> Json.Str("Scala"), "nested" -> Json.Arr(Json.Num(Long.MinValue)))
      val call = ContentBlock.ToolUse("call", "search", input, content = Some("stale"))
      val result = ChatCompletionsRequest.encode(
        Chunk(
          Msg.assistant(Chunk(ContentBlock.Text("searching"), call)),
          Msg.tool("call", "search", "found")
        ),
        Chunk(definition),
        config
      )
      assertTrue(
        messages(result)
          .flatMap(_.headOption)
          .flatMap(_.asObject)
          .flatMap(_.get("tool_calls"))
          .contains(
            Json.Arr(
              Json.Obj(
                "id"   -> Json.Str("call"),
                "type" -> Json.Str("function"),
                "function" -> Json
                  .Obj(
                    "name"      -> Json.Str("search"),
                    "arguments" -> Json.Str("""{"query":"Scala","nested":[-9223372036854775808]}""")
                  )
              )
            )
          )
      )
    },
    test("expands grouped results with correlation and visible error observations") {
      val history = Chunk(
        Msg.assistant(
          Chunk(
            ContentBlock.ToolUse("one", "search", Json.Obj()),
            ContentBlock.ToolUse("two", "search", Json.Obj()),
            ContentBlock.ToolUse("three", "search", Json.Obj())
          )
        ),
        Msg.tool(
          Chunk(
            ContentBlock.ToolResult.text("one", "search", "found"),
            ContentBlock.ToolResult.text("two", "search", "failed", ToolResultState.Error),
            ContentBlock.ToolResult
              .text("three", "search", "[ERROR] already classified", ToolResultState.Error)
          )
        )
      )
      val result = ChatCompletionsRequest.encode(history, Chunk.empty, config)
      assertTrue(
        messages(result)
          .map(_.drop(1))
          .contains(
            Chunk(
              Json.Obj(
                "role"         -> Json.Str("tool"),
                "tool_call_id" -> Json.Str("one"),
                "content"      -> Json.Str("found")
              ),
              Json.Obj(
                "role"         -> Json.Str("tool"),
                "tool_call_id" -> Json.Str("two"),
                "content"      -> Json.Str("[ERROR] failed")
              ),
              Json.Obj(
                "role"         -> Json.Str("tool"),
                "tool_call_id" -> Json.Str("three"),
                "content"      -> Json.Str("[ERROR] already classified")
              )
            )
          )
      )
    },
    test("distinguishes denied and interrupted observations and rejects running results") {
      val history = Chunk(
        Msg.assistant(
          Chunk(
            ContentBlock.ToolUse("one", "search", Json.Obj()),
            ContentBlock.ToolUse("two", "search", Json.Obj())
          )
        ),
        Msg.tool(
          Chunk(
            ContentBlock.ToolResult.text("one", "search", "stopped", ToolResultState.Interrupted),
            ContentBlock.ToolResult.text("two", "search", "policy", ToolResultState.Denied)
          )
        )
      )
      val running = Msg.tool(
        Chunk(ContentBlock.ToolResult.text("call", "search", "pending", ToolResultState.Running))
      )
      assertTrue(
        messages(ChatCompletionsRequest.encode(history, Chunk.empty, config)).map(
          _.drop(1).flatMap(_.asObject.flatMap(_.get("content")))
        ) == Some(Chunk(Json.Str("[INTERRUPTED] stopped"), Json.Str("[DENIED] policy"))),
        ChatCompletionsRequest.encode(pairedHistory(running), Chunk.empty, config) == Left(
          AgentError.InvalidMessage("In-flight tool results cannot be serialized as observations")
        )
      )
    },
    test("omits reasoning by default and replays only the latest assistant when selected") {
      val history = Chunk(
        Msg.assistant(Chunk(ContentBlock.Thinking("old"), ContentBlock.Text("previous"))),
        Msg.user("next"),
        Msg.assistant(Chunk(ContentBlock.Thinking("current"), ContentBlock.Text("answer")))
      )
      val omitted = messages(ChatCompletionsRequest.encode(history, Chunk.empty, config))
      val replayed = messages(
        ChatCompletionsRequest.encode(history, Chunk.empty, config, ReasoningReplay.LatestAssistant)
      )
      assertTrue(
        omitted.exists(_.forall(_.asObject.flatMap(_.get("reasoning_content")).isEmpty)),
        replayed
          .flatMap(_.headOption)
          .flatMap(_.asObject)
          .flatMap(_.get("reasoning_content"))
          .isEmpty,
        replayed
          .flatMap(_.lastOption)
          .flatMap(_.asObject)
          .flatMap(_.get("reasoning_content"))
          .contains(Json.Str("current"))
      )
    },
    test("signed reasoning requires a provider-specific codec when replay is selected") {
      val history = Chunk(
        Msg.assistant(
          Chunk(ContentBlock.Thinking("reason", Map("thoughtSignature" -> Json.Str("signed"))))
        )
      )
      assertTrue(
        ChatCompletionsRequest
          .encode(history, Chunk.empty, config, ReasoningReplay.LatestAssistant)
          .isLeft,
        ChatCompletionsRequest.encode(history, Chunk.empty, config, ReasoningReplay.Omit).isRight
      )
    },
    test("does not serialize credentials endpoint or runtime message metadata") {
      val history = Chunk(
        Msg
          .user("question")
          .withId(Some("private-id"))
          .withMetadata("internal", Json.Str("private-trace"))
      )
      val result = ChatCompletionsRequest.encode(
        history,
        Chunk.empty,
        config.copy(apiKey = Some("secret-value"), endpoint = "https://private.example/path")
      )
      assertTrue(
        result.exists(value =>
          !value.toJson.contains("secret-value") && !value.toJson.contains("private.example") &&
            !value.toJson.contains("private-id") && !value.toJson.contains("private-trace")
        )
      )
    },
    test("rejects unsupported media and ambiguous tool-role content") {
      val invalid = Chunk(
        Msg.user(Chunk(ContentBlock.Audio(MediaSource.Url("https://example.test/audio")))),
        Msg.assistant(Chunk(ContentBlock.Custom("extension", Json.Obj()))),
        Msg.tool(Chunk(ContentBlock.Text("uncorrelated"))),
        Msg.tool(Chunk.empty),
        Msg.tool(
          Chunk(
            ContentBlock.ToolResult(
              "call",
              output = Chunk(ContentBlock.Data(MediaSource.Url("https://example.test/file")))
            )
          )
        )
      )
      assertTrue(
        invalid.forall(msg =>
          ChatCompletionsRequest
            .encode(pairedHistory(msg), Chunk.empty, config)
            .left
            .exists(_.isInstanceOf[AgentError.InvalidMessage])
        )
      )
    },
    test("rejects server signed and suspended calls or results") {
      val call = ContentBlock.ToolUse("call", "search", Json.Obj())
      val invalid = Chunk(
        Msg.assistant(Chunk(call.withServerTool(true))),
        Msg.assistant(Chunk(call.withThoughtSignature("signature"))),
        Msg.tool(
          Chunk(
            ContentBlock.ToolResult
              .text("call", "search", "inline", metadata = Map("serverTool" -> Json.Bool(true)))
          )
        ),
        Msg.tool(Chunk(ContentBlock.ToolResult.suspended("call", "search")))
      )
      assertTrue(
        invalid.forall(msg =>
          ChatCompletionsRequest
            .encode(pairedHistory(msg), Chunk.empty, config)
            .left
            .exists(error =>
              error == AgentError.InvalidMessage(
                if msg.role != Role.Assistant then
                  "Server or suspended tool results require a different protocol"
                else if msg.toolUses.exists(_.metadata.contains("thoughtSignature")) then
                  "Tool thought signatures require an explicit wire format"
                else "Server tools and reasoning details require another request codec"
              )
            )
        )
      )
    },
    test("rejects invalid configuration before serializing nonfinite numbers") {
      val invalid = Chunk(
        config.copy(model = " "),
        config.copy(temperature = Double.NaN),
        config.copy(temperature = Double.PositiveInfinity),
        config.copy(temperature = -1),
        config.copy(temperature = 3),
        config.copy(topP = Some(Double.NaN)),
        config.copy(topP = Some(Double.PositiveInfinity)),
        config.copy(topP = Some(-0.1)),
        config.copy(topP = Some(1.1)),
        config.copy(maxTokens = Some(0)),
        config.copy(responseFormat = Some(Json.Str("invalid")))
      ) ++ Chunk(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity, -2.1, 2.1).flatMap(
        value =>
          Chunk(
            config.copy(frequencyPenalty = Some(value)),
            config.copy(presencePenalty = Some(value))
          )
      )
      assertTrue(
        invalid.forall(value =>
          ChatCompletionsRequest
            .encodeNonStreaming(Chunk(Msg.user("question")), Chunk.empty, value) ==
            ChatCompletionsRequest.encode(Chunk(Msg.user("question")), Chunk.empty, value) &&
            ChatCompletionsRequest
              .encode(Chunk(Msg.user("question")), Chunk.empty, value)
              .left
              .exists(_.isInstanceOf[AgentError.InvalidConfiguration])
        )
      )
    },
    test("rejects invalid names duplicate tools and non-object schemas") {
      val invalid = Chunk(
        Chunk(definition, definition),
        Chunk(definition.copy(name = "bad name")),
        Chunk(definition.copy(parametersSchema = Json.Arr()))
      )
      assertTrue(
        invalid.forall(tools =>
          ChatCompletionsRequest.encode(Chunk(Msg.user("question")), tools, config).isLeft
        ),
        ChatCompletionsRequest
          .encode(Chunk(Msg.user("question").withName(Some("bad name"))), Chunk.empty, config)
          .isLeft,
        ChatCompletionsRequest.encode(Chunk.empty, Chunk.empty, config).isLeft
      )
    },
    test("rejects orphan duplicate unresolved and interrupted tool-result sequences") {
      val calls  = Msg.assistant(Chunk(ContentBlock.ToolUse("call", "search", Json.Obj())))
      val result = Msg.tool("call", "search", "found")
      val invalid = Chunk(
        Chunk(result),
        Chunk(calls),
        Chunk(calls, Msg.user("next")),
        Chunk(calls, Msg.assistant("done")),
        Chunk(calls, result, result),
        Chunk(calls, Msg.tool("unknown", "search", "wrong")),
        Chunk(
          calls,
          Msg.tool(
            Chunk(
              ContentBlock.ToolResult.text("call", "search", "one"),
              ContentBlock.ToolResult.text("call", "search", "two")
            )
          )
        )
      )
      assertTrue(
        invalid.forall(history =>
          ChatCompletionsRequest
            .encode(history, Chunk.empty, config)
            .left
            .exists(_.isInstanceOf[AgentError.InvalidMessage])
        )
      )
    },
    test("accepts separate parallel results in completion order and reused IDs in later turns") {
      val calls = Msg.assistant(
        Chunk(
          ContentBlock.ToolUse("one", "search", Json.Obj()),
          ContentBlock.ToolUse("two", "search", Json.Obj())
        )
      )
      val history = Chunk(
        calls,
        Msg.tool("two", "search", "second"),
        Msg.tool("one", "search", "first"),
        Msg.assistant(Chunk(ContentBlock.ToolUse("one", "search", Json.Obj()))),
        Msg.tool("one", "search", "reused")
      )
      assertTrue(
        messages(ChatCompletionsRequest.encode(history, Chunk.empty, config)).exists(_.size == 5)
      )
    },
    test("rejects duplicate argument keys and duplicate call identities") {
      val call = ContentBlock.ToolUse("call", "search", Json.Obj())
      val invalid = Chunk(
        Msg.assistant(Chunk(call, call)),
        Msg.assistant(Chunk(call.copy(input = Json.Obj("x" -> Json.Num(1), "x" -> Json.Num(2))))),
        Msg.assistant(Chunk(call.copy(input = Json.Arr()))),
        Msg.assistant(Chunk(call.copy(id = "")))
      )
      val expected = Chunk(
        "Assistant tool call IDs must be unique",
        "Duplicate JSON object fields",
        "Function call arguments must be an object",
        "Tool calls require a nonblank ID and valid function name"
      )
      assertTrue(
        invalid
          .zip(expected)
          .forall((msg, reason) =>
            ChatCompletionsRequest.encode(pairedHistory(msg), Chunk.empty, config) == Left(
              AgentError.InvalidMessage(reason)
            )
          )
      )
    }
  )

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
package com.weisgnosis.agents.mcp.protocol

import com.weisgnosis.agents.core.error.AgentError
import zio.*
import zio.json.ast.Json
import zio.test.*

object McpCallResultSpec extends ZIOSpecDefault:
  private def block(kind: String, fields: (String, Json)*): Json.Obj =
    Json.Obj(Chunk("type" -> Json.Str(kind)) ++ Chunk.fromIterable(fields))

  private def result(content: Json, extra: (String, Json)*): Json.Obj =
    Json.Obj(Chunk("content" -> content) ++ Chunk.fromIterable(extra))

  private def read(content: Json): Either[AgentError.DeserializationError, McpCallResult] =
    McpCallResult.read(result(Json.Arr(content)))

  def spec: Spec[TestEnvironment, Any] = suite("McpCallResultSpec")(
    test("ordered content covers every variant including resources with both text and blob") {
      val bodies = Chunk(
        Json.Obj("uri" -> Json.Str("r"), "text" -> Json.Str("text")),
        Json.Obj("uri" -> Json.Str("r"), "blob" -> Json.Str("AQ==")),
        Json.Obj("uri" -> Json.Str("r"), "text" -> Json.Str("text"), "blob" -> Json.Str("AQ=="))
      )
      val content = Chunk(
        block("text", "text"  -> Json.Str("answer")),
        block("image", "data" -> Json.Str("image-data"), "mimeType" -> Json.Str("image/png")),
        block("audio", "data" -> Json.Str("audio-data"), "mimeType" -> Json.Str("audio/wav")),
        block("resource_link", "uri" -> Json.Str("r"), "name" -> Json.Str("resource"))
      ) ++ bodies.map(body => block("resource", "resource" -> body))
      val expected = Chunk[McpContentBlock](
        McpContentBlock.Text("answer"),
        McpContentBlock.Image("image-data", "image/png"),
        McpContentBlock.Audio("audio-data", "audio/wav"),
        McpContentBlock.ResourceLink(McpResourceLink("r", "resource", None, None, None, None)),
        McpContentBlock.Resource(
          McpResourceContents("r", McpResourceBody.Text("text"), None, None, Json.Obj())
        ),
        McpContentBlock.Resource(
          McpResourceContents("r", McpResourceBody.Blob("AQ=="), None, None, Json.Obj())
        ),
        McpContentBlock.Resource(
          McpResourceContents(
            "r",
            McpResourceBody.TextAndBlob("text", "AQ=="),
            None,
            None,
            Json.Obj()
          )
        )
      )
      assertTrue(
        McpCallResult.read(result(Json.Arr(content))).map(_.content.map(_.block)) == Right(expected)
      )
    },
    test("tool failures remain results and metadata stays attached to its original level") {
      val outcomes = Chunk(
        None        -> McpCallOutcome.Success,
        Some(false) -> McpCallOutcome.Success,
        Some(true)  -> McpCallOutcome.Error
      )
      val statusChecks = outcomes.map: (flag, expected) =>
        val fields = Chunk("content" -> Json.Arr()) ++ Chunk.fromIterable(
          flag.map(value => "isError" -> Json.Bool(value))
        )
        assertTrue(McpCallResult.read(Json.Obj(fields)).map(_.outcome) == Right(expected))
      val top   = Json.Obj("level" -> Json.Str("result"))
      val outer = Json.Obj("level" -> Json.Str("content"))
      val inner = Json.Obj("level" -> Json.Str("resource"))
      val annotations = Json.Obj(
        "audience"     -> Json.Arr(Json.Str("user"), Json.Str("assistant"), Json.Str("user")),
        "priority"     -> Json.Num(BigDecimal("0.5")),
        "lastModified" -> Json.Str("unparsed timestamp"),
        "future"       -> Json.Num(1)
      )
      val resource = Json.Obj(
        "uri"      -> Json.Str("unparsed uri"),
        "blob"     -> Json.Str("unparsed base64"),
        "mimeType" -> Json.Str("application/octet-stream"),
        "_meta"    -> inner,
        "name"     -> Json.Str("extension name")
      )
      val decoded = McpCallResult.read(
        result(
          Json.Arr(
            block(
              "resource",
              "resource"      -> resource,
              "_meta"         -> outer,
              "annotations"   -> annotations,
              "futureContent" -> Json.Num(2)
            )
          ),
          "_meta"             -> top,
          "structuredContent" -> Json.Obj("answer" -> Json.Num(42)),
          "futureResult"      -> Json.Num(3)
        )
      )
      val content = decoded.toOption.flatMap(_.content.headOption)
      assertTrue(
        decoded.map(_.meta) == Right(Some(top)),
        decoded.map(_.structuredContent) == Right(Some(Json.Obj("answer" -> Json.Num(42)))),
        decoded.map(_.extensions) == Right(Json.Obj("futureResult" -> Json.Num(3))),
        content.flatMap(_.meta).contains(outer),
        content.map(_.extensions).contains(Json.Obj("futureContent" -> Json.Num(2))),
        content
          .flatMap(_.annotations)
          .contains(
            McpAnnotations(
              Some(Chunk(McpAudience.User, McpAudience.Assistant, McpAudience.User)),
              Some(BigDecimal("0.5")),
              Some("unparsed timestamp"),
              Json.Obj("future" -> Json.Num(1))
            )
          ),
        content
          .map(_.block)
          .contains(
            McpContentBlock.Resource(
              McpResourceContents(
                "unparsed uri",
                McpResourceBody.Blob("unparsed base64"),
                Some("application/octet-stream"),
                Some(inner),
                Json.Obj("name" -> Json.Str("extension name"))
              )
            )
          )
      ) && statusChecks.reduceOption(_ && _).getOrElse(assertTrue(false))
    },
    test(
      "known fields reject invalid shapes and annotation bounds without expanding integral sizes"
    ) {
      val invalid = Chunk[Json](
        Json.Null,
        block("unknown"),
        block("text"),
        block("text", "text"         -> Json.Num(1)),
        block("image", "data"        -> Json.Str("x")),
        block("audio", "data"        -> Json.Null, "mimeType" -> Json.Str("x")),
        block("resource_link", "uri" -> Json.Str("r")),
        block(
          "resource_link",
          "uri"  -> Json.Str("r"),
          "name" -> Json.Str("n"),
          "size" -> Json.Num(BigDecimal("1.5"))
        ),
        block("resource", "resource" -> Json.Obj("uri" -> Json.Str("r"))),
        block(
          "resource",
          "resource" -> Json
            .Obj("uri" -> Json.Str("r"), "text" -> Json.Null, "blob" -> Json.Str("x"))
        ),
        block("text", "text" -> Json.Str("x"), "_meta"       -> Json.Null),
        block("text", "text" -> Json.Str("x"), "annotations" -> Json.Null),
        block(
          "text",
          "text"        -> Json.Str("x"),
          "annotations" -> Json.Obj("audience" -> Json.Arr(Json.Str("system")))
        ),
        block(
          "text",
          "text"        -> Json.Str("x"),
          "annotations" -> Json.Obj("priority" -> Json.Num(-1))
        ),
        block(
          "text",
          "text"        -> Json.Str("x"),
          "annotations" -> Json.Obj("priority" -> Json.Num(2))
        ),
        block(
          "text",
          "text"        -> Json.Str("x"),
          "annotations" -> Json.Obj("lastModified" -> Json.Null)
        )
      )
      val invalidResults = Chunk(
        Json.Obj(),
        result(Json.Null),
        result(Json.Arr(), "isError"           -> Json.Null),
        result(Json.Arr(), "isError"           -> Json.Str("true")),
        result(Json.Arr(), "structuredContent" -> Json.Arr()),
        result(Json.Arr(), "_meta"             -> Json.Null)
      )
      val sizes = Chunk(
        BigDecimal(-1),
        BigDecimal("2.00"),
        BigDecimal("1e1000000"),
        BigDecimal("0e-2147483647")
      )
      val validSizes = sizes.map: size =>
        read(
          block(
            "resource_link",
            "uri"         -> Json.Str("r"),
            "name"        -> Json.Str("n"),
            "title"       -> Json.Str("title"),
            "description" -> Json.Str("description"),
            "mimeType"    -> Json.Str("text/plain"),
            "size"        -> Json.Num(size)
          )
        )
          .map(_.content.map(_.block)) == Right(
          Chunk(
            McpContentBlock.ResourceLink(
              McpResourceLink(
                "r",
                "n",
                Some("title"),
                Some("description"),
                Some("text/plain"),
                Some(size)
              )
            )
          )
        )
      assertTrue(
        invalid.forall(read(_).isLeft),
        invalidResults.forall(McpCallResult.read(_).isLeft),
        validSizes.forall(identity),
        Chunk(0, 1).forall(priority =>
          read(
            block(
              "text",
              "text"        -> Json.Str("x"),
              "annotations" -> Json.Obj("priority" -> Json.Num(priority), "audience" -> Json.Arr())
            )
          ).isRight
        )
      )
    },
    test("duplicate keys fail even inside otherwise opaque extensions") {
      val duplicate = Json.Obj("key" -> Json.Num(1), "key" -> Json.Num(2))
      assertTrue(
        McpCallResult.read(result(Json.Arr(), "future" -> duplicate)).isLeft,
        read(block("text", "text" -> Json.Str("x"), "future" -> duplicate)).isLeft,
        read(
          block(
            "resource",
            "resource" -> Json.Obj(
              "uri"    -> Json.Str("r"),
              "text"   -> Json.Str("x"),
              "future" -> duplicate
            )
          )
        ).isLeft,
        McpCallResult.read(result(Json.Arr(), "structuredContent" -> duplicate)).isLeft,
        McpCallResult
          .read(result(Json.Arr(), "future" -> Json.Obj("a" -> Json.Num(1), "b" -> Json.Num(2))))
          .isRight
      )
    }
  )

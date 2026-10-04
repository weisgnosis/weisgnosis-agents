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
package com.weisgnosis.agents.core.model

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** Shared media encoding and strict source validation across concrete and polymorphic entry points. */
object MediaSourceSpec extends ZIOSpecDefault:
  private def contract[A <: MediaSource: JsonCodec](source: A, tag: String): TestResult =
    val root: MediaSource = source
    val ast               = source.toJson.fromJson[Json]
    val legacy = ast
      .flatMap(_.asObject.toRight("Expected object"))
      .map(obj => Json.Obj(obj.fields.filterNot(_._1 == "type")))
    assertTrue(
      ast == root.toJson.fromJson[Json],
      source.toJson.fromJson[MediaSource].contains(source),
      root.toJson.fromJson[A].contains(source),
      legacy.flatMap(_.as[A]).contains(source),
      legacy.flatMap(_.as[MediaSource]).isLeft,
      ast.exists(
        _.asObject.exists(obj =>
          obj.fields.count(_._1 == "type") == 1 && obj.get("type").contains(Json.Str(tag))
        )
      )
    )

  def spec: Spec[TestEnvironment, Any] = suite("MediaSourceSpec")(
    test("URL source uses one representation for concrete and polymorphic codecs") {
      val plain    = MediaSource.Url("https://example.com/data")
      val hinted   = MediaSource.Url("https://example.com/data", Some("image/jpeg"))
      val nullHint = """{"type":"url","url":"https://example.com/data","mime_type":null}"""
      val invalidHints = Chunk(
        """{"type":"url","url":"x","mime_type":1}""",
        """{"type":"url","url":"x","mime_type":"image/png","mime_type":"image/jpeg"}"""
      )
      contract(plain, "url") && contract(hinted, "url") && assertTrue(
        plain.toJson
          .fromJson[Json]
          .contains(
            Json.Obj("type" -> Json.Str("url"), "url" -> Json.Str("https://example.com/data"))
          ),
        hinted.toJson
          .fromJson[Json]
          .exists(
            _.asObject.exists(
              _.get("mime_type").contains(Json.Str("image/jpeg"))
            )
          ),
        nullHint.fromJson[MediaSource.Url].contains(plain),
        nullHint.fromJson[MediaSource].contains(plain),
        MediaSource.url("https://example.com/data", Some("image/jpeg")) == hinted,
        invalidHints.forall(value =>
          value.fromJson[MediaSource.Url].isLeft && value.fromJson[MediaSource].isLeft
        )
      )
    },
    test("Base64 source uses one canonical representation with legacy mediaType decoding") {
      val source = MediaSource.Base64("application/pdf", "encoded")
      val legacy =
        """{"mediaType":"application/pdf","data":"encoded"}""".fromJson[MediaSource.Base64]
      contract(source, "base64") && assertTrue(
        legacy.contains(source),
        source.toJson.contains("\"media_type\""),
        !source.toJson.contains("\"mediaType\"")
      )
    },
    test("duplicate, mismatched, absent, and invalid discriminators fail at their boundaries") {
      val badUrl = Chunk(
        """{"type":"url","type":"url","url":"x"}""",
        """{"type":"base64","url":"x"}""",
        """{"type":null,"url":"x"}""",
        """{"type":1,"url":"x"}"""
      )
      val badBase64 = Chunk(
        """{"type":"base64","type":"base64","media_type":"text/plain","data":"x"}""",
        """{"type":"url","media_type":"text/plain","data":"x"}"""
      )
      assertTrue(
        badUrl.forall(_.fromJson[MediaSource.Url].isLeft),
        badBase64.forall(_.fromJson[MediaSource.Base64].isLeft),
        """{"url":"x"}""".fromJson[MediaSource].isLeft,
        Chunk("null", "[]", "{}", """{"type":"unknown"}""").forall(_.fromJson[MediaSource].isLeft)
      )
    },
    test("nested media sources reject duplicate tags, invalid payloads, and ambiguous aliases") {
      val sources = Chunk(
        """{"type":"url","type":"url","url":"x"}""",
        """{"url":"x"}""",
        """{"type":"url","url":null}""",
        """{"type":"base64","media_type":"a","mediaType":"b","data":"x"}""",
        """{"type":"base64","media_type":1,"data":"x"}"""
      )
      assertTrue(
        sources.forall(source =>
          s"""{"type":"data","source":$source}""".fromJson[ContentBlock].isLeft &&
            s"""{"source":$source}""".fromJson[ContentBlock.Data].isLeft
        )
      )
    }
  )

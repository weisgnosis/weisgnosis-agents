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

import zio.json.*
import zio.json.ast.Json

/** Polymorphic media source representing either a remote URL or inline Base64 data.
  *
  * Corresponds to `io.agentscope.core.message.Source` in AgentScope Java.
  */
sealed trait MediaSource

object MediaSource:
  final case class Url(url: String) extends MediaSource
  final case class Base64(mediaType: String, data: String) extends MediaSource

  given JsonCodec[Url] = DeriveJsonCodec.gen
  given JsonCodec[Base64] = DeriveJsonCodec.gen

  given JsonCodec[MediaSource] = summon[JsonCodec[Json]].transformOrFail(
    json =>
      json.asObject.flatMap(_.get("type")).flatMap(_.asString) match
        case Some("url")    => json.as[Url]
        case Some("base64") => json.as[Base64]
        case other          => Left(s"Unknown media source type: ${other.getOrElse("missing type field")}"),
    {
      case u: Url =>
        u.toJsonAST.fold(
          _ => Json.Obj("type" -> Json.Str("url"), "url" -> Json.Str(u.url)),
          ast => ast.mapObject(_.add("type", Json.Str("url")))
        )
      case b: Base64 =>
        b.toJsonAST.fold(
          _ =>
            Json.Obj(
              "type" -> Json.Str("base64"),
              "mediaType" -> Json.Str(b.mediaType),
              "data" -> Json.Str(b.data)
            ),
          ast => ast.mapObject(_.add("type", Json.Str("base64")))
        )
    }
  )

  def url(url: String): MediaSource = Url(url)

  def base64(mediaType: String, data: String): MediaSource = Base64(mediaType, data)

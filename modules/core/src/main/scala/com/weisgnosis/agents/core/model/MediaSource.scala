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

/**
 * Polymorphic media source representing either a remote URL or inline Base64 data.
 *
 * Corresponds to `io.agentscope.core.message.Source` in AgentScope Java.
 */
@jsonDiscriminator("type")
sealed trait MediaSource

object MediaSource:
  @jsonHint("url")
  final case class Url(url: String) extends MediaSource

  object Url:
    given JsonCodec[Url] = taggedCodec("url", PayloadCodecs.url)

  @jsonHint("base64")
  final case class Base64(
      @jsonField("media_type") @jsonAliases("mediaType") mediaType: String,
      data: String
  ) extends MediaSource

  object Base64:
    given JsonCodec[Base64] = taggedCodec("base64", PayloadCodecs.base64)

  private object PayloadCodecs:
    given url: JsonCodec[Url]       = DeriveJsonCodec.gen[Url]
    given base64: JsonCodec[Base64] = DeriveJsonCodec.gen[Base64]

  private lazy val wireCodec: JsonCodec[MediaSource] =
    import PayloadCodecs.given
    DeriveJsonCodec.gen[MediaSource]

  private def sourceDecoder[A](
      payload: => JsonDecoder[A],
      expected: Option[String]
  ): JsonDecoder[A] =
    summon[JsonDecoder[Json]].mapOrFail {
      case obj: Json.Obj =>
        val tags = obj.fields.collect { case ("type", value) => value }.toList
        tags match
          case Nil if expected.nonEmpty                         => payload.fromJsonAST(obj)
          case List(Json.Str(tag)) if expected.forall(_ == tag) => payload.fromJsonAST(obj)
          case _ => Left("Missing, duplicate, or invalid media source discriminator")
      case _ => Left("Expected a media source object")
    }

  private def taggedCodec[A <: MediaSource](tag: String, payload: JsonCodec[A]): JsonCodec[A] =
    JsonCodec(
      JsonEncoder.suspend(wireCodec.encoder).contramap[A](identity),
      sourceDecoder(payload.decoder, Some(tag))
    )

  given JsonCodec[MediaSource] = JsonCodec(
    JsonEncoder.suspend(wireCodec.encoder),
    sourceDecoder(wireCodec.decoder, None)
  )

  def url(url: String): MediaSource = Url(url)

  def base64(mediaType: String, data: String): MediaSource = Base64(mediaType, data)

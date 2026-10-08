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
package com.weisgnosis.agents.core.event.javawire

import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/** Explicit message identities and timestamps; no Java builder defaults or runtime authority. */
final case class JavaMessage(
    @jsonExplicitNull id: Option[String],
    @jsonExplicitNull name: Option[String],
    role: JavaMessageRole,
    content: Chunk[JavaContentBlock],
    metadata: Map[String, Json],
    @jsonExplicitNull timestamp: Option[String],
    @jsonExplicitNull usage: Option[JavaChatUsage]
)

object JavaMessage:
  final private case class Wire(
      @jsonExplicitNull id: Option[String],
      @jsonExplicitNull name: Option[String],
      role: JavaMessageRole,
      content: Chunk[JavaContentBlock],
      metadata: Map[String, Json],
      @jsonExplicitNull timestamp: Option[String],
      @jsonExplicitNull usage: Option[JavaChatUsage]
  ) derives JsonCodec

  private def allowed(role: JavaMessageRole, block: JavaContentBlock): Boolean = role match
    case JavaMessageRole.Assistant | JavaMessageRole.Tool => true
    case JavaMessageRole.System =>
      block match
        case _: JavaContentBlock.Text => true
        case _                        => false
    case JavaMessageRole.User =>
      block match
        case _: JavaContentBlock.Text | _: JavaContentBlock.Data | _: JavaContentBlock.Image |
            _: JavaContentBlock.Audio | _: JavaContentBlock.Video =>
          true
        case _ => false

  given JsonCodec[JavaMessage] = JsonCodec[Wire].transformOrFail(
    value =>
      Either.cond(
        value.content.forall(allowed(value.role, _)),
        JavaMessage(
          value.id,
          value.name,
          value.role,
          value.content,
          value.metadata,
          value.timestamp,
          value.usage
        ),
        "Java message content is not allowed for its role"
      ),
    value =>
      Wire(
        value.id,
        value.name,
        value.role,
        value.content,
        value.metadata,
        value.timestamp,
        value.usage
      )
  )

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

/** Defines the participant role in an agent conversation.
  *
  * Corresponds to `io.agentscope.core.message.MsgRole` in AgentScope Java.
  */
enum Role:
  case System
  case User
  case Assistant
  case Tool

object Role:
  given JsonCodec[Role] = JsonCodec.string.transformOrFail(
    str =>
      str.toLowerCase match
        case "system"    => Right(Role.System)
        case "user"      => Right(Role.User)
        case "assistant" => Right(Role.Assistant)
        case "tool"      => Right(Role.Tool)
        case other       => Left(s"Invalid message role: $other"),
    {
      case Role.System    => "system"
      case Role.User      => "user"
      case Role.Assistant => "assistant"
      case Role.Tool      => "tool"
    }
  )

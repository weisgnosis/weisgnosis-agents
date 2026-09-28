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

/**
 * Represents the terminal or in-flight execution state of a tool invocation.
 *
 * Corresponds to `io.agentscope.core.message.ToolResultState` in AgentScope Java.
 */
enum ToolResultState:
  case Success
  case Error
  case Interrupted
  case Denied
  case Running

object ToolResultState:
  given JsonCodec[ToolResultState] = JsonCodec.string.transformOrFail(
    str =>
      str.toLowerCase match
        case "success"     => Right(ToolResultState.Success)
        case "error"       => Right(ToolResultState.Error)
        case "interrupted" => Right(ToolResultState.Interrupted)
        case "denied"      => Right(ToolResultState.Denied)
        case "running"     => Right(ToolResultState.Running)
        case other         => Left(s"Invalid tool result state: $other"),
    {
      case ToolResultState.Success     => "success"
      case ToolResultState.Error       => "error"
      case ToolResultState.Interrupted => "interrupted"
      case ToolResultState.Denied      => "denied"
      case ToolResultState.Running     => "running"
    }
  )

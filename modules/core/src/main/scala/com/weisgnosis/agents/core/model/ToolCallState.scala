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

/** Protocol lifecycle data; permission and execution authority remain in the runtime. */
enum ToolCallState:
  case Pending, Asking, Allowed, Submitted, Finished

object ToolCallState:
  given JsonCodec[ToolCallState] = JsonCodec.string.transformOrFail(
    value =>
      value match
        case "pending"   => Right(ToolCallState.Pending)
        case "asking"    => Right(ToolCallState.Asking)
        case "allowed"   => Right(ToolCallState.Allowed)
        case "submitted" => Right(ToolCallState.Submitted)
        case "finished"  => Right(ToolCallState.Finished)
        case other       => Left(s"Unknown tool call state: $other"),
    {
      case ToolCallState.Pending   => "pending"
      case ToolCallState.Asking    => "asking"
      case ToolCallState.Allowed   => "allowed"
      case ToolCallState.Submitted => "submitted"
      case ToolCallState.Finished  => "finished"
    }
  )

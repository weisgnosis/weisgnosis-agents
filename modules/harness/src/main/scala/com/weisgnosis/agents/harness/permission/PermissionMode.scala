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
package com.weisgnosis.agents.harness.permission

import com.weisgnosis.agents.core.error.AgentError
import zio.json.*

/** Host-selected fallback policy; explicit independent safety votes are never bypassed. */
enum PermissionMode(val wireName: String):
  case Default     extends PermissionMode("default")
  case Bypass      extends PermissionMode("bypass")
  case DontAsk     extends PermissionMode("dont_ask")
  case Explore     extends PermissionMode("explore")
  case AcceptEdits extends PermissionMode("accept_edits")

object PermissionMode:
  // Host names are forgiving; the wire protocol is an exact scalar vocabulary.
  given JsonCodec[PermissionMode] = JsonCodec.string.transformOrFail(
    value => PermissionMode.values.find(_.wireName == value).toRight("Unknown permission mode"),
    _.wireName
  )

  def fromString(value: String): Either[AgentError.InvalidConfiguration, PermissionMode] =
    PermissionMode.values
      .find(mode => value.equalsIgnoreCase(mode.wireName))
      .toRight(AgentError.InvalidConfiguration("Unknown permission mode"))

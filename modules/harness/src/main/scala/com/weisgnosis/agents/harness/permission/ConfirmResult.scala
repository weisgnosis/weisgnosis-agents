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

import com.weisgnosis.agents.core.model.ContentBlock
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

final case class ConfirmResult(
    confirmed: Boolean,
    modifiedInput: Option[Json] = None,
    newRules: Chunk[PermissionRule] = Chunk.empty,
    reason: Option[String] = None
) derives JsonCodec

/** Serializable durable suspension. The host persists this value before ending an activity. */
final case class PendingApproval(requestId: String, call: ContentBlock.ToolUse) derives JsonCodec

enum PermissionOutcome derives JsonCodec:
  case Approved(call: ContentBlock.ToolUse)
  case Denied(toolName: String, reason: String)
  case AwaitingApproval(pending: PendingApproval)

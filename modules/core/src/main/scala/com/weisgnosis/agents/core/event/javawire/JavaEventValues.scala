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

/** Java DTO values only; these enums do not select runtime behavior or grant permission. */
enum JavaMessageRole(val wireName: String):
  case User      extends JavaMessageRole("USER")
  case Assistant extends JavaMessageRole("ASSISTANT")
  case System    extends JavaMessageRole("SYSTEM")
  case Tool      extends JavaMessageRole("TOOL")

object JavaMessageRole:
  // Scalar enum encoding follows Jackson rather than the Scala tagged-object representation.
  given JsonCodec[JavaMessageRole] = JsonCodec.string.transformOrFail(
    value =>
      JavaMessageRole.values.find(_.wireName == value).toRight(s"Unknown JavaMessageRole: $value"),
    _.wireName
  )

enum JavaPermissionBehavior(val wireName: String):
  case Allow       extends JavaPermissionBehavior("allow")
  case Deny        extends JavaPermissionBehavior("deny")
  case Ask         extends JavaPermissionBehavior("ask")
  case Passthrough extends JavaPermissionBehavior("passthrough")

object JavaPermissionBehavior:
  // Scalar enum encoding follows Jackson rather than the Scala tagged-object representation.
  given JsonCodec[JavaPermissionBehavior] = JsonCodec.string.transformOrFail(
    value =>
      JavaPermissionBehavior.values
        .find(_.wireName == value)
        .toRight(s"Unknown JavaPermissionBehavior: $value"),
    _.wireName
  )

enum JavaGenerateReason(val wireName: String):
  case ModelStop               extends JavaGenerateReason("MODEL_STOP")
  case ToolCalls               extends JavaGenerateReason("TOOL_CALLS")
  case StructuredOutput        extends JavaGenerateReason("STRUCTURED_OUTPUT")
  case ToolSuspended           extends JavaGenerateReason("TOOL_SUSPENDED")
  case ReasoningStopRequested  extends JavaGenerateReason("REASONING_STOP_REQUESTED")
  case ActingStopRequested     extends JavaGenerateReason("ACTING_STOP_REQUESTED")
  case PermissionAsking        extends JavaGenerateReason("PERMISSION_ASKING")
  case MiddlewareStopRequested extends JavaGenerateReason("MIDDLEWARE_STOP_REQUESTED")
  case AllToolsDenied          extends JavaGenerateReason("ALL_TOOLS_DENIED")
  case Interrupted             extends JavaGenerateReason("INTERRUPTED")
  case MaxIterations           extends JavaGenerateReason("MAX_ITERATIONS")
  case ToolReturnDirect        extends JavaGenerateReason("TOOL_RETURN_DIRECT")

object JavaGenerateReason:
  // Scalar enum encoding follows Jackson rather than the Scala tagged-object representation.
  given JsonCodec[JavaGenerateReason] = JsonCodec.string.transformOrFail(
    value =>
      JavaGenerateReason.values
        .find(_.wireName == value)
        .toRight(s"Unknown JavaGenerateReason: $value"),
    _.wireName
  )

/** Counts and time are supplied data; no request timing or clock read occurs here. */
final case class JavaChatUsage(
    inputTokens: Int,
    outputTokens: Int,
    cachedTokens: Int = 0,
    cacheCreationTokens: Int = 0,
    reasoningTokens: Int = 0,
    toolUsePromptTokens: Int = 0,
    time: BigDecimal = BigDecimal(0)
):
  def totalTokens: Int = inputTokens + outputTokens

object JavaChatUsage:
  final private case class Wire(
      inputTokens: Int = 0,
      outputTokens: Int = 0,
      cachedTokens: Int = 0,
      cacheCreationTokens: Int = 0,
      reasoningTokens: Int = 0,
      toolUsePromptTokens: Int = 0,
      time: BigDecimal = BigDecimal(0),
      totalTokens: Option[Int] = None
  ) derives JsonCodec

  // Java exposes totalTokens as a computed getter rather than a constructor argument.
  given JsonCodec[JavaChatUsage] = JsonCodec[Wire].transformOrFail(
    value =>
      Either.cond(
        value.totalTokens.forall(_ == value.inputTokens + value.outputTokens),
        JavaChatUsage(
          value.inputTokens,
          value.outputTokens,
          value.cachedTokens,
          value.cacheCreationTokens,
          value.reasoningTokens,
          value.toolUsePromptTokens,
          value.time
        ),
        "Usage totalTokens disagrees with inputTokens + outputTokens"
      ),
    value =>
      Wire(
        value.inputTokens,
        value.outputTokens,
        value.cachedTokens,
        value.cacheCreationTokens,
        value.reasoningTokens,
        value.toolUsePromptTokens,
        value.time,
        Some(value.totalTokens)
      )
  )

final case class JavaPermissionRule(
    @jsonField("tool_name") @jsonExplicitNull toolName: Option[String],
    @jsonField("rule_content") @jsonExplicitNull ruleContent: Option[String],
    behavior: JavaPermissionBehavior,
    @jsonExplicitNull source: Option[String]
) derives JsonCodec

final case class JavaConfirmResult(
    confirmed: Boolean,
    @jsonExplicitNull toolCall: Option[JavaContentBlock.ToolUse],
    @jsonExplicitNull rules: Option[Chunk[JavaPermissionRule]],
    @jsonExplicitNull reason: Option[String]
) derives JsonCodec

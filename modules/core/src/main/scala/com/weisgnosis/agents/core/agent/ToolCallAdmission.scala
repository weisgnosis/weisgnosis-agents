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
package com.weisgnosis.agents.core.agent

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.ModelStreamContext
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.model.ContentBlock
import com.weisgnosis.agents.core.tool.*
import zio.*
import zio.json.ast.Json

/** Checked tool admission owns no history, execution, event publication or completion state. */
final private[agent] class ToolCallAdmission(toolAuthorizer: ToolAuthorizer):
  import ToolCallAdmission.ArgumentPolicy

  def validateExternal(
      name: String,
      input: Json,
      registry: Toolkit
  ): UIO[Either[AgentError.InvalidMessage, Unit]] =
    ZIO
      .suspendSucceed(ZIO.succeed(registry.validateExternalInput(name, input)))
      .catchAllCause(cause =>
        if cause.isInterrupted then ZIO.refailCause(cause)
        else
          ZIO.succeed(
            Left(AgentError.InvalidMessage(s"External tool argument validation failed: $name"))
          )
      )

  def validateToolInput(
      name: String,
      input: Json,
      registry: Toolkit
  ): UIO[Either[AgentError.InvalidMessage, Unit]] =
    ZIO
      .suspendSucceed(
        registry.validateSchemaInput(name, input) *>
          ZIO.fromEither(registry.validateExternalInput(name, input))
      )
      .either
      .catchAllCause(cause =>
        if cause.isInterrupted then ZIO.refailCause(cause)
        else
          ZIO.succeed(
            Left(AgentError.InvalidMessage(s"Tool argument validation failed: $name"))
          )
      )

  def admittedAccess(
      call: ContentBlock.ToolUse,
      toolkit: Toolkit
  ): Option[AdmittedToolAccess] =
    toolkit
      .resolve(call.name)
      .map(tool =>
        if tool.isReadOnly then AdmittedToolAccess.ReadOnly else AdmittedToolAccess.Mutating
      )

  def authorizeTool(
      call: ContentBlock.ToolUse,
      callOrdinal: Int,
      requestId: String,
      preparedInput: Json,
      context: ModelStreamContext,
      toolkit: Toolkit,
      argumentPolicy: ArgumentPolicy
  ): UIO[ToolAuthorization] =
    val decision =
      if changesReasoningInput(call, preparedInput, argumentPolicy) then
        ZIO.succeed(
          ToolAuthorization.Denied("Cannot modify arguments carrying provider reasoning metadata")
        )
      else
        ZIO
          .scoped(
            ZIO.suspendSucceed:
              toolAuthorizer.authorize(
                ToolAuthorizationRequest(
                  requestId,
                  context,
                  withToolInput(call, preparedInput),
                  Some(callOrdinal)
                ),
                admittedAccess(call, toolkit)
              )
          )
          .flatMap: result =>
            result match
              case ToolAuthorization.Approved(input) =>
                JsonBoundary.validate(input) match
                  case Left(error) =>
                    ZIO.succeed(ToolAuthorization.Denied(s"Invalid approved input: $error"))
                  case Right(_) if changesReasoningInput(call, input, argumentPolicy) =>
                    ZIO.succeed(
                      ToolAuthorization.Denied(
                        "Cannot modify arguments carrying provider reasoning metadata"
                      )
                    )
                  case Right(_) =>
                    (ZIO.fromEither(
                      toolkit
                        .validateApprovedInput(call.name, input)
                        .flatMap(_ => toolkit.validateExternalInput(call.name, input))
                    ) *> toolkit.validateSchemaInput(call.name, input)).fold(
                      error =>
                        ToolAuthorization.Denied(s"Invalid approved input: ${error.message}"),
                      _ => result
                    )
              case ToolAuthorization.Deferred(reason) if reason.trim.isEmpty =>
                ZIO.succeed(
                  ToolAuthorization.Denied("Deferred authorization requires a nonblank reason")
                )
              case _ => ZIO.succeed(result)
    decision.foldCauseZIO(
      cause =>
        if cause.isInterrupted then ZIO.refailCause(cause).orDie
        else
          ZIO.succeed(
            ToolAuthorization.Denied(
              cause.failureOption.map(_.reason).getOrElse("Tool authorization failed")
            )
          )
      ,
      ZIO.succeed(_)
    )

  def changesReasoningInput(
      call: ContentBlock.ToolUse,
      input: Json,
      argumentPolicy: ArgumentPolicy
  ): Boolean =
    input != call.input &&
      (argumentPolicy == ArgumentPolicy.Preserve || call.metadata.contains("thoughtSignature") ||
        call.metadata.contains("reasoningDetails"))

  def withToolInput(call: ContentBlock.ToolUse, input: Json): ContentBlock.ToolUse =
    if input == call.input then call
    else
      call.copy(
        input = input,
        content = None,
        metadata = call.metadata.updated("agentscope_original_tool_input", call.input)
      )

private[agent] object ToolCallAdmission:
  enum ArgumentPolicy:
    case AllowRewrite, Preserve

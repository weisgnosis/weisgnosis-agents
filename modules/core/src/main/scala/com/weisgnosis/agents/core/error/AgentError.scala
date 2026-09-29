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
package com.weisgnosis.agents.core.error

import zio.json.*

/**
 * Typed recoverable domain errors for the agent runtime.
 */
sealed trait AgentError extends Throwable:
  def message: String
  override def getMessage: String = message

object AgentError:

  final case class InvalidConfiguration(message: String) extends AgentError derives JsonCodec

  final case class InvalidMessage(message: String) extends AgentError derives JsonCodec

  final case class ModelError(
      statusCode: Int,
      message: String,
      errorPayload: Option[String] = None
  ) extends AgentError derives JsonCodec

  final case class ToolExecutionError(
      toolName: String,
      message: String,
      cause: Option[String] = None
  ) extends AgentError derives JsonCodec

  final case class ToolNotFoundError(
      toolName: String
  ) extends AgentError derives JsonCodec:
    def message: String = s"Tool not found: $toolName"

  final case class ContextLengthExceeded(
      currentTokens: Long,
      limit: Long
  ) extends AgentError derives JsonCodec:
    def message: String = s"Context length exceeded: current=$currentTokens, limit=$limit"

  final case class PermissionDenied(
      toolName: String,
      reason: String
  ) extends AgentError derives JsonCodec:
    def message: String = s"Permission denied for tool '$toolName': $reason"

  final case class MaxIterationsExceeded(
      iterations: Int
  ) extends AgentError derives JsonCodec:
    def message: String = s"Maximum ReAct iterations reached: $iterations"

  final case class Interrupted(
      message: String
  ) extends AgentError derives JsonCodec

  final case class DeserializationError(
      message: String
  ) extends AgentError derives JsonCodec

  final case class TimeoutError(
      timeoutSeconds: Long,
      message: String
  ) extends AgentError derives JsonCodec

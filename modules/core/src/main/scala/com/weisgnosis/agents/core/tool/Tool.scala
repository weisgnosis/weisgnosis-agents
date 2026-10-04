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
package com.weisgnosis.agents.core.tool

import com.weisgnosis.agents.core.error.AgentError
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.{ZChannel, ZStream}
import scala.annotation.unused

/**
 * Type-safe functional tool interface with suspended ZIO execution.
 *
 * Corresponds to `io.agentscope.core.tool.Tool` in AgentScope Java.
 */
trait Tool[-R, In, Out]:
  def fixedArguments: ToolFixedArguments = ToolFixedArguments.empty
  def definition: ToolDef
  def isConcurrencySafe: Boolean = definition.isConcurrencySafe
  def isReadOnly: Boolean        = definition.isReadOnly
  def execute(input: In): ZIO[R, Throwable, Out]

  /** Runtime invocation capabilities are explicit; existing tools retain their ordinary execution. */
  def executeWithContext(input: In, @unused invocation: ToolInvocation): ZIO[R, Throwable, Out] =
    execute(input)

  /** Provisional updates precede exactly one completion; consumers must join EOF and cleanup. */
  def streamWithContext(
      input: In,
      invocation: ToolInvocation
  ): ZStream[R, Throwable, ToolExecutionUpdate[Out]] =
    ZStream.fromChannel(
      ZChannel
        .fromZIO(ZIO.suspendSucceed(executeWithContext(input, invocation)))
        .flatMap(output =>
          ZChannel.write(Chunk[ToolExecutionUpdate[Out]](ToolExecutionUpdate.Completed(output)))
        )
    )

  def decodeInput(raw: Json): Either[String, In]
  def encodeOutput(out: Out): String

  /** Completed observations may supply typed content and Success or Error state. */
  def encodeResult(out: Out): ToolExecutionResult = ToolExecutionResult(encodeOutput(out))

object Tool:

  /** Bind once; preparation applies only at raw decoding/dispatch, never to trusted typed execution. */
  def withFixedArguments[R, In, Out](
      tool: Tool[R, In, Out],
      fixed: ToolFixedArguments
  ): Either[AgentError.InvalidConfiguration, Tool[R, In, Out]] =
    if fixed.isEmpty then Right(tool)
    else if !tool.fixedArguments.isEmpty then
      Left(AgentError.InvalidConfiguration("Tool already has nonempty fixed arguments"))
    else
      Right(new Tool[R, In, Out]:
        override def fixedArguments: ToolFixedArguments = fixed
        def definition: ToolDef                         = tool.definition
        override def isReadOnly: Boolean                = tool.isReadOnly
        override def isConcurrencySafe: Boolean         = tool.isConcurrencySafe
        def execute(input: In): ZIO[R, Throwable, Out]  = tool.execute(input)
        override def executeWithContext(
            input: In,
            invocation: ToolInvocation
        ): ZIO[R, Throwable, Out] =
          tool.executeWithContext(input, invocation)
        override def streamWithContext(
            input: In,
            invocation: ToolInvocation
        ): ZStream[R, Throwable, ToolExecutionUpdate[Out]] =
          tool.streamWithContext(input, invocation)
        def decodeInput(raw: Json): Either[String, In] =
          fixed.prepare(raw).left.map(_.message).flatMap(tool.decodeInput)
        def encodeOutput(out: Out): String                       = tool.encodeOutput(out)
        override def encodeResult(out: Out): ToolExecutionResult = tool.encodeResult(out))

  def fromFunction[In: JsonDecoder, Out: JsonEncoder](
      name: String,
      description: String,
      schema: Json = Json.Obj(),
      isReadOnly: Boolean = false,
      isConcurrencySafe: Boolean = true
  )(run: In => ZIO[Any, Throwable, Out]): Tool[Any, In, Out] =
    val defn = ToolDef(name, description, schema, isConcurrencySafe, isReadOnly)
    new Tool[Any, In, Out]:
      def definition: ToolDef                          = defn
      def execute(input: In): ZIO[Any, Throwable, Out] = run(input)
      def decodeInput(raw: Json): Either[String, In]   = raw.as[In]
      def encodeOutput(out: Out): String               = out.toJson

  def make(
      defn: ToolDef
  )(run: Json => ZIO[Any, Throwable, String]): Tool[Any, Json, String] =
    new Tool[Any, Json, String]:
      def definition: ToolDef                               = defn
      def execute(input: Json): ZIO[Any, Throwable, String] = run(input)
      def decodeInput(raw: Json): Either[String, Json]      = Right(raw)
      def encodeOutput(out: String): String                 = out

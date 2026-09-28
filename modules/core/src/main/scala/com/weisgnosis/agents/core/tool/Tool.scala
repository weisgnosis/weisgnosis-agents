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

import zio.*
import zio.json.*
import zio.json.ast.Json

/** Type-safe functional tool interface with suspended ZIO execution.
  *
  * Corresponds to `io.agentscope.core.tool.Tool` in AgentScope Java.
  */
trait Tool[-R, In, Out]:
  def definition: ToolDef
  def isConcurrencySafe: Boolean = definition.isConcurrencySafe
  def isReadOnly: Boolean = definition.isReadOnly
  def execute(input: In): ZIO[R, Throwable, Out]
  def decodeInput(raw: Json): Either[String, In]
  def encodeOutput(out: Out): String

object Tool:

  def fromFunction[In: JsonDecoder, Out: JsonEncoder](
    name: String,
    description: String,
    schema: Json = Json.Obj(),
    isReadOnly: Boolean = false,
    isConcurrencySafe: Boolean = true
  )(run: In => ZIO[Any, Throwable, Out]): Tool[Any, In, Out] =
    val defn = ToolDef(name, description, schema, isConcurrencySafe, isReadOnly)
    new Tool[Any, In, Out]:
      def definition: ToolDef = defn
      def execute(input: In): ZIO[Any, Throwable, Out] = run(input)
      def decodeInput(raw: Json): Either[String, In] = raw.as[In]
      def encodeOutput(out: Out): String = out.toJson

  def make(
    defn: ToolDef
  )(run: Json => ZIO[Any, Throwable, String]): Tool[Any, Json, String] =
    new Tool[Any, Json, String]:
      def definition: ToolDef = defn
      def execute(input: Json): ZIO[Any, Throwable, String] = run(input)
      def decodeInput(raw: Json): Either[String, Json] = Right(raw)
      def encodeOutput(out: String): String = out

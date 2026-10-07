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

import com.weisgnosis.agents.core.model.ToolResultState
import zio.*
import zio.json.ast.Json
import zio.test.*

object ToolFixedArgumentsSpec extends ZIOSpecDefault:
  final private case class Input(value: String)
  final private case class Output(value: String)

  def spec: Spec[TestEnvironment, Any] = suite("ToolFixedArgumentsSpec")(
    test(
      "checked fixed fields preserve order, enforce approved consistency and leave empty policies transparent"
    ) {
      val duplicate = Json.Obj("key" -> Json.Num(1), "key" -> Json.Num(2))
      val fixed = Json.Obj("locked" -> Json.Null, "nested" -> Json.Obj("safe" -> Json.Bool(true)))
      val raw = Json.Obj(
        "first"  -> Json.Str("kept"),
        "locked" -> Json.Str("replace"),
        "last"   -> Json.Num(3)
      )
      val expected = Json.Obj(
        "first"  -> Json.Str("kept"),
        "locked" -> Json.Null,
        "last"   -> Json.Num(3),
        "nested" -> Json.Obj("safe" -> Json.Bool(true))
      )
      val emptyInputs = Chunk[Json](Json.Str("scalar"), Json.Arr(Json.Num(1)), duplicate)
      for policy <- ZIO.fromEither(ToolFixedArguments.make(fixed))
      yield assertTrue(
        policy.prepare(raw) == Right(expected),
        policy.prepare(expected) == Right(expected),
        policy.validateApproved(expected) == Right(()),
        policy.validateApproved(Json.Obj(expected.fields.map { case (key, value) =>
          key -> (if key == "first" then Json.Str("approved") else value)
        })) == Right(()),
        policy.validateApproved(raw).isLeft,
        policy.validateApproved(Json.Obj("nested" -> Json.Obj("safe" -> Json.Bool(true)))).isLeft,
        policy.prepare(Json.Obj("locked" -> duplicate)).isLeft,
        policy.prepare(Json.Arr(Json.Obj())).isLeft,
        ToolFixedArguments.make(Json.Obj("nested" -> duplicate)).isLeft,
        ToolFixedArguments.make(duplicate).isLeft,
        emptyInputs.forall(value => ToolFixedArguments.empty.prepare(value) == Right(value)),
        emptyInputs.forall(value => ToolFixedArguments.empty.validateApproved(value) == Right(()))
      )
    },
    test(
      "typed wrappers preserve handlers, observations and explicit flags through toolkit selection and merging"
    ) {
      val defn =
        ToolDef(
          "typed",
          "Typed tool",
          Json.Obj(),
          isReadOnly = false,
          isConcurrencySafe = true,
          schemaStrictness = Some(ToolSchemaStrictness.Strict)
        )
      val original = new Tool[Any, Input, Output]:
        def definition: ToolDef                 = defn
        override def isReadOnly: Boolean        = true
        override def isConcurrencySafe: Boolean = false
        def execute(input: Input): UIO[Output]  = ZIO.succeed(Output("direct:" + input.value))
        override def executeWithContext(input: Input, invocation: ToolInvocation): UIO[Output] =
          ZIO.succeed(Output(s"context:$invocation:${input.value}"))
        def decodeInput(raw: Json): Either[String, Input] =
          raw.asObject
            .flatMap(_.get("value"))
            .flatMap(_.asString)
            .toRight("Expected value")
            .map(Input.apply)
        def encodeOutput(out: Output): String = "text:" + out.value
        override def encodeResult(out: Output): ToolExecutionResult =
          ToolExecutionResult("typed:" + out.value, ToolResultState.Error)
      for
        fixed   <- ZIO.fromEither(ToolFixedArguments.make(Json.Obj("value" -> Json.Str("fixed"))))
        wrapped <- ZIO.fromEither(Tool.withFixedArguments(original, fixed))
        registered <- ZIO.fromEither(Toolkit(wrapped))
        selected = registered.filterNames(_ == "typed")
        merged <- ZIO.fromEither(Toolkit.empty ++ selected)
        direct <- wrapped.execute(Input("trusted"))
        result <- merged.executeSafe("typed", Json.Obj("value" -> Json.Str("model")))
      yield assertTrue(
        wrapped.definition == defn,
        wrapped.isReadOnly,
        !wrapped.isConcurrencySafe,
        wrapped.decodeInput(Json.Obj("value" -> Json.Str("model"))) == Right(Input("fixed")),
        direct == Output("direct:trusted"),
        wrapped.encodeOutput(direct) == "text:direct:trusted",
        result == ToolExecutionResult("typed:context:Detached:fixed", ToolResultState.Error),
        merged
          .resolve("typed")
          .exists(tool => tool.fixedArguments == fixed && tool.definition == defn),
        merged.prepareInput("typed", Json.Obj()) == Right(Json.Obj("value" -> Json.Str("fixed"))),
        merged.validateApprovedInput("typed", Json.Obj()).isLeft,
        Tool.withFixedArguments(wrapped, fixed).isLeft,
        Tool.withFixedArguments(original, ToolFixedArguments.empty) == Right(original),
        Tool.withFixedArguments(wrapped, ToolFixedArguments.empty) == Right(wrapped)
      )
    },
    test(
      "direct toolkit dispatch enforces custom attached policies before decoding without changing empty or missing dispatch"
    ) {
      for
        fixed <- ZIO.fromEither(ToolFixedArguments.make(Json.Obj("value" -> Json.Str("fixed"))))
        seen  <- Ref.make(Chunk.empty[Json])
        custom = new Tool[Any, Json, String]:
          def definition: ToolDef                          = ToolDef("custom", "Custom", Json.Obj())
          override def fixedArguments: ToolFixedArguments  = fixed
          def execute(input: Json): UIO[String]            = seen.update(_ :+ input).as("ran")
          def decodeInput(raw: Json): Either[String, Json] = Right(raw)
          def encodeOutput(out: String): String            = out
        legacy = Tool.make(ToolDef("legacy", "Legacy", Json.Obj()))(input =>
          ZIO.succeed(input.toString)
        )
        toolkit <- ZIO.fromEither(Toolkit(custom, legacy))
        valid   <- toolkit.executeSafe("custom", Json.Obj("value" -> Json.Str("model")))
        invalid <- toolkit.executeSafe(
          "custom",
          Json.Obj("value" -> Json.Obj("x" -> Json.Num(1), "x" -> Json.Num(2)))
        )
        scalar  <- toolkit.executeSafe("legacy", Json.Str("scalar"))
        missing <- toolkit.executeSafe("missing", Json.Str("scalar"))
        inputs  <- seen.get
      yield assertTrue(
        valid.state == ToolResultState.Success,
        invalid.state == ToolResultState.Error,
        inputs == Chunk(Json.Obj("value" -> Json.Str("fixed"))),
        scalar.state == ToolResultState.Success,
        missing.state == ToolResultState.Error,
        toolkit.prepareInput("missing", Json.Str("scalar")) == Right(Json.Str("scalar")),
        toolkit.validateApprovedInput("missing", Json.Str("scalar")) == Right(())
      )
    }
  )

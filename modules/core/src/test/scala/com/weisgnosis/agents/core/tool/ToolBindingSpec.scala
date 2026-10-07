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
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ToolBindingSpec extends ZIOSpecDefault:
  final private case class Arguments(endpoint: String) derives JsonCodec

  def spec: Spec[TestEnvironment, Any] = suite("ToolBindingSpec")(
    test("external declarations validate without local execution and survive registry operations"):
      val definition = ToolDef("external", "External API", Json.Obj(), isReadOnly = true)
      val valid      = Json.Obj("endpoint" -> Json.Str("/users"))
      val invalid    = Json.Obj("endpoint" -> Json.Num(42))
      for
        external <- ZIO.fromEither(ToolBinding.external[Arguments](definition))
        registry = Toolkit.single(external)
        validDispatch <- registry.dispatchObserved("external", valid, ToolInvocation.Detached)(_ =>
          ZIO.dieMessage("Unexpected progress")
        )
        invalidDispatch <- registry.dispatchObserved("external", invalid, ToolInvocation.Detached)(
          _ => ZIO.dieMessage("Unexpected progress")
        )
        legacy <- registry.executeSafe("external", valid)
        calls  <- Ref.make(0)
        local         = Tool.make(definition)(_ => calls.update(_ + 1).as("local"))
        localRegistry = registry.updated(local)
        localResult <- localRegistry.executeSafe("external", valid)
        count       <- calls.get
        restored    <- ZIO.fromEither(Toolkit.fromBindings(registry.tools))
        invalidIsError = invalidDispatch match
          case Toolkit.Dispatch.Completed(value) => value.result.state == ToolResultState.Error
          case _                                 => false
      yield assertTrue(
        validDispatch == Toolkit.Dispatch.AwaitingExternal(valid),
        invalidIsError,
        legacy.state == ToolResultState.Error,
        registry.validateExternalInput("external", invalid).isLeft,
        registry.validateExternalInput("unknown", invalid).isRight,
        registry.register(local).isLeft,
        restored.resolve("external").contains(external),
        restored.filterNames(_ == "external").resolve("external").contains(external),
        localRegistry.overlay(registry).resolve("external").contains(external),
        (registry ++ localRegistry).isLeft,
        Toolkit.fromBindings(Map("alias" -> external)).isLeft,
        localResult.output == "local",
        count == 1,
        external.fixedArguments == ToolFixedArguments.empty,
        external.isReadOnly,
        ToolBinding.external[Arguments](definition.copy(name = " ")).isLeft
      )
  )

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

import com.weisgnosis.agents.core.agent.{AgentConfig, ReActAgent, ToolExecution}
import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolResultState}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ToolGroupsSpec extends ZIOSpecDefault:
  private val first: ToolGroup = ToolGroup("first", "First group", tools = Set("shared", "one"))
  private val second: ToolGroup =
    ToolGroup("second", "Second group", ToolGroupActivation.Inactive, tools = Set("shared", "two"))

  def spec: Spec[TestEnvironment, Any] = suite("ToolGroupsSpec")(
    test("notes sort group names and separate host visibility from Meta activation") {
      val external   = ToolGroup("external", "Host controlled", scope = ToolGroupScope.External)
      val inactive   = second.copy(description = "")
      val noActive   = "No tool groups are currently activated."
      val noInactive = "No tool groups are currently inactive."
      val cases = Chunk(
        (
          Chunk.empty[ToolGroup],
          noActive,
          s"Activated tool groups:\n$noActive\nInactive tool groups:\n$noInactive\n"
        ),
        (
          Chunk(inactive),
          noActive,
          s"Activated tool groups:\n$noActive\nInactive tool groups:\n- second: \n"
        ),
        (
          Chunk(second, first, external),
          "Activated tool groups:\n- external: Host controlled\n- first: First group\n",
          "Activated tool groups:\n- first: First group\nInactive tool groups:\n- second: Second group\n"
        ),
        (
          Chunk(external),
          "Activated tool groups:\n- external: Host controlled\n",
          s"Activated tool groups:\n$noActive\nInactive tool groups:\n$noInactive\n"
        )
      )
      ZIO
        .foreach(cases): (values, active, meta) =>
          ZIO
            .fromEither(
              values.foldLeft[Either[AgentError, ToolGroups]](Right(ToolGroups.empty))(
                (result, value) => result.flatMap(_.register(value))
              )
            )
            .map(groups => assertTrue(groups.activeNotes == active, groups.metaNotes == meta))
        .map(results => results.foldLeft(assertTrue(true))(_ && _))
    },
    test(
      "registration and multi-name activation validate atomically without mutating earlier snapshots"
    ) {
      for
        original <- ZIO.fromEither(ToolGroups.empty.register(first).flatMap(_.register(second)))
        changed <- ZIO.fromEither(
          original.updateActivation(Set("first", "second"), ToolGroupActivation.Inactive)
        )
        duplicate <- ZIO.fromEither(original.addTool("first", "shared"))
        reenabled <- ZIO.fromEither(
          changed.updateActivation(Set("second"), ToolGroupActivation.Active)
        )
        added <- ZIO.fromEither(original.addTool("second", "new"))
      yield assertTrue(
        original.activeNames == Set("first"),
        changed.activeNames.isEmpty,
        reenabled.activeNames == Set("second"),
        original.memberships("new").isEmpty,
        original.isActiveTool("new"),
        added.memberships("new") == Set("second"),
        !added.isActiveTool("new"),
        duplicate.groups == original.groups,
        original.register(first).isLeft,
        Chunk("", " ", "\t").forall(name =>
          ToolGroups.empty.register(first.copy(name = name)).isLeft
        ),
        ToolGroups.empty.register(first.copy(tools = Set(""))).isLeft,
        original.updateActivation(Set("first", "absent"), ToolGroupActivation.Inactive).isLeft,
        original.replaceActive(Set("second", "absent")).isLeft,
        original.addTool("absent", "one").isLeft,
        original.removeTool("absent", "one").isLeft,
        original.addTool("first", " ").isLeft,
        original.removeTool("first", " ").isLeft,
        first.toJson.fromJson[ToolGroup] == Right(first)
      )
    },
    test(
      "Meta replacement preserves External activation while full replacement includes every scope"
    ) {
      val external = ToolGroup("external", "Host controlled", scope = ToolGroupScope.External)
      for
        original <- ZIO.fromEither(
          ToolGroups.empty.register(first).flatMap(_.register(second)).flatMap(_.register(external))
        )
        meta      <- ZIO.fromEither(original.replaceMetaActive(Set("second")))
        clearMeta <- ZIO.fromEither(meta.replaceMetaActive(Set.empty))
        full      <- ZIO.fromEither(original.replaceActive(Set("second")))
        clear     <- ZIO.fromEither(full.replaceActive(Set.empty))
      yield assertTrue(
        meta.activeNames == Set("second", "external"),
        clearMeta.activeNames == Set("external"),
        full.activeNames == Set("second"),
        clear.activeNames.isEmpty,
        original.replaceMetaActive(Set("second", "external")).isLeft,
        original.replaceMetaActive(Set("second", "unknown")).isLeft,
        original.activeNames == Set("first", "external")
      )
    },
    test(
      "OR membership and both removal paths report orphaning while selecting exact fixed-policy tools"
    ) {
      val tool = new Tool[Any, Json, String]:
        def definition: ToolDef                          = ToolDef("shared", "Shared", Json.Obj())
        override def isReadOnly: Boolean                 = true
        override def isConcurrencySafe: Boolean          = false
        def execute(input: Json): UIO[String]            = ZIO.succeed(input.toString)
        def decodeInput(raw: Json): Either[String, Json] = Right(raw)
        def encodeOutput(out: String): String            = out
      for
        fixed    <- ZIO.fromEither(ToolFixedArguments.make(Json.Obj("tenant" -> Json.Str("host"))))
        wrapped  <- ZIO.fromEither(Tool.withFixedArguments(tool, fixed))
        toolkit  <- ZIO.fromEither(Toolkit(wrapped))
        original <- ZIO.fromEither(ToolGroups.empty.register(first).flatMap(_.register(second)))
        selected = original.select(toolkit)
        removed  = original.remove(Set("first", "missing"))
        last     = removed.groups.remove(Set("second"))
        member   <- ZIO.fromEither(removed.groups.removeTool("second", "shared"))
        repeated <- ZIO.fromEither(member.groups.removeTool("second", "shared"))
      yield assertTrue(
        original.memberships("shared") == Set("first", "second"),
        selected.resolve("shared").contains(wrapped),
        selected
          .resolve("shared")
          .exists(value =>
            value.isReadOnly && !value.isConcurrencySafe && value.fixedArguments == fixed
          ),
        selected.prepareInput("shared", Json.Obj()) == Right(
          Json.Obj("tenant" -> Json.Str("host"))
        ),
        removed.newlyUngrouped == Set("one"),
        removed.groups.select(toolkit).isEmpty,
        last.newlyUngrouped == Set("shared", "two"),
        last.groups.select(toolkit).resolve("shared").contains(wrapped),
        last.groups.remove(Set("second")).newlyUngrouped.isEmpty,
        member.newlyUngrouped == Set("shared"),
        member.groups.select(toolkit).resolve("shared").contains(wrapped),
        repeated.newlyUngrouped.isEmpty,
        original.select(toolkit).resolve("shared").contains(wrapped),
        Chunk(original, removed.groups, last.groups, member.groups).forall(groups =>
          toolkit.tools.keys.forall(name =>
            groups.isActiveTool(name) == groups.select(toolkit).contains(name)
          )
        )
      )
    },
    test(
      "an actual selected ReAct snapshot hides inactive definitions and blocks their local dispatch"
    ) {
      ZIO.scoped:
        for
          executed <- Ref.make(Chunk.empty[String])
          exposed  <- Ref.make(Chunk.empty[Chunk[String]])
          tools = Chunk("active", "hidden", "ungrouped").map(name =>
            Tool.make(ToolDef(name, name, Json.Obj()))(_ => executed.update(_ :+ name).as(name))
          )
          toolkit <- ZIO.fromEither(
            tools.foldLeft[Either[AgentError, Toolkit]](Right(Toolkit.empty))((result, tool) =>
              result.flatMap(_.register(tool))
            )
          )
          groups <- ZIO.fromEither(
            ToolGroups.empty
              .register(ToolGroup("enabled", "Enabled", tools = Set("active")))
              .flatMap(
                _.register(
                  ToolGroup(
                    "disabled",
                    "Disabled",
                    ToolGroupActivation.Inactive,
                    tools = Set("hidden")
                  )
                )
              )
          )
          selected = groups.select(toolkit)
          calls = Chunk("active", "hidden", "ungrouped").map(name =>
            ContentBlock.ToolUse(name, name, Json.Obj())
          )
          model = LlmClient.mockEffectful((history, definitions, _) =>
            exposed
              .update(_ :+ definitions.map(_.name))
              .as(
                if history.flatMap(_.toolResults).isEmpty then
                  TurnOutput.ToolCalls(Msg.assistant(calls))
                else TurnOutput.FinalText(Msg.assistant("done"))
              )
          )
          agent <- ReActAgent.make(
            config = AgentConfig(toolExecution = ToolExecution.Sequential),
            client = model,
            toolkit = selected
          )
          _           <- agent.run(Msg.user("use selected tools"))
          seen        <- executed.get
          definitions <- exposed.get
          history     <- agent.history
        yield assertTrue(
          seen == Chunk("active", "ungrouped"),
          definitions == Chunk.fill(2)(Chunk("active", "ungrouped")),
          history.flatMap(_.toolResults).map(_.state) == Chunk(
            ToolResultState.Success,
            ToolResultState.Error,
            ToolResultState.Success
          ),
          toolkit.contains("hidden"),
          !selected.contains("hidden")
        )
    }
  )

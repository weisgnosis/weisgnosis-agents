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

import com.weisgnosis.agents.core.client.*
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{AgentEvent, ModelStreamContext}
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolResultState}
import com.weisgnosis.agents.core.tool.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ReActAgentToolGroupsSpec extends ZIOSpecDefault:
  private val reset        = "reset_equipped_tools"
  private val capture      = StructuredOutput.ToolName
  private val schema: Json = Json.Obj("type" -> Json.Str("integer"))
  final private case class Request(tools: Chunk[ToolDef], config: LlmConfig)

  private def recording(
      requests: Ref[Chunk[Request]]
  )(respond: (Int, Request) => IO[AgentError, TurnOutput]): LlmClient = new LlmClient:
    def streamTurn(
        history: Chunk[Msg],
        tools: Chunk[ToolDef],
        config: LlmConfig,
        eventHub: Hub[AgentEvent],
        context: ModelStreamContext
    ): ZIO[Scope, AgentError, TurnOutput] =
      val request = Request(tools, config)
      requests
        .modify(current => current.size -> (current :+ request))
        .flatMap(index => respond(index, request))

  private def activate(names: String*): Json.Obj =
    Json.Obj("to_activate" -> Json.Arr(Chunk.fromIterable(names.map(Json.Str(_)))))

  private def call(id: String, name: String, input: Json = Json.Obj()): ContentBlock.ToolUse =
    ContentBlock.ToolUse(id, name, input)

  private def calls(values: ContentBlock.ToolUse*): TurnOutput =
    TurnOutput.ToolCalls(Msg.assistant(Chunk.fromIterable(values)))

  private def finish: TurnOutput = TurnOutput.FinalText(Msg.assistant("done"))

  private def groups: IO[AgentError, ToolGroups] = ZIO.fromEither(
    ToolGroups.empty
      .register(ToolGroup("a", "Initially available", tools = Set("a")))
      .flatMap(
        _.register(
          ToolGroup(
            "b",
            "Initially unavailable",
            ToolGroupActivation.Inactive,
            tools = Set("b", reset, capture)
          )
        )
      )
      .flatMap(
        _.register(
          ToolGroup(
            "external",
            "Host managed",
            scope = ToolGroupScope.External,
            tools = Set("external")
          )
        )
      )
  )

  private def toolkit(executed: Ref[Chunk[(String, Json)]]): IO[AgentError, Toolkit] =
    for
      fixed <- ZIO.fromEither(ToolFixedArguments.make(Json.Obj("tenant" -> Json.Str("host"))))
      result <- ZIO.fromEither(
        Chunk("a", "b", "external").foldLeft[Either[AgentError, Toolkit]](Right(Toolkit.empty))(
          (result, name) =>
            val tool = Tool.make(ToolDef(name, name, Json.Obj()))(input =>
              executed.update(_ :+ (name -> input)).as(name)
            )
            for
              current <- result
              bound <- Tool
                .withFixedArguments(tool, if name == "a" then fixed else ToolFixedArguments.empty)
              updated <- current.register(bound)
            yield updated
        )
      )
    yield result

  def spec: Spec[TestEnvironment, Any] = suite("ReActAgentToolGroupsSpec")(
    test(
      "reset changes the next request while every call in its batch retains the admitted snapshot"
    ) {
      val initialNotes =
        "Activated tool groups:\n- a: Initially available\nInactive tool groups:\n- b: Initially unavailable\n"
      val activatedNotes =
        "Activated tool groups:\n- b: Initially unavailable\nInactive tool groups:\n- a: Initially available\n"
      val clearedNotes =
        "Activated tool groups:\nNo tool groups are currently activated.\nInactive tool groups:\n- a: Initially available\n- b: Initially unavailable\n"
      ZIO.scoped:
        for
          defaults <- groups
          executed <- Ref.make(Chunk.empty[(String, Json)])
          requests <- Ref.make(Chunk.empty[Request])
          base     <- toolkit(executed)
          model = recording(requests)((index, _) =>
            ZIO.succeed(index match
              case 0 =>
                calls(
                  call("activate", reset, activate("b")),
                  call("new-too-soon", "b"),
                  call("old-admitted", "a")
                )
              case 1 =>
                calls(
                  call("clear", reset, activate()),
                  call("still-admitted", "b"),
                  call("old-hidden", "a")
                )
              case 2 => calls(call("external-call", "external"))
              case _ => finish)
          )
          agent <- ReActAgent.make(
            config = AgentConfig(toolExecution = ToolExecution.Parallel),
            client = model,
            toolkit = base,
            toolGroups = Some(defaults)
          )
          result   <- agent.run(Msg.user("select tools"))
          observed <- requests.get
          ran      <- executed.get
        yield assertTrue(
          observed.map(_.tools.map(_.name)) == Chunk(
            Chunk("a", "external", reset),
            Chunk("b", "external", reset),
            Chunk("external", reset),
            Chunk("external", reset)
          ),
          observed.forall(
            _.tools
              .find(_.name == reset)
              .exists(tool => !tool.isConcurrencySafe && !tool.isReadOnly)
          ),
          ran.map(_._1) == Chunk("a", "b", "external"),
          ran.find(_._1 == "a").exists(_._2 == Json.Obj("tenant" -> Json.Str("host"))),
          observed.forall(
            _.tools
              .find(_.name == reset)
              .exists(tool =>
                tool.description.contains("Initially unavailable") && !tool.description
                  .contains("Host managed")
              )
          ),
          observed
            .zip(Chunk(initialNotes, activatedNotes, clearedNotes, clearedNotes))
            .forall((request, notes) =>
              request.tools.find(_.name == reset).exists(_.description.endsWith(notes))
            ),
          result.history
            .flatMap(_.toolResults)
            .filter(_.name.contains(reset))
            .map(_.outputText.fromJson[Json]) == Chunk(
            Right(
              Json.Obj(
                "active_groups" -> Json.Arr(Json.Str("b")),
                "notes"         -> Json.Str(activatedNotes)
              )
            ),
            Right(Json.Obj("active_groups" -> Json.Arr(), "notes" -> Json.Str(clearedNotes)))
          ),
          result.history.flatMap(_.toolResults).map(_.state) == Chunk(
            ToolResultState.Success,
            ToolResultState.Error,
            ToolResultState.Success,
            ToolResultState.Success,
            ToolResultState.Success,
            ToolResultState.Error,
            ToolResultState.Success
          ),
          defaults.activeNames == Set("a", "external")
        )
    },
    test(
      "invalid and denied resets cannot partially activate groups or change External availability"
    ) {
      val invalid = Chunk[Json](
        Json.Obj(),
        Json.Str("b"),
        Json.Obj("to_activate" -> Json.Str("b")),
        Json.Obj("to_activate" -> Json.Arr(Json.Num(1))),
        Json.Obj("to_activate" -> Json.Arr(), "extra" -> Json.Bool(true)),
        activate("b", "missing"),
        activate("b", "external")
      )
      ZIO.scoped:
        for
          defaults <- groups
          executed <- Ref.make(Chunk.empty[(String, Json)])
          requests <- Ref.make(Chunk.empty[Request])
          approved <- Ref.make(Chunk.empty[String])
          base     <- toolkit(executed)
          badCalls = invalid.zipWithIndex.map((input, index) =>
            call(s"invalid-$index", reset, input)
          )
          duplicate = Json.Obj(
            "to_activate" -> Json.Arr(),
            "to_activate" -> Json.Arr(Json.Str("b"))
          )
          model = recording(requests)((index, _) =>
            ZIO.succeed(index match
              case 0 =>
                TurnOutput.ToolCalls(
                  Msg.assistant(
                    badCalls ++ Chunk(
                      call("duplicate", reset, duplicate),
                      call("denied", reset, activate("b"))
                    )
                  )
                )
              case 1 => calls(call("external-call", "external"), call("clear", reset, activate()))
              case _ => finish)
          )
          agent <- ReActAgent.make(
            client = model,
            toolkit = base,
            toolGroups = Some(defaults),
            toolAuthorizer = ToolAuthorizer(request =>
              approved
                .update(_ :+ request.call.id)
                .as(
                  if request.call.id == "denied" then ToolAuthorization.Denied("host denied reset")
                  else ToolAuthorization.Approved(request.call.input)
                )
            )
          )
          result     <- agent.run(Msg.user("invalid resets"))
          observed   <- requests.get
          ran        <- executed.get
          authorized <- approved.get
        yield assertTrue(
          observed.map(_.tools.map(_.name)) == Chunk(
            Chunk("a", "external", reset),
            Chunk("a", "external", reset),
            Chunk("external", reset)
          ),
          result.history.flatMap(_.toolResults).map(_.state) ==
            Chunk.fill(invalid.size)(ToolResultState.Error) ++ Chunk(
              ToolResultState.Denied,
              ToolResultState.Denied,
              ToolResultState.Success,
              ToolResultState.Success
            ),
          authorized.contains("denied"),
          ran.map(_._1) == Chunk("external"),
          observed.forall(
            _.tools
              .find(_.name == reset)
              .flatMap(_.parametersSchema.asObject)
              .flatMap(_.get("properties"))
              .flatMap(_.asObject)
              .flatMap(_.get("to_activate"))
              .flatMap(_.asObject)
              .flatMap(_.get("items"))
              .flatMap(_.asObject)
              .flatMap(_.get("enum"))
              .contains(Json.Arr(Json.Str("a"), Json.Str("b")))
          )
        )
    },
    test(
      "ordinary and streamed invocations reset host defaults after completion or joined cancellation"
    ) {
      ZIO.scoped:
        for
          defaults <- groups
          executed <- Ref.make(Chunk.empty[(String, Json)])
          requests <- Ref.make(Chunk.empty[Request])
          entered  <- Promise.make[Nothing, Unit]
          cleaned  <- Ref.make(false)
          base     <- toolkit(executed)
          model = recording(requests)((index, _) =>
            index match
              case 0 | 2 => ZIO.succeed(calls(call(s"activate-$index", reset, activate("b"))))
              case 3     => (entered.succeed(()) *> ZIO.never).ensuring(cleaned.set(true))
              case _     => ZIO.succeed(finish)
          )
          agent     <- ReActAgent.builder().client(model).toolkit(base).toolGroups(defaults).build
          first     <- agent.run(Msg.user("first"))
          running   <- agent.run(Msg.user("cancelled")).forkScoped
          _         <- entered.await
          cancelled <- running.interrupt
          joined    <- cleaned.get
          streamed  <- agent.streamWithResult(Msg.user("third")).runCollect
          observed  <- requests.get
        yield assertTrue(
          first.state == AgentRunState.Completed,
          cancelled.causeOption.exists(_.isInterrupted),
          joined,
          streamed.collect { case AgentRunUpdate.Result(value) => value.state } == Chunk(
            AgentRunState.Completed
          ),
          observed.map(_.tools.map(_.name)) == Chunk(
            Chunk("a", "external", reset),
            Chunk("b", "external", reset),
            Chunk("a", "external", reset),
            Chunk("b", "external", reset),
            Chunk("a", "external", reset)
          )
        )
    },
    test(
      "native fallback retains successful activation and synthetic controls survive later deactivation"
    ) {
      ZIO.scoped:
        for
          defaults <- groups
          executed <- Ref.make(Chunk.empty[(String, Json)])
          requests <- Ref.make(Chunk.empty[Request])
          base     <- toolkit(executed)
          model = recording(requests)((index, _) =>
            index match
              case 0 => ZIO.succeed(calls(call("activate", reset, activate("b"))))
              case 1 => ZIO.fail(AgentError.ModelError(400, "native unsupported", Some("fixture")))
              case 2 => ZIO.succeed(calls(call("clear", reset, activate())))
              case _ =>
                ZIO.succeed(calls(call("capture", capture, Json.Obj("response" -> Json.Num(7)))))
          )
          agent <- ReActAgent.make(
            client = model,
            toolkit = base,
            toolGroups = Some(defaults),
            llmConfig = LlmConfig(
              "model",
              structuredOutputCapability =
                StructuredOutputCapability.Native(NativeToolSupport.Supported)
            )
          )
          result   <- agent.runStructured[Int](Chunk(Msg.user("number")), schema)
          observed <- requests.get
        yield assertTrue(
          result._2 == 7,
          observed.map(_.config.responseFormat.isDefined) == Chunk(true, true, false, false),
          observed.map(_.tools.map(_.name)) == Chunk(
            Chunk("a", "external", reset),
            Chunk("b", "external", reset),
            Chunk("b", "external", capture, reset),
            Chunk("external", capture, reset)
          )
        )
    },
    test("empty-host Auto counts reset and hidden base collisions fail before model dispatch") {
      ZIO.scoped:
        for
          meta <- ZIO.fromEither(
            ToolGroups.empty.register(
              ToolGroup(
                "meta",
                "Controls cannot be hidden",
                ToolGroupActivation.Inactive,
                tools = Set(reset, capture)
              )
            )
          )
          observed <- Ref.make(Chunk.empty[Request])
          model = recording(observed)((_, _) =>
            ZIO.succeed(calls(call("capture", capture, Json.Obj("response" -> Json.Num(8)))))
          )
          agent <- ReActAgent.make(
            client = model,
            toolGroups = Some(meta),
            llmConfig = LlmConfig(
              "model",
              structuredOutputCapability =
                StructuredOutputCapability.Native(NativeToolSupport.Unsupported)
            )
          )
          result   <- agent.runStructured[Int](Chunk(Msg.user("number")), schema)
          requests <- observed.get
          collisions <- ZIO.foreach(Chunk(reset, capture))(name =>
            for
              attempts <- Ref.make(Chunk.empty[Request])
              base <- ZIO.fromEither(
                Toolkit(
                  Tool.make(ToolDef(name, "Hidden collision", Json.Obj()))(_ =>
                    ZIO.succeed("unused")
                  )
                )
              )
              collided <- ReActAgent.make(
                client = recording(attempts)((_, _) => ZIO.succeed(finish)),
                toolkit = base,
                toolGroups = Some(meta)
              )
              exit <- collided
                .runStructured[Int](
                  Chunk(Msg.user("collision")),
                  schema,
                  StructuredMode.SyntheticTool
                )
                .exit
              sent <- attempts.get
            yield exit.causeOption
              .flatMap(_.failureOption)
              .contains(
                AgentError
                  .ToolExecutionError(name, s"Tool collision: '$name' is already registered")
              ) && sent.isEmpty
          )
          external <- ZIO.fromEither(
            ToolGroups.empty.register(
              ToolGroup("external", "Only host selection", scope = ToolGroupScope.External)
            )
          )
          externalRequests <- Ref.make(Chunk.empty[Request])
          externalAgent <- ReActAgent.make(
            client = recording(externalRequests)((_, _) => ZIO.succeed(finish)),
            toolGroups = Some(external)
          )
          _            <- externalAgent.run(Msg.user("no reset"))
          withoutReset <- externalRequests.get
        yield assertTrue(
          result._2 == 8,
          requests.map(_.tools.map(_.name)) == Chunk(Chunk(capture, reset)),
          requests.forall(_.config.responseFormat.isEmpty),
          collisions.forall(identity),
          withoutReset.forall(_.tools.isEmpty)
        )
    }
  ) @@ TestAspect.timeout(20.seconds)

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
import com.weisgnosis.agents.core.model.{ContentBlock, MediaSource, ToolResultState}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/**
 * Selected upstream scenario suite for ToolExecutionTest.java from AgentScope Java.
 */
object ToolExecutionSpec extends ZIOSpecDefault:

  final case class AddInput(a: Int, b: Int) derives JsonCodec

  private val addTool: Tool[Any, AddInput, Int] =
    Tool.fromFunction[AddInput, Int]("add", "Add two integers") { in =>
      ZIO.succeed(in.a + in.b)
    }

  private val failingTool: Tool[Any, Json, String] =
    Tool.make(ToolDef("fail", "Always fails", Json.Obj())) { _ =>
      ZIO.fail(new RuntimeException("Simulated tool crash"))
    }

  private val toolkit = Toolkit(
    Map(addTool.definition.name -> addTool, failingTool.definition.name -> failingTool)
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ToolExecutionSpec")(
    test(
      "typed tool projections retain payloads and codecs while reserving runtime lifecycle states"
    ) {
      val payload = ToolResultPayload(
        Chunk(
          ContentBlock.Text("first"),
          ContentBlock.Image(MediaSource.Url("https://example.com/image")),
          ContentBlock.Text("last")
        ),
        Map("source" -> Json.Str("tool"))
      )
      ZIO
        .foreach(Chunk.fromArray(ToolResultState.values)): state =>
          val projected = ToolExecutionResult(payload, state)
          val typed = new Tool[Any, Json, ToolExecutionResult]:
            def definition: ToolDef = ToolDef("typed", "Typed observations", Json.Obj())
            def decodeInput(raw: Json): Either[String, Json]   = Right(raw)
            def execute(input: Json): UIO[ToolExecutionResult] = ZIO.succeed(projected)
            def encodeOutput(out: ToolExecutionResult): String = out.output
            override def encodeResult(out: ToolExecutionResult): ToolExecutionResult = out
          Toolkit(Map("typed" -> typed))
            .executeSafe("typed", Json.Obj())
            .map: result =>
              val expected = state match
                case ToolResultState.Success | ToolResultState.Error => result == projected
                case _ =>
                  result.state == ToolResultState.Error && result.output.contains(state.toString)
              assertTrue(
                expected,
                projected.output == "first\nlast",
                projected.toJson.fromJson[ToolExecutionResult] == Right(projected)
              )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    },
    test("a tool defect becomes an error observation") {
      val defective = Tool.make(ToolDef("defect", "Defective tool", Json.Obj()))(_ =>
        ZIO.die(AgentError.ToolExecutionError("defect", "internal failure"))
      )
      val toolkit = Toolkit(Map("defect" -> defective))
      for result <- toolkit.executeSafe("defect", Json.Obj())
      yield assertTrue(
        result.state == ToolResultState.Error,
        result.output.contains("internal failure")
      )
    },
    test("Successful tool execution with typed argument decoding") {
      val validArgs = Json.Obj("a" -> Json.Num(15), "b" -> Json.Num(27))

      for res <- toolkit.executeSafe("add", validArgs)
      yield assertTrue(
        res.state == ToolResultState.Success,
        res.output == "42"
      )
    },
    test("Malformed arguments yield ToolResultState.Error without throwing") {
      val invalidArgs = Json.Obj("a" -> Json.Str("not_a_number"), "b" -> Json.Num(27))

      for res <- toolkit.executeSafe("add", invalidArgs)
      yield assertTrue(
        res.state == ToolResultState.Error,
        res.output.contains("Invalid arguments for 'add'")
      )
    },
    test("Missing tool returns ToolResultState.Error") {
      for res <- toolkit.executeSafe("nonexistent_tool", Json.Obj())
      yield assertTrue(
        res.state == ToolResultState.Error,
        res.output.contains("not found in toolkit")
      )
    },
    test("Tool execution failure returns ToolResultState.Error with cause") {
      for res <- toolkit.executeSafe("fail", Json.Obj())
      yield assertTrue(
        res.state == ToolResultState.Error,
        res.output.contains("Simulated tool crash")
      )
    },
    test("interruption joins tool cleanup and preserves its defect") {
      for
        entered <- Promise.make[Nothing, Unit]
        cleanup <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        defect = new IllegalStateException("tool cleanup failed")
        slowTool = Tool.make(ToolDef("slow", "Takes forever", Json.Obj())) { _ =>
          (entered.succeed(()) *> ZIO.never)
            .ensuring(cleanup.succeed(()).unit *> release.await *> ZIO.die(defect))
        }
        slowTk = Toolkit(Map("slow" -> slowTool))
        fiber        <- slowTk.executeSafe("slow", Json.Obj()).forkScoped
        _            <- entered.await
        cancelling   <- fiber.interrupt.forkScoped
        _            <- cleanup.await
        pending      <- cancelling.poll
        _            <- release.succeed(())
        exit         <- cancelling.join
        childEntered <- Promise.make[Nothing, Unit]
        innerTool = Tool.make(ToolDef("inner", "Joins cancelled child", Json.Obj())) { _ =>
          ZIO.scoped:
            for
              child <- (childEntered.succeed(()) *> ZIO.never).ensuring(ZIO.die(defect)).forkScoped
              _     <- childEntered.await
              _     <- child.interrupt
              value <- child.join
            yield value
        }
        inner <- Toolkit(Map("inner" -> innerTool)).executeSafe("inner", Json.Obj()).exit
        typedFailure = AgentError.ToolExecutionError("mixed", "failure during interruption")
        mixedTool = Tool.make(ToolDef("mixed", "Compound cancellation cause", Json.Obj()))(_ =>
          ZIO.fiberId.flatMap(id =>
            ZIO.refailCause(Cause.fail(typedFailure) ++ Cause.interrupt(id))
          )
        )
        mixed <- Toolkit(Map("mixed" -> mixedTool)).executeSafe("mixed", Json.Obj()).exit
      yield assertTrue(
        pending.isEmpty,
        exit.isInterrupted,
        exit.causeOption.exists(_.defects.contains(defect)),
        inner.isInterrupted,
        inner.causeOption.exists(_.defects.contains(defect)),
        mixed.isInterrupted,
        mixed.causeOption.exists(_.defects.contains(typedFailure))
      )
    }
  )

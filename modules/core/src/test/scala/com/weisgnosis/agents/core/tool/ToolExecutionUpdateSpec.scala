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
import com.weisgnosis.agents.core.model.{ContentBlock, ToolResultState}
import zio.*
import zio.json.ast.Json
import zio.stream.{ZChannel, ZStream}
import zio.test.*

object ToolExecutionUpdateSpec extends ZIOSpecDefault:
  private val progress: ToolResultPayload = ToolResultPayload(
    Chunk(ContentBlock.Text("working")),
    Map("phase" -> Json.Str("first"))
  )

  private def streaming(
      updates: => ZStream[Any, Throwable, ToolExecutionUpdate[String]]
  ): Tool[Any, Json, String] = new Tool[Any, Json, String]:
    def definition: ToolDef = ToolDef("stream", "Stream updates", Json.Obj())
    def decodeInput(raw: Json): Either[String, Json] = Right(raw)
    def execute(input: Json): Task[String] =
      ZIO.fail(AgentError.InvalidConfiguration("Unexpected legacy execution"))
    override def streamWithContext(
        input: Json,
        invocation: ToolInvocation
    ): ZStream[Any, Throwable, ToolExecutionUpdate[String]] = updates
    def encodeOutput(out: String): String = out

  private def toolkit(tool: Tool[Any, ?, ?]): Toolkit = Toolkit(Map("stream" -> tool))

  def spec: Spec[TestEnvironment, Any] = suite("ToolExecutionUpdateSpec")(
    test(
      "legacy delegation runs once; fixed wrappers retain typed streams and joined final encoding"
    ) {
      for
        executions <- Ref.make(0)
        legacy = Tool.make(ToolDef("legacy", "Legacy", Json.Obj()))(_ =>
          executions.update(_ + 1).as("legacy result")
        )
        legacyResult <- Toolkit(Map("legacy" -> legacy)).executeSafe("legacy", Json.Obj())
        count        <- executions.get
        closed       <- Promise.make[Nothing, Unit]
        seen         <- Ref.make(Chunk.empty[ToolResultPayload])
        published    <- Promise.make[Nothing, Unit]
        entered      <- Promise.make[Nothing, Unit]
        release      <- Promise.make[Nothing, Unit]
        inputSeen    <- Ref.make(Option.empty[Json])
        tool = new Tool[Any, Json, Int]:
          def definition: ToolDef          = ToolDef("stream", "Typed stream", Json.Obj())
          override def isReadOnly: Boolean = true
          def decodeInput(raw: Json): Either[String, Json] = Right(raw)
          def execute(input: Json): Task[Int] =
            ZIO.fail(AgentError.InvalidConfiguration("Legacy path must not run"))
          override def streamWithContext(
              input: Json,
              invocation: ToolInvocation
          ): ZStream[Any, Throwable, ToolExecutionUpdate[Int]] =
            ZStream
              .acquireReleaseWith(entered.succeed(()).unit)(_ => closed.succeed(()).unit)
              .flatMap: _ =>
                ZStream.fromZIO(inputSeen.set(Some(input))) *>
                  (ZStream.succeed(ToolExecutionUpdate.Progress(progress)) ++
                    ZStream.fromZIO(release.await).as(ToolExecutionUpdate.Completed(42)))
          def encodeOutput(out: Int): String = out.toString
          override def encodeResult(out: Int): ToolExecutionResult =
            ToolExecutionResult(
              ToolResultPayload(
                Chunk(ContentBlock.Text(out.toString)),
                Map("final" -> Json.Bool(true))
              ),
              ToolResultState.Success
            )
        fixed   <- ZIO.fromEither(ToolFixedArguments.make(Json.Obj("tenant" -> Json.Str("fixed"))))
        wrapped <- ZIO.fromEither(Tool.withFixedArguments(tool, fixed))
        observed <- ZIO.scoped:
          (for
            fiber <- toolkit(wrapped)
              .executeObserved("stream", Json.Obj(), ToolInvocation.Detached)(payload =>
                seen.update(_ :+ payload) *> published.succeed(()).unit
              )
              .forkScoped
            _        <- entered.await
            _        <- published.await
            before   <- fiber.poll
            _        <- release.succeed(())
            result   <- fiber.join
            released <- closed.isDone
          yield (before, result, released)).ensuring(release.succeed(()).unit)
        input  <- inputSeen.get
        chunks <- seen.get
      yield assertTrue(
        legacyResult == ToolExecutionResult("legacy result"),
        count == 1,
        observed._1.isEmpty,
        observed._3,
        observed._2.result.output == "42",
        observed._2.result.payload.metadata == Map("final" -> Json.Bool(true)),
        observed._2.progress == Toolkit.ProgressDisposition.ProgressPublished,
        chunks == Chunk(progress),
        input.contains(Json.Obj("tenant" -> Json.Str("fixed"))),
        wrapped.isReadOnly
      )
    },
    test(
      "completion is unique and last; failures retain progress while metadata-only updates do not publish"
    ) {
      val complete     = ToolExecutionUpdate.Completed("final")
      val metadataOnly = ToolResultPayload(Chunk.empty, Map("phase" -> Json.Str("quiet")))
      val invalid = Chunk(
        Chunk.empty[ToolExecutionUpdate[String]],
        Chunk(ToolExecutionUpdate.Progress(progress)),
        Chunk(complete, complete),
        Chunk(complete, ToolExecutionUpdate.Progress(progress))
      )
      for
        rejected <- ZIO.foreach(invalid)(updates =>
          toolkit(streaming(ZStream.fromIterable(updates))).executeSafe("stream", Json.Obj())
        )
        seen <- Ref.make(Chunk.empty[ToolResultPayload])
        quiet <- toolkit(streaming(ZStream(ToolExecutionUpdate.Progress(metadataOnly), complete)))
          .executeObserved("stream", Json.Obj(), ToolInvocation.Detached)(payload =>
            seen.update(_ :+ payload)
          )
        quietSeen <- seen.get
        failed <- toolkit(
          streaming(
            ZStream.succeed(ToolExecutionUpdate.Progress(progress)) ++
              ZStream.fromChannel(
                ZChannel.refailCause(Cause.fail(new IllegalStateException("after progress")))
              )
          )
        ).executeObserved("stream", Json.Obj(), ToolInvocation.Detached)(payload =>
          seen.update(_ :+ payload)
        )
        cleanup <- toolkit(
          streaming(
            ZStream.succeed(complete).ensuring(ZIO.die(new IllegalStateException("cleanup failed")))
          )
        ).executeSafe("stream", Json.Obj())
        chunks <- seen.get
      yield assertTrue(
        rejected.forall(_.state == ToolResultState.Error),
        rejected.size == invalid.size,
        quiet.result == ToolExecutionResult("final"),
        quiet.progress == Toolkit.ProgressDisposition.NoProgress,
        quietSeen.isEmpty,
        chunks == Chunk(progress),
        failed.result.state == ToolResultState.Error,
        failed.result.output.contains("after progress"),
        failed.progress == Toolkit.ProgressDisposition.ProgressPublished,
        cleanup.state == ToolResultState.Error,
        cleanup.output.contains("cleanup failed")
      )
    },
    test("legacy mixed causes remain intact and stream cancellation joins defecting cleanup") {
      val failure = new IllegalStateException("typed failure")
      val defect  = new IllegalStateException("stream defect")
      val mixed   = Cause.fail(failure) ++ Cause.die(defect) ++ Cause.interrupt(FiberId.None)
      val legacy =
        Tool.make(ToolDef("legacy", "Mixed cause", Json.Obj()))(_ => ZIO.refailCause(mixed))
      for
        legacyExit <- Toolkit(Map("legacy" -> legacy)).executeSafe("legacy", Json.Obj()).exit
        streamExit <- toolkit(streaming(ZStream.fromChannel(ZChannel.refailCause(mixed))))
          .executeSafe("stream", Json.Obj())
          .exit
        entered      <- Promise.make[Nothing, Unit]
        cleaning     <- Promise.make[Nothing, Unit]
        release      <- Promise.make[Nothing, Unit]
        cleanupCount <- Ref.make(0)
        tool = streaming(
          ZStream
            .acquireReleaseWith(entered.succeed(()).unit)(_ =>
              cleanupCount.update(_ + 1) *> cleaning.succeed(()) *> release.await *> ZIO.die(defect)
            )
            .flatMap(_ => ZStream.never)
        )
        cancelled <- ZIO.scoped:
          (for
            fiber     <- toolkit(tool).executeSafe("stream", Json.Obj()).forkScoped
            _         <- entered.await
            interrupt <- fiber.interrupt.forkScoped
            _         <- cleaning.await
            pending   <- interrupt.poll
            _         <- release.succeed(())
            exit      <- interrupt.join
          yield (pending, exit)).ensuring(release.succeed(()).unit)
        count <- cleanupCount.get
      yield assertTrue(
        legacyExit.causeOption.contains(mixed.flatMap(error => Cause.die(error))),
        streamExit.causeOption.contains(mixed.flatMap(error => Cause.die(error))),
        cancelled._1.isEmpty,
        cancelled._2.causeOption.exists(cause =>
          cause.isInterrupted && cause.defects == List(defect)
        ),
        count == 1
      )
    }
  ) @@ TestAspect.timeout(15.seconds)

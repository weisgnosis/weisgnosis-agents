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
import zio.test.*

object ToolExecutionUpdateSpec extends ZIOSpecDefault:
  private enum AcquisitionConsumer:
    case DirectEffect, Toolkit

  private enum TerminalOutcome:
    case Success, Failure, Cancelled

  private val progress: ToolResultPayload = ToolResultPayload(
    Chunk(ContentBlock.Text("working")),
    Map("phase" -> Json.Str("first"))
  )

  private def scopedTool(
      run: (ToolResultPayload => UIO[Unit]) => ZIO[Scope, Throwable, String]
  ): Tool[Any, Json, String] = new Tool[Any, Json, String]:
    def definition: ToolDef = ToolDef("scoped", "Publish progress", Json.Obj())
    def decodeInput(raw: Json): Either[String, Json] = Right(raw)
    def execute(input: Json): Task[String] =
      ZIO.fail(AgentError.InvalidConfiguration("Unexpected legacy execution"))
    override def executeScoped(
        input: Json,
        invocation: ToolInvocation
    )(publish: ToolResultPayload => UIO[Unit]): ZIO[Scope, Throwable, String] = run(publish)
    def encodeOutput(out: String): String = out

  private def toolkit(tool: Tool[Any, ?, ?]): Toolkit = Toolkit.single(tool)

  def spec: Spec[TestEnvironment, Any] = suite("ToolExecutionUpdateSpec")(
    test(
      "legacy delegation runs once; fixed wrappers retain typed scoped execution and joined final encoding"
    ) {
      for
        executions <- Ref.make(0)
        legacy = Tool.make(ToolDef("legacy", "Legacy", Json.Obj()))(_ =>
          executions.update(_ + 1).as("legacy result")
        )
        legacyResult <- Toolkit.single(legacy).executeSafe("legacy", Json.Obj())
        count        <- executions.get
        closed       <- Promise.make[Nothing, Unit]
        seen         <- Ref.make(Chunk.empty[ToolResultPayload])
        published    <- Promise.make[Nothing, Unit]
        entered      <- Promise.make[Nothing, Unit]
        release      <- Promise.make[Nothing, Unit]
        inputSeen    <- Ref.make(Option.empty[Json])
        tool = new Tool[Any, Json, Int]:
          def definition: ToolDef          = ToolDef("scoped", "Typed scoped execution", Json.Obj())
          override def isReadOnly: Boolean = true
          def decodeInput(raw: Json): Either[String, Json] = Right(raw)
          def execute(input: Json): Task[Int] =
            ZIO.fail(AgentError.InvalidConfiguration("Legacy path must not run"))
          override def executeScoped(
              input: Json,
              invocation: ToolInvocation
          )(publish: ToolResultPayload => UIO[Unit]): ZIO[Scope, Throwable, Int] =
            ZIO.acquireRelease(entered.succeed(()).unit)(_ => closed.succeed(()).unit) *>
              inputSeen.set(Some(input)) *> publish(progress) *> release.await.as(42)
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
              .executeObserved("scoped", Json.Obj(), ToolInvocation.Detached)(payload =>
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
      "typed outcomes retain progress on failure and reject cleanup failure; metadata-only updates do not publish"
    ) {
      val metadataOnly = ToolResultPayload(Chunk.empty, Map("phase" -> Json.Str("quiet")))
      for
        seen <- Ref.make(Chunk.empty[ToolResultPayload])
        quiet <- toolkit(scopedTool(publish => publish(metadataOnly).as("final")))
          .executeObserved("scoped", Json.Obj(), ToolInvocation.Detached)(payload =>
            seen.update(_ :+ payload)
          )
        quietSeen <- seen.get
        failed <- toolkit(
          scopedTool(publish =>
            publish(progress) *> ZIO.fail(new IllegalStateException("after progress"))
          )
        ).executeObserved("scoped", Json.Obj(), ToolInvocation.Detached)(payload =>
          seen.update(_ :+ payload)
        )
        cleanup <- toolkit(
          scopedTool(_ =>
            ZIO
              .acquireRelease(ZIO.unit)(_ => ZIO.die(new IllegalStateException("cleanup failed")))
              .as("final")
          )
        ).executeSafe("scoped", Json.Obj())
        chunks <- seen.get
      yield assertTrue(
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
    test(
      "legacy and scoped mixed causes remain intact; body cancellation joins defecting cleanup"
    ) {
      val failure = new IllegalStateException("typed failure")
      val defect  = new IllegalStateException("scoped defect")
      val mixed   = Cause.fail(failure) ++ Cause.die(defect) ++ Cause.interrupt(FiberId.None)
      val legacy =
        Tool.make(ToolDef("legacy", "Mixed cause", Json.Obj()))(_ => ZIO.refailCause(mixed))
      for
        legacyExit <- Toolkit.single(legacy).executeSafe("legacy", Json.Obj()).exit
        scopedExit <- toolkit(scopedTool(_ => ZIO.refailCause(mixed)))
          .executeSafe("scoped", Json.Obj())
          .exit
        entered      <- Promise.make[Nothing, Unit]
        cleaning     <- Promise.make[Nothing, Unit]
        release      <- Promise.make[Nothing, Unit]
        cleanupCount <- Ref.make(0)
        tool = scopedTool(_ =>
          ZIO.acquireRelease(ZIO.unit)(_ =>
            cleanupCount.update(_ + 1) *> cleaning.succeed(()) *> release.await *> ZIO.die(defect)
          ) *> entered.succeed(()) *> ZIO.never
        )
        cancelled <- ZIO.scoped:
          (for
            fiber     <- toolkit(tool).executeSafe("scoped", Json.Obj()).forkScoped
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
        scopedExit.causeOption.contains(mixed.flatMap(error => Cause.die(error))),
        cancelled._1.isEmpty,
        cancelled._2.causeOption.exists(cause =>
          cause.isInterrupted && cause.defects == List(defect)
        ),
        count == 1
      )
    },
    suite("scoped acquisition cancellation preserves its joined cleanup defect")(
      AcquisitionConsumer.values.toList.map { consumer =>
        test(consumer.toString) {
          val defect = new IllegalStateException("scoped acquisition cleanup defect")
          for
            entered        <- Promise.make[Nothing, Unit]
            releaseAcquire <- Promise.make[Nothing, Unit]
            cleaning       <- Promise.make[Nothing, Unit]
            releaseCleanup <- Promise.make[Nothing, Unit]
            cleanupExit    <- Promise.make[Nothing, Exit[Nothing, Nothing]]
            cleanupCount   <- Ref.make(0)
            tool = scopedTool(_ =>
              ZIO.acquireRelease(entered.succeed(()) *> releaseAcquire.await)(_ =>
                (cleanupCount.update(_ + 1) *> cleaning.succeed(()) *> releaseCleanup.await *>
                  ZIO.die(defect)).onExit(exit => cleanupExit.succeed(exit).unit)
              ) *> ZIO.never
            )
            execution = consumer match
              case AcquisitionConsumer.DirectEffect =>
                ZIO
                  .scoped(tool.executeScoped(Json.Obj(), ToolInvocation.Detached)(_ => ZIO.unit))
                  .unit
              case AcquisitionConsumer.Toolkit =>
                toolkit(tool).executeSafe("scoped", Json.Obj()).unit
            cancelled <- ZIO.scoped:
              (for
                fiber    <- execution.forkScoped
                _        <- entered.await
                stopping <- fiber.interrupt.forkScoped
                _        <- TestClock.adjust(0.seconds)
                _        <- releaseAcquire.succeed(())
                _        <- cleaning.await
                pending  <- stopping.poll
                _        <- releaseCleanup.succeed(())
                exit     <- stopping.join
                released <- cleanupExit.await
              yield (pending, exit, released)).ensuring(
                releaseAcquire.succeed(()) *> releaseCleanup.succeed(()).unit
              )
            count <- cleanupCount.get
          yield assertTrue(
            cancelled._1.isEmpty,
            cancelled._2.causeOption.exists(cause =>
              cause.isInterrupted && cause.defects == List(defect)
            ),
            cancelled._3.causeOption.exists(_.defects == List(defect)),
            count == 1
          )
        }
      }*
    ),
    suite("retained progress publication is revoked after scope closure")(
      TerminalOutcome.values.toList.map { outcome =>
        test(outcome.toString) {
          for
            retained <- Promise.make[Nothing, ToolResultPayload => UIO[Unit]]
            closed   <- Ref.make(0)
            seen     <- Ref.make(Chunk.empty[ToolResultPayload])
            tool = scopedTool(publish =>
              ZIO.acquireRelease(ZIO.unit)(_ => closed.update(_ + 1)) *>
                retained.succeed(publish) *> (outcome match
                  case TerminalOutcome.Success => ZIO.succeed("final")
                  case TerminalOutcome.Failure =>
                    ZIO.fail(new IllegalStateException("execution failed"))
                  case TerminalOutcome.Cancelled => ZIO.never)
            )
            terminal <- ZIO.scoped:
              for
                fiber <- toolkit(tool)
                  .executeObserved("scoped", Json.Obj(), ToolInvocation.Detached)(payload =>
                    seen.update(_ :+ payload)
                  )
                  .forkScoped
                publish <- retained.await
                exit <- outcome match
                  case TerminalOutcome.Cancelled => fiber.interrupt
                  case _                         => fiber.await
              yield (publish, exit)
            _      <- terminal._1(progress)
            chunks <- seen.get
            count  <- closed.get
            valid = outcome match
              case TerminalOutcome.Success =>
                terminal._2.exists(result =>
                  result.result == ToolExecutionResult(
                    "final"
                  ) && result.progress == Toolkit.ProgressDisposition.NoProgress
                )
              case TerminalOutcome.Failure =>
                terminal._2.exists(result =>
                  result.result.state == ToolResultState.Error && result.result.output.contains(
                    "execution failed"
                  ) &&
                    result.progress == Toolkit.ProgressDisposition.NoProgress
                )
              case TerminalOutcome.Cancelled => terminal._2.isInterrupted
          yield assertTrue(valid, chunks.isEmpty, count == 1)
        }
      }*
    ),
    test("cancellation interrupts queued progress and joins the scoped publisher before cleanup") {
      val queued = ToolResultPayload(Chunk(ContentBlock.Text("queued")))
      for
        retained       <- Promise.make[Nothing, ToolResultPayload => UIO[Unit]]
        observing      <- Promise.make[Nothing, Unit]
        waiting        <- Promise.make[Nothing, Unit]
        releaseObserve <- Promise.make[Nothing, Unit]
        childExit      <- Promise.make[Nothing, Exit[Nothing, Unit]]
        closed         <- Ref.make(0)
        seen           <- Ref.make(Chunk.empty[ToolResultPayload])
        tool = scopedTool(publish =>
          ZIO.acquireRelease(ZIO.unit)(_ => closed.update(_ + 1)) *>
            retained.succeed(publish) *>
            publish(progress).onExit(exit => childExit.succeed(exit).unit).forkScoped *>
            observing.await *> waiting.succeed(()) *> publish(queued).as("final")
        )
        cancelled <- ZIO.scoped:
          (for
            fiber <- toolkit(tool)
              .executeObserved("scoped", Json.Obj(), ToolInvocation.Detached)(payload =>
                seen.update(_ :+ payload) *> observing.succeed(()) *> releaseObserve.await
              )
              .forkScoped
            publish <- retained.await
            _       <- waiting.await
            _       <- TestClock.adjust(0.seconds)
            before  <- fiber.poll
            exit    <- fiber.interrupt
            child   <- childExit.await
          yield (publish, before, exit, child)).ensuring(releaseObserve.succeed(()).unit)
        _      <- cancelled._1(queued)
        chunks <- seen.get
        count  <- closed.get
      yield assertTrue(
        cancelled._2.isEmpty,
        cancelled._3.isInterrupted,
        cancelled._4.isInterrupted,
        chunks == Chunk(progress),
        count == 1
      )
    },
    test(
      "cancellation during progress observation joins cleanup and revokes the admitted publisher"
    ) {
      for
        retained       <- Promise.make[Nothing, ToolResultPayload => UIO[Unit]]
        observing      <- Promise.make[Nothing, Unit]
        releaseObserve <- Promise.make[Nothing, Unit]
        observerExit   <- Promise.make[Nothing, Exit[Nothing, Unit]]
        cleaning       <- Promise.make[Nothing, Unit]
        releaseCleanup <- Promise.make[Nothing, Unit]
        count          <- Ref.make(0)
        seen           <- Ref.make(Chunk.empty[ToolResultPayload])
        tool = scopedTool(publish =>
          ZIO.acquireRelease(ZIO.unit)(_ =>
            count.update(_ + 1) *> cleaning.succeed(()) *> releaseCleanup.await
          ) *> retained.succeed(publish) *> publish(progress).as("final")
        )
        cancelled <- ZIO.scoped:
          (for
            fiber <- toolkit(tool)
              .executeObserved("scoped", Json.Obj(), ToolInvocation.Detached)(payload =>
                (seen.update(_ :+ payload) *> observing.succeed(()) *> releaseObserve.await)
                  .onExit(exit => observerExit.succeed(exit).unit)
              )
              .forkScoped
            publish  <- retained.await
            _        <- observing.await
            stopping <- fiber.interrupt.forkScoped
            _        <- cleaning.await
            pending  <- stopping.poll
            _        <- releaseCleanup.succeed(())
            exit     <- stopping.join
            observed <- observerExit.await
          yield (publish, pending, exit, observed)).ensuring(
            releaseObserve.succeed(()) *> releaseCleanup.succeed(()).unit
          )
        _      <- cancelled._1(progress)
        chunks <- seen.get
        closed <- count.get
      yield assertTrue(
        cancelled._2.isEmpty,
        cancelled._3.isInterrupted,
        cancelled._4.isInterrupted,
        chunks == Chunk(progress),
        closed == 1
      )
    }
  ) @@ TestAspect.timeout(15.seconds)

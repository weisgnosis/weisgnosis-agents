/*
 * Copyright 2024-2026 WeisGnosis (https://weisgnosis.com)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.weisgnosis.agents.examples

import com.weisgnosis.agents.core.agent.AgentRunState
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import zio.*
import zio.json.ast.Json
import zio.test.*

object LiveFileTaskSpec extends ZIOSpecDefault:
  private def io[A](operation: => A): IO[AgentError, A] =
    ZIO.attemptBlocking(operation).mapError(error => AgentError.StorageError(error.toString))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("LiveFileTaskSpec")(
    test(
      "actual ReAct file tools write exact bytes and the next model request consumes the physical read before cleanup"
    ):
      for
        requests <- Ref.make(Chunk.empty[Chunk[Msg]])
        client = LlmClient.mockEffectful((history, _, _) =>
          requests
            .updateAndGet(_ :+ history)
            .map: observed =>
              observed.length match
                case 1 =>
                  TurnOutput.ToolCalls(
                    Msg.assistant(
                      Chunk(
                        ContentBlock.ToolUse(
                          "write",
                          "fixture_write",
                          Json.Obj("text" -> Json.Str(LiveFileTask.ExpectedText))
                        )
                      )
                    )
                  )
                case 2 =>
                  TurnOutput.ToolCalls(
                    Msg.assistant(Chunk(ContentBlock.ToolUse("read", "fixture_read", Json.Obj())))
                  )
                case _ => TurnOutput.FinalText(Msg.assistant("Recipe verified"))
        )
        report   <- LiveFileTask.exercise(client, LlmConfig("offline-fixture"))
        observed <- requests.get
        second = observed.lift(1).toList.flatMap(_.flatMap(_.toolResults))
        third  = observed.lift(2).toList.flatMap(_.flatMap(_.toolResults))
      yield assertTrue(
        observed.length == 3,
        second.exists(result =>
          result.id == "write" && result.name.contains("fixture_write") && result.outputText
            .contains("Recipe written")
        ),
        !second.exists(_.name.contains("fixture_read")),
        third.exists(result =>
          result.id == "read" && result.name.contains(
            "fixture_read"
          ) && result.outputText == LiveFileTask.ExpectedText
        ),
        report.result.state == AgentRunState.Completed,
        report.writes == 1,
        report.reads == 1,
        report.bytes == Chunk.fromArray(LiveFileTask.ExpectedText.getBytes(StandardCharsets.UTF_8)),
        report.directoryRemoved
      )
    ,
    test("owned fixture cleanup joins caller cancellation and preserves typed task failure"):
      ZIO.scoped:
        for
          path       <- Ref.make(Option.empty[Path])
          entered    <- Promise.make[Nothing, Unit]
          cleaning   <- Promise.make[Nothing, Unit]
          release    <- Promise.make[Nothing, Unit]
          controller <- ZIO.fiberId
          running <- ZIO.uninterruptibleMask: restore =>
            for
              fiber <- restore(
                LiveFileTask.withFixture(directory =>
                  path.set(Some(directory)) *> io(
                    Files.write(directory.resolve("recipe.txt"), Array[Byte](1))
                  ).unit *>
                    (entered.succeed(()) *> ZIO.never).ensuring(
                      cleaning.succeed(()) *> release.await
                    )
                )
              ).forkScoped
              _ <- ZIO.addFinalizer(release.succeed(()).unit)
            yield fiber
          _             <- entered.await
          _             <- running.interruptAsFork(controller)
          _             <- cleaning.await
          held          <- running.poll
          _             <- release.succeed(())
          cancelled     <- running.await
          cancelledPath <- path.get
          removed       <- ZIO.foreach(cancelledPath)(directory => io(!Files.exists(directory)))
          failure = AgentError.InvalidConfiguration("File task body failed")
          failed <- LiveFileTask
            .withFixture(directory => path.set(Some(directory)) *> ZIO.fail(failure))
            .either
          failedPath    <- path.get
          failedRemoved <- ZIO.foreach(failedPath)(directory => io(!Files.exists(directory)))
        yield assertTrue(
          held.isEmpty,
          cancelled.causeOption.exists(_.interruptors.contains(controller)),
          removed.contains(true),
          failed == Left(failure),
          failedRemoved.contains(true)
        )
  )

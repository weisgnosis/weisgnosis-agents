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
package com.weisgnosis.agents.core.skill

import com.weisgnosis.agents.core.error.AgentError
import zio.*
import zio.test.*

object SkillLibrarySpec extends ZIOSpecDefault:
  private val definition: SkillDefinition = SkillDefinition(
    "selected",
    "Selected description",
    "Instructions",
    Map("eager.md" -> "Eager text"),
    Set("tools")
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SkillLibrarySpec")(
    test("checked exact capabilities remain cold, selectable and distinct from preloaded text"):
      for
        reads <- Ref.make(0)
        catalog <- ZIO.fromEither(
          SkillCatalog.make(Chunk(definition, definition.copy(id = "other")))
        )
        reader = SkillResourceReader(reads.updateAndGet(_ + 1).map(count => s"Read $count"))
        library <- ZIO.fromEither(
          SkillLibrary.make(
            catalog,
            Map(
              SkillResourceKey("selected", "references/lazy.md") -> reader,
              SkillResourceKey("other", "other.md")              -> reader
            )
          )
        )
        selected <- ZIO.fromEither(library.select(Set("selected")))
        empty    <- ZIO.fromEither(selected.select(Set.empty))
        invalid = Chunk(
          SkillResourceKey("absent", "valid.md"),
          SkillResourceKey("selected", "SKILL.md"),
          SkillResourceKey("selected", "../bad"),
          SkillResourceKey("selected", "/bad"),
          SkillResourceKey("selected", ""),
          SkillResourceKey("selected", "eager.md")
        ).map(key => SkillLibrary.make(catalog, Map(key -> reader)))
        before   <- reads.get
        eager    <- selected.resolve("selected", "eager.md")
        document <- SkillLibrary.from(catalog).resolve("selected", "SKILL.md")
        first    <- selected.resolve("selected", "references/lazy.md")
        second   <- selected.resolve("selected", "references/lazy.md")
        removed  <- selected.resolve("other", "other.md").either
        absent   <- empty.resolve("selected", "references/lazy.md").either
        after    <- reads.get
      yield assertTrue(
        before == 0,
        after == 2,
        invalid.forall(_.isLeft),
        eager.content == "Eager text",
        document.content == "Instructions",
        first == SkillCatalog.Resource("Read 1", Set("tools")),
        second.content == "Read 2",
        removed.isLeft,
        absent.isLeft,
        selected.select(Set("other")).isLeft,
        selected.catalog.definitions == Chunk(definition),
        selected.systemPrompt.contains("references/lazy.md"),
        !selected.systemPrompt.contains("other.md"),
        !selected.systemPrompt.contains("Read 1"),
        empty.systemPrompt.isEmpty,
        SkillLibrary.from(catalog).systemPrompt == catalog.systemPrompt,
        catalog.load("selected", "references/lazy.md").isLeft
      )
    ,
    test(
      "read scopes complete before resolution and retain failure and joined cancellation causes"
    ):
      val failure = AgentError.InvalidConfiguration("read failed")
      val defect  = new IllegalStateException("reader cleanup")
      for
        catalog  <- ZIO.fromEither(SkillCatalog.make(Chunk(definition)))
        closed   <- Ref.make(false)
        started  <- Promise.make[Nothing, Unit]
        cleaning <- Promise.make[Nothing, Unit]
        release  <- Promise.make[Nothing, Unit]
        library <- ZIO.fromEither(
          SkillLibrary.make(
            catalog,
            Map(
              SkillResourceKey("selected", "success") -> SkillResourceReader(
                ZIO.acquireRelease(ZIO.unit)(_ => closed.set(true)).as("Text")
              ),
              SkillResourceKey("selected", "failure") -> SkillResourceReader(
                ZIO.acquireRelease(ZIO.unit)(_ => ZIO.die(defect)) *> ZIO.fail(failure)
              ),
              SkillResourceKey("selected", "cancel") -> SkillResourceReader(
                ZIO.acquireRelease(ZIO.unit)(_ =>
                  cleaning.succeed(()) *> release.await *> ZIO.die(defect)
                ) *>
                  started.succeed(()) *> ZIO.never
              )
            )
          )
        )
        success   <- library.resolve("selected", "success")
        wasClosed <- closed.get
        failed    <- library.resolve("selected", "failure").exit
        result <- (for
          fiber     <- library.resolve("selected", "cancel").forkScoped
          _         <- started.await
          stopping  <- fiber.interrupt.forkScoped
          _         <- cleaning.await
          pending   <- stopping.poll
          _         <- release.succeed(())
          cancelled <- stopping.join
        yield assertTrue(
          success.content == "Text",
          wasClosed,
          failed == Exit.failCause(Cause.fail(failure) ++ Cause.die(defect)),
          pending.isEmpty,
          cancelled.isInterrupted,
          cancelled.causeOption.exists(_.defects == List(defect))
        )).ensuring(release.succeed(()).unit)
      yield result
  ) @@ TestAspect.timeout(20.seconds)

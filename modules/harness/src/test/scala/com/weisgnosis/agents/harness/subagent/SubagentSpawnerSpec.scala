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
package com.weisgnosis.agents.harness.subagent

import com.weisgnosis.agents.core.error.AgentError
import scala.compiletime.testing.typeChecks
import zio.*
import zio.test.*

object SubagentSpawnerSpec extends ZIOSpecDefault:
  private def owner: ZIO[Scope, Nothing, Scope.Closeable] =
    ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))

  private def accepted[A](
      admission: ZIO[Scope, AgentError, TaskAdmission[A]]
  ): ZIO[Scope, AgentError, SupervisedTask[A]] =
    admission.flatMap:
      case TaskAdmission.Accepted(task) => ZIO.succeed(task)
      case TaskAdmission.Closed =>
        ZIO.fail(AgentError.InvalidConfiguration("Unexpected closed supervisor"))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentSpawnerSpec")(
    test("lineage is runtime authority with no public constructor or JSON codec") {
      assertTrue(
        !typeChecks(
          "def create(supervisor: com.weisgnosis.agents.harness.subagent.TaskSupervisor[Unit]): com.weisgnosis.agents.harness.subagent.SubagentSpawner[Unit] = new com.weisgnosis.agents.harness.subagent.SubagentSpawner[Unit](supervisor, zio.Chunk.empty)"
        ),
        !typeChecks(
          "summon[zio.json.JsonCodec[com.weisgnosis.agents.harness.subagent.SubagentSpawner[Unit]]]"
        )
      )
    },
    test("spawn effect construction allocates no identity and evaluates no callback") {
      for
        supervisor <- TaskSupervisor.open[Unit]
        root       <- SubagentSpawner.root(supervisor)
        effects    <- Ref.make(0)
        _          <- TestRandom.feedLongs(1L, 2L)
        expected   <- Random.nextUUID
        _          <- TestRandom.feedLongs(1L, 2L)
        pending = root.spawn(_ => effects.update(_ + 1))
        actual <- Random.nextUUID
        before <- effects.get
        task   <- accepted(pending)
        _      <- task.await
        after  <- effects.get
      yield assertTrue(
        expected == actual,
        before == 0,
        after == 1,
        root.depth == 0,
        !root.source.contains('/')
      )
    },
    test("depth four rejects before identity allocation or callback evaluation") {
      for
        supervisor <- TaskSupervisor.open[Unit]
        root       <- SubagentSpawner.root(supervisor)
        deepest    <- Promise.make[Nothing, SubagentSpawner[Unit]]
        release    <- Promise.make[Nothing, Unit]
        seen       <- Ref.make(Chunk((root.depth, root.source)))
        attempts   <- Ref.make(0)
        descend = (spawner: SubagentSpawner[Unit]) =>
          descendToLimit(spawner, deepest, release, seen)
        task     <- accepted(root.spawn(descend))
        leaf     <- deepest.await
        _        <- TestRandom.feedLongs(11L, 22L)
        expected <- Random.nextUUID
        _        <- TestRandom.feedLongs(11L, 22L)
        rejected <- leaf.spawn(_ => attempts.update(_ + 1)).exit
        actual   <- Random.nextUUID
        count    <- attempts.get
        lineage  <- seen.get
        _        <- release.succeed(())
        result   <- task.await
      yield assertTrue(
        lineage.map(_._1) == Chunk(0, 1, 2, 3),
        lineage
          .zipWith(lineage.drop(1))((parent, child) => child._2.startsWith(parent._2 + "/"))
          .forall(identity),
        rejected.causeOption.exists(_.failures.contains(AgentError.SpawnDepthExceeded(3))),
        actual == expected,
        count == 0,
        result.isSuccess
      )
    },
    test("concurrent siblings inherit one parent with independent identity") {
      for
        supervisor <- TaskSupervisor.open[Unit]
        root       <- SubagentSpawner.root(supervisor)
        seen       <- Ref.make(Chunk.empty[(Int, String)])
        tasks <- ZIO.foreachPar(Chunk.fromIterable(1 to 12))(_ =>
          accepted(root.spawn(child => seen.update(_ :+ (child.depth, child.source))))
        )
        exits    <- ZIO.foreach(tasks)(_.await)
        children <- seen.get
      yield assertTrue(
        exits.forall(_.isSuccess),
        children.size == 12,
        children.map(_._2).toSet.size == 12,
        children.forall(child => child._1 == 1 && child._2.startsWith(root.source + "/"))
      )
    },
    test("closed supervisor admission invokes no descendant callback") {
      for
        host       <- owner
        supervisor <- host.extend(TaskSupervisor.open[Unit])
        root       <- SubagentSpawner.root(supervisor)
        calls      <- Ref.make(0)
        _          <- host.close(Exit.unit)
        result     <- root.spawn(_ => calls.update(_ + 1))
        count      <- calls.get
      yield assertTrue(result == TaskAdmission.Closed, count == 0)
    },
    test("promoted descendant survives parent completion until its supervisor closes") {
      for
        host       <- owner
        supervisor <- host.extend(TaskSupervisor.open[Unit])
        root       <- SubagentSpawner.root(supervisor)
        entered    <- Promise.make[Nothing, Unit]
        published  <- Promise.make[Nothing, (SupervisedTask[Unit], TaskWait[Unit])]
        cleaned    <- Ref.make(0)
        parent <- accepted(root.spawn { child =>
          for
            task <- accepted(
              child.spawn(_ =>
                ZIO.addFinalizer(cleaned.update(_ + 1)) *> entered.succeed(()) *> ZIO.never
              )
            )
            result <- task.awaitOrPromote
            _      <- published.succeed((task, result))
          yield ()
        })
        _          <- entered.await
        _          <- TestClock.adjust(30.seconds)
        promoted   <- published.await
        parentExit <- parent.await
        before     <- cleaned.get
        _          <- host.close(Exit.unit)
        childExit  <- promoted._1.await
        after      <- cleaned.get
      yield assertTrue(
        promoted._2 == TaskWait.Promoted,
        parentExit.isSuccess,
        before == 0,
        childExit.isInterrupted,
        after == 1
      )
    }
  )

  private def descendToLimit(
      spawner: SubagentSpawner[Unit],
      deepest: Promise[Nothing, SubagentSpawner[Unit]],
      release: Promise[Nothing, Unit],
      seen: Ref[Chunk[(Int, String)]]
  ): ZIO[Scope, AgentError, Unit] =
    seen.update(_ :+ (spawner.depth, spawner.source)) *>
      (if spawner.depth == SubagentSpawner.MaximumDepth then
         deepest.succeed(spawner) *> release.await
       else
         accepted(spawner.spawn(child => descendToLimit(child, deepest, release, seen)))
           .flatMap(_.await.flatMap(exit => exit)))

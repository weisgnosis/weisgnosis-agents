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
package com.weisgnosis.agents.storage.postgresql

import com.weisgnosis.agents.harness.memory.{MemoryLifecycleIsolationSpec as Shared, *}
import java.time.Instant
import zio.*
import zio.test.*

object MemoryLifecycleIsolationSpec extends ZIOSpecDefault:
  import PostgresFixture.*

  def spec: Spec[TestEnvironment, Any] = suite("PersistentMemoryLifecycleIsolationSpec")(
    test("independent remote gates retain all eight flush and maintenance middleware windows"):
      fixture: (_, backend, name) =>
        for
          secondSource  <- dataSource(name + "-reopened")
          secondBackend <- store(secondSource)
          selected      <- Shared.policy
          results <- ZIO.foreach(for
            scope <- Chunk(
              MemoryScope.User(Some("user")),
              MemoryScope.Session(Some("session")),
              MemoryScope.Agent,
              MemoryScope.Global
            )
            order <- Chunk.fromIterable(Shared.Order.values)
          yield scope -> order): (scope, order) =>
            ZIO.scoped:
              for
                location     <- address(name, s"repository-$scope-$order")
                gateLocation <- address(name, s"gate-$scope-$order")
                repository   <- ZIO.fromEither(VersionedMemoryRepository.make(backend, location))
                first <- ZIO.fromEither(
                  VersionedMemoryTriggerGate.make(
                    backend,
                    gateLocation,
                    selected,
                    now = ZIO.succeed(Instant.EPOCH)
                  )
                )
                rebuilt <- ZIO.fromEither(
                  VersionedMemoryTriggerGate.make(
                    secondBackend,
                    gateLocation,
                    selected,
                    now = ZIO.succeed(Instant.EPOCH)
                  )
                )
                result   <- Shared.exercise(repository, first, rebuilt, scope, order)
                reopened <- ZIO.fromEither(VersionedMemoryRepository.make(secondBackend, location))
                curated  <- reopened.daily.readCurated
                gateRecord <- secondBackend.read(gateLocation)
              yield result && assertTrue(curated.contains("curated seed"), gateRecord.nonEmpty)
        yield results.foldLeft(assertTrue(true))(_ && _)
  ) @@ TestAspect.sequential @@ TestAspect.withLiveRandom @@ TestAspect.withLiveSystem @@
    TestAspect.withLiveClock @@ TestAspect.timeout(30.seconds) @@
    TestAspect.ifEnvSet("WEISGNOSIS_POSTGRES_URL")

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
package com.weisgnosis.agents.harness.memory

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.Msg
import java.time.LocalDate
import zio.*
import zio.test.*

object MemoryLifecycleIsolationSpec extends ZIOSpecDefault:
  private[agents] enum Order:
    case FlushFirst, MaintenanceFirst

  private def accepted(submission: MemoryTaskSubmission): IO[AgentError, MemoryTaskTicket] =
    submission match
      case MemoryTaskSubmission.Accepted(ticket) => ZIO.succeed(ticket)
      case MemoryTaskSubmission.Rejected(reason) =>
        ZIO.fail(AgentError.InvalidConfiguration(s"Unexpected rejection: $reason"))

  private def flush(scheduler: MemoryFlushScheduler): IO[AgentError, MemoryTaskOutcome] =
    scheduler
      .submit(Chunk(Msg.user("durable fact")))
      .flatMap:
        case MemoryFlushSubmission.Queued(submission) => accepted(submission).flatMap(_.await)
        case MemoryFlushSubmission.Empty =>
          ZIO.fail(AgentError.InvalidConfiguration("Expected eligible conversation"))

  private def maintain(scheduler: MemoryConsolidationScheduler): IO[AgentError, MemoryTaskOutcome] =
    scheduler.submit.flatMap(accepted).flatMap(_.await)

  private[agents] def policy: IO[AgentError.InvalidConfiguration, MemoryTriggerPolicy] =
    for
      flushInterval       <- ZIO.fromEither(MemoryInterval.fromDuration(1.hour))
      maintenanceInterval <- ZIO.fromEither(MemoryInterval.fromDuration(24.hours))
    yield MemoryTriggerPolicy(
      MemoryTrigger.Throttled(flushInterval),
      MemoryTrigger.Throttled(maintenanceInterval)
    )

  /** One shared behavioral oracle for local and independently reconstructed persistent authorities. */
  private[agents] def exercise(
      repository: MemoryRepository,
      gate: MemoryTriggerGate,
      rebuiltGate: MemoryTriggerGate,
      scope: MemoryScope,
      order: Order
  ): ZIO[Scope, AgentError, TestResult] =
    for
      _           <- repository.daily.appendDaily(LocalDate.of(2026, 9, 29), "seed fact")
      queue       <- MemoryTaskQueue.open()
      flushes     <- Ref.make(0)
      extractions <- Ref.make(0)
      reports     <- Ref.make(Chunk.empty[ConsolidationResult])
      manager <- MemoryFlushManager.make(
        repository.daily,
        MemoryExtractor(_ => flushes.update(_ + 1).as("NO_REPLY"))
      )
      consolidator <- MemoryConsolidator.make(
        repository.consolidation,
        MemoryExtractor(_ => extractions.update(_ + 1).as("curated seed"))
      )
      firstFlush <- MemoryFlushScheduler.make(queue, gate, scope, manager)
      firstMaintenance <- MemoryConsolidationScheduler.make(
        queue,
        gate,
        scope,
        consolidator,
        result => reports.update(_ :+ result)
      )
      initial <- order match
        case Order.FlushFirst       => flush(firstFlush).zip(maintain(firstMaintenance))
        case Order.MaintenanceFirst => maintain(firstMaintenance).zip(flush(firstFlush))
      repeatedFlush       <- flush(firstFlush)
      repeatedMaintenance <- maintain(firstMaintenance)
      rebuiltFlush        <- MemoryFlushScheduler.make(queue, rebuiltGate, scope, manager)
      rebuiltMaintenance <- MemoryConsolidationScheduler.make(
        queue,
        rebuiltGate,
        scope,
        consolidator,
        result => reports.update(_ :+ result)
      )
      finalFlush       <- flush(rebuiltFlush)
      finalMaintenance <- maintain(rebuiltMaintenance)
      _                <- queue.awaitIdle
      flushCount       <- flushes.get
      extractionCount  <- extractions.get
      results          <- reports.get
      curated          <- repository.daily.readCurated
    yield assertTrue(
      initial == (
        MemoryTaskOutcome.Completed(Exit.unit),
        MemoryTaskOutcome.Completed(Exit.unit)
      ),
      List(repeatedFlush, repeatedMaintenance, finalFlush, finalMaintenance)
        .forall(_ == MemoryTaskOutcome.Completed(Exit.unit)),
      flushCount == 1,
      extractionCount == 1,
      results == Chunk(ConsolidationResult.Committed),
      curated.contains("curated seed")
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("MemoryLifecycleIsolationSpec")(
    List(
      MemoryScope.User(Some("user")),
      MemoryScope.Session(Some("session")),
      MemoryScope.Agent,
      MemoryScope.Global
    ).flatMap(scope =>
      Order.values.toList.map(order =>
        test(s"flushAndMaintenanceHaveIndependentWindows: $scope / $order") {
          for
            repository <- MemoryRepository.inMemory()
            selected   <- policy
            gate       <- MemoryTriggerGate.inMemory(selected)
            result     <- exercise(repository, gate, gate, scope, order)
          yield result
        }
      )
    )*
  )

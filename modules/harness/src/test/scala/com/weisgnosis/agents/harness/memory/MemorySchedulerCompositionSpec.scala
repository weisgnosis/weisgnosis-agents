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

import com.weisgnosis.agents.core.agent.ReActAgent
import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import java.time.LocalDate
import zio.*
import zio.test.*

object MemorySchedulerCompositionSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("MemorySchedulerCompositionSpec")(
    test(
      "observer order admits flush first but pending replacement does not impose execution dependencies"
    ) {
      for
        repository <- MemoryRepository.inMemory()
        queue      <- MemoryTaskQueue.open()
        gate       <- MemoryTriggerGate.inMemory()
        entered    <- Promise.make[Nothing, Unit]
        release    <- Promise.make[Nothing, Unit]
        flushes    <- Ref.make(0)
        input      <- Ref.make(Chunk.empty[Msg])
        reports    <- Ref.make(Chunk.empty[ConsolidationResult])
        manager <- MemoryFlushManager.make(
          repository.daily,
          MemoryExtractor(_ =>
            flushes
              .updateAndGet(_ + 1)
              .flatMap:
                case 1 => entered.succeed(()) *> release.await.as("first committed fact")
                case _ => ZIO.succeed("second committed fact")
          )
        )
        consolidator <- MemoryConsolidator.make(
          repository.consolidation,
          MemoryExtractor(messages => input.set(messages).as("curated first fact"))
        )
        flush <- MemoryFlushScheduler.make(queue, gate, MemoryScope.Global, manager)
        maintenance <- MemoryConsolidationScheduler.make(
          queue,
          gate,
          MemoryScope.Global,
          consolidator,
          result => reports.update(_ :+ result)
        )
        flushTickets       <- Ref.make(Chunk.empty[MemoryTaskTicket])
        maintenanceTickets <- Ref.make(Chunk.empty[MemoryTaskTicket])
        observer = flush
          .completionObserver(ticket => flushTickets.update(_ :+ ticket))
          .andThen(maintenance.completionObserver(ticket => maintenanceTickets.update(_ :+ ticket)))
        agent <- ReActAgent.make(
          client = LlmClient.mockFunction((_, _) => TurnOutput.FinalText(Msg.assistant("done"))),
          completionObserver = observer
        )
        _          <- agent.run(Msg.user("first turn"))
        _          <- entered.await
        _          <- agent.run(Msg.user("second turn"))
        _          <- release.succeed(())
        _          <- queue.awaitIdle
        flushed    <- flushTickets.get.flatMap(ZIO.foreach(_)(_.await))
        maintained <- maintenanceTickets.get.flatMap(ZIO.foreach(_)(_.await))
        seen       <- input.get
        results    <- reports.get
        snapshot   <- repository.consolidation.snapshot
      yield assertTrue(
        flushed == Chunk.fill(2)(MemoryTaskOutcome.Completed(Exit.unit)),
        maintained == Chunk(MemoryTaskOutcome.Superseded, MemoryTaskOutcome.Completed(Exit.unit)),
        seen.exists(_.text.contains("first committed fact")),
        !seen.exists(_.text.contains("second committed fact")),
        results == Chunk(ConsolidationResult.Committed),
        snapshot.curated == "curated first fact",
        snapshot.pending.exists(_.content.contains("second committed fact"))
      )
    },
    test(
      "a failed report cannot erase a local consolidation that survives owner close and reopen"
    ) {
      val failure = AgentError.MemoryStorageError("report unavailable")
      for
        root <- MemoryTestSupport.temporaryDirectory
        outcome <- ZIO.scoped:
          for
            repository <- LocalMemoryRepository.open(root, WorkspaceTrust.HostControlled)
            _          <- repository.daily.appendDaily(LocalDate.of(2026, 9, 29), "persistent fact")
            queue      <- MemoryTaskQueue.open()
            gate       <- MemoryTriggerGate.inMemory()
            consolidator <- MemoryConsolidator.make(
              repository.consolidation,
              MemoryExtractor(_ => ZIO.succeed("persistent curated fact"))
            )
            scheduler <- MemoryConsolidationScheduler.make(
              queue,
              gate,
              MemoryScope.Global,
              consolidator,
              _ => ZIO.fail(failure)
            )
            submission <- scheduler.submit
            outcome <- submission match
              case MemoryTaskSubmission.Accepted(ticket) => ticket.await
              case MemoryTaskSubmission.Rejected(reason) =>
                ZIO.fail(AgentError.InvalidConfiguration(s"Unexpected rejection: $reason"))
          yield outcome
        reopened <- ZIO.scoped:
          LocalMemoryRepository
            .open(root, WorkspaceTrust.HostControlled)
            .flatMap: repository =>
              repository.consolidation.snapshot.map(snapshot =>
                snapshot.curated -> snapshot.pending
              )
      yield assertTrue(
        outcome == MemoryTaskOutcome.Completed(Exit.fail(failure)),
        reopened._1 == "persistent curated fact",
        reopened._2.isEmpty
      )
    }
  )

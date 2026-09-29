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
import java.time.LocalDate
import zio.*
import zio.test.*

object MemoryConsolidationSchedulerSpec extends ZIOSpecDefault:
  private val day: LocalDate     = LocalDate.of(2026, 9, 29)
  private val scope: MemoryScope = MemoryScope.User(Some("owner"))
  private val client: LlmClient =
    LlmClient.mockFunction((_, _) => TurnOutput.FinalText(Msg.assistant("done")))

  private def accepted(submission: UIO[MemoryTaskSubmission]): IO[AgentError, MemoryTaskTicket] =
    submission.flatMap:
      case MemoryTaskSubmission.Accepted(ticket) => ZIO.succeed(ticket)
      case MemoryTaskSubmission.Rejected(reason) =>
        ZIO.fail(AgentError.InvalidConfiguration(s"Unexpected rejection: $reason"))

  private def always: UIO[MemoryTriggerGate] =
    MemoryTriggerGate.inMemory(MemoryTriggerPolicy(MemoryTrigger.Always, MemoryTrigger.Always))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("MemoryConsolidationSchedulerSpec")(
    test(
      "gateClaimRunsOffTheCompletingThread: a blocked maintenance gate cannot delay agent completion"
    ) {
      for
        repository <- MemoryRepository.inMemory()
        queue      <- MemoryTaskQueue.open()
        entered    <- Promise.make[Nothing, Unit]
        release    <- Promise.make[Nothing, Unit]
        operations <- Ref.make(Chunk.empty[MemoryOperation])
        gate = new MemoryTriggerGate:
          def claim(
              operation: MemoryOperation,
              scope: MemoryScope
          ): IO[AgentError, MemoryTriggerDecision] =
            operations.update(_ :+ operation) *> entered.succeed(()) *> release.await.as(
              MemoryTriggerDecision.Throttled
            )
          def cleanup(retention: MemoryInterval): IO[AgentError, Int] = ZIO.succeed(0)
        consolidator <- MemoryConsolidator.make(
          repository.consolidation,
          MemoryExtractor(_ => ZIO.succeed("curated"))
        )
        results <- Ref.make(Chunk.empty[ConsolidationResult])
        tickets <- Ref.make(Chunk.empty[MemoryTaskTicket])
        scheduler <- MemoryConsolidationScheduler.make(
          queue,
          gate,
          scope,
          consolidator,
          result => results.update(_ :+ result)
        )
        agent <- ReActAgent.make(
          client = client,
          completionObserver = scheduler.completionObserver(ticket => tickets.update(_ :+ ticket))
        )
        result     <- ZIO.scoped(agent.run(Msg.user("fact")))
        _          <- entered.await
        registered <- tickets.get
        _          <- release.succeed(())
        outcomes   <- ZIO.foreach(registered)(_.await)
        observed   <- results.get
        claimed    <- operations.get
      yield assertTrue(
        result.reply.text == "done",
        registered.size == 1,
        outcomes.forall(_ == MemoryTaskOutcome.Completed(Exit.unit)),
        observed.isEmpty,
        claimed == Chunk(MemoryOperation.Maintenance)
      )
    },
    test("reports every returned consolidation result after scoped extraction cleanup") {
      val pending = Chunk(DailyMemory(day, "new fact"))
      val cases = List(
        (
          Chunk.empty[DailyMemory],
          "unused",
          ConsolidationCommit.Applied,
          ConsolidationResult.NoPendingMemories
        ),
        (pending, " ", ConsolidationCommit.Applied, ConsolidationResult.EmptyResponse),
        (pending, "curated", ConsolidationCommit.Conflict, ConsolidationResult.Conflict),
        (pending, "curated", ConsolidationCommit.Applied, ConsolidationResult.Committed)
      )
      ZIO
        .foreach(cases) { case (entries, text, committed, expected) =>
          for
            queue   <- MemoryTaskQueue.open()
            gate    <- always
            closed  <- Ref.make(false)
            reports <- Ref.make(Chunk.empty[(ConsolidationResult, Boolean)])
            store = new ConsolidationStore:
              type Revision = Unit
              def snapshot: IO[AgentError, ConsolidationSnapshot[Unit]] =
                ZIO.succeed(ConsolidationSnapshot((), "", entries))
              def commit(revision: Unit, content: String): IO[AgentError, ConsolidationCommit] =
                ZIO.succeed(committed)
            consolidator <- MemoryConsolidator
              .make(store, MemoryExtractor(_ => ZIO.addFinalizer(closed.set(true)).as(text)))
            scheduler <- MemoryConsolidationScheduler.make(
              queue,
              gate,
              scope,
              consolidator,
              value => closed.get.flatMap(done => reports.update(_ :+ (value -> done)))
            )
            ticket   <- accepted(scheduler.submit)
            outcome  <- ticket.await
            reported <- reports.get
          yield outcome == MemoryTaskOutcome.Completed(Exit.unit) && reported == Chunk(
            expected -> entries.nonEmpty
          )
        }
        .map(values => assertTrue(values.forall(identity)))
    },
    test("reporting failure leaves a successfully committed curated update intact") {
      val failure = AgentError.MemoryStorageError("result sink unavailable")
      for
        repository <- MemoryRepository.inMemory()
        _          <- repository.daily.appendDaily(day, "fact")
        queue      <- MemoryTaskQueue.open()
        gate       <- always
        consolidator <- MemoryConsolidator.make(
          repository.consolidation,
          MemoryExtractor(_ => ZIO.succeed("curated fact"))
        )
        scheduler <- MemoryConsolidationScheduler.make(
          queue,
          gate,
          scope,
          consolidator,
          _ => ZIO.fail(failure)
        )
        ticket  <- accepted(scheduler.submit)
        outcome <- ticket.await
        curated <- repository.daily.readCurated
        pending <- repository.consolidation.snapshot
      yield assertTrue(
        curated.contains("curated fact"),
        pending.pending.isEmpty,
        outcome == MemoryTaskOutcome.Completed(Exit.fail(failure))
      )
    },
    test("queued consolidation reads the latest committed daily snapshot at execution") {
      for
        repository <- MemoryRepository.inMemory()
        queue      <- MemoryTaskQueue.open()
        gate       <- always
        blocker    <- MemoryConversationToken.make
        entered    <- Promise.make[Nothing, Unit]
        release    <- Promise.make[Nothing, Unit]
        seen       <- Ref.make(Chunk.empty[Msg])
        results    <- Ref.make(Chunk.empty[ConsolidationResult])
        consolidator <- MemoryConsolidator.make(
          repository.consolidation,
          MemoryExtractor(input => seen.set(input).as("curated"))
        )
        scheduler <- MemoryConsolidationScheduler.make(
          queue,
          gate,
          scope,
          consolidator,
          value => results.update(_ :+ value)
        )
        _        <- queue.submit(blocker)(entered.succeed(()) *> release.await)
        _        <- entered.await
        ticket   <- accepted(scheduler.submit)
        _        <- repository.daily.appendDaily(day, "committed after admission")
        _        <- release.succeed(())
        outcome  <- ticket.await
        input    <- seen.get
        reported <- results.get
      yield assertTrue(
        outcome == MemoryTaskOutcome.Completed(Exit.unit),
        input.exists(_.text.contains("committed after admission")),
        reported == Chunk(ConsolidationResult.Committed)
      )
    },
    test("one owner scheduler coalesces pending maintenance from different conversations") {
      for
        repository <- MemoryRepository.inMemory()
        _          <- repository.daily.appendDaily(day, "fact")
        queue      <- MemoryTaskQueue.open()
        gate       <- always
        entered    <- Promise.make[Nothing, Unit]
        release    <- Promise.make[Nothing, Unit]
        reports    <- Ref.make(Chunk.empty[ConsolidationResult])
        consolidator <- MemoryConsolidator.make(
          repository.consolidation,
          MemoryExtractor(_ => entered.succeed(()) *> release.await.as("curated"))
        )
        scheduler <- MemoryConsolidationScheduler.make(
          queue,
          gate,
          scope,
          consolidator,
          value => reports.update(_ :+ value)
        )
        tickets <- Ref.make(Chunk.empty[MemoryTaskTicket])
        observer = scheduler.completionObserver(ticket => tickets.update(_ :+ ticket))
        first      <- ReActAgent.make(client = client, completionObserver = observer)
        second     <- ReActAgent.make(client = client, completionObserver = observer)
        _          <- first.run(Msg.user("first"))
        _          <- entered.await
        _          <- second.run(Msg.user("second"))
        _          <- first.run(Msg.user("third"))
        _          <- release.succeed(())
        registered <- tickets.get
        outcomes   <- ZIO.foreach(registered)(_.await)
        results    <- reports.get
      yield assertTrue(
        outcomes.count(_ == MemoryTaskOutcome.Superseded) == 1,
        outcomes.count(_ == MemoryTaskOutcome.Completed(Exit.unit)) == 2,
        results == Chunk(ConsolidationResult.Committed, ConsolidationResult.NoPendingMemories)
      )
    },
    test("disabled maintenance performs no consolidation and produces no result report") {
      for
        repository <- MemoryRepository.inMemory()
        _          <- repository.daily.appendDaily(day, "fact")
        queue      <- MemoryTaskQueue.open()
        gate <- MemoryTriggerGate.inMemory(MemoryTriggerPolicy(maintenance = MemoryTrigger.Never))
        extracted <- Ref.make(false)
        reports   <- Ref.make(Chunk.empty[ConsolidationResult])
        consolidator <- MemoryConsolidator.make(
          repository.consolidation,
          MemoryExtractor(_ => extracted.set(true).as("curated"))
        )
        scheduler <- MemoryConsolidationScheduler.make(
          queue,
          gate,
          scope,
          consolidator,
          value => reports.update(_ :+ value)
        )
        ticket   <- accepted(scheduler.submit)
        outcome  <- ticket.await
        called   <- extracted.get
        reported <- reports.get
        curated  <- repository.daily.readCurated
      yield assertTrue(
        outcome == MemoryTaskOutcome.Completed(Exit.unit),
        !called,
        reported.isEmpty,
        curated.isEmpty
      )
    }
  )

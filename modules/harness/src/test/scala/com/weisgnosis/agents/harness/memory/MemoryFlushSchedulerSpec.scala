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

import com.weisgnosis.agents.core.agent.*
import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import com.weisgnosis.agents.harness.memory.MemoryTestSupport.temporaryDirectory
import java.time.ZoneOffset
import zio.*
import zio.json.ast.Json
import zio.test.*

object MemoryFlushSchedulerSpec extends ZIOSpecDefault:
  private val scope: MemoryScope = MemoryScope.User(Some("owner"))
  private val client: LlmClient =
    LlmClient.mockFunction((_, _) => TurnOutput.FinalText(Msg.assistant("done")))

  private def agent(
      scheduler: MemoryFlushScheduler,
      tickets: Ref[Chunk[MemoryTaskTicket]]
  ): IO[AgentError, ReActAgent] =
    ReActAgent.make(
      client = client,
      completionObserver = scheduler.completionObserver(ticket => tickets.update(_ :+ ticket))
    )

  private def accepted(
      submission: IO[AgentError, MemoryFlushSubmission]
  ): IO[AgentError, MemoryTaskTicket] =
    submission.flatMap:
      case MemoryFlushSubmission.Queued(MemoryTaskSubmission.Accepted(ticket)) =>
        ZIO.succeed(ticket)
      case _ => ZIO.fail(AgentError.InvalidConfiguration("Expected a scheduled memory task"))

  private def completed(outcome: MemoryTaskOutcome): Boolean = outcome match
    case MemoryTaskOutcome.Completed(exit) => exit.isSuccess
    case _                                 => false

  def spec: Spec[TestEnvironment & Scope, Any] = suite("MemoryFlushSchedulerSpec")(
    test("onCompleteFiresBeforeAsyncFlushCompletes") {
      for
        repository <- MemoryRepository.inMemory()
        queue      <- MemoryTaskQueue.open()
        gate       <- MemoryTriggerGate.inMemory()
        entered    <- Promise.make[Nothing, Unit]
        release    <- Promise.make[Nothing, Unit]
        inputs     <- Ref.make(Chunk.empty[Chunk[Msg]])
        tickets    <- Ref.make(Chunk.empty[MemoryTaskTicket])
        manager <- MemoryFlushManager.make(
          repository.daily,
          MemoryExtractor(input =>
            inputs.update(_ :+ input) *> entered.succeed(()) *> release.await.as(
              "- deploy on Friday"
            )
          )
        )
        scheduler  <- MemoryFlushScheduler.make(queue, gate, scope, manager)
        runtime    <- agent(scheduler, tickets)
        events     <- ZIO.scoped(runtime.streamEvents(Msg.user("remember Friday")).runCollect)
        _          <- entered.await
        now        <- Clock.instant
        before     <- repository.daily.readDaily(now.atZone(ZoneOffset.UTC).toLocalDate)
        registered <- tickets.get
        _          <- release.succeed(())
        outcomes   <- ZIO.foreach(registered)(_.await)
        after      <- repository.daily.readDaily(now.atZone(ZoneOffset.UTC).toLocalDate)
        observed   <- inputs.get
      yield assertTrue(
        before.isEmpty,
        registered.size == 1,
        outcomes.forall(completed),
        after.exists(_.contains("deploy on Friday")),
        observed.flatten.exists(_.text.contains("remember Friday")),
        events.count(_.isInstanceOf[AgentEvent.AgentResultEvent]) == 1,
        events.lastOption.exists(_.isInstanceOf[AgentEvent.AgentEndEvent])
      )
    },
    test("flushesForSameIsolationKeyRunSerially") {
      for
        repository <- MemoryRepository.inMemory()
        queue      <- MemoryTaskQueue.open()
        gate       <- MemoryTriggerGate.inMemory()
        entered    <- Promise.make[Nothing, Unit]
        release    <- Promise.make[Nothing, Unit]
        calls      <- Ref.make(0)
        active     <- Ref.make(0)
        maximum    <- Ref.make(0)
        tickets    <- Ref.make(Chunk.empty[MemoryTaskTicket])
        extractor = MemoryExtractor(_ =>
          ZIO.acquireReleaseWith(
            active.updateAndGet(_ + 1).tap(value => maximum.update(_.max(value)))
          )(_ => active.update(_ - 1))(_ =>
            calls.getAndUpdate(_ + 1).flatMap {
              case 0 => entered.succeed(()) *> release.await.as("NO_REPLY")
              case _ => ZIO.succeed("NO_REPLY")
            }
          )
        )
        manager    <- MemoryFlushManager.make(repository.daily, extractor)
        scheduler  <- MemoryFlushScheduler.make(queue, gate, scope, manager)
        runtime    <- agent(scheduler, tickets)
        _          <- runtime.run(Msg.user("first"))
        _          <- entered.await
        _          <- runtime.run(Msg.user("second"))
        before     <- calls.get
        _          <- release.succeed(())
        _          <- queue.awaitIdle
        total      <- calls.get
        concurrent <- maximum.get
      yield assertTrue(before == 1, total == 2, concurrent == 1)
    },
    test("backToBackSameSessionFlushesCoalesceIntoOnePendingTask") {
      for
        repository <- MemoryRepository.inMemory()
        queue      <- MemoryTaskQueue.open()
        gate       <- MemoryTriggerGate.inMemory()
        entered    <- Promise.make[Nothing, Unit]
        release    <- Promise.make[Nothing, Unit]
        inputs     <- Ref.make(Chunk.empty[String])
        tickets    <- Ref.make(Chunk.empty[MemoryTaskTicket])
        manager <- MemoryFlushManager.make(
          repository.daily,
          MemoryExtractor(input =>
            inputs
              .getAndUpdate(_ :+ input.map(_.text).mkString)
              .flatMap(previous =>
                if previous.isEmpty then entered.succeed(()) *> release.await.as("NO_REPLY")
                else ZIO.succeed("NO_REPLY")
              )
          )
        )
        scheduler  <- MemoryFlushScheduler.make(queue, gate, scope, manager)
        runtime    <- agent(scheduler, tickets)
        _          <- runtime.run(Msg.user("turn-one"))
        _          <- entered.await
        _          <- runtime.run(Msg.user("turn-two"))
        _          <- runtime.run(Msg.user("turn-three"))
        _          <- release.succeed(())
        _          <- queue.awaitIdle
        registered <- tickets.get
        outcomes   <- ZIO.foreach(registered)(_.await)
        seen       <- inputs.get
      yield assertTrue(
        seen.size == 2,
        seen.lastOption.exists(text =>
          List("turn-one", "turn-two", "turn-three").forall(text.contains)
        ),
        outcomes.count(_ == MemoryTaskOutcome.Superseded) == 1,
        outcomes.count(completed) == 2
      )
    },
    test("sameUserDifferentSessionsEachKeepTheirPendingFlush") {
      for
        repository <- MemoryRepository.inMemory()
        queue      <- MemoryTaskQueue.open()
        gate       <- MemoryTriggerGate.inMemory()
        entered    <- Promise.make[Nothing, Unit]
        release    <- Promise.make[Nothing, Unit]
        inputs     <- Ref.make(Chunk.empty[String])
        tickets    <- Ref.make(Chunk.empty[MemoryTaskTicket])
        manager <- MemoryFlushManager.make(
          repository.daily,
          MemoryExtractor(input =>
            inputs
              .getAndUpdate(_ :+ input.map(_.text).mkString)
              .flatMap(previous =>
                if previous.isEmpty then entered.succeed(()) *> release.await.as("NO_REPLY")
                else ZIO.succeed("NO_REPLY")
              )
          )
        )
        firstScheduler  <- MemoryFlushScheduler.make(queue, gate, scope, manager)
        secondScheduler <- MemoryFlushScheduler.make(queue, gate, scope, manager)
        first           <- agent(firstScheduler, tickets)
        second          <- agent(secondScheduler, tickets)
        _               <- first.run(Msg.user("a-initial"))
        _               <- entered.await
        _               <- second.run(Msg.user("b-initial"))
        _               <- first.run(Msg.user("a-latest"))
        _               <- second.run(Msg.user("b-latest"))
        _               <- release.succeed(())
        _               <- queue.awaitIdle
        seen            <- inputs.get
        registered      <- tickets.get
        outcomes        <- ZIO.foreach(registered)(_.await)
      yield assertTrue(
        seen.size == 3,
        seen.drop(1).headOption.exists(_.contains("b-latest")),
        seen.lastOption.exists(_.contains("a-latest")),
        outcomes.count(_ == MemoryTaskOutcome.Superseded) == 1
      )
    },
    test(
      "empty and internal-only snapshots neither displace eligible work nor claim a throttle slot"
    ) {
      for
        repository <- MemoryRepository.inMemory()
        queue      <- MemoryTaskQueue.open()
        trigger    <- ZIO.fromEither(MemoryTrigger.throttled(1.hour))
        gate       <- MemoryTriggerGate.inMemory(MemoryTriggerPolicy(flush = trigger))
        blocker    <- MemoryConversationToken.make
        entered    <- Promise.make[Nothing, Unit]
        release    <- Promise.make[Nothing, Unit]
        calls      <- Ref.make(0)
        manager <- MemoryFlushManager.make(
          repository.daily,
          MemoryExtractor(_ => calls.update(_ + 1).as("NO_REPLY"))
        )
        scheduler <- MemoryFlushScheduler.make(queue, gate, scope, manager)
        _         <- queue.submit(blocker)(entered.succeed(()) *> release.await)
        _         <- entered.await
        useful    <- accepted(scheduler.submit(Chunk(Msg.user("keep this fact"))))
        empty     <- scheduler.submit(Chunk.empty)
        internal <- scheduler.submit(
          Chunk(Msg.system("instructions"), Msg.user("<session_context>internal"), Msg.user(" "))
        )
        _       <- release.succeed(())
        outcome <- useful.await
        after   <- gate.claim(MemoryOperation.Flush, scope)
        count   <- calls.get
      yield assertTrue(
        empty == MemoryFlushSubmission.Empty,
        internal == MemoryFlushSubmission.Empty,
        completed(outcome),
        after == MemoryTriggerDecision.Throttled,
        count == 1
      )
    },
    test("invalid provenance fails before queue admission or trigger reservation") {
      for
        repository <- MemoryRepository.inMemory()
        queue      <- MemoryTaskQueue.open()
        trigger    <- ZIO.fromEither(MemoryTrigger.throttled(1.hour))
        gate       <- MemoryTriggerGate.inMemory(MemoryTriggerPolicy(flush = trigger))
        manager <- MemoryFlushManager.make(
          repository.daily,
          MemoryExtractor(_ => ZIO.succeed("NO_REPLY"))
        )
        scheduler <- MemoryFlushScheduler.make(queue, gate, scope, manager)
        malformed = Msg
          .user("summary")
          .withName(ConversationCompactor.SummaryName)
          .withMetadata(ConversationCompactor.RetainedUsersKey, Json.Num(1))
        exit  <- scheduler.submit(Chunk(malformed)).exit
        first <- gate.claim(MemoryOperation.Flush, scope)
        _     <- queue.awaitIdle
      yield assertTrue(exit.isFailure, first == MemoryTriggerDecision.Admitted)
    },
    test(
      "worker-side throttling suppresses extraction and retains admission after extraction failure"
    ) {
      val failure = AgentError.ModelError(503, "extractor offline")
      for
        repository <- MemoryRepository.inMemory()
        queue      <- MemoryTaskQueue.open()
        trigger    <- ZIO.fromEither(MemoryTrigger.throttled(5.seconds))
        gate       <- MemoryTriggerGate.inMemory(MemoryTriggerPolicy(flush = trigger))
        calls      <- Ref.make(0)
        manager <- MemoryFlushManager.make(
          repository.daily,
          MemoryExtractor(_ => calls.update(_ + 1) *> ZIO.fail(failure))
        )
        scheduler  <- MemoryFlushScheduler.make(queue, gate, scope, manager)
        first      <- accepted(scheduler.submit(Chunk(Msg.user("fact"))))
        failed     <- first.await
        second     <- accepted(scheduler.submit(Chunk(Msg.user("later"))))
        suppressed <- second.await
        count      <- calls.get
      yield assertTrue(
        count == 1,
        completed(suppressed),
        failed match
          case MemoryTaskOutcome.Completed(Exit.Failure(cause)) => cause.failures.contains(failure)
          case _                                                => false
      )
    },
    test("completed background extraction survives local daily-store closure and reopen") {
      for
        root <- temporaryDirectory
        now  <- Clock.instant
        _ <- ZIO.scoped:
          for
            store <- LocalDailyMemoryStore.open(root, WorkspaceTrust.HostControlled)
            queue <- MemoryTaskQueue.open()
            gate  <- MemoryTriggerGate.inMemory()
            manager <- MemoryFlushManager.make(
              store,
              MemoryExtractor(_ => ZIO.succeed("- durable preference"))
            )
            scheduler  <- MemoryFlushScheduler.make(queue, gate, scope, manager)
            tickets    <- Ref.make(Chunk.empty[MemoryTaskTicket])
            runtime    <- agent(scheduler, tickets)
            _          <- runtime.run(Msg.user("remember preference"))
            registered <- tickets.get
            _          <- ZIO.foreachDiscard(registered)(_.await)
          yield ()
        stored <- ZIO.scoped(
          LocalDailyMemoryStore
            .open(root, WorkspaceTrust.HostControlled)
            .flatMap(_.readDaily(now.atZone(ZoneOffset.UTC).toLocalDate))
        )
      yield assertTrue(stored.exists(_.contains("- durable preference")))
    },
    test("awaited compactor flush preserves removed facts before scheduled terminal extraction") {
      for
        repository <- MemoryRepository.inMemory()
        queue      <- MemoryTaskQueue.open()
        gate       <- MemoryTriggerGate.inMemory()
        inputs     <- Ref.make(Chunk.empty[String])
        manager <- MemoryFlushManager.make(
          repository.daily,
          MemoryExtractor(input =>
            inputs.update(_ :+ input.map(_.text).mkString).as("- preserved fact")
          )
        )
        scheduler <- MemoryFlushScheduler.make(queue, gate, scope, manager)
        tickets   <- Ref.make(Chunk.empty[MemoryTaskTicket])
        compactor = new ConversationCompactor(
          _ => ZIO.succeed("summary"),
          CompactorConfig(
            triggerTokens = 1,
            keepTokens = 0,
            preserveTailMessages = 1,
            protectTokens = 0
          ),
          flush = manager.flush
        )
        runtime <- ReActAgent.make(
          client = client,
          initialHistory = Chunk(Msg.user("old durable fact"), Msg.assistant("old reply")),
          historyPreparer = compactor.preparer,
          completionObserver = scheduler.completionObserver(ticket => tickets.update(_ :+ ticket))
        )
        result <- runtime.run(Msg.user("latest fact"))
        _      <- queue.awaitIdle
        seen   <- inputs.get
        now    <- Clock.instant
        stored <- repository.daily.readDaily(now.atZone(ZoneOffset.UTC).toLocalDate)
      yield assertTrue(
        !result.history.exists(_.text.contains("old durable fact")),
        seen.size == 2,
        seen.headOption.exists(_.contains("old durable fact")),
        seen.lastOption.exists(_.contains("latest fact")),
        stored.exists(_.contains("- preserved fact"))
      )
    },
    test("queued work uses the submitted completed snapshot rather than later agent history") {
      for
        repository <- MemoryRepository.inMemory()
        queue      <- MemoryTaskQueue.open()
        gate       <- MemoryTriggerGate.inMemory()
        blocker    <- MemoryConversationToken.make
        entered    <- Promise.make[Nothing, Unit]
        release    <- Promise.make[Nothing, Unit]
        observed   <- Ref.make(Chunk.empty[Msg])
        manager <- MemoryFlushManager.make(
          repository.daily,
          MemoryExtractor(input => observed.set(input).as("NO_REPLY"))
        )
        scheduler <- MemoryFlushScheduler.make(queue, gate, scope, manager)
        tickets   <- Ref.make(Chunk.empty[MemoryTaskTicket])
        runtime   <- agent(scheduler, tickets)
        _         <- queue.submit(blocker)(entered.succeed(()) *> release.await)
        _         <- entered.await
        _         <- runtime.run(Msg.user("immutable fact"))
        _         <- runtime.resetHistory
        _         <- release.succeed(())
        _         <- queue.awaitIdle
        input     <- observed.get
        history   <- runtime.history
      yield assertTrue(history.isEmpty, input.exists(_.text.contains("immutable fact")))
    }
  )

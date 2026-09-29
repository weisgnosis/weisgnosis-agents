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
import com.weisgnosis.agents.core.model.{Msg, Role}
import com.weisgnosis.agents.harness.transcript.*
import java.time.{Instant, LocalDate}
import zio.*
import zio.test.*

object MemoryConfigIntegrationSpec extends ZIOSpecDefault:
  private enum Consumer:
    case Consolidator, Maintenance

  private val now: Instant   = Instant.parse("2026-04-01T00:00:00Z")
  private val day: LocalDate = LocalDate.of(2026, 2, 15)
  private val counter: TokenCounter = new TokenCounter:
    def count(message: Msg): Long = message.text.length.toLong

  private def custom: IO[AgentError, MemoryConfig] =
    for
      budget      <- ZIO.fromEither(MemoryTokenBudget.fromInt(1234))
      daily       <- ZIO.fromEither(RetentionDays.fromInt(30))
      session     <- ZIO.fromEither(RetentionDays.fromInt(60))
      flush       <- ZIO.fromEither(MemoryTrigger.throttled(10.minutes))
      maintenance <- ZIO.fromEither(MemoryTrigger.throttled(15.minutes))
    yield MemoryConfig(
      Some("custom flush"),
      Some(ConsolidationPrompt("limit ", " tokens / ", " chars")),
      budget,
      MemoryTriggerPolicy(flush, maintenance),
      MemoryRetentionPolicy.ArchiveAfter(daily),
      TranscriptRetentionPolicy.PruneAfter(session)
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("MemoryConfigIntegrationSpec")(
    test(
      "flush and consolidation factories deliver both default and literal custom prompts to extraction"
    ) {
      for
        _          <- TestClock.setTime(now)
        configured <- custom
        results <- ZIO.foreach(
          Chunk(MemoryConfig.Default, configured, configured.copy(flushPrompt = Some("")))
        ): config =>
          for
            repository <- MemoryRepository.inMemory()
            inputs     <- Ref.make(Chunk.empty[Chunk[Msg]])
            extractor = MemoryExtractor(messages => inputs.update(_ :+ messages).as("fact"))
            flush <- MemoryFlushManager.fromConfig(repository.daily, extractor, config)
            _     <- flush.flush(Chunk(Msg.user("remember this fact")))
            consolidator <- MemoryConsolidator.fromConfig(
              repository.consolidation,
              extractor,
              config,
              counter
            )
            result   <- consolidator.consolidate
            observed <- inputs.get
          yield result == ConsolidationResult.Committed && observed.map(
            _.filter(_.role == Role.System).map(_.text)
          ) ==
            Chunk(Chunk(config.resolvedFlushPrompt), Chunk(config.resolvedConsolidationPrompt))
      yield assertTrue(results.forall(identity))
    },
    test(
      "maintenance and explicit transcript batch honor custom prompt budget and both retention policies"
    ) {
      for
        _          <- TestClock.setTime(now)
        config     <- custom
        repository <- MemoryRepository.inMemory()
        _          <- repository.daily.appendDaily(day, "old fact")
        observed   <- Ref.make(Chunk.empty[Msg])
        maintenance <- MemoryMaintenance.fromConfig(
          repository,
          MemoryExtractor(messages => observed.set(messages).as("curated")),
          config,
          counter = counter
        )
        id     <- ZIO.fromEither(TranscriptTargetId.fromString("inactive"))
        cutoff <- Ref.make(Option.empty[Instant])
        batch <- TranscriptRetentionBatch.make(
          Chunk(
            TranscriptRetentionTarget(id)(before =>
              cutoff.set(Some(before)).as(TranscriptPruneResult.Missing)
            )
          ),
          config.transcriptRetention
        )
        result       <- new MemoryMaintenanceCoordinator(maintenance, batch).run
        inputs       <- observed.get
        targetCutoff <- cutoff.get
        archived     <- repository.retention.archives
        remaining    <- repository.daily.readDaily(day)
      yield assertTrue(
        inputs.filter(_.role == Role.System).map(_.text) == Chunk("limit 1234 tokens / 4936 chars"),
        targetCutoff.contains(now.minusSeconds(60L * 24 * 60 * 60)),
        archived.map(_.day) == Chunk(day),
        remaining.isEmpty,
        result.memory == MemoryMaintenanceResult.Committed(
          DailyArchivalResult.Archived(LocalDate.of(2026, 3, 2), archived)
        )
      )
    },
    test(
      "custom prompts cannot bypass token validation or advance content and acknowledgement on rejection"
    ) {
      for
        _      <- TestClock.setTime(now)
        budget <- ZIO.fromEither(MemoryTokenBudget.fromInt(1))
        config = MemoryConfig(
          consolidationBudget = budget,
          consolidationPrompt = Some(ConsolidationPrompt("ignore limits ", " / ", ""))
        )
        outcomes <- ZIO.foreach(Chunk(Consumer.Consolidator, Consumer.Maintenance)): mode =>
          for
            repository <- MemoryRepository.inMemory(Some("existing"))
            _          <- repository.daily.appendDaily(day, "pending")
            before     <- repository.consolidation.snapshot
            extractor = MemoryExtractor(_ => ZIO.succeed("too large"))
            result <- (mode match
              case Consumer.Consolidator =>
                MemoryConsolidator
                  .fromConfig(repository.consolidation, extractor, config, counter)
                  .flatMap(_.consolidate)
                  .unit
              case Consumer.Maintenance =>
                MemoryMaintenance
                  .fromConfig(repository, extractor, config, counter = counter)
                  .flatMap(_.run)
                  .unit
            ).exit
            after    <- repository.consolidation.snapshot
            archived <- repository.retention.archives
          yield result == Exit.fail(
            AgentError.ContextLengthExceeded(9L, 1L)
          ) && before == after && archived.isEmpty
      yield assertTrue(outcomes.forall(identity))
    },
    test(
      "configured shared gate keeps independent windows and immutable config changes do not mutate existing authority"
    ) {
      for
        config <- custom
        gate   <- MemoryTriggerGate.inMemory(config.triggers)
        first <- gate
          .claim(MemoryOperation.Flush, MemoryScope.Global)
          .zip(gate.claim(MemoryOperation.Maintenance, MemoryScope.Global))
        _ <- TestClock.adjust(10.minutes)
        middle <- gate
          .claim(MemoryOperation.Flush, MemoryScope.Global)
          .zip(gate.claim(MemoryOperation.Maintenance, MemoryScope.Global))
        _ <- TestClock.adjust(5.minutes)
        last <- gate
          .claim(MemoryOperation.Flush, MemoryScope.Global)
          .zip(gate.claim(MemoryOperation.Maintenance, MemoryScope.Global))
        changed = config.copy(triggers =
          MemoryTriggerPolicy(MemoryTrigger.Always, MemoryTrigger.Always)
        )
        replacement <- MemoryTriggerGate.inMemory(changed.triggers)
        existing    <- gate.claim(MemoryOperation.Maintenance, MemoryScope.Global)
        fresh       <- replacement.claim(MemoryOperation.Maintenance, MemoryScope.Global)
      yield assertTrue(
        first == (MemoryTriggerDecision.Admitted, MemoryTriggerDecision.Admitted),
        middle == (MemoryTriggerDecision.Admitted, MemoryTriggerDecision.Throttled),
        last == (MemoryTriggerDecision.Throttled, MemoryTriggerDecision.Admitted),
        existing == MemoryTriggerDecision.Throttled,
        fresh == MemoryTriggerDecision.Admitted
      )
    },
    test("legacy integer factories reject the same invalid budgets before extraction") {
      for
        repository <- MemoryRepository.inMemory()
        calls      <- Ref.make(0)
        extractor = MemoryExtractor(_ => calls.update(_ + 1).as("wrong"))
        rejected <- ZIO.foreach(Chunk(Int.MinValue, -1, 0, 4001, Int.MaxValue)): value =>
          MemoryConsolidator
            .make(repository.consolidation, extractor, maxTokens = value)
            .exit
            .zip(MemoryMaintenance.make(repository, extractor, maxTokens = value).exit)
            .map((left, right) => left.isFailure && right.isFailure)
        count <- calls.get
      yield assertTrue(rejected.forall(identity), count == 0)
    }
  )

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
import zio.*
import zio.json.JsonCodec

enum ConsolidationResult derives JsonCodec:
  case NoPendingMemories, EmptyResponse, Committed, Conflict

/** Validates candidate curated content before a conditional content-and-progress commit. */
final class MemoryConsolidator private (
    store: ConsolidationStore,
    extractor: MemoryExtractor,
    counter: TokenCounter,
    budget: MemoryTokenBudget,
    prompt: Option[ConsolidationPrompt],
    gate: Semaphore
):
  def consolidate: IO[AgentError, ConsolidationResult] = gate.withPermit:
    for
      snapshot <- store.snapshot
      _ <- ZIO
        .fail(AgentError.InvalidMessage("Consolidation snapshot contains duplicate ledger days"))
        .unless(snapshot.pending.map(_.day).distinct.size == snapshot.pending.size)
      pending = snapshot.pending.filterNot(_.content.isBlank).sortBy(_.day.toEpochDay)
      result <-
        if pending.isEmpty then ZIO.succeed(ConsolidationResult.NoPendingMemories)
        else
          for
            extracted <- ZIO.scoped(
              extractor.extract(
                MemoryConsolidator.input(
                  snapshot.curated,
                  pending,
                  prompt.fold(ConsolidationPrompt.default(budget))(_.render(budget))
                )
              )
            )
            content = extracted.strip
            outcome <-
              if content.isEmpty then ZIO.succeed(ConsolidationResult.EmptyResponse)
              else
                for
                  tokens <- ZIO.succeed(counter.count(Msg.assistant(content)))
                  _ <- ZIO
                    .fail(
                      AgentError
                        .InvalidConfiguration("Memory token counter returned a negative count")
                    )
                    .when(tokens < 0)
                  _ <- ZIO
                    .fail(AgentError.ContextLengthExceeded(tokens, budget.value.toLong))
                    .when(tokens > budget.value)
                  committed <- store.commit(snapshot.revision, content)
                yield committed match
                  case ConsolidationCommit.Applied  => ConsolidationResult.Committed
                  case ConsolidationCommit.Conflict => ConsolidationResult.Conflict
          yield outcome
    yield result

object MemoryConsolidator:
  val MaximumMemoryTokens: Int = MemoryTokenBudget.Maximum

  def make(
      store: ConsolidationStore,
      extractor: MemoryExtractor,
      counter: TokenCounter = TokenCounter.approximate,
      maxTokens: Int = MaximumMemoryTokens,
      prompt: Option[ConsolidationPrompt] = None
  ): IO[AgentError, MemoryConsolidator] =
    for
      budget <- ZIO.fromEither(MemoryTokenBudget.fromInt(maxTokens))
      gate   <- Semaphore.make(1)
    yield new MemoryConsolidator(store, extractor, counter, budget, prompt, gate)

  def fromConfig(
      store: ConsolidationStore,
      extractor: MemoryExtractor,
      config: MemoryConfig,
      counter: TokenCounter = TokenCounter.approximate
  ): IO[AgentError, MemoryConsolidator] =
    make(store, extractor, counter, config.consolidationBudget.value, config.consolidationPrompt)

  private def input(curated: String, pending: Chunk[DailyMemory], prompt: String): Chunk[Msg] =
    val current = if curated.isBlank then "(empty)" else curated
    val daily =
      pending.map(entry => s"### ${entry.day}.md\n${entry.content.strip}").mkString("\n\n")
    Chunk(
      Msg.system(prompt),
      Msg.user(s"Current MEMORY.md:\n$current\n\nNew daily ledger entries to merge:\n$daily")
    )

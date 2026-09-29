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
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.Msg
import java.time.LocalDate
import zio.*
import zio.test.*

object MemoryFlushManagerIntegrationSpec extends ZIOSpecDefault:
  private val counter: TokenCounter = new TokenCounter:
    def count(message: Msg): Long = 1L
  private val config: CompactorConfig =
    CompactorConfig(triggerTokens = 2, keepTokens = 2, preserveTailMessages = 2, protectTokens = 0)
  private val initial: Chunk[Msg] = Chunk.fill(5)(Msg.assistant("old fact"))
  private val input: Msg          = Msg.user("q")
  private def store(append: String => IO[AgentError, Unit]): DailyMemoryStore =
    new DailyMemoryStore:
      def readCurated: IO[AgentError, Option[String]]                      = ZIO.none
      def readDaily(day: LocalDate): IO[AgentError, Option[String]]        = ZIO.none
      def appendDaily(day: LocalDate, entry: String): IO[AgentError, Unit] = append(entry)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("MemoryFlushManagerIntegrationSpec")(
    test("daily append completes before compactor offload summary and main model request") {
      for
        operations       <- Ref.make(Chunk.empty[String])
        extractionClosed <- Ref.make(false)
        extractorClient = LlmClient.mockEffectful((_, _, _) =>
          ZIO.addFinalizer(extractionClosed.set(true)) *>
            operations.update(_ :+ "extract").as(TurnOutput.FinalText(Msg.assistant("- new fact")))
        )
        manager <- MemoryFlushManager.make(
          store(_ => operations.update(_ :+ "append")),
          MemoryExtractor.fromClient(extractorClient, LlmConfig("extractor"))
        )
        compactor = new ConversationCompactor(
          _ => operations.update(_ :+ "summary").as("summary"),
          config,
          counter,
          manager.flush,
          _ => operations.update(_ :+ "offload")
        )
        client = LlmClient.mockEffectful((_, _, _) =>
          operations.update(_ :+ "model").as(TurnOutput.FinalText(Msg.assistant("done")))
        )
        agent <- ReActAgent.make(
          client = client,
          initialHistory = initial,
          historyPreparer = compactor.preparer
        )
        result   <- agent.run(input)
        observed <- operations.get
        closed   <- extractionClosed.get
      yield assertTrue(
        observed == Chunk("extract", "append", "offload", "summary", "model"),
        closed,
        result.reply.text == "done",
        result.history.count(ConversationCompactor.isSummary) == 1
      )
    },
    test("daily append failure preserves canonical input and prevents summary and dispatch") {
      val error = AgentError.InvalidMessage("daily append failed")
      for
        operations <- Ref.make(Chunk.empty[String])
        manager <- MemoryFlushManager.make(
          store(_ => operations.update(_ :+ "append") *> ZIO.fail(error)),
          MemoryExtractor(_ => ZIO.succeed("- fact"))
        )
        compactor = new ConversationCompactor(
          _ => operations.update(_ :+ "summary").as("wrong"),
          config,
          counter,
          manager.flush
        )
        client = LlmClient.mockEffectful((_, _, _) =>
          operations.update(_ :+ "model").as(TurnOutput.FinalText(Msg.assistant("wrong")))
        )
        agent <- ReActAgent.make(
          client = client,
          initialHistory = initial,
          historyPreparer = compactor.preparer
        )
        result   <- agent.run(input).either
        history  <- agent.history
        observed <- operations.get
      yield assertTrue(
        result == Left(AgentError.HistoryPreparationError(error.message)),
        history == initial :+ input,
        observed == Chunk("append")
      )
    },
    test(
      "cancelling installed extraction closes resources and leaves original history plus recovery"
    ) {
      for
        entered    <- Promise.make[Nothing, Unit]
        closed     <- Ref.make(false)
        operations <- Ref.make(Chunk.empty[String])
        manager <- MemoryFlushManager.make(
          store(_ => operations.update(_ :+ "append")),
          MemoryExtractor(_ =>
            ZIO.addFinalizer(closed.set(true)) *> entered.succeed(()) *> ZIO.never
          )
        )
        compactor = new ConversationCompactor(
          _ => operations.update(_ :+ "summary").as("wrong"),
          config,
          counter,
          manager.flush
        )
        client = LlmClient.mockEffectful((_, _, _) =>
          operations.update(_ :+ "model").as(TurnOutput.FinalText(Msg.assistant("wrong")))
        )
        agent <- ReActAgent.make(
          client = client,
          initialHistory = initial,
          historyPreparer = compactor.preparer
        )
        fiber    <- agent.run(input).forkScoped
        _        <- entered.await
        exit     <- fiber.interrupt
        history  <- agent.history
        observed <- operations.get
        released <- closed.get
      yield assertTrue(
        exit.isInterrupted,
        released,
        observed.isEmpty,
        history.dropRight(1) == initial :+ input,
        history.lastOption.flatMap(_.generateReason).contains("interrupted")
      )
    }
  )

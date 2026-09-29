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
package com.weisgnosis.agents.harness.transcript

import com.weisgnosis.agents.core.agent.*
import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import com.weisgnosis.agents.harness.memory.{CompactorConfig, ConversationCompactor}
import com.weisgnosis.agents.harness.memory.MemoryTestSupport.temporaryDirectory
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object TranscriptCompletionSpec extends ZIOSpecDefault:
  private def user(id: String, text: String): Msg =
    Msg.user(Chunk(ContentBlock.Text(text)), id = Some(id))
  private def assistant(id: String, text: String): Msg =
    Msg.assistant(Chunk(ContentBlock.Text(text)), id = Some(id))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("TranscriptCompletionSpec")(
    test("stable completion snapshots persist each observation once across runs and reopen") {
      for
        root <- temporaryDirectory
        _ <- ZIO.scoped:
          for
            store <- LocalTranscriptStore.open(root, WorkspaceTrust.HostControlled)
            writer = new SessionTranscriptWriter(store)
            client <- LlmClient.mock(
              Chunk(
                TurnOutput.FinalText(assistant("a1", "first reply")),
                TurnOutput.FinalText(assistant("a2", "second reply"))
              )
            )
            agent <- ReActAgent.make(
              client = client,
              completionObserver =
                writer.completionObserver(AnonymousTranscriptPolicy.RequireStableIds)
            )
            _ <- agent.run(Chunk(Msg.user("<session_context>internal"), user("q1", "first")))
            _ <- agent.run(user("q2", "second"))
          yield ()
        persisted <- ZIO.scoped(
          LocalTranscriptStore.open(root, WorkspaceTrust.HostControlled).flatMap(_.entries)
        )
      yield assertTrue(
        persisted.flatMap(_.key) == Chunk("q1", "a1", "q2", "a2").map(TranscriptKey.Message.apply)
      )
    },
    test("anonymous recording policy explicitly retains repeated snapshot observations") {
      for
        store <- TranscriptStore.inMemory
        writer = new SessionTranscriptWriter(store)
        client <- LlmClient.mock(
          Chunk(
            TurnOutput.FinalText(Msg.assistant("first reply")),
            TurnOutput.FinalText(Msg.assistant("second reply"))
          )
        )
        agent <- ReActAgent.make(
          client = client,
          completionObserver =
            writer.completionObserver(AnonymousTranscriptPolicy.RecordEachSnapshot)
        )
        _       <- agent.run(Msg.user("first"))
        _       <- agent.run(Msg.user("second"))
        entries <- store.entries
        texts = entries.collect {
          case TranscriptEntry(_, _, _, _, TranscriptPayload.Message(_, text, _)) => text
        }
      yield assertTrue(
        texts == Chunk("first", "first reply", "first", "first reply", "second", "second reply"),
        entries.forall(_.key.isEmpty)
      )
    },
    test("strict identity policy fails before append without silently assigning conversation IDs") {
      for
        store <- TranscriptStore.inMemory
        agent <- ReActAgent.make(
          client = LlmClient.mockFunction((_, _) => TurnOutput.FinalText(Msg.assistant("done"))),
          completionObserver = new SessionTranscriptWriter(store).completionObserver(
            AnonymousTranscriptPolicy.RequireStableIds
          )
        )
        exit    <- agent.run(Msg.user("anonymous")).exit
        history <- agent.history
        entries <- store.entries
      yield assertTrue(
        exit.causeOption.exists(
          _.failures.exists(_.isInstanceOf[AgentError.CompletionObservationError])
        ),
        entries.isEmpty,
        history.forall(_.id.isEmpty),
        history.lastOption.exists(_.text == "done")
      )
    },
    test("cancellation persists reconciled tool errors before the owning filesystem scope closes") {
      for
        root <- temporaryDirectory
        cancelled <- ZIO.scoped:
          for
            store   <- LocalTranscriptStore.open(root, WorkspaceTrust.HostControlled)
            entered <- Promise.make[Nothing, Unit]
            tool = new Tool[Any, Json, String]:
              def definition: ToolDef = ToolDef("wait", "Wait for cancellation", Json.Obj())
              def decodeInput(raw: Json): Either[String, Json] = Right(raw)
              def encodeOutput(value: String): String          = value
              def execute(input: Json): ZIO[Any, Throwable, String] =
                entered.succeed(()) *> ZIO.never
            client = LlmClient.mockFunction((_, _) =>
              TurnOutput.ToolCalls(
                Msg.assistant(Chunk(ContentBlock.ToolUse("t", "wait", Json.Obj())))
              )
            )
            agent <- ReActAgent.make(
              client = client,
              toolkit = Toolkit(Map("wait" -> tool)),
              completionObserver = new SessionTranscriptWriter(store).completionObserver(
                AnonymousTranscriptPolicy.RecordEachSnapshot
              )
            )
            fiber <- agent.run(Msg.user("q")).forkScoped
            _     <- entered.await
            exit  <- fiber.interrupt
          yield exit
        persisted <- ZIO.scoped(
          LocalTranscriptStore.open(root, WorkspaceTrust.HostControlled).flatMap(_.entries)
        )
        results = persisted.collect {
          case TranscriptEntry(_, _, _, _, result: TranscriptPayload.ToolResult) => result
        }
      yield assertTrue(
        cancelled.isInterrupted,
        results.size == 1,
        results.forall(result => result.callId == "t" && result.state == ToolResultState.Error),
        persisted.lastOption.exists(
          _.payload == TranscriptPayload.Message(
            Role.Assistant,
            "I noticed that you have interrupted me. What can I do for you?",
            Chunk(TranscriptBlockType.Text)
          )
        )
      )
    },
    test(
      "pre-compaction offload retains removed prefixes before the terminal snapshot is observed"
    ) {
      val original =
        Chunk(user("q1", "first"), assistant("a1", "first reply"), user("q2", "second"))
      for
        store <- TranscriptStore.inMemory
        writer = new SessionTranscriptWriter(store)
        compactor = new ConversationCompactor(
          _ => ZIO.succeed("summary"),
          CompactorConfig(
            triggerTokens = 1,
            keepTokens = 0,
            preserveTailMessages = 1,
            protectTokens = 0
          ),
          offload = writer.offload
        )
        agent <- ReActAgent.make(
          client = LlmClient.mockFunction((_, _) => TurnOutput.FinalText(assistant("a2", "done"))),
          initialHistory = original,
          historyPreparer = compactor.preparer,
          completionObserver =
            writer.completionObserver(AnonymousTranscriptPolicy.RecordEachSnapshot)
        )
        result  <- agent.run(user("q3", "latest"))
        entries <- store.entries
        keys = entries.flatMap(_.key).toSet
      yield assertTrue(
        Set("q1", "a1", "q2", "q3", "a2").map(TranscriptKey.Message.apply).subsetOf(keys),
        result.history.exists(ConversationCompactor.isSummary),
        !result.history.exists(_.id.contains("q1")),
        entries.exists(_.key.isEmpty)
      )
    },
    test("policies round trip and backend failures enter the completion-specific error channel") {
      val store = new TranscriptStore:
        def entries: IO[AgentError, Chunk[TranscriptEntry]] = ZIO.succeed(Chunk.empty)
        def append(drafts: Chunk[TranscriptDraft]): IO[AgentError, Chunk[TranscriptEntry]] =
          ZIO.fail(AgentError.MemoryStorageError("backend unavailable"))
      val observer = new SessionTranscriptWriter(store).completionObserver(
        AnonymousTranscriptPolicy.RequireStableIds
      )
      val result = AgentResult(assistant("a", "done"), Chunk(user("q", "question")), 1)
      for failure <- ZIO
          .scoped(observer.observe(TurnCompletion("agent", result.history, Exit.succeed(result))))
          .either
      yield assertTrue(
        failure == Left(AgentError.CompletionObservationError("backend unavailable")),
        AnonymousTranscriptPolicy.values.forall(policy =>
          policy.toJson.fromJson[AnonymousTranscriptPolicy] == Right(policy)
        )
      )
    }
  )

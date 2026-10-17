/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.examples

import com.weisgnosis.agents.core.agent.{ReActAgent, StructuredMode}
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, ScriptedLlmClient, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg}
import com.weisgnosis.agents.core.tool.{ToolSchema, Toolkit}
import com.weisgnosis.agents.examples.retrieval.*
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Deterministic integration recipe, not a claim of model accuracy or a bundled RAG engine. */
object OfflineRetrieval extends ZIOAppDefault:
  val corpus: Chunk[SourceExcerpt] = Chunk(
    SourceExcerpt("runtime", "r1", "cancellation", "Cancellation waits for owned tool cleanup."),
    SourceExcerpt("runtime", "r1", "history", "Completed tool results remain in agent history.")
  )
  val expectedAnswer: EvidenceAnswer = EvidenceAnswer(
    "Cancellation waits for cleanup.",
    Chunk(EvidenceCitation("runtime:r1:cancellation", "Cancellation waits for owned tool cleanup."))
  )

  val searchTurn: TurnOutput = TurnOutput.ToolCalls(
    Msg.assistant(
      Chunk(
        ContentBlock.ToolUse(
          "search-1",
          "search_documents",
          Json.Obj("query" -> Json.Str("cancellation"), "maximumResults" -> Json.Num(2))
        )
      )
    )
  )

  def exercise(client: LlmClient, retriever: Retriever): IO[AgentError, EvidenceAnswer] =
    ZIO.scoped:
      for
        manifest <- Ref.make(Chunk.empty[RetrievedEvidence])
        toolkit <- ZIO.fromEither(
          Toolkit.empty.register(BoundedRetrieval.tool(retriever, manifest))
        )
        agent <- ReActAgent.make(
          toolkit = toolkit,
          client = client,
          llmConfig = LlmConfig("offline-script")
        )
        generated <- agent.runStructured[EvidenceAnswer](
          Chunk(Msg.user("What does cancellation wait for? Search first and cite the evidence.")),
          summon[ToolSchema[EvidenceAnswer]].jsonSchema,
          StructuredMode.Native
        )
        evidence <- manifest.get
        checked  <- ZIO.fromEither(BoundedRetrieval.validateAnswer(generated._2, evidence))
      yield checked

  def run: ZIO[Any, Throwable, Unit] =
    for
      retriever <- ZIO.fromEither(BoundedRetrieval.lexical(corpus))
      client <- ScriptedLlmClient.make(
        Chunk(searchTurn, TurnOutput.FinalText(Msg.assistant(expectedAnswer.toJson))),
        maxRecordedRequests = 2
      )
      answer <- exercise(client, retriever)
      _      <- client.verifyConsumed
      _      <- Console.printLine(answer.toJson)
    yield ()

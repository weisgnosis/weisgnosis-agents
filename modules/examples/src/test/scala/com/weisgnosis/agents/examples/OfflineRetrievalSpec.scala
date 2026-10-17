/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.examples

import com.weisgnosis.agents.core.agent.ReActAgent
import com.weisgnosis.agents.core.client.{LlmConfig, ScriptedLlmClient, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{Msg, ToolResultState}
import com.weisgnosis.agents.core.tool.Toolkit
import com.weisgnosis.agents.examples.retrieval.*
import zio.*
import zio.json.*
import zio.test.*

object OfflineRetrievalSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("OfflineRetrievalSpec")(
    test("malformed answer Unicode is rejected without splitting a valid supplementary quote"):
      val source   = SourceExcerpt("unicode", "r1", "c1", "\uD83D\uDE80")
      val manifest = Chunk(RetrievedEvidence(source.citationId, source))
      val valid = EvidenceAnswer(
        "Rocket evidence",
        Chunk(EvidenceCitation(source.citationId, "\uD83D\uDE80"))
      )
      val splitQuote = valid.copy(citations = Chunk(EvidenceCitation(source.citationId, "\uD83D")))
      val malformedAnswer = valid.copy(answer = "\uDC80")
      assertTrue(
        BoundedRetrieval.validateAnswer(valid, manifest) == Right(valid),
        BoundedRetrieval.validateAnswer(splitQuote, manifest).isLeft,
        BoundedRetrieval.validateAnswer(malformedAnswer, manifest).isLeft
      )
    ,
    test("public tool observation reaches the next model request before typed citation validation"):
      for
        retriever <- ZIO.fromEither(BoundedRetrieval.lexical(OfflineRetrieval.corpus))
        client <- ScriptedLlmClient.make(
          Chunk(
            OfflineRetrieval.searchTurn,
            TurnOutput.FinalText(Msg.assistant(OfflineRetrieval.expectedAnswer.toJson))
          ),
          2
        )
        answer   <- OfflineRetrieval.exercise(client, retriever)
        requests <- client.requests
        _        <- client.verifyConsumed
        observations = requests.lift(1).toList.flatMap(_.input.history.flatMap(_.toolResults))
      yield assertTrue(
        answer == OfflineRetrieval.expectedAnswer,
        requests.size == 2,
        observations.exists(result =>
          result.id == "search-1" && result.outputText.contains("runtime:r1:cancellation")
        )
      )
    ,
    test("unknown citation and changed quote fail before display"):
      val source =
        OfflineRetrieval.corpus.take(1).map(value => RetrievedEvidence(value.citationId, value))
      val invalid = Chunk(
        EvidenceAnswer("claim", Chunk(EvidenceCitation("other:r1:chunk", "Cancellation"))),
        EvidenceAnswer(
          "claim",
          Chunk(EvidenceCitation("runtime:r1:cancellation", "Invented quote"))
        ),
        EvidenceAnswer("claim", Chunk.empty)
      )
      assertTrue(invalid.forall(value => BoundedRetrieval.validateAnswer(value, source).isLeft))
    ,
    test("host rejects fabricated citation after a real structured agent run"):
      for
        retriever <- ZIO.fromEither(BoundedRetrieval.lexical(OfflineRetrieval.corpus))
        client <- ScriptedLlmClient.make(
          Chunk(
            OfflineRetrieval.searchTurn,
            TurnOutput.FinalText(
              Msg.assistant(
                EvidenceAnswer("claim", Chunk(EvidenceCitation("unknown:r1:chunk", "quote"))).toJson
              )
            )
          ),
          2
        )
        result <- OfflineRetrieval.exercise(client, retriever).either
      yield assertTrue(result.left.exists(_.isInstanceOf[AgentError.DeserializationError]))
    ,
    test("empty retrieval succeeds, ranking is stable and truncation is explicit"):
      ZIO.scoped:
        for
          retriever <- ZIO.fromEither(BoundedRetrieval.lexical(OfflineRetrieval.corpus))
          empty     <- retriever.retrieve(SearchInput("absent-term", 1))
          first     <- retriever.retrieve(SearchInput("tool", 1))
          again     <- retriever.retrieve(SearchInput("tool", 1))
        yield assertTrue(
          empty.evidence.isEmpty,
          empty.status == RetrievalStatus.Complete,
          first == again,
          first.evidence.size == 1,
          first.status == RetrievalStatus.Truncated
        )
    ,
    test("candidate bounds apply before deduplication and UTF-8 limits count bytes"):
      val source = SourceExcerpt("doc", "r1", "chunk", "éé")
      val invalidCorpora = Chunk(
        BoundedRetrieval.lexical(Chunk(source, source), RetrievalLimits(maximumCandidates = 1)),
        BoundedRetrieval.lexical(Chunk(source, source)),
        BoundedRetrieval.lexical(Chunk(source), RetrievalLimits(maximumExcerptBytes = 3)),
        BoundedRetrieval.lexical(Chunk(source.copy(documentId = "../secret"))),
        BoundedRetrieval.lexical(Chunk(source), RetrievalLimits(maximumCandidates = 0))
      )
      ZIO.scoped:
        for
          retriever <- ZIO.fromEither(
            BoundedRetrieval.lexical(Chunk(source), RetrievalLimits(maximumQueryBytes = 3))
          )
          oversizedQuery <- retriever.retrieve(SearchInput("éé", 1)).either
          invalidCount   <- retriever.retrieve(SearchInput("é", 9)).either
          small <- ZIO.fromEither(
            BoundedRetrieval.lexical(Chunk(source), RetrievalLimits(maximumObservationBytes = 1))
          )
          oversizedObservation <- small.retrieve(SearchInput("é", 1)).either
        yield assertTrue(
          invalidCorpora.forall(_.isLeft),
          oversizedQuery.isLeft,
          invalidCount.isLeft,
          oversizedObservation.isLeft
        )
    ,
    test("malformed Unicode is rejected before source acceptance or query scanning"):
      val malformed     = Chunk("\uD800", "\uDC00")
      val supplementary = "\uD83D\uDE80"
      val source        = SourceExcerpt("unicode", "r1", "chunk", supplementary)
      val badSources =
        malformed.map(text => BoundedRetrieval.lexical(Chunk(source.copy(text = text))))
      ZIO.scoped:
        for
          retriever <- ZIO.fromEither(
            BoundedRetrieval.lexical(
              Chunk(source),
              RetrievalLimits(maximumExcerptBytes = 4, maximumQueryBytes = 4)
            )
          )
          badQueries <- ZIO.foreach(malformed)(query =>
            retriever.retrieve(SearchInput(query, 1)).either
          )
          valid <- retriever.retrieve(SearchInput(supplementary, 1))
        yield assertTrue(
          badSources.forall(_.isLeft),
          badQueries.forall(_.isLeft),
          valid.evidence.map(_.source.text) == Chunk(supplementary),
          BoundedRetrieval.lexical(Chunk(source), RetrievalLimits(maximumExcerptBytes = 3)).isLeft
        )
    ,
    test("backend failure is an Error observation and cannot retain earlier evidence"):
      ZIO.scoped:
        for
          manifest <- Ref.make(
            Chunk(RetrievedEvidence("old:r1:c1", SourceExcerpt("old", "r1", "c1", "old")))
          )
          retriever = new Retriever:
            def retrieve(input: SearchInput): ZIO[Scope, AgentError, RetrievalBatch] =
              ZIO.fail(
                AgentError.ToolExecutionError("search_documents", s"Unavailable for ${input.query}")
              )
          toolkit <- ZIO.fromEither(
            Toolkit.empty.register(BoundedRetrieval.tool(retriever, manifest))
          )
          client <- ScriptedLlmClient.make(
            Chunk(
              OfflineRetrieval.searchTurn,
              TurnOutput.FinalText(Msg.assistant("Search unavailable"))
            ),
            2
          )
          agent <- ReActAgent.make(
            toolkit = toolkit,
            client = client,
            llmConfig = LlmConfig("offline-script")
          )
          _        <- agent.run(Msg.user("Search"))
          history  <- agent.history
          evidence <- manifest.get
        yield assertTrue(
          evidence.isEmpty,
          history.flatMap(_.toolResults).exists(_.state == ToolResultState.Error)
        )
    ,
    test("lookup starts after persistence and cancellation joins owned cleanup"):
      ZIO.scoped:
        for
          ready    <- Promise.make[Nothing, ReActAgent]
          entered  <- Promise.make[Nothing, Boolean]
          cleaning <- Promise.make[Nothing, Unit]
          release  <- Promise.make[Nothing, Unit]
          cleaned  <- Ref.make(false)
          manifest <- Ref.make(Chunk.empty[RetrievedEvidence])
          retriever = new Retriever:
            def retrieve(input: SearchInput): ZIO[Scope, AgentError, RetrievalBatch] =
              ZIO.acquireRelease(
                ready.await
                  .flatMap(_.history)
                  .flatMap(history =>
                    entered.succeed(
                      input.query.nonEmpty && history.flatMap(_.toolUses).exists(_.id == "search-1")
                    )
                  )
              )(_ => cleaning.succeed(()) *> release.await *> cleaned.set(true)) *> ZIO.never
          toolkit <- ZIO.fromEither(
            Toolkit.empty.register(BoundedRetrieval.tool(retriever, manifest))
          )
          client <- ScriptedLlmClient.make(Chunk(OfflineRetrieval.searchTurn), 1)
          agent <- ReActAgent.make(
            toolkit = toolkit,
            client = client,
            llmConfig = LlmConfig("offline-script")
          )
          _          <- ready.succeed(agent)
          controller <- ZIO.fiberId
          running <- ZIO.uninterruptibleMask: restore =>
            for
              fiber <- restore(agent.run(Msg.user("Search"))).forkScoped
              _     <- ZIO.addFinalizer(release.succeed(()).unit)
            yield fiber
          persisted       <- entered.await
          _               <- running.interruptAsFork(controller)
          _               <- cleaning.await
          held            <- running.poll
          _               <- release.succeed(())
          exit            <- running.await
          cleanupComplete <- cleaned.get
          history         <- agent.history
        yield assertTrue(
          persisted,
          held.isEmpty,
          cleanupComplete,
          exit.causeOption.exists(_.interruptors.contains(controller)),
          history.flatMap(_.toolResults).count(_.id == "search-1") == 1,
          history.flatMap(_.toolResults).exists(_.state == ToolResultState.Error),
          history.count(
            _.text == "I noticed that you have interrupted me. What can I do for you?"
          ) == 1
        )
  )

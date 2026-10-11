/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.examples

import com.weisgnosis.agents.core.agent.ReActAgent
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.*
import com.weisgnosis.agents.harness.eviction.*
import zio.*
import zio.json.ast.Json
import zio.test.*

object LiveContextEvidenceSpec extends ZIOSpecDefault:
  private def exactFact(history: Chunk[Msg]): String =
    "EARLIER_FACT=([0-9a-f-]+)".r
      .findFirstMatchIn(history.map(_.text).mkString("\n"))
      .fold("missing")(_.group(1))
  private def toolCall(id: String, name: String, input: Json): TurnOutput =
    TurnOutput.ToolCalls(Msg.assistant(Chunk(ContentBlock.ToolUse(id, name, input))))

  private def scripted(truncatedAt: Option[Int]): UIO[LlmClient] =
    Ref
      .make(0)
      .map(calls =>
        LlmClient.mockEffectful((history, _, _) =>
          calls
            .updateAndGet(_ + 1)
            .map:
              case 1 => toolCall("lookup", "large_evidence", Json.Obj())
              case 2 =>
                val path = history
                  .flatMap(_.toolResults)
                  .flatMap(_.metadata.get("eviction_path").flatMap(_.asString))
                  .headOption
                  .getOrElse("missing")
                toolCall("retrieve", "read_file", Json.Obj("path" -> Json.Str(path)))
              case 3 =>
                val text = history
                  .flatMap(_.toolResults)
                  .find(_.name.contains("read_file"))
                  .map(_.text)
                  .getOrElse("")
                val fact =
                  "MIDDLE_FACT=([0-9a-f-]+)".r.findFirstMatchIn(text).fold("missing")(_.group(1))
                if truncatedAt.contains(3) then TurnOutput.Truncated(Msg.assistant(fact))
                else TurnOutput.FinalText(Msg.assistant(fact))
              case 4 => TurnOutput.FinalText(Msg.assistant(s"EARLIER_FACT=${exactFact(history)}"))
              case _ =>
                if truncatedAt.contains(5) then
                  TurnOutput.Truncated(Msg.assistant(exactFact(history)))
                else TurnOutput.FinalText(Msg.assistant(exactFact(history)))
        )
      )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("LiveContextEvidenceSpec")(
    test(
      "public assembly retrieves exact physical middle evidence and explicitly merges a prior summary"
    ):
      for
        client  <- scripted(None)
        receipt <- LiveContextEvidence.exercise(client, LlmConfig("offline"))
      yield assertTrue(
        receipt.scenarioAccepted,
        receipt.model == "offline",
        receipt.requests == 5,
        receipt.retrievals == 1,
        receipt.payloadHash.length == 64,
        receipt.storageVerified,
        receipt.previewVerified,
        receipt.summaryVerified,
        receipt.recallVerified,
        receipt.pairingVerified,
        receipt.provenanceVerified,
        receipt.directoryRemoved
      )
    ,
    test("typed storage failure preserves the entire observation in the next public model request"):
      val payload = "original-" * 10001
      for
        seen <- Ref.make(Chunk.empty[Chunk[Msg]])
        store = new ToolResultStore:
          def persist(key: String, content: String): IO[AgentError, String] =
            ZIO.fail(AgentError.StorageError("injected persistence failure"))
          def resolve(key: String): IO[AgentError, Option[StoredToolResult]] = ZIO.succeed(None)
        client = LlmClient.mockEffectful((history, _, _) =>
          seen
            .updateAndGet(_ :+ history)
            .map(observed =>
              if observed.size == 1 then toolCall("large", "lookup", Json.Obj())
              else TurnOutput.FinalText(Msg.assistant("done"))
            )
        )
        agent <- ReActAgent.make(
          client = client,
          toolkit = Toolkit.single(
            Tool.make(ToolDef("lookup", "Read", Json.Obj(), isReadOnly = true))(_ =>
              ZIO.succeed(payload)
            )
          ),
          toolResultProcessor = new ToolResultEvictor(store).processor(),
          historyPreparer = new ToolResultEvictor(store).preparer()
        )
        result   <- agent.run(Msg.user("Read"))
        requests <- seen.get
        next = requests.lift(1).toList.flatMap(_.flatMap(_.toolResults))
      yield assertTrue(
        requests.size == 2,
        next.exists(_.text == payload),
        result.history.flatMap(_.toolResults).exists(_.text == payload),
        next.forall(!_.metadata.contains("eviction_cache_key"))
      )
    ,
    test("a truncated exact fact cannot pass either live completion oracle"):
      ZIO
        .foreach(Chunk(3, 5))(index =>
          for
            client <- scripted(Some(index))
            result <- LiveContextEvidence.exercise(client, LlmConfig("offline")).either
          yield assertTrue(
            result == Left(
              AgentError.InvalidConfiguration(
                if index == 3 then
                  "Oversized storage, preview, retrieval or exact answer evidence failed"
                else "Explicit compaction or exact recall evidence failed"
              )
            )
          )
        )
        .map(results => results.foldLeft(assertTrue(true))(_ && _))
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(2.minutes)

/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.examples

import com.weisgnosis.agents.core.agent.{AgentConfig, AgentResult, ReActAgent}
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, ScriptedLlmClient, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolResultState}
import com.weisgnosis.agents.core.tool.{Tool, ToolAuthorizer, Toolkit, ToolSchema}
import com.weisgnosis.agents.harness.subagent.*
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Offline leader delegation using existing named sessions; no model credentials are required. */
object NamedSpecialistTeam extends ZIOAppDefault:
  final case class Evidence(
      firstReply: String,
      followupReply: String,
      participants: Chunk[String],
      parallelMembersStarted: Boolean,
      privateContextRetained: Boolean,
      privateObservationHidden: Boolean,
      summariesBounded: Boolean,
      omittedCharacters: Int,
      unknownMemberRejected: Boolean
  ) derives JsonCodec

  final private case class PrivateQuery(topic: String) derives JsonCodec, ToolSchema

  private val privateFinding: String = "PRIVATE-RESEARCH-OBSERVATION"

  private[examples] def assignment(
      id: String,
      member: String,
      prompt: String
  ): ContentBlock.ToolUse =
    ContentBlock.ToolUse(
      id,
      "agent_send",
      Json.Obj("agent_id" -> Json.Str(member), "prompt" -> Json.Str(prompt))
    )

  private[examples] def member(
      name: String,
      client: LlmClient,
      toolkit: Toolkit = Toolkit.empty
  ): IO[AgentError, SubagentEntry] =
    ZIO
      .fromEither(
        SubagentFactory.make(
          AgentConfig(name = name, maxIterations = 4),
          LlmConfig("scripted"),
          toolkit,
          SubagentToolBoundary(),
          SubagentToolBoundary(),
          _ => ZIO.succeed(Toolkit.empty),
          _ => ZIO.succeed(client),
          ToolAuthorizer.allow
        )
      )
      .map(SubagentEntry(name, s"Host-installed $name specialist", _))

  private[examples] def install(entries: Chunk[SubagentEntry]): ZIO[Scope, AgentError, Toolkit] =
    for
      supervisor <- TaskSupervisor.open[AgentResult]
      parent     <- SubagentSpawner.root(supervisor)
      admission <- SubagentSessionCatalog.open(
        parent,
        entries,
        maxSessions = 2,
        maxTrackedPerSession = 4
      )
      catalog <- admission match
        case TaskResourceAdmission.Accepted(resource) => ZIO.succeed(resource.value)
        case TaskResourceAdmission.Closed =>
          ZIO.fail(AgentError.InvalidConfiguration("Team owner closed during installation"))
      timeout <- ZIO.fromEither(SubagentSyncTimeout.fromDuration(30.seconds))
    yield SubagentTools.forSessionCatalog(catalog, SubagentExecutionPolicy.Synchronous(timeout))

  private def observations(history: Chunk[Msg]): IO[AgentError, Chunk[SubagentCatalogToolResult]] =
    ZIO.foreach(history.flatMap(_.toolResults))(result =>
      ZIO
        .fromEither(result.text.fromJson[SubagentCatalogToolResult])
        .mapError(AgentError.DeserializationError(_))
    )

  /** Each invocation owns fresh specialists; subsequent leader turns reuse those specialists. */
  def demonstrate: ZIO[Scope, AgentError, Evidence] =
    for
      researcherStarted <- Promise.make[Nothing, Unit]
      reviewerStarted   <- Promise.make[Nothing, Unit]
      privateTool = Tool.derived[PrivateQuery, String](
        "read_private_notes",
        "Read private research notes.",
        isReadOnly = true
      )(_ => ZIO.succeed(privateFinding))
      researcher <- ScriptedLlmClient.makeEffectful(
        Chunk(
          researcherStarted.succeed(()) *> reviewerStarted.await.as(
            TurnOutput.ToolCalls(
              Msg.assistant(
                Chunk(
                  ContentBlock.ToolUse(
                    "private-read",
                    "read_private_notes",
                    Json.Obj("topic" -> Json.Str("release"))
                  )
                )
              )
            )
          ),
          ZIO.succeed(
            TurnOutput.FinalText(Msg.assistant("Research summary: " + "evidence " * 600))
          ),
          ZIO.succeed(
            TurnOutput.FinalText(Msg.assistant("Research follow-up: include the migration note."))
          )
        ),
        maxRecordedRequests = 3
      )
      reviewer <- ScriptedLlmClient.makeEffectful(
        Chunk(
          reviewerStarted.succeed(()) *> researcherStarted.await.as(
            TurnOutput.FinalText(
              Msg.assistant("Review summary: require a passing migration check.")
            )
          )
        ),
        maxRecordedRequests = 1
      )
      researchEntry <- member("researcher", researcher, Toolkit.single(privateTool))
      reviewEntry   <- member("reviewer", reviewer)
      tools         <- install(Chunk(researchEntry, reviewEntry))
      leaderClient <- ScriptedLlmClient.make(
        Chunk(
          TurnOutput.ToolCalls(
            Msg.assistant(
              Chunk(
                assignment("research", "researcher", "Investigate the release."),
                assignment("review", "reviewer", "Review the release independently.")
              )
            )
          ),
          TurnOutput.FinalText(
            Msg.assistant("Release review collected from researcher and reviewer.")
          ),
          TurnOutput.ToolCalls(
            Msg.assistant(
              Chunk(
                assignment("follow-up", "researcher", "What should the migration note contain?")
              )
            )
          ),
          TurnOutput.FinalText(Msg.assistant("Add the migration note before release."))
        ),
        maxRecordedRequests = 4
      )
      leader <- ReActAgent.make(
        config = AgentConfig(name = "leader", maxIterations = 4),
        client = leaderClient,
        toolkit = tools
      )
      first            <- leader.run(Msg.user("Coordinate the release review."))
      followup         <- leader.run(Msg.user("Ask the researcher for a follow-up."))
      history          <- leader.history
      results          <- observations(history)
      researchRequests <- researcher.requests
      unknown <- tools.executeSafe(
        "agent_send",
        Json.Obj(
          "agent_id" -> Json.Str("unregistered"),
          "prompt"   -> Json.Str("Do not create an agent.")
        )
      )
      _ <- leaderClient.verifyConsumed *> researcher.verifyConsumed *> reviewer.verifyConsumed
      researchReady <- researcherStarted.isDone
      reviewReady   <- reviewerStarted.isDone
      summaries = results.collect {
        case SubagentCatalogToolResult.Agent(
              name,
              SubagentToolResult.Task(_, SubagentToolState.Completed(reply))
            ) =>
          (name, reply)
      }
    yield Evidence(
      first.reply.text,
      followup.reply.text,
      summaries.map(_._1),
      researchReady && reviewReady,
      researchRequests.lastOption.exists(
        _.input.history.flatMap(_.toolResults).exists(_.text.contains(privateFinding))
      ),
      !history.flatMap(_.toolResults).exists(_.text.contains(privateFinding)),
      summaries.forall(_._2.text.length <= SubagentTools.MaximumTextCharacters),
      summaries.map(_._2.omittedCharacters).sum,
      unknown.state == ToolResultState.Error
    )

  def run: ZIO[Any, Throwable, Unit] =
    ZIO.scoped(demonstrate.flatMap(evidence => Console.printLine(evidence.toJsonPretty)))

/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.core.coordination

import com.weisgnosis.agents.core.agent.{AgentResult, AgentRunState, PeerObservation, ReActAgent}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg}
import zio.*

/** Explicit host composition; stages retain their own histories, policies and execution authority. */
object Pipeline:
  final case class StageResult(agentId: String, name: String, result: AgentResult)
  final case class StageOutcome(
      agentId: String,
      name: String,
      outcome: Exit[AgentError, AgentResult]
  )

  /** Later stages receive only the previous completed reply as inert attributed User input. */
  def sequential(
      agents: Chunk[ReActAgent],
      input: Chunk[Msg]
  ): ZIO[Scope, AgentError, Chunk[StageResult]] =
    def loop(
        remaining: List[ReActAgent],
        messages: Chunk[Msg],
        completed: Chunk[StageResult]
    ): ZIO[Scope, AgentError, Chunk[StageResult]] = remaining match
      case Nil => ZIO.succeed(completed)
      case agent :: rest =>
        ZIO
          .scoped(agent.run(messages))
          .flatMap: result =>
            val observed = completed :+ StageResult(agent.agentId, agent.name, result)
            if result.state != AgentRunState.Completed || rest.isEmpty then ZIO.succeed(observed)
            else
              ZIO.fromEither(
                PeerObservation.validate(agent.agentId, agent.name, Chunk(result.reply))
              ) *>
                loop(
                  rest,
                  Chunk(
                    Msg.user(
                      Chunk(ContentBlock.PeerObservation(agent.agentId, agent.name, result.reply))
                    )
                  ),
                  observed
                )
    validate(agents) *> loop(agents.toList, input, Chunk.empty)

  /** Typed failures are collected; interruption and defects fail the operation after joining children. */
  def fanout(
      agents: Chunk[ReActAgent],
      input: Chunk[Msg],
      parallelism: Int
  ): ZIO[Scope, AgentError, Chunk[StageOutcome]] =
    validate(agents) *>
      ZIO
        .fail(AgentError.InvalidConfiguration("Pipeline parallelism must be between 1 and 64"))
        .unless(parallelism >= 1 && parallelism <= 64) *>
      ZIO
        .foreachPar(agents)(agent =>
          ZIO
            .scoped(agent.run(input))
            .exit
            .flatMap:
              case Exit.Failure(cause) if cause.isInterrupted || cause.defects.nonEmpty =>
                ZIO.refailCause(cause)
              case outcome => ZIO.succeed(StageOutcome(agent.agentId, agent.name, outcome))
        )
        .withParallelism(parallelism)

  private def validate(agents: Chunk[ReActAgent]): IO[AgentError, Unit] =
    ZIO
      .fail(AgentError.InvalidConfiguration("Pipeline requires 1–64 distinct agent runtimes"))
      .unless(
        agents.nonEmpty && agents.size <= 64 && agents.map(_.agentId).distinct.size == agents.size
      )
      .unit

/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.examples

import com.weisgnosis.agents.core.agent.{AgentSnapshot, ReActAgent}
import com.weisgnosis.agents.core.client.ModelTarget
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.tool.Toolkit
import zio.*
import zio.json.*

/** Example-owned routing policy; a checkpoint is data, never permission to construct a provider. */
object HostModelRouting:
  enum Task:
    case General, ReleaseReview

  enum Capability:
    case LocalTools

  enum CostTier:
    case Economy, Standard

  final case class Requirements(task: Task, capabilities: Set[Capability], maxCost: CostTier)

  /** The host changes the revision whenever model, protocol or replay compatibility changes. */
  final case class Binding(id: String, revision: String, replayCompatibilityKey: String)
      derives JsonCodec

  final case class Candidate(
      binding: Binding,
      target: ModelTarget,
      tasks: Set[Task],
      capabilities: Set[Capability],
      cost: CostTier
  ):
    def eligible(requirements: Requirements): Boolean =
      tasks.contains(requirements.task) && requirements.capabilities.subsetOf(capabilities) &&
        cost.ordinal <= requirements.maxCost.ordinal

  /** Persist this envelope atomically in authenticated host storage, not as an executable binding. */
  final case class Checkpoint(version: Int, binding: Binding, snapshot: AgentSnapshot)
      derives JsonCodec

  final class Runtime private[HostModelRouting] (
      val binding: Binding,
      val agent: ReActAgent
  ):
    def checkpoint: UIO[Checkpoint] = agent.snapshot.map(Checkpoint(1, binding, _))

  final class Registry private[HostModelRouting] (
      candidates: Chunk[Candidate],
      defaultId: String
  ):
    /** Explicit caller choice must satisfy the same eligibility checks as automatic selection. */
    def select(
        requirements: Requirements,
        explicitId: Option[String] = None
    ): Either[AgentError.InvalidConfiguration, Candidate] =
      val eligible = candidates.filter(_.eligible(requirements))
      val selected = explicitId match
        case Some(id) => eligible.find(_.binding.id == id)
        case None     => eligible.find(_.binding.id == defaultId).orElse(eligible.headOption)
      selected.toRight(invalid("No registered route satisfies the host requirements"))

    def open(
        requirements: Requirements,
        toolkit: Toolkit,
        explicitId: Option[String] = None
    ): IO[AgentError, Runtime] =
      ZIO.fromEither(select(requirements, explicitId)).flatMap(assemble(_, toolkit))

    /** Restore never selects again: changed defaults cannot redirect an unfinished logical reply. */
    def restore(
        checkpoint: Checkpoint,
        requirements: Requirements,
        toolkit: Toolkit
    ): IO[AgentError, Runtime] =
      for
        candidate <- ZIO.fromEither(resolve(checkpoint, requirements))
        runtime   <- assemble(candidate, toolkit)
        _         <- runtime.agent.restoreSnapshot(checkpoint.snapshot)
      yield runtime

    private def resolve(
        checkpoint: Checkpoint,
        requirements: Requirements
    ): Either[AgentError.InvalidConfiguration, Candidate] =
      for
        _ <- Either.cond(
          checkpoint.version == 1,
          (),
          invalid("Unsupported route checkpoint version")
        )
        candidate <- candidates
          .find(_.binding == checkpoint.binding)
          .toRight(invalid("Saved route is missing or its trusted binding has changed"))
        _ <- Either.cond(
          candidate.eligible(requirements),
          (),
          invalid("Saved route no longer satisfies the host requirements")
        )
      yield candidate

    private def assemble(candidate: Candidate, toolkit: Toolkit): IO[AgentError, Runtime] =
      ReActAgent
        .make(
          toolkit = toolkit,
          client = candidate.target.client,
          llmConfig = candidate.target.config
        )
        .map(new Runtime(candidate.binding, _))

  def registry(
      candidates: Chunk[Candidate],
      defaultId: String
  ): Either[AgentError.InvalidConfiguration, Registry] =
    val ids = candidates.map(_.binding.id)
    val valid = candidates.nonEmpty && ids.distinct.size == ids.size && ids.contains(defaultId) &&
      candidates.forall(candidate =>
        Chunk(
          candidate.binding.id,
          candidate.binding.revision,
          candidate.binding.replayCompatibilityKey
        )
          .forall(value =>
            value.nonEmpty && value.length <= 128 && value.forall(character =>
              character.isLetterOrDigit || "-_.:".contains(character)
            )
          ) && candidate.tasks.nonEmpty
      )
    Either.cond(
      valid,
      new Registry(candidates, defaultId),
      invalid(
        "Routes require unique bounded identifiers, revisions, replay keys, tasks and a registered default"
      )
    )

  private def invalid(message: String): AgentError.InvalidConfiguration =
    AgentError.InvalidConfiguration(message)

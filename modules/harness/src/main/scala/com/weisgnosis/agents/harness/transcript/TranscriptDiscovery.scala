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

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import java.nio.file.Path
import zio.*

/** Explicit source composition; membership and borrowed readers confer no writer or retirement authority. */
object TranscriptDiscovery:
  final case class Limits(maxSources: Int = 16, maxTargets: Int = 256)

  /** The host supplies an exact binding and read capability; discovery never opens or owns the backend. */
  final class Target private (
      val session: TranscriptSession,
      read: IO[AgentError, Chunk[TranscriptEntry]]
  ):
    def entries: IO[AgentError, Chunk[TranscriptEntry]] = ZIO.suspendSucceed(read)

  object Target:
    def apply(session: TranscriptSession)(read: IO[AgentError, Chunk[TranscriptEntry]]): Target =
      new Target(session, read)

  /** A source must bound its own acquisition/enumeration; returned readers remain borrowed after scope closure. */
  final class Source private (operation: ZIO[Scope, AgentError, Chunk[Target]]):
    private[transcript] def discover: ZIO[Scope, AgentError, Chunk[Target]] =
      ZIO.suspendSucceed(operation)

  object Source:
    def apply(operation: ZIO[Scope, AgentError, Chunk[Target]]): Source = new Source(operation)

    /** The explicit catalog is the enumeration boundary, not the backend's complete address space. */
    def catalog(
        catalog: TranscriptSessionCatalog,
        read: TranscriptSession => IO[AgentError, Chunk[TranscriptEntry]]
    ): Source = Source(
      ZIO
        .suspendSucceed(catalog.sessions)
        .map(_.map(session => Target(session)(ZIO.suspendSucceed(read(session)))))
    )

    /**
     * Selects existing local directories using the existing bounded/trusted discovery contract.
     * The host mapping must supply its actual reader and address; matching the basename is not
     * proof that the reader physically refers to that path. No competing writer lease is acquired.
     */
    def local(
        directory: Path,
        trust: WorkspaceTrust,
        bind: TranscriptTargetId => Either[AgentError, Target],
        limits: LocalTranscriptDiscoveryLimits = LocalTranscriptDiscoveryLimits()
    ): Source = Source(
      LocalTranscriptDiscovery
        .discover(directory, trust, limits)
        .flatMap(targets =>
          ZIO.foreach(targets)(target =>
            ZIO
              .fromEither(bind(target.id))
              .flatMap(bound =>
                ZIO
                  .succeed(bound)
                  .filterOrFail(_.session.id == target.id)(
                    invalid("Local transcript mapping must retain the selected basename identity")
                  )
              )
          )
        )
    )

  /**
   * Reads sources sequentially in caller order, closing each discovery scope before the next.
   * Counts every candidate before deduplication. Exact repeated bindings retain the first reader;
   * conflicting logical IDs or overlapping physical layouts fail the whole result. This reads no
   * transcript bodies and establishes neither a global snapshot nor a byte budget.
   */
  def discover(
      sources: Chunk[Source],
      limits: Limits = Limits()
  ): IO[AgentError, Chunk[Target]] =
    if limits.maxSources <= 0 || limits.maxTargets <= 0 then
      ZIO.fail(invalid("Transcript discovery limits must be positive"))
    else if sources.size > limits.maxSources then
      ZIO.fail(invalid("Transcript discovery source limit exceeded"))
    else
      ZIO
        .foldLeft(sources)((0L, Chunk.empty[Target])): (current, source) =>
          ZIO
            .scoped(source.discover)
            .flatMap: candidates =>
              val count = current._1 + candidates.size.toLong
              if count > limits.maxTargets.toLong then
                ZIO.fail(invalid("Transcript discovery candidate limit exceeded"))
              else
                ZIO
                  .fromEither(
                    candidates.foldLeft[Either[AgentError, Chunk[Target]]](Right(current._2)):
                      (result, candidate) =>
                        result.flatMap: accepted =>
                          if accepted.exists(_.session == candidate.session) then Right(accepted)
                          else if accepted.exists(previous =>
                              previous.session.id == candidate.session.id ||
                                VersionedTranscriptSessionCatalog
                                  .conflicts(previous.session, candidate.session)
                            )
                          then Left(invalid("Conflicting discovered transcript binding"))
                          else Right(accepted :+ candidate)
                  )
                  .map(count -> _)
        .map(_._2)

  private def invalid(message: String): AgentError.InvalidConfiguration =
    AgentError.InvalidConfiguration(message)

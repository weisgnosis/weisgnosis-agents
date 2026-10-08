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
package com.weisgnosis.agents.harness.subagent

import com.weisgnosis.agents.core.error.AgentError
import java.time.Instant
import zio.*

/** Explicit host-driven reconciliation; timestamps and worker identities never grant ownership. */
object SubagentTaskRecovery:
  final class Policy private (val staleAfter: Duration, val minGap: Duration)

  object Policy:
    def make(
        staleAfter: Duration = 10.minutes,
        minGap: Duration = 5.minutes
    ): Either[AgentError, Policy] =
      val maximum = Duration.fromNanos(Long.MaxValue)
      if staleAfter.isNegative || minGap.isNegative ||
        staleAfter.compareTo(maximum) >= 0 || minGap.compareTo(maximum) >= 0
      then
        Left(
          AgentError.InvalidConfiguration(
            "Recovery durations must be finite nonnegative nanoseconds"
          )
        )
      else Right(new Policy(staleAfter, minGap))

  enum Claim:
    case Admitted, Throttled

  trait Gate:
    def claim(policy: Policy): IO[AgentError, Claim]

  object Gate:
    /** A process-local throttle, not a storage lease or distributed claim. Backward time refuses. */
    def local: UIO[Gate] = Ref.Synchronized
      .make(Option.empty[Long])
      .map: previous =>
        new Gate:
          def claim(policy: Policy): IO[AgentError, Claim] = previous.modifyZIO: last =>
            Clock.nanoTime.map: now =>
              val eligible = last.forall: before =>
                now >= before && BigInt(now) - BigInt(before) >= BigInt(policy.minGap.toNanos)
              if eligible then (Claim.Admitted, Some(now)) else (Claim.Throttled, last)

  /**
   * Host assertion covering this registry's bound partition and exact record. This value alone
   * supplies no fencing: acquire the real host ownership resource in Authority.acquire's Scope.
   */
  final class Permit private (val expected: SubagentTaskRecord)

  object Permit:
    def local(expected: SubagentTaskRecord): Either[AgentError, Permit] =
      expected.state match
        case SubagentTaskRecordState.Pending | SubagentTaskRecordState.Running
            if expected.metadata.nonEmpty =>
          Right(new Permit(expected))
        case _ =>
          Left(AgentError.InvalidConfiguration("Recovery requires an admitted nonterminal record"))

  enum Decision:
    case Local(permit: Permit)
    case Remote, OwnerPresent, Unknown

  trait Authority:
    /**
     * The host must cover this exact registry's bound store partition, not another partition with
     * equal task data. Acquired resources remain owned through final conditional mutation/cleanup.
     * Do not reenter registry admission/close from ownership cleanup.
     */
    def acquire(
        registry: SubagentTaskRegistry,
        expected: SubagentTaskRecord
    ): ZIO[Scope, AgentError, Decision]

  enum Transition:
    case Reconciled(record: SubagentTaskRecord, publication: SubagentTerminalPublication)
    case Changed(record: SubagentTaskRecord)
    case Missing

  enum Disposition:
    case Transitioned(result: Transition)
    case RetainedLocal, Remote, OwnerPresent, Unknown

  final case class Receipt(id: SubagentTaskId, disposition: Disposition)

  enum Result:
    case Throttled
    case Swept(receipts: Chunk[Receipt])

  private[subagent] def candidates(
      records: Chunk[SubagentTaskRecord],
      policy: Policy,
      observedAt: Instant
  ): IO[AgentError, Chunk[SubagentTaskRecord]] =
    ZIO
      .attempt(observedAt.minus(policy.staleAfter))
      .mapError(error =>
        AgentError.InvalidConfiguration(
          s"Recovery cutoff is outside the supported time range: ${error.getMessage}"
        )
      )
      .map: cutoff =>
        records.filter: record =>
          record.terminal.isEmpty && record.metadata.exists(metadata =>
            !metadata.lastUpdatedAt.isAfter(cutoff)
          )

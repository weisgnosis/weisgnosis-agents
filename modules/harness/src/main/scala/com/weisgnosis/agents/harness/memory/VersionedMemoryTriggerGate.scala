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

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.harness.storage.*
import java.time.Instant
import zio.*
import zio.json.*

/** Persistent admission windows; the host owns clock consistency and the aggregate's lifetime. */
object VersionedMemoryTriggerGate:
  def make(
      store: VersionedJsonStore,
      address: StorageAddress,
      policy: MemoryTriggerPolicy = MemoryTriggerPolicy(),
      maxAttempts: Int = 8,
      now: UIO[Instant] = Clock.instant
  ): Either[AgentError.InvalidConfiguration, MemoryTriggerGate] =
    Either.cond(
      maxAttempts > 0,
      new Backend(store, address, policy, maxAttempts, now),
      AgentError.InvalidConfiguration("Memory gate publication attempts must be positive")
    )

  final private case class Reservation(
      operation: MemoryOperation,
      scope: MemoryScope,
      claimedAt: Instant
  ) derives JsonCodec
  final private case class Payload(
      schemaVersion: Int,
      policy: MemoryTriggerPolicy,
      reservations: Chunk[Reservation]
  ) derives JsonCodec
  final private case class Loaded(
      revision: ExpectedRevision,
      reservations: Chunk[Reservation]
  )

  final private class Backend(
      store: VersionedJsonStore,
      address: StorageAddress,
      policy: MemoryTriggerPolicy,
      maxAttempts: Int,
      now: UIO[Instant]
  ) extends MemoryTriggerGate:
    def claim(
        operation: MemoryOperation,
        scope: MemoryScope
    ): IO[AgentError, MemoryTriggerDecision] =
      trigger(policy, operation) match
        case MemoryTrigger.Always => ZIO.succeed(MemoryTriggerDecision.Admitted)
        case MemoryTrigger.Never  => ZIO.succeed(MemoryTriggerDecision.Disabled)
        case MemoryTrigger.Throttled(gap) =>
          val normalized = MemoryScope.normalized(scope)
          now.flatMap: instant =>
            modify(
              current =>
                val previous =
                  current.find(entry => entry.operation == operation && entry.scope == normalized)
                if previous.exists(entry => elapsed(instant, entry.claimedAt) < gap.nanos) then
                  MemoryTriggerDecision.Throttled -> current
                else
                  MemoryTriggerDecision.Admitted -> (
                    current.filterNot(entry =>
                      entry.operation == operation && entry.scope == normalized
                    ) :+
                      Reservation(operation, normalized, instant)
                  )
              ,
              maxAttempts
            )

    def cleanup(retention: MemoryInterval): IO[AgentError, Int] =
      now.flatMap: instant =>
        modify(
          current =>
            val retained = current.filter: entry =>
              val age = elapsed(instant, entry.claimedAt)
              val active = trigger(policy, entry.operation) match
                case MemoryTrigger.Throttled(gap) => age < gap.nanos
                case _                            => false
              active || age <= retention.nanos
            (current.size - retained.size) -> retained
          ,
          maxAttempts
        )

    private def read: IO[AgentError, Loaded] =
      store
        .read(address)
        .flatMap:
          case None => ZIO.succeed(Loaded(ExpectedRevision.Absent, Chunk.empty))
          case Some(record) =>
            val decoded = for
              validated <- JsonBoundary.validate(record.value)
              _ <- Either.cond(JsonBoundary.validUnicode(validated), (), "Malformed gate Unicode")
              payload   <- JsonBoundary.render(validated).fromJson[Payload]
              canonical <- JsonBoundary.decode(payload.toJson)
              _ <- Either.cond(
                payload.schemaVersion == 1 && payload.policy == policy &&
                  JsonBoundary.sameRepresentation(validated, canonical) &&
                  payload.reservations
                    .map(entry => entry.operation -> entry.scope)
                    .distinct
                    .size == payload.reservations.size &&
                  payload.reservations.forall(entry =>
                    entry.scope == MemoryScope.normalized(entry.scope) &&
                      (trigger(policy, entry.operation) match
                        case MemoryTrigger.Throttled(_) => true
                        case _                          => false)
                  ),
                (),
                "Invalid gate schema, reservations or policy"
              )
            yield Loaded(ExpectedRevision.AtRevision(record.revision), payload.reservations)
            ZIO.fromEither(
              decoded.left.map(_ => AgentError.MemoryStorageError("Invalid persisted memory gate"))
            )

    private def modify[A](
        transition: Chunk[Reservation] => (A, Chunk[Reservation]),
        attempts: Int
    ): IO[AgentError, A] =
      read.flatMap: current =>
        val (result, next) = transition(current.reservations)
        if next == current.reservations then ZIO.succeed(result)
        else
          val encoded = for
            value <- JsonBoundary.decode(Payload(1, policy, next).toJson)
            _     <- Either.cond(JsonBoundary.validUnicode(value), (), "Malformed gate Unicode")
          yield value
          ZIO
            .fromEither(
              encoded.left.map(_ =>
                AgentError.MemoryStorageError("Invalid memory gate publication")
              )
            )
            .flatMap(value => store.compareAndSet(address, current.revision, value))
            .flatMap:
              case ConditionalWrite.Applied(_) => ZIO.succeed(result)
              case ConditionalWrite.Conflict if attempts > 1 =>
                ZIO.yieldNow *> ZIO.suspendSucceed(modify(transition, attempts - 1))
              case ConditionalWrite.Conflict =>
                ZIO.fail(AgentError.MemoryStorageError("Memory gate conflict retry limit exceeded"))

  private def trigger(policy: MemoryTriggerPolicy, operation: MemoryOperation): MemoryTrigger =
    operation match
      case MemoryOperation.Flush       => policy.flush
      case MemoryOperation.Maintenance => policy.maintenance

  private def elapsed(now: Instant, previous: Instant): BigInt =
    (BigInt(now.getEpochSecond) - BigInt(previous.getEpochSecond)) * 1000000000 +
      now.getNano - previous.getNano

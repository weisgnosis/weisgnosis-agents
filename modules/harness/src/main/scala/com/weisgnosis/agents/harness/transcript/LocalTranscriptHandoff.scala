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
import com.weisgnosis.agents.harness.storage.{WorkspaceLease, WorkspaceOwner}
import java.nio.file.Path
import zio.*

/** Trusted host fencing of the exact remote binding and local destination, not inferred ownership. */
trait TranscriptHandoffAuthority:
  def acquire(directory: Path, binding: TranscriptSession): ZIO[Scope, AgentError, Unit]

enum TranscriptHandoffResult:
  case Imported, LocalRetained

/**
 * Explicit cold import and branch-preserving reconciliation. Each branch retains its exact linear
 * chain; union order is observational, never fabricated parentage or runtime restoration.
 * The remote reader is borrowed, host-bound to binding, and never receives writes. Authority and
 * reader callbacks must not reenter this owner/store or close its scope; admitted operations hold
 * the local lease until their cleanup joins. Hosts bound callback lifetime and cross-owner cycles.
 */
final class LocalTranscriptHandoff private (
    val store: TranscriptStore,
    files: TranscriptFiles,
    lease: WorkspaceLease,
    binding: TranscriptSession,
    readRemote: ZIO[Scope, AgentError, Chunk[TranscriptEntry]],
    authority: TranscriptHandoffAuthority
):
  import LocalTranscriptHandoff.Mode

  /** Host authority spans remote cleanup, fresh local inspection, atomic import and joined release. */
  def sync: IO[AgentError, TranscriptHandoffResult] = handoff(Mode.Sync)

  /** Latest remote branch first, then retained branches and local-only IDs; not an append chain. */
  def recoveredView: IO[AgentError, Chunk[TranscriptEntry]] =
    lease.protect(
      files.read.flatMap(text => ZIO.fromEither(TranscriptLog.decodeDocument(text))).map(_.entries)
    )

  private[transcript] def load: IO[AgentError, Unit] =
    lease
      .protect(files.readExisting)
      .flatMap:
        case Some(text) => ZIO.fromEither(TranscriptLog.decodeDocument(text)).unit
        case None       => handoff(Mode.Cold).unit

  private def handoff(mode: Mode): IO[AgentError, TranscriptHandoffResult] =
    ZIO.checkInterruptible: status =>
      lease.protect:
        ZIO.scoped:
          for
            _ <- callback(ZIO.suspendSucceed(authority.acquire(files.directory, binding)), status)
            remote  <- callback(ZIO.scoped(ZIO.suspendSucceed(readRemote)), status)
            checked <- ZIO.fromEither(TranscriptState.restore(remote))
            local   <- files.readExisting
            result <- local match
              case Some(text) if mode == Mode.Cold =>
                ZIO
                  .fromEither(TranscriptLog.decodeDocument(text))
                  .as(TranscriptHandoffResult.LocalRetained)
              case _ =>
                for
                  current <- ZIO.fromEither(TranscriptLog.decodeDocument(local.getOrElse("")))
                  next    <- ZIO.fromEither(TranscriptLog.reconcile(current, checked))
                  result <-
                    if local.isEmpty || next != current then
                      ZIO
                        .fromEither(TranscriptLog.encodeHandoff(next))
                        .flatMap(files.write)
                        .as(TranscriptHandoffResult.Imported)
                    else ZIO.succeed(TranscriptHandoffResult.LocalRetained)
                yield result
          yield result

  private def callback[A](
      effect: ZIO[Scope, AgentError, A],
      status: InterruptStatus
  ): ZIO[Scope, AgentError, A] =
    if status.isInterruptible then effect.interruptible else effect.uninterruptible

object LocalTranscriptHandoff:
  private enum Mode:
    case Cold, Sync

  /**
   * The directory must already exist and be host-controlled. Existing local data is authoritative
   * for load; only explicit sync contacts remote afterward. No background mirror or replay occurs.
   */
  def open(
      directory: Path,
      trust: WorkspaceTrust,
      binding: TranscriptSession,
      readRemote: ZIO[Scope, AgentError, Chunk[TranscriptEntry]],
      authority: TranscriptHandoffAuthority
  ): ZIO[Scope, AgentError, LocalTranscriptHandoff] =
    WorkspaceLease.initialize:
      for
        files <- TranscriptFiles.open(directory, trust)
        lease <- WorkspaceLease.open(files.directory, WorkspaceOwner.Transcript)
        owner = new LocalTranscriptHandoff(
          LocalTranscriptStore.owned(files, lease),
          files,
          lease,
          binding,
          readRemote,
          authority
        )
        _ <- owner.load
      yield owner

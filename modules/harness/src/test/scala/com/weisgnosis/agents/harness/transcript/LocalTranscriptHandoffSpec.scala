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
import com.weisgnosis.agents.core.model.Role
import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import com.weisgnosis.agents.harness.memory.MemoryTestSupport.{children, temporaryDirectory}
import com.weisgnosis.agents.harness.storage.StorageAddress
import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.FileTime
import java.time.Instant
import zio.*
import zio.test.*

object LocalTranscriptHandoffSpec extends ZIOSpecDefault:
  private def draft(value: String): TranscriptDraft = TranscriptDraft(
    Some(TranscriptKey.Message(value)),
    TranscriptPayload.Message(Role.User, value, Chunk(TranscriptBlockType.Text))
  )

  private def binding: IO[AgentError, TranscriptSession] =
    ZIO.fromEither:
      for
        address <- StorageAddress.from(Chunk("remote-transcripts"), "session")
        id      <- TranscriptTargetId.fromString("session")
        session <- TranscriptSession.make(id, address, TranscriptLayout.Aggregate)
      yield session

  private enum InitialHistory:
    case Empty, Nonempty

  private def prefixContract(initial: InitialHistory): ZIO[Scope, Throwable, TestResult] =
    for
      root    <- temporaryDirectory
      session <- binding
      remote  <- TranscriptStore.inMemory
      _ <- remote.append(initial match
        case InitialHistory.Empty    => Chunk.empty
        case InitialHistory.Nonempty => Chunk(draft("first")))
      original <- remote.entries
      snapshot <- Ref.make(original)
      reads    <- Ref.make(0)
      grants   <- Ref.make(Chunk.empty[(Path, TranscriptSession)])
      released <- Ref.make(0)
      authority = new TranscriptHandoffAuthority:
        def acquire(directory: Path, selected: TranscriptSession): ZIO[Scope, AgentError, Unit] =
          ZIO.acquireRelease(grants.update(_ :+ (directory -> selected)))(_ =>
            released.update(_ + 1)
          )
      reader = reads.update(_ + 1) *> snapshot.get
      cold <- ZIO.scoped:
        LocalTranscriptHandoff
          .open(root, WorkspaceTrust.HostControlled, session, reader, authority)
          .flatMap(_.store.entries)
      exists    <- ZIO.attemptBlocking(Files.isRegularFile(root.resolve("transcript.jsonl")))
      saved     <- ZIO.attemptBlocking(Files.readString(root.resolve("transcript.jsonl")))
      canonical <- ZIO.fromEither(TranscriptState.restore(original).flatMap(TranscriptLog.encode))
      reopened <- ZIO.scoped:
        LocalTranscriptHandoff
          .open(
            root,
            WorkspaceTrust.HostControlled,
            session,
            ZIO.fail(AgentError.StorageError("Existing local load must not read remote")),
            authority
          )
          .flatMap(_.store.entries)
      result <- ZIO.scoped:
        for
          owner <- LocalTranscriptHandoff.open(
            root,
            WorkspaceTrust.HostControlled,
            session,
            reader,
            authority
          )
          _               <- remote.append(Chunk(draft("remote-ahead")))
          ahead           <- remote.entries
          _               <- snapshot.set(ahead)
          extended        <- owner.sync
          extendedEntries <- owner.store.entries
          _ <- new SessionTranscriptWriter(owner.store).appendMessages(
            Chunk(
              com.weisgnosis.agents.core.model.Msg.user(
                Chunk(com.weisgnosis.agents.core.model.ContentBlock.Text("local-only")),
                id = Some("local-only")
              )
            )
          )
          localAhead      <- owner.store.entries
          retained        <- owner.sync
          retainedEntries <- owner.store.entries
          beforeConflict  <- ZIO.attemptBlocking(Files.readString(root.resolve("transcript.jsonl")))
          _               <- remote.append(Chunk(draft("remote-divergence")))
          rawDivergent    <- remote.entries
          divergent = rawDivergent.map(entry =>
            if entry.key.contains(TranscriptKey.Message("remote-divergence")) then
              entry.copy(recordedAt = Instant.parse("2040-01-01T00:00:00Z"))
            else entry
          )
          _               <- snapshot.set(divergent)
          merged          <- owner.sync
          union           <- owner.recoveredView
          selected        <- owner.store.entries
          afterConflict   <- ZIO.attemptBlocking(Files.readString(root.resolve("transcript.jsonl")))
          unchangedRemote <- remote.entries
          expectedUnion = divergent ++ localAhead.filterNot(entry =>
            divergent.exists(_.id == entry.id)
          )
          _       <- snapshot.set(original)
          _       <- owner.sync
          shorter <- owner.recoveredView
          expectedShorter = original ++ expectedUnion.filterNot(entry =>
            original.exists(_.id == entry.id)
          )
          beforeIdentity <- ZIO.attemptBlocking(Files.readString(root.resolve("transcript.jsonl")))
          // Same identity with a changed payload is not a common prefix.
          changed = ahead.map(entry =>
            entry.copy(payload =
              TranscriptPayload.Message(
                Role.User,
                "forged",
                Chunk(TranscriptBlockType.Text)
              )
            )
          )
          _             <- snapshot.set(changed)
          idConflict    <- owner.sync.either
          afterIdentity <- owner.store.entries
          identityBytes <- ZIO.attemptBlocking(Files.readString(root.resolve("transcript.jsonl")))
        yield (
          assertTrue(
            extended == TranscriptHandoffResult.Imported && extendedEntries == ahead,
            retained == TranscriptHandoffResult.LocalRetained && retainedEntries == localAhead,
            merged == TranscriptHandoffResult.Imported && beforeConflict != afterConflict,
            unchangedRemote == rawDivergent && union == expectedUnion && selected == localAhead,
            shorter == expectedShorter,
            idConflict.isLeft && afterIdentity == localAhead && beforeIdentity == identityBytes
          ),
          localAhead,
          expectedShorter
        )
      continued <- ZIO.scoped:
        for
          normal  <- LocalTranscriptStore.open(root, WorkspaceTrust.HostControlled)
          _       <- normal.append(Chunk(draft("fresh-continuation")))
          entries <- normal.entries
        yield entries
      freshView <- ZIO.scoped:
        LocalTranscriptHandoff
          .open(
            root,
            WorkspaceTrust.HostControlled,
            session,
            ZIO.fail(AgentError.StorageError("Recovered local load must not contact remote")),
            authority
          )
          .flatMap(_.recoveredView)
      _ <- ZIO.attemptBlocking(
        Files.setLastModifiedTime(root.resolve("transcript.jsonl"), FileTime.from(Instant.EPOCH))
      )
      retainedArchive <- LocalTranscriptRetention.prune(
        root,
        WorkspaceTrust.HostControlled,
        Instant.parse("2039-01-01T00:00:00Z")
      )
      count         <- reads.get
      grantValues   <- grants.get
      releaseCount  <- released.get
      canonicalRoot <- ZIO.attemptBlocking(root.toRealPath())
      names         <- children(root)
    yield result._1 && assertTrue(
      cold == original && reopened == original && exists && saved == canonical,
      count == 6 && grantValues.size == count && releaseCount == count,
      continued.startsWith(result._2) && continued.size == result._2.size + 1,
      freshView == result._3 ++ continued.drop(result._2.size),
      retainedArchive == TranscriptPruneResult.Retained(Instant.parse("2040-01-01T00:00:00Z")),
      grantValues.forall(_ == (canonicalRoot -> session)),
      names == Set("transcript.jsonl")
    )

  private enum OversizedImport:
    case Cold, Prefix

  private def oversizedImport(mode: OversizedImport): ZIO[Scope, Throwable, TestResult] =
    for
      root       <- temporaryDirectory
      sourceRoot <- temporaryDirectory
      session    <- binding
      // Existing ordinary local history accepts this valid linear chain; remote admission has its
      // own finite publication bound and must not retrofit that limit onto legacy local writers.
      remote <- LocalTranscriptStore.open(sourceRoot, WorkspaceTrust.HostControlled)
      _      <- remote.append(Chunk(draft("shared-prefix")))
      prefix <- remote.entries
      authority = new TranscriptHandoffAuthority:
        def acquire(directory: Path, selected: TranscriptSession): ZIO[Scope, AgentError, Unit] =
          ZIO.unit
      _ <- mode match
        case OversizedImport.Cold => ZIO.unit
        case OversizedImport.Prefix =>
          ZIO
            .scoped(
              LocalTranscriptHandoff
                .open(root, WorkspaceTrust.HostControlled, session, ZIO.succeed(prefix), authority)
            )
            .unit
      before <- ZIO.attemptBlocking:
        val target = root.resolve("transcript.jsonl")
        if Files.exists(target) then Some(Files.readString(target)) else None
      huge = TranscriptDraft(
        Some(TranscriptKey.Message("large")),
        TranscriptPayload.Message(Role.User, "x" * 1048576, Chunk(TranscriptBlockType.Text))
      )
      _            <- remote.append(Chunk(huge))
      full         <- remote.entries
      encoded      <- ZIO.fromEither(TranscriptState.restore(full).flatMap(TranscriptLog.encode))
      sourceBefore <- ZIO.attemptBlocking(Files.readString(sourceRoot.resolve("transcript.jsonl")))
      result <- ZIO
        .scoped(
          LocalTranscriptHandoff
            .open(root, WorkspaceTrust.HostControlled, session, ZIO.succeed(full), authority)
            .flatMap: owner =>
              mode match
                case OversizedImport.Cold   => ZIO.unit
                case OversizedImport.Prefix => owner.sync.unit
        )
        .either
      after <- ZIO.attemptBlocking:
        val target = root.resolve("transcript.jsonl")
        if Files.exists(target) then Some(Files.readString(target)) else None
      sourceAfter <- ZIO.attemptBlocking(Files.readString(sourceRoot.resolve("transcript.jsonl")))
      names       <- children(root)
      ordinaryAccepted = encoded
        .getBytes(StandardCharsets.UTF_8)
        .length > 1048576 && sourceBefore == encoded
      unchanged = after == before && sourceAfter == sourceBefore
    yield assertTrue(
      ordinaryAccepted,
      full.size == 2,
      result.isLeft,
      unchanged,
      names == before.fold(Set.empty[String])(_ => Set("transcript.jsonl"))
    )

  private enum InterruptedRead:
    case Failed, Cancelled

  private def failedRead(mode: InterruptedRead): ZIO[Scope, Throwable, TestResult] =
    for
      root     <- temporaryDirectory
      session  <- binding
      entered  <- Promise.make[Nothing, Unit]
      cleaning <- Promise.make[Nothing, Unit]
      release  <- Promise.make[Nothing, Unit]
      released <- Ref.make(false)
      failure = AgentError.StorageError("Remote handoff read failed")
      cleanup = new IllegalStateException("Remote handoff cleanup")
      authority = new TranscriptHandoffAuthority:
        def acquire(directory: Path, selected: TranscriptSession): ZIO[Scope, AgentError, Unit] =
          ZIO.acquireRelease(ZIO.unit)(_ => released.set(true))
      reader = ZIO.addFinalizer(cleaning.succeed(()) *> release.await *> ZIO.die(cleanup)) *>
        entered.succeed(()) *> (mode match
          case InterruptedRead.Failed    => ZIO.fail(failure)
          case InterruptedRead.Cancelled => ZIO.never)
      active <- ZIO.uninterruptibleMask: restore =>
        for
          fiber <- restore(
            ZIO.scoped(
              LocalTranscriptHandoff.open(
                root,
                WorkspaceTrust.HostControlled,
                session,
                reader,
                authority
              )
            )
          ).forkScoped
          _ <- ZIO.addFinalizer(release.succeed(()).unit)
        yield fiber
      _          <- entered.await
      controller <- ZIO.fiberId
      _          <- active.interruptAsFork(controller).when(mode == InterruptedRead.Cancelled)
      _          <- cleaning.await
      held       <- active.poll
      heldNames  <- children(root)
      grantReleasedEarly <- released.get
      _                  <- release.succeed(())
      exit               <- active.await
      names              <- children(root)
      grantReleased      <- released.get
      // Failed initialization promptly relinquishes the lease in the same live host scope.
      retry <- ZIO.scoped(
        LocalTranscriptStore.open(root, WorkspaceTrust.HostControlled).flatMap(_.entries)
      )
    yield assertTrue(
      held.isEmpty && !grantReleasedEarly && heldNames == Set(".transcript-writer.lease"),
      names.isEmpty && grantReleased && retry.isEmpty,
      exit.causeOption.exists(cause =>
        cause.defects == List(cleanup) && (mode match
          case InterruptedRead.Failed    => cause.failures == List(failure)
          case InterruptedRead.Cancelled => cause.interruptors.contains(controller))
      )
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("LocalTranscriptHandoffSpec")(
    test(
      "cold import and durable branch union preserve exact history and ordinary local continuation"
    ):
      ZIO
        .foreach(Chunk(InitialHistory.Nonempty, InitialHistory.Empty))(prefixContract)
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test(
      "oversized linear remote imports fail for cold and prefix publication without changing either source"
    ):
      ZIO
        .foreach(Chunk(OversizedImport.Cold, OversizedImport.Prefix))(oversizedImport)
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test("failed or cancelled cold readers join cleanup and release ownership without importing"):
      ZIO
        .foreach(Chunk(InterruptedRead.Failed, InterruptedRead.Cancelled))(failedRead)
        .map(_.foldLeft(assertTrue(true))(_ && _))
  ) @@ TestAspect.timeout(20.seconds)

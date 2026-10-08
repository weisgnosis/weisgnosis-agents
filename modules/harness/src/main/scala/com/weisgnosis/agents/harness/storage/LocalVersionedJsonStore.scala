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
package com.weisgnosis.agents.harness.storage

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.tool.PathSafety
import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{
  Files,
  LinkOption,
  NoSuchFileException,
  Path,
  StandardCopyOption,
  StandardOpenOption
}
import java.nio.file.attribute.BasicFileAttributes
import zio.*
import zio.json.*
import zio.json.ast.Json

/**
 * One explicitly bound file/address under cooperative host ownership. Atomic replacement provides
 * visibility, not fsync or crash recovery. External replacement/deletion invalidates revisions;
 * abandoned leases require explicit host recovery. Keep this scope alive through borrowed users.
 */
object LocalVersionedJsonStore:
  final class Limits private (val maxBytes: Int)
  object Limits:
    val default: Limits = new Limits(2 * 1024 * 1024)
    def make(maxBytes: Int): Either[AgentError.InvalidConfiguration, Limits] =
      Either.cond(
        maxBytes > 0 && maxBytes < Int.MaxValue,
        new Limits(maxBytes),
        AgentError.InvalidConfiguration(
          "Local JSON byte limit must be positive and leave room for one overflow byte"
        )
      )

  final private case class Envelope(
      formatVersion: Int,
      address: StorageAddress,
      record: VersionedJson
  ) derives JsonCodec

  /** The parent directory must already exist; names and partitions are never inferred. */
  def open(
      path: Path,
      address: StorageAddress,
      trust: WorkspaceTrust,
      limits: Limits = Limits.default
  ): ZIO[Scope, AgentError, VersionedJsonStore] =
    openWith(path, address, trust, limits)((temporary, target) =>
      io(
        Files.move(
          temporary,
          target,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING
        )
      ).unit
    )

  private[storage] def openWith(
      path: Path,
      address: StorageAddress,
      trust: WorkspaceTrust,
      limits: Limits
  )(
      atomicReplace: (Path, Path) => IO[AgentError, Unit]
  ): ZIO[Scope, AgentError, VersionedJsonStore] =
    trust match
      case WorkspaceTrust.Untrusted =>
        deny("Untrusted local JSON storage requires a secure platform backend")
      case WorkspaceTrust.HostControlled =>
        WorkspaceLease.initialize:
          for
            absolute <- io(path.toAbsolutePath)
            _ <- deny("Parent traversal is not permitted").when(
              (0 until absolute.getNameCount).exists(index =>
                absolute.getName(index).toString == ".."
              )
            )
            name <- ZIO
              .fromOption(Option(absolute.getFileName))
              .orElseFail(AgentError.StorageError("Local JSON target needs a filename"))
              .map(_.toString)
            _ <- ZIO.fromEither(
              WorkspaceLease.validateName(name, 128).left.map(AgentError.StorageError(_))
            )
            _ <- deny("Local JSON targets require a portable lowercase .json filename").unless(
              name.endsWith(".json") && name.headOption.exists(character =>
                character >= 'a' && character <= 'z' || character >= '0' && character <= '9'
              )
            )
            _ <- deny("Sensitive local JSON target").when(PathSafety.isSensitivePath(absolute))
            parentPath <- ZIO
              .fromOption(Option(absolute.getParent))
              .orElseFail(AgentError.StorageError("Local JSON target needs a parent directory"))
            parent <- canonicalDirectory(parentPath)
            target = parent.resolve(name)
            _ <- deny("Sensitive local JSON target").when(PathSafety.isSensitivePath(target))
            lease <- WorkspaceLease.openNamed(
              parent,
              WorkspaceOwner.VersionedJson,
              s".$name.versioned-writer.lease"
            )
            backend = new Backend(parent, target, address, limits, lease, atomicReplace)
            _ <- backend.read(address)
          yield backend

  final private class Backend(
      parent: Path,
      target: Path,
      bound: StorageAddress,
      limits: Limits,
      lease: WorkspaceLease,
      atomicReplace: (Path, Path) => IO[AgentError, Unit]
  ) extends VersionedJsonStore:
    def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
      checkAddress(address) *> lease.protect(load)

    def compareAndSet(
        address: StorageAddress,
        expected: ExpectedRevision,
        value: Json
    ): IO[AgentError, ConditionalWrite] =
      checkAddress(address) *> ZIO
        .fromEither(validate(value))
        .flatMap: valid =>
          lease.protect:
            for
              _ <- expected match
                case ExpectedRevision.AtRevision(revision) if revision.value == Long.MaxValue =>
                  ZIO.fail(AgentError.StorageError("Storage revision exhausted"))
                case _ => ZIO.unit
              current <- load
              matches = (expected, current) match
                case (ExpectedRevision.Absent, None) => true
                case (ExpectedRevision.AtRevision(revision), Some(record)) =>
                  revision == record.revision
                case _ => false
              result <-
                if !matches then ZIO.succeed(ConditionalWrite.Conflict)
                else
                  for
                    revision <- ZIO.fromEither(
                      StorageRevision.fromLong(current.fold(1L)(_.revision.value + 1L))
                    )
                    envelope = Envelope(1, bound, VersionedJson(valid, revision))
                    encoded <- ZIO.fromEither(
                      envelope.toJsonAST.left.map(AgentError.StorageError(_))
                    )
                    text = JsonBoundary.render(encoded)
                    _ <- checkSize(text.getBytes(StandardCharsets.UTF_8).length.toLong)
                    _ <- replace(text)
                  yield ConditionalWrite.Applied(revision)
            yield result

    private def checkAddress(address: StorageAddress): IO[AgentError, Unit] =
      ZIO
        .fail(AgentError.StorageError("Local JSON store is bound to a different address"))
        .unless(address == bound)
        .unit

    private def checkSize(bytes: Long): IO[AgentError, Unit] =
      ZIO
        .fail(AgentError.StorageError("Local JSON file exceeds its byte limit"))
        .when(bytes > limits.maxBytes.toLong)
        .unit

    private def location: IO[AgentError, Unit] = canonicalDirectory(parent).flatMap(actual =>
      deny("Local JSON directory changed").unless(actual == parent).unit
    )

    private def attributes: IO[AgentError, Option[BasicFileAttributes]] =
      ZIO
        .attemptBlocking(
          Files.readAttributes(target, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
        )
        .map(Some(_))
        .catchSome:
          case _: NoSuchFileException => ZIO.none
        .mapError(storageError)
        .flatMap:
          case None => ZIO.none
          case Some(found) =>
            for
              _ <- deny("Local JSON target must be a non-symlink regular file").unless(
                found.isRegularFile
              )
              canonical <- io(target.toRealPath())
              _ <- deny("Local JSON target escapes its parent").unless(
                canonical == target && canonical.getParent == parent
              )
            yield Some(found)

    private def boundedText: IO[AgentError, String] =
      managed(io(Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)))(stream =>
        io(stream.close())
      ): stream =>
        for
          bytes <- io(stream.readNBytes(limits.maxBytes + 1))
          _     <- checkSize(bytes.length.toLong)
          text  <- io(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString)
        yield text

    private def load: IO[AgentError, Option[VersionedJson]] =
      location *> attributes.flatMap:
        case None => ZIO.none
        case Some(found) =>
          for
            _    <- checkSize(found.size())
            text <- boundedText
            _    <- checkSize(text.getBytes(StandardCharsets.UTF_8).length.toLong)
            json <- ZIO.fromEither(JsonBoundary.decode(text).left.map(AgentError.StorageError(_)))
            _    <- ZIO.fromEither(validate(json))
            envelope  <- ZIO.fromEither(json.as[Envelope].left.map(AgentError.StorageError(_)))
            canonical <- ZIO.fromEither(envelope.toJsonAST.left.map(AgentError.StorageError(_)))
            _ <- ZIO
              .fail(
                AgentError.StorageError(
                  "Local JSON envelope is not canonical or does not match its address/version"
                )
              )
              .unless(
                envelope.formatVersion == 1 && envelope.address == bound && JsonBoundary
                  .sameRepresentation(json, canonical)
              )
          yield Some(envelope.record)

    private def replace(text: String): IO[AgentError, Unit] =
      managed(io(Files.createTempFile(parent, ".versioned-json-pending-", ".tmp")))(temporary =>
        io(Files.deleteIfExists(temporary)).unit
      ): temporary =>
        for
          _ <- io(
            Files.writeString(temporary, text, StandardCharsets.UTF_8, StandardOpenOption.WRITE)
          )
          _ <- location *> attributes
          _ <- ZIO.suspendSucceed(atomicReplace(temporary, target))
        yield ()

  /** Register release before use; retain typed cleanup failures after the resource scope joins. */
  private def managed[A, B](
      acquire: IO[AgentError, A]
  )(release: A => IO[AgentError, Unit])(use: A => IO[AgentError, B]): IO[AgentError, B] =
    for
      cleanup <- Ref.make[Exit[AgentError, Unit]](Exit.unit)
      outcome <- ZIO
        .scoped(
          ZIO
            .acquireRelease(acquire)(resource => release(resource).exit.flatMap(cleanup.set))
            .flatMap(use)
        )
        .exit
      released <- cleanup.get
      result   <- outcome.zipWith(released)((value, _) => value, _ ++ _)
    yield result

  private def canonicalDirectory(path: Path): IO[AgentError, Path] =
    for
      _ <- deny("Sensitive local JSON directory").when(PathSafety.isSensitivePath(path))
      attributes <- io(
        Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
      )
      _ <- deny("Local JSON requires a non-symlink directory").unless(attributes.isDirectory)
      canonical <- io(path.toRealPath())
      _ <- deny("Sensitive canonical local JSON directory").when(
        PathSafety.isSensitivePath(canonical)
      )
    yield canonical

  private def validate(value: Json): Either[AgentError.StorageError, Json] =
    JsonBoundary
      .validate(value)
      .left
      .map(AgentError.StorageError(_))
      .flatMap(valid =>
        Either.cond(
          JsonBoundary.validUnicode(valid),
          valid,
          AgentError.StorageError("Storage JSON contains an unpaired UTF-16 surrogate")
        )
      )

  private def deny(message: String): IO[AgentError, Nothing] =
    ZIO.fail(AgentError.PermissionDenied("local_json_store", message))
  private def storageError(error: Throwable): AgentError.StorageError =
    AgentError.StorageError(Option(error.getMessage).getOrElse(error.toString))
  private def io[A](operation: => A): IO[AgentError, A] =
    ZIO.attemptBlocking(operation).mapError(storageError)

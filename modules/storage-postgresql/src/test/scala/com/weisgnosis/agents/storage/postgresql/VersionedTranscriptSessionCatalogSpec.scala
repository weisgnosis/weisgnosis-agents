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
package com.weisgnosis.agents.storage.postgresql

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg}
import com.weisgnosis.agents.harness.storage.*
import com.weisgnosis.agents.harness.transcript.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object VersionedTranscriptSessionCatalogSpec extends ZIOSpecDefault:
  import PostgresFixture.*

  private enum Acknowledgement:
    case Lost, Interrupted

  private def session(
      name: String,
      manifest: StorageAddress,
      layout: TranscriptLayout = TranscriptLayout.Aggregate
  ): IO[AgentError, TranscriptSession] =
    ZIO.fromEither(
      TranscriptTargetId.fromString(name).flatMap(TranscriptSession.make(_, manifest, layout))
    )

  private def catalog(
      backend: VersionedJsonStore,
      target: StorageAddress,
      maxSessions: Int = 256,
      maxAttempts: Int = 32
  ): IO[AgentError, TranscriptSessionCatalog] =
    ZIO.fromEither(
      VersionedTranscriptSessionCatalog.make(backend, target, maxSessions, maxAttempts)
    )

  private def registeredWriters(
      backend: VersionedJsonStore,
      readerBackend: VersionedJsonStore,
      name: String
  ): IO[AgentError, Boolean] =
    ZIO
      .foreach(Chunk(TranscriptLayout.Aggregate, TranscriptLayout.Segmented)): layout =>
        for
          target     <- address(name + "-writer", layout.toString)
          manifest   <- address(name + "-writer-history", layout.toString)
          descriptor <- session(layout.toString, manifest, layout)
          index      <- catalog(backend, target)
          writer <- ZIO.fromEither(SessionTranscriptWriter.registered(backend, descriptor, index))
          message = Msg.user(
            Chunk(ContentBlock.Text("registered history")),
            id = Some("stable-message")
          )
          appended   <- writer.appendMessages(Chunk(message))
          _          <- writer.offload(Chunk(message))
          coldIndex  <- catalog(readerBackend, target)
          discovered <- coldIndex.sessions
          coldStore <- ZIO.fromEither(layout match
            case TranscriptLayout.Aggregate =>
              VersionedTranscriptStore.make(readerBackend, manifest)
            case TranscriptLayout.Segmented =>
              SegmentedTranscriptStore.make(readerBackend, manifest))
          history <- coldStore.entries
        yield discovered == Chunk(descriptor) && appended.size == 1 && history == appended
      .map(_.forall(identity))

  private def encoded(
      sessions: Chunk[TranscriptSession],
      version: Int = 1
  ): IO[AgentError, Json.Obj] =
    ZIO
      .fromEither(sessions.toJsonAST.left.map(AgentError.StorageError.apply))
      .map(values => Json.Obj("schemaVersion" -> Json.Num(version), "sessions" -> values))

  def spec: Spec[TestEnvironment, Any] = suite("VersionedTranscriptSessionCatalogSpec")(
    test(
      "independent catalog clients register one sorted binding per ID and reopen isolated discovery"
    ):
      fixture: (source, backend, name) =>
        for
          target        <- address(name, "catalog")
          first         <- catalog(backend, target)
          empty         <- first.sessions
          absent        <- backend.read(target)
          secondSource  <- dataSource(name + "-second")
          secondBackend <- store(secondSource)
          second        <- catalog(secondBackend, target)
          descriptors <- ZIO.foreach(Chunk("z", "a", "b", "A", "m", "!")) { id =>
            address(name + "-transcripts", id).flatMap(session(id, _, TranscriptLayout.Segmented))
          }
          results <- ZIO.foreachPar(descriptors ++ descriptors)(descriptor =>
            (if descriptor.id.value == "a" then first else second).register(descriptor)
          )
          reopenedBackend <- store(source)
          reopened        <- catalog(reopenedBackend, target)
          restored        <- reopened.sessions
          otherTarget     <- address(name + "-isolated", "catalog")
          isolated        <- catalog(secondBackend, otherTarget)
          untouched       <- isolated.sessions
          unavailable     <- ZIO.foreach(restored)(descriptor => backend.read(descriptor.manifest))
          before          <- backend.read(target)
          repeated        <- ZIO.foreach(restored)(reopened.register)
          after           <- backend.read(target)
          registered      <- registeredWriters(backend, secondBackend, name)
        yield assertTrue(
          registered,
          empty.isEmpty,
          absent.isEmpty,
          untouched.isEmpty,
          results.count(_ == TranscriptRegistration.Added) == descriptors.size,
          results.count(_ == TranscriptRegistration.Existing) == descriptors.size,
          restored == descriptors.sortBy(_.id.value),
          unavailable.forall(_.isEmpty),
          repeated.forall(_ == TranscriptRegistration.Existing),
          before == after
        )
    ,
    test("identity, layout, reserved namespace and capacity conflicts cannot retarget a binding"):
      fixture: (_, backend, name) =>
        for
          target   <- address(name, "catalog")
          root     <- address(name + "-transcripts", "root")
          original <- session("original", root, TranscriptLayout.Segmented)
          writes   <- Ref.make(0)
          counted = new VersionedJsonStore:
            def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
              backend.read(address)
            def compareAndSet(address: StorageAddress, expected: ExpectedRevision, value: Json)
                : IO[AgentError, ConditionalWrite] =
              writes.update(_ + 1) *> backend.compareAndSet(address, expected, value)
          instance     <- catalog(counted, target, maxSessions = 8)
          added        <- instance.register(original)
          before       <- backend.read(target)
          otherAddress <- address(name + "-transcripts", "other")
          nestedAddress <- ZIO.fromEither(
            StorageAddress.from(root.namespace ++ Chunk(root.key, "segments"), "nested")
          )
          conflicts <- ZIO.collectAll(
            Chunk(
              session("original", otherAddress),
              session("other-id", root, TranscriptLayout.Segmented),
              session("original", root, TranscriptLayout.Aggregate),
              session("nested", nestedAddress),
              session("self", target)
            )
          )
          rejected <- ZIO.foreach(conflicts)(instance.register(_).either)
          existing <- instance.register(original)
          after    <- backend.read(target)
          calls    <- writes.get
          nestedCatalogAddress <- ZIO.fromEither(
            StorageAddress.from(root.namespace ++ Chunk(root.key, "segments"), "catalog")
          )
          nestedCatalog       <- catalog(counted, nestedCatalogAddress)
          overlap             <- nestedCatalog.register(original).either
          finalCalls          <- writes.get
          limitedTarget       <- address(name, "limited")
          limited             <- catalog(counted, limitedTarget, maxSessions = 1)
          _                   <- limited.register(original)
          limitedBefore       <- backend.read(limitedTarget)
          another             <- session("capacity", otherAddress)
          capacity            <- limited.register(another).either
          duplicateAtCapacity <- limited.register(original)
          limitedAfter        <- backend.read(limitedTarget)
          boundedCalls        <- writes.get
          longManifest        <- address("x" * 500, "manifest")
          id                  <- ZIO.fromEither(TranscriptTargetId.fromString("bounded"))
          malformedId         <- ZIO.fromEither(TranscriptTargetId.fromString("\ud800"))
        yield assertTrue(
          added == TranscriptRegistration.Added,
          rejected.forall(_.left.exists(_.isInstanceOf[AgentError.MemoryStorageError])),
          existing == TranscriptRegistration.Existing,
          before == after,
          calls == 1,
          finalCalls == 1,
          overlap.left.exists(_.isInstanceOf[AgentError.MemoryStorageError]),
          capacity.left.exists(_.isInstanceOf[AgentError.MemoryStorageError]),
          duplicateAtCapacity == TranscriptRegistration.Existing,
          limitedBefore == limitedAfter,
          boundedCalls == 2,
          TranscriptSession.make(id, longManifest, TranscriptLayout.Segmented).isLeft,
          TranscriptSession.make(malformedId, root, TranscriptLayout.Aggregate).isLeft,
          VersionedTranscriptSessionCatalog.make(backend, target, maxSessions = 0).isLeft,
          VersionedTranscriptSessionCatalog.make(backend, target, maxAttempts = 0).isLeft
        )
    ,
    test(
      "strict catalogs, bounded conflicts and uncertain registration preserve current authority"
    ):
      fixture: (_, backend, name) =>
        for
          firstAddress    <- address(name + "-transcripts", "a")
          secondAddress   <- address(name + "-transcripts", "b")
          first           <- session("a", firstAddress)
          second          <- session("b", secondAddress, TranscriptLayout.Segmented)
          sameAddress     <- session("b", firstAddress)
          good            <- encoded(Chunk(first, second))
          reversed        <- encoded(Chunk(second, first))
          sameId          <- session("a", secondAddress, TranscriptLayout.Segmented)
          repeatedId      <- encoded(Chunk(first, sameId))
          repeatedAddress <- encoded(Chunk(first, sameAddress))
          version         <- encoded(Chunk(first), version = 2)
          segmentedFirst  <- session("a", firstAddress, TranscriptLayout.Segmented)
          nestedAddress <- ZIO.fromEither(
            StorageAddress.from(
              firstAddress.namespace ++ Chunk(firstAddress.key, "segments"),
              "nested"
            )
          )
          nested      <- session("b", nestedAddress)
          overlapping <- encoded(Chunk(segmentedFirst, nested))
          invalid = Chunk(
            overlapping,
            reversed,
            repeatedId,
            repeatedAddress,
            version,
            Json.Obj(good.fields :+ ("unknown" -> Json.Bool(true)))
          )
          rejected <- ZIO.foreach(invalid.zipWithIndex): (value, index) =>
            for
              target    <- address(name, s"bad-$index")
              published <- backend.compareAndSet(target, ExpectedRevision.Absent, value)
              before    <- backend.read(target)
              instance  <- catalog(backend, target)
              result    <- instance.sessions.either
              after     <- backend.read(target)
            yield published.isInstanceOf[ConditionalWrite.Applied] && result.left.exists(
              _.isInstanceOf[AgentError.MemoryStorageError]
            ) && before == after
          budgets <- ZIO.foreach(Chunk(1, 8)): budget =>
            for
              target     <- address(name, s"conflict-$budget")
              competitor <- catalog(backend, target)
              calls      <- Ref.make(0)
              interposed = new VersionedJsonStore:
                def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
                  backend.read(address)
                def compareAndSet(address: StorageAddress, expected: ExpectedRevision, value: Json)
                    : IO[AgentError, ConditionalWrite] =
                  calls
                    .getAndUpdate(_ + 1)
                    .flatMap(previous =>
                      ZIO.when(previous == 0)(competitor.register(first)) *>
                        backend.compareAndSet(address, expected, value)
                    )
              instance <- catalog(interposed, target, maxAttempts = budget)
              result   <- instance.register(second).either
              attempts <- calls.get
              visible  <- competitor.sessions
            yield
              if budget == 1 then
                result.left.exists(
                  _.isInstanceOf[AgentError.MemoryStorageError]
                ) && attempts == 1 && visible == Chunk(first)
              else
                result == Right(TranscriptRegistration.Added) && attempts == 2 && visible == Chunk(
                  first,
                  second
                )
          uncertain <- ZIO.foreach(Chunk.fromArray(Acknowledgement.values)): acknowledgement =>
            for
              target  <- address(name, s"uncertain-$acknowledgement")
              writes  <- Ref.make(0)
              entered <- Promise.make[Nothing, Unit]
              cleaned <- Ref.make(false)
              interposed = new VersionedJsonStore:
                def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
                  backend.read(address)
                def compareAndSet(address: StorageAddress, expected: ExpectedRevision, value: Json)
                    : IO[AgentError, ConditionalWrite] =
                  (writes.update(_ + 1) *> backend
                    .compareAndSet(address, expected, value)
                    .flatMap:
                      case ConditionalWrite.Applied(_) =>
                        acknowledgement match
                          case Acknowledgement.Lost =>
                            ZIO.fail(AgentError.StorageError("lost acknowledgement"))
                          case Acknowledgement.Interrupted => entered.succeed(()) *> ZIO.never
                      case outcome => ZIO.succeed(outcome)
                  ).ensuring(cleaned.set(true))
              instance <- catalog(interposed, target)
              failed <- acknowledgement match
                case Acknowledgement.Lost =>
                  instance
                    .register(first)
                    .either
                    .map(_.left.exists(_.isInstanceOf[AgentError.StorageError]))
                case Acknowledgement.Interrupted =>
                  ZIO.scoped:
                    for
                      fiber <- instance.register(first).forkScoped
                      _     <- entered.await
                      exit  <- fiber.interrupt
                    yield exit.isInterrupted
              closed    <- cleaned.get
              calls     <- writes.get
              recovered <- catalog(backend, target)
              visible   <- recovered.sessions
              before    <- backend.read(target)
              repeated  <- recovered.register(first)
              after     <- backend.read(target)
            yield failed && closed && calls == 1 && visible == Chunk(
              first
            ) && repeated == TranscriptRegistration.Existing && before == after
        yield assertTrue(
          rejected.forall(identity),
          budgets.forall(identity),
          uncertain.forall(identity)
        )
  ) @@ TestAspect.sequential @@ TestAspect.withLiveRandom @@ TestAspect.withLiveSystem @@ TestAspect.withLiveClock @@
    TestAspect.timeout(30.seconds) @@ TestAspect.ifEnvSet("WEISGNOSIS_POSTGRES_URL")

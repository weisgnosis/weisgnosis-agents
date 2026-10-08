/*
 * Copyright 2024-2026 WeisGnosis (https://weisgnosis.com)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.weisgnosis.agents.harness.subagent

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.harness.storage.StorageAddress
import java.nio.charset.StandardCharsets
import zio.*
import zio.test.*

object SharedSubagentTaskFilesSpec extends ZIOSpecDefault:
  private val path: String = "agents/my-agent/tasks/sess-test.json"

  private def bind(
      backend: TaskFileBackend,
      owner: String,
      maxBytes: Int = 16
  ): IO[AgentError, SharedSubagentTaskFiles] =
    ZIO.fromEither:
      for
        address <- StorageAddress.from(Chunk("users", owner), "workspace")
        agent   <- LocalSubagentTaskWorkspace.Name.from("my-agent")
        files   <- SharedSubagentTaskFiles.bind(backend, address, agent, maxBytes)
      yield files

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SharedSubagentTaskFilesSpec")(
    test(
      "shared route roundtrips exact bytes and isolates owners and sessions on a borrowed backend"
    ):
      for
        stored <- Ref.make(Map.empty[StorageAddress, Chunk[Byte]])
        writes <- Ref.make(Chunk.empty[StorageAddress])
        backend = new TaskFileBackend:
          def read(address: StorageAddress): IO[AgentError, Option[Chunk[Byte]]] =
            stored.get.map(_.get(address))
          def write(address: StorageAddress, bytes: Chunk[Byte]): IO[AgentError, Unit] =
            stored.update(_.updated(address, bytes)) *> writes.update(_ :+ address)
        first  <- bind(backend, "alice")
        second <- bind(backend, "bob")
        bytes = Chunk[Byte](0, -1, -128, 65, 10)
        json  = Chunk.fromArray("{\"test\":true}".getBytes(StandardCharsets.UTF_8))
        missing   <- first.read(path)
        _         <- first.write(path, json)
        jsonRead  <- first.read(path)
        _         <- first.write(path, bytes)
        actual    <- first.read(path)
        foreign   <- second.read(path)
        sibling   <- first.read("agents/my-agent/tasks/other.json")
        _         <- second.write(path, Chunk[Byte](42))
        reopened  <- bind(backend, "alice")
        retained  <- reopened.read(path)
        bob       <- second.read(path)
        addresses <- writes.get
        expected <- ZIO.fromEither(
          StorageAddress.from(
            Chunk("users", "alice", "workspace", "my-agent", "sess-test"),
            "tasks"
          )
        )
      yield assertTrue(
        missing.isEmpty,
        jsonRead.contains(json),
        actual.contains(bytes),
        foreign.isEmpty,
        sibling.isEmpty,
        retained.contains(bytes),
        bob.contains(Chunk[Byte](42)),
        addresses.headOption.contains(expected),
        addresses.distinct.length == 2
      )
    ,
    test(
      "rejected routes never reach storage; bounds, failures and joined cancellation have no fallback"
    ):
      ZIO.scoped:
        for
          calls <- Ref.make(0)
          failure = AgentError.StorageError("Borrowed task backend unavailable")
          backend = new TaskFileBackend:
            def read(address: StorageAddress): IO[AgentError, Option[Chunk[Byte]]] =
              calls.update(_ + 1).as(Some(Chunk[Byte](1, 2, 3)))
            def write(address: StorageAddress, bytes: Chunk[Byte]): IO[AgentError, Unit] =
              calls.update(_ + 1) *> ZIO.fail(failure)
          files <- bind(backend, "alice", 2)
          invalid = Chunk(
            "agents/other/tasks/sess-test.json",
            "agents/my-agent/tasks/../secret.json",
            "agents/my-agent/tasks/.env.json",
            "/agents/my-agent/tasks/sess-test.json",
            "agents/my-agent/tasks/sess-test.json/extra",
            "agents/my-agent/tasks/UPPER.json",
            "agents\\my-agent\\tasks\\sess-test.json",
            "MEMORY.md"
          )
          rejected <- ZIO.foreach(invalid)(value =>
            files.read(value).either.zip(files.write(value, Chunk.empty).either)
          )
          before         <- calls.get
          oversizedWrite <- files.write(path, Chunk[Byte](1, 2, 3)).either
          afterWrite     <- calls.get
          oversizedRead  <- files.read(path).either
          failedWrite    <- files.write(path, Chunk[Byte](1)).either
          failingBackend = new TaskFileBackend:
            def read(address: StorageAddress): IO[AgentError, Option[Chunk[Byte]]] =
              ZIO.fail(failure)
            def write(address: StorageAddress, bytes: Chunk[Byte]): IO[AgentError, Unit] =
              ZIO.fail(failure)
          failedFiles    <- bind(failingBackend, "alice")
          failedRead     <- failedFiles.read(path).either
          entered        <- Promise.make[Nothing, Unit]
          cleanupEntered <- Promise.make[Nothing, Unit]
          release        <- Promise.make[Nothing, Unit]
          _              <- ZIO.addFinalizer(release.succeed(()).unit)
          waitingBackend = new TaskFileBackend:
            def read(address: StorageAddress): IO[AgentError, Option[Chunk[Byte]]] =
              (entered.succeed(()) *> ZIO.never)
                .ensuring(cleanupEntered.succeed(()) *> release.await)
            def write(address: StorageAddress, bytes: Chunk[Byte]): IO[AgentError, Unit] = ZIO.unit
          waiting    <- bind(waitingBackend, "alice")
          controller <- ZIO.fiberId
          running <- ZIO.uninterruptibleMask: restore =>
            for
              fiber <- restore(waiting.read(path)).forkScoped
              _     <- ZIO.addFinalizer(release.succeed(()).unit)
            yield fiber
          _    <- entered.await
          _    <- running.interruptAsFork(controller)
          _    <- cleanupEntered.await
          held <- running.poll
          _    <- release.succeed(())
          exit <- running.await
        yield assertTrue(
          rejected.forall((read, write) => read.isLeft && write.isLeft),
          before == 0,
          afterWrite == 0,
          oversizedWrite.isLeft,
          oversizedRead.isLeft,
          failedWrite == Left(failure),
          failedRead == Left(failure),
          held.isEmpty,
          exit.causeOption.exists(_.interruptors.contains(controller))
        )
  )

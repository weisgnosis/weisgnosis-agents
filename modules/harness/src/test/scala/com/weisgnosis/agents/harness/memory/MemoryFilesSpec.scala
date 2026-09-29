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

import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import java.nio.file.Files
import java.time.LocalDate
import zio.*
import zio.test.*
import MemoryTestSupport.temporaryDirectory

object MemoryFilesSpec extends ZIOSpecDefault:
  private val day: LocalDate = LocalDate.of(2026, 9, 29)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("MemoryFilesSpec")(
    test("enumeration includes canonical daily files and ignores internal and unrelated entries") {
      for
        root  <- temporaryDirectory
        files <- MemoryFiles.open(root, WorkspaceTrust.HostControlled)
        _     <- files.write(MemoryFile.Daily(day), "daily")
        _     <- files.write(MemoryFile.Checkpoint, "checkpoint")
        _     <- files.write(MemoryFile.Journal, "journal")
        _ <- ZIO.attemptBlocking(
          Files.writeString(files.directory.resolve("notes.md"), "unrelated")
        )
        _ <- ZIO.attemptBlocking(
          Files.writeString(files.directory.resolve("2026-02-30.md"), "invalid date")
        )
        _ <- ZIO.attemptBlocking(
          Files.writeString(files.directory.resolve(".memory-pending-1.tmp"), "pending")
        )
        daily <- files.dailyContents
      yield assertTrue(daily == Map(day -> "daily"))
    },
    test("nonregular daily candidates and fixed metadata targets fail without replacement") {
      for
        root  <- temporaryDirectory
        files <- MemoryFiles.open(root, WorkspaceTrust.HostControlled)
        _     <- ZIO.attemptBlocking(Files.createDirectory(files.directory.resolve(s"$day.md")))
        daily <- files.dailyContents.either
        _ <- ZIO.attemptBlocking(
          Files.createDirectory(files.directory.resolve(".consolidation_state"))
        )
        state <- files.write(MemoryFile.Checkpoint, "wrong").either
        intact <- ZIO.attemptBlocking(
          Files.isDirectory(files.directory.resolve(".consolidation_state"))
        )
      yield assertTrue(daily.isLeft, state.isLeft, intact)
    },
    test("journal removal leaves daily, curated and checkpoint payloads untouched") {
      for
        root       <- temporaryDirectory
        files      <- MemoryFiles.open(root, WorkspaceTrust.HostControlled)
        _          <- files.write(MemoryFile.Curated, "curated")
        _          <- files.write(MemoryFile.Checkpoint, "checkpoint")
        _          <- files.write(MemoryFile.Daily(day), "daily")
        _          <- files.write(MemoryFile.Journal, "journal")
        _          <- files.removeJournal
        curated    <- files.read(MemoryFile.Curated)
        checkpoint <- files.read(MemoryFile.Checkpoint)
        daily      <- files.read(MemoryFile.Daily(day))
        journal    <- files.read(MemoryFile.Journal)
      yield assertTrue(
        curated.contains("curated"),
        checkpoint.contains("checkpoint"),
        daily.contains("daily"),
        journal.isEmpty
      )
    }
  )

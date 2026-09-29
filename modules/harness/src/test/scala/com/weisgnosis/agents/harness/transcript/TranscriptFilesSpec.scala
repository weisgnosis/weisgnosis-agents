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

import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import com.weisgnosis.agents.harness.memory.MemoryTestSupport.{children, temporaryDirectory}
import java.nio.file.Files
import zio.*
import zio.test.*

object TranscriptFilesSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("TranscriptFilesSpec")(
    test("replacing a hard-linked payload leaves the other file unchanged") {
      for
        root     <- temporaryDirectory
        original <- ZIO.attemptBlocking(Files.writeString(root.resolve("original"), "original"))
        _     <- ZIO.attemptBlocking(Files.createLink(root.resolve("transcript.jsonl"), original))
        files <- TranscriptFiles.open(root, WorkspaceTrust.HostControlled)
        _     <- files.write("replacement")
        saved <- files.read
        unchanged <- ZIO.attemptBlocking(Files.readString(original))
        names     <- children(root)
      yield assertTrue(
        saved == "replacement",
        unchanged == "original",
        names == Set("original", "transcript.jsonl")
      )
    },
    test("failed nonregular replacement cleans temporary files and preserves the directory") {
      for
        root   <- temporaryDirectory
        files  <- TranscriptFiles.open(root, WorkspaceTrust.HostControlled)
        _      <- ZIO.attemptBlocking(Files.createDirectory(root.resolve("transcript.jsonl")))
        result <- files.write("wrong").either
        names  <- children(root)
        intact <- ZIO.attemptBlocking(Files.isDirectory(root.resolve("transcript.jsonl")))
      yield assertTrue(result.isLeft, names == Set("transcript.jsonl"), intact)
    }
  )

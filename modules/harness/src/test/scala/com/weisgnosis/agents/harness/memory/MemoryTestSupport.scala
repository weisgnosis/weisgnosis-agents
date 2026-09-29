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

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import zio.*

private[memory] object MemoryTestSupport:
  def temporaryDirectory: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("agent-memory-"))):
      directory =>
        ZIO
          .scoped:
            for
              stream <- ZIO.acquireRelease(ZIO.attemptBlocking(Files.walk(directory)))(value =>
                ZIO.attemptBlocking(value.close()).orDie
              )
              paths <- ZIO.attemptBlocking(
                stream.iterator().asScala.toList.sortBy(_.getNameCount).reverse
              )
              _ <- ZIO.foreachDiscard(paths)(path =>
                ZIO.attemptBlocking(Files.deleteIfExists(path))
              )
            yield ()
          .orDie

  def children(directory: Path): ZIO[Scope, Throwable, Set[String]] =
    for
      stream <- ZIO.acquireRelease(ZIO.attemptBlocking(Files.list(directory)))(value =>
        ZIO.attemptBlocking(value.close()).orDie
      )
      names <- ZIO.attemptBlocking(stream.iterator().asScala.map(_.getFileName.toString).toSet)
    yield names

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
package com.weisgnosis.agents.harness.plan

import com.weisgnosis.agents.core.error.AgentError
import java.util.Locale
import zio.Chunk
import zio.json.*

/** Logical workspace-relative configuration, never a native absolute path. */
opaque type PlanDirectory = String

object PlanDirectory:
  val Default: PlanDirectory = "plans"

  def fromString(input: String): Either[AgentError.InvalidConfiguration, PlanDirectory] =
    val normalized = input.strip.dropWhile(_ == '/').reverse.dropWhile(_ == '/').reverse
    if input.isBlank || normalized.isEmpty then Right(Default)
    else if normalized.split("/", -1).toList.forall(validSegment) then Right(normalized)
    else
      Left(
        AgentError.InvalidConfiguration(
          "Plan directory must contain safe nonempty workspace-relative segments"
        )
      )

  given JsonCodec[PlanDirectory] = JsonCodec.string.transformOrFail(
    input => fromString(input).left.map(_.message),
    directory => directory
  )

  extension (directory: PlanDirectory)
    def value: String                         = directory
    private[plan] def segments: Chunk[String] = Chunk.fromIterable(directory.split("/", -1).toList)

  private def validSegment(segment: String): Boolean =
    PlanLocation.fromString(segment).isRight && segment != "." && segment != ".." &&
      segment == segment.strip && !segment.endsWith(".") &&
      !segment.exists(character => "\\:*?\"<>|".contains(character)) && !reserved(segment)

  private def reserved(segment: String): Boolean =
    val stem = segment.takeWhile(_ != '.').strip.toUpperCase(Locale.ROOT)
    Set("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$").contains(stem) ||
    Chunk("COM", "LPT").exists(prefix =>
      stem.startsWith(prefix) && stem.length == prefix.length + 1 &&
        stem.lastOption.exists(character => "123456789\u00b9\u00b2\u00b3".contains(character))
    )

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
import zio.json.*

/** Descriptive location data; it grants no read, write or approval capability. */
opaque type PlanLocation = String

object PlanLocation:
  def fromString(text: String): Either[AgentError.InvalidConfiguration, PlanLocation] =
    if text.isBlank || !validDisplay(text) then
      Left(
        AgentError.InvalidConfiguration(
          "Plan location must be nonblank Unicode display text without control or formatting characters"
        )
      )
    else Right(text)

  private def validDisplay(text: String): Boolean =
    @annotation.tailrec
    def scan(index: Int): Boolean =
      if index >= text.length then true
      else
        val first = text.charAt(index)
        if Character.isHighSurrogate(first) then
          if index + 1 >= text.length then false
          else
            val second = text.charAt(index + 1)
            if Character.isLowSurrogate(second) && visible(Character.toCodePoint(first, second))
            then scan(index + 2)
            else false
        else if Character.isLowSurrogate(first) || !visible(first.toInt) then false
        else scan(index + 1)
    scan(0)

  private def visible(codePoint: Int): Boolean =
    !Character.isISOControl(codePoint) && Character.getType(codePoint) != Character.FORMAT &&
      codePoint != 0x2028 && codePoint != 0x2029

  given JsonCodec[PlanLocation] = JsonCodec.string.transformOrFail(
    text => fromString(text).left.map(_.message),
    text => text
  )

  extension (location: PlanLocation) def text: String = location

/** One session snapshot associated with an immutable configured destination, if the host supplies one. */
final case class PlanContext(snapshot: PlanSessionSnapshot, location: Option[PlanLocation] = None)
    derives JsonCodec

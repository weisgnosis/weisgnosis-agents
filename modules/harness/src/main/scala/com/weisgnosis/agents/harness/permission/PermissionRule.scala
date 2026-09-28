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
package com.weisgnosis.agents.harness.permission

import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/** Tool names are exact or '*'; optional content is a glob against string-valued arguments. */
final case class PermissionRule(
    toolName: String,
    behavior: PermissionBehavior,
    ruleContent: Option[String] = None,
    source: String = "user",
    argumentPath: Chunk[String] = Chunk("command")
) derives JsonCodec:
  def matches(name: String, input: Json): Boolean =
    (toolName == name || toolName == "*") && ruleContent.forall { pattern =>
      val argument = input match
        case text: Json.Str => Some(text)
        case _ =>
          argumentPath.foldLeft(Option(input)) { (current, field) =>
            current.flatMap(_.asObject).flatMap(_.get(field))
          }
      argument.flatMap(_.asString).exists(value => glob(pattern, value))
    }

  /** Dynamic programming keeps wildcard matching total without regex compilation or backtracking. */
  private def glob(pattern: String, text: String): Boolean =
    val initial = Vector(true) ++ Vector.fill(text.length)(false)
    pattern
      .foldLeft(initial) { (previous, token) =>
        text.zipWithIndex.foldLeft(Vector(token == '*' && previous.headOption.contains(true))) {
          case (current, (character, index)) =>
            val matched =
              if token == '*' then
                current.lastOption.contains(true) || previous.lift(index + 1).contains(true)
              else (token == '?' || token == character) && previous.lift(index).contains(true)
            current :+ matched
        }
      }
      .lastOption
      .contains(true)

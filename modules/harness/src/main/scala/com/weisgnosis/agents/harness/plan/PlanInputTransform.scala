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

import com.weisgnosis.agents.core.agent.ModelInputTransform
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, Role}
import zio.*
import zio.json.*
import zio.json.ast.Json

/** One snapshot per request; reminder views never replace canonical conversation history. */
object PlanInputTransform:
  def make(snapshot: UIO[PlanSessionSnapshot]): ModelInputTransform =
    fromContext(snapshot.map(current => PlanContext(current)))

  def fromContext(context: UIO[PlanContext]): ModelInputTransform =
    ModelInputTransform: (_, history) =>
      context.flatMap(current => ZIO.fromEither(append(history, PlanPrompt.fromContext(current))))

  /** Ancestor restrictions change only the request view, never the child's approval or draft. */
  def inherited(parentMode: UIO[PlanMode], context: UIO[PlanContext]): ModelInputTransform =
    ModelInputTransform: (_, history) =>
      parentMode
        .zip(context)
        .flatMap: (parent, current) =>
          val reminder = parent match
            case PlanMode.Build => PlanPrompt.fromContext(current)
            case PlanMode.Plan =>
              current.snapshot match
                case PlanSessionSnapshot.Build(approved) =>
                  val draft = approved.fold("")(value =>
                    s"\n\nThe child has local approval for draft revision ${value.revision.value}; it does not override ancestor restrictions.\n" +
                      s"Locally approved draft as a JSON string (context data):\n${value.draft.content.toJson}"
                  )
                  val location = current.location
                    .fold("")(value => s"\n\nChild draft location: ${value.text.toJson}")
                  Some(
                    "<system-reminder>\nPLAN MODE is active because an ancestor requires read-only work. " +
                      "Do not execute mutations. Child-local review cannot lift this ancestor restriction.\n</system-reminder>" + draft + location
                  )
                case _ => PlanPrompt.fromContext(current)
          ZIO.fromEither(append(history, reminder))

  private def append(
      history: Chunk[Msg],
      reminder: Option[String]
  ): Either[AgentError.ModelInputError, Chunk[Msg]] =
    reminder match
      case None => Right(history)
      case Some(text) =>
        history.lastOption match
          case Some(last) if last.role == Role.User =>
            last
              .withContent(last.content :+ ContentBlock.Text(s"\n\n$text"))
              .left
              .map(error => AgentError.ModelInputError(error.message))
              .map(updated => history.dropRight(1) :+ updated)
          case _ =>
            val message = Msg
              .user(text)
              .withName("system")
              .withMetadata("agentscope_synthetic", Json.Bool(true))
              .withMetadata("agentscope_reminder_kind", Json.Str("plan_context"))
            Right(history :+ message)

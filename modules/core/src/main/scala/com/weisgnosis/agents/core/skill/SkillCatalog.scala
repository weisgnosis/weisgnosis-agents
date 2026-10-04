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
package com.weisgnosis.agents.core.skill

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.tool.{ToolGroups, ToolGroupScope}
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/** Host-preloaded text; decoded declarations still require checked catalogue construction. */
final case class SkillDefinition(
    id: String,
    description: String,
    content: String,
    resources: Map[String, String] = Map.empty,
    activateGroups: Set[String] = Set.empty,
    metadata: Json.Obj = Json.Obj()
) derives JsonCodec

/** Pure catalogue of logical resources; it owns neither filesystem access nor activation state. */
final class SkillCatalog private (entries: Map[String, SkillDefinition]):
  def definitions: Chunk[SkillDefinition] =
    Chunk.fromIterable(entries.toList.sortBy(_._1).map(_._2))
  def isEmpty: Boolean = entries.isEmpty

  /** Request-only discovery text; omits bodies, metadata and activation targets. */
  def systemPrompt: String =
    if isEmpty then ""
    else
      val visible = definitions.map: skill =>
        Json.Obj(
          "id"          -> Json.Str(skill.id),
          "description" -> Json.Str(skill.description),
          "resources" -> Json.Arr(
            (Chunk("SKILL.md") ++ Chunk.fromIterable(skill.resources.keys.toList.sorted))
              .map(Json.Str(_))
          )
        )
      "Available skills\n" +
        s"Use ${SkillCatalog.LoadToolName} to load a selected skill by skillId and logical path; begin with SKILL.md.\n" +
        Json.Arr(visible).toJson

  /** Select existing capabilities only; an empty selection removes every skill. */
  def select(ids: Set[String]): Either[AgentError.InvalidConfiguration, SkillCatalog] =
    if ids.exists(_.isBlank) then
      Left(AgentError.InvalidConfiguration("Selected skill IDs must be nonblank"))
    else
      ids.diff(entries.keySet).toList.sorted.headOption match
        case Some(missing) =>
          Left(AgentError.InvalidConfiguration(s"Unknown selected skill '${missing.take(80)}'"))
        case None => Right(new SkillCatalog(entries.filter((id, _) => ids.contains(id))))

  /** Advisory guidance derived from explicit bindings, without tracking which skills are in use. */
  def groupNotes: String =
    val bindings = definitions.toList
      .flatMap(skill => skill.activateGroups.toList.map(group => group -> skill.id))
      .groupMap(_._1)(_._2)
    if bindings.isEmpty then ""
    else
      val lines = bindings.toList
        .sortBy(_._1)
        .map: (group, ids) =>
          val quotedIds = ids.distinct.sorted.map(id => Json.Str(id).toJson).mkString(", ")
          s"- Keep group ${Json.Str(group).toJson} active while using any of these skills: $quotedIds."
      "Skill group reminders:\n" + lines.mkString("\n")

  def validateGroups(groups: ToolGroups): Either[AgentError.InvalidConfiguration, Unit] =
    definitions
      .flatMap(value => Chunk.fromIterable(value.activateGroups))
      .distinct
      .sorted
      .foldLeft[Either[AgentError.InvalidConfiguration, Unit]](Right(()))((result, name) =>
        result.flatMap(_ =>
          groups.group(name) match
            case Some(group) if group.scope == ToolGroupScope.Meta => Right(())
            case Some(_) =>
              Left(
                AgentError
                  .InvalidConfiguration(s"Skill target group '${name.take(80)}' is External")
              )
            case None =>
              Left(
                AgentError.InvalidConfiguration(s"Unknown skill target group '${name.take(80)}'")
              )
        )
      )

  def load(
      skillId: String,
      path: String
  ): Either[AgentError.ToolExecutionError, SkillCatalog.Resource] =
    for
      _ <- Either.cond(
        SkillCatalog.validPath(path),
        (),
        failure("Invalid logical skill resource path")
      )
      skill <- entries.get(skillId).toRight(failure(s"Unknown skill '${skillId.take(80)}'"))
      content <- (if path == "SKILL.md" then Some(skill.content) else skill.resources.get(path))
        .toRight(failure(s"Unknown skill resource '${path.take(80)}'"))
    yield SkillCatalog.Resource(content, skill.activateGroups)

  private def failure(message: String): AgentError.ToolExecutionError =
    AgentError.ToolExecutionError(SkillCatalog.LoadToolName, message)

object SkillCatalog:
  val empty: SkillCatalog  = new SkillCatalog(Map.empty)
  val LoadToolName: String = "load_skill_through_path"

  final case class Resource(content: String, activateGroups: Set[String])

  def make(
      definitions: Chunk[SkillDefinition]
  ): Either[AgentError.InvalidConfiguration, SkillCatalog] =
    definitions
      .foldLeft[Either[AgentError.InvalidConfiguration, Map[String, SkillDefinition]]](
        Right(Map.empty)
      )((result, definition) =>
        result.flatMap(entries =>
          for
            _ <- Either.cond(
              definition.id.trim.nonEmpty && definition.description.trim.nonEmpty && definition.content.trim.nonEmpty,
              (),
              AgentError.InvalidConfiguration("Skill ID, description and content must be nonblank")
            )
            _ <- Either.cond(
              !entries.contains(definition.id),
              (),
              AgentError.InvalidConfiguration(s"Duplicate skill ID '${definition.id.take(80)}'")
            )
            _ <- Either.cond(
              definition.activateGroups.forall(_.trim.nonEmpty),
              (),
              AgentError.InvalidConfiguration("Skill target group names must be nonblank")
            )
            _ <- Either.cond(
              definition.resources.keys.forall(path => path != "SKILL.md" && validPath(path)),
              (),
              AgentError.InvalidConfiguration(
                "Skill resource identifiers must be relative logical paths and cannot replace SKILL.md"
              )
            )
            _ <- JsonBoundary
              .validate(definition.metadata)
              .left
              .map(message => AgentError.InvalidConfiguration(s"Invalid skill metadata: $message"))
            _ <- Either.cond(
              !definition.metadata.fields.exists((key, _) => key == "name" || key == "description"),
              (),
              AgentError.InvalidConfiguration("Skill metadata cannot replace name or description")
            )
          yield entries.updated(definition.id, definition)
        )
      )
      .map(entries => new SkillCatalog(entries))

  private def validPath(path: String): Boolean =
    path.trim.nonEmpty && !path.exists(character =>
      character == '\\' || character == ':' || Character.isISOControl(character)
    ) &&
      path.split("/", -1).forall(segment => segment.nonEmpty && segment != "." && segment != "..")

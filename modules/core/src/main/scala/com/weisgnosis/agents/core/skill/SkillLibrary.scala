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
import zio.*
import zio.json.JsonCodec

/** Exact logical authority; no physical location is part of this model. */
final case class SkillResourceKey(skillId: String, path: String) derives JsonCodec

trait SkillResourceReader:
  def read: ZIO[Scope, AgentError, String]

object SkillResourceReader:
  def apply(effect: => ZIO[Scope, AgentError, String]): SkillResourceReader =
    new SkillResourceReader:
      def read: ZIO[Scope, AgentError, String] = ZIO.suspendSucceed(effect)

/** A checked catalogue and exact, borrowed resource capabilities; owns no activation state. */
final class SkillLibrary private (
    val catalog: SkillCatalog,
    readers: Map[SkillResourceKey, SkillResourceReader]
):
  def systemPrompt: String =
    catalog.renderSystemPrompt(
      readers.keys.toList.groupMap(_.skillId)(_.path).view.mapValues(_.toSet).toMap
    )

  def select(ids: Set[String]): Either[AgentError.InvalidConfiguration, SkillLibrary] =
    catalog
      .select(ids)
      .map(selected =>
        new SkillLibrary(selected, readers.filter((key, _) => ids.contains(key.skillId)))
      )

  /** Completes each read's scope before returning content or catalogue-owned activation targets. */
  def resolve(skillId: String, path: String): IO[AgentError, SkillCatalog.Resource] =
    readers.get(SkillResourceKey(skillId, path)) match
      case None => ZIO.fromEither(catalog.load(skillId, path))
      case Some(reader) =>
        for
          resource <- ZIO.fromEither(catalog.load(skillId, "SKILL.md"))
          content  <- ZIO.scoped(ZIO.suspendSucceed(reader.read))
        yield resource.copy(content = content)

object SkillLibrary:
  def from(skills: SkillCatalog | SkillLibrary): SkillLibrary = skills match
    case catalog: SkillCatalog => new SkillLibrary(catalog, Map.empty)
    case library: SkillLibrary => library

  def make(
      catalog: SkillCatalog,
      readers: Map[SkillResourceKey, SkillResourceReader]
  ): Either[AgentError.InvalidConfiguration, SkillLibrary] =
    val definitions = catalog.definitions.map(skill => skill.id -> skill).toMap
    readers.keys.toList
      .sortBy(key => (key.skillId, key.path))
      .foldLeft[Either[AgentError.InvalidConfiguration, Unit]](Right(()))((result, key) =>
        result.flatMap: _ =>
          definitions.get(key.skillId) match
            case None =>
              Left(
                AgentError.InvalidConfiguration(s"Unknown resource skill '${key.skillId.take(80)}'")
              )
            case Some(_) if key.path == "SKILL.md" || !SkillCatalog.validPath(key.path) =>
              Left(
                AgentError.InvalidConfiguration(
                  "Deferred resources require portable logical paths other than SKILL.md"
                )
              )
            case Some(skill) if skill.resources.contains(key.path) =>
              Left(
                AgentError.InvalidConfiguration(
                  s"Deferred resource conflicts with preloaded resource '${key.path.take(80)}'"
                )
              )
            case Some(_) => Right(())
      )
      .map(_ => new SkillLibrary(catalog, readers))

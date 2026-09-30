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
package com.weisgnosis.agents.harness.skill

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.skill.SkillDefinition
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Parse
import org.snakeyaml.engine.v2.events.*
import org.snakeyaml.engine.v2.nodes.Tag
import org.snakeyaml.engine.v2.resolver.CoreScalarResolver
import zio.*
import zio.json.JsonCodec

/** UTF-16 bounds; the header excludes delimiter lines, while the document includes the body. */
final case class SkillMarkdownLimits(
    maxDocumentCharacters: Int = 128000,
    maxHeaderCharacters: Int = 16000
) derives JsonCodec

/**
 * Strict inline SKILL.md profile: only name and description string fields, with exact body text.
 * Column-zero delimiters accept trailing spaces/tabs and LF or CRLF, without a leading BOM.
 * No filesystem access, metadata conversion, resource loading or group inference is performed.
 */
object SkillMarkdownParser:
  private type Header = Map[String, String]

  private enum State:
    case StreamStart, DocumentStart, Root
    case Key(values: Header)
    case Field(name: String, values: Header)
    case DocumentEnd(values: Header)
    case StreamEnd(values: Header)
    case Done(values: Header)

  def parse(
      markdown: String,
      limits: SkillMarkdownLimits = SkillMarkdownLimits()
  ): IO[AgentError, SkillDefinition] =
    for
      parts <- ZIO.fromEither(split(markdown, limits))
      (header, body) = parts
      reader <- parserEffect {
        val settings = LoadSettings.builder().setCodePointLimit(limits.maxHeaderCharacters).build()
        (new Parse(settings).parseString(header).iterator(), new CoreScalarResolver(false))
      }
      values <- consume(reader._1, reader._2, State.StreamStart)
      name   <- ZIO.fromOption(values.get("name")).orElseFail(invalid("name is required"))
      description <- ZIO
        .fromOption(values.get("description"))
        .orElseFail(invalid("description is required"))
      _ <- ZIO.fail(invalid("body must be nonblank")).when(body.isBlank)
    yield SkillDefinition(name, description, body)

  private def invalid(message: String): AgentError.InvalidConfiguration =
    AgentError.InvalidConfiguration("Invalid skill markdown: " + message)

  private def parserEffect[A](effect: => A): IO[AgentError, A] =
    ZIO
      .attempt(effect)
      .mapError(error => invalid(s"YAML syntax error (${error.getClass.getSimpleName.take(80)})"))

  private def split(
      markdown: String,
      limits: SkillMarkdownLimits
  ): Either[AgentError, (String, String)] =
    if limits.maxDocumentCharacters <= 0 || limits.maxHeaderCharacters <= 0 then
      Left(invalid("character limits must be positive"))
    else if markdown.length > limits.maxDocumentCharacters then
      Left(invalid("document character limit exceeded"))
    else
      "\\A---[ \\t]*(?:\\r?\\n|\\z)".r
        .findPrefixMatchOf(markdown)
        .toRight(invalid("missing opening delimiter line"))
        .flatMap: opening =>
          val remainder = markdown.drop(opening.end)
          "(?:\\A|(?<=\\n))---[ \\t]*(?:\\r?\\n|\\z)".r
            .findFirstMatchIn(remainder)
            .toRight(invalid("missing closing delimiter line"))
            .flatMap: closing =>
              val header = remainder.take(closing.start)
              if header.length > limits.maxHeaderCharacters then
                Left(invalid("header character limit exceeded"))
              else Right((header, remainder.drop(closing.end)))

  private def consume(
      iterator: java.util.Iterator[Event],
      resolver: CoreScalarResolver,
      state: State
  ): IO[AgentError, Header] =
    parserEffect(if iterator.hasNext then Some(iterator.next()) else None).flatMap:
      case None =>
        state match
          case State.Done(values) => ZIO.succeed(values)
          case _                  => ZIO.fail(invalid("incomplete YAML document"))
      case Some(event) =>
        parserEffect(advance(state, event, resolver))
          .flatMap(ZIO.fromEither(_))
          .flatMap(next => consume(iterator, resolver, next))

  /** Called only inside parserEffect, including every resolver and foreign event accessor. */
  private def advance(
      state: State,
      event: Event,
      resolver: CoreScalarResolver
  ): Either[AgentError, State] =
    val unsupported = event match
      case node: NodeEvent if node.getAnchor.isPresent                     => true
      case scalar: ScalarEvent if scalar.getTag.isPresent                  => true
      case collection: CollectionStartEvent if collection.getTag.isPresent => true
      case document: DocumentStartEvent
          if document.getSpecVersion.isPresent || !document.getTags.isEmpty =>
        true
      case _ => false
    if unsupported then
      Left(
        invalid("anchors, aliases, explicit tags and YAML version/tag directives are unsupported")
      )
    else
      (state, event) match
        case (State.StreamStart, _: StreamStartEvent)     => Right(State.DocumentStart)
        case (State.DocumentStart, _: DocumentStartEvent) => Right(State.Root)
        case (State.Root, _: MappingStartEvent)           => Right(State.Key(Map.empty))
        case (State.Key(values), _: MappingEndEvent)      => Right(State.DocumentEnd(values))
        case (State.Key(values), scalar: ScalarEvent) =>
          val key = scalar.getValue
          if key != "name" && key != "description" then Left(invalid("unsupported header field"))
          else if values.contains(key) then Left(invalid(s"duplicate field '$key'"))
          else Right(State.Field(key, values))
        case (State.Field(name, values), scalar: ScalarEvent) =>
          val value = scalar.getValue
          if resolver.resolve(value, scalar.isPlain) != Tag.STR || value.isBlank then
            Left(invalid(s"$name must be a nonblank string"))
          else Right(State.Key(values.updated(name, value)))
        case (State.DocumentEnd(values), _: DocumentEndEvent) => Right(State.StreamEnd(values))
        case (State.StreamEnd(values), _: StreamEndEvent)     => Right(State.Done(values))
        case _ => Left(invalid("expected one mapping with name and description strings"))

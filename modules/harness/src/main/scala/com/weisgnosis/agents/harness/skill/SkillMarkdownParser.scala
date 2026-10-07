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
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.skill.SkillDefinition
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Parse
import org.snakeyaml.engine.v2.events.*
import org.snakeyaml.engine.v2.nodes.Tag
import org.snakeyaml.engine.v2.resolver.CoreScalarResolver
import zio.*
import zio.json.JsonCodec
import zio.json.ast.Json

/** UTF-16 bounds; the header excludes delimiter lines, while the document includes the body. */
final case class SkillMarkdownLimits(
    maxDocumentCharacters: Int = 128000,
    maxHeaderCharacters: Int = 16000,
    maxMetadataDepth: Int = 10
) derives JsonCodec

/**
 * Strict inline SKILL.md profile: required name/description strings and inert JSON metadata.
 * Column-zero delimiters accept trailing spaces/tabs, LF/CRLF/CR and one optional initial BOM.
 * Extra-value collection depth excludes the frontmatter root; scalars have depth zero.
 * No filesystem access, resource loading or group inference is performed.
 */
object SkillMarkdownParser:
  private enum Frame:
    case Mapping(fields: Chunk[(String, Json)], pending: Option[String])
    case Sequence(values: Chunk[Json])

  private enum State:
    case StreamStart, DocumentStart, Root
    case Nodes(frames: List[Frame])
    case DocumentEnd(values: Json.Obj)
    case StreamEnd(values: Json.Obj)
    case Done(values: Json.Obj)

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
      values      <- consume(reader._1, reader._2, State.StreamStart, limits.maxMetadataDepth)
      name        <- ZIO.fromEither(requiredString(values, "name"))
      description <- ZIO.fromEither(requiredString(values, "description"))
      _           <- ZIO.fail(invalid("body must be nonblank")).when(body.isBlank)
    yield SkillDefinition(
      name,
      description,
      body,
      metadata =
        Json.Obj(values.fields.filterNot((key, _) => key == "name" || key == "description"))
    )

  private def requiredString(values: Json.Obj, name: String): Either[AgentError, String] =
    values.fields.find(_._1 == name).map(_._2) match
      case Some(Json.Str(value)) if !value.isBlank => Right(value)
      case _ => Left(invalid(s"$name must be a nonblank string"))

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
    if limits.maxDocumentCharacters <= 0 || limits.maxHeaderCharacters <= 0 || limits.maxMetadataDepth <= 0
    then Left(invalid("character and depth limits must be positive"))
    else if markdown.length > limits.maxDocumentCharacters then
      Left(invalid("document character limit exceeded"))
    else
      "\\A\uFEFF?---[ \\t]*(?:\\r\\n|[\\r\\n]|\\z)".r
        .findPrefixMatchOf(markdown)
        .toRight(invalid("missing opening delimiter line"))
        .flatMap: opening =>
          val remainder = markdown.drop(opening.end)
          "(?:\\A|(?<=[\\r\\n]))---[ \\t]*(?:\\r\\n|[\\r\\n]|\\z)".r
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
      state: State,
      maxDepth: Int
  ): IO[AgentError, Json.Obj] =
    parserEffect(if iterator.hasNext then Some(iterator.next()) else None).flatMap:
      case None =>
        state match
          case State.Done(values) => ZIO.succeed(values)
          case _                  => ZIO.fail(invalid("incomplete YAML document"))
      case Some(event) =>
        parserEffect(advance(state, event, resolver, maxDepth))
          .flatMap(ZIO.fromEither(_))
          .flatMap(next => consume(iterator, resolver, next, maxDepth))

  /** Called only inside parserEffect, including every resolver and foreign event accessor. */
  private def advance(
      state: State,
      event: Event,
      resolver: CoreScalarResolver,
      maxDepth: Int
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
        case (State.Root, _: MappingStartEvent) =>
          Right(State.Nodes(List(Frame.Mapping(Chunk.empty, None))))
        case (State.Nodes(Frame.Mapping(fields, None) :: rest), scalar: ScalarEvent) =>
          val key = scalar.getValue
          if resolver.resolve(key, scalar.isPlain) != Tag.STR then
            Left(invalid("mapping keys must be strings"))
          else if fields.exists(_._1 == key) then
            Left(invalid(s"duplicate field '${key.take(80)}'"))
          else Right(State.Nodes(Frame.Mapping(fields, Some(key)) :: rest))
        case (State.Nodes(frames), scalar: ScalarEvent) =>
          scalarValue(scalar, resolver).flatMap(value => complete(value, frames))
        case (State.Nodes(frames), _: MappingStartEvent) =>
          descend(Frame.Mapping(Chunk.empty, None), frames, maxDepth)
        case (State.Nodes(frames), _: SequenceStartEvent) =>
          descend(Frame.Sequence(Chunk.empty), frames, maxDepth)
        case (State.Nodes(Frame.Mapping(fields, None) :: rest), _: MappingEndEvent) =>
          complete(Json.Obj(fields), rest)
        case (State.Nodes(Frame.Sequence(values) :: rest), _: SequenceEndEvent) =>
          complete(Json.Arr(values), rest)
        case (State.DocumentEnd(values), _: DocumentEndEvent) => Right(State.StreamEnd(values))
        case (State.StreamEnd(values), _: StreamEndEvent)     => Right(State.Done(values))
        case _ => Left(invalid("expected one mapping with name and description strings"))

  private def descend(frame: Frame, frames: List[Frame], maxDepth: Int): Either[AgentError, State] =
    frames match
      case Frame.Mapping(_, None) :: _   => Left(invalid("mapping keys must be strings"))
      case _ if frames.length > maxDepth => Left(invalid("metadata collection depth exceeded"))
      case _                             => Right(State.Nodes(frame :: frames))

  private def complete(value: Json, frames: List[Frame]): Either[AgentError, State] =
    frames match
      case Frame.Mapping(fields, Some(key)) :: rest =>
        Right(State.Nodes(Frame.Mapping(fields :+ (key -> value), None) :: rest))
      case Frame.Sequence(values) :: rest =>
        Right(State.Nodes(Frame.Sequence(values :+ value) :: rest))
      case Nil =>
        value match
          case objectValue: Json.Obj => Right(State.DocumentEnd(objectValue))
          case _                     => Left(invalid("frontmatter must be a mapping"))
      case _ => Left(invalid("expected a mapping value"))

  /** The resolver and checked numeric conversion execute only inside parserEffect. */
  private def scalarValue(
      scalar: ScalarEvent,
      resolver: CoreScalarResolver
  ): Either[AgentError, Json] =
    val value = scalar.getValue
    resolver.resolve(value, scalar.isPlain) match
      case Tag.STR  => Right(Json.Str(value))
      case Tag.NULL => Right(Json.Null)
      case Tag.BOOL => Right(Json.Bool(value.equalsIgnoreCase("true")))
      case Tag.INT | Tag.FLOAT =>
        JsonBoundary
          .decode(value)
          .left
          .map(_ => invalid("metadata numbers must be finite JSON numbers"))
          .flatMap:
            case number: Json.Num => Right(number)
            case _                => Left(invalid("metadata numbers must be finite JSON numbers"))
      case _ => Left(invalid("unsupported metadata scalar tag"))

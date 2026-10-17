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
package com.weisgnosis.agents.harness.subagent

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.client.TemperaturePolicy
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Parse
import org.snakeyaml.engine.v2.events.*
import zio.*
import zio.json.*

/** UTF-16 limits: the document includes delimiters/body; the header excludes delimiter lines. */
final case class SubagentDeclarationLimits(
    maxDocumentCharacters: Int = 128000,
    maxHeaderCharacters: Int = 16000
) derives JsonCodec

/**
 * Bounded inline frontmatter only: no path resolution, object loading or capability acquisition.
 * Column-zero delimiter lines accept trailing spaces/tabs, LF/CRLF/CR and one optional initial BOM.
 * Scalars are textual except plain YAML null spellings; quoted null remains literal text.
 */
object SubagentDeclarationParser:
  private enum Value:
    case Scalar(text: Option[String])
    case Names(names: Set[String])

  private type Header = Map[String, Value]

  private enum State:
    case StreamStart, DocumentStart, Root
    case Key(values: Header)
    case Field(name: String, values: Header)
    case Names(field: String, values: Header, names: Set[String])
    case DocumentEnd(values: Header)
    case StreamEnd(values: Header)
    case Done(values: Header)

  private val fields: Set[String] =
    Set(
      "description",
      "model",
      "temperature",
      "temperature_policy",
      "top_p",
      "topP",
      "tools",
      "skills",
      "steps",
      "maxIters",
      "mode",
      "hidden"
    )

  def parse(
      name: String,
      markdown: String,
      limits: SubagentDeclarationLimits = SubagentDeclarationLimits()
  ): IO[AgentError, SubagentDeclaration] =
    for
      parts <- ZIO.fromEither(split(markdown, limits))
      (header, body) = parts
      iterator <- parserEffect {
        val settings = LoadSettings.builder().setCodePointLimit(limits.maxHeaderCharacters).build()
        new Parse(settings).parseString(header).iterator()
      }
      values      <- consume(iterator, State.StreamStart)
      declaration <- ZIO.fromEither(project(name, body, values))
    yield declaration

  private def invalid(message: String): AgentError.InvalidConfiguration =
    AgentError.InvalidConfiguration("Invalid subagent declaration: " + message)

  private def parserEffect[A](effect: => A): IO[AgentError, A] =
    ZIO
      .attempt(effect)
      .mapError(error => invalid(s"YAML syntax error (${error.getClass.getSimpleName.take(80)})"))

  private def split(
      markdown: String,
      limits: SubagentDeclarationLimits
  ): Either[AgentError, (String, String)] =
    if limits.maxDocumentCharacters <= 0 || limits.maxHeaderCharacters <= 0 then
      Left(invalid("character limits must be positive"))
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

  private def consume(iterator: java.util.Iterator[Event], state: State): IO[AgentError, Header] =
    parserEffect(if iterator.hasNext then Some(iterator.next()) else None).flatMap:
      case None =>
        state match
          case State.Done(values) => ZIO.succeed(values)
          case _                  => ZIO.fail(invalid("incomplete YAML document"))
      case Some(event) =>
        ZIO.fromEither(advance(state, event)).flatMap(next => consume(iterator, next))

  private def advance(state: State, event: Event): Either[AgentError, State] =
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
          if !fields.contains(key) then Left(invalid("unsupported header field"))
          else if values.contains(key) then Left(invalid(s"duplicate field '$key'"))
          else Right(State.Field(key, values))
        case (State.Field(name, values), scalar: ScalarEvent) =>
          Right(State.Key(values.updated(name, Value.Scalar(text(scalar)))))
        case (State.Field(name, values), _: SequenceStartEvent)
            if name == "tools" || name == "skills" =>
          Right(State.Names(name, values, Set.empty))
        case (State.Names(field, values, names), scalar: ScalarEvent) =>
          text(scalar)
            .filterNot(_.isBlank)
            .toRight(invalid(s"${nameLabel(field)} must be nonblank scalars"))
            .map(value => State.Names(field, values, names + value.trim))
        case (State.Names(field, values, names), _: SequenceEndEvent) =>
          Right(State.Key(values.updated(field, Value.Names(names))))
        case (State.DocumentEnd(values), _: DocumentEndEvent) => Right(State.StreamEnd(values))
        case (State.StreamEnd(values), _: StreamEndEvent)     => Right(State.Done(values))
        case _ =>
          Left(
            invalid("expected one scalar-valued mapping with optional tools or skills sequences")
          )

  private def text(scalar: ScalarEvent): Option[String] =
    val value = scalar.getValue
    if scalar.isPlain && Set("", "~", "null", "Null", "NULL").contains(value) then None
    else Some(value)

  private def scalar(values: Header, name: String): Option[String] =
    values.get(name).collect { case Value.Scalar(Some(value)) => value }

  private def integer(value: String): Either[AgentError, Int] =
    Option
      .when(value.matches("[+-]?[0-9]+"))(value.toIntOption)
      .flatten
      .filter(_ > 0)
      .toRight(invalid("iteration overrides must be positive decimal Int values"))

  private def decimal(value: String, field: String): Either[AgentError, Double] =
    Option
      .when(value.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))(
        value.toDoubleOption
      )
      .flatten
      .filter(_.isFinite)
      .toRight(invalid(s"$field must be a finite decimal number"))

  private def optional[A](
      value: Option[String],
      decode: String => Either[AgentError, A]
  ): Either[AgentError, Option[A]] =
    value.fold[Either[AgentError, Option[A]]](Right(None))(text => decode(text).map(Some(_)))

  private def nameLabel(field: String): String =
    if field == "tools" then "tool names" else "skill IDs"

  private def selection(values: Header, field: String): Either[AgentError, Option[Set[String]]] =
    values.get(field) match
      case None | Some(Value.Scalar(None)) => Right(None)
      case Some(Value.Names(names))        => Right(Some(names))
      case Some(Value.Scalar(Some(value))) =>
        val names =
          if value.isBlank then Set.empty[String] else value.split(",", -1).toSet.map(_.trim)
        if names.exists(_.isBlank) then
          Left(invalid(s"comma-separated ${nameLabel(field)} must be nonblank"))
        else Right(Some(names))

  private def project(
      name: String,
      body: String,
      values: Header
  ): Either[AgentError, SubagentDeclaration] =
    for
      description <- scalar(values, "description").toRight(invalid("description is required"))
      tools       <- selection(values, "tools")
      skills      <- selection(values, "skills")
      sampled     <- optional(scalar(values, "temperature"), decimal(_, "temperature"))
      temperaturePolicy <- optional(
        scalar(values, "temperature_policy"),
        value =>
          value.trim.toLowerCase(java.util.Locale.ROOT) match
            case "configured" => Right(TemperaturePolicy.Configured)
            case "omit"       => Right(TemperaturePolicy.Omit)
            case _            => Left(invalid("temperature_policy must be configured or omit"))
      )
      _ <- Either.cond(
        !(values.contains("top_p") && values.contains("topP")),
        (),
        invalid("top_p and topP cannot both be supplied")
      )
      topP  <- optional(scalar(values, "top_p").orElse(scalar(values, "topP")), decimal(_, "topP"))
      steps <- optional(scalar(values, "steps"), integer)
      maxIters <- optional(scalar(values, "maxIters"), integer)
      visibility <- values.get("hidden") match
        case None => Right(SubagentVisibility.Visible)
        case Some(Value.Scalar(Some(value))) =>
          value.trim.toLowerCase(java.util.Locale.ROOT) match
            case "false" => Right(SubagentVisibility.Visible)
            case "true"  => Right(SubagentVisibility.Hidden)
            case _       => Left(invalid("hidden must be true or false"))
        case _ => Left(invalid("hidden must be true or false"))
      // Unknown names inherit ALL, matching upstream declaration-loader compatibility.
      mode = scalar(values, "mode").map(_.trim.toLowerCase(java.util.Locale.ROOT)) match
        case Some("primary")  => SubagentMode.Primary
        case Some("subagent") => SubagentMode.Subagent
        case _                => SubagentMode.All
      declaration <- SubagentDeclaration(
        name,
        description,
        body,
        tools.fold[SubagentToolSelection](SubagentToolSelection.AllAvailable)(
          SubagentToolSelection.Only(_)
        ),
        scalar(values, "model"),
        sampled,
        steps.orElse(maxIters),
        skills.fold[SubagentSkillSelection](SubagentSkillSelection.AllAvailable)(
          SubagentSkillSelection.Only(_)
        ),
        topP,
        mode,
        visibility,
        temperaturePolicy
      ).validated
    yield declaration

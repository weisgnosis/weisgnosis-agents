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
package com.weisgnosis.agents.core.tool

import com.fasterxml.jackson.databind.{DeserializationFeature, JsonNode}
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.node.{DecimalNode, TextNode}
import com.networknt.schema.{
  Schema,
  SchemaContext,
  SchemaLocation,
  SchemaRegistry,
  ExecutionContext,
  Specification,
  SpecificationVersion
}
import com.networknt.schema.dialect.Dialect
import com.networknt.schema.path.NodePath
import com.networknt.schema.keyword.{
  DynamicRefValidator,
  Keyword,
  KeywordValidator,
  MultipleOfValidator,
  RefValidator
}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import zio.*
import zio.json.*
import zio.json.ast.Json
import scala.jdk.CollectionConverters.*
import scala.util.Try
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.util.regex.Matcher

/** Suspended standards-backed admission; normalization never changes dispatched arguments. */
private[core] object ToolSchemaValidator:
  private enum StandardDraft(val id: String, val version: SpecificationVersion):
    case Draft202012
        extends StandardDraft(
          "https://json-schema.org/draft/2020-12/schema",
          SpecificationVersion.DRAFT_2020_12
        )
    case Draft201909
        extends StandardDraft(
          "https://json-schema.org/draft/2019-09/schema",
          SpecificationVersion.DRAFT_2019_09
        )
    case Draft7
        extends StandardDraft(
          "http://json-schema.org/draft-07/schema#",
          SpecificationVersion.DRAFT_7
        )
    case Draft6
        extends StandardDraft(
          "http://json-schema.org/draft-06/schema#",
          SpecificationVersion.DRAFT_6
        )
    case Draft4
        extends StandardDraft(
          "http://json-schema.org/draft-04/schema#",
          SpecificationVersion.DRAFT_4
        )

  private val dialect: String = "https://json-schema.org/draft/2020-12/schema"
  private val standardResources: Set[String] = Set(
    dialect,
    "https://json-schema.org/draft/2020-12/meta/core",
    "https://json-schema.org/draft/2020-12/meta/applicator",
    "https://json-schema.org/draft/2020-12/meta/unevaluated",
    "https://json-schema.org/draft/2020-12/meta/validation",
    "https://json-schema.org/draft/2020-12/meta/meta-data",
    "https://json-schema.org/draft/2020-12/meta/format-annotation",
    "https://json-schema.org/draft/2020-12/meta/format-assertion",
    "https://json-schema.org/draft/2020-12/meta/content"
  ) ++ StandardDraft.values.toSet.flatMap(value => Set(value.id, value.id.stripSuffix("#"))) ++
    Set("core", "applicator", "validation", "meta-data", "format", "content")
      .map(name => s"https://json-schema.org/draft/2019-09/meta/$name")

  private def declaredDraft(
      schema: Json,
      inherited: StandardDraft
  ): Either[AgentError.InvalidMessage, StandardDraft] =
    schema match
      case obj: Json.Obj =>
        member(obj, "$schema") match
          case None => Right(inherited)
          case Some(Json.Str(value)) =>
            StandardDraft.values
              .find(_.id == value)
              .toRight(AgentError.InvalidMessage("Unsupported declared tool schema dialect"))
          case _ =>
            Left(AgentError.InvalidMessage("Tool schema dialect must be a standard URI string"))
      case _ => Right(inherited)

  def validate(schema: Json, input: Json): IO[AgentError.InvalidMessage, Unit] =
    for
      _ <- ZIO.fromEither(
        JsonBoundary
          .validate(schema)
          .flatMap(_ => JsonBoundary.validate(input))
          .left
          .map(reason => AgentError.InvalidMessage(s"Invalid tool JSON: $reason"))
      )
      _ <-
        if schema == Json.Obj() then ZIO.unit
        else
          for
            selected   <- ZIO.fromEither(declaredDraft(schema, StandardDraft.Draft202012))
            view       <- ZIO.fromEither(normalize(input, schema, schema))
            references <- ZIO.fromEither(canonicalReferences(schema))
            errors <- ZIO
              .attempt:
                // Each admission owns its evaluator state. Nothing is initialized in a pure factory.
                val mapper = JsonMapper
                  .builder()
                  .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                  .build()
                // The standard keyword's Double zero guard underflows for positive tiny divisors.
                // Meta-validation checks positivity; retain the original decimal divisor exactly.
                val exactMultipleOf = new Keyword:
                  def getValue(): String = "multipleOf"
                  def newValidator(
                      location: SchemaLocation,
                      node: JsonNode,
                      parent: Schema,
                      context: SchemaContext
                  ): KeywordValidator =
                    new MultipleOfValidator(location, node, parent, context):
                      override protected def getDivisor(value: JsonNode): java.math.BigDecimal =
                        value.decimalValue()
                      override def validate(
                          execution: ExecutionContext,
                          value: JsonNode,
                          root: JsonNode,
                          path: NodePath
                      ): Unit =
                        if value.isNumber then
                          super.validate(
                            execution,
                            DecimalNode.valueOf(value.decimalValue()),
                            root,
                            path
                          )
                val exactDialects = StandardDraft.values.toVector.map: profile =>
                  val builder = Dialect
                    .builder(Specification.getDialect(profile.version))
                    .keyword(exactMultipleOf)
                    .keyword(referenceKeyword("$ref", references))
                  profile match
                    case StandardDraft.Draft202012 =>
                      builder.keyword(referenceKeyword("$dynamicRef", references)).build()
                    case _ => builder.build()
                val registry = SchemaRegistry.withDialects(
                  exactDialects.asJava,
                  builder =>
                    builder
                      .schemaCacheEnabled(false)
                      .nodeReader(reader => reader.jsonMapper(mapper))
                      .schemaLoader(loader =>
                        loader
                          .fetchRemoteResources(false)
                          .allow(iri => standardResources.contains(iri.toString))
                      )
                    ()
                )
                val schemaNode = mapper.readTree(exactJson(schema))
                val malformed =
                  registry.getSchema(SchemaLocation.of(selected.id)).validate(schemaNode)
                val failures =
                  if !malformed.isEmpty then malformed
                  else registry.getSchema(schemaNode).validate(mapper.readTree(exactJson(view)))
                failures.asScala.iterator
                  .take(8)
                  .map(error => s"${error.getInstanceLocation}: ${error.getMessage}")
                  .toVector
              .mapError(error =>
                AgentError.InvalidMessage(
                  s"Tool schema evaluation failed (${error.getClass.getSimpleName})"
                )
              )
            _ <- ZIO.fail(AgentError.InvalidMessage(errors.mkString("; "))).when(errors.nonEmpty)
          yield ()
    yield ()

  // Preserve integer node kinds for Draft 4; the multipleOf adapter handles their exact conversion.
  // Decimal text remains exact without expanding large exponents or using the AST's numeric encoder.
  private def exactJson(value: Json): String = value match
    case Json.Num(number) => number.toString
    case Json.Obj(fields) =>
      fields
        .map((name, item) => Json.Str(name).toJson + ":" + exactJson(item))
        .mkString("{", ",", "}")
    case Json.Arr(items) => items.map(exactJson).mkString("[", ",", "]")
    case other           => other.toJson

  private def normalize(
      input: Json,
      schema: Json,
      root: Json
  ): Either[AgentError.InvalidMessage, Json] =
    resolve(schema, root, Set.empty).flatMap: resolved =>
      (input, resolved) match
        case (Json.Obj(fields), obj: Json.Obj) =>
          val properties = member(obj, "properties").collect { case value: Json.Obj => value }
          val required = member(obj, "required")
            .collect { case Json.Arr(values) =>
              values.collect { case Json.Str(name) => name }.toSet
            }
            .getOrElse(Set.empty)
          val additional = member(obj, "additionalProperties").collect { case value: Json.Obj =>
            value
          }
          fields
            .foldLeft[Either[AgentError.InvalidMessage, Chunk[(String, Json)]]](Right(Chunk.empty)):
              case (result, (name, value)) =>
                result.flatMap: accumulated =>
                  val declared = properties.flatMap(member(_, name))
                  if value == Json.Null && declared.nonEmpty && !required.contains(name) then
                    Right(accumulated)
                  else
                    declared.orElse(additional) match
                      case Some(child) =>
                        normalize(value, child, root).map(item => accumulated :+ (name -> item))
                      case None => Right(accumulated :+ (name -> value))
            .map(Json.Obj(_))
        case (Json.Arr(items), obj: Json.Obj) =>
          member(obj, "items") match
            case Some(Json.Arr(children)) =>
              items.zipWithIndex
                .foldLeft[Either[AgentError.InvalidMessage, Chunk[Json]]](Right(Chunk.empty)):
                  case (result, (value, index)) =>
                    result.flatMap: values =>
                      children.lift(index).orElse(member(obj, "additionalItems")) match
                        case Some(child) => normalize(value, child, root).map(values :+ _)
                        case None        => Right(values :+ value)
                .map(Json.Arr(_))
            case Some(child) =>
              items
                .foldLeft[Either[AgentError.InvalidMessage, Chunk[Json]]](Right(Chunk.empty))(
                  (result, value) =>
                    result.flatMap(values => normalize(value, child, root).map(values :+ _))
                )
                .map(Json.Arr(_))
            case None => Right(input)
        case _ => Right(input)

  private def resolve(
      schema: Json,
      root: Json,
      seen: Set[String]
  ): Either[AgentError.InvalidMessage, Json] =
    schema match
      case obj: Json.Obj =>
        member(obj, "$ref") match
          case Some(Json.Str(ref)) if ref.startsWith("#") =>
            decodeFragment(ref).flatMap: fragment =>
              if fragment.nonEmpty && !fragment.startsWith("/") then Right(schema)
              else if seen.contains(ref) then
                Left(AgentError.InvalidMessage("Cyclic tool schema reference"))
              else
                val segments =
                  if fragment.isEmpty then Vector.empty
                  else fragment.drop(1).split("/", -1).toVector
                segments.foldLeft[Option[Json]](Some(root)):
                  case (current, segment) =>
                    val name = segment.replace("~1", "/").replace("~0", "~")
                    current.flatMap:
                      case value: Json.Obj  => member(value, name)
                      case Json.Arr(values) => name.toIntOption.filter(_ >= 0).flatMap(values.lift)
                      case _                => None
                match
                  case Some(value) => resolve(value, root, seen + ref)
                  case None =>
                    Left(AgentError.InvalidMessage("Unresolved local tool schema reference"))
          case _ => Right(schema)
      case _ => Right(schema)

  private def member(obj: Json.Obj, name: String): Option[Json] =
    obj.fields.find(_._1 == name).map(_._2)

  private def referenceKeyword(name: String, references: Map[String, String]): Keyword =
    new Keyword:
      def getValue(): String = name
      def newValidator(
          location: SchemaLocation,
          node: JsonNode,
          parent: Schema,
          context: SchemaContext
      ): KeywordValidator =
        val canonical = TextNode.valueOf(references.getOrElse(node.asText(), node.asText()))
        if name == "$dynamicRef" then new DynamicRefValidator(location, canonical, parent, context)
        else new RefValidator(location, canonical, parent, context)

  /** Inspect schema-bearing keywords only; const/enum/examples remain ordinary instance data. */
  private def canonicalReferences(
      schema: Json
  ): Either[AgentError.InvalidMessage, Map[String, String]] =
    def children(obj: Json.Obj, profile: StandardDraft): List[Json] =
      val modern = profile match
        case StandardDraft.Draft202012 | StandardDraft.Draft201909 =>
          Set("unevaluatedProperties", "unevaluatedItems", "contentSchema")
        case _ => Set.empty[String]
      val conditional = profile match
        case StandardDraft.Draft4 | StandardDraft.Draft6 => Set.empty[String]
        case _                                           => Set("if", "then", "else")
      val newer = profile match
        case StandardDraft.Draft4 => Set.empty[String]
        case _                    => Set("contains", "propertyNames")
      val single = Set("additionalProperties", "not") ++ modern ++ conditional ++ newer
      val mapped = Set("definitions", "properties", "patternProperties") ++ (profile match
        case StandardDraft.Draft202012 | StandardDraft.Draft201909 =>
          Set("$defs", "dependentSchemas")
        case _ => Set("dependencies"))
      val arrays = Set("allOf", "anyOf", "oneOf") ++ (profile match
        case StandardDraft.Draft202012 => Set("prefixItems")
        case _                         => Set.empty[String])
      obj.fields.toList.flatMap: (name, value) =>
        if single.contains(name) then List(value)
        else if name == "items" || name == "additionalItems" then
          (profile, value, name) match
            case (StandardDraft.Draft202012, _, "additionalItems") => Nil
            case (StandardDraft.Draft202012, _, _)                 => List(value)
            case (_, Json.Arr(items), _)                           => items.toList
            case _                                                 => List(value)
        else if mapped.contains(name) then
          value match
            case Json.Obj(fields) => fields.map(_._2).toList
            case _                => Nil
        else if arrays.contains(name) then
          value match
            case Json.Arr(items) => items.toList
            case _               => Nil
        else Nil

    @annotation.tailrec
    def loop(
        pending: List[(Json, StandardDraft)],
        known: Map[String, String]
    ): Either[AgentError.InvalidMessage, Map[String, String]] = pending match
      case Nil => Right(known)
      case ((obj: Json.Obj, inherited)) :: rest =>
        val checked = declaredDraft(obj, inherited).flatMap: profile =>
          val keywords = profile match
            case StandardDraft.Draft202012 => Chunk("$ref", "$dynamicRef")
            case _                         => Chunk("$ref")
          keywords
            .foldLeft[Either[AgentError.InvalidMessage, Map[String, String]]](Right(known)):
              (result, keyword) =>
                result.flatMap: values =>
                  member(obj, keyword) match
                    case Some(Json.Str(ref)) if ref.startsWith("#") =>
                      decodeFragment(ref).map: fragment =>
                        val canonical =
                          if fragment.startsWith("/") then
                            "#" + java.net.URLEncoder
                              .encode(fragment, StandardCharsets.UTF_8)
                              .replace("%2F", "/")
                              .replace("%7E", "~")
                              .replace("+", "%20")
                          else ref
                        values.updated(ref, canonical)
                    case _ => Right(values)
            .map(values => (profile, values))
        checked match
          case Left(error) => Left(error)
          case Right((profile, updated)) =>
            loop(children(obj, profile).map(_ -> profile) ::: rest, updated)
      case _ :: rest => loop(rest, known)
    loop(List(schema -> StandardDraft.Draft202012), Map.empty)

  private def decodeFragment(ref: String): Either[AgentError.InvalidMessage, String] =
    Try:
      val fragment = java.net.URI.create(ref).getRawFragment
      "(?:%[0-9a-fA-F]{2})+".r.replaceAllIn(
        fragment,
        matched =>
          val bytes = matched.matched
            .grouped(3)
            .map(value => Integer.parseInt(value.drop(1), 16).toByte)
            .toArray
          val text = StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString
          Matcher.quoteReplacement(text)
      )
    .toEither.left.map(_ =>
      AgentError.InvalidMessage("Invalid local tool schema reference encoding")
    )

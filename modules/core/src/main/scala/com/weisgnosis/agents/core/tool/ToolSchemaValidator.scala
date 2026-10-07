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
import com.fasterxml.jackson.databind.node.TextNode
import com.networknt.schema.{
  Schema,
  SchemaContext,
  SchemaLocation,
  SchemaRegistry,
  Specification,
  SpecificationVersion
}
import com.networknt.schema.dialect.Dialect
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
  )

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
                val exactDialect = Dialect
                  .builder(Specification.getDialect(SpecificationVersion.DRAFT_2020_12))
                  .keyword(exactMultipleOf)
                  .keyword(referenceKeyword("$ref", references))
                  .keyword(referenceKeyword("$dynamicRef", references))
                  .build()
                val registry = SchemaRegistry.withDialect(
                  exactDialect,
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
                val schemaNode = mapper.readTree(decimalJson(schema))
                val malformed  = registry.getSchema(SchemaLocation.of(dialect)).validate(schemaNode)
                val failures =
                  if !malformed.isEmpty then malformed
                  else registry.getSchema(schemaNode).validate(mapper.readTree(decimalJson(view)))
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

  // The evaluator's integer multipleOf path converts through Double. Decimal nodes keep all numeric
  // keywords exact, including mathematically integral values, without expanding large exponents.
  private def decimalJson(value: Json): String = value match
    case Json.Num(number) =>
      val text = number.toString
      if text.exists(char => char == '.' || char == 'e' || char == 'E') then text else text + ".0"
    case Json.Obj(fields) =>
      fields
        .map((name, item) => Json.Str(name).toJson + ":" + decimalJson(item))
        .mkString("{", ",", "}")
    case Json.Arr(items) => items.map(decimalJson).mkString("[", ",", "]")
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
    val single = Set(
      "additionalProperties",
      "unevaluatedProperties",
      "items",
      "contains",
      "propertyNames",
      "not",
      "if",
      "then",
      "else",
      "unevaluatedItems",
      "contentSchema"
    )
    val mapped = Set("$defs", "definitions", "properties", "patternProperties", "dependentSchemas")
    val arrays = Set("allOf", "anyOf", "oneOf", "prefixItems")
    @annotation.tailrec
    def loop(
        pending: List[Json],
        known: Map[String, String]
    ): Either[AgentError.InvalidMessage, Map[String, String]] =
      pending match
        case Nil => Right(known)
        case (obj: Json.Obj) :: rest =>
          val checked = Chunk("$ref", "$dynamicRef")
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
          checked match
            case Left(error) => Left(error)
            case Right(updated) =>
              val children = obj.fields.toList.flatMap: (name, value) =>
                if single.contains(name) then List(value)
                else if mapped.contains(name) then
                  value match
                    case Json.Obj(fields) => fields.map(_._2).toList
                    case _                => Nil
                else if arrays.contains(name) then
                  value match
                    case Json.Arr(items) => items.toList
                    case _               => Nil
                else Nil
              loop(children ::: rest, updated)
        case _ :: rest => loop(rest, known)
    loop(List(schema), Map.empty)

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

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
package com.weisgnosis.agents.core.json

import zio.json.*
import zio.json.ast.Json

/** Shared wire validation rejects ambiguous keys before interpreting external data. */
private[agents] object JsonBoundary:
  private enum StringPosition:
    case Outside, Inside, Escaped

  private enum RenderToken:
    case Value(value: Json)
    case Text(value: String)

  private val numberPattern = "-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?".r

  def decode(value: String): Either[String, Json] =
    for
      json      <- value.fromJson[Json]
      _         <- completeDocument(value, json)
      validated <- validate(json)
    yield validated

  /** Checks immutable AST fields directly, preserving the supplied JSON representation. */
  def validate(value: Json): Either[String, Json] =
    uniqueFields(value).map(_ => value)

  /** Exact decimal rendering avoids the dependency's minimum-Long unscaled-value overflow. */
  def render(value: Json): String =
    @annotation.tailrec
    def loop(pending: List[RenderToken], output: List[String]): String = pending match
      case Nil                            => output.reverse.mkString
      case RenderToken.Text(text) :: rest => loop(rest, text :: output)
      case RenderToken.Value(json) :: rest =>
        json match
          case Json.Num(number) => loop(rest, number.toString :: output)
          case Json.Str(text)   => loop(rest, text.toJson :: output)
          case Json.Bool(value) => loop(rest, value.toString :: output)
          case Json.Null        => loop(rest, "null" :: output)
          case Json.Arr(values) =>
            val elements = values.zipWithIndex.toList.flatMap: (item, index) =>
              val prefix = if index == 0 then Nil else List(RenderToken.Text(","))
              prefix ::: List(RenderToken.Value(item))
            loop(RenderToken.Text("[") :: (elements ::: (RenderToken.Text("]") :: rest)), output)
          case Json.Obj(fields) =>
            val members = fields.zipWithIndex.toList.flatMap: (field, index) =>
              val (key, item) = field
              val prefix      = if index == 0 then Nil else List(RenderToken.Text(","))
              prefix ::: List(
                RenderToken.Text(key.toJson),
                RenderToken.Text(":"),
                RenderToken.Value(item)
              )
            loop(RenderToken.Text("{") :: (members ::: (RenderToken.Text("}") :: rest)), output)
    loop(List(RenderToken.Value(value)), Nil)

  private def whitespace(value: Char): Boolean =
    value == ' ' || value == '\t' || value == '\r' || value == '\n'

  /** The library validates grammar but permits trailing input; validate its document boundary. */
  private def completeDocument(value: String, json: Json): Either[String, Unit] =
    val input = value.dropWhile(whitespace).reverse.dropWhile(whitespace).reverse
    json match
      case Json.Bool(boolean) =>
        Either.cond(input == boolean.toString, (), "Trailing input after JSON boolean")
      case Json.Null => Either.cond(input == "null", (), "Trailing input after JSON null")
      case _: Json.Num =>
        Either.cond(numberPattern.matches(input), (), "Invalid complete JSON number")
      case _ =>
        documentEnd(input).flatMap(end =>
          Either.cond(end == input.length, (), "Trailing input after JSON value")
        )

  private def documentEnd(input: String): Either[String, Int] =
    @annotation.tailrec
    def scan(index: Int, closing: List[Char], position: StringPosition): Either[String, Int] =
      if index >= input.length then Left("Incomplete JSON document")
      else
        val current = input.charAt(index)
        position match
          case StringPosition.Escaped => scan(index + 1, closing, StringPosition.Inside)
          case StringPosition.Inside =>
            current match
              case '\\'                   => scan(index + 1, closing, StringPosition.Escaped)
              case '"' if closing.isEmpty => Right(index + 1)
              case '"'                    => scan(index + 1, closing, StringPosition.Outside)
              case _                      => scan(index + 1, closing, StringPosition.Inside)
          case StringPosition.Outside =>
            current match
              case '"' => scan(index + 1, closing, StringPosition.Inside)
              case '{' => scan(index + 1, '}' :: closing, StringPosition.Outside)
              case '[' => scan(index + 1, ']' :: closing, StringPosition.Outside)
              case '}' | ']' =>
                closing match
                  case expected :: rest if current == expected =>
                    if rest.isEmpty then Right(index + 1)
                    else scan(index + 1, rest, StringPosition.Outside)
                  case _ => Left("Unmatched JSON container boundary")
              case _ => scan(index + 1, closing, StringPosition.Outside)
    scan(0, Nil, StringPosition.Outside)

  private def uniqueFields(json: Json): Either[String, Unit] =
    @annotation.tailrec
    def loop(pending: List[Json]): Either[String, Unit] = pending match
      case Nil => Right(())
      case Json.Obj(fields) :: rest =>
        if fields.map(_._1).distinct.size != fields.size then Left("Duplicate JSON object fields")
        else loop(fields.map(_._2).toList ::: rest)
      case Json.Arr(values) :: rest => loop(values.toList ::: rest)
      case _ :: rest                => loop(rest)
    loop(List(json))

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
package com.weisgnosis.agents.core.agent

import com.weisgnosis.agents.core.model.{ContentBlock, Msg, Role}
import com.weisgnosis.agents.core.tool.{Tool, ToolDef}
import zio.*
import zio.json.*
import zio.json.ast.Json

/**
 * Operational mode for structured output generation.
 */
enum StructuredMode derives JsonCodec:
  case Native
  case SyntheticTool
  case Auto

/**
 * Utilities and synthetic tool definitions for enforcing structured JSON output.
 *
 * Implements invariant INV-11 (Root Schema Hoisting) for nested schemas containing `$defs`.
 */
object StructuredOutput:

  val ToolName: String                       = "generate_response"
  val MetadataStructuredOutput: String       = "_structured_output"
  val MetadataStructuredReminder: String     = "_structured_output_reminder"
  val MetadataStructuredReminderType: String = "_structured_output_reminder_type"

  /**
   * Hoists `$defs` or `definitions` from a nested schema up to the outer level (Invariant INV-11).
   */
  def hoistDefs(schema: Json): (Json, Option[Json]) =
    schema match
      case Json.Obj(fields) =>
        val (defsFields, otherFields) = fields.partition { case (k, _) =>
          k == "$defs" || k == "definitions"
        }
        val hoistedDefs = defsFields.flatMap {
          case (_, Json.Obj(f)) => f
          case _                => Chunk.empty
        }
        val hoistedOpt = if hoistedDefs.isEmpty then None else Some(Json.Obj(hoistedDefs))
        (Json.Obj(otherFields), hoistedOpt)
      case other =>
        (other, None)

  /**
   * Builds the parameters schema for the synthetic `generate_response` tool.
   */
  def buildParametersSchema(schema: Json): Json =
    val (cleanedInner, _) = hoistDefs(schema)
    val definitions = schema match
      case Json.Obj(fields) =>
        fields.filter { case (key, _) => key == "$defs" || key == "definitions" }
      case _ => Chunk.empty
    val baseProps = Chunk("response" -> cleanedInner)
    val outerFields = Chunk(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(baseProps),
      "required"   -> Json.Arr(Json.Str("response"))
    ) ++ definitions
    Json.Obj(outerFields)

  /** Removes synthetic blocks without discarding ordinary calls in mixed tool turns. */
  def compressHistory(history: Chunk[Msg]): Chunk[Msg] =
    history.flatMap { msg =>
      val content = msg.content.filter {
        case call: ContentBlock.ToolUse      => call.name != ToolName
        case result: ContentBlock.ToolResult => !result.name.contains(ToolName)
        case _                               => true
      }
      val emptiedByCompression = msg.content.nonEmpty && content.isEmpty
      if msg.metadata
          .get(MetadataStructuredReminder)
          .contains(Json.Bool(true)) || emptiedByCompression
      then Chunk.empty
      else Chunk(msg.copy(content = content))
    }

  /** Transfers the generating reply's thinking and usage to the final structured message once. */
  private[agent] def completeHistory(history: Chunk[Msg], finalReply: Msg): Chunk[Msg] =
    val (trailingResults, assistantAndPrefix) = history.reverse.span(_.role != Role.Assistant)
    val retainedHistory = assistantAndPrefix.headOption match
      case Some(generatingReply) =>
        val residualReply = generatingReply.copy(
          content = generatingReply.content.filter {
            case _: ContentBlock.Thinking => false
            case _                        => true
          },
          usage = None
        )
        assistantAndPrefix.drop(1).reverse ++ Chunk(residualReply) ++ trailingResults.reverse
      case None => history
    compressHistory(retainedHistory) :+ finalReply

  /**
   * Creates the synthetic `generate_response` Tool instance that captures the response.
   */
  def createTool(schema: Json, onResponseCaptured: Json => UIO[Unit]): Tool[Any, Json, String] =
    val toolDef = ToolDef(
      name = ToolName,
      description =
        "Generate the final structured response. Call this function when you have all the information needed to provide a complete answer.",
      parametersSchema = buildParametersSchema(schema)
    )
    new Tool[Any, Json, String]:
      def definition: ToolDef                 = toolDef
      override def isConcurrencySafe: Boolean = false
      def decodeInput(raw: Json): Either[String, Json] =
        raw match
          case Json.Obj(fields) =>
            fields.find(_._1 == "response").map(_._2) match
              case Some(resp) => Right(resp)
              case None       => Right(raw) // Fallback if model supplied fields directly
          case other =>
            Right(other)

      def encodeOutput(out: String): String = out

      def execute(input: Json): ZIO[Any, Throwable, String] =
        onResponseCaptured(input).as("Successfully generated response.")

  /**
   * Extracts and decodes structured data from a Msg.
   */
  def parseStructuredResult[A: JsonDecoder](msg: Msg): Either[String, A] =
    msg.metadata.get(MetadataStructuredOutput) match
      case Some(ast) => ast.as[A]
      case None      => msg.text.fromJson[A]

  /**
   * Checks if a message is an internal structured-output transient message.
   */
  def isStructuredOutputRelated(msg: Msg): Boolean =
    val hasReminder =
      msg.metadata.get(MetadataStructuredReminder).flatMap(_.asBoolean).getOrElse(false)
    val hasGenCall  = msg.toolUses.exists(_.name == ToolName)
    val isGenResult = msg.toolResults.nonEmpty && msg.toolResults.forall(_.name.contains(ToolName))
    hasReminder || hasGenCall || isGenResult

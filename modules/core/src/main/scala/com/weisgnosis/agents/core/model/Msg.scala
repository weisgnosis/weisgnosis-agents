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
package com.weisgnosis.agents.core.model

import zio.{Chunk, Random, UIO, ZIO}
import zio.json.*
import zio.json.ast.Json

/**
 * Immutable message representing a single conversation turn between users, agents, and tools.
 *
 * Corresponds to `io.agentscope.core.message.Msg` in AgentScope Java.
 */
final case class Msg(
    id: Option[String] = None,
    name: Option[String] = None,
    role: Role,
    content: Chunk[ContentBlock] = Chunk.empty,
    metadata: Map[String, Json] = Map.empty,
    timestamp: Option[String] = None,
    usage: Option[ChatUsage] = None
):

  /** Pure constructors leave identity optional; allocation is suspended in the runtime. */
  def identified: UIO[Msg] = id match
    case Some(_) => ZIO.succeed(this)
    case None    => Random.nextUUID.map(value => copy(id = Some(value.toString)))

  /** Reads canonical usage first, then valid legacy metadata without caching or mutation. */
  def chatUsage: Option[ChatUsage] =
    usage.orElse(metadata.get(Msg.MetadataChatUsage).flatMap(_.as[ChatUsage].toOption))

  /** Concatenates text content across all text blocks separated by newlines. */
  def text: String =
    content.collect { case ContentBlock.Text(t) => t }.mkString("\n")

  /** Java parity alias for `text`. */
  def getTextContent: String = text

  /** Retrieves the first content block, if present. */
  def getFirstContentBlock: Option[ContentBlock] = content.headOption

  /** Concatenates thinking content across all thinking blocks separated by newlines. */
  def thinking: String =
    content.collect { case ContentBlock.Thinking(t, _) => t }.mkString("\n")

  /** Extracts all tool use blocks from this message. */
  def toolUses: Chunk[ContentBlock.ToolUse] =
    content.collect { case tu: ContentBlock.ToolUse => tu }

  /** Extracts all tool result blocks from this message. */
  def toolResults: Chunk[ContentBlock.ToolResult] =
    content.collect { case tr: ContentBlock.ToolResult => tr }

  /** Checks whether this message was synthetically injected by the agent runtime. */
  def isSynthetic: Boolean =
    metadata.get(Msg.MetadataSynthetic).flatMap(_.asBoolean).getOrElse(false)

  /** Returns the reminder kind if marked as synthetic. */
  def reminderKind: Option[String] =
    metadata.get(Msg.MetadataReminderKind).flatMap(_.asString)

  /** Returns an updated copy with an additional or replaced metadata key-value pair. */
  def withMetadata(key: String, value: Json): Msg =
    copy(metadata = metadata.updated(key, value))

  /** Returns an updated copy replacing content with a single text block. */
  def withTextContent(newText: String): Msg =
    copy(content = Chunk(ContentBlock.Text(newText)))

  /** Returns an updated copy with usage metrics. */
  def withUsage(newUsage: ChatUsage): Msg =
    copy(usage = Some(newUsage))

  /** Returns the generate reason if marked in metadata. */
  def generateReason: Option[String] =
    metadata.get(Msg.MetadataGenerateReason).flatMap(_.asString)

  /** Returns an updated copy with generate reason metadata. */
  def withGenerateReason(reason: String): Msg =
    withMetadata(Msg.MetadataGenerateReason, Json.Str(reason))

  /** Extracts and decodes structured output from metadata `_structured_output`, or parses message text. */
  def getStructuredData[A: JsonDecoder]: Either[String, A] =
    metadata.get("_structured_output") match
      case Some(ast) => ast.as[A]
      case None      => text.fromJson[A]

  /** Returns an updated copy with participant name. */
  def withName(newName: String): Msg =
    copy(name = Some(newName))

object Msg:

  val MetadataChatUsage: String             = "_chat_usage"
  val MetadataGenerateReason: String        = "agentscope_generate_reason"
  val MetadataConfirmResults: String        = "agentscope_confirm_results"
  val MetadataConfirmRequestReplyId: String = "agentscope_confirm_request_reply_id"
  val MetadataExternalExecutionRequestReplyId: String =
    "agentscope_external_execution_request_reply_id"
  val MetadataSynthetic: String    = "agentscope_synthetic"
  val MetadataReminderKind: String = "agentscope_reminder_kind"

  /**
   * Enforces role-to-content block type compatibility invariants:
   * - USER: Text, Data, Image, Audio, Video (rejects other block types)
   * - SYSTEM: Text only
   * - ASSISTANT: Unrestricted (any content block)
   * - TOOL: Unrestricted (retained for backward compatibility)
   */
  def validateRoleContent(role: Role, content: Chunk[ContentBlock]): Either[String, Unit] =
    role match
      case Role.User =>
        val invalid = content.filterNot {
          case _: ContentBlock.Text  => true
          case _: ContentBlock.Data  => true
          case _: ContentBlock.Image => true
          case _: ContentBlock.Audio => true
          case _: ContentBlock.Video => true
          case _                     => false
        }
        if invalid.isEmpty then Right(())
        else
          Left(
            s"USER messages cannot contain blocks of type: ${invalid.map(b => b.getClass.getSimpleName).mkString(", ")}"
          )

      case Role.System =>
        val invalid = content.filterNot {
          case _: ContentBlock.Text => true
          case _                    => false
        }
        if invalid.isEmpty then Right(())
        else
          Left(
            s"SYSTEM messages can only contain TextBlock, but found: ${invalid.map(b => b.getClass.getSimpleName).mkString(", ")}"
          )

      case Role.Assistant =>
        Right(())

      case Role.Tool =>
        Right(())

  /** Validated smart constructor ensuring invariants hold before instantiating `Msg`. */
  def create(
      role: Role,
      content: Chunk[ContentBlock] = Chunk.empty,
      id: Option[String] = None,
      name: Option[String] = None,
      metadata: Map[String, Json] = Map.empty,
      timestamp: Option[String] = None,
      usage: Option[ChatUsage] = None
  ): Either[String, Msg] =
    validateRoleContent(role, content).map(_ =>
      Msg(
        id = id,
        name = name,
        role = role,
        content = content,
        metadata = metadata,
        timestamp = timestamp,
        usage = usage
      )
    )

  def user(text: String): Msg =
    Msg(role = Role.User, content = Chunk(ContentBlock.Text(text)), name = Some("user"))

  def user(blocks: Chunk[ContentBlock]): Msg =
    Msg(role = Role.User, content = blocks, name = Some("user"))

  def assistant(text: String): Msg =
    Msg(role = Role.Assistant, content = Chunk(ContentBlock.Text(text)), name = Some("assistant"))

  def assistant(blocks: Chunk[ContentBlock]): Msg =
    Msg(role = Role.Assistant, content = blocks, name = Some("assistant"))

  def system(text: String): Msg =
    Msg(role = Role.System, content = Chunk(ContentBlock.Text(text)), name = Some("system"))

  def systemReminder(text: String): Msg =
    Msg(
      role = Role.User,
      name = Some("system"),
      content = Chunk(ContentBlock.Text(s"<system-reminder>$text</system-reminder>")),
      metadata = Map(
        MetadataSynthetic    -> Json.Bool(true),
        MetadataReminderKind -> Json.Str("system_directive")
      )
    )

  def tool(
      id: String,
      name: String,
      output: String,
      state: ToolResultState = ToolResultState.Success
  ): Msg =
    Msg(
      role = Role.Tool,
      name = Some(name),
      content = Chunk(ContentBlock.ToolResult.text(id, name, output, state))
    )

  given JsonCodec[Msg] = summon[JsonCodec[Json]].transformOrFail(
    json =>
      for
        obj  <- json.asObject.toRight("Msg must be a JSON object")
        role <- obj.get("role").toRight("Missing 'role' field in Msg").flatMap(_.as[Role])
        content <- obj.get("content") match
          case None | Some(Json.Null) => Right(Chunk.empty[ContentBlock])
          case Some(Json.Arr(elements)) =>
            val parsed = elements.map(el => el.as[ContentBlock])
            val lefts  = parsed.collect { case Left(err) => err }
            if lefts.nonEmpty then Left(lefts.mkString("; "))
            else Right(Chunk.fromIterable(parsed.collect { case Right(b) => b }))
          case Some(other) =>
            Left(s"Invalid content field in Msg: expected array, got: $other")
        _    <- validateRoleContent(role, content)
        id   <- JsonFields.optional[String](obj, "id")
        name <- JsonFields.optional[String](obj, "name")
        metadata <- JsonFields
          .optional[Map[String, Json]](obj, "metadata")
          .map(_.getOrElse(Map.empty))
        timestamp <- JsonFields.optional[String](obj, "timestamp")
        usage     <- JsonFields.optional[ChatUsage](obj, "usage")
      yield Msg(id, name, role, content, metadata, timestamp, usage),
    msg =>
      val baseFields: Chunk[(String, Json)] = Chunk(
        "role"    -> msg.role.toJsonAST.getOrElse(Json.Str("user")),
        "content" -> Json.Arr(msg.content.map(b => b.toJsonAST.getOrElse(Json.Null)))
      )
      val nameField = msg.name.map(n => "name" -> Json.Str(n))
      val metaField =
        if msg.metadata.nonEmpty then Some("metadata" -> Json.Obj(Chunk.fromIterable(msg.metadata)))
        else None
      val tsField    = msg.timestamp.map(ts => "timestamp" -> Json.Str(ts))
      val usageField = msg.usage.flatMap(u => u.toJsonAST.toOption.map(ast => "usage" -> ast))
      Json.Obj(
        baseFields ++ Chunk.fromIterable(msg.id.map(value => "id" -> Json.Str(value))) ++ Chunk
          .fromIterable(nameField) ++ Chunk.fromIterable(metaField) ++ Chunk
          .fromIterable(tsField) ++ Chunk.fromIterable(usageField)
      )
  )

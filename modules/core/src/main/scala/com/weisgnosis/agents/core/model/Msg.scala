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

import com.weisgnosis.agents.core.error.AgentError
import zio.{Chunk, Random, UIO, ZIO}
import zio.json.*
import zio.json.ast.Json

/**
 * Immutable message representing a single conversation turn between users, agents, and tools.
 *
 * Corresponds to `io.agentscope.core.message.Msg` in AgentScope Java.
 */
final class Msg private (
    val id: Option[String],
    val name: Option[String],
    val role: Role,
    val content: Chunk[ContentBlock],
    val metadata: Map[String, Json],
    val timestamp: Option[String],
    val usage: Option[ChatUsage]
):

  /** Pure constructors leave identity optional; allocation is suspended in the runtime. */
  def identified: UIO[Msg] = id match
    case Some(_) => ZIO.succeed(this)
    case None    => Random.nextUUID.map(value => rebuild(id = Some(value.toString)))

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
    rebuild(metadata = metadata.updated(key, value))

  /** Returns an updated copy replacing content with a single text block. */
  def withTextContent(newText: String): Msg =
    rebuild(content = Chunk(ContentBlock.Text(newText)))

  /** Returns an updated copy with usage metrics. */
  def withUsage(newUsage: ChatUsage): Msg =
    rebuild(usage = Some(newUsage))

  /** Returns the generate reason if marked in metadata. */
  def generateReason: Option[String] =
    metadata.get(Msg.MetadataGenerateReason).flatMap(_.asString)

  /** Classifies known reason metadata, defaulting to ModelStop without changing raw extensions. */
  def generationReason: GenerateReason =
    generateReason
      .flatMap(value => GenerateReason.fromValue(value).toOption)
      .getOrElse(GenerateReason.ModelStop)

  /** Returns an updated copy with generate reason metadata. */
  def withGenerateReason(reason: String): Msg =
    withMetadata(Msg.MetadataGenerateReason, Json.Str(reason))

  /** Writes a typed reason using the established lowercase metadata representation. */
  def withGenerateReason(reason: GenerateReason): Msg =
    withGenerateReason(reason.wireName)

  /** Extracts and decodes structured output from metadata `_structured_output`, or parses message text. */
  def getStructuredData[A: JsonDecoder]: Either[String, A] =
    metadata.get("_structured_output") match
      case Some(ast) => ast.as[A]
      case None      => text.fromJson[A]

  /** Returns an updated copy with participant name. */
  def withName(newName: String): Msg =
    rebuild(name = Some(newName))

  def withId(value: Option[String]): Msg           = rebuild(id = value)
  def withName(value: Option[String]): Msg         = rebuild(name = value)
  def withTimestamp(value: Option[String]): Msg    = rebuild(timestamp = value)
  def withMetadata(values: Map[String, Json]): Msg = rebuild(metadata = values)
  def withUsage(value: Option[ChatUsage]): Msg     = rebuild(usage = value)

  def withContent(value: Chunk[ContentBlock]): Either[AgentError, Msg] =
    Msg.create(role, value, id, name, metadata, timestamp, usage)

  def withRole(value: Role): Either[AgentError, Msg] =
    Msg.create(value, content, id, name, metadata, timestamp, usage)

  def filterContent(keep: ContentBlock => Boolean): Msg =
    rebuild(content = content.filter(keep))

  def mapToolUses(f: ContentBlock.ToolUse => ContentBlock.ToolUse): Msg =
    rebuild(content = content.map {
      case call: ContentBlock.ToolUse => f(call)
      case block                      => block
    })

  def mapToolResults(f: ContentBlock.ToolResult => ContentBlock.ToolResult): Msg =
    rebuild(content = content.map {
      case result: ContentBlock.ToolResult => f(result)
      case block                           => block
    })

  def mapToolResultsZIO[R, E](
      f: ContentBlock.ToolResult => ZIO[R, E, ContentBlock.ToolResult]
  ): ZIO[R, E, Msg] =
    ZIO.suspendSucceed(
      ZIO
        .foreach(content) {
          case result: ContentBlock.ToolResult => ZIO.suspendSucceed(f(result))
          case block                           => ZIO.succeed(block)
        }
        .map(value => rebuild(content = value))
    )

  override def equals(other: Any): Boolean = other match
    case that: Msg =>
      id == that.id && name == that.name && role == that.role && content == that.content &&
      metadata == that.metadata && timestamp == that.timestamp && usage == that.usage
    case _ => false

  override def hashCode(): Int = (id, name, role, content, metadata, timestamp, usage).hashCode()

  override def toString: String = s"Msg($id,$name,$role,$content,$metadata,$timestamp,$usage)"

  // Only operations that preserve role/content compatibility may call this private reconstruction.
  private def rebuild(
      id: Option[String] = this.id,
      name: Option[String] = this.name,
      content: Chunk[ContentBlock] = this.content,
      metadata: Map[String, Json] = this.metadata,
      timestamp: Option[String] = this.timestamp,
      usage: Option[ChatUsage] = this.usage
  ): Msg = new Msg(id, name, role, content, metadata, timestamp, usage)

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
  def validateRoleContent(role: Role, content: Chunk[ContentBlock]): Either[AgentError, Unit] =
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
            AgentError.InvalidMessage(
              s"USER messages cannot contain blocks of type: ${invalid.map(b => b.getClass.getSimpleName).mkString(", ")}"
            )
          )

      case Role.System =>
        val invalid = content.filterNot {
          case _: ContentBlock.Text => true
          case _                    => false
        }
        if invalid.isEmpty then Right(())
        else
          Left(
            AgentError.InvalidMessage(
              s"SYSTEM messages can only contain TextBlock, but found: ${invalid.map(b => b.getClass.getSimpleName).mkString(", ")}"
            )
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
  ): Either[AgentError, Msg] =
    validateRoleContent(role, content).map(_ =>
      new Msg(
        id = id,
        name = name,
        role = role,
        content = content,
        metadata = metadata,
        timestamp = timestamp,
        usage = usage
      )
    )

  type UserBlock = ContentBlock.Text | ContentBlock.Data | ContentBlock.Image | ContentBlock.Audio |
    ContentBlock.Video

  def user(text: String): Msg = user(Chunk(ContentBlock.Text(text)))

  def user(
      content: Chunk[UserBlock] = Chunk.empty,
      id: Option[String] = None,
      name: Option[String] = Some("user"),
      metadata: Map[String, Json] = Map.empty,
      timestamp: Option[String] = None,
      usage: Option[ChatUsage] = None
  ): Msg = new Msg(id, name, Role.User, content, metadata, timestamp, usage)

  def assistant(text: String): Msg = assistant(Chunk(ContentBlock.Text(text)))

  def assistant(
      content: Chunk[ContentBlock] = Chunk.empty,
      id: Option[String] = None,
      name: Option[String] = Some("assistant"),
      metadata: Map[String, Json] = Map.empty,
      timestamp: Option[String] = None,
      usage: Option[ChatUsage] = None
  ): Msg = new Msg(id, name, Role.Assistant, content, metadata, timestamp, usage)

  def system(text: String): Msg = system(Chunk(ContentBlock.Text(text)))

  def system(
      content: Chunk[ContentBlock.Text] = Chunk.empty,
      id: Option[String] = None,
      name: Option[String] = Some("system"),
      metadata: Map[String, Json] = Map.empty,
      timestamp: Option[String] = None,
      usage: Option[ChatUsage] = None
  ): Msg = new Msg(id, name, Role.System, content, metadata, timestamp, usage)

  def tool(
      content: Chunk[ContentBlock] = Chunk.empty,
      id: Option[String] = None,
      name: Option[String] = None,
      metadata: Map[String, Json] = Map.empty,
      timestamp: Option[String] = None,
      usage: Option[ChatUsage] = None
  ): Msg = new Msg(id, name, Role.Tool, content, metadata, timestamp, usage)

  def systemReminder(text: String): Msg =
    user(
      content = Chunk(ContentBlock.Text(s"<system-reminder>$text</system-reminder>")),
      name = Some("system"),
      metadata = Map(
        MetadataSynthetic    -> Json.Bool(true),
        MetadataReminderKind -> Json.Str("system_directive")
      )
    )

  def tool(id: String, name: String, output: String): Msg =
    tool(id, name, output, ToolResultState.Success)

  def tool(id: String, name: String, output: String, state: ToolResultState): Msg =
    tool(content = Chunk(ContentBlock.ToolResult.text(id, name, output, state)), name = Some(name))

  final private case class WireMsg(
      id: Option[String],
      name: Option[String],
      role: Role,
      content: Chunk[ContentBlock],
      metadata: Option[Map[String, Json]],
      timestamp: Option[String],
      usage: Option[ChatUsage]
  ) derives JsonCodec

  private val messageDecoder: JsonDecoder[Msg] = summon[JsonDecoder[Json]].mapOrFail(json =>
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
      id   <- JsonFields.optional[String](obj, "id")
      name <- JsonFields.optional[String](obj, "name")
      metadata <- JsonFields
        .optional[Map[String, Json]](obj, "metadata")
        .map(_.getOrElse(Map.empty))
      timestamp <- JsonFields.optional[String](obj, "timestamp")
      usage     <- JsonFields.optional[ChatUsage](obj, "usage")
      result    <- create(role, content, id, name, metadata, timestamp, usage).left.map(_.message)
    yield result
  )

  given JsonCodec[Msg] = JsonCodec(
    summon[JsonEncoder[WireMsg]].contramap(message =>
      WireMsg(
        message.id,
        message.name,
        message.role,
        message.content,
        Option.when(message.metadata.nonEmpty)(message.metadata),
        message.timestamp,
        message.usage
      )
    ),
    messageDecoder
  )

/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.model.{ContentBlock, Msg}
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/**
 * Lossless provider data is replayed only while it still projects to the current canonical content.
 * This prevents hidden retained wire data from overriding host content transformations.
 */
private[client] object AnthropicNativeReplay:
  val key: String = "anthropic_native_content"

  def attach(message: Msg, blocks: Chunk[Json.Obj]): Msg =
    message.withMetadata(key, Json.Arr(blocks))

  def content(
      message: Msg,
      webSearch: Option[AnthropicWebSearch]
  ): Either[AgentError, Option[Chunk[Json]]] =
    message.metadata.get(key) match
      case None => Right(None)
      case Some(value) =>
        for
          checked <- JsonBoundary.validate(value).left.map(AgentError.InvalidMessage(_))
          _ <- Either.cond(
            JsonBoundary.validUnicode(checked),
            (),
            AgentError.InvalidMessage("Malformed Unicode in native replay")
          )
          values <- checked.asArray.toRight(
            AgentError.InvalidMessage("Native replay must be an ordered block array")
          )
          objects <- values.foldLeft[Either[AgentError, Chunk[Json.Obj]]](Right(Chunk.empty))(
            (previous, block) =>
              for
                accumulated <- previous
                obj <- block.asObject.toRight(
                  AgentError.InvalidMessage("Native replay blocks must be objects")
                )
              yield accumulated :+ obj
          )
          canonical <- AnthropicMessageStream.canonicalBlocks(objects, webSearch)
          _ <- Either.cond(
            normalize(canonical) == normalize(message.content),
            (),
            AgentError.InvalidMessage(
              "Native replay differs from current canonical content; changed content cannot reuse stale provider data"
            )
          )
        yield Some(values)

  private def normalize(content: Chunk[ContentBlock]): Chunk[ContentBlock] = content.map:
    case call: ContentBlock.ToolUse => call.copy(content = None)
    case other                      => other

/*
 * Copyright 2024-2026 WeisGnosis (https://weisgnosis.com)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.weisgnosis.agents.core.agent

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.{ExactJson, JsonBoundary}
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, Role}
import zio.Chunk
import zio.json.*

/** Finite passive admission; stored speaker labels grant no executable authority. */
private[core] object PeerObservation:
  def project(history: Chunk[Msg]): Either[AgentError.ModelInputError, Chunk[Msg]] =
    history.foldLeft[Either[AgentError.ModelInputError, Chunk[Msg]]](Right(Chunk.empty))(
      (checked, message) =>
        checked.flatMap: projected =>
          val peers = message.content.collect { case peer: ContentBlock.PeerObservation => peer }
          if peers.isEmpty then Right(projected :+ message)
          else if message.role != Role.User then
            Left(AgentError.ModelInputError("Peer context requires a User observation"))
          else
            for
              content <- message.content.foldLeft[
                Either[AgentError.ModelInputError, Chunk[ContentBlock]]
              ](Right(Chunk.empty))((current, block) =>
                current.flatMap: previous =>
                  block match
                    case peer: ContentBlock.PeerObservation =>
                      for
                        _ <- validate(peer.senderId, peer.senderName, Chunk(peer.message)).left.map(
                          error => AgentError.ModelInputError(error.message)
                        )
                        body <- projectContent(peer.message.content)
                        label = ContentBlock.Text(
                          s"Peer message from ${peer.senderName.toJson} (agent ${peer.senderId.toJson}, original role ${peer.message.role.toJson}):"
                        )
                      yield previous ++ Chunk(label) ++ body :+ ContentBlock.Text(
                        "End peer message."
                      )
                    case other => Right(previous :+ other)
              )
              view <- message
                .withContent(content)
                .left
                .map(error => AgentError.ModelInputError(error.message))
            yield projected :+ view
    )

  private def projectContent(
      blocks: Chunk[ContentBlock]
  ): Either[AgentError.ModelInputError, Chunk[ContentBlock]] =
    blocks.foldLeft[Either[AgentError.ModelInputError, Chunk[ContentBlock]]](Right(Chunk.empty))(
      (checked, block) =>
        checked.flatMap: content =>
          block match
            case _: ContentBlock.Thinking => Right(content)
            case _: ContentBlock.Text | _: ContentBlock.Image | _: ContentBlock.Audio |
                _: ContentBlock.Video | _: ContentBlock.Data =>
              Right(content :+ block)
            case call: ContentBlock.ToolUse =>
              ExactJson
                .encode(call.copy(content = None, metadata = Map.empty))
                .left
                .map(error => AgentError.ModelInputError(error.message))
                .map(value => content :+ ContentBlock.Text(s"Peer tool call (data only): $value"))
            case result: ContentBlock.ToolResult =>
              for
                output <- projectContent(result.output)
                value <- ExactJson
                  .encode(result.copy(output = output, metadata = Map.empty))
                  .left
                  .map(error => AgentError.ModelInputError(error.message))
              yield content :+ ContentBlock.Text(s"Peer tool result (data only): $value")
            case _ => Left(AgentError.ModelInputError("Unsupported peer content projection"))
    )

  def validate(
      senderId: String,
      senderName: String,
      messages: Chunk[Msg]
  ): Either[AgentError, Unit] =
    for
      _ <- Either.cond(
        !senderId.isBlank && !senderName.isBlank && senderName.length <= 256,
        (),
        AgentError.InvalidMessage(
          "Peer identity must be nonblank and its name at most 256 characters"
        )
      )
      _ <- Either.cond(
        messages.nonEmpty && messages.size <= 64,
        (),
        AgentError.InvalidMessage("Peer observation requires between 1 and 64 messages")
      )
      _ <- messages.foldLeft[Either[AgentError, Unit]](Right(()))((checked, message) =>
        checked.flatMap(_ => message.withContent(message.content).map(_ => ()))
      )
      _ <- Either.cond(
        !containsPeer(messages.flatMap(_.content)),
        (),
        AgentError.InvalidMessage("Peer observations cannot contain nested peer observations")
      )
      wire = messages.map(message =>
        Msg.user(Chunk(ContentBlock.PeerObservation(senderId, senderName, message)))
      )
      ast <- wire.toJsonAST.left.map(AgentError.InvalidMessage(_))
      _ <- Either.cond(
        JsonBoundary.validUnicode(ast),
        (),
        AgentError.InvalidMessage("Peer observation contains invalid Unicode")
      )
      encoded <- ExactJson.encode(wire)
      _ <- Either.cond(
        encoded.length <= 1000000,
        (),
        AgentError.InvalidMessage("Peer observation exceeds 1,000,000 encoded characters")
      )
    yield ()

  @annotation.tailrec
  private def containsPeer(blocks: Chunk[ContentBlock]): Boolean = blocks.headOption match
    case None                                  => false
    case Some(_: ContentBlock.PeerObservation) => true
    case Some(result: ContentBlock.ToolResult) => containsPeer(result.output ++ blocks.drop(1))
    case Some(_)                               => containsPeer(blocks.drop(1))

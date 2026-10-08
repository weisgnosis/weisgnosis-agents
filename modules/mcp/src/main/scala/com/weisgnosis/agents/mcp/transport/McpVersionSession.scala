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
package com.weisgnosis.agents.mcp.transport

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.mcp.protocol.*
import zio.*
import zio.json.ast.Json

/** A single wire-decoding authority; closing a lease never closes its borrowed transport. */
final private[mcp] class McpVersionSession private (
    preferred: McpProtocolVersion,
    supported: Option[McpSupportedVersions],
    state: Ref[McpVersionSession.State],
    leaseReady: Promise[Nothing, Unit]
):
  import McpVersionSession.*

  def current: UIO[McpProtocolVersion] = state.get.map(version)

  def acquire: ZIO[Scope, AgentError, McpProtocolLease] = supported match
    case None => ZIO.succeed(McpProtocolLease.exact)
    case Some(_) =>
      ZIO.acquireRelease(
        state
          .modify[Either[AgentError, McpProtocolLease]]:
            case State.Proposed(Ownership.Available) =>
              (
                Right(
                  new McpProtocolLease:
                    def close: UIO[Unit] = seal
                ),
                State.Proposed(Ownership.Claimed)
              )
            case current =>
              (
                Left(AgentError.McpError("MCP negotiation session is already owned or closed")),
                current
              )
          .flatMap(ZIO.fromEither(_))
          .tap(_ => leaseReady.succeed(()))
      )(_.close)

  def seal: UIO[Unit] = supported match
    case None => ZIO.unit
    case Some(_) =>
      state.update(current => State.Closed(version(current))) *> leaseReady.succeed(()).unit

  def ensureOwned: IO[AgentError, Unit] = supported match
    case None => ZIO.unit
    case Some(_) =>
      state.get.flatMap:
        case State.Proposed(Ownership.Available) =>
          ZIO.fail(AgentError.McpError("MCP negotiation requires a scoped protocol lease"))
        case State.Closed(_) => ZIO.fail(AgentError.McpError("MCP negotiation session closed"))
        case _               => ZIO.unit

  /** Legacy endpoint discovery precedes the lease; protocol-message consumption does not. */
  def awaitOwned: IO[AgentError, Unit] = supported match
    case None    => ZIO.unit
    case Some(_) => leaseReady.await *> ensureOwned

  private def version(current: State): McpProtocolVersion = current match
    case State.Negotiated(selected) => selected
    case State.Closed(selected)     => selected
    case _                          => preferred

  /** Registration precedes any wire write and issuance publication. */
  def prepare(packet: McpPacket): IO[AgentError, McpProtocolVersion] = supported match
    case None => ZIO.succeed(preferred)
    case Some(_) =>
      state
        .modify[Either[AgentError, McpProtocolVersion]]:
          case current @ State.Closed(_) =>
            (Left(AgentError.McpError("MCP negotiation session closed")), current)
          case current @ State.Proposed(Ownership.Available) =>
            (Left(AgentError.McpError("MCP negotiation requires a scoped protocol lease")), current)
          case current =>
            packet match
              case McpPacket.Single(McpMessage.Request(id, "initialize", params)) =>
                current match
                  case State.Proposed(Ownership.Claimed)
                      if params
                        .flatMap(_.get("protocolVersion"))
                        .contains(Json.Str(preferred.wire)) =>
                    (Right(preferred), State.PendingInitialize(id))
                  case _ =>
                    (
                      Left(
                        AgentError.InvalidMessage(
                          "MCP initialization was already started or has an invalid preferred revision"
                        )
                      ),
                      current
                    )
              case _ => (Right(version(current)), current)
        .flatMap(ZIO.fromEither(_))

  def decode(
      wire: String,
      origin: ReplyOrigin = ReplyOrigin.SharedStream
  ): IO[AgentError, McpPacket] = supported match
    case None => ZIO.fromEither(McpPacket.decode(wire, preferred))
    case Some(accepted) =>
      state
        .modify[Either[AgentError, McpPacket]]: current =>
          current match
            case State.Closed(_) =>
              (Left(AgentError.McpError("MCP negotiation session closed")), current)
            case State.Proposed(Ownership.Available) =>
              (
                Left(AgentError.McpError("MCP negotiation requires a scoped protocol lease")),
                current
              )
            case _ =>
              McpPacket.decode(wire, version(current)) match
                case Left(error) =>
                  val next =
                    if mayInitialize(current, origin) then State.Closed(version(current))
                    else current
                  (Left(error), next)
                case Right(packet) =>
                  current match
                    case State.PendingInitialize(id) if mayInitialize(current, origin) =>
                      packet match
                        case McpPacket.Single(McpMessage.Success(replyId, result))
                            if replyId == id =>
                          McpInitialization.readSupportedResult(result, accepted) match
                            case Right(initialized) =>
                              (Right(packet), State.Negotiated(initialized.protocolVersion))
                            case Left(error) => (Left(error), State.Closed(version(current)))
                        case McpPacket.Single(McpMessage.Failure(replyId, _))
                            if replyId.contains(id) =>
                          (Right(packet), State.Closed(version(current)))
                        case McpPacket.Batch(members) if containsInitialization(members, id) =>
                          (
                            Left(
                              AgentError.InvalidMessage(
                                "MCP initialization response must be a singleton"
                              )
                            ),
                            State.Closed(version(current))
                          )
                        case _ => (Right(packet), current)
                    case _ => (Right(packet), current)
        .flatMap(ZIO.fromEither(_))

  private def mayInitialize(current: State, origin: ReplyOrigin): Boolean = origin match
    case ReplyOrigin.SharedStream => true
    case ReplyOrigin.Unrelated    => false
    case ReplyOrigin.InitializePost(id) =>
      current match
        case State.PendingInitialize(pending) => pending == id
        case _                                => false

  private def containsInitialization(members: Chunk[McpBatchMember], id: McpRequestId): Boolean =
    members.exists:
      case McpBatchMember.Message(McpMessage.Success(replyId, _)) => replyId == id
      case McpBatchMember.Message(McpMessage.Failure(replyId, _)) => replyId.contains(id)
      case _                                                      => false

private[mcp] object McpVersionSession:
  enum ReplyOrigin:
    case SharedStream, Unrelated
    case InitializePost(id: McpRequestId)

  private enum Ownership:
    case Available, Claimed

  private enum State:
    case Proposed(ownership: Ownership)
    case PendingInitialize(id: McpRequestId)
    case Negotiated(version: McpProtocolVersion)
    case Closed(version: McpProtocolVersion)

  def validate(
      preferred: McpProtocolVersion,
      supported: Option[McpSupportedVersions]
  ): Either[AgentError.InvalidConfiguration, Unit] =
    Either.cond(
      supported.forall(_.versions.contains(preferred)),
      (),
      AgentError.InvalidConfiguration("MCP preferred revision must belong to the supported set")
    )

  def make(
      preferred: McpProtocolVersion,
      supported: Option[McpSupportedVersions]
  ): IO[AgentError.InvalidConfiguration, McpVersionSession] =
    ZIO.fromEither(validate(preferred, supported)) *>
      (for
        state <- Ref.make[State](State.Proposed(Ownership.Available))
        ready <- Promise.make[Nothing, Unit]
      yield new McpVersionSession(preferred, supported, state, ready))

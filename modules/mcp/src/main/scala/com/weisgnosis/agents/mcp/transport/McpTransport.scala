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
import com.weisgnosis.agents.mcp.protocol.{McpMessage, McpProtocolVersion}
import zio.*
import zio.stream.ZStream

/**
 * Borrowed duplex message port. The host acquires it before its connection and releases it afterward.
 * Implementations decode incoming envelopes at the protocol boundary, support concurrent sends,
 * and make send/stream cancellation join owned cleanup. A send receives its own bounded Scope.
 */
trait McpTransport:
  /** The handshake and any transport version headers must agree on this explicit selection. */
  def protocolVersion: McpProtocolVersion = McpProtocolVersion.V20250618

  def send(message: McpMessage): ZIO[Scope, AgentError, Unit]

  /**
   * Publish an issued-attempt boundary before waiting for a reply when supported. The callback must
   * remain within this send and perform only bounded local publication. The legacy fallback signals
   * after successful send, so response-owning implementations must override for in-flight eligibility.
   * Publication is not proof of delivery to the peer.
   */
  def send(message: McpMessage, onIssued: UIO[Unit]): ZIO[Scope, AgentError, Unit] =
    ZIO.uninterruptibleMask: restore =>
      restore(send(message)) *> onIssued

  def incoming: ZStream[Any, AgentError, McpMessage]

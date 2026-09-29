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
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.error.AgentError
import zio.*
import zio.http.*

/** Deterministic scoped HTTP driver shared by transport and agent integration tests. */
private[client] object HttpClientTestSupport:
  def scripted(f: Request => ZIO[Scope, Throwable, Response]): Client =
    ZClient.fromDriver(new ZClient.Driver[Any, Scope, Throwable]:
      def request(
          version: Version,
          method: Method,
          url: URL,
          headers: Headers,
          body: Body,
          sslConfig: Option[ClientSSLConfig],
          proxy: Option[Proxy]
      )(using Trace): ZIO[Scope, Throwable, Response] =
        f(Request(version = version, method = method, url = url, headers = headers, body = body))

      def socket[Env1 <: Any](
          version: Version,
          url: URL,
          headers: Headers,
          app: WebSocketApp[Env1]
      )(using Trace, Scope =:= Scope): ZIO[Env1 & Scope, Throwable, Response] =
        ZIO.fail(AgentError.InvalidConfiguration("WebSockets are outside this HTTP test driver")))

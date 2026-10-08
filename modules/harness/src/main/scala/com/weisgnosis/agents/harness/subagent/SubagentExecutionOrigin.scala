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
package com.weisgnosis.agents.harness.subagent

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import java.nio.charset.StandardCharsets
import zio.http.URL
import zio.json.*
import zio.json.ast.Json

/** Exact host-declared endpoint observation, never a transport or execution capability. */
opaque type SubagentProtocolBaseUri = String

object SubagentProtocolBaseUri:
  val MaxUtf8Bytes: Int = 2048

  def from(value: String): Either[AgentError.InvalidConfiguration, SubagentProtocolBaseUri] =
    val invalid = AgentError.InvalidConfiguration(
      "Agent protocol base URI requires bounded absolute HTTP(S) without credentials, query or fragment"
    )
    for
      _ <- Either.cond(
        JsonBoundary.validUnicode(Json.Str(value)) &&
          value.getBytes(StandardCharsets.UTF_8).length <= MaxUtf8Bytes &&
          !value.exists(character => character.isWhitespace || character.isControl) &&
          !value.contains('?') && !value.contains('#'),
        (),
        invalid
      )
      parsed <- URL.decode(value).left.map(_ => invalid)
      // Inspect only the validated URI authority; an @ in a path is ordinary observation data.
      authority = value.dropWhile(_ != ':').drop(3).takeWhile(_ != '/')
      _ <- Either.cond(
        !authority.contains('@') && parsed.isAbsolute && parsed.host.exists(_.nonEmpty) &&
          parsed.scheme.exists(_.isHttp),
        (),
        invalid
      )
    yield value

  given JsonCodec[SubagentProtocolBaseUri] = JsonCodec.string.transformOrFail(
    value => from(value).left.map(_.message),
    value => value
  )

  extension (uri: SubagentProtocolBaseUri) def value: String = uri

/** Persisted producer provenance; remote observations confer no local execution ownership. */
enum SubagentExecutionOrigin derives JsonCodec:
  case Local
  case AgentProtocol(baseUri: SubagentProtocolBaseUri)

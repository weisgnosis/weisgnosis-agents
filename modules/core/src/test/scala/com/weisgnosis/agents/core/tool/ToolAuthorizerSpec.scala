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
package com.weisgnosis.agents.core.tool

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.ModelStreamContext
import com.weisgnosis.agents.core.model.ContentBlock
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ToolAuthorizerSpec extends ZIOSpecDefault:
  private val request: ToolAuthorizationRequest = ToolAuthorizationRequest(
    "approval",
    ModelStreamContext("reply", "request"),
    ContentBlock.ToolUse("call", "read", Json.Obj())
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ToolAuthorizerSpec")(
    test("request and decisions retain their typed JSON identity") {
      val approved: ToolAuthorization =
        ToolAuthorization.Approved(Json.Obj("query" -> Json.Str("safe")))
      val denied: ToolAuthorization = ToolAuthorization.Denied("restricted")
      val indexed                   = request.copy(callOrdinal = Some(2))
      assertTrue(
        request.toJson.fromJson[ToolAuthorizationRequest] == Right(request),
        indexed.toJson.fromJson[ToolAuthorizationRequest] == Right(indexed),
        """{"requestId":"approval","context":{"replyId":"reply","requestId":"request"},"call":{"id":"call","name":"read","input":{}}}"""
          .fromJson[ToolAuthorizationRequest] == Right(request),
        approved.toJson.fromJson[ToolAuthorization] == Right(approved),
        denied.toJson.fromJson[ToolAuthorization] == Right(denied)
      )
    },
    test("legacy and lambda decisions remain lazy and delegate unchanged with admitted access") {
      for
        calls <- Ref.make(0)
        authorizer = ToolAuthorizer(value =>
          calls.update(_ + 1).as(ToolAuthorization.Approved(value.call.input))
        )
        effect = authorizer.authorize(request)
        before      <- calls.get
        approved    <- ZIO.scoped(effect)
        after       <- calls.get
        default     <- ZIO.scoped(ToolAuthorizer.allow.authorize(request))
        legacyCalls <- Ref.make(Chunk.empty[ToolAuthorizationRequest])
        legacy = new ToolAuthorizer:
          def authorize(
              value: ToolAuthorizationRequest
          ): ZIO[Scope, AgentError.PermissionDenied, ToolAuthorization] =
            legacyCalls.update(_ :+ value).as(ToolAuthorization.Approved(value.call.input))
        accesses = Chunk[Option[AdmittedToolAccess]](
          None,
          Some(AdmittedToolAccess.ReadOnly),
          Some(AdmittedToolAccess.Mutating)
        )
        delegated <- ZIO.scoped(
          ZIO.foreach(accesses)(access =>
            authorizer.authorize(request, access).zip(legacy.authorize(request, access))
          )
        )
        legacyObserved <- legacyCalls.get
        finalCalls     <- calls.get
      yield assertTrue(
        before == 0,
        after == 1,
        finalCalls == 4,
        legacyObserved == Chunk.fill(3)(request),
        delegated == Chunk.fill(3)(default -> default),
        approved == default,
        default == ToolAuthorization.Approved(request.call.input)
      )
    },
    test("unrelated provider failures cannot inhabit the permission error channel") {
      assertZIO(typeCheck("""
        import com.weisgnosis.agents.core.tool.*
        import com.weisgnosis.agents.core.error.AgentError
        import zio.*
        ToolAuthorizer(_ => ZIO.fail(AgentError.ModelError(400, "bad")))
      """))(Assertion.isLeft)
    }
  )

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
package com.weisgnosis.agents.harness.permission

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.ModelStreamContext
import com.weisgnosis.agents.core.model.ContentBlock
import com.weisgnosis.agents.core.tool.{
  AdmittedToolAccess,
  ToolAuthorization,
  ToolAuthorizationRequest
}
import zio.*
import zio.json.ast.Json
import zio.test.*

object UnavailablePermissionAuthorizerSpec extends ZIOSpecDefault:
  private val input: Json = Json.Obj("value" -> Json.Str("prepared"))
  private val request: ToolAuthorizationRequest = ToolAuthorizationRequest(
    "authorization",
    ModelStreamContext("reply", "model"),
    ContentBlock.ToolUse("call", "read", input, Some("stale raw input")),
    Some(2)
  )
  private val allow: PermissionEngine = PermissionEngine(
    Chunk(PermissionRule("read", PermissionBehavior.Allow))
  )
  private val deny: PermissionEngine = PermissionEngine(
    Chunk(PermissionRule("read", PermissionBehavior.Deny))
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("UnavailablePermissionAuthorizer")(
    test("delegates permission precedence and samples policy after scoped gate cleanup"):
      val approved = ToolAuthorization.Approved(input)
      val denied   = ToolAuthorization.Denied("Tool execution denied by policy")
      val unavailable = ToolAuthorization.Denied(
        "Tool approval is unavailable: no host approval channel is configured"
      )
      val cases: Chunk[
        (
            PermissionEngine,
            Option[PermissionBehavior],
            Option[PermissionBehavior],
            ToolAuthorization
        )
      ] = Chunk(
        (PermissionEngine(), None, None, unavailable),
        (allow, None, None, approved),
        (deny, Some(PermissionBehavior.Allow), Some(PermissionBehavior.Ask), denied),
        (allow, Some(PermissionBehavior.Ask), None, unavailable),
        (allow, Some(PermissionBehavior.Passthrough), None, unavailable),
        (allow, Some(PermissionBehavior.Allow), Some(PermissionBehavior.Deny), denied),
        (
          PermissionEngine(Chunk(PermissionRule("read", PermissionBehavior.Passthrough))),
          None,
          None,
          unavailable
        )
      )
      for
        rows <- ZIO.foreach(cases):
          case (policy, toolVote, interceptorVote, expected) =>
            for
              reads  <- Ref.make(0)
              seen   <- Ref.make(Chunk.empty[(String, Json, Option[AdmittedToolAccess])])
              closed <- Ref.make(0)
              gate = (vote: Option[PermissionBehavior]) =>
                new PermissionGate:
                  def evaluate(
                      name: String,
                      value: Json
                  ): ZIO[Scope, AgentError.PermissionDenied, Option[PermissionBehavior]] =
                    evaluate(name, value, None)
                  override def evaluate(
                      name: String,
                      value: Json,
                      access: Option[AdmittedToolAccess]
                  ): ZIO[Scope, AgentError.PermissionDenied, Option[PermissionBehavior]] =
                    ZIO.addFinalizer(closed.update(_ + 1)) *>
                      seen.update(_ :+ ((name, value, access))).as(vote)
              authorizer = UnavailablePermissionAuthorizer(
                reads.update(_ + 1).as(policy),
                gate(toolVote),
                gate(interceptorVote)
              )
              before <- reads.get.zip(seen.get).zip(closed.get)
              outcome <- ZIO.scoped(
                authorizer.authorize(request, Some(AdmittedToolAccess.ReadOnly))
              )
              after <- reads.get.zip(seen.get).zip(closed.get)
            yield assertTrue(
              before == (0, Chunk.empty, 0),
              outcome == expected,
              after == (1, Chunk.fill(2)(("read", input, Some(AdmittedToolAccess.ReadOnly))), 2)
            )
        policy <- Ref.make(allow)
        authorizer = UnavailablePermissionAuthorizer(
          policy.get,
          PermissionGate((_, _) =>
            ZIO.addFinalizer(policy.set(deny)).as(Some(PermissionBehavior.Allow))
          )
        )
        afterCleanup <- ZIO.scoped(authorizer.authorize(request))
      yield rows.foldLeft(assertTrue(afterCleanup == denied))(_ && _)
  )

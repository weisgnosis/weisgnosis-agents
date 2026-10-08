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

import com.weisgnosis.agents.core.event.ModelStreamContext
import com.weisgnosis.agents.core.model.ContentBlock
import com.weisgnosis.agents.core.tool.{
  AdmittedToolAccess,
  ToolAuthorization,
  ToolAuthorizationRequest,
  ToolPreflight
}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object PermissionModeSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("PermissionModeSpec")(
    test("mode fallbacks retain independent safety priority across all approval channels") {
      val read  = Some(AdmittedToolAccess.ReadOnly)
      val write = Some(AdmittedToolAccess.Mutating)
      val cases = Chunk(
        (PermissionMode.Default, read, None, None, PermissionBehavior.Ask),
        (PermissionMode.Bypass, write, None, None, PermissionBehavior.Allow),
        (
          PermissionMode.Bypass,
          write,
          Some(PermissionBehavior.Deny),
          Some(PermissionBehavior.Allow),
          PermissionBehavior.Deny
        ),
        (
          PermissionMode.Bypass,
          read,
          Some(PermissionBehavior.Allow),
          Some(PermissionBehavior.Ask),
          PermissionBehavior.Ask
        ),
        (
          PermissionMode.Bypass,
          read,
          None,
          Some(PermissionBehavior.Passthrough),
          PermissionBehavior.Passthrough
        ),
        (PermissionMode.DontAsk, write, None, None, PermissionBehavior.Deny),
        (
          PermissionMode.DontAsk,
          read,
          None,
          Some(PermissionBehavior.Allow),
          PermissionBehavior.Allow
        ),
        (PermissionMode.DontAsk, read, Some(PermissionBehavior.Ask), None, PermissionBehavior.Deny),
        (PermissionMode.Explore, read, None, None, PermissionBehavior.Allow),
        (
          PermissionMode.Explore,
          write,
          Some(PermissionBehavior.Allow),
          Some(PermissionBehavior.Allow),
          PermissionBehavior.Deny
        ),
        (PermissionMode.Explore, None, None, None, PermissionBehavior.Deny),
        (PermissionMode.Explore, read, Some(PermissionBehavior.Ask), None, PermissionBehavior.Ask),
        (PermissionMode.AcceptEdits, read, None, None, PermissionBehavior.Allow),
        (PermissionMode.AcceptEdits, write, None, None, PermissionBehavior.Ask),
        (
          PermissionMode.AcceptEdits,
          read,
          None,
          Some(PermissionBehavior.Deny),
          PermissionBehavior.Deny
        ),
        (
          PermissionMode.AcceptEdits,
          read,
          Some(PermissionBehavior.Ask),
          None,
          PermissionBehavior.Ask
        )
      )
      val request = ToolAuthorizationRequest(
        "approval",
        ModelStreamContext("reply", "model"),
        ContentBlock.ToolUse("call", "probe", Json.Obj())
      )
      for rows <- ZIO.foreach(cases): (mode, access, rule, vote, expected) =>
          for
            requests <- PermissionRequests.make
            prompts  <- Ref.make(0)
            policy = PermissionEngine(
              Chunk.fromIterable(rule.map(PermissionRule("probe", _))),
              mode
            )
            gate = PermissionGate((_, _) => ZIO.succeed(vote))
            interactive <- InteractivePermissionAuthorizer.make(
              requests,
              value =>
                prompts
                  .update(_ + 1) *> requests.resolve(value.requestId, ConfirmResult(false)).unit,
              policy,
              gate
            )
            preflight         <- ZIO.scoped(interactive.preflight(request, access))
            actualInteractive <- ZIO.scoped(interactive.authorize(request, access))
            actualCheckpoint <- ZIO.scoped(
              CheckpointPermissionAuthorizer(ZIO.succeed(policy), gate).authorize(request, access)
            )
            actualUnavailable <- ZIO.scoped(
              UnavailablePermissionAuthorizer(ZIO.succeed(policy), gate).authorize(request, access)
            )
            count <- prompts.get
            unresolved =
              expected == PermissionBehavior.Ask || expected == PermissionBehavior.Passthrough
            approved = ToolAuthorization.Approved(request.call.input)
          yield assertTrue(
            policy.evaluate("probe", request.call.input, vote, admittedAccess = access) == expected,
            if expected == PermissionBehavior.Allow then
              actualInteractive == approved && actualCheckpoint == approved && actualUnavailable == approved
            else
              actualInteractive.isInstanceOf[ToolAuthorization.Denied] && actualUnavailable
                .isInstanceOf[ToolAuthorization.Denied] &&
              (if unresolved then actualCheckpoint.isInstanceOf[ToolAuthorization.Deferred]
               else actualCheckpoint.isInstanceOf[ToolAuthorization.Denied])
            ,
            count == (if unresolved then 1 else 0),
            mode != PermissionMode.DontAsk || rule.nonEmpty || preflight == ToolPreflight.Continue,
            mode != PermissionMode.Explore || access.contains(
              AdmittedToolAccess.ReadOnly
            ) || preflight.isInstanceOf[ToolPreflight.Denied]
          )
      yield rows.foldLeft(assertTrue(true))(_ && _)
    },
    test("host names are case insensitive and the scalar wire names remain explicit") {
      val cases = Chunk(
        PermissionMode.Default     -> "default",
        PermissionMode.Bypass      -> "bypass",
        PermissionMode.DontAsk     -> "dont_ask",
        PermissionMode.Explore     -> "explore",
        PermissionMode.AcceptEdits -> "accept_edits"
      )
      assertTrue(
        cases.forall((mode, name) =>
          mode.toJson == Json.Str(name).toJson && PermissionMode.fromString(
            name.toUpperCase(java.util.Locale.ROOT)
          ) == Right(mode) && mode.toJson.fromJson[PermissionMode] == Right(mode)
        ),
        PermissionMode.fromString("unknown").isLeft,
        "\"unknown\"".fromJson[PermissionMode].isLeft
      )
    }
  ) @@ TestAspect.timeout(20.seconds)

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

import com.weisgnosis.agents.core.model.ContentBlock
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object DurableResumeSpec extends ZIOSpecDefault:
  private val call: ContentBlock.ToolUse =
    ContentBlock.ToolUse("call", "shell", Json.Obj("command" -> Json.Str("git status")))
  def spec: Spec[TestEnvironment, Any] = suite("DurableResumeSpec")(
    test("serialized suspension resumes with modified input and immutable policy updates") {
      val engine    = PermissionEngine()
      val suspended = engine.authorize("request", call)
      val decoded   = suspended.toJson.fromJson[PermissionOutcome]
      val rule      = PermissionRule("read_file", PermissionBehavior.Allow)
      val modified  = Json.Obj("command" -> Json.Str("git diff"))
      val resumed = decoded.toOption.collect { case PermissionOutcome.AwaitingApproval(pending) =>
        engine.resume(pending, ConfirmResult(true, Some(modified), Chunk(rule)))
      }
      assertTrue(
        resumed.exists(_._1.rules == Chunk(rule)),
        engine.rules.isEmpty,
        resumed.exists(_._2 == PermissionOutcome.Approved(call.copy(input = modified)))
      )
    },
    test("declined confirmations do not install new rules or approve execution") {
      val engine = PermissionEngine()
      val resumed = engine.resume(
        PendingApproval("request", call),
        ConfirmResult(
          false,
          newRules = Chunk(PermissionRule("*", PermissionBehavior.Allow)),
          reason = Some("declined")
        )
      )
      assertTrue(resumed._1 == engine, resumed._2 == PermissionOutcome.Denied("shell", "declined"))
    },
    test("modified input is checked against explicit deny rules") {
      val engine =
        PermissionEngine(Chunk(PermissionRule("shell", PermissionBehavior.Deny, Some("rm *"))))
      val resumed = engine.resume(
        PendingApproval("request", call),
        ConfirmResult(true, Some(Json.Obj("command" -> Json.Str("rm file"))))
      )
      assertTrue(resumed._2.isInstanceOf[PermissionOutcome.Denied])
    },
    test("confirmed results and policies round-trip without runtime objects") {
      val result = ConfirmResult(
        true,
        Some(call.input),
        Chunk(PermissionRule("shell", PermissionBehavior.Ask)),
        Some("reviewed")
      )
      assertTrue(result.toJson.fromJson[ConfirmResult] == Right(result))
    }
  )

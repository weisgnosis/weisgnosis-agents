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

import zio.*
import zio.json.ast.Json
import zio.test.*

object PermissionGateSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment, Any] = suite("PermissionGateSpec")(
    test("all independent gate combinations follow Deny > Ask > Passthrough > Allow") {
      val order = Chunk(
        PermissionBehavior.Allow,
        PermissionBehavior.Passthrough,
        PermissionBehavior.Ask,
        PermissionBehavior.Deny
      )
      val pairs =
        for first <- order.zipWithIndex; second <- order.zipWithIndex
        yield PermissionBehavior.combine(first._1, second._1) == (if first._2 >= second._2 then
                                                                    first._1
                                                                  else second._1)
      assertTrue(pairs.forall(identity))
    },
    test("deny and ask policies take precedence over allow rules in either order") {
      val input = Json.Obj("command" -> Json.Str("git status"))
      val allow = PermissionRule("shell", PermissionBehavior.Allow)
      val ask   = PermissionRule("shell", PermissionBehavior.Ask)
      val deny  = PermissionRule("shell", PermissionBehavior.Deny)
      assertTrue(
        PermissionEngine(Chunk(allow, ask, deny))
          .evaluate("shell", input) == PermissionBehavior.Deny,
        PermissionEngine(Chunk(deny, allow, ask))
          .evaluate("shell", input) == PermissionBehavior.Deny,
        PermissionEngine(Chunk(allow, ask)).evaluate("shell", input) == PermissionBehavior.Ask
      )
    },
    test("tool and interceptor safety decisions cannot be bypassed by allow rules") {
      val engine = PermissionEngine(Chunk(PermissionRule("*", PermissionBehavior.Allow)))
      assertTrue(
        engine.evaluate(
          "shell",
          Json.Obj(),
          Some(PermissionBehavior.Deny),
          Some(PermissionBehavior.Allow)
        ) == PermissionBehavior.Deny,
        engine.evaluate(
          "shell",
          Json.Obj(),
          Some(PermissionBehavior.Allow),
          Some(PermissionBehavior.Ask)
        ) == PermissionBehavior.Ask
      )
    },
    test("content restrictions match arguments rather than granting the entire tool") {
      val engine =
        PermissionEngine(Chunk(PermissionRule("shell", PermissionBehavior.Allow, Some("git *"))))
      assertTrue(
        engine.evaluate(
          "shell",
          Json.Obj("command" -> Json.Str("git status"))
        ) == PermissionBehavior.Allow,
        engine
          .evaluate("shell", Json.Obj("command" -> Json.Str("rm file"))) == PermissionBehavior.Ask,
        engine.evaluate("other", Json.Str("git status")) == PermissionBehavior.Ask,
        engine.evaluate(
          "shell",
          Json.Obj("command" -> Json.Str("rm file"), "note" -> Json.Str("git status"))
        ) == PermissionBehavior.Ask
      )
    },
    test("wildcard matching handles empty strings, punctuation, and nested arguments") {
      val rule = PermissionRule(
        "*",
        PermissionBehavior.Allow,
        Some("a?[*"),
        argumentPath = Chunk("nested", "command")
      )
      assertTrue(
        rule.matches("tool", Json.Obj("nested" -> Json.Obj("command" -> Json.Str("ab[tail")))),
        !rule.matches("tool", Json.Str("a[tail")),
        PermissionRule("*", PermissionBehavior.Allow, Some("*")).matches("tool", Json.Str(""))
      )
    },
    test("an unconfigured engine asks for confirmation") {
      assertTrue(PermissionEngine().evaluate("unknown", Json.Obj()) == PermissionBehavior.Ask)
    }
  )

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
package com.weisgnosis.agents.harness.plan

import com.weisgnosis.agents.harness.permission.*
import zio.*
import zio.json.ast.Json
import zio.test.*

object PlanPermissionGateSpec extends ZIOSpecDefault:
  private val access: Map[String, PlanToolAccess] = Map(
    "read"       -> PlanToolAccess.ReadOnly,
    "plan_write" -> PlanToolAccess.PlanControl,
    "plan_exit"  -> PlanToolAccess.RequestExecution,
    "write"      -> PlanToolAccess.Mutation,
    "inspect"    -> PlanToolAccess.PosixReadCommand
  )
  private val input: Json = Json.Obj("command" -> Json.Str("pwd"))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("PlanPermissionGateSpec")(
    test(
      "Plan abstains for eligible capabilities asks for execution requests and denies mutation or unknown names"
    ) {
      val gate = PlanPermissionGate.make(ZIO.succeed(PlanMode.Plan), access)
      for decisions <- ZIO.foreach(
          Chunk(
            "read",
            "plan_write",
            "plan_exit",
            "write",
            "inspect",
            "READ",
            "agent_spawn",
            "agent_send",
            "unknown"
          )
        )(gate.evaluate(_, input))
      yield assertTrue(
        decisions == Chunk(
          None,
          None,
          Some(PermissionBehavior.Ask),
          Some(PermissionBehavior.Deny),
          None,
          Some(PermissionBehavior.Deny),
          Some(PermissionBehavior.Deny),
          Some(PermissionBehavior.Deny),
          Some(PermissionBehavior.Deny)
        )
      )
    },
    test("Build abstains even for unknown capabilities and malformed shell envelopes") {
      val gate = PlanPermissionGate.make(ZIO.succeed(PlanMode.Build), access)
      for decisions <- ZIO.foreach(
          Chunk("write", "inspect", "plan_exit", "agent_spawn", "unknown")
        )(gate.evaluate(_, Json.Arr()))
      yield assertTrue(decisions.forall(_.isEmpty))
    },
    test("mode evaluation is suspended and samples current authority exactly once for each check") {
      for
        mode    <- Ref.make(PlanMode.Build)
        samples <- Ref.make(0)
        gate = PlanPermissionGate.make(samples.update(_ + 1) *> mode.get, access)
        before <- samples.get
        first  <- gate.evaluate("write", input)
        _      <- mode.set(PlanMode.Plan)
        second <- gate.evaluate("write", input)
        count  <- samples.get
      yield assertTrue(
        before == 0,
        count == 2,
        first.isEmpty,
        second.contains(PermissionBehavior.Deny)
      )
    },
    test(
      "eligibility never grants underlying permission and stronger deny wins over execution requests"
    ) {
      val gate    = PlanPermissionGate.make(ZIO.succeed(PlanMode.Plan), access)
      val allowed = PermissionEngine(Chunk(PermissionRule("*", PermissionBehavior.Allow)))
      for
        read  <- gate.evaluate("read", input)
        write <- gate.evaluate("write", input)
        exit  <- gate.evaluate("plan_exit", input)
      yield assertTrue(
        PermissionEngine()
          .evaluate("read", input, interceptorGate = read) == PermissionBehavior.Ask,
        allowed.evaluate("write", input, interceptorGate = write) == PermissionBehavior.Deny,
        allowed.evaluate("plan_exit", input, interceptorGate = exit) == PermissionBehavior.Ask,
        allowed.evaluate(
          "plan_exit",
          input,
          Some(PermissionBehavior.Deny),
          exit
        ) == PermissionBehavior.Deny
      )
    },
    test(
      "shell classification consumes exact authoritative input without model supplied access overrides"
    ) {
      val gate = PlanPermissionGate.make(ZIO.succeed(PlanMode.Plan), access)
      for
        valid  <- gate.evaluate("inspect", input)
        syntax <- gate.evaluate("inspect", Json.Obj("command" -> Json.Str("cat file > out")))
        extra <- gate.evaluate(
          "inspect",
          Json.Obj("command" -> Json.Str("pwd"), "access" -> Json.Str("ReadOnly"))
        )
        unknown <- gate.evaluate("unknown", Json.Obj("isReadOnly" -> Json.Bool(true)))
      yield assertTrue(
        valid.isEmpty,
        Chunk(syntax, extra, unknown).forall(_.contains(PermissionBehavior.Deny))
      )
    },
    test(
      "cancelling suspended mode evaluation waits for cleanup without producing an admission decision"
    ) {
      for
        entered <- Promise.make[Nothing, Unit]
        cleaned <- Ref.make(false)
        gate = PlanPermissionGate.make(
          (entered.succeed(()) *> ZIO.never).ensuring(cleaned.set(true)),
          access
        )
        fiber  <- ZIO.scoped(gate.evaluate("read", input)).forkScoped
        _      <- entered.await
        exit   <- fiber.interrupt
        closed <- cleaned.get
      yield assertTrue(exit.isInterrupted, closed)
    }
  )

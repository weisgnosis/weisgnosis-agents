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
import com.weisgnosis.agents.core.tool.{ToolAuthorization, ToolAuthorizationRequest}
import zio.*
import zio.json.ast.Json
import zio.test.*

object InteractivePermissionAuthorizerSpec extends ZIOSpecDefault:
  private val original: Json = Json.Obj("command" -> Json.Str("read original"))
  private val modified: Json = Json.Obj("command" -> Json.Str("read modified"))
  private def request(id: String): ToolAuthorizationRequest = ToolAuthorizationRequest(
    id,
    ModelStreamContext("reply", "model"),
    ContentBlock.ToolUse("reused", "read", original, Some("stale raw arguments"))
  )
  private val allow: PermissionRule = PermissionRule("read", PermissionBehavior.Allow)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("InteractivePermissionAuthorizerSpec")(
    test("known allow skips publication and an independent deny still wins") {
      for
        requests  <- PermissionRequests.make
        published <- Ref.make(0)
        allowed <- InteractivePermissionAuthorizer.make(
          requests,
          _ => published.update(_ + 1),
          PermissionEngine(Chunk(allow))
        )
        denied <- InteractivePermissionAuthorizer.make(
          requests,
          _ => published.update(_ + 1),
          PermissionEngine(Chunk(allow)),
          interceptorGate = PermissionGate((_, _) => ZIO.some(PermissionBehavior.Deny))
        )
        first  <- ZIO.scoped(allowed.authorize(request("one")))
        second <- ZIO.scoped(denied.authorize(request("two")))
        count  <- published.get
      yield assertTrue(
        first == ToolAuthorization.Approved(original),
        second.isInstanceOf[ToolAuthorization.Denied],
        count == 0
      )
    },
    test("unknown policy registers before immediate confirmation publication") {
      for
        requests   <- PermissionRequests.make
        registered <- Ref.make(false)
        resolved   <- Ref.make(false)
        authorizer <- InteractivePermissionAuthorizer.make(
          requests,
          value =>
            requests.pendingIds.flatMap(ids => registered.set(ids.contains(value.requestId))) *>
              requests.resolve(value.requestId, ConfirmResult(true)).flatMap(resolved.set)
        )
        result    <- ZIO.scoped(authorizer.authorize(request("one")))
        seen      <- registered.get
        accepted  <- resolved.get
        remaining <- requests.pendingIds
      yield assertTrue(
        result == ToolAuthorization.Approved(original),
        seen,
        accepted,
        remaining.isEmpty
      )
    },
    test(
      "both gates recheck authoritative modified input and close scopes before waiting and completion"
    ) {
      for
        requests        <- PermissionRequests.make
        observations    <- Ref.make(Chunk.empty[(String, Json)])
        active          <- Ref.make(0)
        activeAtPublish <- Ref.make(-1)
        tool = PermissionGate((name, input) =>
          ZIO.acquireRelease(active.update(_ + 1))(_ => active.update(_ - 1)) *>
            observations.update(_ :+ (s"tool:$name" -> input)).as(Some(PermissionBehavior.Allow))
        )
        interceptor = PermissionGate((name, input) =>
          ZIO.acquireRelease(active.update(_ + 1))(_ => active.update(_ - 1)) *>
            observations
              .update(_ :+ (s"interceptor:$name" -> input))
              .as(
                Some(if input == modified then PermissionBehavior.Deny else PermissionBehavior.Ask)
              )
        )
        authorizer <- InteractivePermissionAuthorizer.make(
          requests,
          value =>
            active.get.flatMap(activeAtPublish.set) *> requests
              .resolve(value.requestId, ConfirmResult(true, Some(modified)))
              .unit,
          toolGate = tool,
          interceptorGate = interceptor
        )
        result <- ZIO.scoped(authorizer.authorize(request("one")))
        seen   <- observations.get
        during <- activeAtPublish.get
        after  <- active.get
      yield assertTrue(
        result.isInstanceOf[ToolAuthorization.Denied],
        during == 0,
        after == 0,
        seen == Chunk(
          "tool:read"        -> original,
          "interceptor:read" -> original,
          "tool:read"        -> modified,
          "interceptor:read" -> modified
        )
      )
    },
    test(
      "concurrent confirmations merge both rule additions without holding a lock during waiting"
    ) {
      val firstRule  = PermissionRule("first", PermissionBehavior.Allow)
      val secondRule = PermissionRule("second", PermissionBehavior.Allow)
      for
        requests  <- PermissionRequests.make
        published <- Queue.unbounded[ToolAuthorizationRequest]
        authorizer <- InteractivePermissionAuthorizer.make(
          requests,
          value => published.offer(value).unit
        )
        first        <- ZIO.scoped(authorizer.authorize(request("one"))).forkScoped
        second       <- ZIO.scoped(authorizer.authorize(request("two"))).forkScoped
        _            <- published.takeN(2)
        _            <- requests.resolve("two", ConfirmResult(true, newRules = Chunk(secondRule)))
        secondResult <- second.join
        _            <- requests.resolve("one", ConfirmResult(true, newRules = Chunk(firstRule)))
        firstResult  <- first.join
        policy       <- authorizer.policy
      yield assertTrue(
        firstResult == ToolAuthorization.Approved(original),
        secondResult == ToolAuthorization.Approved(original),
        policy.rules == Chunk(secondRule, firstRule)
      )
    },
    test("a deny installed by another confirmation wins against an older waiting request") {
      val deny = PermissionRule("read", PermissionBehavior.Deny)
      for
        requests  <- PermissionRequests.make
        published <- Queue.unbounded[ToolAuthorizationRequest]
        authorizer <- InteractivePermissionAuthorizer.make(
          requests,
          value => published.offer(value).unit
        )
        first        <- ZIO.scoped(authorizer.authorize(request("one"))).forkScoped
        _            <- published.take
        second       <- ZIO.scoped(authorizer.authorize(request("two"))).forkScoped
        _            <- published.take
        _            <- requests.resolve("two", ConfirmResult(true, newRules = Chunk(deny)))
        secondResult <- second.join
        _            <- requests.resolve("one", ConfirmResult(true))
        firstResult  <- first.join
      yield assertTrue(
        firstResult.isInstanceOf[ToolAuthorization.Denied],
        secondResult.isInstanceOf[ToolAuthorization.Denied]
      )
    },
    test("declined confirmation preserves its reason and cannot install new rules") {
      for
        requests <- PermissionRequests.make
        checks   <- Ref.make(0)
        authorizer <- InteractivePermissionAuthorizer.make(
          requests,
          value =>
            requests
              .resolve(
                value.requestId,
                ConfirmResult(false, Some(modified), Chunk(allow), Some("declined by user"))
              )
              .unit,
          toolGate = PermissionGate((_, _) => checks.update(_ + 1).as(None))
        )
        result <- ZIO.scoped(authorizer.authorize(request("one")))
        policy <- authorizer.policy
        count  <- checks.get
      yield assertTrue(
        result == ToolAuthorization.Denied("declined by user"),
        policy.rules.isEmpty,
        count == 1
      )
    },
    test("cancellation removes the waiter and late confirmation cannot install policy") {
      for
        requests  <- PermissionRequests.make
        published <- Queue.unbounded[ToolAuthorizationRequest]
        authorizer <- InteractivePermissionAuthorizer.make(
          requests,
          value => published.offer(value).unit
        )
        fiber   <- ZIO.scoped(authorizer.authorize(request("one"))).forkScoped
        _       <- published.take
        exit    <- fiber.interrupt
        late    <- requests.resolve("one", ConfirmResult(true, newRules = Chunk(allow)))
        pending <- requests.pendingIds
        policy  <- authorizer.policy
      yield assertTrue(exit.isInterrupted, !late, pending.isEmpty, policy.rules.isEmpty)
    },
    test("gate failures preserve their typed channel without publishing or changing policy") {
      for
        requests  <- PermissionRequests.make
        published <- Ref.make(0)
        authorizer <- InteractivePermissionAuthorizer.make(
          requests,
          _ => published.update(_ + 1),
          toolGate = PermissionGate((name, _) =>
            ZIO.fail(AgentError.PermissionDenied(name, "gate unavailable"))
          )
        )
        result <- ZIO.scoped(authorizer.authorize(request("one"))).either
        count  <- published.get
        policy <- authorizer.policy
      yield assertTrue(
        result == Left(AgentError.PermissionDenied("read", "gate unavailable")),
        count == 0,
        policy.rules.isEmpty
      )
    },
    test("publication defects remove the registered waiter") {
      for
        requests <- PermissionRequests.make
        authorizer <- InteractivePermissionAuthorizer
          .make(requests, _ => ZIO.die(new IllegalStateException("publish failed")))
        exit    <- ZIO.scoped(authorizer.authorize(request("one"))).exit
        pending <- requests.pendingIds
      yield assertTrue(exit.isFailure, pending.isEmpty)
    }
  )

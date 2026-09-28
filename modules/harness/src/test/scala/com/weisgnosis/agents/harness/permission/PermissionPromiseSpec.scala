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
import zio.json.ast.Json
import zio.test.*

object PermissionPromiseSpec extends ZIOSpecDefault:
  private val request: PendingApproval =
    PendingApproval("approval", ContentBlock.ToolUse("call", "shell", Json.Obj()))
  def spec: Spec[TestEnvironment & Scope, Any] = suite("PermissionPromiseSpec")(
    test("approval is registered before publication and completes exactly once") {
      for
        requests  <- PermissionRequests.make
        published <- Promise.make[Nothing, Unit]
        fiber     <- requests.awaitApproval(request)(_ => published.succeed(()).unit).forkScoped
        _         <- published.await
        first     <- requests.resolve(request.requestId, ConfirmResult(true))
        second    <- requests.resolve(request.requestId, ConfirmResult(false))
        result    <- fiber.join
        remaining <- requests.pendingIds
      yield assertTrue(first, !second, result.confirmed, remaining.isEmpty)
    },
    test("cancellation removes the waiter and rejects late confirmation") {
      for
        requests  <- PermissionRequests.make
        published <- Promise.make[Nothing, Unit]
        fiber     <- requests.awaitApproval(request)(_ => published.succeed(()).unit).forkScoped
        _         <- published.await
        _         <- fiber.interrupt
        accepted  <- requests.resolve(request.requestId, ConfirmResult(true))
        remaining <- requests.pendingIds
      yield assertTrue(!accepted, remaining.isEmpty)
    },
    test("duplicate request IDs fail without replacing the original waiter") {
      for
        requests  <- PermissionRequests.make
        published <- Promise.make[Nothing, Unit]
        fiber     <- requests.awaitApproval(request)(_ => published.succeed(()).unit).forkScoped
        _         <- published.await
        duplicate <- requests.awaitApproval(request)(_ => ZIO.unit).either
        _         <- requests.resolve(request.requestId, ConfirmResult(true))
        original  <- fiber.join
      yield assertTrue(duplicate.isLeft, original.confirmed)
    }
  ) @@ TestAspect.timeout(10.seconds)

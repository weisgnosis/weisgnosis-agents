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
import zio.*

/** Interactive requests live only for the requesting fiber's lifetime. */
final class PermissionRequests private (pending: Ref[Map[String, Promise[Nothing, ConfirmResult]]]):
  def awaitApproval(
      request: PendingApproval
  )(publish: PendingApproval => UIO[Unit]): IO[AgentError, ConfirmResult] =
    ZIO.uninterruptibleMask { restore =>
      for
        promise <- Promise.make[Nothing, ConfirmResult]
        registered <- pending.modify { current =>
          if current.contains(request.requestId) then (false, current)
          else (true, current.updated(request.requestId, promise))
        }
        result <-
          if !registered then
            ZIO.fail(
              AgentError
                .PermissionDenied(request.call.name, "Duplicate approval request identifier")
            )
          else
            restore(publish(request) *> promise.await)
              .ensuring(pending.update(_ - request.requestId))
      yield result
    }

  def resolve(requestId: String, result: ConfirmResult): UIO[Boolean] =
    pending.get.flatMap(_.get(requestId) match
      case Some(promise) => promise.succeed(result)
      case None          => ZIO.succeed(false))

  def pendingIds: UIO[Set[String]] = pending.get.map(_.keySet)

object PermissionRequests:
  def make: UIO[PermissionRequests] =
    Ref.make(Map.empty[String, Promise[Nothing, ConfirmResult]]).map(new PermissionRequests(_))

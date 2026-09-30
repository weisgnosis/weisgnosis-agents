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
package com.weisgnosis.agents.mcp.client

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.mcp.protocol.*
import zio.*
import zio.json.ast.Json
import zio.test.*

object McpRequestsSpec extends ZIOSpecDefault:
  private def scope: ZIO[Scope, Nothing, Scope.Closeable] =
    ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))

  private def lease(
      registry: McpRequests
  ): ZIO[Scope, AgentError.McpError, (McpRequests.PendingRequest, Scope.Closeable)] =
    for
      requestScope <- scope
      request      <- registry.register.provideEnvironment(ZEnvironment[Scope](requestScope))
    yield (request, requestScope)

  private def success(id: McpRequestId): McpMessage.Success = McpMessage.Success(id, Json.Obj())

  private def localFailure(exit: Exit[AgentError, Any]): Boolean =
    exit.causeOption.exists(_.failures.exists:
      case _: AgentError.McpError => true
      case _                      => false)

  def spec: Spec[TestEnvironment, Any] = suite("McpRequestsSpec")(
    test("runtime IDs correlate out-of-order responses and remote errors remain reply data") {
      ZIO.scoped:
        for
          registry <- McpRequests.make(2)
          first    <- lease(registry)
          second   <- lease(registry)
          failure = McpMessage.Failure(
            Some(second._1.id),
            McpRpcError(-32602, "remote rejection", Some(Json.Null))
          )
          secondDelivery <- registry.complete(
            McpMessage.Failure(
              Some(second._1.id),
              McpRpcError(-32602, "remote rejection", Some(Json.Null))
            )
          )
          firstDelivery <- registry.complete(success(first._1.id))
          firstReply    <- first._1.await
          secondReply   <- second._1.await
        yield assertTrue(
          first._1.id == McpRequestId.Number(1),
          second._1.id == McpRequestId.Number(2),
          firstDelivery == McpRequests.Delivery.Delivered,
          secondDelivery == McpRequests.Delivery.Delivered,
          firstReply == success(first._1.id),
          secondReply == failure
        )
    },
    test("capacity and request-scope closure release registrations without reusing identities") {
      ZIO.scoped:
        for
          invalid  <- McpRequests.make(0).exit
          registry <- McpRequests.make(1)
          first    <- lease(registry)
          full     <- registry.register.exit
          missing <- registry.complete(McpMessage.Failure(None, McpRpcError(-32700, "no identity")))
          mismatched  <- registry.complete(success(McpRequestId.Text("1")))
          _           <- first._2.close(Exit.unit)
          abandoned   <- first._1.await.exit
          late        <- registry.complete(success(first._1.id))
          second      <- lease(registry)
          delivered   <- registry.complete(success(second._1.id))
          duplicate   <- registry.complete(success(second._1.id))
          reply       <- second._1.await
          third       <- lease(registry)
          thirdWaiter <- third._1.await.fork
          defect = new IllegalStateException("request cleanup failed")
          domainCause = Cause.fail(AgentError.McpError("send failed")) ++ Cause.die(defect) ++ Cause
            .interrupt(FiberId.None)
          _            <- third._2.close(Exit.failCause(domainCause))
          domainExit   <- thirdWaiter.await
          fourth       <- lease(registry)
          fourthWaiter <- fourth._1.await.fork
          foreignCause = Cause.fail("foreign failure") ++ Cause.die(defect) ++ Cause.interrupt(
            FiberId.None
          )
          _           <- fourth._2.close(Exit.failCause(foreignCause))
          foreignExit <- fourthWaiter.await
          replacement <- lease(registry)
        yield assertTrue(
          invalid.isFailure,
          localFailure(full),
          localFailure(abandoned),
          missing == McpRequests.Delivery.MissingId,
          mismatched == McpRequests.Delivery.UnknownId,
          late == McpRequests.Delivery.UnknownId,
          delivered == McpRequests.Delivery.Delivered,
          duplicate == McpRequests.Delivery.UnknownId,
          second._1.id == McpRequestId.Number(2),
          reply == success(second._1.id),
          domainExit.causeOption.contains(domainCause),
          localFailure(foreignExit),
          foreignExit.causeOption.exists(cause =>
            cause.failures.size == 1 && cause.stripFailures == foreignCause.stripFailures
          ),
          replacement._1.id == McpRequestId.Number(5)
        )
    },
    test(
      "interrupted awaiting abandons its slot and completed replies survive later scope closure"
    ) {
      ZIO.scoped:
        for
          registry    <- McpRequests.make(1)
          first       <- lease(registry)
          waiter      <- first._1.await.fork
          _           <- (ZIO.yieldNow *> waiter.status).repeatUntil(_.isSuspended)
          interrupted <- waiter.interrupt
          late        <- registry.complete(success(first._1.id))
          second      <- lease(registry)
          _           <- registry.complete(success(second._1.id))
          _           <- second._2.close(Exit.fail(AgentError.McpError("late scope failure")))
          reply       <- second._1.await
        yield assertTrue(
          interrupted.isInterrupted,
          late == McpRequests.Delivery.UnknownId,
          reply == success(second._1.id)
        )
    },
    test("owner closure and terminal failure settle all waiters exactly once with complete causes") {
      ZIO.scoped:
        for
          owner       <- scope
          owned       <- McpRequests.make(1).provideEnvironment(ZEnvironment[Scope](owner))
          ownerLease  <- lease(owned)
          _           <- owner.close(Exit.unit)
          ownerResult <- ownerLease._1.await.exit
          registry    <- McpRequests.make(2)
          first       <- lease(registry)
          second      <- lease(registry)
          one         <- first._1.await.fork
          two         <- second._1.await.fork
          defect = new IllegalStateException("connection cleanup failed")
          cause = Cause.fail(AgentError.McpError("disconnected")) ++ Cause.die(defect) ++ Cause
            .interrupt(FiberId.None)
          _          <- registry.failAll(cause)
          _          <- registry.close
          _          <- registry.failAll(Cause.fail(AgentError.McpError("later failure")))
          oneExit    <- one.await
          twoExit    <- two.await
          rejected   <- registry.register.exit
          closed     <- registry.complete(success(first._1.id))
          empty      <- McpRequests.make(1)
          emptyLease <- lease(empty)
          _          <- empty.failAll(Cause.empty)
          emptyExit  <- emptyLease._1.await.exit
        yield assertTrue(
          localFailure(ownerResult),
          localFailure(rejected),
          localFailure(emptyExit),
          closed == McpRequests.Delivery.Closed,
          oneExit.causeOption.contains(cause),
          twoExit.causeOption.contains(cause)
        )
    }
  ) @@ TestAspect.timeout(10.seconds)

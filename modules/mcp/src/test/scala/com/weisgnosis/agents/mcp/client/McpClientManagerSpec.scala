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
import com.weisgnosis.agents.core.model.ToolResultState
import com.weisgnosis.agents.core.tool.ToolDef
import com.weisgnosis.agents.mcp.protocol.*
import com.weisgnosis.agents.mcp.transport.McpTransport
import zio.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

object McpClientManagerSpec extends ZIOSpecDefault:
  private enum StopCase:
    case CancelAcquisition, RemoveDiscovery

  private val schema: Json.Obj = Json.Obj("type" -> Json.Str("object"))

  private def select(
      prefix: String
  )(remote: McpToolDefinition): Either[AgentError, Option[ToolDef]] =
    Right(
      Option.when(remote.name != "hidden")(
        ToolDef(prefix + remote.name, "Host authority", schema, isReadOnly = false)
      )
    )

  private def peer(
      tools: Chunk[String],
      released: UIO[Unit] = ZIO.unit,
      listed: IO[AgentError, Unit] = ZIO.unit,
      call: McpMessage.Request => ZIO[Scope, AgentError, String] = request =>
        ZIO.succeed(request.params.flatMap(_.get("name")).flatMap(_.asString).getOrElse("missing"))
  ): ZIO[Scope, AgentError, McpClient] =
    for
      _     <- ZIO.acquireRelease(ZIO.unit)(_ => released)
      inbox <- ZIO.acquireRelease(Queue.unbounded[McpMessage])(_.shutdown)
      transport = new McpTransport:
        def incoming: ZStream[Any, AgentError, McpMessage] = ZStream.fromQueue(inbox)
        def send(message: McpMessage): ZIO[Scope, AgentError, Unit] = message match
          case request @ McpMessage.Request(id, method, _) =>
            val result = method match
              case "initialize" =>
                ZIO.succeed(
                  Json.Obj(
                    "protocolVersion" -> Json.Str(McpInitialization.protocolVersion),
                    "serverInfo" -> Json
                      .Obj("name" -> Json.Str("peer"), "version" -> Json.Str("1")),
                    "capabilities" -> Json.Obj("tools" -> Json.Obj())
                  )
                )
              case "tools/list" =>
                listed.as(
                  Json.Obj(
                    "tools" -> Json.Arr(
                      tools.map(name =>
                        Json.Obj(
                          "name"        -> Json.Str(name),
                          "inputSchema" -> schema,
                          "annotations" -> Json.Obj("readOnlyHint" -> Json.Bool(true))
                        )
                      )
                    )
                  )
                )
              case "tools/call" =>
                call(request).map(text =>
                  Json.Obj(
                    "content" -> Json.Arr(
                      Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str(text))
                    )
                  )
                )
              case _ => ZIO.fail(AgentError.McpError("Unexpected test method"))
            result.flatMap(value => inbox.offer(McpMessage.Success(id, value)).unit)
          case _: McpMessage.Notification => ZIO.unit
          case _ => ZIO.fail(AgentError.McpError("Unexpected test message"))
      client <- McpClient.open(transport, McpImplementation("client", "1"))
    yield client

  def spec: Spec[TestEnvironment, Any] = suite("McpClientManagerSpec")(
    test("registration publishes atomically with bounded membership and explicit host authority") {
      ZIO.scoped:
        for
          manager <- McpClientManager.make(maxClients = 2, maxTools = 3)
          invalidLimits <- ZIO.foreach(Chunk((0, 1), (1, 0)))((clients, tools) =>
            McpClientManager.make(clients, tools).exit
          )
          acquisitions <- Ref.make(0)
          releases     <- Ref.make(0)
          acquire = acquisitions.update(_ + 1) *> peer(
            Chunk("echo", "hidden"),
            releases.update(_ + 1)
          )
          _            <- manager.register("first", acquire, 1, select("local_"))
          duplicate    <- manager.register("first", acquire, 1, select("other_")).exit
          invalid      <- manager.register("", acquire, 1, select("other_")).exit
          invalidPages <- manager.register("invalid", acquire, 0, select("other_")).exit
          before       <- acquisitions.get
          rejected <- ZIO.foreach(
            Chunk(
              peer(Chunk("echo"), releases.update(_ + 1)),
              peer(Chunk("a", "a"), releases.update(_ + 1)),
              peer(Chunk("a", "b", "c"), releases.update(_ + 1)),
              peer(
                Chunk("a"),
                releases.update(_ + 1),
                ZIO.fail(AgentError.McpError("Discovery failed"))
              )
            )
          )(candidate => manager.register("candidate", candidate, 1, select("local_")).exit)
          failedSelection <- manager
            .register(
              "candidate",
              peer(Chunk("a"), releases.update(_ + 1)),
              1,
              _ => Left(AgentError.McpError("Rejected by host"))
            )
            .exit
          failure       = AgentError.McpError("Acquisition failed")
          firstDefect   = new IllegalStateException("First initializer defect")
          secondDefect  = new IllegalStateException("Second initializer defect")
          cleanupDefect = new IllegalStateException("Independent resource defect")
          original      = (Cause.fail(failure) ++ Cause.die(firstDefect)) ++ Cause.die(secondDefect)
          failedCause <- manager
            .register(
              "failed-cause",
              ZIO.acquireRelease(ZIO.unit)(_ => ZIO.die(cleanupDefect)) *> ZIO.refailCause(
                original
              ),
              1,
              select("")
            )
            .exit
          snapshot          <- manager.toolkit
          names             <- manager.names
          closed            <- releases.get
          result            <- snapshot.executeSafe("local_echo", Json.Obj())
          _                 <- manager.register("second", acquire, 1, select("second_"))
          capacity          <- manager.register("third", acquire, 1, select("third_")).exit
          after             <- acquisitions.get
          missing           <- manager.remove("missing")
          concurrent        <- McpClientManager.make()
          arrivals          <- Ref.make(0)
          ready             <- Promise.make[Nothing, Unit]
          rejectedResources <- Ref.make(0)
          attempts <- ZIO.foreachPar(Chunk("one", "two"))(name =>
            concurrent
              .register(
                name,
                peer(
                  Chunk("shared", name),
                  rejectedResources.update(_ + 1),
                  arrivals
                    .updateAndGet(_ + 1)
                    .flatMap(count => ready.succeed(()).when(count == 2)) *> ready.await
                ),
                1,
                select("racing_")
              )
              .exit
          )
          concurrentTools <- concurrent.toolkit
          concurrentNames <- concurrent.names
          rejectedCount   <- rejectedResources.get
        yield assertTrue(
          invalidLimits.forall(_.isFailure),
          duplicate.isFailure,
          invalid.isFailure,
          invalidPages.isFailure,
          before == 1,
          rejected.forall(_.isFailure),
          failedSelection.isFailure,
          failedCause.causeOption.exists(_.failures == List(failure)),
          failedCause.causeOption.contains(original ++ Cause.die(cleanupDefect)),
          failedCause.causeOption.exists(
            _.defects == List(firstDefect, secondDefect, cleanupDefect)
          ),
          closed == 5,
          snapshot.definitions.map(_.name) == Chunk("local_echo"),
          snapshot.definitions.forall(!_.isReadOnly),
          names == Chunk("first"),
          result.output == "echo",
          result.state == ToolResultState.Success,
          capacity.isFailure,
          after == 2,
          missing == McpClientRemoval.Missing,
          attempts.count(_.isSuccess) == 1,
          concurrentTools.size == 2,
          concurrentNames.size == 1,
          rejectedCount == 1
        )
    },
    test(
      "cancelled or removed initialization retains capacity through joined cleanup and cannot publish late"
    ) {
      ZIO
        .foreach(Chunk.fromIterable(StopCase.values)): scenario =>
          ZIO.scoped:
            for
              manager  <- McpClientManager.make(maxClients = 1)
              entered  <- Promise.make[Nothing, Unit]
              cleanup  <- Promise.make[Nothing, Unit]
              release  <- Promise.make[Nothing, Unit]
              released <- Ref.make(0)
              finalizer =
                cleanup.succeed(()) *> release.await *> released.update(_ + 1)
              acquire = scenario match
                case StopCase.CancelAcquisition =>
                  ZIO.acquireRelease(ZIO.unit)(_ => finalizer) *> entered.succeed(()) *> ZIO.never
                case StopCase.RemoveDiscovery =>
                  peer(Chunk("echo"), finalizer, entered.succeed(()) *> ZIO.never)
              registration <- manager.register("pending", acquire, 1, select("")).forkScoped
              _            <- entered.await
              stopping <- (scenario match
                case StopCase.CancelAcquisition => registration.interrupt.unit
                case StopCase.RemoveDiscovery   => manager.remove("pending").unit
              ).forkScoped
              _        <- cleanup.await
              capacity <- manager.register("other", peer(Chunk("echo")), 1, select("")).exit
              snapshot <- manager.toolkit
              pending  <- stopping.poll
              _        <- release.succeed(())
              _        <- stopping.join
              exit     <- registration.await
              count    <- released.get
              names    <- manager.names
              _        <- manager.register("other", peer(Chunk("echo")), 1, select("")).unit
            yield assertTrue(
              capacity.isFailure,
              snapshot.isEmpty,
              pending.isEmpty,
              exit.isFailure,
              count == 1,
              names.isEmpty
            )
        .map(results => results.reduceOption(_ && _).getOrElse(assertTrue(false)))
    },
    test("removal joins active calls before releasing transport and old snapshots never retarget") {
      ZIO.scoped:
        for
          manager  <- McpClientManager.make(maxClients = 3, maxTools = 2)
          entered  <- Promise.make[Nothing, Unit]
          cleanup  <- Promise.make[Nothing, Unit]
          release  <- Promise.make[Nothing, Unit]
          released <- Ref.make(false)
          acquire = peer(
            Chunk("echo"),
            released.set(true),
            call = _ =>
              ZIO.acquireRelease(ZIO.unit)(_ => cleanup.succeed(()).unit *> release.await) *>
                entered.succeed(()) *> ZIO.never
          )
          _            <- manager.register("first", acquire, 1, select("first_"))
          _            <- manager.register("second", peer(Chunk("echo")), 1, select("second_"))
          old          <- manager.toolkit
          call         <- old.executeSafe("first_echo", Json.Obj()).forkScoped
          _            <- entered.await
          removing     <- manager.remove("first").forkScoped
          _            <- cleanup.await
          snapshot     <- manager.toolkit
          unrelated    <- snapshot.executeSafe("second_echo", Json.Obj())
          collision    <- manager.register("third", peer(Chunk("echo")), 1, select("first_")).exit
          overBudget   <- manager.register("third", peer(Chunk("echo")), 1, select("third_")).exit
          earlyRelease <- released.get
          pending      <- removing.poll
          _            <- release.succeed(())
          removed      <- removing.join
          _            <- call.await
          closed       <- released.get
          _ <- manager.register(
            "first",
            peer(Chunk("echo"), call = _ => ZIO.succeed("replacement")),
            1,
            select("first_")
          )
          stale    <- old.executeSafe("first_echo", Json.Obj())
          current  <- manager.toolkit.flatMap(_.executeSafe("first_echo", Json.Obj()))
          _        <- manager.remove("second")
          external <- peer(Chunk("echo"))
          _        <- manager.register("external", ZIO.succeed(external), 1, select("external_"))
          _        <- manager.remove("external")
          externalCall <- external.callTool("echo", Some(Json.Obj())).exit
        yield assertTrue(
          !snapshot.contains("first_echo"),
          collision.isFailure,
          overBudget.isFailure,
          unrelated.output == "echo",
          !earlyRelease,
          pending.isEmpty,
          closed,
          removed == McpClientRemoval.Removed,
          stale.state == ToolResultState.Error,
          current.output == "replacement",
          externalCall.isFailure
        )
    },
    test(
      "removal and owner closure share defects, close clients concurrently and reject later acquisition"
    ) {
      ZIO.scoped:
        for
          owner        <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit).exit.unit)
          manager      <- owner.extend(McpClientManager.make())
          first        <- Promise.make[Nothing, Unit]
          second       <- Promise.make[Nothing, Unit]
          release      <- Promise.make[Nothing, Unit]
          releases     <- Ref.make(0)
          acquisitions <- Ref.make(0)
          defect = new IllegalStateException("Client resource cleanup failed")
          _ <- manager.register(
            "first",
            peer(
              Chunk("echo"),
              first.succeed(()) *> release.await *> releases.update(_ + 1) *> ZIO.die(defect)
            ),
            1,
            select("first_")
          )
          _ <- manager.register(
            "second",
            peer(Chunk("echo"), second.succeed(()) *> release.await *> releases.update(_ + 1)),
            1,
            select("second_")
          )
          removing    <- manager.remove("first").exit.forkScoped
          _           <- first.await
          closing     <- manager.close.exit.forkScoped
          _           <- second.await
          joining     <- manager.remove("first").exit.forkScoped
          rootClosing <- owner.close(Exit.unit).exit.forkScoped
          _           <- (ZIO.yieldNow *> joining.status).repeatUntil(_.isSuspended)
          _           <- (ZIO.yieldNow *> rootClosing.status).repeatUntil(_.isSuspended)
          rejected <- manager
            .register("late", acquisitions.update(_ + 1) *> peer(Chunk("echo")), 1, select(""))
            .exit
          pending <- closing.poll
          _       <- release.succeed(())
          outcomes <- ZIO.foreach(
            Chunk(removing, closing, joining, rootClosing)
          )(_.join)
          repeated      <- manager.close.exit
          acquired      <- acquisitions.get
          count         <- releases.get
          closedOwner   <- Scope.make
          _             <- closedOwner.close(Exit.unit)
          closedManager <- closedOwner.extend(McpClientManager.make())
          closedRegistration <- closedManager
            .register("closed", acquisitions.update(_ + 1) *> peer(Chunk("echo")), 1, select(""))
            .exit
          afterClosed <- acquisitions.get
        yield assertTrue(
          pending.isEmpty,
          rejected.isFailure,
          acquired == 0,
          count == 2,
          outcomes.forall(_.causeOption.exists(_.defects.contains(defect))),
          repeated.causeOption.exists(_.defects.contains(defect)),
          closedRegistration.isFailure,
          afterClosed == 0
        )
    }
  ) @@ TestAspect.timeout(15.seconds)

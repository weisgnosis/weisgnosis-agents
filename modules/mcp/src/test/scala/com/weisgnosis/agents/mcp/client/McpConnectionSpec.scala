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
import com.weisgnosis.agents.mcp.transport.{McpProtocolLease, McpTransport}
import zio.*
import zio.json.ast.Json
import zio.stream.*
import zio.test.*

object McpConnectionSpec extends ZIOSpecDefault:
  final private case class Wire(
      received: Queue[Take[AgentError, McpMessage]],
      sent: Queue[McpMessage]
  ):
    def transport(
        afterSend: McpMessage => ZIO[Scope, AgentError, Unit] = _ => ZIO.unit,
        onReadClose: UIO[Unit] = ZIO.unit
    ): McpTransport = new McpTransport:
      def send(message: McpMessage): ZIO[Scope, AgentError, Unit] =
        sent.offer(message).unit *> afterSend(message)
      def incoming: ZStream[Any, AgentError, McpMessage] =
        ZStream.fromQueue(received).flattenTake.ensuring(onReadClose)

    def receive(message: McpMessage): UIO[Unit] = received.offer(Take.single(message)).unit

    def nextRequest: IO[AgentError, McpMessage.Request] =
      sent.take.flatMap:
        case request: McpMessage.Request => ZIO.succeed(request)
        case _ => ZIO.fail(AgentError.McpError("Expected an outbound request"))

  private enum CancellationCase:
    case Awaiting, Sending, Initialize, Replied

  private def wire: ZIO[Scope, Nothing, Wire] =
    for
      received <- ZIO.acquireRelease(Queue.unbounded[Take[AgentError, McpMessage]])(_.shutdown)
      sent     <- ZIO.acquireRelease(Queue.unbounded[McpMessage])(_.shutdown)
    yield Wire(received, sent)

  private def success(id: McpRequestId): McpMessage.Success = McpMessage.Success(id, Json.Obj())

  private def localFailure(exit: Exit[AgentError, Any]): Boolean =
    exit.causeOption.exists(_.failures.exists:
      case _: AgentError.McpError => true
      case _                      => false)

  def spec: Spec[TestEnvironment, Any] = suite("McpConnectionSpec")(
    test("defective protocol lease closure still settles requests and joins owned cleanup"):
      ZIO.scoped:
        for
          duplex       <- wire
          controlReady <- Promise.make[Nothing, Unit]
          cleanupReady <- Promise.make[Nothing, Unit]
          release      <- Promise.make[Nothing, Unit]
          abortControl <- Promise.make[Nothing, Unit]
          readerClosed <- Promise.make[Nothing, Unit]
          leaseCloses  <- Ref.make(0)
          defect = new IllegalStateException("protocol lease close defect")
          _ <- ZIO.addFinalizer(
            abortControl.succeed(()).unit *> release.succeed(()).unit *>
              duplex.received.offer(Take.end).unit
          )
          transport = new McpTransport:
            override def acquireProtocolSession: ZIO[Scope, AgentError, McpProtocolLease] =
              ZIO.succeed(
                new McpProtocolLease:
                  def close: UIO[Unit] = leaseCloses.update(_ + 1) *> ZIO.die(defect)
              )
            def send(message: McpMessage): ZIO[Scope, AgentError, Unit] = message match
              case _: McpMessage.Success =>
                ZIO.acquireRelease(controlReady.succeed(()))(_ =>
                  cleanupReady.succeed(()) *> release.await
                ) *> abortControl.await *> ZIO.fail(AgentError.McpError("Fixture control ended"))
              case _ => duplex.sent.offer(message).unit
            def incoming: ZStream[Any, AgentError, McpMessage] =
              duplex.transport(onReadClose = readerClosed.succeed(()).unit).incoming
          owner <- ZIO.acquireRelease(Scope.make)(scope =>
            abortControl.succeed(()).unit *> release.succeed(()).unit *>
              duplex.received.offer(Take.end).unit *> scope.close(Exit.unit)
          )
          connection <- owner.extend(McpConnection.make(transport))
          request    <- connection.request("pending").forkScoped
          _          <- duplex.nextRequest
          _          <- duplex.receive(McpMessage.Request(McpRequestId.Text("server-ping"), "ping"))
          _          <- controlReady.await
          closing <- ZIO.uninterruptible:
            for
              fiber <- owner.close(Exit.unit).exit.forkScoped
              _ <- ZIO.addFinalizer(
                abortControl.succeed(()).unit *> release.succeed(()).unit *>
                  duplex.received.offer(Take.end).unit
              )
            yield fiber
          reachedCleanup <- cleanupReady.await.as(true).raceFirst(closing.await.as(false))
          beforeRelease  <- closing.poll
          _ <- abortControl.succeed(()) *> release.succeed(()) *>
            duplex.received.offer(Take.end)
          closed <- closing.join
          // Explicit fixture signals also finish children under the intentionally defective draft.
          pending    <- request.await
          _          <- request.interrupt
          _          <- readerClosed.await
          closeCount <- leaseCloses.get
        yield assertTrue(
          reachedCleanup,
          beforeRelease.isEmpty,
          closed.causeOption.exists(cause =>
            cause.defects.nonEmpty && cause.defects.forall(_ eq defect)
          ),
          pending.isFailure,
          closeCount == 1
        )
    ,
    test("issued abandonment notifies once without blocking cancellation or overflowing controls") {
      val eligibility = ZIO
        .foreach(Chunk.fromArray(CancellationCase.values))(mode =>
          ZIO.scoped:
            for
              duplex   <- wire
              boundary <- Promise.make[Nothing, Unit]
              transport = new McpTransport:
                def send(message: McpMessage): ZIO[Scope, AgentError, Unit] =
                  duplex.sent.offer(message).unit *> (message match
                    case _: McpMessage.Request if mode == CancellationCase.Sending =>
                      boundary.succeed(()) *> ZIO.never
                    case _ => ZIO.unit)
                override def send(message: McpMessage, onIssued: UIO[Unit])
                    : ZIO[Scope, AgentError, Unit] =
                  super.send(message, onIssued *> boundary.succeed(()).unit)
                def incoming: ZStream[Any, AgentError, McpMessage] =
                  duplex.transport().incoming
              connection <- McpConnection.make(transport)
              request <- connection
                .request(
                  if mode == CancellationCase.Initialize then "initialize" else "tools/list"
                )
                .forkScoped
              outbound <- duplex.nextRequest
              _        <- boundary.await
              _ <-
                if mode == CancellationCase.Replied then
                  duplex.receive(success(outbound.id)) *> request.join.unit
                else ZIO.unit
              exit <- request.interrupt
              marker = success(McpRequestId.Text("barrier"))
              _    <- duplex.receive(McpMessage.Request(marker.id, "ping"))
              sent <- ZStream.fromQueue(duplex.sent).takeUntil(_ == marker).runCollect
            yield assertTrue(
              mode match
                case CancellationCase.Replied => exit == Exit.succeed(success(outbound.id))
                case _                        => exit.isInterrupted,
              sent == (if mode == CancellationCase.Awaiting then
                         Chunk(
                           McpMessage.Notification(
                             "notifications/cancelled",
                             Some(
                               Json.Obj(
                                 "requestId" -> Json.Num(1)
                               )
                             )
                           ),
                           marker
                         )
                       else Chunk(marker))
            )
        )
        .map(_.foldLeft(assertTrue(true))(_ && _))
      val bounded = ZIO.scoped:
        for
          duplex   <- wire
          writing  <- Promise.make[Nothing, Unit]
          cleaning <- Promise.make[Nothing, Unit]
          release  <- Promise.make[Nothing, Unit]
          issued   <- ZIO.acquireRelease(Queue.unbounded[Unit])(_.shutdown)
          transport = new McpTransport:
            def send(message: McpMessage): ZIO[Scope, AgentError, Unit] =
              duplex.sent.offer(message).unit *> (message match
                case McpMessage.Notification("notifications/cancelled", _) =>
                  ZIO.acquireRelease(writing.succeed(()))(_ =>
                    cleaning.succeed(()) *> release.await
                  ) *> ZIO.never
                case _ => ZIO.unit)
            override def send(
                message: McpMessage,
                onIssued: UIO[Unit]
            ): ZIO[Scope, AgentError, Unit] =
              super.send(message, onIssued *> issued.offer(()).unit)
            def incoming: ZStream[Any, AgentError, McpMessage] = duplex.transport().incoming
          connection <- McpConnection.make(transport, controlCapacity = 1)
          result <- ZIO.scoped {
            (for
              first         <- connection.request("one").forkScoped
              _             <- duplex.nextRequest *> issued.take
              stoppedFirst  <- first.interrupt
              _             <- writing.await
              sentFirst     <- duplex.sent.take
              second        <- connection.request("two").forkScoped
              _             <- duplex.nextRequest *> issued.take
              stoppedSecond <- second.interrupt
              third         <- connection.request("three").forkScoped
              _             <- duplex.nextRequest *> issued.take
              stoppedThird  <- third.interrupt
              later         <- connection.request("still open").forkScoped
              next          <- duplex.nextRequest
              _             <- duplex.receive(success(next.id))
              reply         <- later.join
              closing       <- connection.close.forkScoped
              _             <- cleaning.await
              pending       <- closing.poll
              _             <- release.succeed(())
              _             <- closing.join
              remaining     <- duplex.sent.takeAll
            yield assertTrue(
              stoppedFirst.isInterrupted,
              stoppedSecond.isInterrupted,
              stoppedThird.isInterrupted,
              sentFirst == McpMessage.Notification(
                "notifications/cancelled",
                Some(
                  Json.Obj(
                    "requestId" -> Json.Num(1)
                  )
                )
              ),
              reply == success(next.id),
              pending.isEmpty,
              remaining.isEmpty
            )).ensuring(release.succeed(()).unit)
          }
        yield result
      eligibility.zipWith(bounded)(_ && _)
    },
    test(
      "registers before sending and correlates concurrent replies without treating remote errors as local failures"
    ) {
      ZIO.scoped:
        for
          immediateWire <- wire
          immediate <- McpConnection.make(immediateWire.transport:
            case McpMessage.Request(_, "send-failure", _) =>
              ZIO.fail(AgentError.McpError("send failed"))
            case request: McpMessage.Request => immediateWire.receive(success(request.id))
            case _                           => ZIO.unit)
          immediateReply <- immediate.request("ping")
          sendFailure    <- immediate.request("send-failure").exit
          recovered      <- immediate.request("ping").exit
          _              <- immediateWire.sent.takeAll
          invalid <- immediate
            .request("invalid", Some(Json.Obj("key" -> Json.Null, "key" -> Json.Null)))
            .exit
          wrongId <- immediate
            .request(_ => Right(McpMessage.Request(McpRequestId.Number(-1), "wrong")))
            .exit
          rejectedBuilder <- immediate
            .request(_ => Left(AgentError.InvalidMessage("Rejected request payload")))
            .exit
          leaked        <- immediateWire.sent.takeAll
          duplex        <- wire
          connection    <- McpConnection.make(duplex.transport())
          first         <- connection.request("first").forkScoped
          firstRequest  <- duplex.nextRequest
          second        <- connection.request("second").forkScoped
          secondRequest <- duplex.nextRequest
          remote = McpMessage.Failure(Some(secondRequest.id), McpRpcError(-32602, "rejected"))
          _            <- duplex.receive(remote)
          _            <- duplex.receive(success(firstRequest.id))
          secondReply  <- second.join
          firstReply   <- first.join
          _            <- connection.notify(McpMessage.Notification("notifications/initialized"))
          notification <- duplex.sent.take
        yield assertTrue(
          immediateReply == success(McpRequestId.Number(1)),
          localFailure(sendFailure),
          recovered.isSuccess,
          invalid.isFailure,
          wrongId.isFailure,
          rejectedBuilder.isFailure,
          leaked.isEmpty,
          firstReply == success(firstRequest.id),
          secondReply == remote,
          firstRequest.id != secondRequest.id,
          notification == McpMessage.Notification("notifications/initialized")
        )
    },
    test(
      "cancellation waits for send cleanup, preserves its defect, and releases bounded admission"
    ) {
      ZIO.scoped:
        for
          duplex         <- wire
          cleanupStarted <- Promise.make[Nothing, Unit]
          releaseCleanup <- Promise.make[Nothing, Unit]
          entered        <- Promise.make[Nothing, Unit]
          cleaned        <- Ref.make(false)
          defect = new IllegalStateException("send cleanup defect")
          connection <- McpConnection.make(
            duplex.transport {
              case McpMessage.Request(_, "blocked", _) =>
                ZIO.acquireRelease(ZIO.unit)(_ =>
                  cleanupStarted.succeed(()).unit *> releaseCleanup.await *>
                    cleaned.set(true) *> ZIO.die(defect)
                ) *> entered.succeed(()) *> ZIO.never
              case request: McpMessage.Request => duplex.receive(success(request.id))
              case _                           => ZIO.unit
            },
            capacity = 1
          )
          request     <- connection.request("blocked").forkScoped
          _           <- duplex.nextRequest
          _           <- entered.await
          full        <- connection.request("full").exit
          cancel      <- request.interrupt.forkScoped
          _           <- cleanupStarted.await
          before      <- cancel.poll
          _           <- releaseCleanup.succeed(())
          interrupted <- cancel.join
          after       <- cleaned.get
          replacement <- connection.request("replacement")
        yield assertTrue(
          localFailure(full),
          before.isEmpty,
          after,
          interrupted.isInterrupted,
          interrupted.causeOption.exists(_.defects.contains(defect)),
          replacement == success(McpRequestId.Number(2))
        )
    },
    test("reader failure preserves its complete cause during send and reply waiting") {
      ZIO
        .foreach(Chunk("blocked", "awaiting")): mode =>
          for
            observed <- Promise.make[Nothing, TestResult]
            defect = new IllegalStateException("reader defect")
            cause  = Cause.fail(AgentError.McpError("reader failed")) ++ Cause.die(defect)
            scopeExit <- ZIO
              .scoped(
                for
                  duplex  <- wire
                  cleaned <- Promise.make[Nothing, Unit]
                  entered <- Promise.make[Nothing, Unit]
                  connection <- McpConnection.make(
                    duplex.transport {
                      case request: McpMessage.Request =>
                        ZIO.acquireRelease(ZIO.unit)(_ => cleaned.succeed(()).unit) *>
                          (if mode == "blocked" then duplex.receive(success(request.id))
                           else ZIO.unit) *>
                          entered.succeed(()) *>
                          (if mode == "blocked" then ZIO.never else ZIO.unit)
                      case _ => ZIO.unit
                    },
                    capacity = 1
                  )
                  request          <- connection.request(mode).forkScoped
                  _                <- duplex.nextRequest
                  _                <- entered.await
                  full             <- connection.request("full").exit
                  _                <- duplex.received.offer(Take.failCause(cause))
                  failed           <- request.await
                  _                <- cleaned.await
                  rejected         <- connection.request("after failure").exit
                  sentAfterFailure <- duplex.sent.takeAll
                  _ <- observed.succeed(
                    assertTrue(
                      localFailure(full),
                      failed.causeOption.contains(cause),
                      rejected.causeOption.contains(cause),
                      sentAfterFailure.isEmpty
                    )
                  )
                yield ()
              )
              .exit
            result <- observed.await
          yield result && assertTrue(scopeExit.causeOption.exists(_.defects.contains(defect)))
        .map(results => results.foldLeft(assertTrue(true))(_ && _))
    },
    test(
      "terminal failure during failing-send cleanup retains one cause without owned cancellation"
    ) {
      ZIO.scoped:
        for
          duplex    <- wire
          cleanup   <- Promise.make[Nothing, Unit]
          cancelled <- Promise.make[Nothing, Unit]
          release   <- Promise.make[Nothing, Unit]
          cause = Cause
            .fail(AgentError.McpSessionExpired())
            .annotated(Map("request-origin" -> "resource-regression"))
            .spanned(List(LogSpan("failing-send", 1L)))
          connection <- McpConnection.make(duplex.transport:
            case _: McpMessage.Request =>
              ZIO
                .failCause(cause)
                .ensuring(
                  cleanup.succeed(()).unit *>
                    ZIO.interruptible(ZIO.never).exit *>
                    cancelled.succeed(()).unit *> release.await
                )
            case _ => ZIO.unit)
          request <- connection.request("failing-send").forkScoped
          _       <- duplex.nextRequest
          _       <- cleanup.await
          _       <- duplex.received.offer(Take.failCause(cause))
          _       <- cancelled.await
          before  <- request.poll
          _       <- release.succeed(())
          exit    <- request.await
        yield assertTrue(
          before.isEmpty,
          exit.causeOption.contains(cause),
          exit.causeOption.exists(_.annotations == cause.annotations),
          exit.causeOption.exists(_.spans == cause.spans)
        )
    },
    test("acquisition in an already closed parent scope cannot start borrowed transport activity") {
      for
        parent <- Scope.make
        _      <- parent.close(Exit.unit)
        starts <- Ref.make(0)
        transport = new McpTransport:
          def send(message: McpMessage): ZIO[Scope, AgentError, Unit] = ZIO.unit
          def incoming: ZStream[Any, AgentError, McpMessage] =
            ZStream.fromZIO(starts.update(_ + 1)) *> ZStream.never
        _     <- McpConnection.make(transport).provideEnvironment(ZEnvironment[Scope](parent))
        count <- starts.get
      yield assertTrue(count == 0)
    },
    test(
      "control writes do not block reply correlation and incoming cancellation cannot cancel outgoing IDs"
    ) {
      ZIO.scoped:
        for
          duplex  <- wire
          writing <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          connection <- McpConnection.make(duplex.transport:
            case _: McpMessage.Success => writing.succeed(()).unit *> release.await
            case _                     => ZIO.unit)
          request  <- connection.request("tools/list").forkScoped
          outbound <- duplex.nextRequest
          _        <- duplex.receive(McpMessage.Request(outbound.id, "ping"))
          _        <- writing.await
          ping     <- duplex.sent.take
          _ <- duplex.receive(
            McpMessage.Notification(
              "notifications/cancelled",
              Some(
                Json.Obj(
                  "requestId" -> Json.Num(1)
                )
              )
            )
          )
          _     <- duplex.receive(success(outbound.id))
          reply <- request.join
          _     <- release.succeed(())
          _ <- duplex.receive(
            McpMessage.Request(McpRequestId.Text("server"), "sampling/createMessage")
          )
          unsupported <- duplex.sent.take
        yield assertTrue(
          ping == success(outbound.id),
          reply == success(outbound.id),
          unsupported match
            case McpMessage.Failure(Some(McpRequestId.Text("server")), error) =>
              error.code == BigInt(-32601)
            case _ => false
        )
    },
    test(
      "concurrent close waits for active cleanup and reader release before the borrowed transport closes"
    ) {
      for
        order    <- Ref.make(Chunk.empty[String])
        observed <- Promise.make[Nothing, TestResult]
        defect = new IllegalStateException("close cleanup defect")
        scopeExit <- ZIO
          .scoped(
            for
              _       <- ZIO.acquireRelease(ZIO.unit)(_ => order.update(_ :+ "transport"))
              duplex  <- wire
              cleanup <- Promise.make[Nothing, Unit]
              release <- Promise.make[Nothing, Unit]
              entered <- Promise.make[Nothing, Unit]
              connection <- McpConnection.make(
                duplex.transport(
                  _ =>
                    ZIO.acquireRelease(ZIO.unit)(_ =>
                      cleanup.succeed(()).unit *> release.await *> order.update(_ :+ "send") *> ZIO
                        .die(defect)
                    ) *>
                      entered.succeed(()) *> ZIO.never,
                  order.update(_ :+ "reader")
                )
              )
              request  <- connection.request("blocked").forkScoped
              _        <- duplex.nextRequest
              _        <- entered.await
              closeOne <- connection.close.forkScoped
              _        <- cleanup.await
              closeTwo <- connection.close.forkScoped
              rejected <- connection.request("closed").exit
              before   <- closeOne.poll.zip(closeTwo.poll)
              _        <- release.succeed(())
              closers  <- closeOne.await.zipPar(closeTwo.await)
              failed   <- request.await
              inside   <- order.get
              _ <- observed.succeed(
                assertTrue(
                  before._1.isEmpty,
                  before._2.isEmpty,
                  localFailure(rejected),
                  localFailure(failed),
                  inside.contains("send"),
                  inside.contains("reader"),
                  !inside.contains("transport"),
                  closers._1.causeOption.exists(_.defects.contains(defect)),
                  closers._2.causeOption == closers._1.causeOption
                )
              )
            yield ()
          )
          .exit
        result     <- observed.await
        finalOrder <- order.get
      yield result && assertTrue(
        finalOrder.lastOption.contains("transport"),
        scopeExit.causeOption.exists(_.defects.contains(defect))
      )
    },
    test("EOF, uncorrelatable peer errors, and control overflow terminate admission") {
      ZIO.scoped:
        for
          afterTerminalSent <- Ref.make(0)
          written           <- Promise.make[Nothing, Unit]
          readerStopped     <- Promise.make[Nothing, Unit]
          terminalTransport = new McpTransport:
            def send(message: McpMessage): ZIO[Scope, AgentError, Unit] =
              afterTerminalSent.update(_ + 1) *> written.succeed(()).unit
            def incoming: ZStream[Any, AgentError, McpMessage] =
              (ZStream.fromIterable(
                Chunk(
                  McpMessage.Failure(None, McpRpcError(-32700, "invalid JSON")),
                  McpMessage.Request(McpRequestId.Text("after-terminal"), "ping")
                )
              ) ++ ZStream.fromZIO(written.await).drain)
                .ensuring(readerStopped.succeed(()).unit)
          _              <- McpConnection.make(terminalTransport)
          _              <- readerStopped.await
          terminalSends  <- afterTerminalSent.get
          duplex         <- wire
          invalid        <- McpConnection.make(duplex.transport(), capacity = 0).exit
          invalidControl <- McpConnection.make(duplex.transport(), controlCapacity = 0).exit
          connection     <- McpConnection.make(duplex.transport())
          request        <- connection.request("pending").forkScoped
          _              <- duplex.nextRequest
          _              <- duplex.received.offer(Take.end)
          eof            <- request.await
          peer           <- wire
          peerConnection <- McpConnection.make(peer.transport())
          peerRequest    <- peerConnection.request("pending").forkScoped
          _              <- peer.nextRequest
          _           <- peer.receive(McpMessage.Failure(None, McpRpcError(-32700, "invalid JSON")))
          peerFailure <- peerRequest.await
          flooded     <- wire
          writing     <- Promise.make[Nothing, Unit]
          floodConnection <- McpConnection.make(
            flooded.transport {
              case _: McpMessage.Success => writing.succeed(()).unit *> ZIO.never
              case _                     => ZIO.unit
            },
            controlCapacity = 1
          )
          floodRequest <- floodConnection.request("pending").forkScoped
          _            <- flooded.nextRequest
          _            <- flooded.receive(McpMessage.Request(McpRequestId.Text("one"), "ping"))
          _            <- writing.await
          _            <- flooded.receive(McpMessage.Request(McpRequestId.Text("two"), "ping"))
          _            <- flooded.receive(McpMessage.Request(McpRequestId.Text("three"), "ping"))
          overflow     <- floodRequest.await
        yield assertTrue(
          terminalSends == 0,
          invalid.isFailure,
          invalidControl.isFailure,
          localFailure(eof),
          localFailure(peerFailure),
          localFailure(overflow)
        )
    }
  ) @@ TestAspect.timeout(10.seconds)

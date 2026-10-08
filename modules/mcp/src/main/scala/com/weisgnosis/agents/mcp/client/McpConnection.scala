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
import com.weisgnosis.agents.mcp.protocol.{
  McpBatchMember,
  McpMessage,
  McpPacket,
  McpRequestId,
  McpRpcError
}
import com.weisgnosis.agents.mcp.transport.{McpTransport, McpProtocolLease}
import zio.*
import zio.json.ast.Json

/**
 * Scoped RPC ownership over a borrowed transport; no initialization, version negotiation or deadlines.
 * Interrupted issued requests enqueue best-effort cancellation notifications; delivery
 * is connection-owned and may be dropped when its control queue is full. Incoming notifications are
 * ignored by the public factory, including server cancellation:
 * server request identities never grant authority to cancel this connection's outbound requests.
 * Owned sends, the incoming stream, and their finalizers must not close their own connection.
 */
trait McpConnection:
  def request(method: String, params: Option[Json.Obj] = None): IO[AgentError, McpRequests.Reply]

  /** Custom implementations must allocate and register an identity before invoking this builder. */
  def request(
      build: McpRequestId => Either[AgentError.InvalidMessage, McpMessage.Request]
  ): IO[AgentError, McpRequests.Reply]
  def notify(notification: McpMessage.Notification): IO[AgentError, Unit]
  def close: UIO[Unit]

object McpConnection:
  private enum Admission:
    case Open
    case Stopped(cause: Cause[AgentError])

  private enum Dispatch:
    case NotIssued
    case Issued(request: McpMessage.Request)

  private enum Closing:
    case Open, Started

  private enum Observation[+A]:
    case Completed(exit: Exit[AgentError, A])
    case Stopped(cause: Cause[AgentError])

  def make(
      transport: McpTransport,
      capacity: Int = 64,
      controlCapacity: Int = 16
  ): ZIO[Scope, AgentError, McpConnection] =
    observed(transport, capacity, controlCapacity, _ => ZIO.unit, _ => ZIO.unit)

  /** Internal observers perform bounded local publication only; they never await remote work. */
  private[mcp] def observed(
      transport: McpTransport,
      capacity: Int,
      controlCapacity: Int,
      observeNotification: McpMessage.Notification => UIO[Unit],
      observeStop: Cause[AgentError] => UIO[Unit]
  ): ZIO[Scope, AgentError, McpConnection] =
    if capacity <= 0 || controlCapacity <= 0 then
      ZIO.fail(AgentError.InvalidConfiguration("MCP connection capacities must be positive"))
    else
      ZIO.uninterruptible:
        for
          lease        <- transport.acquireProtocolSession
          owner        <- Scope.make
          requests     <- owner.extend(McpRequests.make(capacity))
          gate         <- Semaphore.make(1)
          admission    <- Ref.make[Admission](Admission.Open)
          active       <- Ref.make(Map.empty[Promise[Nothing, Unit], Fiber[AgentError, Any]])
          terminal     <- Promise.make[Nothing, Cause[AgentError]]
          controls     <- Queue.dropping[McpPacket](controlCapacity)
          background   <- Ref.make(Chunk.empty[Fiber.Runtime[AgentError, Any]])
          initializing <- Ref.make(Set.empty[McpRequestId])
          closing      <- Ref.make(Closing.Open)
          closed       <- Promise.make[Nothing, Exit[Nothing, Unit]]
          live = new Live(
            transport,
            lease,
            capacity,
            owner,
            requests,
            gate,
            admission,
            active,
            terminal,
            controls,
            background,
            closing,
            initializing,
            closed,
            observeNotification,
            observeStop
          )
          _ <- ZIO.addFinalizer(live.close)
          _ <- live.start
        yield live

  final private class Live(
      transport: McpTransport,
      lease: McpProtocolLease,
      capacity: Int,
      owner: Scope.Closeable,
      requests: McpRequests,
      gate: Semaphore,
      admission: Ref[Admission],
      active: Ref[Map[Promise[Nothing, Unit], Fiber[AgentError, Any]]],
      terminal: Promise[Nothing, Cause[AgentError]],
      controls: Queue[McpPacket],
      background: Ref[Chunk[Fiber.Runtime[AgentError, Any]]],
      closing: Ref[Closing],
      initializing: Ref[Set[McpRequestId]],
      closed: Promise[Nothing, Exit[Nothing, Unit]],
      observeNotification: McpMessage.Notification => UIO[Unit],
      observeStop: Cause[AgentError] => UIO[Unit]
  ) extends McpConnection:
    def request(method: String, params: Option[Json.Obj]): IO[AgentError, McpRequests.Reply] =
      request(id => Right(McpMessage.Request(id, method, params)))

    def request(
        build: McpRequestId => Either[AgentError.InvalidMessage, McpMessage.Request]
    ): IO[AgentError, McpRequests.Reply] =
      execute:
        for
          dispatch <- Ref.make[Dispatch](Dispatch.NotIssued)
          pending <- reserve(cause =>
            if cause.isInterrupted then cancelIssued(dispatch) else ZIO.unit
          )
          message <- ZIO.suspendSucceed(ZIO.fromEither(build(pending.id)))
          _ <- ZIO
            .fail(AgentError.InvalidMessage("MCP request builder changed its allocated identity"))
            .unless(message.id == pending.id)
          _ <-
            if message.method == "initialize" then
              ZIO.acquireRelease(initializing.update(_ + message.id))(_ =>
                initializing.update(_ - message.id)
              )
            else ZIO.unit
          _ <- ZIO.fromEither(McpMessage.toJson(message)) *>
            ZIO.suspendSucceed(transport.send(message, dispatch.set(Dispatch.Issued(message))))
          reply <- pending.await
        yield reply

    /** Reserve in the fresh action scope before stop can close the request registry. */
    private def reserve(
        onAbandon: Cause[AgentError] => UIO[Unit]
    ): ZIO[Scope, AgentError, McpRequests.PendingRequest] =
      gate
        .withPermit(
          ZIO.uninterruptible:
            admission.get.flatMap:
              case Admission.Open       => requests.register(onAbandon).map(Some(_))
              case Admission.Stopped(_) => ZIO.none
        )
        .flatMap:
          case Some(pending) => ZIO.succeed(pending)
          // The supervisor owns terminal delivery; never wait while holding its admission gate.
          case None => ZIO.never

    /** The registry settles abandonment first; this bounded offer never waits on transport I/O. */
    private def cancelIssued(dispatch: Ref[Dispatch]): UIO[Unit] =
      dispatch.get.flatMap:
        case Dispatch.NotIssued                                         => ZIO.unit
        case Dispatch.Issued(request) if request.method == "initialize" => ZIO.unit
        case Dispatch.Issued(request) =>
          val id = request.id match
            case McpRequestId.Text(value)   => Json.Str(value)
            case McpRequestId.Number(value) => Json.Num(BigDecimal(value))
          val notification = McpMessage.Notification(
            "notifications/cancelled",
            Some(Json.Obj("requestId" -> id))
          )
          ZIO.uninterruptible:
            gate.withPermit:
              admission.get.flatMap:
                case Admission.Open       => controls.offer(McpPacket.Single(notification)).unit
                case Admission.Stopped(_) => ZIO.unit

    def notify(notification: McpMessage.Notification): IO[AgentError, Unit] =
      execute(send(notification))

    private def send(message: McpMessage): ZIO[Scope, AgentError, Unit] =
      ZIO.fromEither(McpMessage.toJson(message)) *> ZIO.suspendSucceed(transport.send(message))

    /** Publication is masked and bounded; no transport operation or join runs under this gate. */
    private def launch[A](
        effect: ZIO[Scope, AgentError, A]
    ): IO[AgentError, Fiber.Runtime[AgentError, A]] =
      gate
        .withPermit(
          admission.get.flatMap:
            case Admission.Stopped(cause) => ZIO.refailCause(cause)
            case Admission.Open =>
              active.get.flatMap:
                case workers if workers.size >= capacity =>
                  ZIO.fail(AgentError.McpError("MCP connection operation capacity reached"))
                case _ =>
                  for
                    ready <- Promise.make[Nothing, Unit]
                    worker <- (ready.await *> supervise(effect))
                      .ensuring(gate.withPermit(admission.get.flatMap:
                        case Admission.Open       => active.update(_.removed(ready))
                        case Admission.Stopped(_) => ZIO.unit))
                      .interruptible
                      .forkIn(owner)
                    _ <- active.update(_.updated(ready, worker))
                  yield (ready, worker)
        )
        .flatMap((ready, worker) => ready.succeed(()).as(worker))

    private def execute[A](effect: ZIO[Scope, AgentError, A]): IO[AgentError, A] =
      ZIO.uninterruptibleMask: restore =>
        for
          worker   <- launch(effect)
          observed <- restore(worker.await).exit
          result <- observed match
            case Exit.Success(exit) => exit
            case Exit.Failure(cause) =>
              cancelAndFail(worker, cause)
        yield result

    /** The owned worker supervises its action even if its public caller no longer waits. */
    private def supervise[A](effect: ZIO[Scope, AgentError, A]): IO[AgentError, A] =
      ZIO.scoped:
        ZIO.uninterruptibleMask: restore =>
          for
            action <- ZIO.scoped(effect).interruptible.forkScoped
            observed <- restore(
              action.await
                .map[Observation[A]](Observation.Completed(_))
                .raceFirst(
                  terminal.await.map[Observation[A]](Observation.Stopped(_))
                )
            ).exit
            result <- observed match
              case Exit.Success(Observation.Completed(exit)) => exit
              case Exit.Success(Observation.Stopped(cause)) =>
                cancelAndFail(action, cause)
              case Exit.Failure(cause) =>
                cancelAndFail(action, cause)
          yield result

    /** Cancel and join the owned fiber, retaining the identity of this cleanup interrupter. */
    private def cancelAndFail[A](
        fiber: Fiber[AgentError, A],
        cause: Cause[AgentError]
    ): IO[AgentError, A] =
      ZIO.fiberId.flatMap: interrupter =>
        fiber.interrupt.flatMap(exit => ZIO.refailCause(combine(cause, exit, interrupter)))

    /**
     * Remove only the interruption issued by this owner while joining the loser. Independent
     * interruption, typed failures and cleanup defects remain part of the completed cause.
     */
    private def combine[A](
        cause: Cause[AgentError],
        exit: Exit[AgentError, A],
        interrupter: FiberId
    ): Cause[AgentError] =
      exit match
        case Exit.Failure(observed) =>
          val independent = observed.foldLog[Cause[AgentError]](
            Cause.empty,
            (error, trace, spans, annotations) =>
              Cause.fail(error, trace).spanned(spans).annotated(annotations),
            (defect, trace, spans, annotations) =>
              Cause.die(defect, trace).spanned(spans).annotated(annotations),
            (id, trace, spans, annotations) =>
              if id == interrupter then Cause.empty
              else Cause.interrupt(id, trace).spanned(spans).annotated(annotations)
          )(_ ++ _, _ && _, (value, stackless) => Cause.Stackless(value, stackless))
          if independent.contains(cause) then independent
          else if independent.isEmpty then cause
          else cause ++ independent
        case _ => cause

    /** Publish once, then signal services without joining; self-interruption cannot stop fan-out. */
    private def stop(cause: Cause[AgentError]): UIO[Unit] =
      ZIO.uninterruptible:
        for
          stopped <- gate.withPermit(admission.get.flatMap:
            case Admission.Stopped(_) =>
              ZIO.succeed((Chunk.empty[Fiber.Runtime[AgentError, Any]], Exit.unit))
            case Admission.Open =>
              for
                _         <- admission.set(Admission.Stopped(cause))
                leaseExit <- lease.close.exit
                completeCause = leaseExit match
                  case Exit.Success(_)          => cause
                  case Exit.Failure(leaseCause) => cause ++ leaseCause
                _        <- admission.set(Admission.Stopped(completeCause))
                _        <- requests.failAll(completeCause)
                _        <- terminal.succeed(completeCause)
                _        <- observeStop(completeCause)
                services <- background.get
              yield (services, leaseExit))
          interrupter <- ZIO.fiberId
          _           <- ZIO.foreachDiscard(stopped._1)(_.interruptAsFork(interrupter))
          _           <- stopped._2
        yield ()

    private def controlReply(id: McpRequestId, method: String): McpMessage =
      if method == "ping" then McpMessage.Success(id, Json.Obj())
      else McpMessage.Failure(Some(id), McpRpcError(-32601, "Method not found"))

    private def enqueue(packet: McpPacket): UIO[Unit] =
      controls
        .offer(packet)
        .flatMap:
          case true => ZIO.unit
          case false =>
            stop(Cause.fail(AgentError.McpError("MCP control response capacity reached")))

    private def receivePacket(packet: McpPacket): UIO[Unit] = packet match
      case McpPacket.Single(message) => receive(message)
      case McpPacket.InvalidRequest  => enqueue(McpPacket.InvalidRequest)
      case McpPacket.Batch(members) =>
        initializing.get.flatMap: ids =>
          val initializationReply = members.exists:
            case McpBatchMember.Message(reply: McpMessage.Success) => ids.contains(reply.id)
            case McpBatchMember.Message(reply: McpMessage.Failure) => reply.id.exists(ids.contains)
            case _                                                 => false
          if initializationReply then
            stop(Cause.fail(AgentError.McpError("MCP initialization cannot receive batch packets")))
          else receiveBatch(members)

    private def receiveBatch(members: Chunk[McpBatchMember]): UIO[Unit] =
      val replies = members.flatMap:
        case McpBatchMember.InvalidRequest => Chunk(McpBatchMember.InvalidRequest)
        case McpBatchMember.Message(McpMessage.Request(id, method, _)) =>
          Chunk(McpBatchMember.Message(controlReply(id, method)))
        case _ => Chunk.empty
      ZIO.foreachDiscard(members):
        case McpBatchMember.Message(_: McpMessage.Request) | McpBatchMember.InvalidRequest =>
          ZIO.unit
        case McpBatchMember.Message(message) => receive(message)
      *> (if replies.isEmpty then ZIO.unit else enqueue(McpPacket.Batch(replies)))

    private def receive(message: McpMessage): UIO[Unit] = message match
      case reply: McpMessage.Success => requests.complete(reply).unit
      case reply: McpMessage.Failure =>
        reply.id match
          case Some(_) => requests.complete(reply).unit
          case None =>
            stop(Cause.fail(AgentError.McpError("MCP received an uncorrelated protocol error")))
      case McpMessage.Request(id, method, _) => enqueue(McpPacket.Single(controlReply(id, method)))
      case notification: McpMessage.Notification => observeNotification(notification)

    def start: UIO[Unit] =
      for
        ready <- Promise.make[Nothing, Unit]
        _ <- gate.withPermit(admission.get.flatMap:
          case Admission.Stopped(_) => ZIO.unit
          case Admission.Open =>
            for
              reader <- (ready.await *> ZIO.suspendSucceed(
                transport.incomingPackets.runForeach(receivePacket)
              ))
                .onExit:
                  case Exit.Failure(cause) => stop(cause)
                  case Exit.Success(_) =>
                    stop(Cause.fail(AgentError.McpError("MCP incoming stream ended")))
                .interruptible
                .forkIn(owner)
              writer <- (ready.await *> controls.take
                .flatMap(packet => ZIO.scoped(transport.sendPacket(packet)))
                .forever)
                .onExit:
                  case Exit.Failure(cause) => stop(cause)
                  case Exit.Success(_)     => ZIO.unit
                .interruptible
                .forkIn(owner)
              _ <- background.set(Chunk(reader, writer))
            yield ())
        _ <- ready.succeed(())
      yield ()

    private def defects(exit: Exit[AgentError, Any]): Cause[Nothing] =
      exit.causeOption.flatMap(_.keepDefects).getOrElse(Cause.empty)

    private def shutdown: UIO[Unit] =
      for
        stopExit <- stop(Cause.fail(AgentError.McpError("MCP connection closed"))).exit
        workers  <- gate.withPermit(active.get.map(_.values.toList))
        services <- background.get
        results <- ZIO.foreachPar(workers ++ services.toList)(_.await.map(exit =>
          val cause = defects(exit)
          if cause.isEmpty then Exit.unit else Exit.failCause(cause)
        ))
        _         <- active.set(Map.empty)
        _         <- controls.shutdown
        ownerExit <- owner.close(Exit.unit).exit
        _         <- Exit.collectAllParDiscard(results ++ List(stopExit, ownerExit))
      yield ()

    def close: UIO[Unit] =
      ZIO.uninterruptible:
        closing
          .getAndSet(Closing.Started)
          .flatMap:
            case Closing.Open =>
              shutdown.exit.flatMap(closed.succeed).unit *> closed.await.flatMap(exit => exit)
            case Closing.Started => closed.await.flatMap(exit => exit)

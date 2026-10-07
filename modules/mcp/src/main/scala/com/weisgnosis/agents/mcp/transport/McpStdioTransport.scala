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
package com.weisgnosis.agents.mcp.transport

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.mcp.protocol.{McpMessage, McpProtocolVersion}
import scala.jdk.CollectionConverters.*
import zio.*
import zio.stream.{ZChannel, ZStream}

/** A host-selected direct process; consumers must remain within its acquisition scope. */
object McpStdioTransport:
  enum StderrPolicy:
    case Discard, Inherit

  private enum Reader:
    case Available, Claimed

  private enum Closing:
    case Open, Started

  def open(
      command: Chunk[String],
      maxFrameBytes: Int = 1048576,
      stderr: StderrPolicy = StderrPolicy.Discard,
      environment: Map[String, String] = Map.empty,
      version: McpProtocolVersion = McpProtocolVersion.V20250618
  ): ZIO[Scope, AgentError, McpTransport] =
    if command.headOption.forall(_.trim.isEmpty) || maxFrameBytes <= 0 then
      ZIO.fail(
        AgentError.InvalidConfiguration(
          "MCP stdio requires an executable and a positive frame limit"
        )
      )
    else if environment.keys.exists(key =>
        environment.keys.exists(other => key != other && key.equalsIgnoreCase(other))
      ) ||
      !environment.forall((key, value) =>
        key.nonEmpty && !key.contains('=') && !key.contains('\u0000') && !value.contains('\u0000')
      )
    then ZIO.fail(AgentError.InvalidConfiguration("MCP stdio environment overrides are invalid"))
    else
      for
        writes   <- Semaphore.make(1)
        reader   <- Ref.make(Reader.Available)
        terminal <- Promise.make[Nothing, Cause[AgentError]]
        closing  <- Ref.make(Closing.Open)
        closed   <- Promise.make[Nothing, Exit[Nothing, Unit]]
        transport <- ZIO.acquireRelease(
          ZIO
            .attemptBlocking {
              val builder = new ProcessBuilder(command.toSeq*)
              val _ = builder.redirectError(stderr match
                case StderrPolicy.Discard => ProcessBuilder.Redirect.DISCARD
                case StderrPolicy.Inherit => ProcessBuilder.Redirect.INHERIT)
              builder
            }
            .mapError(ioError("configure", _))
            .flatMap(builder =>
              ZIO
                .attemptBlocking:
                  val target  = builder.environment()
                  val windows = java.lang.System.getProperty("os.name", "").startsWith("Windows")
                  environment.foreach: (key, value) =>
                    val selectedKey =
                      if windows then
                        target
                          .keySet()
                          .iterator()
                          .asScala
                          .find(_.equalsIgnoreCase(key))
                          .getOrElse(key)
                      else key
                    val _ = target.put(selectedKey, value)
                .mapError(_ =>
                  AgentError.InvalidConfiguration("MCP stdio environment overrides are invalid")
                ) *>
                ZIO.attemptBlocking(builder.start()).mapError(ioError("start", _))
            )
            .map(process =>
              new Live(process, maxFrameBytes, version, writes, reader, terminal, closing, closed)
            )
        )(_.close)
      yield transport

  private def ioError(operation: String, error: Throwable): AgentError =
    AgentError.McpError(
      s"MCP stdio $operation failed (${error.getClass.getName}): ${Option(error.getMessage).getOrElse("no message")}"
    )

  final private class Live(
      process: Process,
      maxFrameBytes: Int,
      override val protocolVersion: McpProtocolVersion,
      writes: Semaphore,
      reader: Ref[Reader],
      terminal: Promise[Nothing, Cause[AgentError]],
      closing: Ref[Closing],
      closed: Promise[Nothing, Exit[Nothing, Unit]]
  ) extends McpTransport:
    private def ensureOpen: IO[AgentError, Unit] =
      terminal.poll.flatMap:
        case Some(cause) => cause.flatMap(ZIO.refailCause(_))
        case None        => ZIO.unit

    /** Publish interruption before aborting: an unblocked pipe must not turn it into clean EOF. */
    private def cancelIo: UIO[Unit] =
      ZIO.descriptorWith: descriptor =>
        val cause = descriptor.interrupters.foldLeft[Cause[AgentError]](Cause.empty)((acc, id) =>
          acc && Cause.interrupt(id)
        )
        terminal.succeed(if cause.isEmpty then Cause.interrupt(descriptor.id) else cause).unit *>
          cleanup

    private def failWrite[A](cause: Cause[AgentError]): IO[AgentError, A] =
      for
        _       <- terminal.succeed(cause)
        release <- cleanup.exit
        first   <- terminal.await
        result <- ZIO.refailCause(release match
          case Exit.Success(_)     => first
          case Exit.Failure(extra) => if first.contains(extra) then first else first ++ extra)
      yield result

    def send(message: McpMessage): ZIO[Scope, AgentError, Unit] =
      send(message, ZIO.unit)

    override def send(message: McpMessage, onIssued: UIO[Unit]): ZIO[Scope, AgentError, Unit] =
      McpStdioFraming
        .encode(message, maxFrameBytes)
        .flatMap: frame =>
          writes.withPermit:
            ZIO.uninterruptibleMask: restore =>
              ensureOpen *> restore(
                ZIO
                  .attemptBlockingCancelable {
                    val output = process.getOutputStream
                    output.write(frame.toArray)
                    output.flush()
                  }(cancelIo)
                  .mapError(ioError("write", _))
              ).exit.flatMap:
                case Exit.Success(_)     => ensureOpen *> onIssued
                case Exit.Failure(cause) => failWrite(cause)

    private def claimReader: IO[AgentError, Unit] =
      ensureOpen *> reader
        .modify {
          case Reader.Available => (true, Reader.Claimed)
          case Reader.Claimed   => (false, Reader.Claimed)
        }
        .flatMap:
          case true  => ZIO.unit
          case false => ZIO.fail(AgentError.McpError("MCP stdio incoming permits one consumer"))

    private def read: IO[AgentError, Chunk[Byte]] =
      ensureOpen *> ZIO
        .attemptBlockingCancelable {
          val input = process.getInputStream
          Chunk.fromArray(input.readNBytes(math.max(1, math.min(input.available(), 8192))))
        }(cancelIo)
        .mapError(ioError("read", _))
        .exit
        .flatMap:
          case Exit.Success(bytes) => ensureOpen.as(bytes)
          case Exit.Failure(cause) =>
            terminal.succeed(cause) *> terminal.await.flatMap(ZIO.refailCause(_))

    private def releaseReader(exit: Exit[Any, Any]): UIO[Unit] =
      val cause: Cause[AgentError] = exit match
        case Exit.Success(_) => Cause.fail(AgentError.McpError("MCP stdio incoming ended"))
        case Exit.Failure(failure) =>
          failure.map:
            case error: AgentError => error
            case other             => AgentError.McpError(s"MCP stdio consumer failed: $other")
      terminal.succeed(cause).unit *> cleanup

    private def readChannel: ZChannel[Any, Any, Any, Any, AgentError, Chunk[Byte], Unit] =
      ZChannel
        .fromZIO(read)
        .flatMap: bytes =>
          if bytes.isEmpty then ZChannel.unit
          else ZChannel.write(bytes) *> readChannel

    def incoming: ZStream[Any, AgentError, McpMessage] =
      ZStream.fromChannel(
        ZChannel.acquireReleaseExitWith(claimReader)((_, exit) => releaseReader(exit))(_ =>
          ZStream.fromChannel(readChannel).via(McpStdioFraming.decode(maxFrameBytes)).toChannel
        )
      )

    /** No caller joins here: an I/O caller may itself be running this cancellation finalizer. */
    private def releaseProcess: UIO[Unit] =
      for
        terminated <- ZIO
          .attemptBlocking {
            val _ = process.destroyForcibly()
            val _ = process.waitFor()
          }
          .mapError(ioError("terminate", _))
          .orDie
          .exit
        streams <- ZIO.foreach(
          Chunk(
            ZIO.attemptBlocking(process.getOutputStream.close()),
            ZIO.attemptBlocking(process.getInputStream.close()),
            ZIO.attemptBlocking(process.getErrorStream.close())
          )
        )(_.mapError(ioError("close stream", _)).orDie.exit)
        _ <- Exit.collectAllParDiscard(terminated +: streams)
      yield ()

    private def cleanup: UIO[Unit] =
      ZIO.uninterruptible:
        closing
          .getAndSet(Closing.Started)
          .flatMap:
            case Closing.Open =>
              releaseProcess.exit.flatMap(closed.succeed).unit *> closed.await.flatMap(exit => exit)
            case Closing.Started => closed.await.flatMap(exit => exit)

    def close: UIO[Unit] =
      ZIO.uninterruptible(
        terminal
          .succeed(Cause.fail(AgentError.McpError("MCP stdio transport closed")))
          .unit *> cleanup
      )

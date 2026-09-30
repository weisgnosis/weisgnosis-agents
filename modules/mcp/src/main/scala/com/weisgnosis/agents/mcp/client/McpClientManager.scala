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
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import com.weisgnosis.agents.mcp.protocol.McpToolDefinition
import com.weisgnosis.agents.mcp.tool.McpTool
import zio.*

enum McpClientRemoval:
  case Removed, Missing

/**
 * Scoped ownership and atomic publication of explicitly selected MCP tools. Acquisition must own
 * its transport resources in the supplied Scope. Returning a client grants close authority without
 * extending an existing external lifetime. Old Toolkit snapshots keep their definitions,
 * but removal closes their client; registering the same name never retargets an old binding.
 * Acquisitions, selectors and owned finalizers must not remove themselves or close this manager.
 */
trait McpClientManager:
  def register(
      name: String,
      acquire: ZIO[Scope, AgentError, McpClient],
      maxPages: Int,
      select: McpToolDefinition => Either[AgentError, Option[ToolDef]]
  ): IO[AgentError, Unit]

  /** Immutable snapshot containing only completely published registrations. */
  def toolkit: UIO[Toolkit]
  def names: UIO[Chunk[String]]
  def remove(name: String): UIO[McpClientRemoval]
  def close: UIO[Unit]

object McpClientManager:
  private enum Closing:
    case Open, Started

  private enum Registration:
    case Pending(entry: Entry)
    case Ready(entry: Entry, tools: Toolkit)
    case Removing(entry: Entry, tools: Toolkit)

    def owner: Entry = this match
      case Pending(entry)     => entry
      case Ready(entry, _)    => entry
      case Removing(entry, _) => entry

    def retained: Toolkit = this match
      case Pending(_)         => Toolkit.empty
      case Ready(_, tools)    => tools
      case Removing(_, tools) => tools

  private enum State:
    case Open(values: Map[String, Registration])
    case Closed(values: Map[String, Registration])

    def entries: Map[String, Registration] = this match
      case Open(values)   => values
      case Closed(values) => values

    def update(values: Map[String, Registration]): State = this match
      case Open(_)   => Open(values)
      case Closed(_) => Closed(values)

  /** All library-controlled closers await and replay the same complete cleanup outcome. */
  final private class Closure[A](
      phase: Ref[Closing],
      completed: Promise[Nothing, Exit[Nothing, A]]
  ):
    def run(cleanup: UIO[A]): UIO[A] =
      ZIO.uninterruptible:
        phase
          .getAndSet(Closing.Started)
          .flatMap:
            case Closing.Open =>
              cleanup.exit.flatMap(completed.succeed).unit *> completed.await.flatMap(exit => exit)
            case Closing.Started => completed.await.flatMap(exit => exit)

  private def closure[A]: UIO[Closure[A]] =
    for
      phase     <- Ref.make[Closing](Closing.Open)
      completed <- Promise.make[Nothing, Exit[Nothing, A]]
    yield new Closure(phase, completed)

  /** Keep initialization provenance separate from independently collected cleanup outcomes. */
  final private case class Finalization(
      initialized: Exit[AgentError, Toolkit],
      cleanup: Cause[Nothing]
  ):
    def replay: UIO[Unit] =
      val defects = initialized.causeOption.flatMap(_.keepDefects).getOrElse(Cause.empty)
      val cause   = combine(defects, cleanup)
      if cause.isEmpty then ZIO.unit else ZIO.refailCause(cause)

  final private class Entry(
      val start: Promise[Nothing, Unit],
      val initializer: Fiber.Runtime[AgentError, Toolkit],
      client: Ref[Option[McpClient]],
      workers: Scope.Closeable,
      resources: Scope.Closeable,
      completion: Closure[Finalization]
  ):
    def close(exit: Exit[Any, Any]): UIO[Unit] = finish(exit).flatMap(_.replay)

    def finish(exit: Exit[Any, Any]): UIO[Finalization] = completion.run:
      for
        initialized  <- initializer.interrupt
        workerExit   <- workers.close(exit).exit
        clientExit   <- client.get.flatMap(value => ZIO.foreachDiscard(value)(_.close)).exit
        resourceExit <- resources.close(exit).exit
        cause = Chunk(workerExit, clientExit, resourceExit).foldLeft[Cause[Nothing]](Cause.empty)(
          (current, outcome) =>
            outcome.causeOption.fold(current)(additional => combine(current, additional))
        )
      yield Finalization(initialized, cause)

  private def combine[E](first: Cause[E], second: Cause[E]): Cause[E] =
    if first.isEmpty then second
    else if second.isEmpty || first.contains(second) then first
    else if second.contains(first) then second
    else first ++ second

  def make(
      maxClients: Int = 16,
      maxTools: Int = 256
  ): ZIO[Scope, AgentError.InvalidConfiguration, McpClientManager] =
    if maxClients <= 0 || maxTools <= 0 then
      ZIO.fail(AgentError.InvalidConfiguration("MCP manager capacities must be positive"))
    else
      ZIO.uninterruptible:
        for
          gate       <- Semaphore.make(1)
          state      <- Ref.make[State](State.Open(Map.empty))
          completion <- closure[Unit]
          live = new Live(maxClients, maxTools, gate, state, completion)
          _ <- ZIO.addFinalizerExit(live.closeWith(_))
        yield live

  final private class Live(
      maxClients: Int,
      maxTools: Int,
      gate: Semaphore,
      state: Ref[State],
      completion: Closure[Unit]
  ) extends McpClientManager:
    def toolkit: UIO[Toolkit] = state.get.map:
      case State.Closed(_) => Toolkit.empty
      case State.Open(entries) =>
        Toolkit(
          entries.values
            .collect { case Registration.Ready(_, tools) => tools.tools }
            .foldLeft(Map.empty[String, Tool[Any, ?, ?]])(_ ++ _)
        )

    def names: UIO[Chunk[String]] = state.get.map:
      case State.Closed(_) => Chunk.empty
      case State.Open(entries) =>
        Chunk.fromIterable(
          entries.collect { case (name, Registration.Ready(_, _)) => name }.toList.sorted
        )

    def register(
        name: String,
        acquire: ZIO[Scope, AgentError, McpClient],
        maxPages: Int,
        select: McpToolDefinition => Either[AgentError, Option[ToolDef]]
    ): IO[AgentError, Unit] =
      if name.trim.isEmpty || maxPages <= 0 then
        ZIO.fail(
          AgentError.InvalidConfiguration("MCP client name and positive page limit are required")
        )
      else
        ZIO.uninterruptibleMask: restore =>
          for
            entry    <- reserve(name, acquire, maxPages, select)
            _        <- entry.start.succeed(())
            observed <- restore(entry.initializer.await).exit
            result <- observed match
              case Exit.Success(Exit.Success(tools)) => publish(name, entry, tools).exit
              case Exit.Success(Exit.Failure(cause)) => ZIO.succeed(Exit.failCause(cause))
              case Exit.Failure(cause)               => ZIO.succeed(Exit.failCause(cause))
            _ <- result match
              case Exit.Success(_) => ZIO.unit
              case failed @ Exit.Failure(cause) =>
                retire(name, entry, failed).flatMap: finalized =>
                  val failure = observed match
                    case Exit.Failure(_) =>
                      finalized.initialized match
                        case Exit.Failure(initialization) if !initialization.isInterruptedOnly =>
                          combine(cause, initialization)
                        case _ => cause
                    case Exit.Success(_) => cause
                  ZIO.refailCause(combine(failure, finalized.cleanup))
          yield ()

    /** Only bounded state publication and fiber allocation occur under the admission gate. */
    private def reserve(
        name: String,
        acquire: ZIO[Scope, AgentError, McpClient],
        maxPages: Int,
        select: McpToolDefinition => Either[AgentError, Option[ToolDef]]
    ): IO[AgentError, Entry] = gate.withPermit:
      state.get.flatMap:
        case State.Closed(_) => ZIO.fail(AgentError.McpError("MCP manager closed"))
        case State.Open(entries) if entries.contains(name) =>
          ZIO.fail(
            AgentError.McpError(s"MCP client '$name' is already registered or being removed")
          )
        case State.Open(entries) if entries.size >= maxClients =>
          ZIO.fail(AgentError.McpError("MCP manager client capacity reached"))
        case State.Open(entries) =>
          for
            resources  <- Scope.make
            workers    <- Scope.make
            completion <- closure[Finalization]
            start      <- Promise.make[Nothing, Unit]
            acquired   <- Ref.make(Option.empty[McpClient])
            initializer <- (start.await *> resources.extend(
              ZIO
                .uninterruptibleMask(restore =>
                  restore(acquire).flatMap(client => acquired.set(Some(client)).as(client))
                )
                .flatMap(client =>
                  client
                    .discoverTools(maxPages, maxTools)
                    .flatMap(definitions =>
                      ZIO.foldLeft(definitions)(Toolkit.empty)((tools, remote) =>
                        ZIO
                          .suspendSucceed(ZIO.fromEither(select(remote)))
                          .flatMap:
                            case None => ZIO.succeed(tools)
                            case Some(definition) =>
                              ZIO.fromEither(
                                tools.register(McpTool.bind(client, remote.name, definition))
                              )
                      )
                    )
                )
            )).interruptible.forkIn(workers)
            entry = new Entry(start, initializer, acquired, workers, resources, completion)
            _ <- state.set(State.Open(entries.updated(name, Registration.Pending(entry))))
          yield entry

    private def publish(name: String, entry: Entry, tools: Toolkit): IO[AgentError, Unit] =
      gate.withPermit:
        state.get.flatMap:
          case State.Open(entries) if entries.get(name).contains(Registration.Pending(entry)) =>
            val retained  = entries.values.map(_.retained)
            val used      = retained.map(_.size).sum
            val collision = retained.flatMap(_.tools.keys).exists(tools.contains)
            if collision then ZIO.fail(AgentError.McpError("MCP local tool name collision"))
            else if tools.size > maxTools - used then
              ZIO.fail(AgentError.McpError("MCP manager tool capacity reached"))
            else state.set(State.Open(entries.updated(name, Registration.Ready(entry, tools))))
          case _ => ZIO.fail(AgentError.McpError("MCP registration was closed before publication"))

    private def markRemoving(name: String, entry: Entry): UIO[Unit] =
      state.update(current =>
        current.entries.get(name) match
          case Some(record) if record.owner eq entry =>
            current.update(
              current.entries.updated(name, Registration.Removing(entry, record.retained))
            )
          case _ => current
      )

    private def retire(name: String, entry: Entry, exit: Exit[Any, Any]): UIO[Finalization] =
      gate.withPermit(markRemoving(name, entry)) *> entry
        .finish(exit)
        .ensuring(
          gate.withPermit(
            state.update(current =>
              if current.entries.get(name).exists(record => record.owner eq entry) then
                current.update(current.entries.removed(name))
              else current
            )
          )
        )

    def remove(name: String): UIO[McpClientRemoval] = ZIO.uninterruptible:
      gate
        .withPermit(
          state.get.flatMap(current =>
            current.entries.get(name) match
              case None         => ZIO.none
              case Some(record) => markRemoving(name, record.owner).as(Some(record.owner))
          )
        )
        .flatMap:
          case None => ZIO.succeed(McpClientRemoval.Missing)
          case Some(entry) =>
            retire(name, entry, Exit.unit).flatMap(_.replay).as(McpClientRemoval.Removed)

    def close: UIO[Unit] = closeWith(Exit.unit)

    def closeWith(exit: Exit[Any, Any]): UIO[Unit] = completion.run:
      gate
        .withPermit(state.modify(current => (current.entries, State.Closed(current.entries))))
        .flatMap(entries =>
          ZIO
            .foreachPar(entries.values)(_.owner.close(exit).exit)
            .flatMap(Exit.collectAllParDiscard)
        )
        .ensuring(state.set(State.Closed(Map.empty)))

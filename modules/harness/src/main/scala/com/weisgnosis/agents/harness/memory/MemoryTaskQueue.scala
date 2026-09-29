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
package com.weisgnosis.agents.harness.memory

import com.weisgnosis.agents.core.error.AgentError
import zio.*
import zio.stm.*

/**
 * One host-owned queue serializes one memory owner's tasks; only pending conversation work coalesces.
 * Tasks must not await their own ticket, another queued task, or this queue's idleness.
 * Closing cancels pending work and waits for running cleanup; drain with awaitIdle before closing.
 */
trait MemoryTaskQueue:
  def submit(conversation: MemoryConversationToken)(
      task: => ZIO[Scope, AgentError, Unit]
  ): UIO[MemoryTaskSubmission]

  /** Wait for current work to finish; this does not close admission or prevent later submissions. */
  def awaitIdle: UIO[Unit]

object MemoryTaskQueue:
  final private case class Work(
      conversation: MemoryConversationToken,
      task: ZIO[Scope, AgentError, Unit],
      completion: TPromise[Nothing, MemoryTaskOutcome]
  )

  private enum State:
    case Open(running: Option[Work], pending: Chunk[Work])
    case Closing(running: Option[Work])
    case Closed

  def open(maxPending: Int = 256): ZIO[Scope, AgentError, MemoryTaskQueue] =
    ZIO.uninterruptible:
      for
        _ <- ZIO
          .fail(AgentError.InvalidConfiguration("Memory task queue capacity must be positive"))
          .when(maxPending <= 0)
        state <- TRef.make[State](State.Open(None, Chunk.empty)).commit
        queue = new Live(state, maxPending)
        // Acquisition masking must not become the persistent worker's cancellation policy.
        worker <- queue.run.interruptible.forkScoped
        _      <- ZIO.addFinalizer(queue.beginClose *> worker.interrupt.unit)
      yield queue

  final private class Live(state: TRef[State], maxPending: Int) extends MemoryTaskQueue:
    def submit(conversation: MemoryConversationToken)(
        task: => ZIO[Scope, AgentError, Unit]
    ): UIO[MemoryTaskSubmission] =
      (for
        current <- state.get
        result <- current match
          case State.Closing(_) | State.Closed =>
            STM.succeed(MemoryTaskSubmission.Rejected(MemoryTaskRejection.Closed))
          case State.Open(running, pending) =>
            val previous = pending.find(_.conversation == conversation)
            if previous.isEmpty && pending.size >= maxPending then
              STM.succeed(MemoryTaskSubmission.Rejected(MemoryTaskRejection.Capacity))
            else
              for
                completion <- TPromise.make[Nothing, MemoryTaskOutcome]
                work = Work(conversation, ZIO.suspendSucceed(task), completion)
                updated = previous match
                  case Some(_) =>
                    pending.map(value => if value.conversation == conversation then work else value)
                  case None => pending :+ work
                _ <- STM.foreach(previous)(_.completion.succeed(MemoryTaskOutcome.Superseded))
                _ <- state.set(State.Open(running, updated))
              yield MemoryTaskSubmission.Accepted(new MemoryTaskTicket(completion))
      yield result).commit

    def awaitIdle: UIO[Unit] = state.get.flatMap {
      case State.Open(None, pending) if pending.isEmpty => STM.unit
      case State.Closing(None) | State.Closed           => STM.unit
      case _                                            => STM.retry
    }.commit

    private def take: UIO[Option[Work]] = state.get.flatMap {
      case State.Open(None, pending) =>
        pending.headOption match
          case Some(work) => state.set(State.Open(Some(work), pending.drop(1))).as(Some(work))
          case None       => STM.retry
      case State.Open(Some(_), _)          => STM.retry
      case State.Closing(_) | State.Closed => STM.succeed(None)
    }.commit

    def run: UIO[Unit] = loop.onExit(workerClosed)

    private def loop: UIO[Unit] =
      take.flatMap:
        case None       => ZIO.unit
        case Some(work) => execute(work) *> loop

    /** Settle ownership even if cancellation arrives after a claim but before child registration. */
    private def workerClosed(exit: Exit[Nothing, Unit]): UIO[Unit] = (for
      current <- state.get
      unfinished = exit match
        case Exit.Failure(cause) => MemoryTaskOutcome.Completed(Exit.failCause(cause))
        case Exit.Success(_)     => MemoryTaskOutcome.CancelledBeforeStart
      _ <- current match
        case State.Open(running, pending) =>
          STM.foreach(pending)(_.completion.succeed(MemoryTaskOutcome.CancelledBeforeStart)) *>
            STM.foreach(running)(_.completion.succeed(unfinished))
        case State.Closing(running) => STM.foreach(running)(_.completion.succeed(unfinished))
        case State.Closed           => STM.unit
      _ <- state.set(State.Closed)
    yield ()).commit

    private def execute(work: Work): UIO[Unit] =
      ZIO.scoped:
        ZIO.uninterruptibleMask: restore =>
          for
            child   <- restore(ZIO.scoped(work.task)).forkScoped
            awaited <- restore(child.await).exit
            outcome <- awaited match
              case Exit.Success(exit) => ZIO.succeed(exit)
              case Exit.Failure(_)    => child.interrupt
            _ <- (work.completion.succeed(MemoryTaskOutcome.Completed(outcome)) *> state.update {
              case State.Open(_, pending) => State.Open(None, pending)
              case State.Closing(_)       => State.Closing(None)
              case State.Closed           => State.Closed
            }).commit
            _ <- awaited match
              case Exit.Success(_)     => ZIO.unit
              case Exit.Failure(cause) => ZIO.refailCause(cause)
          yield ()

    def beginClose: UIO[Unit] = (for
      current <- state.get
      _ <- current match
        case State.Open(running, pending) =>
          STM.foreach(pending)(_.completion.succeed(MemoryTaskOutcome.CancelledBeforeStart)) *>
            state.set(State.Closing(running))
        case State.Closing(_) | State.Closed => STM.unit
    yield ()).commit

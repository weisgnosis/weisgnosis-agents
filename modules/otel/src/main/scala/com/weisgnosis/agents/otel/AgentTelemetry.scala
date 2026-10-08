/*
 * Copyright 2024-2026 WeisGnosis (https://weisgnosis.com)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.weisgnosis.agents.otel

import com.weisgnosis.agents.core.agent.*
import com.weisgnosis.agents.core.event.InvocationContext
import com.weisgnosis.agents.core.model.ToolResultState
import com.weisgnosis.agents.core.tool.{
  ToolDispatchCompletion,
  ToolDispatchContext,
  ToolDispatchObserver
}
import io.opentelemetry.api.trace.{Span, StatusCode, Tracer}
import zio.*
import zio.telemetry.opentelemetry.context.ContextStorage

/** Bounded operational diagnostics: no exception messages, payloads or per-request records. */
final case class TelemetryDiagnostics(operationFailures: Long)

/**
 * Optional observation using borrowed tracing capabilities. Only spans created by this adapter
 * are ended. SDK processors, exporters, propagation and shutdown remain host responsibilities.
 */
final class AgentTelemetry private (
    tracer: Tracer,
    contexts: ContextStorage,
    invocationSpan: FiberRef[Option[(InvocationContext, Span)]],
    failures: Ref[Long]
):
  def diagnostics: UIO[TelemetryDiagnostics] = failures.get.map(TelemetryDiagnostics(_))

  private def isolate[R, A](effect: => ZIO[R, Throwable, A]): ZIO[R, Nothing, Option[A]] =
    ZIO
      .suspendSucceed(effect)
      .map[Option[A]](Some(_))
      .catchAllCause(cause =>
        if cause.isInterrupted then ZIO.refailCause(cause.flatMap(error => Cause.die(error)))
        else failures.update(count => if count == Long.MaxValue then count else count + 1).as(None)
      )

  private def annotate(span: Span, key: String, value: String): UIO[Unit] =
    isolate(ZIO.attempt(span.setAttribute(key, value))).unit

  private def open(name: String): ZIO[Scope, Nothing, Option[Span]] =
    isolate(contexts.get).flatMap:
      case None => ZIO.none
      case Some(parent) =>
        for
          span <- ZIO.acquireRelease(
            isolate(ZIO.attempt(tracer.spanBuilder(name).setParent(parent).startSpan()))
          )(span => ZIO.foreachDiscard(span)(value => isolate(ZIO.attempt(value.end())).unit))
          _ <- ZIO.foreachDiscard(span)(value =>
            isolate(contexts.locallyScoped(parent.`with`(value))).unit
          )
        yield span

  private def outcome[E](span: Span, exit: Exit[E, ?]): UIO[Unit] =
    val label = exit match
      case Exit.Success(_)                                => "success"
      case Exit.Failure(cause) if cause.isInterruptedOnly => "interrupted"
      case Exit.Failure(_)                                => "failure"
    annotate(span, "weisgnosis.outcome", label) *>
      (exit match
        case Exit.Failure(cause) if cause.isInterrupted =>
          annotate(span, "weisgnosis.interruption", "present")
        case _ => ZIO.unit) *>
      (if label == "failure" then isolate(ZIO.attempt(span.setStatus(StatusCode.ERROR))).unit
       else ZIO.unit)

  val phaseObserver: PhaseObserver = PhaseObserver((phase, _) =>
    val name = phase match
      case PhaseContext.Invocation(_)   => "agent.invocation"
      case PhaseContext.Reasoning(_, _) => "agent.reasoning"
      case PhaseContext.ModelCall(_, _) => "agent.model"
    for
      span <- open(name)
      _ <- ZIO.foreachDiscard(span)(value =>
        phase match
          case PhaseContext.Invocation(invocation) =>
            invocationSpan.locallyScoped(Some(invocation -> value))
          case PhaseContext.Reasoning(_, iteration) =>
            isolate(ZIO.attempt(value.setAttribute("weisgnosis.iteration", iteration.toLong))).unit
          case PhaseContext.ModelCall(_, _) => ZIO.unit
      )
    yield PhaseCompletion(exit =>
      ZIO.foreachDiscard(span)(value =>
        outcome(value, exit) *> ((phase, exit) match
          case (PhaseContext.Invocation(_), Exit.Failure(cause)) =>
            annotate(
              value,
              "weisgnosis.run.state",
              if cause.isInterruptedOnly then "cancelled" else "failed"
            )
          case _ => ZIO.unit)
      )
    )
  )

  val completionObserver: TurnCompletionObserver = TurnCompletionObserver(completion =>
    invocationSpan.get.flatMap:
      case Some((invocation, span)) if completion.invocation.contains(invocation) =>
        completion.outcome match
          case Exit.Success(result) =>
            val state = result.state match
              case AgentRunState.Completed    => "completed"
              case AgentRunState.Interrupted  => "interrupted"
              case AgentRunState.Suspended(_) => "suspended"
              case AgentRunState.Stopped(_)   => "stopped"
            annotate(span, "weisgnosis.run.state", state)
          case Exit.Failure(cause) =>
            annotate(
              span,
              "weisgnosis.run.state",
              if cause.isInterruptedOnly then "cancelled" else "failed"
            )
      case _ => ZIO.unit
  )

  val dispatchObserver: ToolDispatchObserver = ToolDispatchObserver((_, invocation) =>
    for
      span <- open("agent.tool")
      _ <- ZIO.foreachDiscard(span)(value =>
        invocation match
          case ToolDispatchContext.Active(_, _, ordinal) =>
            isolate(ZIO.attempt(value.setAttribute("weisgnosis.tool.ordinal", ordinal.toLong))).unit
          case ToolDispatchContext.Detached => ZIO.unit
      )
    yield ToolDispatchCompletion(exit =>
      ZIO.foreachDiscard(span)(value =>
        outcome(value, exit) *> (exit match
          case Exit.Success(state) =>
            val label = state match
              case ToolResultState.Success     => "success"
              case ToolResultState.Error       => "error"
              case ToolResultState.Denied      => "denied"
              case ToolResultState.Running     => "running"
              case ToolResultState.Interrupted => "interrupted"
            annotate(value, "weisgnosis.tool.state", label) *>
              (if state == ToolResultState.Error then
                 isolate(ZIO.attempt(value.setStatus(StatusCode.ERROR))).unit
               else ZIO.unit)
          case Exit.Failure(_) => ZIO.unit)
      )
    )
  )

object AgentTelemetry:
  /** The host must supply fiber-local OpenTelemetry.contextZIO storage, not native thread storage. */
  def make(tracer: Tracer, contexts: ContextStorage): ZIO[Scope, Nothing, AgentTelemetry] =
    for
      invocationSpan <- FiberRef.make(Option.empty[(InvocationContext, Span)])
      failures       <- Ref.make(0L)
    yield new AgentTelemetry(tracer, contexts, invocationSpan, failures)

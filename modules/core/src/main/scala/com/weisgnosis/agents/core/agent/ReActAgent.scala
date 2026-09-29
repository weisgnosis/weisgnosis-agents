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
package com.weisgnosis.agents.core.agent

import com.weisgnosis.agents.core.client.{
  LlmClient,
  LlmConfig,
  NativeToolSupport,
  StructuredOutputCapability,
  TurnOutput
}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{AgentEvent, ModelStreamContext}
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.*
import com.weisgnosis.agents.core.json.JsonBoundary
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.{Take, ZStream}

/**
 * Autonomous reasoning and acting agent executing pure functional ReAct loops.
 *
 * Enforces:
 * - INV-2: Dangling Tool Reconciliation on fiber cancellation or interruption
 * - INV-3: Thinking-Only Retry Loopback with synthetic `<system-reminder>`
 * - Provider server tool pass-through without local execution
 * - Multi-tool concurrency-safe parallel execution via `ZIO.foreachPar`
 * - Type-safe structured output extraction via synthetic `generate_response` tool
 */
final class ReActAgent private (
    val config: AgentConfig,
    val toolkit: Toolkit,
    val client: LlmClient,
    val llmConfig: LlmConfig,
    private val stateRef: Ref[Chunk[Msg]],
    private val interruptedPromiseRef: Ref[Promise[Nothing, Unit]],
    val agentId: String,
    private val runLock: Semaphore,
    val postReasoningHook: PostReasoningHook,
    val toolResultProcessor: ToolResultProcessor,
    val toolAuthorizer: ToolAuthorizer,
    val historyPreparer: HistoryPreparer,
    val modelInputTransform: ModelInputTransform,
    val completionObserver: TurnCompletionObserver
):

  def name: String        = config.name
  def description: String = config.description
  def maxIterations: Int  = config.maxIterations
  def getMaxIters: Int    = config.maxIterations
  def getAgentId: String  = agentId

  def history: UIO[Chunk[Msg]] = stateRef.get
  def resetHistory: UIO[Unit]  = runLock.withPermit(stateRef.set(Chunk.empty))

  /** Signals an interruption to the active ReAct loop. */
  def interrupt: UIO[Unit] =
    for
      p <- interruptedPromiseRef.get
      _ <- p.succeed(()).unit
    yield ()

  /** Executes a single agent turn given new input messages, returning the final reply Msg. */
  def call(messages: Chunk[Msg]): ZIO[Scope, AgentError, Msg] =
    run(messages).map(_.reply)

  /** Executes a single agent turn given a single user message. */
  def call(message: Msg): ZIO[Scope, AgentError, Msg] =
    call(Chunk(message))

  /** Executes a structured output turn, returning the final reply Msg containing structured metadata. */
  def callStructured[A: JsonCodec](
      messages: Chunk[Msg],
      schema: Json,
      mode: StructuredMode = StructuredMode.Auto
  ): ZIO[Scope, AgentError, Msg] =
    runStructured[A](messages, schema, mode).map(_._1.reply)

  def callStructured[A: JsonCodec](
      message: Msg,
      schema: Json
  ): ZIO[Scope, AgentError, Msg] =
    callStructured[A](Chunk(message), schema, StructuredMode.Auto)

  /** Full run returning complete AgentResult. */
  def run(messages: Chunk[Msg]): ZIO[Scope, AgentError, AgentResult] =
    runLock.withPermit(
      observeInvocation(complete =>
        ZIO.scoped(for
          hub    <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
          result <- runOrdinaryWithHub(messages, hub, complete)
        yield result)
      )
    )

  def run(message: Msg): ZIO[Scope, AgentError, AgentResult] =
    run(Chunk(message))

  /** Full structured run returning AgentResult and decoded structured object. */
  def runStructured[A: JsonCodec](
      messages: Chunk[Msg],
      schema: Json,
      mode: StructuredMode = StructuredMode.Auto
  ): ZIO[Scope, AgentError, (AgentResult, A)] =
    runLock.withPermit(
      observeInvocation(complete =>
        ZIO.scoped(for
          hub    <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
          result <- runStructuredWithHub[A](messages, schema, mode, hub, complete)
        yield result)
      )
    )

  private def runStructuredWithHub[A: JsonCodec](
      messages: Chunk[Msg],
      schema: Json,
      mode: StructuredMode,
      hub: Hub[AgentEvent],
      complete: AgentResult => IO[AgentError, Unit]
  ): ZIO[Scope, AgentError, (AgentResult, A)] =
    Ref.make[Option[Json]](None).zip(Ref.make[Option[ChatUsage]](None)).flatMap {
      case (capturedRef, usageRef) =>
        val syntheticTool = StructuredOutput.createTool(schema, json => capturedRef.set(Some(json)))
        val nativeConfig = llmConfig.copy(responseFormat =
          Some(
            Json.Obj(
              "type"        -> Json.Str("json_schema"),
              "json_schema" -> Json.Obj("name" -> Json.Str("response"), "schema" -> schema)
            )
          )
        )

        def prepare(result: AgentResult): IO[AgentError, (AgentResult, A)] =
          capturedRef.get
            .flatMap(captured => ZIO.fromEither(prepareStructured[A](result, captured)))

        def nativeAttempt: ZIO[Scope, AgentError, (AgentResult, A)] =
          runWithHub(messages, hub, toolkit, usageRef, complete, nativeConfig, Some(capturedRef))(
            prepare
          )

        val syntheticConfig = llmConfig.copy(responseFormat = None)

        def syntheticAttempt(input: Chunk[Msg]): ZIO[Scope, AgentError, (AgentResult, A)] =
          ZIO
            .fromEither(toolkit.register(syntheticTool))
            .flatMap(active =>
              runWithHub(
                input,
                hub,
                active,
                usageRef,
                complete,
                syntheticConfig,
                Some(capturedRef)
              )(prepare)
            )

        def nativeWithFallback: ZIO[Scope, AgentError, (AgentResult, A)] =
          nativeAttempt.catchAll {
            case AgentError.ModelError(status, _, _) if Set(400, 422, 501).contains(status) =>
              syntheticAttempt(Chunk.empty)
            case error => ZIO.fail(error)
          }

        def automaticAttempt: ZIO[Scope, AgentError, (AgentResult, A)] =
          llmConfig.structuredOutputCapability match
            case StructuredOutputCapability.Unsupported => syntheticAttempt(messages)
            case StructuredOutputCapability.Native(NativeToolSupport.Supported) =>
              nativeWithFallback
            case StructuredOutputCapability.Unknown | StructuredOutputCapability.Native(
                  NativeToolSupport.Unsupported
                ) =>
              if toolkit.isEmpty then nativeWithFallback else syntheticAttempt(messages)

        mode match
          case StructuredMode.Native        => nativeAttempt
          case StructuredMode.SyntheticTool => syntheticAttempt(messages)
          case StructuredMode.Auto          => automaticAttempt
    }

  /** Prepare one validated payload and final history without publishing or mutating runtime state. */
  private def prepareStructured[A: JsonCodec](
      rawResult: AgentResult,
      captured: Option[Json]
  ): Either[AgentError, (AgentResult, A)] =
    if rawResult.state != AgentRunState.Completed then
      Left(AgentError.Interrupted("Structured output run ended before completion"))
    else
      val payload = captured match
        case Some(json) => StructuredOutput.validatedPayload(json)
        case None       => StructuredOutput.structuredPayload(rawResult.reply)
      for
        json <- payload.left.map(error =>
          AgentError.DeserializationError(s"Invalid structured output payload: $error")
        )
        decoded <- json
          .as[A]
          .left
          .map(error =>
            AgentError.DeserializationError(s"Failed to decode structured output payload: $error")
          )
        completed = captured match
          case Some(_) =>
            val reply = Msg.assistant(
              name = Some(config.name),
              content = rawResult.reply.content.collect { case thinking: ContentBlock.Thinking =>
                thinking
              } :+ ContentBlock.Text(json.toJson),
              metadata =
                rawResult.reply.metadata.updated(StructuredOutput.MetadataStructuredOutput, json),
              usage = rawResult.reply.usage
            )
            rawResult.copy(
              reply = reply,
              history = StructuredOutput.completeHistory(rawResult.history, reply)
            )
          case None =>
            val reply =
              rawResult.reply.withMetadata(StructuredOutput.MetadataStructuredOutput, json)
            rawResult.copy(reply = reply, history = rawResult.history.dropRight(1) :+ reply)
      yield (completed, decoded)

  def runStructured[A: JsonCodec](
      message: Msg,
      schema: Json
  ): ZIO[Scope, AgentError, (AgentResult, A)] =
    runStructured[A](Chunk(message), schema, StructuredMode.Auto)

  /** Streams typed lifecycle and delta events while running the turn in the background. */
  def streamEvents(messages: Chunk[Msg]): ZStream[Scope, AgentError, AgentEvent] =
    streamRun((hub, complete) => runOrdinaryWithHub(messages, hub, complete))

  /** Streams an attempt through validated structured completion before its result/end events. */
  def streamStructuredEvents[A: JsonCodec](
      messages: Chunk[Msg],
      schema: Json,
      mode: StructuredMode = StructuredMode.Auto
  ): ZStream[Scope, AgentError, AgentEvent] =
    streamRun((hub, complete) => runStructuredWithHub[A](messages, schema, mode, hub, complete))

  def streamStructuredEvents[A: JsonCodec](
      message: Msg,
      schema: Json
  ): ZStream[Scope, AgentError, AgentEvent] =
    streamStructuredEvents[A](Chunk(message), schema, StructuredMode.Auto)

  private def streamRun[A](
      runEffect: (Hub[AgentEvent], AgentResult => IO[AgentError, Unit]) => ZIO[Scope, AgentError, A]
  ): ZStream[Scope, AgentError, AgentEvent] =
    ZStream.unwrapScoped {
      for
        hub      <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
        sub      <- hub.subscribe
        delivery <- ZIO.acquireRelease(Queue.unbounded[Take[AgentError, AgentEvent]])(_.shutdown)
        forwarder <- ZStream
          .fromQueue(sub)
          .runForeach(event => delivery.offer(Take.single(event)).unit)
          .forkScoped
        _ <- ZIO.uninterruptibleMask { restore =>
          for
            exit <- restore(
              runLock.withPermit(
                observeInvocation(complete => ZIO.scoped(runEffect(hub, complete)))
              )
            ).exit
            // Join the claimed prefix before appending the unclaimed suffix and terminal signal.
            tail <- sub.takeAll
            _    <- sub.shutdown
            _    <- forwarder.join
            _    <- delivery.offer(Take.chunk(tail))
            terminal = exit match
              case Exit.Success(_)     => Take.end
              case Exit.Failure(cause) => Take.failCause(cause)
            _ <- delivery.offer(terminal)
          yield ()
        }.forkScoped
      yield ZStream.fromQueue(delivery).flattenTake
    }

  def streamEvents(message: Msg): ZStream[Scope, AgentError, AgentEvent] =
    streamEvents(Chunk(message))

  private def runOrdinaryWithHub(
      messages: Chunk[Msg],
      hub: Hub[AgentEvent],
      complete: AgentResult => IO[AgentError, Unit]
  ): ZIO[Scope, AgentError, AgentResult] =
    Ref
      .make[Option[ChatUsage]](None)
      .flatMap(usage =>
        runWithHub(messages, hub, toolkit, usage, complete)(result => ZIO.succeed((result, ())))
          .map(_._1)
      )

  private def runWithHub[A](
      newMessages: Chunk[Msg],
      eventHub: Hub[AgentEvent],
      activeToolkit: Toolkit,
      usageRef: Ref[Option[ChatUsage]],
      complete: AgentResult => IO[AgentError, Unit],
      requestConfig: LlmConfig = llmConfig,
      structuredResponse: Option[Ref[Option[Json]]] = None
  )(
      prepareResult: AgentResult => IO[AgentError, (AgentResult, A)]
  ): ZIO[Scope, AgentError, (AgentResult, A)] =
    for
      p       <- Promise.make[Nothing, Unit]
      _       <- interruptedPromiseRef.set(p)
      replyId <- Random.nextUUID.map(_.toString)
      systemMsgOpt =
        if config.systemPrompt.nonEmpty then Some(Msg.system(config.systemPrompt)) else None
      _ <- stateRef.update { current =>
        val withSystem = systemMsgOpt match
          case Some(sysMsg)
              if current.isEmpty || current.headOption.forall(_.role != Role.System) =>
            sysMsg +: current
          case _ =>
            current
        withSystem ++ newMessages
      }
      progressRef <- Ref.make[RunProgress](RunProgress.Ready)
      result <- ZIO.acquireReleaseWith(
        eventHub.publish(AgentEvent.AgentStartEvent(agentId, replyId, agentId))
      )(_ => eventHub.publish(AgentEvent.AgentEndEvent(replyId)).unit) { _ =>
        ZIO.uninterruptibleMask { restore =>
          val prepare = loop(
            1,
            0,
            activeToolkit,
            eventHub,
            replyId,
            requestConfig,
            structuredResponse,
            progressRef,
            usageRef
          ).flatMap(result => usageRef.get.map(usage => result.copy(usage = usage)))
            .flatMap(prepareResult)
            .onInterrupt(reconcileDanglingToolCalls(stateRef, progressRef).unit)
          for
            completed <- restore(
              raceWithSignal(prepare, ZIO.sleep(config.timeout)).flatMap:
                case Left(_) =>
                  ZIO.fail(AgentError.TimeoutError(config.timeout.toSeconds, "Agent run timed out"))
                case Right(value) => ZIO.succeed(value)
            )
            _ <- stateRef.set(completed._1.history)
            _ <- complete(completed._1)
            _ <- eventHub.publish(AgentEvent.AgentResultEvent(completed._1.reply))
          yield completed
        }
      }
    yield result

  private enum ObservationState:
    case Pending, Started

  private def observeInvocation[A](
      runEffect: (AgentResult => IO[AgentError, Unit]) => ZIO[Scope, AgentError, A]
  ): ZIO[Scope, AgentError, A] =
    ZIO.uninterruptibleMask: restore =>
      for
        observation <- Ref.make(ObservationState.Pending)
        execution <- restore(
          ZIO.scoped(runEffect(result => observeOnce(observation, Exit.succeed(result))))
        ).exit
        value <- execution match
          case Exit.Success(value) => ZIO.succeed(value)
          case Exit.Failure(original) =>
            observeOnce(observation, Exit.failCause(original)).exit.flatMap:
              case Exit.Success(_)       => ZIO.refailCause(original)
              case Exit.Failure(failure) => ZIO.refailCause(original ++ failure)
      yield value

  private def observeOnce(
      state: Ref[ObservationState],
      outcome: Exit[AgentError, AgentResult]
  ): IO[AgentError.CompletionObservationError, Unit] =
    state
      .modify(current => current -> ObservationState.Started)
      .flatMap:
        case ObservationState.Started => ZIO.unit
        case ObservationState.Pending =>
          stateRef.get.flatMap(history =>
            ZIO.scoped(
              ZIO.suspendSucceed(
                completionObserver.observe(TurnCompletion(agentId, history, outcome))
              )
            )
          )

  /** Own the worker separately so cancellation cannot discard its resource-cleanup failures. */
  private def raceWithSignal[R, E, A](
      effect: ZIO[R, E, A],
      signal: UIO[Unit]
  ): ZIO[R, E, Either[Unit, A]] =
    ZIO.scoped(ZIO.uninterruptibleMask: restore =>
      for
        worker  <- restore(effect).forkScoped
        outcome <- restore(worker.await.raceEither(signal)).exit
        value <- outcome match
          case Exit.Success(Left(exit)) => worker.inheritAll *> exit.map(Right(_))
          case Exit.Success(Right(_)) =>
            worker.interrupt.flatMap:
              case Exit.Failure(cause) if !cause.isInterruptedOnly => ZIO.refailCause(cause)
              case _                                               => ZIO.succeed(Left(()))
          case Exit.Failure(waitCause) =>
            worker.interrupt.flatMap:
              case Exit.Failure(cause) if !cause.isInterruptedOnly =>
                ZIO.refailCause(waitCause ++ cause)
              case _ => ZIO.refailCause(waitCause)
      yield value)

  private def loop(
      iteration: Int,
      emptyThinkingRetries: Int,
      activeToolkit: Toolkit,
      eventHub: Hub[AgentEvent],
      replyId: String,
      requestConfig: LlmConfig,
      structuredResponse: Option[Ref[Option[Json]]],
      progressRef: Ref[RunProgress],
      usageRef: Ref[Option[ChatUsage]]
  ): ZIO[Scope, AgentError, AgentResult] =
    if iteration > config.maxIterations then
      for
        _ <- eventHub.publish(
          AgentEvent.ExceedMaxItersEvent(replyId, config.maxIterations, iteration - 1)
        )
        maxIterMsg <- ZIO.succeed(
          Msg.assistant(
            name = Some(config.name),
            content =
              Chunk(ContentBlock.Text(s"Maximum iterations (${config.maxIterations}) reached.")),
            metadata = Map(
              Msg.MetadataGenerateReason -> Json.Str("max_iterations"),
              "_summary_failed"          -> Json.Bool(true)
            )
          )
        )
        _            <- persistAssistant(maxIterMsg, progressRef)
        finalHistory <- stateRef.get
      yield AgentResult(reply = maxIterMsg, history = finalHistory, iterationCount = iteration - 1)
    else
      ZIO
        .uninterruptibleMask { restore =>
          def raceWithInterrupt[R, E, A](effect: ZIO[R, E, A]): ZIO[R, E, Either[Unit, A]] =
            interruptedPromiseRef.get
              .flatMap(signal => restore(raceWithSignal(effect, signal.await)))

          def acceptTurn(
              turnOutput: TurnOutput,
              context: ModelStreamContext
          ): ZIO[Scope, AgentError, AgentResult] =
            turnOutput match
              case TurnOutput.FinalText(assistantMsg) =>
                for
                  _         <- persistAssistant(assistantMsg, progressRef)
                  finalHist <- stateRef.get
                yield AgentResult(
                  reply = assistantMsg,
                  history = finalHist,
                  iterationCount = iteration,
                  usage = assistantMsg.usage
                )

              case TurnOutput.ThinkingOnly(thinkingMsg) =>
                if emptyThinkingRetries < config.emptyThinkingRetryLimit then
                  val reminder = Msg.user(
                    name = Some("system"),
                    content = Chunk(
                      ContentBlock.Text(
                        "<system-reminder>Your previous reply had empty content - the full answer was written to the reasoning channel only. Reply again and write the final answer into the content channel.</system-reminder>"
                      )
                    ),
                    metadata = Map(
                      Msg.MetadataSynthetic    -> Json.Bool(true),
                      Msg.MetadataReminderKind -> Json.Str("empty_response")
                    )
                  )
                  for
                    _ <- persistAssistant(thinkingMsg, progressRef) *> stateRef.update(
                      _ :+ reminder
                    )
                    nextTurn <- restore(
                      loop(
                        iteration + 1,
                        emptyThinkingRetries + 1,
                        activeToolkit,
                        eventHub,
                        replyId,
                        requestConfig,
                        structuredResponse,
                        progressRef,
                        usageRef
                      )
                    )
                  yield nextTurn
                else
                  val limitMsg = Msg.assistant(
                    name = Some(config.name),
                    content = Chunk(
                      ContentBlock.Text("Empty visible content retry limit reached.")
                    ),
                    metadata = Map(
                      Msg.MetadataGenerateReason -> Json.Str("empty_response")
                    )
                  )
                  for
                    _ <- persistAssistant(thinkingMsg, progressRef) *> stateRef.update(
                      _ :+ limitMsg
                    )
                    finalHist <- stateRef.get
                  yield AgentResult(
                    reply = limitMsg,
                    history = finalHist,
                    iterationCount = iteration
                  )

              case TurnOutput.ToolCalls(assistantMsg) =>
                for
                  assistantPosition <- persistAssistant(assistantMsg, progressRef)
                  allCalls = assistantMsg.toolUses
                  inline = partitionToolResults(
                    allCalls,
                    assistantMsg.toolResults,
                    _.isServerTool,
                    (call, result) => call.id == result.id
                  )
                  pendingCalls = inline.pending
                  allSafe = config.streamParallelTools && pendingCalls
                    .forall(c => activeToolkit.resolve(c.call.name).forall(_.isConcurrencySafe))
                  execute = (call: IndexedToolCall) =>
                    executeAndRecord(
                      call,
                      assistantPosition,
                      activeToolkit,
                      stateRef,
                      eventHub,
                      context
                    )
                  localPhase =
                    if allSafe then ZIO.foreachParDiscard(pendingCalls)(execute)
                    else ZIO.foreachDiscard(pendingCalls)(execute)
                  phase = (ZIO.foreachDiscard(inline.completed) { case (call, indexed) =>
                    processInlineResult(call, indexed, assistantPosition, context, eventHub)
                  } *> localPhase).onExit:
                    case Exit.Failure(cause) if !cause.isInterruptedOnly =>
                      finalizeFailedToolPhase(assistantPosition)
                    case _ => ZIO.unit
                  outcome <- raceWithInterrupt(phase)
                  turnRes <- outcome match
                    case Left(_) => handleInterrupt(stateRef, iteration, progressRef)
                    case Right(_) =>
                      val captured = structuredResponse.fold[UIO[Option[Json]]](ZIO.none)(_.get)
                      captured.flatMap:
                        case value if pendingCalls.isEmpty || value.nonEmpty =>
                          completedToolTurn(assistantPosition, iteration)
                        case _ =>
                          restore(
                            loop(
                              iteration + 1,
                              0,
                              activeToolkit,
                              eventHub,
                              replyId,
                              requestConfig,
                              structuredResponse,
                              progressRef,
                              usageRef
                            )
                          )
                yield turnRes

              case TurnOutput.Interrupted(partial) =>
                persistAssistant(partial, progressRef) *> handleInterrupt(
                  stateRef,
                  iteration,
                  progressRef
                )

          def dispatchModel(
              history: Chunk[Msg],
              context: ModelStreamContext
          ): ZIO[Scope, AgentError, AgentResult] =
            for
              _ <- eventHub.publish(
                AgentEvent.ModelRequestStartEvent(requestConfig.model, 0, context = Some(context))
              )
              turnOutputEither <- raceWithInterrupt(
                ZIO
                  .scoped(
                    client.streamTurn(
                      history,
                      activeToolkit.definitions,
                      requestConfig,
                      eventHub,
                      context
                    )
                  )
                  .tap(output =>
                    usageRef
                      .update(current =>
                        output.message.chatUsage
                          .fold(current)(usage => Some(current.fold(usage)(_.combine(usage))))
                      )
                      .uninterruptible
                  )
                  .tap:
                    case _: TurnOutput.Interrupted => ZIO.unit
                    case output =>
                      eventHub
                        .publish(
                          AgentEvent.ModelResponseEndEvent(
                            requestConfig.model,
                            output.message.chatUsage.fold(0)(_.outputTokens),
                            output.message.chatUsage.fold(0)(_.totalTokens),
                            context = Some(context)
                          )
                        )
                        .unit
              )

              outcome <- turnOutputEither match
                case Left(_) =>
                  handleInterrupt(stateRef, iteration, progressRef)

                case Right(TurnOutput.Interrupted(partial)) =>
                  persistAssistant(partial, progressRef) *> handleInterrupt(
                    stateRef,
                    iteration,
                    progressRef
                  )

                case Right(rawOutput) =>
                  raceWithInterrupt(ZIO.scoped(applyReasoningHook(rawOutput, context))).flatMap:
                    case Left(_) => handleInterrupt(stateRef, iteration, progressRef)
                    case Right(None) =>
                      restore(
                        loop(
                          iteration + 1,
                          emptyThinkingRetries,
                          activeToolkit,
                          eventHub,
                          replyId,
                          requestConfig,
                          structuredResponse,
                          progressRef,
                          usageRef
                        )
                      )
                    case Right(Some(turnOutput)) => acceptTurn(turnOutput, context)
            yield outcome

          def requestModel(history: Chunk[Msg]): ZIO[Scope, AgentError, AgentResult] =
            for
              requestId <- Random.nextUUID.map(_.toString)
              context = ModelStreamContext(replyId, requestId)
              transformed <- raceWithInterrupt(
                ZIO.scoped(ZIO.suspendSucceed(modelInputTransform(context, history)))
              )
              result <- transformed match
                case Left(_) => handleInterrupt(stateRef, iteration, progressRef)
                case Right(candidate) =>
                  ZIO
                    .fromEither(ModelInputTransform.validate(history, candidate))
                    .flatMap(view => dispatchModel(view, context))
            yield result

          for
            _        <- progressRef.set(RunProgress.Ready)
            original <- stateRef.get
            prepared <- raceWithInterrupt(
              ZIO.scoped(ZIO.suspendSucceed(historyPreparer.prepare(original)))
            )
            result <- prepared match
              case Left(_) => handleInterrupt(stateRef, iteration, progressRef)
              case Right(candidate) =>
                ZIO
                  .fromEither(HistoryPreparer.validate(original, candidate))
                  .flatMap(history => stateRef.set(history) *> requestModel(history))
          yield result
        }
        .onInterrupt {
          reconcileDanglingToolCalls(stateRef, progressRef).ignore
        }

  private def applyReasoningHook(
      output: TurnOutput,
      context: ModelStreamContext
  ): ZIO[Scope, AgentError, Option[TurnOutput]] =
    output match
      case _: TurnOutput.Interrupted => ZIO.some(output)
      case _ =>
        postReasoningHook(context, output.message).flatMap:
          case ReasoningDecision.Keep    => ZIO.some(output)
          case ReasoningDecision.Discard => ZIO.none
          case ReasoningDecision.Replace(replacement) =>
            if replacement.role != Role.Assistant then
              ZIO.fail(
                AgentError.ReasoningHookError(
                  "A replacement reasoning message must have the assistant role"
                )
              )
            else
              val usageMetadata = output.message.metadata
                .get(Msg.MetadataChatUsage)
                .map(value => Msg.MetadataChatUsage -> value)
              val accepted = replacement
                .withUsage(output.message.usage)
                .withMetadata((replacement.metadata - Msg.MetadataChatUsage) ++ usageMetadata)
              val classified =
                if accepted.toolUses.nonEmpty then TurnOutput.ToolCalls(accepted)
                else if accepted.text.trim.isEmpty && accepted.thinking.nonEmpty then
                  TurnOutput.ThinkingOnly(accepted)
                else TurnOutput.FinalText(accepted)
              ZIO.some(classified)

  final private case class IndexedToolCall(call: ContentBlock.ToolUse, ordinal: Int)

  final private case class IndexedToolResult(result: ContentBlock.ToolResult, ordinal: Int)

  final private case class ToolResultPartition(
      completed: Chunk[(ContentBlock.ToolUse, IndexedToolResult)],
      pending: Chunk[IndexedToolCall],
      unmatched: Chunk[IndexedToolResult]
  )

  /** Consume each observation at most once, retaining pending call order and duplicate occurrences. */
  private def partitionToolResults(
      calls: Chunk[ContentBlock.ToolUse],
      results: Chunk[ContentBlock.ToolResult],
      eligible: ContentBlock.ToolUse => Boolean,
      matches: (ContentBlock.ToolUse, ContentBlock.ToolResult) => Boolean = (call, result) =>
        result.id == call.id && result.name.forall(_ == call.name)
  ): ToolResultPartition =
    val indexedResults = results.zipWithIndex.map { case (result, ordinal) =>
      IndexedToolResult(result, ordinal)
    }
    calls.zipWithIndex.foldLeft(ToolResultPartition(Chunk.empty, Chunk.empty, indexedResults)) {
      case (partition, (call, ordinal)) =>
        val indexed = IndexedToolCall(call, ordinal)
        if !eligible(call) then partition.copy(pending = partition.pending :+ indexed)
        else
          val (before, matchingAndAfter) =
            partition.unmatched.span(indexed => !matches(call, indexed.result))
          matchingAndAfter.headOption match
            case Some(result) =>
              partition.copy(
                completed = partition.completed :+ (call -> result),
                unmatched = before ++ matchingAndAfter.drop(1)
              )
            case None => partition.copy(pending = partition.pending :+ indexed)
    }

  private def completedToolTurn(position: Int, iteration: Int): IO[AgentError, AgentResult] =
    stateRef.get.flatMap: history =>
      ZIO
        .fromOption(history.lift(position))
        .orElseFail(AgentError.InvalidMessage("The completed assistant turn is missing"))
        .map(reply => AgentResult(reply, history, iteration))

  private def processedResult(result: ContentBlock.ToolResult): UIO[ContentBlock.ToolResult] =
    ZIO
      .scoped(ZIO.suspendSucceed(toolResultProcessor.process(result)))
      .catchAll(_ => ZIO.succeed(ToolResultPayload.fromResult(result)))
      .map(_.applyTo(result))

  private def processInlineResult(
      call: ContentBlock.ToolUse,
      indexed: IndexedToolResult,
      assistantPosition: Int,
      context: ModelStreamContext,
      eventHub: Hub[AgentEvent]
  ): UIO[Unit] =
    ZIO.uninterruptibleMask: restore =>
      for
        completed <- restore(processedResult(indexed.result))
        _ <- stateRef.update(_.zipWithIndex.map { case (message, position) =>
          if position == assistantPosition then
            message.mapToolResultAt(indexed.ordinal)(_ => completed)
          else message
        })
        _ <- publishInlineResult(call, completed, context, eventHub)
      yield ()

  private def publishInlineResult(
      call: ContentBlock.ToolUse,
      result: ContentBlock.ToolResult,
      context: ModelStreamContext,
      eventHub: Hub[AgentEvent]
  ): UIO[Unit] =
    eventHub.publish(
      AgentEvent.ToolResultStartEvent(
        call.id,
        call.name,
        metadata = result.metadata,
        context = Some(context)
      )
    ) *> publishCompletedResult(call, result, context, eventHub)

  private def publishCompletedResult(
      call: ContentBlock.ToolUse,
      result: ContentBlock.ToolResult,
      context: ModelStreamContext,
      eventHub: Hub[AgentEvent]
  ): UIO[Unit] =
    for
      _ <- ZIO.foreachDiscard(result.output):
        case ContentBlock.Text(text) =>
          eventHub.publish(
            AgentEvent.ToolResultDeltaEvent(
              call.id,
              text,
              metadata = result.metadata,
              context = Some(context)
            )
          )
        case data =>
          eventHub.publish(
            AgentEvent.ToolResultDataDeltaEvent(
              context.replyId,
              call.id,
              call.name,
              data,
              result.metadata,
              context = Some(context)
            )
          )
      _ <- eventHub.publish(
        AgentEvent.ToolResultEndEvent(
          call.id,
          call.name,
          result.text,
          result.state,
          metadata = result.metadata,
          context = Some(context)
        )
      )
    yield ()

  private def authorizeTool(
      call: ContentBlock.ToolUse,
      context: ModelStreamContext
  ): UIO[ToolAuthorization] =
    val decision = for
      requestId <- Random.nextUUID.map(_.toString)
      result <- ZIO.scoped(
        ZIO.suspendSucceed(
          toolAuthorizer.authorize(ToolAuthorizationRequest(requestId, context, call))
        )
      )
    yield result match
      case ToolAuthorization.Approved(input) =>
        JsonBoundary.validate(input) match
          case Left(error) => ToolAuthorization.Denied(s"Invalid approved input: $error")
          case Right(_)
              if input != call.input && (call.metadata.contains("thoughtSignature") || call.metadata
                .contains("reasoningDetails")) =>
            ToolAuthorization.Denied(
              "Cannot modify arguments carrying provider reasoning metadata"
            )
          case Right(_) => result
      case _ => result
    decision.foldCauseZIO(
      cause =>
        if cause.isInterrupted then ZIO.interrupt
        else
          ZIO.succeed(
            ToolAuthorization.Denied(
              cause.failureOption.map(_.reason).getOrElse("Tool authorization failed")
            )
          )
      ,
      ZIO.succeed(_)
    )

  private def executeAndRecord(
      indexed: IndexedToolCall,
      assistantPosition: Int,
      toolkit: Toolkit,
      stateRef: Ref[Chunk[Msg]],
      eventHub: Hub[AgentEvent],
      context: ModelStreamContext
  ): ZIO[Any, Nothing, Unit] =
    ZIO.uninterruptibleMask: restore =>
      val call = indexed.call
      for
        authorization <- restore(authorizeTool(call, context))
        _ <- eventHub.publish(
          AgentEvent.ToolResultStartEvent(call.id, call.name, context = Some(context))
        )
        res <- authorization match
          case ToolAuthorization.Denied(reason) =>
            ZIO.succeed(ToolExecutionResult(reason, ToolResultState.Denied))
          case ToolAuthorization.Approved(input) =>
            val approved =
              if input == call.input then call
              else
                call.copy(
                  input = input,
                  content = None,
                  metadata = call.metadata.updated("agentscope_original_tool_input", call.input)
                )
            stateRef.update(_.zipWithIndex.map { case (message, position) =>
              if position == assistantPosition then
                message.mapToolUseAt(indexed.ordinal)(_ => approved)
              else message
            }) *> restore(toolkit.executeSafe(call.name, input))
        resultBlock = ContentBlock.ToolResult.text(call.id, call.name, res.output, res.state)
        toolMsg     = Msg.tool(name = Some(call.name), content = Chunk(resultBlock))
        position  <- stateRef.modify(history => (history.size, history :+ toolMsg))
        completed <- restore(processedResult(resultBlock))
        _ <- stateRef.update(_.zipWithIndex.map { case (message, index) =>
          if index == position then toolMsg.mapToolResults(_ => completed) else message
        })
        _ <- publishCompletedResult(call, completed, context, eventHub)
      yield ()

  private def handleInterrupt(
      stateRef: Ref[Chunk[Msg]],
      iteration: Int,
      progressRef: Ref[RunProgress]
  ): UIO[AgentResult] =
    reconcileDanglingToolCalls(stateRef, progressRef).flatMap { recoveryMsg =>
      stateRef.get.map { finalHist =>
        AgentResult(
          reply = recoveryMsg,
          history = finalHist,
          iterationCount = iteration,
          state = AgentRunState.Interrupted
        )
      }
    }

  private enum RunProgress:
    case Ready
    case PersistedAssistant(position: Int)
    case Recovered(message: Msg)

  private def persistAssistant(message: Msg, progressRef: Ref[RunProgress]): UIO[Int] =
    ZIO.uninterruptible(
      stateRef
        .modify(history => (history.size, history :+ message))
        .tap(position => progressRef.set(RunProgress.PersistedAssistant(position)))
    )

  private def pendingLocalCalls(history: Chunk[Msg], position: Int): Chunk[IndexedToolCall] =
    history
      .lift(position)
      .fold(Chunk.empty[IndexedToolCall]): assistant =>
        val withoutInline = partitionToolResults(
          assistant.toolUses,
          assistant.toolResults,
          _.isServerTool,
          (call, result) => call.id == result.id
        ).pending
        partitionToolResults(
          withoutInline.map(_.call),
          history.drop(position + 1).filter(_.role == Role.Tool).flatMap(_.toolResults),
          _ => true
        ).pending

  private def finalizeFailedToolPhase(position: Int): UIO[Unit] =
    stateRef.update: history =>
      history ++ pendingLocalCalls(history, position).map: indexed =>
        val call = indexed.call
        Msg.tool(
          name = Some(call.name),
          content = Chunk(
            ContentBlock.ToolResult.text(
              call.id,
              call.name,
              s"Tool phase aborted before a completed result was recorded. Tool: ${call.name}",
              ToolResultState.Error
            )
          )
        )

  private def reconcileDanglingToolCalls(
      stateRef: Ref[Chunk[Msg]],
      progressRef: Ref[RunProgress]
  ): UIO[Msg] =
    ZIO.uninterruptible(
      progressRef.get.flatMap {
        case RunProgress.Recovered(message) => ZIO.succeed(message)
        case progress =>
          stateRef
            .modify { history =>
              val pending = progress match
                case RunProgress.PersistedAssistant(position) =>
                  pendingLocalCalls(history, position)
                case _ => Chunk.empty[IndexedToolCall]
              val synthesized = pending.map { indexed =>
                val call = indexed.call
                Msg.tool(
                  name = Some(call.name),
                  content = Chunk(
                    ContentBlock.ToolResult.text(
                      call.id,
                      call.name,
                      s"Tool execution was interrupted before it could run. Tool: ${call.name}",
                      ToolResultState.Error
                    )
                  )
                )
              }
              val recovery = Msg.assistant(
                name = Some(config.name),
                content = Chunk(
                  ContentBlock.Text(
                    "I noticed that you have interrupted me. What can I do for you?"
                  )
                ),
                metadata = Map(Msg.MetadataGenerateReason -> Json.Str("interrupted"))
              )
              (recovery, history ++ synthesized :+ recovery)
            }
            .tap(message => progressRef.set(RunProgress.Recovered(message)))
      }
    )

object ReActAgent:

  def make(
      config: AgentConfig = AgentConfig(),
      toolkit: Toolkit = Toolkit.empty,
      client: LlmClient,
      llmConfig: LlmConfig = LlmConfig("default-model"),
      initialHistory: Chunk[Msg] = Chunk.empty,
      postReasoningHook: PostReasoningHook = PostReasoningHook.keep,
      toolResultProcessor: ToolResultProcessor = ToolResultProcessor.identity,
      toolAuthorizer: ToolAuthorizer = ToolAuthorizer.allow,
      historyPreparer: HistoryPreparer = HistoryPreparer.identity,
      modelInputTransform: ModelInputTransform = ModelInputTransform.identity,
      completionObserver: TurnCompletionObserver = TurnCompletionObserver.ignore
  ): IO[AgentError, ReActAgent] =
    for
      _                     <- ZIO.fromEither(config.validated)
      stateRef              <- Ref.make(initialHistory)
      agentId               <- Random.nextUUID.map(_.toString)
      p                     <- Promise.make[Nothing, Unit]
      interruptedPromiseRef <- Ref.make(p)
      runLock               <- Semaphore.make(1)
    yield new ReActAgent(
      config,
      toolkit,
      client,
      llmConfig,
      stateRef,
      interruptedPromiseRef,
      agentId,
      runLock,
      postReasoningHook,
      toolResultProcessor,
      toolAuthorizer,
      historyPreparer,
      modelInputTransform,
      completionObserver
    )

  def builder(): Builder = Builder()

  final case class Builder(
      private val _config: AgentConfig = AgentConfig(),
      private val _toolkit: Toolkit = Toolkit.empty,
      private val _client: Option[LlmClient] = None,
      private val _llmConfig: LlmConfig = LlmConfig("default-model"),
      private val _initialHistory: Chunk[Msg] = Chunk.empty,
      private val _postReasoningHook: PostReasoningHook = PostReasoningHook.keep,
      private val _toolResultProcessor: ToolResultProcessor = ToolResultProcessor.identity,
      private val _toolAuthorizer: ToolAuthorizer = ToolAuthorizer.allow,
      private val _historyPreparer: HistoryPreparer = HistoryPreparer.identity,
      private val _modelInputTransform: ModelInputTransform = ModelInputTransform.identity,
      private val _completionObserver: TurnCompletionObserver = TurnCompletionObserver.ignore
  ):
    def name(n: String): Builder                  = copy(_config = _config.copy(name = n))
    def description(d: String): Builder           = copy(_config = _config.copy(description = d))
    def sysPrompt(p: String): Builder             = copy(_config = _config.copy(systemPrompt = p))
    def systemPrompt(p: String): Builder          = copy(_config = _config.copy(systemPrompt = p))
    def maxIterations(m: Int): Builder            = copy(_config = _config.copy(maxIterations = m))
    def maxIters(m: Int): Builder                 = copy(_config = _config.copy(maxIterations = m))
    def timeout(d: Duration): Builder             = copy(_config = _config.copy(timeout = d))
    def toolExecution(te: ToolExecution): Builder = copy(_config = _config.copy(toolExecution = te))
    def toolkit(tk: Toolkit): Builder             = copy(_toolkit = tk)
    def model(c: LlmClient): Builder              = copy(_client = Some(c))
    def client(c: LlmClient): Builder             = copy(_client = Some(c))
    def llmConfig(c: LlmConfig): Builder          = copy(_llmConfig = c)
    def initialHistory(h: Chunk[Msg]): Builder    = copy(_initialHistory = h)

    def postReasoningHook(hook: PostReasoningHook): Builder = copy(_postReasoningHook = hook)

    def toolResultProcessor(processor: ToolResultProcessor): Builder =
      copy(_toolResultProcessor = processor)

    def toolAuthorizer(authorizer: ToolAuthorizer): Builder = copy(_toolAuthorizer = authorizer)

    def historyPreparer(preparer: HistoryPreparer): Builder = copy(_historyPreparer = preparer)

    def modelInputTransform(transform: ModelInputTransform): Builder =
      copy(_modelInputTransform = transform)

    def completionObserver(observer: TurnCompletionObserver): Builder =
      copy(_completionObserver = observer)

    def build: IO[AgentError, ReActAgent] =
      ZIO
        .fromOption(_client)
        .orElseFail(AgentError.InvalidConfiguration("A model client is required"))
        .flatMap(model =>
          ReActAgent.make(
            _config,
            _toolkit,
            model,
            _llmConfig,
            _initialHistory,
            _postReasoningHook,
            _toolResultProcessor,
            _toolAuthorizer,
            _historyPreparer,
            _modelInputTransform,
            _completionObserver
          )
        )

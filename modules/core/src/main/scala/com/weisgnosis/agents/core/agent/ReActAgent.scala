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

import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{AgentEvent, ModelStreamContext}
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.Toolkit
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
    private val runLock: Semaphore
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
    runLock.withPermit(ZIO.scoped(for
      hub    <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
      result <- runWithHub(messages, hub, toolkit)(result => ZIO.succeed((result, ()))).map(_._1)
    yield result))

  def run(message: Msg): ZIO[Scope, AgentError, AgentResult] =
    run(Chunk(message))

  /** Full structured run returning AgentResult and decoded structured object. */
  def runStructured[A: JsonCodec](
      messages: Chunk[Msg],
      schema: Json,
      mode: StructuredMode = StructuredMode.Auto
  ): ZIO[Scope, AgentError, (AgentResult, A)] =
    runLock.withPermit(ZIO.scoped(for
      hub    <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
      result <- runStructuredWithHub[A](messages, schema, mode, hub)
    yield result))

  private def runStructuredWithHub[A: JsonCodec](
      messages: Chunk[Msg],
      schema: Json,
      mode: StructuredMode,
      hub: Hub[AgentEvent]
  ): ZIO[Scope, AgentError, (AgentResult, A)] =
    Ref.make[Option[Json]](None).flatMap { capturedRef =>
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
        capturedRef.get.flatMap(captured => ZIO.fromEither(prepareStructured[A](result, captured)))

      def nativeAttempt: ZIO[Scope, AgentError, (AgentResult, A)] =
        runWithHub(messages, hub, toolkit, nativeConfig, Some(capturedRef))(prepare)

      def syntheticAttempt(input: Chunk[Msg]): ZIO[Scope, AgentError, (AgentResult, A)] =
        ZIO
          .fromEither(toolkit.register(syntheticTool))
          .flatMap(active => runWithHub(input, hub, active, llmConfig, Some(capturedRef))(prepare))

      mode match
        case StructuredMode.Native        => nativeAttempt
        case StructuredMode.SyntheticTool => syntheticAttempt(messages)
        case StructuredMode.Auto if toolkit.isEmpty =>
          nativeAttempt.catchAll {
            case AgentError.ModelError(status, _, _) if Set(400, 422, 501).contains(status) =>
              syntheticAttempt(Chunk.empty)
            case error => ZIO.fail(error)
          }
        case StructuredMode.Auto => syntheticAttempt(messages)
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
    streamRun(hub => runWithHub(messages, hub, toolkit)(result => ZIO.succeed((result, ()))))

  /** Streams an attempt through validated structured completion before its result/end events. */
  def streamStructuredEvents[A: JsonCodec](
      messages: Chunk[Msg],
      schema: Json,
      mode: StructuredMode = StructuredMode.Auto
  ): ZStream[Scope, AgentError, AgentEvent] =
    streamRun(hub => runStructuredWithHub[A](messages, schema, mode, hub))

  def streamStructuredEvents[A: JsonCodec](
      message: Msg,
      schema: Json
  ): ZStream[Scope, AgentError, AgentEvent] =
    streamStructuredEvents[A](Chunk(message), schema, StructuredMode.Auto)

  private def streamRun[A](
      runEffect: Hub[AgentEvent] => ZIO[Scope, AgentError, A]
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
            exit <- restore(runLock.withPermit(ZIO.scoped(runEffect(hub)))).exit
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

  private def runWithHub[A](
      newMessages: Chunk[Msg],
      eventHub: Hub[AgentEvent],
      activeToolkit: Toolkit,
      requestConfig: LlmConfig = llmConfig,
      structuredResponse: Option[Ref[Option[Json]]] = None
  )(
      prepareResult: AgentResult => IO[AgentError, (AgentResult, A)]
  ): ZIO[Scope, AgentError, (AgentResult, A)] =
    for
      startingHistory <- stateRef.get
      p               <- Promise.make[Nothing, Unit]
      _               <- interruptedPromiseRef.set(p)
      replyId         <- Random.nextUUID.map(_.toString)
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
      runHistoryStart <- stateRef.get.map(_.size)
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
            runHistoryStart
          ).map(result =>
            result.copy(usage =
              result.history
                .drop(startingHistory.size)
                .filter(_.role == Role.Assistant)
                .flatMap(_.usage)
                .reduceOption(_.combine(_))
            )
          ).flatMap(prepareResult)
            .onInterrupt(reconcileDanglingToolCalls(stateRef, runHistoryStart).unit)
          for
            completed <- restore(
              prepare.timeoutFail(
                AgentError.TimeoutError(config.timeout.toSeconds, "Agent run timed out")
              )(config.timeout)
            )
            _ <- stateRef.set(completed._1.history)
            _ <- eventHub.publish(AgentEvent.AgentResultEvent(completed._1.reply))
          yield completed
        }
      }
    yield result

  private def loop(
      iteration: Int,
      emptyThinkingRetries: Int,
      activeToolkit: Toolkit,
      eventHub: Hub[AgentEvent],
      replyId: String,
      requestConfig: LlmConfig,
      structuredResponse: Option[Ref[Option[Json]]],
      runHistoryStart: Int
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
        _            <- stateRef.update(_ :+ maxIterMsg)
        finalHistory <- stateRef.get
      yield AgentResult(reply = maxIterMsg, history = finalHistory, iterationCount = iteration - 1)
    else
      ZIO
        .uninterruptibleMask { restore =>
          def raceWithInterrupt[R, E, A](effect: ZIO[R, E, A]): ZIO[R, E, Either[Unit, A]] =
            for
              p   <- interruptedPromiseRef.get
              res <- restore(effect.exit.raceEither(p.await))
              value <- res match
                case Left(exit) => exit.map(Right(_))
                case Right(_)   => ZIO.succeed(Left(()))
            yield value

          for
            history   <- stateRef.get
            requestId <- Random.nextUUID.map(_.toString)
            context = ModelStreamContext(replyId, requestId)
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
                .tap:
                  case _: TurnOutput.Interrupted => ZIO.unit
                  case output =>
                    eventHub
                      .publish(
                        AgentEvent.ModelResponseEndEvent(
                          requestConfig.model,
                          output.message.usage.fold(0)(_.outputTokens),
                          output.message.usage.fold(0)(_.totalTokens),
                          context = Some(context)
                        )
                      )
                      .unit
            )

            outcome <- turnOutputEither match
              case Left(_) =>
                handleInterrupt(stateRef, iteration, runHistoryStart)

              case Right(turnOutput) =>
                turnOutput match
                  case TurnOutput.FinalText(assistantMsg) =>
                    for
                      _         <- stateRef.update(_ :+ assistantMsg)
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
                        _ <- stateRef.update(_ :+ thinkingMsg :+ reminder)
                        nextTurn <- restore(
                          loop(
                            iteration + 1,
                            emptyThinkingRetries + 1,
                            activeToolkit,
                            eventHub,
                            replyId,
                            requestConfig,
                            structuredResponse,
                            runHistoryStart
                          )
                        )
                      yield nextTurn
                    else
                      val limitMsg = Msg.assistant(
                        name = Some(config.name),
                        content =
                          Chunk(ContentBlock.Text("Empty visible content retry limit reached.")),
                        metadata = Map(
                          Msg.MetadataGenerateReason -> Json.Str("empty_response")
                        )
                      )
                      for
                        _         <- stateRef.update(_ :+ thinkingMsg :+ limitMsg)
                        finalHist <- stateRef.get
                      yield AgentResult(
                        reply = limitMsg,
                        history = finalHist,
                        iterationCount = iteration
                      )

                  case TurnOutput.ToolCalls(assistantMsg) =>
                    for
                      _ <- stateRef.update(_ :+ assistantMsg)
                      allCalls = assistantMsg.toolUses
                      inline = partitionToolResults(
                        allCalls,
                        assistantMsg.toolResults,
                        _.isServerTool
                      )
                      pendingCalls = inline.pending
                      _ <- ZIO.foreachDiscard(inline.completed) { case (call, result) =>
                        publishInlineResult(call, result, context, eventHub)
                      }

                      turnRes <-
                        if pendingCalls.isEmpty then
                          for finalHist <- stateRef.get
                          yield AgentResult(
                            reply = assistantMsg,
                            history = finalHist,
                            iterationCount = iteration,
                            usage = assistantMsg.usage
                          )
                        else
                          val allSafe = config.streamParallelTools && pendingCalls
                            .forall(c => activeToolkit.resolve(c.name).forall(_.isConcurrencySafe))
                          val execEffect =
                            if allSafe then
                              ZIO.foreachPar(pendingCalls)(call =>
                                executeAndRecord(call, activeToolkit, stateRef, eventHub, context)
                              )
                            else
                              ZIO.foreach(pendingCalls)(call =>
                                executeAndRecord(call, activeToolkit, stateRef, eventHub, context)
                              )

                          for
                            execRes <- raceWithInterrupt(execEffect)
                            res <- execRes match
                              case Left(_) =>
                                handleInterrupt(stateRef, iteration, runHistoryStart)
                              case Right(_) =>
                                val captured =
                                  structuredResponse.fold[UIO[Option[Json]]](ZIO.none)(_.get)
                                captured.flatMap {
                                  case Some(_) =>
                                    stateRef.get
                                      .map(history => AgentResult(assistantMsg, history, iteration))
                                  case None =>
                                    restore(
                                      loop(
                                        iteration + 1,
                                        0,
                                        activeToolkit,
                                        eventHub,
                                        replyId,
                                        requestConfig,
                                        structuredResponse,
                                        runHistoryStart
                                      )
                                    )
                                }
                          yield res
                    yield turnRes

                  case TurnOutput.Interrupted(partial) =>
                    stateRef
                      .update(_ :+ partial) *> handleInterrupt(stateRef, iteration, runHistoryStart)
          yield outcome
        }
        .onInterrupt {
          reconcileDanglingToolCalls(stateRef, runHistoryStart).ignore
        }

  final private case class ToolResultPartition(
      completed: Chunk[(ContentBlock.ToolUse, ContentBlock.ToolResult)],
      pending: Chunk[ContentBlock.ToolUse],
      unmatched: Chunk[ContentBlock.ToolResult]
  )

  /** Consume each observation at most once, retaining pending call order and duplicate occurrences. */
  private def partitionToolResults(
      calls: Chunk[ContentBlock.ToolUse],
      results: Chunk[ContentBlock.ToolResult],
      eligible: ContentBlock.ToolUse => Boolean
  ): ToolResultPartition =
    calls.foldLeft(ToolResultPartition(Chunk.empty, Chunk.empty, results)) { (partition, call) =>
      if !eligible(call) then partition.copy(pending = partition.pending :+ call)
      else
        val (before, matchingAndAfter) = partition.unmatched.span(result =>
          result.id != call.id || (!call.isServerTool && result.name.exists(_ != call.name))
        )
        matchingAndAfter.headOption match
          case Some(result) =>
            partition.copy(
              completed = partition.completed :+ (call -> result),
              unmatched = before ++ matchingAndAfter.drop(1)
            )
          case None => partition.copy(pending = partition.pending :+ call)
    }

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

  private def executeAndRecord(
      call: ContentBlock.ToolUse,
      toolkit: Toolkit,
      stateRef: Ref[Chunk[Msg]],
      eventHub: Hub[AgentEvent],
      context: ModelStreamContext
  ): ZIO[Any, Nothing, Unit] =
    ZIO.uninterruptibleMask: restore =>
      for
        _ <- eventHub.publish(
          AgentEvent.ToolResultStartEvent(call.id, call.name, context = Some(context))
        )
        res <- restore(toolkit.executeSafe(call.name, call.input))
        resultBlock = ContentBlock.ToolResult.text(call.id, call.name, res.output, res.state)
        toolMsg     = Msg.tool(name = Some(call.name), content = Chunk(resultBlock))
        _ <- stateRef.update(_ :+ toolMsg)
        _ <- publishCompletedResult(call, resultBlock, context, eventHub)
      yield ()

  private def handleInterrupt(
      stateRef: Ref[Chunk[Msg]],
      iteration: Int,
      runHistoryStart: Int
  ): UIO[AgentResult] =
    reconcileDanglingToolCalls(stateRef, runHistoryStart).flatMap { recoveryMsg =>
      stateRef.get.map { finalHist =>
        AgentResult(
          reply = recoveryMsg,
          history = finalHist,
          iterationCount = iteration,
          state = AgentRunState.Interrupted
        )
      }
    }

  private def reconcileDanglingToolCalls(
      stateRef: Ref[Chunk[Msg]],
      runHistoryStart: Int
  ): UIO[Msg] =
    stateRef.modify { history =>
      val runHistory    = history.drop(runHistoryStart)
      val currentTurn   = runHistory.reverse.takeWhile(_.role != Role.Assistant).reverse
      val lastAssistant = runHistory.reverse.find(_.role == Role.Assistant)

      lastAssistant match
        case Some(asst)
            if asst.generateReason.contains("interrupted") && history.lastOption.contains(asst) =>
          (asst, history)

        case Some(asst) =>
          val pendingCalls = partitionToolResults(
            asst.toolUses.filterNot(_.isServerTool),
            currentTurn.flatMap(_.toolResults),
            _ => true
          ).pending
          val synthesizedResults = pendingCalls.map { call =>
            Msg.tool(
              name = Some(call.name),
              content = Chunk(
                ContentBlock.ToolResult(
                  id = call.id,
                  name = Some(call.name),
                  output = Chunk(
                    ContentBlock.Text(
                      s"Tool execution was interrupted before it could run. Tool: ${call.name}"
                    )
                  ),
                  state = ToolResultState.Error
                )
              )
            )
          }
          val recoveryMsg = Msg.assistant(
            name = Some(config.name),
            content = Chunk(
              ContentBlock.Text("I noticed that you have interrupted me. What can I do for you?")
            ),
            metadata = Map(
              Msg.MetadataGenerateReason -> Json.Str("interrupted")
            )
          )
          val newHistory =
            if pendingCalls.nonEmpty then history ++ synthesizedResults :+ recoveryMsg
            else history :+ recoveryMsg
          (recoveryMsg, newHistory)

        case None =>
          val recoveryMsg = Msg.assistant(
            name = Some(config.name),
            content = Chunk(
              ContentBlock.Text("I noticed that you have interrupted me. What can I do for you?")
            ),
            metadata = Map(
              Msg.MetadataGenerateReason -> Json.Str("interrupted")
            )
          )
          (recoveryMsg, history :+ recoveryMsg)
    }

object ReActAgent:

  def make(
      config: AgentConfig = AgentConfig(),
      toolkit: Toolkit = Toolkit.empty,
      client: LlmClient,
      llmConfig: LlmConfig = LlmConfig("default-model"),
      initialHistory: Chunk[Msg] = Chunk.empty
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
      runLock
    )

  def builder(): Builder = Builder()

  final case class Builder(
      private val _config: AgentConfig = AgentConfig(),
      private val _toolkit: Toolkit = Toolkit.empty,
      private val _client: Option[LlmClient] = None,
      private val _llmConfig: LlmConfig = LlmConfig("default-model"),
      private val _initialHistory: Chunk[Msg] = Chunk.empty
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

    def build: IO[AgentError, ReActAgent] =
      ZIO
        .fromOption(_client)
        .orElseFail(AgentError.InvalidConfiguration("A model client is required"))
        .flatMap(model => ReActAgent.make(_config, _toolkit, model, _llmConfig, _initialHistory))

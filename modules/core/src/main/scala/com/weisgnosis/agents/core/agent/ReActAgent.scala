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
  BeforeResponseRejection,
  LlmClient,
  LlmConfig,
  ModelTarget,
  NativeToolSupport,
  StructuredOutputCapability,
  TurnOutput
}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{AgentEvent, ModelStreamContext}
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.skill.{SkillCatalog, SkillLibrary}
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
    private val pendingRef: Ref[Option[AgentResult]],
    private val interruptedPromiseRef: Ref[Promise[Nothing, Unit]],
    val agentId: String,
    private val runLock: Semaphore,
    val postReasoningHook: PostReasoningHook,
    val toolResultProcessor: ToolResultProcessor,
    val toolAuthorizer: ToolAuthorizer,
    val historyPreparer: HistoryPreparer,
    val modelInputTransform: ModelInputTransform,
    val completionObserver: TurnCompletionObserver,
    val toolGroups: Option[ToolGroups],
    private val skillLibrary: SkillLibrary,
    val fallback: Option[ModelTarget]
):

  val skills: SkillCatalog = skillLibrary.catalog

  def name: String        = config.name
  def description: String = config.description
  def maxIterations: Int  = config.maxIterations
  def getMaxIters: Int    = config.maxIterations
  def getAgentId: String  = agentId

  def history: UIO[Chunk[Msg]] = stateRef.get

  /** Explicitly discards both conversation history and any unexecuted suspended batch. */
  def resetHistory: UIO[Unit] =
    runLock.withPermit(ZIO.uninterruptible(pendingRef.set(None) *> stateRef.set(Chunk.empty)))

  /** Installs a checked data checkpoint on an empty agent using this host's executable bindings. */
  def restore(result: AgentResult): IO[AgentError, Unit] =
    runLock.withPermit:
      ZIO.uninterruptibleMask: restore =>
        for
          history <- stateRef.get
          pending <- pendingRef.get
          _ <- ZIO
            .fail(AgentError.InvalidConfiguration("Restoration requires an empty agent"))
            .unless(history.isEmpty && pending.isEmpty)
          checkpoint <- ZIO.fromEither(validateCheckpoint(result))
          _          <- restore(restoredRunTools(checkpoint))
          _          <- stateRef.set(result.history) *> pendingRef.set(Some(result))
        yield ()

  /** Continues the saved acting phase before requesting any further model output. */
  def resume(): ZIO[Scope, AgentError, AgentResult] =
    resume(Chunk.empty)

  /** Supplies checked execution observations and executes only the remaining local occurrences. */
  def resume(results: Chunk[SuppliedToolResult]): ZIO[Scope, AgentError, AgentResult] =
    runLock.withPermit:
      for
        prepared <- prepareResume(results)
        resumed <- observeInvocation(complete =>
          ZIO.scoped:
            for
              hub   <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
              value <- runResumeWithHub(prepared, hub, complete)
            yield value
        )
      yield resumed

  final private case class PreparedResume(
      result: AgentResult,
      checkpoint: PendingActing,
      tools: RunTools,
      admitted: Toolkit
  )

  /** Validates under the caller's run lock without claiming or publishing the checkpoint. */
  private def prepareResume(results: Chunk[SuppliedToolResult]): IO[AgentError, PreparedResume] =
    for
      saved <- pendingRef.get.someOrFail(
        AgentError.InvalidConfiguration("No suspended batch is available")
      )
      result     <- ZIO.fromEither(PendingActing.withResults(saved, results))
      checkpoint <- ZIO.fromEither(validateCheckpoint(result))
      history    <- stateRef.get
      _ <- ZIO
        .fail(AgentError.InvalidConfiguration("Suspended history has changed"))
        .unless(history == result.history)
      tools <- restoredRunTools(checkpoint)
    yield PreparedResume(result, checkpoint, tools._1, tools._2)

  private def runResumeWithHub(
      prepared: PreparedResume,
      hub: Hub[AgentEvent],
      complete: AgentResult => IO[AgentError, Unit]
  ): ZIO[Scope, AgentError, AgentResult] =
    Ref
      .make(prepared.result.usage)
      .flatMap(usage =>
        runWithHub(
          RunEntry.Resume(prepared.result, prepared.checkpoint, prepared.admitted),
          hub,
          prepared.tools,
          usage,
          complete,
          llmConfig.copy(responseFormat = prepared.checkpoint.responseFormat)
        )(value => ZIO.succeed((value, ()))).map(_._1)
      )

  private def requireFreshRun: IO[AgentError.InvalidConfiguration, Unit] =
    pendingRef.get.flatMap(pending =>
      ZIO
        .fail(
          AgentError.InvalidConfiguration(
            "Resume or discard the suspended batch before starting a new run"
          )
        )
        .when(pending.nonEmpty)
        .unit
    )

  private def validateCheckpoint(
      result: AgentResult
  ): Either[AgentError.InvalidConfiguration, PendingActing] =
    PendingActing
      .validate(result)
      .flatMap(pending =>
        Either.cond(
          result.iterationCount <= config.maxIterations,
          pending,
          AgentError.InvalidConfiguration("Suspended iteration exceeds the host iteration limit")
        )
      )

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
      requireFreshRun *> observeInvocation(complete =>
        ZIO.scoped(for
          hub    <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
          result <- runOrdinaryWithHub(messages, hub, complete)
        yield result)
      )
    )

  def run(message: Msg): ZIO[Scope, AgentError, AgentResult] =
    run(Chunk(message))

  /**
   * Direct execution with scoped event forwarding. A closed destination drops events without
   * cancelling the run; result and failure authority remain on the calling fiber.
   */
  private[agents] def runForwarding(
      messages: Chunk[Msg],
      destination: ToolEventPort,
      source: String
  ): ZIO[Scope, AgentError, AgentResult] =
    def forward(event: AgentEvent): UIO[Unit] =
      destination
        .publish(event.withSource(event.source.fold(source)(nested => s"$source/$nested")))
        .unit

    runLock.withPermit(
      requireFreshRun *> observeInvocation(complete =>
        ZIO.scoped:
          for
            hub          <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
            subscription <- hub.subscribe
            drain        <- AgentEventForwarder.start(subscription, forward)
            result <- ZIO
              .scoped(runOrdinaryWithHub(messages, hub, complete))
              .ensuring(drain)
          yield result
      )
    )

  private[agents] def runForwarding(
      message: Msg,
      destination: ToolEventPort,
      source: String
  ): ZIO[Scope, AgentError, AgentResult] =
    runForwarding(Chunk(message), destination, source)

  /** Full structured run returning AgentResult and decoded structured object. */
  def runStructured[A: JsonCodec](
      messages: Chunk[Msg],
      schema: Json,
      mode: StructuredMode = StructuredMode.Auto
  ): ZIO[Scope, AgentError, (AgentResult, A)] =
    runLock.withPermit(
      requireFreshRun *> observeInvocation(complete =>
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
    for
      runTools    <- makeRunTools
      capturedRef <- Ref.make[Option[Json]](None)
      usageRef    <- Ref.make[Option[ChatUsage]](None)
      result <-
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
          runWithHub(
            RunEntry.Fresh(messages),
            hub,
            runTools,
            usageRef,
            complete,
            nativeConfig,
            CompletionMode.Structured(capturedRef)
          )(
            prepare
          )

        val syntheticConfig = llmConfig.copy(responseFormat = None)

        def syntheticAttempt(input: Chunk[Msg]): ZIO[Scope, AgentError, (AgentResult, A)] =
          ZIO
            .fromEither(runTools.withControl(syntheticTool))
            .flatMap(active =>
              runWithHub(
                RunEntry.Fresh(input),
                hub,
                active,
                usageRef,
                complete,
                syntheticConfig,
                CompletionMode.Structured(capturedRef)
              )(prepare)
            )

        def nativeWithFallback: ZIO[Scope, AgentError, (AgentResult, A)] =
          nativeAttempt.catchAllCause {
            case Cause.Fail(AgentError.ModelError(status, _, _), _)
                if Set(400, 422, 501).contains(status) =>
              syntheticAttempt(Chunk.empty)
            case cause => ZIO.refailCause(cause)
          }

        def automaticAttempt: ZIO[Scope, AgentError, (AgentResult, A)] =
          llmConfig.structuredOutputCapability match
            case StructuredOutputCapability.Unsupported => syntheticAttempt(messages)
            case StructuredOutputCapability.Native(NativeToolSupport.Supported) =>
              nativeWithFallback
            case StructuredOutputCapability.Unknown | StructuredOutputCapability.Native(
                  NativeToolSupport.Unsupported
                ) =>
              runTools.snapshot.flatMap(selected =>
                if selected.isEmpty then nativeWithFallback else syntheticAttempt(messages)
              )

        mode match
          case StructuredMode.Native        => nativeAttempt
          case StructuredMode.SyntheticTool => syntheticAttempt(messages)
          case StructuredMode.Auto          => automaticAttempt
    yield result

  /** Prepare one validated payload and final history without publishing or mutating runtime state. */
  private def prepareStructured[A: JsonCodec](
      rawResult: AgentResult,
      captured: Option[Json]
  ): Either[AgentError, (AgentResult, A)] =
    if rawResult.state != AgentRunState.Completed then
      Left(rawResult.state match
        case AgentRunState.Stopped(reason) => AgentError.ExecutionStopped(reason)
        case _ => AgentError.Interrupted("Structured output run ended before completion"))
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

  /**
   * Streams one invocation's events followed by its complete result, after execution cleanup.
   * A fully consumed stream preserves the full failure Cause and emits no Result on failure.
   * Early consumers cancel and join owned work but abandon subsequent output, including terminal
   * observer failures. This uses the existing unbounded delivery buffers and provides no replay.
   */
  def streamWithResult(
      messages: Chunk[Msg]
  ): ZStream[Scope, AgentError, AgentRunUpdate[AgentResult]] =
    streamRun(hub =>
      requireFreshRun *> observeInvocation(complete =>
        ZIO.scoped(runOrdinaryWithHub(messages, hub, complete))
      )
    )

  def streamWithResult(message: Msg): ZStream[Scope, AgentError, AgentRunUpdate[AgentResult]] =
    streamWithResult(Chunk(message))

  /** Lazily resumes one checkpoint with the same delivery and cleanup contract as streamWithResult. */
  def streamResumeWithResult(
      results: Chunk[SuppliedToolResult] = Chunk.empty
  ): ZStream[Scope, AgentError, AgentRunUpdate[AgentResult]] =
    streamRun(hub =>
      prepareResume(results).flatMap(prepared =>
        observeInvocation(complete => ZIO.scoped(runResumeWithHub(prepared, hub, complete)))
      )
    )

  def streamResumeEvents(
      results: Chunk[SuppliedToolResult] = Chunk.empty
  ): ZStream[Scope, AgentError, AgentEvent] =
    streamResumeWithResult(results).collect { case AgentRunUpdate.Event(event) => event }

  /** The terminal value retains both the complete result and its validated structured value. */
  def streamStructuredWithResult[A: JsonCodec](
      messages: Chunk[Msg],
      schema: Json,
      mode: StructuredMode = StructuredMode.Auto
  ): ZStream[Scope, AgentError, AgentRunUpdate[(AgentResult, A)]] =
    streamRun(hub =>
      requireFreshRun *> observeInvocation(complete =>
        ZIO.scoped(runStructuredWithHub[A](messages, schema, mode, hub, complete))
      )
    )

  def streamStructuredWithResult[A: JsonCodec](
      message: Msg,
      schema: Json
  ): ZStream[Scope, AgentError, AgentRunUpdate[(AgentResult, A)]] =
    streamStructuredWithResult[A](Chunk(message), schema, StructuredMode.Auto)

  /** Streams typed lifecycle and delta events while running the turn in the background. */
  def streamEvents(messages: Chunk[Msg]): ZStream[Scope, AgentError, AgentEvent] =
    streamWithResult(messages).collect { case AgentRunUpdate.Event(event) => event }

  /** Streams an attempt through validated structured completion before its result/end events. */
  def streamStructuredEvents[A: JsonCodec](
      messages: Chunk[Msg],
      schema: Json,
      mode: StructuredMode = StructuredMode.Auto
  ): ZStream[Scope, AgentError, AgentEvent] =
    streamStructuredWithResult[A](messages, schema, mode).collect {
      case AgentRunUpdate.Event(event) => event
    }

  def streamStructuredEvents[A: JsonCodec](
      message: Msg,
      schema: Json
  ): ZStream[Scope, AgentError, AgentEvent] =
    streamStructuredEvents[A](Chunk(message), schema, StructuredMode.Auto)

  private def streamRun[A](
      runEffect: Hub[AgentEvent] => ZIO[Scope, AgentError, A]
  ): ZStream[Scope, AgentError, AgentRunUpdate[A]] =
    ZStream.unwrapScoped {
      for
        hub <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
        sub <- hub.subscribe
        delivery <- ZIO.acquireRelease(Queue.unbounded[Take[AgentError, AgentRunUpdate[A]]])(
          _.shutdown
        )
        drain <- AgentEventForwarder.start(
          sub,
          event => delivery.offer(Take.single(AgentRunUpdate.Event(event))).unit
        )
        _ <- ZIO.uninterruptibleMask { restore =>
          for
            exit <- restore(
              runLock.withPermit(runEffect(hub))
            ).exit
            _ <- drain
            _ <- exit match
              case Exit.Success(value) =>
                delivery.offer(Take.single(AgentRunUpdate.Result(value))) *> delivery.offer(
                  Take.end
                )
              case Exit.Failure(cause) => delivery.offer(Take.failCause(cause))
          yield ()
        }.forkScoped
      yield ZStream.fromQueue(delivery).flattenTake
    }

  def streamEvents(message: Msg): ZStream[Scope, AgentError, AgentEvent] =
    streamEvents(Chunk(message))

  /** One activation authority per invocation; runtime controls are composed after base selection. */
  final private case class RunTools(
      base: Toolkit,
      activation: Option[Ref[ToolGroups]],
      controls: Toolkit = Toolkit.empty
  ):
    def snapshot: UIO[Toolkit] =
      activation match
        case None => ZIO.succeed(Toolkit(base.tools ++ controls.tools))
        case Some(current) =>
          current.get.map: groups =>
            val selected = groups.select(base)
            val describedControls =
              if controls.contains("reset_equipped_tools") then
                controls.tools.updated("reset_equipped_tools", resetEquippedTools(current, groups))
              else controls.tools
            Toolkit(selected.tools ++ describedControls)

    def withControl(tool: Tool[Any, ?, ?]): Either[AgentError, RunTools] =
      base
        .register(tool)
        .flatMap(_ => controls.register(tool).map(updated => copy(controls = updated)))

  private def makeRunTools: IO[AgentError, RunTools] = makeRunTools(toolGroups)

  private def makeRunTools(groups: Option[ToolGroups]): IO[AgentError, RunTools] =
    if groups.isEmpty && skills.isEmpty then ZIO.succeed(RunTools(toolkit, None))
    else
      val initial = groups.getOrElse(ToolGroups.empty)
      Ref
        .make(initial)
        .flatMap: activation =>
          val runTools = RunTools(toolkit, Some(activation))
          ZIO.fromEither:
            for
              withReset <-
                if initial.groups.exists(_.scope == ToolGroupScope.Meta) then
                  runTools.withControl(resetEquippedTools(activation, initial))
                else Right(runTools)
              withSkills <-
                if skills.isEmpty then Right(withReset)
                else withReset.withControl(loadSkill(activation))
            yield withSkills

  /** Serialized definitions constrain restoration; executable tools always come from the host. */
  private def restoredRunTools(pending: PendingActing): IO[AgentError, (RunTools, Toolkit)] =
    def declarations(groups: Chunk[ToolGroup]): Chunk[ToolGroup] =
      groups
        .map(group =>
          if group.scope == ToolGroupScope.Meta then
            group.copy(activation = ToolGroupActivation.Inactive)
          else group
        )
        .sortBy(_.name)

    val configured =
      if toolGroups.nonEmpty || !skills.isEmpty then Some(toolGroups.getOrElse(ToolGroups.empty))
      else None
    def restoreGroups(
        saved: Option[Chunk[ToolGroup]]
    ): Either[AgentError.InvalidConfiguration, Option[ToolGroups]] =
      (configured, saved) match
        case (None, None) => Right(None)
        case (Some(current), Some(values))
            if declarations(current.groups) == declarations(values) =>
          current
            .replaceMetaActive(values.collect {
              case group
                  if group.scope == ToolGroupScope.Meta && group.activation == ToolGroupActivation.Active =>
                group.name
            }.toSet)
            .map(Some(_))
        case _ =>
          Left(
            AgentError.InvalidConfiguration(
              "Suspended tool groups do not match the host configuration"
            )
          )

    val latest = pending.stage match
      case PendingActingStage.BeforeActing                   => pending.toolGroups
      case PendingActingStage.AwaitingApproval(_, groups, _) => groups
    for
      admittedGroups <- ZIO.fromEither(restoreGroups(pending.toolGroups))
      latestGroups   <- ZIO.fromEither(restoreGroups(latest))
      runTools       <- makeRunTools(admittedGroups)
      admitted       <- runTools.snapshot
      _ <- ZIO
        .fail(
          AgentError.InvalidConfiguration(
            "Suspended tool definitions do not match the host bindings"
          )
        )
        .unless(admitted.definitions == pending.admittedTools)
      // Frozen admitted controls and later snapshots share this one activation authority.
      _ <- (runTools.activation, latestGroups) match
        case (Some(current), Some(groups)) => current.set(groups)
        case (None, None)                  => ZIO.unit
        case _ =>
          ZIO.fail(AgentError.InvalidConfiguration("Suspended group activation is inconsistent"))
    yield (runTools, admitted)

  final private case class SkillRequest(skillId: String, path: String)

  /** Resolves selected library resources before updating the invocation's atomic group state. */
  private def loadSkill(activation: Ref[ToolGroups]): Tool[Any, SkillRequest, String] =
    new Tool[Any, SkillRequest, String]:
      def definition: ToolDef = ToolDef(
        SkillCatalog.LoadToolName,
        "Load a selected skill resource and activate its Meta groups for the next model request.",
        Json.Obj(
          "type" -> Json.Str("object"),
          "properties" -> Json.Obj(
            "skillId" -> Json.Obj(
              "type" -> Json.Str("string"),
              "enum" -> Json.Arr(skills.definitions.map(skill => Json.Str(skill.id)))
            ),
            "path" -> Json.Obj("type" -> Json.Str("string"), "minLength" -> Json.Num(1))
          ),
          "required"             -> Json.Arr(Json.Str("skillId"), Json.Str("path")),
          "additionalProperties" -> Json.Bool(false)
        ),
        isConcurrencySafe = false,
        isReadOnly = true
      )

      def decodeInput(raw: Json): Either[String, SkillRequest] =
        JsonBoundary
          .validate(raw)
          .flatMap:
            case value: Json.Obj if value.fields.map(_._1).toSet == Set("skillId", "path") =>
              (value.get("skillId"), value.get("path")) match
                case (Some(Json.Str(id)), Some(Json.Str(path)))
                    if id.trim.nonEmpty && path.trim.nonEmpty =>
                  Right(SkillRequest(id, path))
                case _ => Left("skillId and path must be nonblank strings")
            case _ => Left("Expected exactly skillId and path fields")

      def execute(request: SkillRequest): IO[AgentError, String] =
        skillLibrary
          .resolve(request.skillId, request.path)
          .flatMap: resource =>
            activation
              .modify(current =>
                val active = current.groups.collect {
                  case group
                      if group.scope == ToolGroupScope.Meta && group.activation == ToolGroupActivation.Active =>
                    group.name
                }.toSet
                current.replaceMetaActive(active ++ resource.activateGroups) match
                  case Left(error)    => (Left(error), current)
                  case Right(updated) => (Right(resource.content), updated)
              )
              .absolve

      def encodeOutput(out: String): String = out

  private def toolGroupNotes(snapshot: ToolGroups): String =
    Chunk(snapshot.metaNotes, skills.groupNotes).filter(_.nonEmpty).mkString("\n\n")

  private def resetEquippedTools(
      activation: Ref[ToolGroups],
      snapshot: ToolGroups
  ): Tool[Any, Set[String], String] =
    val meta = snapshot.groups.filter(_.scope == ToolGroupScope.Meta)
    new Tool[Any, Set[String], String]:
      def definition: ToolDef = ToolDef(
        "reset_equipped_tools",
        "Replace the active Meta tool groups for the next model request; unlisted Meta groups are deactivated.\n" +
          toolGroupNotes(snapshot),
        Json.Obj(
          "type" -> Json.Str("object"),
          "properties" -> Json.Obj(
            "to_activate" -> Json.Obj(
              "type" -> Json.Str("array"),
              "items" -> Json.Obj(
                "type" -> Json.Str("string"),
                "enum" -> Json.Arr(meta.map(group => Json.Str(group.name)))
              )
            )
          ),
          "required"             -> Json.Arr(Json.Str("to_activate")),
          "additionalProperties" -> Json.Bool(false)
        ),
        isConcurrencySafe = false,
        isReadOnly = false
      )
      def decodeInput(raw: Json): Either[String, Set[String]] =
        JsonBoundary
          .validate(raw)
          .flatMap:
            case value: Json.Obj if value.fields.map(_._1) == Chunk("to_activate") =>
              value.get("to_activate") match
                case Some(Json.Arr(values)) =>
                  values.foldLeft[Either[String, Set[String]]](Right(Set.empty))((result, value) =>
                    result.flatMap(names =>
                      value match
                        case Json.Str(name) => Right(names + name)
                        case _              => Left("to_activate must contain only strings")
                    )
                  )
                case _ => Left("to_activate must be an array of strings")
            case _ => Left("Expected exactly the to_activate field")
      def execute(names: Set[String]): IO[AgentError, String] =
        activation
          .modify(current =>
            current.replaceMetaActive(names) match
              case Left(error)    => (Left(error), current)
              case Right(updated) => (Right(updated), updated)
          )
          .absolve
          .map(updated =>
            Json
              .Obj(
                "active_groups" -> Json
                  .Arr(Chunk.fromIterable(names.toList.sorted.map(Json.Str(_)))),
                "notes" -> Json.Str(toolGroupNotes(updated))
              )
              .toJson
          )
      def encodeOutput(out: String): String = out

  private def runOrdinaryWithHub(
      messages: Chunk[Msg],
      hub: Hub[AgentEvent],
      complete: AgentResult => IO[AgentError, Unit]
  ): ZIO[Scope, AgentError, AgentResult] =
    makeRunTools.zip(Ref.make[Option[ChatUsage]](None)).flatMap { case (runTools, usage) =>
      runWithHub(RunEntry.Fresh(messages), hub, runTools, usage, complete)(result =>
        ZIO.succeed((result, ()))
      )
        .map(_._1)
    }

  private enum RunEntry:
    case Fresh(messages: Chunk[Msg])
    case Resume(result: AgentResult, pending: PendingActing, admitted: Toolkit)

  private enum CompletionMode:
    case Ordinary
    case Structured(response: Ref[Option[Json]])

    def captured: UIO[Option[Json]] = this match
      case Ordinary             => ZIO.none
      case Structured(response) => response.get

  private def runWithHub[A](
      entry: RunEntry,
      eventHub: Hub[AgentEvent],
      runTools: RunTools,
      usageRef: Ref[Option[ChatUsage]],
      complete: AgentResult => IO[AgentError, Unit],
      requestConfig: LlmConfig = llmConfig,
      completionMode: CompletionMode = CompletionMode.Ordinary
  )(
      prepareResult: AgentResult => IO[AgentError, (AgentResult, A)]
  ): ZIO[Scope, AgentError, (AgentResult, A)] =
    for
      _ <- entry match
        case RunEntry.Fresh(_) => requireFreshRun
        case _                 => ZIO.unit
      p <- Promise.make[Nothing, Unit]
      _ <- interruptedPromiseRef.set(p)
      replyId <- entry match
        case RunEntry.Fresh(_)              => Random.nextUUID.map(_.toString)
        case RunEntry.Resume(_, pending, _) => ZIO.succeed(pending.context.replyId)
      systemMsgOpt =
        if config.systemPrompt.nonEmpty then Some(Msg.system(config.systemPrompt)) else None
      _ <- entry match
        case RunEntry.Resume(_, _, _) => ZIO.unit
        case RunEntry.Fresh(newMessages) =>
          stateRef.update { current =>
            val withSystem = systemMsgOpt match
              case Some(sysMsg)
                  if current.isEmpty || current.headOption.forall(_.role != Role.System) =>
                sysMsg +: current
              case _ => current
            withSystem ++ newMessages
          }
      supplied = entry match
        case RunEntry.Resume(result, pending, _) => suppliedRecords(result, pending)
        case _                                   => Chunk.empty[RecordedToolResult]
      progressRef <- Ref.make[RunProgress](entry match
        case RunEntry.Resume(_, pending, _) =>
          RunProgress.PersistedAssistant(
            pending.assistantPosition,
            PendingActing
              .completed(pending)
              .map(value => value.callOrdinal -> value.historyPosition)
              .toMap
          )
        case _ => RunProgress.Ready)
      result <- ZIO
        .acquireReleaseWith(
          eventHub.publish(AgentEvent.AgentStartEvent(agentId, replyId, agentId))
        )(_ => eventHub.publish(AgentEvent.AgentEndEvent(replyId)).unit) { _ =>
          ZIO.uninterruptibleMask { restore =>
            val execute = entry match
              case RunEntry.Fresh(_) =>
                loop(
                  0,
                  0,
                  runTools,
                  eventHub,
                  replyId,
                  requestConfig,
                  completionMode,
                  progressRef,
                  usageRef
                )
              case RunEntry.Resume(result, pending, admitted) =>
                actBatch(
                  result.reply,
                  pending.assistantPosition,
                  result.iterationCount,
                  runTools,
                  admitted,
                  eventHub,
                  pending.context,
                  requestConfig,
                  CompletionMode.Ordinary,
                  progressRef,
                  usageRef,
                  supplied,
                  Some(pending)
                )
            val prepare = execute
              .flatMap(result => usageRef.get.map(usage => result.copy(usage = usage)))
              .flatMap(prepareResult)
              .onInterrupt(reconcileDanglingToolCalls(stateRef, progressRef).unit)
            for
              _ <- entry match
                case RunEntry.Resume(_, pending, _) =>
                  pendingRef.set(None) *> stateRef.update(history =>
                    history.zipWithIndex.map { case (message, position) =>
                      if position == pending.assistantPosition && (message.generationReason == GenerateReason.ReasoningStopRequested || message.generationReason == GenerateReason.PermissionAsking)
                      then message.withGenerateReason(GenerateReason.ToolCalls)
                      else message
                    } ++ supplied.map(_.message)
                  ) *> markCompleted(
                    progressRef,
                    supplied.map(value => value.call.ordinal -> value.position).toMap
                  )
                case _ => ZIO.unit
              completed <- restore(
                raceWithSignal(prepare, ZIO.sleep(config.timeout)).flatMap:
                  case Left(_) =>
                    ZIO.fail(
                      AgentError.TimeoutError(config.timeout.toSeconds, "Agent run timed out")
                    )
                  case Right(value) => ZIO.succeed(value)
              )
              _ <- stateRef.set(completed._1.history)
              _ <- complete(completed._1)
              _ <- eventHub.publish(AgentEvent.AgentResultEvent(completed._1.reply))
            yield completed
          }
        }
        .onExit:
          case Exit.Failure(_) =>
            // Only the caller and this run's signal identify cancellation; worker failures do not.
            ZIO.descriptor
              .zip(p.isDone)
              .flatMap:
                case (owner, requested) if owner.interrupters.nonEmpty || requested =>
                  reconcileDanglingToolCalls(stateRef, progressRef).unit
                case _ => ZIO.unit
          case _ => ZIO.unit
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
      completedIterations: Int,
      emptyThinkingRetries: Int,
      runTools: RunTools,
      eventHub: Hub[AgentEvent],
      replyId: String,
      requestConfig: LlmConfig,
      completionMode: CompletionMode,
      progressRef: Ref[RunProgress],
      usageRef: Ref[Option[ChatUsage]]
  ): ZIO[Scope, AgentError, AgentResult] =
    if completedIterations >= config.maxIterations then
      for
        _ <- eventHub.publish(
          AgentEvent.ExceedMaxItersEvent(replyId, config.maxIterations, completedIterations)
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
      yield AgentResult(
        reply = maxIterMsg,
        history = finalHistory,
        iterationCount = completedIterations
      )
    else
      val iteration = completedIterations + 1
      runTools.snapshot.flatMap: activeToolkit =>
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
                          iteration,
                          emptyThinkingRetries + 1,
                          runTools,
                          eventHub,
                          replyId,
                          requestConfig,
                          completionMode,
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
                  persistAssistant(assistantMsg, progressRef).flatMap(position =>
                    restore(
                      actBatch(
                        assistantMsg,
                        position,
                        iteration,
                        runTools,
                        activeToolkit,
                        eventHub,
                        context,
                        requestConfig,
                        completionMode,
                        progressRef,
                        usageRef
                      )
                    )
                  )

                case TurnOutput.Interrupted(partial) =>
                  persistAssistant(partial, progressRef) *> handleInterrupt(
                    stateRef,
                    iteration,
                    progressRef
                  )

            def requestModel(history: Chunk[Msg]): ZIO[Scope, AgentError, AgentResult] =
              raceWithInterrupt(
                modelTurn(
                  history,
                  activeToolkit.definitions,
                  requestConfig,
                  replyId,
                  eventHub,
                  usageRef
                )
              ).flatMap:
                case Left(_) => handleInterrupt(stateRef, iteration, progressRef)
                case Right((TurnOutput.Interrupted(partial), _)) =>
                  persistAssistant(partial, progressRef) *> handleInterrupt(
                    stateRef,
                    iteration,
                    progressRef
                  )
                case Right((rawOutput, context)) =>
                  raceWithInterrupt(ZIO.scoped(applyReasoningHook(rawOutput, context))).flatMap:
                    case Left(_) => handleInterrupt(stateRef, iteration, progressRef)
                    case Right(ReasoningOutcome.Discard) =>
                      restore(
                        loop(
                          iteration,
                          emptyThinkingRetries,
                          runTools,
                          eventHub,
                          replyId,
                          requestConfig,
                          completionMode,
                          progressRef,
                          usageRef
                        )
                      )
                    case Right(ReasoningOutcome.Accept(turnOutput)) =>
                      acceptTurn(turnOutput, context)
                    case Right(ReasoningOutcome.Suspend(message, reason)) =>
                      completionMode match
                        case CompletionMode.Structured(_) =>
                          ZIO.fail(
                            AgentError.ReasoningHookError(
                              "Structured runs cannot suspend before acting"
                            )
                          )
                        case CompletionMode.Ordinary =>
                          suspendActing(
                            message,
                            reason,
                            context,
                            iteration,
                            runTools,
                            activeToolkit,
                            requestConfig,
                            eventHub,
                            progressRef,
                            usageRef
                          )

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
          .onInterrupt(reconcileDanglingToolCalls(stateRef, progressRef).unit)

  /** Executes one already-persisted batch for both fresh reasoning and explicit resumption. */
  private def actBatch(
      assistantMsg: Msg,
      position: Int,
      iteration: Int,
      runTools: RunTools,
      activeToolkit: Toolkit,
      eventHub: Hub[AgentEvent],
      context: ModelStreamContext,
      requestConfig: LlmConfig,
      completionMode: CompletionMode,
      progressRef: Ref[RunProgress],
      usageRef: Ref[Option[ChatUsage]],
      supplied: Chunk[RecordedToolResult] = Chunk.empty,
      continuation: Option[PendingActing] = None
  ): ZIO[Scope, AgentError, AgentResult] =
    ZIO.uninterruptibleMask: restore =>
      def raceWithInterrupt[R, E, A](effect: ZIO[R, E, A]): ZIO[R, E, Either[Unit, A]] =
        interruptedPromiseRef.get.flatMap(signal => restore(raceWithSignal(effect, signal.await)))

      for
        originalGroups <- continuation match
          case Some(pending) => ZIO.succeed(pending.toolGroups)
          case None          => ZIO.foreach(runTools.activation)(_.get.map(_.groups))
        completed <- progressRef.get.map {
          case RunProgress.PersistedAssistant(_, positions) => positions.keySet
          case _                                            => Set.empty[Int]
        }
        inline = partitionToolResults(
          assistantMsg.toolUses,
          assistantMsg.toolResults,
          _.isServerTool,
          (call, result) => call.id == result.id
        )
        stage = continuation.fold[PendingActingStage](PendingActingStage.BeforeActing)(_.stage)
        inlineToProcess = stage match
          case PendingActingStage.BeforeActing        => inline.completed
          case _: PendingActingStage.AwaitingApproval => Chunk.empty
        savedStop = stage match
          case PendingActingStage.BeforeActing => None
          case PendingActingStage.AwaitingApproval(_, _, stop) =>
            stop.map(value => ActingStop(value.callOrdinal, value.reason))
        pendingCalls = inline.pending.filterNot(call =>
          call.call.isServerTool || completed.contains(call.ordinal)
        )
        directDefinitions = continuation match
          case Some(pending) => pending.admittedTools
          case None          => activeToolkit.definitions
        allLocal = assistantMsg.toolUses.nonEmpty && assistantMsg.toolResults.isEmpty &&
          assistantMsg.toolUses.forall(call => !call.isServerTool)
        direct = completionMode == CompletionMode.Ordinary && allLocal &&
          assistantMsg.toolUses.forall(call =>
            directDefinitions.exists(definition =>
              definition.name == call.name && definition.returnMode == ToolReturnMode.Direct
            )
          )
        allSafe = config.streamParallelTools && pendingCalls
          .forall(c => activeToolkit.resolve(c.call.name).forall(_.isConcurrencySafe))
        argumentPolicy =
          if assistantMsg.preservesToolArguments then ArgumentPolicy.Preserve
          else ArgumentPolicy.AllowRewrite
        execute = (call: IndexedToolCall) =>
          executeAndRecord(
            call,
            position,
            activeToolkit,
            stateRef,
            eventHub,
            context,
            argumentPolicy,
            progressRef,
            continuation.flatMap(_.permissionRequests.find(_.callOrdinal == call.ordinal))
          )
        localPhase =
          if allSafe then ZIO.foreachPar(pendingCalls)(execute)
          else ZIO.foreach(pendingCalls)(execute)
        phase = ZIO
          .foreach(inlineToProcess) { case (call, indexed) =>
            processInlineResult(call, indexed, position, context, eventHub).map(
              ActingOutcome.Observed(_)
            )
          }
          .zipWith(
            ZIO.foreach(supplied)(recorded =>
              eventHub.publish(
                AgentEvent.ToolResultStartEvent(
                  recorded.call.call.id,
                  recorded.call.call.name,
                  context = Some(context)
                )
              ) *> processRecordedResult(
                recorded,
                context,
                eventHub,
                Toolkit.ProgressDisposition.NoProgress
              ).map(ActingOutcome.Observed(_))
            )
          )(_ ++ _)
          .zipWith(localPhase)(_ ++ _)
          .onExit:
            case Exit.Failure(cause) if !cause.isInterruptedOnly =>
              finalizeFailedToolPhase(progressRef)
            case _ => ZIO.unit
        outcome <- raceWithInterrupt(phase)
        turnRes <- outcome match
          case Left(_) => handleInterrupt(stateRef, iteration, progressRef)
          case Right(decisions) =>
            val stop = (decisions.collect { case ActingOutcome.Observed(Some(value)) => value } ++
              Chunk.fromIterable(savedStop)).sortBy(_.ordinal).headOption
            val deferredRequests = decisions
              .collect { case value: ActingOutcome.Deferred => value }
              .sortBy(_.request.callOrdinal)
            deferredRequests.headOption match
              case Some(_) if completionMode != CompletionMode.Ordinary =>
                finalizeFailedToolPhase(progressRef) *>
                  ZIO.fail(
                    AgentError.InvalidConfiguration(
                      "Structured runs cannot defer tool authorization"
                    )
                  )
              case Some(deferred) =>
                restore(ZIO.unit) *> suspendAfterActing(
                  PendingActing(
                    deferred.reason,
                    context,
                    position,
                    activeToolkit.definitions,
                    originalGroups,
                    requestConfig.responseFormat,
                    permissionRequests = deferredRequests.map(_.request)
                  ),
                  iteration,
                  runTools,
                  eventHub,
                  progressRef,
                  usageRef,
                  stop
                )
              case None =>
                stop match
                  case Some(value) => stoppedToolTurn(position, iteration, value.reason, eventHub)
                  case None =>
                    (for
                      completed <-
                        if allLocal then completedLocalResults(assistantMsg, position, progressRef)
                        else ZIO.succeed(Chunk.empty)
                      _ <- ZIO.when(
                        completed.nonEmpty && completed.forall(_._2.state == ToolResultState.Denied)
                      )(
                        eventHub.publish(AgentEvent.AllToolsDeniedEvent(completed.map(_._1)))
                      )
                      result <-
                        if direct then
                          directToolTurn(completed.map(_._2), iteration, context, progressRef)
                        else ZIO.none
                    yield result).flatMap:
                      case Some(result) => ZIO.succeed(result)
                      case None =>
                        completionMode.captured.flatMap:
                          // Unresolved provider calls remain in inline.pending and continue ordinary
                          // reasoning; an explicitly captured structured response retains precedence.
                          case value if inline.pending.isEmpty || value.nonEmpty =>
                            completedToolTurn(position, iteration)
                          case _ =>
                            restore(
                              loop(
                                iteration,
                                0,
                                runTools,
                                eventHub,
                                context.replyId,
                                requestConfig,
                                completionMode,
                                progressRef,
                                usageRef
                              )
                            )
      yield turnRes

  /** Commits a partial checkpoint only after all admitted work and processor scopes have joined. */
  private def suspendAfterActing(
      pending: PendingActing,
      iteration: Int,
      runTools: RunTools,
      eventHub: Hub[AgentEvent],
      progressRef: Ref[RunProgress],
      usageRef: Ref[Option[ChatUsage]],
      stop: Option[ActingStop]
  ): IO[AgentError, AgentResult] =
    for
      history <- stateRef.get
      positions <- progressRef.get.flatMap {
        case RunProgress.PersistedAssistant(position, completed)
            if position == pending.assistantPosition =>
          ZIO.succeed(completed)
        case _ =>
          ZIO.fail(AgentError.InvalidConfiguration("The acting completion ledger is unavailable"))
      }
      groups <- ZIO.foreach(runTools.activation)(_.get.map(_.groups))
      usage  <- usageRef.get
      assistant <- ZIO
        .fromOption(history.lift(pending.assistantPosition))
        .orElseFail(AgentError.InvalidConfiguration("The suspended assistant is missing"))
      reply = assistant.withGenerateReason(GenerateReason.PermissionAsking)
      updated = history.zipWithIndex.map { case (message, index) =>
        if index == pending.assistantPosition then reply else message
      }
      checkpoint = pending.copy(stage =
        PendingActingStage.AwaitingApproval(
          Chunk.fromIterable(positions.toList.sortBy(_._1).map { case (ordinal, position) =>
            CompletedToolCall(ordinal, position)
          }),
          groups,
          stop.map(value => PendingActingStop(value.ordinal, value.reason))
        )
      )
      result = AgentResult(reply, updated, iteration, AgentRunState.Suspended(checkpoint), usage)
      _ <- ZIO.fromEither(validateCheckpoint(result))
      _ <- stateRef.set(updated) *> pendingRef.set(Some(result))
      _ <- ZIO.foreachDiscard(checkpoint.permissionRequests)(request =>
        ZIO.foreachDiscard(reply.toolUses.lift(request.callOrdinal))(call =>
          eventHub.publish(
            AgentEvent.PermissionAskEvent(request.requestId, call.name, request.input)
          )
        )
      )
      _ <- eventHub.publish(
        AgentEvent.RequestStopEvent(pending.reason, GenerateReason.PermissionAsking)
      )
    yield result

  /** Each selected model owns its projection, request identity and joined request scope. */
  private def modelTurn(
      history: Chunk[Msg],
      tools: Chunk[ToolDef],
      requestConfig: LlmConfig,
      replyId: String,
      eventHub: Hub[AgentEvent],
      usageRef: Ref[Option[ChatUsage]]
  ): IO[AgentError, (TurnOutput, ModelStreamContext)] =
    def invoke(
        target: ModelTarget
    ): IO[AgentError | BeforeResponseRejection, (TurnOutput, ModelStreamContext)] =
      for
        requestId <- Random.nextUUID.map(_.toString)
        context = ModelStreamContext(replyId, requestId)
        request = ModelRequestInput(
          history,
          target.config.model,
          tools,
          ModelRequestSettings(target.config.temperature, target.config.maxTokens)
        )
        candidate <- ZIO.scoped(
          ZIO.suspendSucceed(modelInputTransform.projectRequest(context, request))
        )
        validated <- ZIO.fromEither(ModelInputTransform.validateProjection(history, candidate))
        view      <- ZIO.fromEither(withSkillPrompt(validated.history))
        projectedConfig = target.config.copy(
          temperature = validated.settings.temperature,
          maxTokens = validated.settings.maxTokens
        )
        _ <- eventHub.publish(
          AgentEvent.ModelRequestStartEvent(target.config.model, 0, context = Some(context))
        )
        output <- ZIO.scoped(
          target.client.attemptTurn(view, tools, projectedConfig, eventHub, context)
        )
        _ <- usageRef
          .update(current =>
            output.message.chatUsage.fold(current)(usage =>
              Some(current.fold(usage)(_.combine(usage)))
            )
          )
          .uninterruptible
        _ <- output match
          case _: TurnOutput.Interrupted => ZIO.unit
          case _ =>
            eventHub
              .publish(
                AgentEvent.ModelResponseEndEvent(
                  target.config.model,
                  output.message.chatUsage.fold(0)(_.outputTokens),
                  output.message.chatUsage.fold(0)(_.totalTokens),
                  context = Some(context)
                )
              )
              .unit
      yield (output, context)

    def exposed(cause: Cause[AgentError | BeforeResponseRejection]): Cause[AgentError] =
      cause.map:
        case rejection: BeforeResponseRejection => rejection.original
        case error: AgentError                  => error

    val compatible = fallback
      .filter(target =>
        requestConfig.responseFormat.isEmpty || (target.config.structuredOutputCapability match
          case StructuredOutputCapability.Native(support) =>
            tools.isEmpty || support == NativeToolSupport.Supported
          case _ => false)
      )
      .map(target =>
        target.copy(config = target.config.copy(responseFormat = requestConfig.responseFormat))
      )

    ZIO.uninterruptibleMask: restore =>
      def fail(cause: Cause[AgentError]): IO[AgentError, Nothing] =
        if cause.isInterrupted then ZIO.refailCause(cause)
        else
          restore(ZIO.unit).exit.flatMap:
            case Exit.Success(_)            => ZIO.refailCause(cause)
            case Exit.Failure(interruption) => ZIO.refailCause(cause ++ interruption)

      restore(invoke(ModelTarget(client, requestConfig))).exit.flatMap:
        case Exit.Success(output) => restore(ZIO.succeed(output))
        case Exit.Failure(cause) =>
          cause match
            case Cause.Fail(_: BeforeResponseRejection, _) =>
              compatible match
                case None => fail(exposed(cause))
                case Some(target) =>
                  restore(invoke(target)).exit.flatMap:
                    case Exit.Success(output) => restore(ZIO.succeed(output))
                    case Exit.Failure(fallbackCause) =>
                      fail(exposed(cause) ++ exposed(fallbackCause))
            case _ => fail(exposed(cause))

  /** Runtime-owned request enrichment follows validation of the host's projected history. */
  private def withSkillPrompt(history: Chunk[Msg]): Either[AgentError, Chunk[Msg]] =
    val prompt = skillLibrary.systemPrompt
    if prompt.isEmpty then Right(history)
    else
      val (before, remaining) = history.span(_.role != Role.System)
      remaining.headOption match
        case None => Right(Chunk(Msg.system(prompt)) ++ history)
        case Some(system) =>
          system
            .withContent(system.content :+ ContentBlock.Text(prompt))
            .map(updated => before ++ Chunk(updated) ++ remaining.drop(1))

  private enum ReasoningOutcome:
    case Accept(output: TurnOutput)
    case Discard
    case Suspend(message: Msg, reason: String)

  private def applyReasoningHook(
      output: TurnOutput,
      context: ModelStreamContext
  ): ZIO[Scope, AgentError, ReasoningOutcome] =
    output match
      case _: TurnOutput.Interrupted => ZIO.succeed(ReasoningOutcome.Accept(output))
      case _ =>
        postReasoningHook(context, output.message).flatMap:
          case ReasoningDecision.Keep    => ZIO.succeed(ReasoningOutcome.Accept(output))
          case ReasoningDecision.Discard => ZIO.succeed(ReasoningOutcome.Discard)
          case ReasoningDecision.Suspend(reason) =>
            output match
              case TurnOutput.ToolCalls(message) if reason.trim.nonEmpty =>
                ZIO.succeed(ReasoningOutcome.Suspend(message, reason))
              case _ =>
                ZIO.fail(
                  AgentError.ReasoningHookError(
                    "Suspension requires tool calls and a nonblank reason"
                  )
                )
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
                else if accepted.text.trim.isEmpty && accepted.hasReasoning then
                  TurnOutput.ThinkingOnly(accepted)
                else TurnOutput.FinalText(accepted)
              ZIO.succeed(ReasoningOutcome.Accept(classified))

  private def suspendActing(
      message: Msg,
      reason: String,
      context: ModelStreamContext,
      iteration: Int,
      runTools: RunTools,
      admitted: Toolkit,
      requestConfig: LlmConfig,
      eventHub: Hub[AgentEvent],
      progressRef: Ref[RunProgress],
      usageRef: Ref[Option[ChatUsage]]
  ): IO[AgentError, AgentResult] =
    for
      history <- stateRef.get
      groups  <- ZIO.foreach(runTools.activation)(_.get.map(_.groups))
      usage   <- usageRef.get
      reply = message.withGenerateReason(GenerateReason.ReasoningStopRequested)
      pending = PendingActing(
        reason,
        context,
        history.size,
        admitted.definitions,
        groups,
        requestConfig.responseFormat
      )
      result = AgentResult(
        reply,
        history :+ reply,
        iteration,
        AgentRunState.Suspended(pending),
        usage
      )
      _ <- ZIO.fromEither(validateCheckpoint(result))
      _ <- persistAssistant(reply, progressRef)
      _ <- pendingRef.set(Some(result))
      _ <- eventHub.publish(
        AgentEvent.RequestStopEvent(reason, GenerateReason.ReasoningStopRequested)
      )
    yield result

  final private case class IndexedToolCall(call: ContentBlock.ToolUse, ordinal: Int)

  private enum ArgumentPolicy:
    case AllowRewrite, Preserve

  final private case class IndexedToolResult(result: ContentBlock.ToolResult, ordinal: Int)

  final private case class RecordedToolResult(
      call: IndexedToolCall,
      result: ContentBlock.ToolResult,
      position: Int
  ):
    def message: Msg = Msg.tool(name = Some(call.call.name), content = Chunk(result))

  private def suppliedRecords(
      result: AgentResult,
      pending: PendingActing
  ): Chunk[RecordedToolResult] =
    pending.suppliedResults.sortBy(_.callOrdinal).zipWithIndex.flatMap { case (supplied, index) =>
      result.reply.toolUses
        .lift(supplied.callOrdinal)
        .map(call =>
          RecordedToolResult(
            IndexedToolCall(call, supplied.callOrdinal),
            supplied.result.payload.applyTo(
              ContentBlock.ToolResult(
                call.id,
                Some(call.name),
                state = supplied.result.state
              )
            ),
            result.history.size + index
          )
        )
    }

  final private case class ActingStop(ordinal: Int, reason: String)

  private enum ActingOutcome:
    case Observed(stop: Option[ActingStop])
    case Deferred(request: PendingPermissionRequest, reason: String)

  final private case class ProcessedResult(result: ContentBlock.ToolResult, stop: Option[String])

  final private case class ToolResultPartition(
      completed: Chunk[(IndexedToolCall, IndexedToolResult)],
      pending: Chunk[IndexedToolCall],
      unmatched: Chunk[IndexedToolResult]
  )

  /** Consume each observation at most once, retaining pending call order and duplicate occurrences. */
  private def partitionToolResults(
      calls: Chunk[ContentBlock.ToolUse],
      results: Chunk[ContentBlock.ToolResult],
      eligible: ContentBlock.ToolUse => Boolean,
      matches: (ContentBlock.ToolUse, ContentBlock.ToolResult) => Boolean
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
                completed = partition.completed :+ (indexed -> result),
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

  /** Resolves the entire completed local batch by occurrence, including prior resumed outcomes. */
  private def completedLocalResults(
      assistant: Msg,
      position: Int,
      progressRef: Ref[RunProgress]
  ): IO[AgentError, Chunk[(ContentBlock.ToolUse, ContentBlock.ToolResult)]] =
    val invalid = AgentError.InvalidMessage("The completed local tool ledger is invalid")
    for
      positions <- progressRef.get.flatMap:
        case RunProgress.PersistedAssistant(saved, completed)
            if saved == position && completed.keySet == assistant.toolUses.indices.toSet &&
              completed.values.toSet.size == completed.size =>
          ZIO.succeed(completed)
        case _ => ZIO.fail(invalid)
      history <- stateRef.get
      persisted <- ZIO
        .fromOption(
          history
            .lift(position)
            .filter(message =>
              message.role == Role.Assistant &&
                message.toolUses.map(call => (call.id, call.name)) ==
                assistant.toolUses.map(call => (call.id, call.name))
            )
        )
        .orElseFail(invalid)
      results <- ZIO.foreach(persisted.toolUses.zipWithIndex): (call, ordinal) =>
        ZIO
          .fromOption(
            positions
              .get(ordinal)
              .flatMap(index =>
                history.lift(index).filter(message => index > position && message.role == Role.Tool)
              )
          )
          .orElseFail(invalid)
          .flatMap(message =>
            message.toolResults match
              case Chunk(result) if result.id == call.id && result.name.contains(call.name) =>
                ZIO.succeed(call -> result)
              case _ => ZIO.fail(invalid)
          )
    yield results

  private def directToolTurn(
      results: Chunk[ContentBlock.ToolResult],
      iteration: Int,
      context: ModelStreamContext,
      progressRef: Ref[RunProgress]
  ): IO[AgentError, Option[AgentResult]] =
    val output = results.flatMap(_.output)
    val presentational = output.forall:
      case _: ContentBlock.Text | _: ContentBlock.Image | _: ContentBlock.Audio |
          _: ContentBlock.Video | _: ContentBlock.Data =>
        true
      case _ => false
    if results.forall(_.state == ToolResultState.Success) && presentational then
      val content = if output.isEmpty then Chunk(ContentBlock.Text("(no output)")) else output
      val reply = Msg
        .assistant(content, id = Some(context.replyId), name = Some(config.name))
        .withGenerateReason(GenerateReason.ToolReturnDirect)
      persistAssistant(reply, progressRef) *> stateRef.get
        .map(completed => Some(AgentResult(reply, completed, iteration)))
    else ZIO.none

  private def stoppedToolTurn(
      position: Int,
      iteration: Int,
      reason: String,
      eventHub: Hub[AgentEvent]
  ): IO[AgentError, AgentResult] =
    for
      _ <- stateRef.update(_.zipWithIndex.map { case (message, index) =>
        if index == position then
          message.withGenerateReason(GenerateReason.ActingStopRequested.wireName)
        else message
      })
      result <- completedToolTurn(position, iteration)
      _ <- eventHub.publish(AgentEvent.RequestStopEvent(reason, GenerateReason.ActingStopRequested))
    yield result.copy(state = AgentRunState.Stopped(reason))

  private def processedResult(result: ContentBlock.ToolResult): UIO[ProcessedResult] =
    ZIO
      .scoped(ZIO.suspendSucceed(toolResultProcessor.processDecision(result)))
      .catchAll(_ => ZIO.succeed(ToolResultDecision.Continue(ToolResultPayload.fromResult(result))))
      .map:
        case ToolResultDecision.Continue(payload) => ProcessedResult(payload.applyTo(result), None)
        case ToolResultDecision.Stop(payload, reason) =>
          ProcessedResult(payload.applyTo(result), Some(reason))

  private def processInlineResult(
      call: IndexedToolCall,
      indexed: IndexedToolResult,
      assistantPosition: Int,
      context: ModelStreamContext,
      eventHub: Hub[AgentEvent]
  ): UIO[Option[ActingStop]] =
    ZIO.uninterruptibleMask: restore =>
      for
        completed <- restore(processedResult(indexed.result))
        _ <- stateRef.update(_.zipWithIndex.map { case (message, position) =>
          if position == assistantPosition then
            message.mapToolResultAt(indexed.ordinal)(_ => completed.result)
          else message
        })
        _ <- publishInlineResult(call.call, completed.result, context, eventHub)
      yield completed.stop.map(ActingStop(call.ordinal, _))

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
      eventHub: Hub[AgentEvent],
      progress: Toolkit.ProgressDisposition = Toolkit.ProgressDisposition.NoProgress
  ): UIO[Unit] =
    for
      _ <- progress match
        case Toolkit.ProgressDisposition.NoProgress =>
          publishResultPayload(call, result, context, eventHub)
        case Toolkit.ProgressDisposition.ProgressPublished => ZIO.unit
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

  private def publishResultPayload(
      call: ContentBlock.ToolUse,
      result: ContentBlock.ToolResult,
      context: ModelStreamContext,
      eventHub: Hub[AgentEvent]
  ): UIO[Unit] =
    ZIO.foreachDiscard(result.output):
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

  private def authorizeTool(
      call: ContentBlock.ToolUse,
      callOrdinal: Int,
      requestId: String,
      preparedInput: Json,
      context: ModelStreamContext,
      toolkit: Toolkit,
      argumentPolicy: ArgumentPolicy
  ): UIO[ToolAuthorization] =
    val decision =
      if changesReasoningInput(call, preparedInput, argumentPolicy) then
        ZIO.succeed(
          ToolAuthorization.Denied("Cannot modify arguments carrying provider reasoning metadata")
        )
      else
        ZIO
          .scoped(
            ZIO.suspendSucceed:
              val admittedAccess = toolkit
                .resolve(call.name)
                .map(tool =>
                  if tool.isReadOnly then AdmittedToolAccess.ReadOnly
                  else AdmittedToolAccess.Mutating
                )
              toolAuthorizer.authorize(
                ToolAuthorizationRequest(
                  requestId,
                  context,
                  withToolInput(call, preparedInput),
                  Some(callOrdinal)
                ),
                admittedAccess
              )
          )
          .map: result =>
            result match
              case ToolAuthorization.Approved(input) =>
                JsonBoundary.validate(input) match
                  case Left(error) => ToolAuthorization.Denied(s"Invalid approved input: $error")
                  case Right(_) if changesReasoningInput(call, input, argumentPolicy) =>
                    ToolAuthorization.Denied(
                      "Cannot modify arguments carrying provider reasoning metadata"
                    )
                  case Right(_) =>
                    toolkit
                      .validateApprovedInput(call.name, input)
                      .fold(
                        error =>
                          ToolAuthorization.Denied(s"Invalid approved input: ${error.message}"),
                        _ => result
                      )
              case ToolAuthorization.Deferred(reason) if reason.trim.isEmpty =>
                ToolAuthorization.Denied("Deferred authorization requires a nonblank reason")
              case _ => result
    decision.foldCauseZIO(
      cause =>
        if cause.isInterrupted then ZIO.refailCause(cause).orDie
        else
          ZIO.succeed(
            ToolAuthorization.Denied(
              cause.failureOption.map(_.reason).getOrElse("Tool authorization failed")
            )
          )
      ,
      ZIO.succeed(_)
    )

  private def changesReasoningInput(
      call: ContentBlock.ToolUse,
      input: Json,
      argumentPolicy: ArgumentPolicy
  ): Boolean =
    input != call.input &&
      (argumentPolicy == ArgumentPolicy.Preserve || call.metadata.contains("thoughtSignature") ||
        call.metadata.contains("reasoningDetails"))

  private def withToolInput(call: ContentBlock.ToolUse, input: Json): ContentBlock.ToolUse =
    if input == call.input then call
    else
      call.copy(
        input = input,
        content = None,
        metadata = call.metadata.updated("agentscope_original_tool_input", call.input)
      )

  private def executeAndRecord(
      indexed: IndexedToolCall,
      assistantPosition: Int,
      toolkit: Toolkit,
      stateRef: Ref[Chunk[Msg]],
      eventHub: Hub[AgentEvent],
      context: ModelStreamContext,
      argumentPolicy: ArgumentPolicy,
      progressRef: Ref[RunProgress],
      savedRequest: Option[PendingPermissionRequest]
  ): UIO[ActingOutcome] =
    ZIO.uninterruptibleMask: restore =>
      val call = indexed.call
      val start = eventHub.publish(
        AgentEvent.ToolResultStartEvent(call.id, call.name, context = Some(context))
      )
      def observe(
          authorization: Either[
            AgentError.InvalidMessage,
            (PendingPermissionRequest, ToolAuthorization)
          ]
      ): UIO[Either[ActingOutcome, Toolkit.ObservedResult]] = authorization match
        case Right((request, ToolAuthorization.Deferred(reason))) =>
          ZIO.left(ActingOutcome.Deferred(request, reason))
        case Left(error) =>
          start.as(
            Right(
              Toolkit.ObservedResult(
                ToolExecutionResult(error.message, ToolResultState.Error),
                Toolkit.ProgressDisposition.NoProgress
              )
            )
          )
        case Right((_, ToolAuthorization.Denied(reason))) =>
          start.as(
            Right(
              Toolkit.ObservedResult(
                ToolExecutionResult(reason, ToolResultState.Denied),
                Toolkit.ProgressDisposition.NoProgress
              )
            )
          )
        case Right((_, ToolAuthorization.Approved(input))) =>
          val approved = withToolInput(call, input)
          start *> stateRef.update(_.zipWithIndex.map { case (message, position) =>
            if position == assistantPosition then
              message.mapToolUseAt(indexed.ordinal)(_ => approved)
            else message
          }) *> restore(
            ZIO.scoped(
              ToolEventPort
                .open(eventHub)
                .flatMap(events =>
                  toolkit.executeObserved(
                    call.name,
                    input,
                    ToolInvocation.Active(context, approved, indexed.ordinal, events)
                  )(payload =>
                    publishResultPayload(
                      call,
                      payload.applyTo(ContentBlock.ToolResult(call.id, Some(call.name))),
                      context,
                      eventHub
                    )
                  )
                )
            )
          ).map(Right(_))
      for
        authorization <- ZIO
          .fromEither(toolkit.prepareInput(call.name, call.input))
          .flatMap: input =>
            for
              requestId <- savedRequest match
                case Some(request) => ZIO.succeed(request.requestId)
                case None          => Random.nextUUID.map(_.toString)
              request = PendingPermissionRequest(indexed.ordinal, requestId, input)
              decision <- restore(
                authorizeTool(
                  call,
                  indexed.ordinal,
                  requestId,
                  input,
                  context,
                  toolkit,
                  argumentPolicy
                )
              )
            yield (request, decision)
          .either
        _ <- savedRequest match
          case Some(request) =>
            authorization match
              case Right((_, ToolAuthorization.Approved(_))) =>
                eventHub
                  .publish(AgentEvent.PermissionDecisionEvent(request.requestId, allowed = true))
                  .unit
              case Right((_, ToolAuthorization.Denied(reason))) =>
                eventHub
                  .publish(
                    AgentEvent.PermissionDecisionEvent(
                      request.requestId,
                      allowed = false,
                      reason = Some(reason)
                    )
                  )
                  .unit
              case _ => ZIO.unit
          case None => ZIO.unit
        observation <- observe(authorization)
        outcome <- observation match
          case Left(deferred) => ZIO.succeed(deferred)
          case Right(observed) =>
            val resultBlock = observed.result.payload.applyTo(
              ContentBlock.ToolResult(call.id, Some(call.name), state = observed.result.state)
            )
            val toolMsg = Msg.tool(name = Some(call.name), content = Chunk(resultBlock))
            for
              position <- stateRef.modify(history => (history.size, history :+ toolMsg))
              _        <- markCompleted(progressRef, Map(indexed.ordinal -> position))
              stop <- restore(
                processRecordedResult(
                  RecordedToolResult(indexed, resultBlock, position),
                  context,
                  eventHub,
                  observed.progress
                )
              )
            yield ActingOutcome.Observed(stop)
      yield outcome

  private def processRecordedResult(
      recorded: RecordedToolResult,
      context: ModelStreamContext,
      eventHub: Hub[AgentEvent],
      progress: Toolkit.ProgressDisposition
  ): UIO[Option[ActingStop]] =
    ZIO.uninterruptibleMask: restore =>
      for
        completed <- restore(processedResult(recorded.result))
        _ <- stateRef.update(_.zipWithIndex.map { case (message, index) =>
          if index == recorded.position then message.mapToolResults(_ => completed.result)
          else message
        })
        _ <- publishCompletedResult(
          recorded.call.call,
          completed.result,
          context,
          eventHub,
          progress
        )
      yield completed.stop.map(ActingStop(recorded.call.ordinal, _))

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
    case PersistedAssistant(position: Int, completed: Map[Int, Int])
    case Recovered(message: Msg)

  private def persistAssistant(message: Msg, progressRef: Ref[RunProgress]): UIO[Int] =
    ZIO.uninterruptible(
      stateRef
        .modify(history => (history.size, history :+ message))
        .tap(position => progressRef.set(RunProgress.PersistedAssistant(position, Map.empty)))
    )

  /** Updated under the same interruption mask as each persisted observation. */
  private def markCompleted(progressRef: Ref[RunProgress], positions: Map[Int, Int]): UIO[Unit] =
    progressRef.update:
      case RunProgress.PersistedAssistant(position, completed) =>
        RunProgress.PersistedAssistant(position, completed ++ positions)
      case other => other

  private def pendingLocalCalls(
      history: Chunk[Msg],
      position: Int,
      completed: Map[Int, Int]
  ): Chunk[IndexedToolCall] =
    history
      .lift(position)
      .fold(Chunk.empty[IndexedToolCall]): assistant =>
        partitionToolResults(
          assistant.toolUses,
          assistant.toolResults,
          _.isServerTool,
          (call, result) => call.id == result.id
        ).pending.filterNot(call => call.call.isServerTool || completed.contains(call.ordinal))

  private def finalizeFailedToolPhase(progressRef: Ref[RunProgress]): UIO[Unit] =
    ZIO.uninterruptible:
      progressRef.get.flatMap:
        case RunProgress.PersistedAssistant(position, completed) =>
          stateRef
            .modify { history =>
              val pending = pendingLocalCalls(history, position, completed)
              val observations = pending.map: indexed =>
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
              (
                pending.zipWithIndex.map { case (call, offset) =>
                  call.ordinal -> (history.size + offset)
                }.toMap,
                history ++ observations
              )
            }
            .flatMap(markCompleted(progressRef, _))
        case _ => ZIO.unit

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
                case RunProgress.PersistedAssistant(position, completed) =>
                  pendingLocalCalls(history, position, completed)
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
            .tap(message => pendingRef.set(None) *> progressRef.set(RunProgress.Recovered(message)))
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
      completionObserver: TurnCompletionObserver = TurnCompletionObserver.ignore,
      toolGroups: Option[ToolGroups] = None,
      skills: SkillCatalog | SkillLibrary = SkillCatalog.empty,
      fallback: Option[ModelTarget] = None
  ): IO[AgentError, ReActAgent] =
    for
      _ <- ZIO.fromEither(config.validated)
      library = SkillLibrary.from(skills)
      _ <- ZIO.fromEither(library.catalog.validateGroups(toolGroups.getOrElse(ToolGroups.empty)))
      stateRef              <- Ref.make(initialHistory)
      pendingRef            <- Ref.make(Option.empty[AgentResult])
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
      pendingRef,
      interruptedPromiseRef,
      agentId,
      runLock,
      postReasoningHook,
      toolResultProcessor,
      toolAuthorizer,
      historyPreparer,
      modelInputTransform,
      completionObserver,
      toolGroups,
      library,
      fallback
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
      private val _completionObserver: TurnCompletionObserver = TurnCompletionObserver.ignore,
      private val _toolGroups: Option[ToolGroups] = None,
      private val _skills: SkillCatalog | SkillLibrary = SkillCatalog.empty,
      private val _fallback: Option[ModelTarget] = None
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
    def toolGroups(groups: ToolGroups): Builder   = copy(_toolGroups = Some(groups))
    def skills(catalog: SkillCatalog | SkillLibrary): Builder = copy(_skills = catalog)
    def model(c: LlmClient): Builder                          = copy(_client = Some(c))
    def client(c: LlmClient): Builder                         = copy(_client = Some(c))
    def llmConfig(c: LlmConfig): Builder                      = copy(_llmConfig = c)
    def fallback(target: ModelTarget): Builder                = copy(_fallback = Some(target))
    def initialHistory(h: Chunk[Msg]): Builder                = copy(_initialHistory = h)

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
            _completionObserver,
            _toolGroups,
            _skills,
            _fallback
          )
        )

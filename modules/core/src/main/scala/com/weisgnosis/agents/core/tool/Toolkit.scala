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
package com.weisgnosis.agents.core.tool

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.ToolResultState
import zio.*
import zio.json.ast.Json
import zio.stream.ZStream

/** Immutable registration and existentially typed execution without runtime casts. */
final class Toolkit private (
    val tools: Map[String, ToolBinding],
    dispatchObserver: ToolDispatchObserver = ToolDispatchObserver.ignore
):
  /** Registry transformations retain this observer; overlay borrows only the other registry. */
  def withDispatchObserver(observer: ToolDispatchObserver): Toolkit =
    new Toolkit(tools, observer)

  def register(tool: Tool[Any, ?, ?]): Either[AgentError, Toolkit] = register(
    ToolBinding.Local(tool)
  )

  def register(tool: ToolBinding): Either[AgentError, Toolkit] =
    val name = tool.definition.name
    if tools.contains(name) then
      Left(AgentError.ToolExecutionError(name, s"Tool collision: '$name' is already registered"))
    else Right(updated(tool))

  def withTool(tool: Tool[Any, ?, ?]): Either[AgentError, Toolkit] = register(tool)

  /** Explicit host replacement by the tool's declared name; register rejects collisions instead. */
  def updated(tool: Tool[Any, ?, ?]): Toolkit = updated(ToolBinding.Local(tool))

  def updated(tool: ToolBinding): Toolkit =
    new Toolkit(tools.updated(tool.definition.name, tool), dispatchObserver)

  /** Explicit right-side precedence between two already valid registries. */
  def overlay(other: Toolkit): Toolkit = new Toolkit(tools ++ other.tools, dispatchObserver)

  /** Select exact registered values without reconstructing or renaming bindings. */
  def filterNames(keep: String => Boolean): Toolkit =
    new Toolkit(tools.filter((name, _) => keep(name)), dispatchObserver)

  override def equals(other: Any): Boolean = other match
    case value: Toolkit => tools == value.tools
    case _              => false

  override def hashCode(): Int = tools.hashCode()

  def ++(other: Toolkit): Either[AgentError, Toolkit] =
    other.tools.values.foldLeft[Either[AgentError, Toolkit]](Right(this)) { (result, tool) =>
      result.flatMap(_.register(tool))
    }

  def resolve(name: String): Option[ToolBinding] = tools.get(name)
  def contains(name: String): Boolean            = tools.contains(name)
  def definitions: Chunk[ToolDef] =
    Chunk.fromIterable(tools.toList.sortBy(_._1).map(_._2.definition))
  def size: Int        = tools.size
  def isEmpty: Boolean = tools.isEmpty

  /** Unknown names retain legacy dispatch behavior; attached policies travel with tool values. */
  def prepareInput(name: String, input: Json): Either[AgentError.InvalidMessage, Json] =
    tools
      .get(name)
      .fold[Either[AgentError.InvalidMessage, Json]](Right(input))(_.fixedArguments.prepare(input))

  def validateApprovedInput(name: String, input: Json): Either[AgentError.InvalidMessage, Unit] =
    tools
      .get(name)
      .fold[Either[AgentError.InvalidMessage, Unit]](Right(()))(
        _.fixedArguments.validateApproved(input)
      )

  /** Validate a private normalization view; preserve exact prepared/approved dispatch input. */
  def validateSchemaInput(name: String, input: Json): IO[AgentError.InvalidMessage, Unit] =
    tools
      .get(name)
      .fold[IO[AgentError.InvalidMessage, Unit]](ZIO.unit)(binding =>
        ToolSchemaValidator.validate(binding.inputValidationSchema, input)
      )

  /** Tool failures and defects are observations; interruption remains cancellation. */
  def executeSafe(name: String, rawArgs: Json): UIO[ToolExecutionResult] =
    executeSafe(name, rawArgs, ToolInvocation.Detached)

  def executeSafe(
      name: String,
      rawArgs: Json,
      invocation: ToolInvocation
  ): UIO[ToolExecutionResult] =
    executeObserved(name, rawArgs, invocation)(_ => ZIO.unit).map(_.result)

  /** Only external bindings require validation before authorization or suspension. */
  def validateExternalInput(name: String, input: Json): Either[AgentError.InvalidMessage, Unit] =
    tools.get(name) match
      case Some(external: ToolBinding.External) => external.validateInput(input)
      case _                                    => Right(())

  private[core] def executeObserved(
      name: String,
      rawArgs: Json,
      invocation: ToolInvocation
  )(observe: ToolResultPayload => UIO[Unit]): UIO[Toolkit.ObservedResult] =
    dispatchObserved(name, rawArgs, invocation)(observe).map:
      case Toolkit.Dispatch.Completed(result) => result
      case Toolkit.Dispatch.AwaitingExternal(_) =>
        observation(s"Tool '$name' requires external execution; use the agent suspension protocol.")

  private[core] def dispatchObserved(
      name: String,
      rawArgs: Json,
      invocation: ToolInvocation
  )(observe: ToolResultPayload => UIO[Unit]): UIO[Toolkit.Dispatch] =
    ZIO.suspendSucceed:
      tools.get(name) match
        case None =>
          ZIO.succeed(
            Toolkit.Dispatch.Completed(observation(s"Tool '$name' not found in toolkit."))
          )
        case Some(ToolBinding.Local(tool)) =>
          dispatchObserver
            .around(name, invocation)(
              executeTyped(tool, rawArgs, invocation, observe)
            )(_.result.state)
            .map(Toolkit.Dispatch.Completed(_))
        case Some(external: ToolBinding.External) =>
          ZIO.suspendSucceed:
            ZIO
              .fromEither(external.validateInput(rawArgs))
              .zipLeft(validateSchemaInput(name, rawArgs))
              .as(Toolkit.Dispatch.AwaitingExternal(rawArgs))
              .catchAllCause: cause =>
                if cause.isInterrupted then ZIO.refailCause(cause).orDie
                else
                  ZIO.succeed(
                    Toolkit.Dispatch.Completed(
                      observation(
                        cause.failureOption
                          .map(_.message)
                          .getOrElse("External tool argument validation failed")
                      )
                    )
                  )

  private def observation(message: String): Toolkit.ObservedResult =
    Toolkit.ObservedResult(
      ToolExecutionResult(message, ToolResultState.Error),
      Toolkit.ProgressDisposition.NoProgress
    )

  private def executeTyped[In, Out](
      tool: Tool[Any, In, Out],
      input: Json,
      invocation: ToolInvocation,
      observe: ToolResultPayload => UIO[Unit]
  ): UIO[Toolkit.ObservedResult] =
    ZIO.uninterruptibleMask: restore =>
      for
        progress <- Ref.make(Toolkit.ProgressDisposition.NoProgress)
        exit <- restore(
          ZIO.scoped:
            ZIO.suspendSucceed:
              ZIO
                .fromEither(tool.fixedArguments.prepare(input))
                .flatMap: prepared =>
                  ToolSchemaValidator.validate(tool.inputValidationSchema, prepared) *>
                    ZIO
                      .fromEither(tool.decodeInput(prepared))
                      .mapError(error =>
                        AgentError.ToolExecutionError(
                          tool.definition.name,
                          s"Invalid arguments for '${tool.definition.name}': $error"
                        )
                      )
                .flatMap: decoded =>
                  consume(
                    tool.streamWithContext(decoded, invocation),
                    tool.definition.name,
                    progress,
                    observe
                  )
        ).exit
        result <- exit match
          case Exit.Failure(cause) => normalizeFailure(tool, cause)
          case Exit.Success(output) =>
            ZIO
              .suspendSucceed:
                ZIO
                  .fromEither(tool.encodeResultChecked(output))
                  .map: result =>
                    result.state match
                      case ToolResultState.Success | ToolResultState.Error => result
                      case other =>
                        ToolExecutionResult(
                          s"Tool '${tool.definition.name}' returned unsupported execution state '$other'; only Success and Error are allowed.",
                          ToolResultState.Error
                        )
              .catchAllCause(cause => normalizeFailure(tool, cause))
        disposition <- progress.get
      yield Toolkit.ObservedResult(result, disposition)

  private def consume[Out](
      updates: ZStream[Any, Throwable, ToolExecutionUpdate[Out]],
      name: String,
      progress: Ref[Toolkit.ProgressDisposition],
      observe: ToolResultPayload => UIO[Unit]
  ): ZIO[Scope, Throwable, Out] =
    def accept(completed: Option[Out], update: ToolExecutionUpdate[Out]): Task[Option[Out]] =
      completed match
        case Some(_) =>
          ZIO.fail(
            AgentError.ToolExecutionError(name, "Tool stream emitted an update after Completed")
          )
        case None =>
          update match
            case ToolExecutionUpdate.Completed(value) => ZIO.some(value)
            case ToolExecutionUpdate.Progress(payload) =>
              val publish =
                if payload.output.isEmpty then ZIO.unit
                else
                  ZIO.uninterruptibleMask: restoreObserver =>
                    restoreObserver(ZIO.suspendSucceed(observe(payload))) *>
                      progress.set(Toolkit.ProgressDisposition.ProgressPublished)
              publish.as(Option.empty[Out])

    // The public channel pull retains complete Causes; ZStream.toPull uses a typed-error fold.
    updates.channel.toPull.flatMap: pull =>
      def loop(completed: Option[Out]): Task[Out] =
        pull.flatMap:
          case Right(chunk) => ZIO.foldLeft(chunk)(completed)(accept).flatMap(loop)
          case Left(_) =>
            completed match
              case Some(value) => ZIO.succeed(value)
              case None =>
                ZIO.fail(AgentError.ToolExecutionError(name, "Tool stream ended without Completed"))
      loop(None)

  private def normalizeFailure[In, Out](
      tool: Tool[Any, In, Out],
      cause: Cause[Throwable]
  ): UIO[ToolExecutionResult] =
    if cause.isInterrupted then ZIO.refailCause(cause).orDie
    else
      ZIO.succeed(
        ToolExecutionResult(
          s"Execution failed for '${tool.definition.name}': ${cause.failureOption.orElse(cause.dieOption).map(error => Option(error.getMessage).getOrElse(error.toString)).getOrElse("Interrupted tool execution")}",
          ToolResultState.Error
        )
      )

object Toolkit:
  private[core] enum Dispatch:
    case Completed(result: ObservedResult)
    case AwaitingExternal(input: Json)

  private[core] enum ProgressDisposition:
    case NoProgress, ProgressPublished

  final private[core] case class ObservedResult(
      result: ToolExecutionResult,
      progress: ProgressDisposition
  )

  val empty: Toolkit = new Toolkit(Map.empty)

  def apply(): Toolkit = empty

  def single(tool: Tool[Any, ?, ?]): Toolkit = single(ToolBinding.Local(tool))

  def single(tool: ToolBinding): Toolkit = new Toolkit(Map(tool.definition.name -> tool))

  /** Host-supplied keys must match declared names; aliases require an explicitly renamed tool. */
  def fromMap(tools: Map[String, Tool[Any, ?, ?]]): Either[AgentError, Toolkit] =
    fromBindings(tools.view.mapValues(ToolBinding.Local(_)).toMap)

  def fromBindings(tools: Map[String, ToolBinding]): Either[AgentError, Toolkit] =
    tools.toList.sortBy(_._1).find((name, tool) => name != tool.definition.name) match
      case Some((name, tool)) =>
        Left(
          AgentError.InvalidConfiguration(
            s"Tool registry key '$name' differs from declared name '${tool.definition.name}'"
          )
        )
      case None => Right(new Toolkit(tools))

  def apply(first: Tool[Any, ?, ?], rest: Tool[Any, ?, ?]*): Either[AgentError, Toolkit] =
    (first +: rest).foldLeft[Either[AgentError, Toolkit]](Right(empty)) { (result, tool) =>
      result.flatMap(_.register(tool))
    }

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
package com.weisgnosis.agents.harness.subagent

import com.weisgnosis.agents.core.agent.*
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolResultState}
import com.weisgnosis.agents.core.tool.{
  ToolAuthorizer,
  Toolkit,
  ToolResultPayload,
  ToolResultProcessor
}
import com.weisgnosis.agents.harness.storage.StorageAddress
import java.util.UUID
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object SubagentRoutedConsumptionSpec extends ZIOSpecDefault:
  private enum Route:
    case Session, SessionWait, Catalog, SessionCatalog, Directory
  private enum Change:
    case Remove, Rebind, Redact, Rewrite, Missing

  private val id: SubagentTaskId = SubagentTaskId(new UUID(0L, 12L))
  private val record: SubagentTerminalRecord =
    SubagentTerminalRecord(id, SubagentTerminalOutcome.Completed("exact routed λ"))
  private val taskResult: SubagentToolResult = SubagentToolResult.Task(
    id.value.toString,
    SubagentToolState.Completed(SubagentToolText("exact routed λ", 0))
  )

  private def terminal(owner: String): IO[AgentError, SubagentTerminalStore] =
    for
      backend <- SubagentTerminalStoreSpec.fixture
      address <- ZIO.fromEither(StorageAddress.from(Chunk(owner), "terminal"))
    yield SubagentTerminalStore.make(backend.store, address)

  private def accepted[A](
      effect: ZIO[Scope, AgentError, TaskResourceAdmission[A]]
  ): ZIO[Scope, AgentError, TaskResource[A]] =
    effect.flatMap:
      case TaskResourceAdmission.Accepted(resource) => ZIO.succeed(resource)
      case TaskResourceAdmission.Closed =>
        ZIO.fail(AgentError.InvalidConfiguration("Closed fixture owner"))

  final private case class Fixture(
      adapter: SubagentDeliveryMiddleware,
      toolkit: Toolkit,
      input: Json,
      name: String,
      expected: String,
      target: SubagentTerminalStore,
      parent: SubagentTerminalStore,
      decoy: SubagentTerminalStore,
      remove: IO[AgentError, Unit],
      rebind: IO[AgentError, Unit]
  )

  private def fixture(
      route: Route,
      beforeLookup: UIO[Unit] = ZIO.unit
  ): ZIO[Scope, AgentError, Fixture] =
    for
      supervisor <- TaskSupervisor.open[AgentResult]
      parent     <- SubagentSpawner.root(supervisor)
      factory <- ZIO.fromEither(
        SubagentFactory.make(
          AgentConfig(),
          LlmConfig("fixture"),
          Toolkit.empty,
          SubagentToolBoundary(),
          SubagentToolBoundary(),
          _ => ZIO.succeed(Toolkit.empty),
          _ =>
            ZIO.succeed(
              LlmClient.mockEffectful((_, _, _) =>
                ZIO.succeed(TurnOutput.FinalText(Msg.assistant("unused")))
              )
            ),
          ToolAuthorizer.allow
        )
      )
      target      <- terminal("target")
      parentStore <- terminal("parent")
      decoy       <- terminal("decoy")
      _           <- target.publish(record)
      _ <- decoy.publish(record.copy(outcome = SubagentTerminalOutcome.Completed("decoy")))
      parentRegistry <- SubagentTaskRegistry.openWith(
        (_, _, _) => ZIO.fail(AgentError.InvalidConfiguration("Parent fixture cannot launch")),
        4,
        Some(parentStore)
      )
      adapter <- SubagentDeliveryMiddleware.make(parentRegistry)
      binding = Some(SubagentTerminalBinding(target))
      selected <- route match
        case Route.Session | Route.SessionWait =>
          accepted(SubagentSession.open(parent, factory, terminalBinding = binding)).map(resource =>
            val wait = route == Route.SessionWait
            val input =
              if wait then Json.Obj("task_ids" -> Json.Str(id.value.toString))
              else Json.Obj("task_id"          -> Json.Str(id.value.toString))
            val expected = if wait then
              val output: SubagentWaitToolResult =
                SubagentWaitToolResult.Completed(Chunk(taskResult))
              output.toJson
            else taskResult.toJson
            (
              adapter.toolsForSession(resource.value),
              input,
              if wait then "wait_async_results" else "agent_task",
              expected,
              ZIO.unit,
              ZIO.unit
            )
          )
        case Route.Catalog =>
          SubagentCatalog
            .open(parent, Chunk(SubagentEntry("worker", "Explicit worker", factory, binding)))
            .map(catalog =>
              val output: SubagentCatalogToolResult =
                SubagentCatalogToolResult.Agent("worker", taskResult)
              (
                adapter.toolsForCatalog(catalog),
                Json
                  .Obj("agent_id" -> Json.Str("worker"), "task_id" -> Json.Str(id.value.toString)),
                "agent_task",
                output.toJson,
                ZIO.unit,
                ZIO.unit
              )
            )
        case Route.SessionCatalog =>
          accepted(
            SubagentSessionCatalog.open(
              parent,
              Chunk(SubagentEntry("worker", "Explicit worker", factory, binding))
            )
          ).map(resource =>
            val output: SubagentCatalogToolResult =
              SubagentCatalogToolResult.Agent("worker", taskResult)
            (
              adapter.toolsForSessionCatalog(resource.value),
              Json.Obj("agent_id" -> Json.Str("worker"), "task_id" -> Json.Str(id.value.toString)),
              "agent_task",
              output.toJson,
              ZIO.unit,
              ZIO.unit
            )
          )
        case Route.Directory =>
          for
            original <- accepted(SubagentSession.open(parent, factory, terminalBinding = binding))
            replacement <- accepted(
              SubagentSession.open(
                parent,
                factory,
                terminalBinding = Some(SubagentTerminalBinding(decoy))
              )
            )
            directory <- SubagentSessionDirectory.open()
            sessionId <- directory
              .register(original)
              .flatMap:
                case SubagentSessionRegistration.Accepted(value) => ZIO.succeed(value)
                case other =>
                  ZIO.fail(AgentError.InvalidConfiguration(s"Fixture registration failed: $other"))
            redirected <- Ref.make(Option.empty[SubagentSession])
            routed = new SubagentSessionDirectory:
              def register(
                  resource: TaskResource[SubagentSession]
              ): UIO[SubagentSessionRegistration] = directory.register(resource)
              def lookup(value: SubagentSessionId): UIO[Option[SubagentSession]] = beforeLookup *> (
                if value == sessionId then
                  redirected.get.flatMap(
                    _.fold(directory.lookup(value))(session => ZIO.some(session))
                  )
                else directory.lookup(value)
              )
              def list: UIO[Chunk[SubagentSessionId]] = directory.list
              def remove(value: SubagentSessionId): UIO[SubagentSessionRemoval] =
                directory.remove(value)
              def close: UIO[Unit] = directory.close
            output: SubagentSessionToolResult = SubagentSessionToolResult.Session(
              sessionId.value.toString,
              taskResult
            )
          yield (
            adapter.toolsForDirectory(routed),
            Json.Obj(
              "session_id" -> Json.Str(sessionId.value.toString),
              "task_id"    -> Json.Str(id.value.toString)
            ),
            "agent_task",
            output.toJson,
            directory.remove(sessionId).unit,
            directory.remove(sessionId).unit *> redirected.set(Some(replacement.value))
          )
      (tools, input, name, expected, remove, rebind) = selected
    yield Fixture(adapter, tools, input, name, expected, target, parentStore, decoy, remove, rebind)

  private def run(
      fixture: Fixture,
      prior: LocalToolResultObserver,
      processor: ToolResultProcessor = ToolResultProcessor.identity
  ): ZIO[Scope, AgentError, AgentResult] =
    val client = LlmClient.mockEffectful((history, _, _) =>
      ZIO.succeed(
        if history.flatMap(_.toolResults).isEmpty then
          TurnOutput.ToolCalls(
            Msg.assistant(Chunk(ContentBlock.ToolUse("same", fixture.name, fixture.input)))
          )
        else TurnOutput.FinalText(Msg.assistant("done"))
      )
    )
    fixture.adapter
      .configure(
        ReActAgent.builder().client(client).toolkit(fixture.toolkit).toolResultProcessor(processor),
        priorLocalToolResultObserver = prior
      )
      .build
      .flatMap(_.run(Msg.user("inspect")))

  private def lookupClosure: ZIO[TestEnvironment & Scope, AgentError, TestResult] =
    ZIO.scoped:
      for
        entered        <- Promise.make[Nothing, Unit]
        releaseLookup  <- Promise.make[Nothing, Unit]
        cleaning       <- Promise.make[Nothing, Unit]
        releaseCleanup <- Promise.make[Nothing, Unit]
        cleaned        <- Ref.make(false)
        current <- fixture(
          Route.Directory,
          (entered.succeed(()) *> releaseLookup.await)
            .ensuring(
              cleaning.succeed(()) *> releaseCleanup.await *> cleaned.set(true)
            )
            .unit
        )
        running <- ZIO.uninterruptibleMask: restore =>
          for
            worker <- restore(run(current, LocalToolResultObserver.ignore)).exit.forkScoped
            _      <- ZIO.addFinalizer(releaseLookup.succeed(()) *> releaseCleanup.succeed(()).unit)
          yield worker
        _                 <- entered.await
        earlyLookupClose  <- Live.live(current.adapter.close.timeout(100.millis))
        _                 <- releaseLookup.succeed(())
        _                 <- cleaning.await
        earlyCleanupClose <- Live.live(current.adapter.close.timeout(100.millis))
        _                 <- releaseCleanup.succeed(())
        result            <- running.join
        cleanupFinished   <- cleaned.get
        _                 <- current.adapter.close
      yield assertTrue(
        earlyLookupClose.isEmpty,
        earlyCleanupClose.isEmpty,
        cleanupFinished,
        result.isSuccess
      )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentRoutedConsumptionSpec")(
    test(
      "explicit public bound routes consume only the persisted exact target wrapper; detached calls remain observational"
    ):
      ZIO
        .foreach(
          Chunk(
            Route.Session,
            Route.SessionWait,
            Route.Catalog,
            Route.SessionCatalog,
            Route.Directory
          )
        ): route =>
          ZIO.scoped:
            for
              current         <- fixture(route)
              detached        <- current.toolkit.executeSafe(current.name, current.input)
              pendingDetached <- current.target.pending
              seen            <- Ref.make(Chunk.empty[String])
              result <- run(
                current,
                LocalToolResultObserver(observation =>
                  current.target.pending
                    .mapError(error => AgentError.CompletionObservationError(error.message))
                    .flatMap(pending =>
                      seen.update(_ :+ observation.original.outputText) *>
                        ZIO
                          .fail(
                            AgentError
                              .CompletionObservationError("Premature routed acknowledgement")
                          )
                          .unless(pending == Chunk(record))
                          .unit
                    )
                )
              )
              pending      <- current.target.pending
              parentStamp  <- current.parent.acknowledgement(id)
              decoyPending <- current.decoy.pending
              callbacks    <- seen.get
            yield assertTrue(
              detached.output == current.expected,
              pendingDetached == Chunk(record),
              callbacks == Chunk(current.expected),
              result.history.flatMap(_.toolResults).map(_.outputText) == Chunk(current.expected),
              pending.isEmpty,
              parentStamp.isEmpty,
              decoyPending.size == 1
            )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test(
      "directory removal or rebinding cannot retarget a receipt; rewritten or redacted wrappers remain pending"
    ):
      ZIO
        .foreach(
          Chunk(Change.Remove, Change.Rebind, Change.Redact, Change.Rewrite, Change.Missing)
        ): change =>
          ZIO.scoped:
            for
              current <- fixture(Route.Directory)
              effective =
                if change == Change.Missing then
                  current.copy(input = current.input match
                    case Json.Obj(fields) =>
                      Json.Obj(
                        fields.map((name, value) =>
                          name -> (if name == "task_id" then Json.Str(new UUID(0L, 999L).toString)
                                   else value)
                        )
                      )
                    case other => other)
                else current
              processor = ToolResultProcessor(result =>
                ZIO.succeed(change match
                  case Change.Redact => ToolResultPayload(Chunk(ContentBlock.Text("redacted")))
                  case Change.Rewrite =>
                    val output: SubagentSessionToolResult =
                      SubagentSessionToolResult.Session("different", taskResult)
                    ToolResultPayload(Chunk(ContentBlock.Text(output.toJson)))
                  case _ => ToolResultPayload.fromResult(result))
              )
              result <- run(
                effective,
                LocalToolResultObserver(_ =>
                  (change match
                    case Change.Remove => current.remove
                    case Change.Rebind => current.rebind
                    case _             => ZIO.unit
                  ).mapError(error => AgentError.CompletionObservationError(error.message))
                ),
                processor
              )
              targetPending <- current.target.pending
              decoyPending  <- current.decoy.pending
              parentStamp   <- current.parent.acknowledgement(id)
            yield assertTrue(
              result.history.flatMap(_.toolResults).map(_.state) == Chunk(
                if change == Change.Missing then ToolResultState.Error else ToolResultState.Success
              ),
              targetPending.isEmpty == (change == Change.Remove || change == Change.Rebind),
              decoyPending.size == 1,
              parentStamp.isEmpty
            )
        .flatMap(checks => lookupClosure.map(check => checks.foldLeft(check)(_ && _)))
  )

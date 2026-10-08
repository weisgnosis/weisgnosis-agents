/*
 * Copyright 2024-2026 WeisGnosis (https://weisgnosis.com)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.weisgnosis.agents.harness.subagent

import com.weisgnosis.agents.core.agent.{AgentConfig, AgentResult, AgentSnapshot}
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, Role, ToolHistory, ToolResultState}
import com.weisgnosis.agents.core.tool.{Tool, ToolAuthorization, ToolAuthorizer, ToolDef, Toolkit}
import com.weisgnosis.agents.harness.storage.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object SubagentNamedSessionsSpec extends ZIOSpecDefault:
  private enum SaveFailure:
    case Conflict, Uncertain

  private def accepted[A](
      effect: ZIO[Scope, AgentError, TaskResourceAdmission[A]]
  ): ZIO[Scope, AgentError, TaskResource[A]] = effect.flatMap:
    case TaskResourceAdmission.Accepted(resource) => ZIO.succeed(resource)
    case TaskResourceAdmission.Closed =>
      ZIO.fail(AgentError.InvalidConfiguration("Fixture owner closed"))

  private def factory(
      client: ZIO[Scope, AgentError, LlmClient],
      tools: Toolkit = Toolkit.empty,
      authorizer: ToolAuthorizer = ToolAuthorizer.allow
  ): IO[AgentError, SubagentFactory] = ZIO.fromEither(
    SubagentFactory.make(
      AgentConfig(),
      LlmConfig("named-session"),
      Toolkit.empty,
      SubagentToolBoundary(),
      SubagentToolBoundary(),
      _ => ZIO.succeed(tools),
      _ => client,
      authorizer
    )
  )

  private def install(
      root: SubagentSpawner[AgentResult],
      store: SubagentNamedSessionStore,
      authority: SubagentNamedSessions.Authority,
      configured: SubagentFactory,
      parent: String = "parent"
  ): ZIO[Scope, AgentError, TaskResource[SubagentNamedSessions]] =
    accepted(
      SubagentNamedSessions.open(
        root,
        parent,
        Chunk(SubagentEntry("worker", "Host configured worker", configured)),
        store,
        authority
      )
    )

  private def exclusive: UIO[SubagentNamedSessions.Authority] =
    Ref
      .make(Set.empty[StorageAddress])
      .map(held =>
        new SubagentNamedSessions.Authority:
          def acquire(
              key: SubagentNamedSessionKey,
              address: StorageAddress
          ): ZIO[Scope, AgentError, Unit] =
            ZIO.acquireRelease(
              held
                .modify(values => (!values.contains(address), values + address))
                .flatMap(acquired =>
                  ZIO
                    .fail(AgentError.StorageError("Fixture claim already held"))
                    .unless(acquired)
                    .unit
                )
            )(_ => held.update(_ - address))
      )

  private def command(
      session: SubagentSession,
      submit: ZIO[Scope, AgentError, SubagentTaskSubmission]
  ): ZIO[Scope, AgentError, Exit[AgentError, AgentResult]] = submit.flatMap:
    case SubagentTaskSubmission.Accepted(id) =>
      session.executions
        .await(id)
        .someOrFail(AgentError.InvalidConfiguration("Fixture execution missing"))
    case other => ZIO.fail(AgentError.InvalidConfiguration(s"Fixture command rejected: $other"))

  private def send(session: SubagentSession, text: String): ZIO[Scope, AgentError, AgentResult] =
    command(session, session.send(Msg.user(text))).flatMap(exit => exit)

  private def key(label: String = "label"): IO[AgentError, SubagentNamedSessionKey] =
    ZIO.fromEither(SubagentNamedSessionKey.from("parent", "worker", label))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentNamedSessionsSpec")(
    test(
      "actual named spawn/send reuse history, isolate keys and reopen idle data with a fresh trusted factory"
    ):
      for
        backend    <- SubagentTerminalStoreSpec.fixture
        store      <- ZIO.fromEither(SubagentNamedSessionStore.make(backend.store, Chunk("named")))
        authority  <- exclusive
        supervisor <- TaskSupervisor.open[AgentResult]
        root       <- SubagentSpawner.root(supervisor)
        histories  <- Ref.make(Chunk.empty[Chunk[String]])
        builds     <- Ref.make(0)
        configured <- factory(
          builds
            .update(_ + 1)
            .as(
              LlmClient.mockEffectful((history, _, _) =>
                histories.update(_ :+ history.filter(_.role == Role.User).map(_.text)) *>
                  ZIO.succeed(TurnOutput.FinalText(Msg.assistant("saved reply")))
              )
            )
        )
        original <- install(root, store, authority, configured)
        tools = SubagentTools.forNamedSessions(original.value)
        input = (label: String, prompt: String) =>
          Json.Obj(
            "agent_id" -> Json.Str("worker"),
            "label"    -> Json.Str(label),
            "prompt"   -> Json.Str(prompt)
          )
        one         <- tools.executeSafe("agent_spawn", input("label", "one"))
        two         <- tools.executeSafe("agent_send", input("label", "two"))
        other       <- tools.executeSafe("agent_spawn", input("other", "isolated"))
        first       <- original.value.getOrOpen("worker", "label")
        same        <- original.value.getOrOpen("worker", "label")
        beforeClose <- builds.get
        _           <- original.close
        freshCalls  <- Ref.make(0)
        fresh <- factory(
          ZIO.succeed(
            LlmClient.mockEffectful((history, _, _) =>
              freshCalls.update(_ + 1) *> histories.update(
                _ :+ history.filter(_.role == Role.User).map(_.text)
              ) *>
                ZIO.succeed(TurnOutput.FinalText(Msg.assistant("fresh factory")))
            )
          )
        )
        reopened   <- install(root, store, authority, fresh)
        restored   <- reopened.value.getOrOpen("worker", "label")
        beforeSend <- freshCalls.get
        reply      <- send(restored, "three")
        separate   <- install(root, store, authority, fresh, "other-parent")
        isolated   <- separate.value.getOrOpen("worker", "label")
        _          <- send(isolated, "different parent")
        observed   <- histories.get
      yield assertTrue(
        one.state == ToolResultState.Success,
        two.state == ToolResultState.Success,
        other.state == ToolResultState.Success,
        one.output.fromJson[SubagentNamedToolResult].exists(_.label == "label"),
        first eq same,
        beforeClose == 2,
        beforeSend == 0,
        reply.reply.text == "fresh factory",
        observed == Chunk(
          Chunk("one"),
          Chunk("one", "two"),
          Chunk("isolated"),
          Chunk("one", "two", "three"),
          Chunk("different parent")
        )
      )
    ,
    test(
      "suspended reopen does not replay model/tools; rejected send preserves pending while a claimed failing resume saves recovery"
    ):
      for
        backend <- SubagentTerminalStoreSpec.fixture
        store   <- ZIO.fromEither(SubagentNamedSessionStore.make(backend.store, Chunk("suspended")))
        authority  <- exclusive
        supervisor <- TaskSupervisor.open[AgentResult]
        root       <- SubagentSpawner.root(supervisor)
        modelCalls <- Ref.make(0)
        toolCalls  <- Ref.make(0)
        definition = ToolDef("lookup", "Read host data", Json.Obj())
        tool       = Tool.make(definition)(_ => toolCalls.update(_ + 1).as("old execution"))
        deferred = ToolAuthorizer(_ => ZIO.succeed(ToolAuthorization.Deferred("Host confirmation")))
        configured <- factory(
          ZIO.succeed(
            LlmClient.mockEffectful((_, _, _) =>
              modelCalls
                .update(_ + 1)
                .as(
                  TurnOutput.ToolCalls(
                    Msg.assistant(Chunk(ContentBlock.ToolUse("call", "lookup", Json.Obj())))
                  )
                )
            )
          ),
          Toolkit.single(tool),
          deferred
        )
        first         <- install(root, store, authority, configured)
        session       <- first.value.getOrOpen("worker", "label")
        pending       <- send(session, "work")
        selected      <- key()
        savedPending  <- store.load(selected)
        rejected      <- command(session, session.send(Msg.user("must not replace checkpoint")))
        afterRejected <- store.load(selected)
        _             <- first.close
        currentCalls  <- Ref.make(0)
        currentTool = Tool.make(definition)(_ => toolCalls.update(_ + 1).as("new execution"))
        resumeFailure = AgentError.InvalidConfiguration(
          "Model failed after the checkpoint was claimed"
        )
        currentClient = LlmClient.mockEffectful((_, _, _) =>
          currentCalls.update(_ + 1) *> ZIO.fail(resumeFailure)
        )
        current <- factory(
          ZIO.succeed(currentClient),
          Toolkit.single(currentTool),
          ToolAuthorizer(_ => ZIO.succeed(ToolAuthorization.Denied("Current host policy")))
        )
        reopened          <- install(root, store, authority, current)
        restored          <- reopened.value.getOrOpen("worker", "label")
        hydrated          <- store.load(selected)
        beforeResumeModel <- currentCalls.get
        beforeResumeTools <- toolCalls.get
        failed            <- command(restored, restored.resume())
        afterResume       <- store.load(selected)
        originalModels    <- modelCalls.get
        resumedModels     <- currentCalls.get
        noTools           <- toolCalls.get
        savedRecovery = afterResume.snapshot match
          case AgentSnapshot.Idle(history) =>
            ToolHistory.inspect(history).isComplete &&
            history
              .flatMap(_.toolResults)
              .exists(result =>
                result.id == "call" && result.name.contains("lookup") &&
                  result.state == ToolResultState.Denied &&
                  result.output == Chunk(ContentBlock.Text("Current host policy"))
              )
          case AgentSnapshot.Suspended(_) => false
      yield assertTrue(
        pending.isSuspended,
        savedPending.snapshot == AgentSnapshot.Suspended(pending),
        rejected.isFailure,
        afterRejected.snapshot == savedPending.snapshot,
        hydrated.snapshot == savedPending.snapshot,
        beforeResumeModel == 0,
        beforeResumeTools == 0,
        failed.causeOption.exists(_.failures.contains(resumeFailure)),
        savedRecovery,
        originalModels == 1,
        resumedModels == 1,
        noTools == 0
      )
    ,
    test(
      "per-key initialization joins waiters; uncertain/conflicting saves poison further execution without replay"
    ):
      ZIO
        .foreach(Chunk(SaveFailure.Conflict, SaveFailure.Uncertain)): mode =>
          ZIO.scoped:
            for
              backend  <- SubagentTerminalStoreSpec.fixture
              armed    <- Ref.make(false)
              attempts <- Ref.make(0)
              failure = AgentError.StorageError("Snapshot acknowledgement lost")
              failing = new VersionedJsonStore:
                def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
                  backend.store.read(address)
                def compareAndSet(address: StorageAddress, expected: ExpectedRevision, value: Json)
                    : IO[AgentError, ConditionalWrite] =
                  armed.get.flatMap:
                    case false => backend.store.compareAndSet(address, expected, value)
                    case true =>
                      attempts.update(_ + 1) *>
                        (mode match
                          case SaveFailure.Uncertain =>
                            backend.store.compareAndSet(address, expected, value) *> ZIO
                              .fail(failure)
                          case SaveFailure.Conflict => ZIO.succeed(ConditionalWrite.Conflict))
              store      <- ZIO.fromEither(SubagentNamedSessionStore.make(failing, Chunk("poison")))
              supervisor <- TaskSupervisor.open[AgentResult]
              root       <- SubagentSpawner.root(supervisor)
              claims     <- Ref.make(0)
              releases   <- Ref.make(0)
              entered    <- Promise.make[Nothing, Unit]
              release    <- Promise.make[Nothing, Unit]
              authority = new SubagentNamedSessions.Authority:
                def acquire(key: SubagentNamedSessionKey, address: StorageAddress)
                    : ZIO[Scope, AgentError, Unit] =
                  ZIO.acquireRelease(claims.update(_ + 1))(_ => releases.update(_ + 1)) *>
                    entered.succeed(()).unit *> release.await
              calls <- Ref.make(0)
              runFailure = AgentError.InvalidConfiguration("Run failed before snapshot save")
              configured <- factory(
                ZIO.succeed(
                  LlmClient.mockEffectful((_, _, _) =>
                    calls.update(_ + 1) *> (mode match
                      case SaveFailure.Conflict =>
                        ZIO.succeed(TurnOutput.FinalText(Msg.assistant("committed execution")))
                      case SaveFailure.Uncertain => ZIO.fail(runFailure))
                  )
                )
              )
              owned         <- install(root, store, authority, configured)
              first         <- owned.value.getOrOpen("worker", "label").forkScoped
              _             <- entered.await
              second        <- owned.value.getOrOpen("worker", "label").forkScoped
              _             <- release.succeed(())
              session       <- first.join
              same          <- second.join
              initialClaims <- claims.get
              _             <- armed.set(true)
              failed        <- command(session, session.send(Msg.user("one")))
              poisoned      <- command(session, session.send(Msg.user("must not execute")))
              count         <- calls.get
              saves         <- attempts.get
              preserved = mode match
                case SaveFailure.Conflict =>
                  failed.causeOption
                    .exists(_.failures.exists(_.message.contains("snapshot conflicted")))
                case SaveFailure.Uncertain =>
                  failed.causeOption.exists(cause =>
                    cause.failures.count(_ == failure) == 1 && cause.failures
                      .count(_ == runFailure) == 1
                  )
              _        <- owned.close
              released <- releases.get
            yield assertTrue(
              session eq same,
              initialClaims == 1,
              preserved,
              poisoned.causeOption.exists(_.failures.exists(_.message.contains("explicit reopen"))),
              count == 1,
              saves == 1,
              released == 1
            )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test(
      "cancelled initialization resolves shared waiters after joined claim cleanup and requires explicit retirement"
    ):
      ZIO.scoped:
        for
          backend <- SubagentTerminalStoreSpec.fixture
          store <- ZIO.fromEither(SubagentNamedSessionStore.make(backend.store, Chunk("cancelled")))
          supervisor <- TaskSupervisor.open[AgentResult]
          root       <- SubagentSpawner.root(supervisor)
          claims     <- Ref.make(0)
          released   <- Ref.make(0)
          blocked    <- Ref.make(true)
          entered    <- Promise.make[Nothing, Unit]
          cleaning   <- Promise.make[Nothing, Unit]
          release    <- Promise.make[Nothing, Unit]
          defect = new IllegalStateException("Named initializer cleanup marker")
          authority = new SubagentNamedSessions.Authority:
            def acquire(
                key: SubagentNamedSessionKey,
                address: StorageAddress
            ): ZIO[Scope, AgentError, Unit] =
              blocked.get.flatMap: held =>
                ZIO.acquireRelease(claims.update(_ + 1))(_ =>
                  if held then
                    cleaning.succeed(()) *> release.await *> released.update(_ + 1) *> ZIO.die(
                      defect
                    )
                  else released.update(_ + 1)
                ) *> (if held then entered.succeed(()) *> ZIO.never else ZIO.unit)
          builds <- Ref.make(0)
          configured <- factory(
            builds
              .update(_ + 1)
              .as(
                LlmClient.mockFunction((_, _) =>
                  TurnOutput.FinalText(Msg.assistant("not called during hydration"))
                )
              )
          )
          owner      <- install(root, store, authority, configured)
          controller <- ZIO.fiberId
          first <- ZIO.uninterruptibleMask: restore =>
            for
              fiber <- restore(owner.value.getOrOpen("worker", "label")).forkScoped
              _     <- ZIO.addFinalizer(release.succeed(()).unit)
            yield fiber
          _               <- entered.await
          second          <- owner.value.getOrOpen("worker", "label").forkScoped
          _               <- first.interruptAsFork(controller)
          _               <- cleaning.await
          firstHeld       <- first.poll
          secondHeld      <- second.poll
          _               <- release.succeed(())
          firstExit       <- first.await
          secondExit      <- second.await
          retainedFailure <- owner.value.getOrOpen("worker", "label").exit
          beforeRetire    <- claims.get
          beforeBuild     <- builds.get
          cleanupCount    <- released.get
          _               <- owner.value.retire("worker", "label")
          _               <- blocked.set(false)
          _               <- owner.value.getOrOpen("worker", "label")
          afterRetire     <- claims.get
          rebuilt         <- builds.get
        yield assertTrue(
          firstHeld.isEmpty,
          secondHeld.isEmpty,
          firstExit.causeOption.exists(cause =>
            cause.interruptors.contains(controller) && cause.defects.count(_ eq defect) == 1
          ),
          secondExit.causeOption.exists(_.defects.contains(defect)),
          retainedFailure.causeOption.exists(_.defects.contains(defect)),
          beforeRetire == 1,
          beforeBuild == 0,
          cleanupCount == 1,
          afterRetire == 2,
          rebuilt == 1
        )
    ,
    test(
      "caller cancellation during a held save preserves exact interruption, storage failure and joined cleanup defect"
    ):
      ZIO.scoped:
        for
          backend        <- SubagentTerminalStoreSpec.fixture
          entered        <- Promise.make[Nothing, Unit]
          releaseSave    <- Promise.make[Nothing, Unit]
          cleaning       <- Promise.make[Nothing, Unit]
          releaseCleanup <- Promise.make[Nothing, Unit]
          cleaned        <- Ref.make(0)
          saves          <- Ref.make(0)
          failure = AgentError.StorageError("Held named snapshot save failed")
          defect  = new IllegalStateException("Held named snapshot cleanup defect")
          failing = new VersionedJsonStore:
            def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
              backend.store.read(address)
            def compareAndSet(
                address: StorageAddress,
                expected: ExpectedRevision,
                value: Json
            ): IO[AgentError, ConditionalWrite] =
              expected match
                case ExpectedRevision.Absent =>
                  backend.store.compareAndSet(address, expected, value)
                case ExpectedRevision.AtRevision(_) =>
                  (saves.update(_ + 1) *> entered.succeed(()) *> releaseSave.await *> ZIO.fail(
                    failure
                  ))
                    .ensuring(
                      cleaning.succeed(()) *> releaseCleanup.await *> cleaned.update(_ + 1) *> ZIO
                        .die(defect)
                    )
          store      <- ZIO.fromEither(SubagentNamedSessionStore.make(failing, Chunk("held-save")))
          authority  <- exclusive
          supervisor <- TaskSupervisor.open[AgentResult]
          root       <- SubagentSpawner.root(supervisor)
          calls      <- Ref.make(0)
          configured <- factory(
            ZIO.succeed(
              LlmClient.mockEffectful((_, _, _) =>
                calls.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("done before save")))
              )
            )
          )
          owner   <- install(root, store, authority, configured)
          session <- owner.value.getOrOpen("worker", "label")
          unblock = releaseSave.succeed(()).unit *> releaseCleanup.succeed(()).unit
          id <- ZIO.uninterruptible:
            for
              submission <- session.send(Msg.user("one"))
              _          <- ZIO.addFinalizer(unblock)
              id <- submission match
                case SubagentTaskSubmission.Accepted(value) => ZIO.succeed(value)
                case other =>
                  ZIO.fail(
                    AgentError.InvalidConfiguration(s"Unexpected held-save admission: $other")
                  )
            yield id
          _          <- entered.await
          controller <- Promise.make[Nothing, FiberId]
          cancelling <- ZIO.uninterruptibleMask: restore =>
            for
              fiber <- restore(
                ZIO.fiberId.flatMap(controller.succeed) *> session.executions.cancel(id)
              ).forkScoped
              _ <- ZIO.addFinalizer(unblock)
            yield fiber
          caller               <- controller.await
          _                    <- TestClock.adjust(0.seconds)
          beforeSaveRelease    <- cancelling.poll
          _                    <- releaseSave.succeed(())
          _                    <- cleaning.await
          beforeCleanupRelease <- cancelling.poll
          _                    <- releaseCleanup.succeed(())
          joined               <- cancelling.join
          outcome              <- session.executions.await(id)
          poisoned             <- command(session, session.send(Msg.user("must not execute")))
          count                <- calls.get
          attempts             <- saves.get
          cleanupCount         <- cleaned.get
        yield assertTrue(
          beforeSaveRelease.isEmpty,
          beforeCleanupRelease.isEmpty,
          joined == outcome,
          outcome
            .flatMap(_.causeOption)
            .exists(cause =>
              cause.interruptors.contains(caller) &&
                cause.failures.count(_ == failure) == 1 && cause.defects.count(_ eq defect) == 1
            ),
          poisoned.causeOption.exists(_.failures.exists(_.message.contains("explicit reopen"))),
          count == 1,
          attempts == 1,
          cleanupCount == 1
        )
  )

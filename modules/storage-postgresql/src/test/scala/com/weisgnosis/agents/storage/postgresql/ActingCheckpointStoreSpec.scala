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
package com.weisgnosis.agents.storage.postgresql

import com.weisgnosis.agents.core.agent.*
import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.*
import com.weisgnosis.agents.harness.storage.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ActingCheckpointStoreSpec extends ZIOSpecDefault:
  import PostgresFixture.*

  private val input: Json = Json.Obj(
    "minimum" -> Json.Num(Long.MinValue),
    "precise" -> Json.Num(BigDecimal("9007199254740993.000000000000000001")),
    "unicode" -> Json.Str("valid \ud83d\ude80")
  )

  private def tools(execute: Json => UIO[String]): Toolkit =
    Toolkit.single(Tool.make(ToolDef("inspect", "Inspect", Json.Obj(), isReadOnly = true))(execute))

  private def suspended(toolkit: Toolkit): IO[AgentError, AgentResult] = ZIO.scoped:
    for
      client <- LlmClient.mock(
        Chunk(
          TurnOutput.ToolCalls(
            Msg
              .assistant(Chunk.fill(2)(ContentBlock.ToolUse("same", "inspect", input)))
              .withMetadata("precise", input)
          )
        )
      )
      agent <- ReActAgent.make(
        client = client,
        toolkit = toolkit,
        postReasoningHook =
          PostReasoningHook((_, _) => ZIO.succeed(ReasoningDecision.Suspend("host review")))
      )
      result <- agent.run(Msg.user("inspect"))
    yield result

  private def required(store: ActingCheckpointStore): IO[AgentError, VersionedActingCheckpoint] =
    store.read.someOrFail(AgentError.StorageError("Missing checkpoint fixture"))

  private def aggregate(result: AgentResult, version: Int = 1): IO[AgentError, Json.Obj] =
    ZIO
      .fromEither(result.toJsonAST.left.map(AgentError.StorageError.apply))
      .map(value => Json.Obj("schemaVersion" -> Json.Num(version), "checkpoint" -> value))

  def spec: Spec[TestEnvironment, Any] = suite("ActingCheckpointStoreSpec")(
    test(
      "a real suspended batch survives storage and fresh-host restore without replaying supplied results"
    ):
      fixture: (source, backend, name) =>
        for
          target <- address(name, "checkpoint")
          calls  <- Ref.make(Chunk.empty[Json])
          bindings = tools(value => calls.update(_ :+ value).as("local"))
          saved <- suspended(bindings)
          checkpoint <- ZIO.fromEither(
            PendingActing.withResults(
              saved,
              Chunk(SuppliedToolResult(0, ToolExecutionResult("external")))
            )
          )
          repository = VersionedActingCheckpointStore.make(backend, target)
          absent          <- repository.read
          published       <- repository.save(ExpectedRevision.Absent, checkpoint)
          reopenedBackend <- store(source)
          reopened = VersionedActingCheckpointStore.make(reopenedBackend, target)
          recovered  <- required(reopened)
          before     <- calls.get
          modelCalls <- Ref.make(0)
          client = LlmClient.mockEffectful((_, _, _) =>
            modelCalls.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("done")))
          )
          result <- ZIO.scoped:
            for
              agent  <- ReActAgent.make(client = client, toolkit = bindings)
              _      <- agent.restore(recovered.checkpoint)
              result <- agent.resume()
            yield result
          executed <- calls.get
          requests <- modelCalls.get
        yield assertTrue(
          absent.isEmpty,
          published == ConditionalWrite.Applied(recovered.revision),
          recovered.checkpoint == checkpoint,
          before.isEmpty,
          executed == Chunk(input),
          requests == 1,
          result.state == AgentRunState.Completed,
          result.reply.text == "done",
          result.history.flatMap(_.toolResults).map(_.outputText) == Chunk("external", "local"),
          result.history
            .find(_.toolUses.nonEmpty)
            .exists(message =>
              message.generationReason == GenerateReason.ToolCalls && message.toolUses == checkpoint.reply.toolUses && message.metadata
                .get("precise")
                .contains(input)
            )
        )
    ,
    test(
      "competing publications are conditional and lost acknowledgements never trigger automatic replay"
    ):
      fixture: (_, backend, name) =>
        for
          target     <- address(name, "race")
          checkpoint <- suspended(tools(_ => ZIO.succeed("unused")))
          first  = VersionedActingCheckpointStore.make(backend, target)
          second = VersionedActingCheckpointStore.make(backend, target)
          created <- first
            .save(ExpectedRevision.Absent, checkpoint)
            .zipPar(second.save(ExpectedRevision.Absent, checkpoint))
          initial <- required(first)
          updated = checkpoint.copy(iterationCount = checkpoint.iterationCount + 1)
          outcomes <- first
            .save(ExpectedRevision.AtRevision(initial.revision), updated)
            .zipPar(second.save(ExpectedRevision.AtRevision(initial.revision), updated))
          current         <- required(first)
          stale           <- second.save(ExpectedRevision.AtRevision(initial.revision), checkpoint)
          unchanged       <- required(first)
          uncertainTarget <- address(name, "ambiguous")
          calls           <- Ref.make(0)
          uncertainBackend = new VersionedJsonStore:
            def read(location: StorageAddress): IO[AgentError, Option[VersionedJson]] =
              backend.read(location)
            def compareAndSet(location: StorageAddress, expected: ExpectedRevision, value: Json)
                : IO[AgentError, ConditionalWrite] =
              calls.update(_ + 1) *> backend
                .compareAndSet(location, expected, value)
                .flatMap:
                  case ConditionalWrite.Applied(_) =>
                    ZIO.fail(AgentError.StorageError("Lost acknowledgement"))
                  case ConditionalWrite.Conflict => ZIO.succeed(ConditionalWrite.Conflict)
          uncertain = VersionedActingCheckpointStore.make(uncertainBackend, uncertainTarget)
          failure   <- uncertain.save(ExpectedRevision.Absent, checkpoint).exit
          attempts  <- calls.get
          recovered <- required(VersionedActingCheckpointStore.make(backend, uncertainTarget))
        yield assertTrue(
          Chunk(created._1, created._2).count(_ == ConditionalWrite.Conflict) == 1,
          Chunk(outcomes._1, outcomes._2).count(_ == ConditionalWrite.Conflict) == 1,
          current.checkpoint == updated,
          current.revision.value == initial.revision.value + 1,
          stale == ConditionalWrite.Conflict,
          unchanged == current,
          failure.causeOption.exists(_.failures.exists(_.isInstanceOf[AgentError.StorageError])),
          attempts == 1,
          recovered.checkpoint == checkpoint
        )
    ,
    test(
      "invalid persisted data cannot restore and valid data retains host binding and iteration gates"
    ):
      fixture: (_, backend, name) =>
        for
          target     <- address(name, "valid")
          checkpoint <- suspended(tools(_ => ZIO.succeed("unused")))
          repository = VersionedActingCheckpointStore.make(backend, target)
          _       <- repository.save(ExpectedRevision.Absent, checkpoint)
          initial <- required(repository)
          invalidReply = checkpoint.reply.withMetadata("bad", Json.Str("bad " + 0xd800.toChar))
          invalidUnicode = checkpoint.copy(
            reply = invalidReply,
            history = checkpoint.history.map(message =>
              if message == checkpoint.reply then invalidReply else message
            )
          )
          invalidCalls <- Ref.make(0)
          guardedBackend = new VersionedJsonStore:
            def read(location: StorageAddress): IO[AgentError, Option[VersionedJson]] =
              backend.read(location)
            def compareAndSet(location: StorageAddress, expected: ExpectedRevision, value: Json)
                : IO[AgentError, ConditionalWrite] =
              invalidCalls.update(_ + 1) *> backend.compareAndSet(location, expected, value)
          guarded = VersionedActingCheckpointStore.make(guardedBackend, target)
          invalidStates = Chunk(
            checkpoint.copy(state = AgentRunState.Completed),
            checkpoint.copy(history = Chunk.empty),
            checkpoint.copy(iterationCount = 0),
            invalidUnicode
          )
          invalidSaves <- ZIO.foreach(invalidStates)(
            guarded.save(ExpectedRevision.AtRevision(initial.revision), _).exit
          )
          unchanged        <- required(repository)
          attemptedInvalid <- invalidCalls.get
          canonical        <- aggregate(checkpoint)
          badVersion       <- aggregate(checkpoint, 2)
          invalidDomain    <- ZIO.foreach(invalidStates.take(3))(aggregate(_))
          malformed = Chunk(
            badVersion,
            Json.Obj("schemaVersion" -> Json.Num(1)),
            Json.Obj(canonical.fields :+ ("extra" -> Json.Bool(true)))
          ) ++ invalidDomain
          failures <- ZIO.foreach(malformed.zipWithIndex): (value, index) =>
            for
              invalidTarget <- address(name, s"invalid-$index")
              _             <- backend.compareAndSet(invalidTarget, ExpectedRevision.Absent, value)
              before        <- backend.read(invalidTarget)
              result        <- VersionedActingCheckpointStore.make(backend, invalidTarget).read.exit
              after         <- backend.read(invalidTarget)
            yield result.causeOption.exists(
              _.failures.exists(_.isInstanceOf[AgentError.StorageError])
            ) && before == after
          highIteration = checkpoint.copy(iterationCount = 2)
          _         <- repository.save(ExpectedRevision.AtRevision(initial.revision), highIteration)
          recovered <- required(repository)
          hostFailures <- ZIO.scoped:
            for
              client <- LlmClient.mock(Chunk.empty)
              limited <- ReActAgent.make(
                config = AgentConfig(maxIterations = 1),
                client = client,
                toolkit = tools(_ => ZIO.succeed("unused"))
              )
              limit   <- limited.restore(recovered.checkpoint).exit
              unbound <- ReActAgent.make(client = client, toolkit = Toolkit.empty)
              missing <- unbound.restore(recovered.checkpoint).exit
            yield Chunk(limit, missing)
        yield assertTrue(
          invalidSaves.forall(_.isFailure),
          attemptedInvalid == 0,
          invalidSaves.lastOption.exists(
            _.causeOption.exists(_.failures.exists(_.isInstanceOf[AgentError.StorageError]))
          ),
          unchanged == initial,
          failures.forall(identity),
          recovered.checkpoint == highIteration,
          hostFailures.forall(_.isFailure)
        )
  ) @@ TestAspect.sequential @@ TestAspect.withLiveRandom @@ TestAspect.withLiveSystem @@ TestAspect.withLiveClock @@
    TestAspect.timeout(30.seconds) @@ TestAspect.ifEnvSet("WEISGNOSIS_POSTGRES_URL")

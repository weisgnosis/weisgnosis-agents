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

import com.weisgnosis.agents.core.agent.{AgentConfig, AgentResult}
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.tool.{ToolAuthorizer, Toolkit}
import com.weisgnosis.agents.harness.storage.StorageAddress
import java.util.UUID
import zio.*
import zio.test.*

object SubagentTerminalBindingSpec extends ZIOSpecDefault:
  private enum Acquisition:
    case Catalog, Session, SessionCatalog

  private def resource[A](
      acquire: ZIO[Scope, AgentError, TaskResourceAdmission[A]]
  ): ZIO[Scope, AgentError, A] = acquire.flatMap:
    case TaskResourceAdmission.Accepted(owned) => ZIO.succeed(owned.value)
    case TaskResourceAdmission.Closed =>
      ZIO.fail(AgentError.InvalidConfiguration("Fixture owner closed"))

  private def registry(
      acquisition: Acquisition,
      parent: SubagentSpawner[AgentResult],
      factory: SubagentFactory,
      binding: Option[SubagentTerminalBinding]
  ): ZIO[Scope, AgentError, SubagentTaskRegistry] =
    val entry = SubagentEntry("same-display-name", "Explicit host binding", factory, binding)
    acquisition match
      case Acquisition.Catalog =>
        SubagentCatalog
          .open(parent, Chunk(entry))
          .flatMap(catalog =>
            ZIO
              .fromOption(catalog.lookup(entry.name))
              .orElseFail(AgentError.InvalidConfiguration("Missing catalog fixture"))
          )
      case Acquisition.Session =>
        resource(SubagentSession.open(parent, factory, terminalBinding = binding)).map(_.executions)
      case Acquisition.SessionCatalog =>
        resource(SubagentSessionCatalog.open(parent, Chunk(entry))).flatMap(catalog =>
          catalog
            .lookup(entry.name)
            .someOrFail(AgentError.InvalidConfiguration("Missing retained session fixture"))
            .map(_._2.executions)
        )

  private def submit(registry: SubagentTaskRegistry): ZIO[Scope, AgentError, SubagentTaskId] =
    registry
      .submit(Msg.user("work"))
      .flatMap:
        case SubagentTaskSubmission.Accepted(id) =>
          registry.await(id).as(id)
        case other =>
          ZIO.fail(AgentError.InvalidConfiguration(s"Unexpected fixture admission: $other"))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentTerminalBindingSpec")(
    test(
      "public catalog and reusable session factories preserve borrowed partitions and worker identities through owner closure"
    ):
      ZIO
        .foreach(Chunk(Acquisition.Catalog, Acquisition.Session, Acquisition.SessionCatalog)):
          acquisition =>
            ZIO.scoped:
              for
                backend    <- SubagentTerminalStoreSpec.fixture
                owner      <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
                supervisor <- owner.extend(TaskSupervisor.open[AgentResult])
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
                          ZIO.succeed(TurnOutput.FinalText(Msg.assistant("exact λ")))
                        )
                      ),
                    ToolAuthorizer.allow
                  )
                )
                leftAddress <- ZIO.fromEither(
                  StorageAddress.from(Chunk("binding", "left"), "terminal")
                )
                rightAddress <- ZIO.fromEither(
                  StorageAddress.from(Chunk("binding", "right"), "terminal")
                )
                leftWorker  <- ZIO.fromEither(SubagentWorkerId.from("host-left"))
                rightWorker <- ZIO.fromEither(SubagentWorkerId.from("host-right"))
                leftStore  = SubagentTerminalStore.make(backend.store, leftAddress)
                rightStore = SubagentTerminalStore.make(backend.store, rightAddress)
                left <- owner.extend(
                  registry(
                    acquisition,
                    parent,
                    factory,
                    Some(SubagentTerminalBinding(leftStore, Some(leftWorker)))
                  )
                )
                right <- owner.extend(
                  registry(
                    acquisition,
                    parent,
                    factory,
                    Some(SubagentTerminalBinding(rightStore, Some(rightWorker)))
                  )
                )
                volatile        <- owner.extend(registry(acquisition, parent, factory, None))
                leftId          <- owner.extend(submit(left))
                rightId         <- owner.extend(submit(right))
                volatileId      <- owner.extend(submit(volatile))
                _               <- left.publication(leftId)
                _               <- right.publication(rightId)
                volatileReceipt <- volatile.publication(volatileId).either
                _               <- owner.close(Exit.unit)
                leftRecords     <- SubagentTerminalStore.make(backend.store, leftAddress).list
                rightRecords    <- SubagentTerminalStore.make(backend.store, rightAddress).list
                leftMetadata    <- leftStore.taskRecord(leftId)
                rightMetadata   <- rightStore.taskRecord(rightId)
                extra = SubagentTerminalRecord(
                  SubagentTaskId(new UUID(0L, 777L)),
                  SubagentTerminalOutcome.Completed("host still owns backend")
                )
                _       <- leftStore.publish(extra)
                pending <- rightStore.pending
              yield assertTrue(
                leftRecords == Chunk(
                  SubagentTerminalRecord(
                    leftId,
                    SubagentTerminalOutcome.Completed("exact λ"),
                    Some(leftWorker)
                  )
                ),
                rightRecords == Chunk(
                  SubagentTerminalRecord(
                    rightId,
                    SubagentTerminalOutcome.Completed("exact λ"),
                    Some(rightWorker)
                  )
                ),
                leftMetadata.exists(record =>
                  record.metadata.nonEmpty && record.terminal
                    .exists(_.workerId.contains(leftWorker))
                ),
                rightMetadata.exists(record =>
                  record.metadata.nonEmpty && record.terminal
                    .exists(_.workerId.contains(rightWorker))
                ),
                volatileReceipt.isLeft,
                pending == rightRecords,
                !leftRecords.exists(_.id == volatileId),
                !rightRecords.exists(_.id == volatileId)
              )
        .map(_.foldLeft(assertTrue(true))(_ && _))
  )

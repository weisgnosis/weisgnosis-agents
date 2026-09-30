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

import com.weisgnosis.agents.core.error.AgentError
import zio.*

/** A host-held lineage capability; descriptive fields cannot reconstruct spawn authority. */
final class SubagentSpawner[A] private (
    supervisor: TaskSupervisor[A],
    lineage: Chunk[String]
):
  val depth: Int     = lineage.size - 1
  val source: String = lineage.mkString("/")

  /** Allocate one descendant whose resources and further descendants belong to its nested owner. */
  private[subagent] def ownChild[B](
      initialize: (SubagentSpawner[A], TaskSupervisor[A]) => ZIO[Scope, AgentError, B]
  ): ZIO[Scope, AgentError, TaskResourceAdmission[B]] =
    if depth >= SubagentSpawner.MaximumDepth then
      ZIO.fail(AgentError.SpawnDepthExceeded(SubagentSpawner.MaximumDepth))
    else
      supervisor.own: nested =>
        Random.nextUUID.flatMap: id =>
          val child = new SubagentSpawner(nested, lineage :+ id.toString)
          ZIO.suspendSucceed(initialize(child, nested))

  def spawn(
      task: SubagentSpawner[A] => ZIO[Scope, AgentError, A]
  ): ZIO[Scope, AgentError, TaskAdmission[A]] =
    if depth >= SubagentSpawner.MaximumDepth then
      ZIO.fail(AgentError.SpawnDepthExceeded(SubagentSpawner.MaximumDepth))
    else
      Random.nextUUID.flatMap: id =>
        val child = new SubagentSpawner(supervisor, lineage :+ id.toString)
        supervisor.attach(ZIO.suspendSucceed(task(child)))

object SubagentSpawner:
  val MaximumDepth: Int = 3

  /** Only a trusted host should create or distribute root capabilities. */
  def root[A](supervisor: TaskSupervisor[A]): UIO[SubagentSpawner[A]] =
    Random.nextUUID.map(id => new SubagentSpawner(supervisor, Chunk(id.toString)))

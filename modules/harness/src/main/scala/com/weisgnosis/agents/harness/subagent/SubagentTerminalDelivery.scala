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

final case class SubagentTerminalDeliveryReceipt(
    id: SubagentTaskId,
    acknowledgement: SubagentTerminalAcknowledgeResult
)

/** Explicit host delivery; snapshots are not exclusive claims and concurrent delivery can repeat. */
object SubagentTerminalDelivery:
  private val maximumRecords: Int = 10

  /**
   * Close the delivery's child scope before acknowledging only the included terminal records.
   * Callback or callback-scope cleanup failure, defects and interruption prevent acknowledgement.
   * Callbacks must explicitly join children whose success matters; scoped cleanup does not prove
   * child success. Earlier acknowledgements remain if
   * a later write fails; uncertain writes are not retried. Hosts own authentication and deduplication.
   */
  def deliverPending(
      registry: SubagentTaskRegistry
  )(
      deliver: Chunk[SubagentTerminalRecord] => ZIO[Scope, AgentError, Unit]
  ): IO[AgentError, Chunk[SubagentTerminalDeliveryReceipt]] =
    ZIO.suspendSucceed:
      registry.durablePendingDeliveries.flatMap: pending =>
        val batch = pending.take(maximumRecords)
        if batch.map(_.id).toSet.size != batch.size then
          ZIO.fail(
            AgentError.StorageError("Delivery snapshot contains duplicate terminal task IDs")
          )
        else if batch.isEmpty then ZIO.succeed(Chunk.empty)
        else
          ZIO.scoped(ZIO.suspendSucceed(deliver(batch))) *>
            ZIO.foreach(batch): record =>
              registry
                .acknowledgeTerminal(record.id)
                .flatMap:
                  case SubagentTerminalAcknowledgeResult.Missing =>
                    ZIO.fail(
                      AgentError.StorageError(
                        "Delivered terminal observation disappeared before acknowledgement"
                      )
                    )
                  case receipt => ZIO.succeed(SubagentTerminalDeliveryReceipt(record.id, receipt))

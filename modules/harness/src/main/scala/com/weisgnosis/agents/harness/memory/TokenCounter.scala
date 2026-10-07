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
package com.weisgnosis.agents.harness.memory

import com.weisgnosis.agents.core.model.Msg
import zio.Chunk
import zio.json.*

/**
 * A pure, replaceable token estimator. Per-message counts must be nonnegative.
 * History estimates add exactly while representable and cap at Long.MaxValue beyond that range;
 * they are budget estimates, not exact usage telemetry.
 */
trait TokenCounter:
  def count(message: Msg): Long
  final def count(history: Chunk[Msg]): Long =
    history.foldLeft(0L)((sum, message) => TokenCounter.addEstimates(sum, count(message)))

object TokenCounter:
  /** Nonnegative capped addition keeps every Int budget comparison and suffix ordering sound. */
  private[memory] def addEstimates(left: Long, right: Long): Long =
    val first  = left.max(0L)
    val second = right.max(0L)
    if first > Long.MaxValue - second then Long.MaxValue else first + second

  /** Conservative transport-size approximation; applications can inject a model tokenizer. */
  val approximate: TokenCounter = new TokenCounter:
    def count(message: Msg): Long = (message.toJson.length.toLong + 3L) / 4L

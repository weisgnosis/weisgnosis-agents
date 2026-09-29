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

import com.weisgnosis.agents.core.error.AgentError
import java.time.LocalDate
import zio.IO

/** A daily-ledger capability: curated memory can be read, but only dated entries can be appended. */
trait DailyMemoryStore:
  def readCurated: IO[AgentError, Option[String]]
  def readDaily(day: LocalDate): IO[AgentError, Option[String]]
  def appendDaily(day: LocalDate, entry: String): IO[AgentError, Unit]

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

import com.weisgnosis.agents.core.event.AgentEvent

/**
 * Runtime stream values, not a wire event schema. A successful invocation emits one Result after
 * all events and scoped execution cleanup. Result preserves the runtime state, including a
 * cooperatively interrupted AgentResult; it does not imply AgentRunState.Completed.
 */
enum AgentRunUpdate[+A]:
  case Event(value: AgentEvent) extends AgentRunUpdate[Nothing]
  case Result(value: A)

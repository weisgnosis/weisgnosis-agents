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

/**
 * Explicit borrowed terminal persistence capability. The host owns its partition and backend
 * lifetime; display names never determine addresses or worker identity. This restores observations,
 * not executable sessions, and grants no automatic routed-result consumption capability.
 */
final case class SubagentTerminalBinding(
    store: SubagentTerminalStore,
    workerId: Option[SubagentWorkerId] = None
)

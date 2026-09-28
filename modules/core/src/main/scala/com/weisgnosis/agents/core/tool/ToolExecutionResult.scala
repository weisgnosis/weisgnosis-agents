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
package com.weisgnosis.agents.core.tool

import com.weisgnosis.agents.core.model.ToolResultState
import zio.json.*

/**
 * Output and state outcome of executing a tool.
 */
final case class ToolExecutionResult(
    output: String,
    state: ToolResultState = ToolResultState.Success
)

object ToolExecutionResult:
  given JsonCodec[ToolExecutionResult] = DeriveJsonCodec.gen

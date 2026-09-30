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

import zio.json.*
import zio.json.ast.Json

/**
 * Declarative definition and JSON schema metadata of an executable tool.
 * Output schema is host-selected descriptive data; it neither validates results nor grants authority.
 *
 * Adapts the declaration contract of `io.agentscope.core.tool.AgentTool`, including getOutputSchema.
 */
final case class ToolDef(
    name: String,
    description: String,
    parametersSchema: Json,
    isConcurrencySafe: Boolean = true,
    isReadOnly: Boolean = false,
    outputSchema: Option[Json] = None
) derives JsonCodec

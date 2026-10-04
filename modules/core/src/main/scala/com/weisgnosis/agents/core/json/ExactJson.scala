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
package com.weisgnosis.agents.core.json

import com.weisgnosis.agents.core.error.AgentError
import zio.json.*

/**
 * Checked output for codecs with faithful AST conversion, including derived runtime data codecs.
 * Custom encoders must preserve values in toJsonAST; encoding cannot recover already lost data.
 */
object ExactJson:
  def encode[A: JsonEncoder](value: A): Either[AgentError.InvalidMessage, String] =
    value.toJsonAST
      .flatMap(JsonBoundary.validate)
      .map(JsonBoundary.render)
      .left
      .map(reason => AgentError.InvalidMessage(s"Cannot encode JSON: $reason"))

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
package com.weisgnosis.agents.core.error

import zio.json.*
import zio.test.*

object AgentErrorSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment, Any] = suite("AgentErrorSpec")(
    test("plan storage errors retain their dedicated type, message and JSON representation") {
      val error = AgentError.PlanStorageError("plan ownership unavailable")
      assertTrue(
        error.toJson.fromJson[AgentError.PlanStorageError] == Right(error),
        error.getMessage == error.message
      )
    },
    test("text transformation errors preserve typed wire identity and their diagnostic message") {
      val error = AgentError.TextTransformationError("host text transformation failed")
      assertTrue(
        error.toJson.fromJson[AgentError.TextTransformationError] == Right(error),
        error.getMessage == error.message,
        """{"message":"host text transformation failed"}"""
          .fromJson[AgentError.TextTransformationError] == Right(error)
      )
    },
    test("memory storage errors retain their typed message and JSON representation") {
      val error = AgentError.MemoryStorageError("daily ownership unavailable")
      assertTrue(
        error.toJson.fromJson[AgentError.MemoryStorageError] == Right(error),
        error.getMessage == error.message
      )
    }
  )

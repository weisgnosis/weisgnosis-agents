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
package com.weisgnosis.agents.core.event.javawire

import com.weisgnosis.agents.core.json.JsonBoundary
import zio.*
import zio.json.*
import zio.test.*

object JavaEventValuesSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("JavaEventValuesSpec")(
    test("confirmation null/empty rules and computed usage match actual Java serialized values"):
      JavaEventFixtures.read.map: fixtures =>
        val values =
          fixtures.filter(fixture => fixture.kind == "confirmation" || fixture.kind == "usage")
        val preserved = values.map: fixture =>
          val encoded =
            if fixture.kind == "confirmation" then
              JsonBoundary.render(fixture.output).fromJson[JavaConfirmResult].flatMap(_.toJsonAST)
            else JsonBoundary.render(fixture.output).fromJson[JavaChatUsage].flatMap(_.toJsonAST)
          JavaEventFixtures.same(encoded, fixture.output)
        val incorrectTotal =
          "{\"inputTokens\":10,\"outputTokens\":4,\"time\":0.25,\"totalTokens\":15}"
            .fromJson[JavaChatUsage]
        val unknownBehavior = "\"approve\"".fromJson[JavaPermissionBehavior]
        assertTrue(
          values.size == 3,
          preserved.forall(identity),
          incorrectTotal.isLeft,
          unknownBehavior.isLeft,
          JavaChatUsage(10, 4).totalTokens == 14
        )
  )

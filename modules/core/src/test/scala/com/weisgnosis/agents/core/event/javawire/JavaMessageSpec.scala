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

object JavaMessageSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("JavaMessageSpec")(
    test("four Java roles preserve explicit nullable fields and reject role-incompatible content"):
      JavaEventFixtures.read.map: fixtures =>
        val messages = fixtures.filter(_.kind == "message")
        val preserved = messages.map(fixture =>
          JavaEventFixtures.same(
            JsonBoundary.render(fixture.output).fromJson[JavaMessage].flatMap(_.toJsonAST),
            fixture.output
          )
        )
        val invalid = Chunk(
          "{\"role\":\"USER\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"secret\"}],\"metadata\":{}}",
          "{\"role\":\"SYSTEM\",\"content\":[{\"type\":\"data\",\"id\":\"data\",\"source\":{\"type\":\"url\",\"url\":\"https://example.invalid\"}}],\"metadata\":{}}",
          "{\"role\":\"OTHER\",\"content\":[],\"metadata\":{}}"
        )
        assertTrue(
          messages.size == 4,
          preserved.forall(identity),
          invalid.forall(_.fromJson[JavaMessage].isLeft)
        )
  )

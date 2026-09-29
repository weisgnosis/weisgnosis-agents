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
package com.weisgnosis.agents.harness.plan

import zio.*
import zio.json.*
import zio.test.*

object PosixReadCommandSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("PosixReadCommandSpec")(
    test("program identities expose fixed bare names and round-trip without executable paths") {
      assertTrue(
        PosixReadProgram.values.toList
          .map(_.name) == List("pwd", "ls", "cat", "head", "tail", "wc"),
        PosixReadProgram.values.forall(program =>
          program.toJson.fromJson[PosixReadProgram] == Right(program)
        )
      )
    },
    test("rejections have explicit stable codec variants") {
      assertTrue(
        ShellRejection.values.forall(reason =>
          reason.toJson.fromJson[ShellRejection] == Right(reason)
        )
      )
    }
  )

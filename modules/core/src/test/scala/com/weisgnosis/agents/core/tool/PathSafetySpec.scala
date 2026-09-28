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

import zio.test.*

/** 1:1 test parity suite for DangerousPathBypassTest.java and ToolBaseTest.java from AgentScope Java.
  */
object PathSafetySpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment, Any] = suite("PathSafetySpec (DangerousPathBypassTest Parity)")(
    suite("Dangerous Path Detection")(
      test("dotEnvIsDetected") {
        assertTrue(PathSafety.isDangerousPath("/home/user/.env"))
      },
      test("dotEnvLocalIsDetected") {
        assertTrue(PathSafety.isDangerousPath("/home/user/.env.local"))
      },
      test("dotEnvProductionIsDetected") {
        assertTrue(PathSafety.isDangerousPath("/home/user/.env.production"))
      },
      test("dotEnvDevelopmentLocalIsDetected") {
        assertTrue(PathSafety.isDangerousPath("/home/user/.env.development.local"))
      },
      test("dotEnvrcIsDetected") {
        assertTrue(PathSafety.isDangerousPath("/home/user/.envrc"))
      },
      test("relativeDotEnvIsDetected") {
        assertTrue(PathSafety.isDangerousPath("./.env"))
      },
      test("caseInsensitiveDotEnvIsDetected") {
        assertTrue(PathSafety.isDangerousPath("/home/user/.ENV"))
      },
      test("sensitiveDirectorySegmentsAreDetected") {
        assertTrue(
          PathSafety.isDangerousPath("/repo/.git/config"),
          PathSafety.isDangerousPath("/user/.ssh/id_rsa"),
          PathSafety.isDangerousPath("/project/.vscode/settings.json"),
          PathSafety.isDangerousPath("/project/.idea/workspace.xml")
        )
      },
      test("shellRcFilesAreDetected") {
        assertTrue(
          PathSafety.isDangerousPath("/home/user/.bashrc"),
          PathSafety.isDangerousPath("/home/user/.zshrc"),
          PathSafety.isDangerousPath("/home/user/.profile")
        )
      },
      test("tildeExpansionIsDetected") {
        assertTrue(PathSafety.isDangerousPath("~/.bashrc"))
      },
      test("ordinaryFilesAreNotDangerous") {
        assertTrue(
          !PathSafety.isDangerousPath("src/main/scala/App.scala"),
          !PathSafety.isDangerousPath("README.md"),
          !PathSafety.isDangerousPath("target/classes/Main.class")
        )
      },
      test("nullAndBlankPathsReturnFalse") {
        assertTrue(
          !PathSafety.isDangerousPath(null),
          !PathSafety.isDangerousPath(""),
          !PathSafety.isDangerousPath("   ")
        )
      }
    ),
    suite("Dangerous Command Detection")(
      test("Detects destructive and privilege-escalating commands") {
        assertTrue(
          PathSafety.isDangerousCommand("rm -rf /"),
          PathSafety.isDangerousCommand("sudo rm test.txt"),
          PathSafety.isDangerousCommand("kill -9 1234"),
          PathSafety.isDangerousCommand("chmod 777 /etc/hosts"),
          !PathSafety.isDangerousCommand("git status"),
          !PathSafety.isDangerousCommand("sbt test"),
          !PathSafety.isDangerousCommand("cat README.md")
        )
      }
    )
  )

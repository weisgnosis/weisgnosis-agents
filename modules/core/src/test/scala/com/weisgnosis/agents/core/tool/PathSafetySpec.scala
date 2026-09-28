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

import java.nio.file.Path
import com.weisgnosis.agents.core.error.AgentError
import zio.*
import zio.test.*

object PathSafetySpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment, Any] = suite("PathSafetySpec")(
    test("sensitive files, variants, directory segments, and shell configuration are denied") {
      val paths = Chunk(
        "/home/user/.env",
        "/home/user/.env.local",
        "/home/user/.env.production",
        "/home/user/.env.development.local",
        "/home/user/.envrc",
        "./.env",
        "/home/user/.ENV",
        "/repo/.git/config",
        "/user/.ssh/id_rsa",
        "/project/.vscode/settings.json",
        "/project/.idea/workspace.xml",
        "/home/user/.bashrc",
        "/home/user/.zshrc",
        "/home/user/.profile",
        "~/.bashrc",
        "/home/user/.env.custom"
      )
      for
        _       <- TestSystem.putProperty("user.home", "C:/home/test")
        results <- ZIO.foreach(paths)(PathSafety.isDangerousPath(_))
      yield assertTrue(results.forall(identity))
    },
    test("ordinary and blank paths are accepted") {
      val paths =
        Chunk("src/main/scala/App.scala", "README.md", "target/classes/Main.class", "", "   ")
      for
        _       <- TestSystem.putProperty("user.home", "C:/home/test")
        results <- ZIO.foreach(paths)(PathSafety.isDangerousPath(_))
      yield assertTrue(results.forall(!_))
    },
    test("parent traversal is rejected before normalization can erase a symbolic-link boundary") {
      val paths = Chunk("link/../id_rsa", "ordinary/../../secret", ".ssh/../public")
      for
        dangerous <- ZIO.foreach(paths)(PathSafety.isDangerousPath(_))
        canonical <- ZIO.foreach(paths)(path => PathSafety.canonicalPath(Path.of(path)).either)
      yield assertTrue(
        dangerous.forall(identity),
        canonical.forall(_.left.exists(_.isInstanceOf[AgentError.PermissionDenied]))
      )
    },
    test("destructive command screening is total") {
      assertTrue(
        PathSafety.isDangerousCommand("rm -rf /"),
        PathSafety.isDangerousCommand("sudo rm test.txt"),
        PathSafety.isDangerousCommand("kill -9 1234"),
        PathSafety.isDangerousCommand("chmod 777 /etc/hosts"),
        !PathSafety.isDangerousCommand("git status"),
        !PathSafety.isDangerousCommand("sbt test"),
        !PathSafety.isDangerousCommand("cat README.md"),
        !PathSafety.isDangerousCommand("")
      )
    }
  )

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

import java.nio.file.{Files, Path}
import com.weisgnosis.agents.core.error.AgentError
import zio.*
import zio.test.*

object PathSafetySpec extends ZIOSpecDefault:
  private def fixtureIO[A](operation: => A): IO[AgentError, A] =
    ZIO
      .attemptBlocking(operation)
      .mapError(error =>
        AgentError.PermissionDenied(
          "native path fixture",
          Option(error.getMessage).getOrElse(error.toString)
        )
      )

  def spec: Spec[TestEnvironment, Any] = suite("PathSafetySpec")(
    (test(
      "native directory aliases resolve existing and missing children before sensitive screening"
    ):
      for
        configured <- zio.System
          .env("WEISGNOSIS_PATH_FIXTURE")
          .mapError(error => AgentError.InvalidConfiguration(error.toString))
        supplied <- ZIO
          .fromOption(configured)
          .orElseFail(
            AgentError.InvalidConfiguration(
              "WEISGNOSIS_PATH_FIXTURE is required for native path verification"
            )
          )
        root      <- fixtureIO(Path.of(supplied).toRealPath())
        ordinary  <- fixtureIO(root.resolve("ordinary").toRealPath())
        sensitive <- fixtureIO(root.resolve(".ssh").toRealPath())
        markersPresent <- fixtureIO(
          Files.isRegularFile(ordinary.resolve("marker.txt")) &&
            Files.isRegularFile(sensitive.resolve("marker.txt"))
        )
        ordinaryAlias  = root.resolve("ordinary-alias")
        sensitiveAlias = root.resolve("sensitive-alias")
        existing <- ZIO.foreach(Chunk(ordinaryAlias, sensitiveAlias))(alias =>
          PathSafety.canonicalPath(alias.resolve("marker.txt"))
        )
        missing <- ZIO.foreach(Chunk(ordinaryAlias, sensitiveAlias))(alias =>
          PathSafety.canonicalPath(alias.resolve("not-created").resolve("file.txt"))
        )
        dangerous <- ZIO.foreach(Chunk(ordinaryAlias, sensitiveAlias))(alias =>
          PathSafety.isDangerousPath(alias.resolve("marker.txt").toString)
        )
        missingDangerous <- ZIO.foreach(Chunk(ordinaryAlias, sensitiveAlias))(alias =>
          PathSafety.isDangerousPath(alias.resolve("not-created").resolve("file.txt").toString)
        )
        traversal <- PathSafety
          .canonicalPath(ordinaryAlias.resolve("..").resolve("marker.txt"))
          .either
        missingStillAbsent <- fixtureIO(
          !Files.exists(ordinary.resolve("not-created")) &&
            !Files.exists(sensitive.resolve("not-created"))
        )
      yield assertTrue(
        ordinary.startsWith(root),
        sensitive.startsWith(root),
        markersPresent,
        !PathSafety.isSensitivePath(sensitiveAlias),
        existing == Chunk(ordinary.resolve("marker.txt"), sensitive.resolve("marker.txt")),
        missing == Chunk(
          ordinary.resolve("not-created").resolve("file.txt"),
          sensitive.resolve("not-created").resolve("file.txt")
        ),
        dangerous == Chunk(false, true),
        missingDangerous == Chunk(false, true),
        traversal.left.exists(_.isInstanceOf[AgentError.PermissionDenied]),
        missingStillAbsent
      )
    ) @@ TestAspect.withLiveSystem @@ TestAspect.ifEnvSet("WEISGNOSIS_PATH_FIXTURE"),
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
        "/home/user/.bash_login",
        "/home/user/.zshenv",
        "/home/user/.zlogin",
        "/home/user/.zshenv.zwc",
        "/home/user/.zprofile.zwc",
        "/home/user/.zshrc.zwc",
        "/home/user/.zlogin.zwc",
        "/home/user/.ZSHENV.ZWC",
        "/home/user/.zshrc",
        "/home/user/.profile",
        "~/.bashrc",
        "/home/user/.env.custom"
      )
      for
        _       <- TestSystem.putProperty("user.home", "C:/home/test")
        results <- ZIO.foreach(paths)(PathSafety.isDangerousPath(_))
      yield
        val permittedSensitivePaths = paths
          .zip(results)
          .collect:
            case (path, false) => path
        assertTrue(permittedSensitivePaths.isEmpty)
    },
    test("ordinary and blank paths are accepted") {
      val paths =
        Chunk(
          "src/main/scala/App.scala",
          "README.md",
          "target/classes/Main.class",
          "docs/.bash_login.txt",
          "docs/.zshenv.example",
          "docs/.zlogin.notes",
          "build/startup.zwc",
          "build/.zshrc.zwc.backup",
          "",
          "   "
        )
      for
        _       <- TestSystem.putProperty("user.home", "C:/home/test")
        results <- ZIO.foreach(paths)(PathSafety.isDangerousPath(_))
      yield
        val rejectedOrdinaryPaths = paths
          .zip(results)
          .collect:
            case (path, true) => path
        assertTrue(rejectedOrdinaryPaths.isEmpty)
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
    test("advisory fragments preserve lexical boundaries, whitespace and device prefixes") {
      val flagged = Chunk(
        "rm -rf /",
        "sudo rm test.txt",
        "kill -9 1234",
        "chmod 777 /etc/hosts",
        "chmod\t-R   777 workspace",
        "/bin/dd if=input.bin of=output.bin",
        "mkfs.ext4 /dev/example",
        "echo> /dev/null",
        "printf 'dd'"
      )
      val unflagged = Chunk("git status", "sbt test", "cat README.md", "", "reformat file")
      assertTrue(
        flagged.filterNot(PathSafety.isDangerousCommand).isEmpty,
        unflagged.filter(PathSafety.isDangerousCommand).isEmpty
      )
    },
    suite("command screening regression counterexamples")(
      test("rejects recursive chmod with the actual uppercase -R option")(
        assertTrue(PathSafety.isDangerousCommand("chmod -R 777 workspace"))
      ),
      test("rejects recursive chown with the actual uppercase -R option")(
        assertTrue(PathSafety.isDangerousCommand("chown -R user workspace"))
      ),
      test("does not mistake dd inside an echo argument for the dd executable")(
        assertTrue(!PathSafety.isDangerousCommand("echo address"))
      ),
      test("still rejects the actual dd executable")(
        assertTrue(PathSafety.isDangerousCommand("dd if=input.bin of=output.bin"))
      )
    )
  )

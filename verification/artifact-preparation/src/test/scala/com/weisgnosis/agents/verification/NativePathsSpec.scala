/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.verification

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.tool.PathSafety
import java.nio.file.{Files, LinkOption, Path}
import zio.*
import zio.test.*

object NativePathsSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment, Any] = suite("NativePathsSpec")(
    test("CLI preserves defaults and rejects missing launchers or invalid deadlines"):
      val accepted = NativePaths.parse(
        List(
          "--java-home",
          "jdk",
          "--sbt-launch",
          "launcher.jar",
          "--full",
          "--timeout",
          "3600"
        )
      )
      val invalid = Chunk(
        List.empty[String],
        List("--sbt-launch"),
        List("--sbt-launch", "launcher.jar", "--timeout", "0"),
        List("--sbt-launch", "launcher.jar", "--timeout", "3601"),
        List("--sbt-launch", "launcher.jar", "--timeout", "invalid"),
        List("--sbt-launch", "launcher.jar", "--unknown")
      )
      assertTrue(
        accepted == Right(NativePaths.Options(Some("jdk"), Some("launcher.jar"), true, 3600)),
        NativePaths.parse(List("--sbt-launch", "launcher.jar")) ==
          Right(NativePaths.Options(launcher = Some("launcher.jar"))),
        invalid.forall(arguments => NativePaths.parse(arguments).isLeft)
      )
    ,
    test("native aliases expose sensitive targets and are removed after scoped failure"):
      for
        windows  <- System.propertyOrElse("os.name", "").map(_.startsWith("Windows"))
        retained <- Ref.make(Option.empty[(Path, Path, Boolean, Chunk[String])])
        result <- ZIO
          .scoped:
            for
              root     <- NativePaths.fixture(windows)
              ordinary <- PathSafety.canonicalPath(root.resolve("ordinary-alias/marker.txt"))
              sensitive <- PathSafety.isDangerousPath(
                root.resolve("sensitive-alias/missing/file.txt").toString
              )
              defaults <- NativePaths.readJvmOptions(root)
              _        <- retained.set(Some((root, ordinary, sensitive, defaults)))
              _        <- ZIO.fail(AgentError.InvalidConfiguration("fixture test failure"))
            yield ()
          .exit
        owned <- retained.get.someOrFail(
          AgentError.InvalidConfiguration("Fixture was not acquired")
        )
        (root, ordinary, sensitive, defaults) = owned
        absent <- ZIO.attemptBlocking(!Files.exists(root, LinkOption.NOFOLLOW_LINKS))
      yield assertTrue(
        ordinary == root.resolve("ordinary/marker.txt"),
        sensitive,
        result.isFailure,
        absent,
        defaults == Chunk("-Xmx4g", "-Xss16m")
      )
  ) @@ TestAspect.withLiveSystem @@ TestAspect.withLiveClock

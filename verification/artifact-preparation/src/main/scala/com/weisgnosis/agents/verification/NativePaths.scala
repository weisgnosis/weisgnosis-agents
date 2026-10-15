/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.verification

import com.weisgnosis.agents.core.error.AgentError
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, LinkOption, Path}
import java.util.concurrent.TimeUnit
import zio.*
import zio.process.Command

/** Owns native aliases until the directly launched verification JVM has terminated. */
object NativePaths extends ZIOAppDefault:
  private val aliases: Chunk[(String, String)] =
    Chunk("ordinary-alias" -> "ordinary", "sensitive-alias" -> ".ssh")

  final private[verification] case class Options(
      javaHome: Option[String] = None,
      launcher: Option[String] = None,
      full: Boolean = false,
      timeout: Int = 1200
  )

  private[verification] def parse(arguments: List[String]): Either[AgentError, Options] =
    def loop(remaining: List[String], options: Options): Either[AgentError, Options] =
      remaining match
        case Nil =>
          Either.cond(
            options.launcher.isDefined && options.timeout >= 1 && options.timeout <= 3600,
            options,
            failure("--sbt-launch is required and --timeout must be between 1 and 3600 seconds")
          )
        case "--full" :: tail                => loop(tail, options.copy(full = true))
        case "--java-home" :: value :: tail  => loop(tail, options.copy(javaHome = Some(value)))
        case "--sbt-launch" :: value :: tail => loop(tail, options.copy(launcher = Some(value)))
        case "--timeout" :: value :: tail =>
          value.toIntOption
            .toRight(failure("--timeout must be an integer"))
            .flatMap(seconds => loop(tail, options.copy(timeout = seconds)))
        case _ => Left(failure("Expected --java-home, --sbt-launch, --full or --timeout"))
    loop(arguments, Options())

  def run: ZIO[ZIOAppArgs & Scope, AgentError, Unit] =
    getArgs.flatMap(arguments =>
      if arguments.contains("--help") || arguments.contains("-h") then
        Console
          .printLine(
            "NativePaths --sbt-launch <jar> [--java-home <JDK 21>] [--full] [--timeout <1..3600>]"
          )
          .mapError(error => failure(error.toString))
      else execute(arguments)
    )

  private def execute(arguments: Chunk[String]): IO[AgentError, Unit] =
    for
      options   <- ZIO.fromEither(parse(arguments.toList))
      inherited <- System.envs.mapError(error => failure(error.toString))
      selected <- ZIO
        .fromOption(options.javaHome.orElse(inherited.get("JAVA_HOME")).filterNot(_.isBlank))
        .orElseFail(failure("Set JAVA_HOME or supply --java-home for JDK 21"))
      windows <- System
        .propertyOrElse("os.name", "")
        .mapError(error => failure(error.toString))
        .map(_.startsWith("Windows"))
      project  <- io(Path.of("").toRealPath())
      javaHome <- io(Path.of(selected).toRealPath())
      release  <- io(Files.readString(javaHome.resolve("release"), UTF_8))
      _ <- ensure(
        "(?m)^JAVA_VERSION=\"21(?:[.+-]|\")".r.findFirstIn(release).isDefined,
        "The selected JAVA_HOME is not JDK 21"
      )
      javaExecutable = javaHome.resolve("bin").resolve(if windows then "java.exe" else "java")
      _ <- ensureFile(javaExecutable, "Selected JDK has no java executable")
      suppliedLauncher <- ZIO
        .fromOption(options.launcher)
        .orElseFail(failure("--sbt-launch is required"))
      launcher   <- io(Path.of(suppliedLauncher).toRealPath())
      _          <- ensureFile(launcher, "sbt launcher JAR is not a regular file")
      jvmOptions <- readJvmOptions(project)
      task =
        if options.full then "verify"
        else "core / testOnly com.weisgnosis.agents.core.tool.PathSafetySpec"
      code <- ZIO.scoped:
        for
          root <- fixture(windows)
          _ <- Console
            .printLine(s"Native path fixture: $root")
            .mapError(error => failure(error.toString))
          pathKey =
            if windows then inherited.keys.find(_.equalsIgnoreCase("PATH")).getOrElse("PATH")
            else "PATH"
          environment = Map(
            "JAVA_HOME" -> javaHome.toString,
            pathKey -> (javaHome.resolve("bin").toString + java.io.File.pathSeparator +
              inherited.getOrElse(pathKey, "")),
            "WEISGNOSIS_PATH_FIXTURE" -> root.toString
          )
          command = Command(
            javaExecutable.toString,
            (jvmOptions ++ Chunk(
              "-jar",
              launcher.toString,
              "set core / Test / fork := false",
              task
            )).toSeq*
          ).workingDirectory(project.toFile).env(environment).inheritIO
          exit <- launch(command).timeoutFail(failure("Verification launcher timed out"))(
            options.timeout.seconds
          )
        yield exit
      _ <- ZIO
        .fail(failure(s"Verification launcher exited with ${code.code}"))
        .unless(code == ExitCode.success)
    yield ()

  private[verification] def readJvmOptions(project: Path): IO[AgentError, Chunk[String]] =
    val file = project.resolve(".jvmopts")
    io(Files.exists(file)).flatMap:
      case false => ZIO.succeed(Chunk("-Xmx4g", "-Xss16m"))
      case true =>
        io(Files.readString(file, UTF_8)).map(text =>
          Chunk.fromIterator(
            text.linesIterator.map(_.trim).filter(line => line.nonEmpty && !line.startsWith("#"))
          )
        )

  private[verification] def fixture(windows: Boolean): ZIO[Scope, AgentError, Path] =
    for
      temporary  <- System.property("java.io.tmpdir").mapError(error => failure(error.toString))
      configured <- ZIO.fromOption(temporary).orElseFail(failure("java.io.tmpdir is missing"))
      parent     <- io(Path.of(configured).toRealPath())
      root <- ZIO.acquireRelease(io(Files.createTempDirectory(parent, "weisgnosis-native-paths-")))(
        root => cleanup(root, parent).orDie
      )
      _ <- ensure(root.getParent == parent, "Temporary root escaped its selected parent")
      _ <- ZIO.foreachDiscard(aliases): (_, targetName) =>
        val target = root.resolve(targetName)
        io(Files.createDirectory(target)) *>
          io(Files.writeString(target.resolve("marker.txt"), "owned fixture\n", UTF_8)).unit
      _ <- ZIO.foreachDiscard(aliases): (aliasName, targetName) =>
        val alias  = root.resolve(aliasName)
        val target = root.resolve(targetName)
        for
          canonical <- io(target.toRealPath())
          _         <- ensure(canonical.startsWith(root), "Alias target escaped its owned root")
          _ <-
            if windows then
              launch(
                Command(
                  "powershell.exe",
                  "-NoProfile",
                  "-NonInteractive",
                  "-Command",
                  "$ErrorActionPreference = 'Stop'; New-Item -ItemType Junction -Path $env:WEISGNOSIS_NATIVE_ALIAS -Target $env:WEISGNOSIS_NATIVE_TARGET | Out-Null"
                ).env(
                  Map(
                    "WEISGNOSIS_NATIVE_ALIAS"  -> alias.toString,
                    "WEISGNOSIS_NATIVE_TARGET" -> target.toString
                  )
                ).inheritIO
              ).timeoutFail(failure("Native junction creation timed out"))(30.seconds)
                .flatMap(code => ensure(code == ExitCode.success, "Cannot create native junction"))
            else io(Files.createSymbolicLink(alias, canonical)).unit
          actual <- io(alias.toRealPath())
          _      <- ensure(actual == canonical, "Native alias did not resolve to its target")
        yield ()
    yield root

  private def cleanup(root: Path, parent: Path): IO[AgentError, Unit] =
    for
      canonical <- io(root.toRealPath())
      _ <- ensure(canonical == root && root.getParent == parent, "Temporary root changed identity")
      _ <- ZIO.foreachDiscard(aliases): (aliasName, targetName) =>
        val alias  = root.resolve(aliasName)
        val target = root.resolve(targetName)
        io(Files.exists(alias, LinkOption.NOFOLLOW_LINKS)).flatMap:
          case false => ZIO.unit
          case true =>
            io(alias.toRealPath()).flatMap(actual =>
              ensure(actual == target && actual != alias, "Refusing to remove a non-alias")
            ) *> io(Files.delete(alias))
      _ <- ZIO.foreachDiscard(aliases): (_, targetName) =>
        val target = root.resolve(targetName)
        io(Files.exists(target, LinkOption.NOFOLLOW_LINKS)).flatMap:
          case false => ZIO.unit
          case true =>
            for
              actual <- io(target.toRealPath())
              _      <- ensure(actual == target, "Target directory changed identity")
              marker = target.resolve("marker.txt")
              present <- io(Files.exists(marker, LinkOption.NOFOLLOW_LINKS))
              _ <- ZIO.when(present)(
                io(Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)).flatMap(regular =>
                  ensure(regular, "Marker changed file type")
                ) *> io(Files.delete(marker))
              )
              _ <- io(Files.delete(target))
            yield ()
      _ <- io(Files.delete(root))
    yield ()

  private def launch(command: Command): IO[AgentError, ExitCode] = ZIO.scoped:
    for
      process <- ZIO.acquireRelease(
        command.run.mapError(error => failure(s"Cannot start child: $error"))
      )(child =>
        child
          .execute(raw => raw)
          .mapError(error => failure(error.toString))
          .flatMap(raw =>
            io(raw.destroy()) *> io(raw.waitFor(15, TimeUnit.SECONDS)).flatMap:
              case true  => ZIO.unit
              case false => io(raw.destroyForcibly()) *> io(raw.waitFor()).unit
          )
          .orDie
      )
      code <- process.exitCode.mapError(error => failure(s"Cannot join child: $error"))
    yield code

  private def ensureFile(path: Path, message: String): IO[AgentError, Unit] =
    io(Files.isRegularFile(path)).flatMap(ensure(_, message))

  private def ensure(condition: Boolean, message: String): IO[AgentError, Unit] =
    ZIO.fail(failure(message)).unless(condition).unit

  private def io[A](operation: => A): IO[AgentError, A] =
    ZIO.attemptBlocking(operation).uninterruptible.mapError(error => failure(error.toString))

  private def failure(message: String): AgentError =
    AgentError.InvalidConfiguration(s"Native path verification failed: $message")

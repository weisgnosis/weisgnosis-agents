/*
 * Copyright 2024-2026 WeisGnosis (https://weisgnosis.com)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.weisgnosis.agents.verification

import com.weisgnosis.agents.core.error.AgentError
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import scala.jdk.CollectionConverters.*
import zio.*
import zio.json.*

object PrepareArtifacts extends ZIOAppDefault:
  def run: ZIO[ZIOAppArgs, Throwable, Unit] =
    getArgs.flatMap(arguments => Preparation.execute(arguments.toList))

private[verification] object Preparation:
  final case class Manifest(version: String, sha256: Map[String, String]) derives JsonCodec
  final case class Options(version: Option[String], checkOnly: Boolean)

  val modules: List[String]     = List("core", "harness", "mcp", "storage-postgresql", "otel")
  val defaultVersion: String    = "0.1.0-SNAPSHOT"
  private val namespace: String = "http://maven.apache.org/POM/4.0.0"
  private val candidatePattern: String =
    "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)-RC[1-9][0-9]*"
  private val testOnly: Set[String] = Set(
    "zio-test_3",
    "zio-test-sbt_3",
    "mcp-core",
    "mcp-json-jackson2",
    "tomcat-embed-core",
    "slf4j-jdk14",
    "postgresql",
    "opentelemetry-sdk",
    "opentelemetry-sdk-trace",
    "opentelemetry-sdk-common",
    "opentelemetry-sdk-testing",
    "opentelemetry-exporter-otlp",
    "opentelemetry-exporter-otlp-common",
    "opentelemetry-exporter-common",
    "opentelemetry-exporter-sender-okhttp"
  )

  def failure(message: String): AgentError =
    AgentError.InvalidConfiguration("Artifact verification failed: " + message)

  def io[A](label: String)(operation: => A): IO[AgentError, A] =
    ZIO
      .attemptBlocking(operation)
      .mapError(error => failure(s"$label: ${error.toString}"))
      .uninterruptible

  def require(condition: Boolean, message: String): IO[AgentError, Unit] =
    ZIO.fail(failure(message)).unless(condition).unit

  def parse(
      arguments: List[String],
      options: Options = Options(None, false)
  ): IO[AgentError, Options] =
    arguments match
      case Nil                    => ZIO.succeed(options)
      case "--check-only" :: rest => parse(rest, options.copy(checkOnly = true))
      case "--version" :: value :: rest if options.version.isEmpty =>
        parse(rest, options.copy(version = Some(value)))
      case _ => ZIO.fail(failure("Expected [--version VERSION] [--check-only]"))

  def version(selected: Option[String]): IO[AgentError, String] =
    selected match
      case None => ZIO.succeed(defaultVersion)
      case Some(value) =>
        require(
          value.length <= 64 && value.matches(candidatePattern),
          "explicit version must be canonical ASCII major.minor.patch-RCpositive without leading zeros"
        ).as(value)

  def root: IO[AgentError, Path] =
    io("locate repository")(Paths.get("").toAbsolutePath.normalize()).flatMap(path =>
      io("check repository root")(Files.isDirectory(path.resolve("modules"))).flatMap(present =>
        require(present, "run from the repository root").as(path)
      )
    )

  def destination(root: Path): Path = root.resolve("target/artifact-verification")
  def manifest(root: Path): Path    = destination(root).resolve("manifest.json")

  def execute(arguments: List[String]): IO[AgentError, Unit] =
    for
      options    <- parse(arguments)
      repository <- root
      _          <- prepare(repository, options)
    yield ()

  def prepare(root: Path, options: Options): IO[AgentError, Unit] =
    for
      _ <- ZIO.unless(options.checkOnly)(
        io("invalidate manifest") {
          Files.createDirectories(destination(root))
          Files.deleteIfExists(manifest(root))
          ()
        }
      )
      selected <- version(options.version)
      _ <-
        if options.checkOnly then
          Console
            .printLine(s"Checked artifact version: $selected")
            .mapError(error => failure(error.toString))
        else
          for
            hashes <- ZIO.foreach(modules)(module =>
              inspect(root, module, selected).map(module -> _)
            )
            _ <- io("write manifest") {
              Files.writeString(manifest(root), Manifest(selected, hashes.toMap).toJson, UTF_8)
              ()
            }
            _ <- Console
              .printLine(
                "Fresh module hashes recorded for verification of the actually loaded consumer JARs"
              )
              .mapError(error => failure(error.toString))
          yield ()
    yield ()

  def artifact(root: Path, module: String, version: String, suffix: String): Path =
    root.resolve(
      s"modules/$module/target/scala-3.9.0/weisgnosis-agents-${module}_3-$version$suffix"
    )

  def archive[A](path: Path)(use: ZipFile => IO[AgentError, A]): IO[AgentError, A] =
    ZIO.scoped:
      ZIO
        .acquireRelease(io(s"open $path")(new ZipFile(path.toFile)))(zip =>
          io(s"close $path")(zip.close()).orDie
        )
        .flatMap(use)

  def entries(zip: ZipFile): IO[AgentError, Vector[String]] =
    io("list archive entries")(zip.entries().asScala.map(_.getName).toVector)

  def bytes(zip: ZipFile, name: String): IO[AgentError, Array[Byte]] =
    for
      entry <- io(s"find $name")(Option(zip.getEntry(name)))
      found <- ZIO.fromOption(entry).orElseFail(failure(s"missing archive entry $name"))
      result <- ZIO.scoped:
        ZIO
          .acquireRelease(io(s"read $name")(zip.getInputStream(found)))(stream =>
            io(s"close $name")(stream.close()).orDie
          )
          .flatMap(stream => io(s"read $name")(stream.readAllBytes()))
    yield result

  private def normalized(bytes: Array[Byte]): Vector[Byte] =
    bytes.toVector.foldLeft(Vector.empty[Byte])((result, byte) =>
      if byte == 10 && result.lastOption.contains(13.toByte) then result.dropRight(1) :+ byte
      else result :+ byte
    )

  private def inspect(root: Path, module: String, version: String): IO[AgentError, String] =
    val binary  = artifact(root, module, version, ".jar")
    val sources = artifact(root, module, version, "-sources.jar")
    val docs    = artifact(root, module, version, "-javadoc.jar")
    val pom     = artifact(root, module, version, ".pom")
    for
      _ <- ZIO.foreachDiscard(List(binary, sources, docs, pom))(path =>
        io(s"check $path")(Files.isRegularFile(path)).flatMap(exists =>
          require(exists, s"missing packaged file $path")
        )
      )
      license <- io("read license")(Files.readAllBytes(root.resolve("LICENSE")))
      _ <- ZIO.foreachDiscard(List(binary, sources, docs))(path =>
        archive(path)(zip =>
          for
            names  <- entries(zip)
            _      <- require(names.count(_ == "META-INF/LICENSE") == 1, s"$path license count")
            actual <- bytes(zip, "META-INF/LICENSE")
            _      <- require(actual.sameElements(license), s"$path license bytes")
          yield ()
        )
      )
      _ <- archive(binary)(zip =>
        entries(zip).flatMap(names =>
          require(names.exists(_.endsWith(".class")), s"$module missing classes") *>
            require(names.exists(_.endsWith(".tasty")), s"$module missing TASTy") *>
            require(!names.exists(_.endsWith("Spec.class")), s"$module includes tests")
        )
      )
      _ <- archive(docs)(zip =>
        entries(zip).flatMap(names =>
          require(names.contains("index.html"), s"$module missing Scaladoc index")
        )
      )
      _ <- inspectSources(root, module, sources)
      _ <- inspectPom(pom, module, version)
      staged = destination(root).resolve(
        s"repository/com/weisgnosis/weisgnosis-agents-${module}_3/$version"
      )
      _ <- io("stage artifacts") {
        Files.createDirectories(staged)
        List(binary, sources, docs, pom).foreach(path =>
          Files.copy(path, staged.resolve(path.getFileName), StandardCopyOption.REPLACE_EXISTING)
        )
        ()
      }
      hash <- io("hash binary") {
        MessageDigest
          .getInstance("SHA-256")
          .digest(Files.readAllBytes(binary))
          .map(byte => f"${byte & 0xff}%02x")
          .mkString
      }
      _ <- Console
        .printLine(
          s"$module: exact license/source content, Scaladoc index, classes/TASTy and POM boundaries passed"
        )
        .mapError(error => failure(error.toString))
    yield hash

  private def inspectSources(root: Path, module: String, sources: Path): IO[AgentError, Unit] =
    val sourceRoot = root.resolve(s"modules/$module/src/main/scala")
    for
      current <- ZIO.scoped:
        ZIO
          .acquireRelease(io("walk production sources")(Files.walk(sourceRoot)))(stream =>
            io("close production source walk")(stream.close()).orDie
          )
          .flatMap(stream =>
            io("collect production sources")(
              stream.iterator().asScala.filter(path => path.toString.endsWith(".scala")).toVector
            )
          )
      _ <- require(current.nonEmpty, s"$module has no production sources")
      _ <- archive(sources)(zip =>
        for
          names <- entries(zip)
          expected = current.map(path =>
            sourceRoot.relativize(path).iterator().asScala.mkString("/")
          )
          _ <- require(
            names.filter(_.endsWith(".scala")).sorted == expected.sorted,
            s"$module source archive entry names/counts differ from current Scala sources"
          )
          _ <- ZIO.foreachDiscard(current.zip(expected)) { case (path, entry) =>
            for
              packaged <- bytes(zip, entry)
              actual   <- io(s"read $path")(Files.readAllBytes(path))
              _ <- require(
                normalized(packaged) == normalized(actual),
                s"$module source differs from current tree: $entry"
              )
            yield ()
          }
        yield ()
      )
    yield ()

  private def children(parent: Element, name: String): Vector[Element] =
    val nodes = parent.getChildNodes
    (0 until nodes.getLength).toVector.flatMap(index =>
      nodes.item(index) match
        case element: Element
            if element.getNamespaceURI == namespace && element.getLocalName == name =>
          Some(element)
        case _ => None
    )

  private def text(parent: Element, name: String, default: String = ""): String =
    children(parent, name).headOption.map(_.getTextContent).getOrElse(default)

  private def inspectPom(path: Path, module: String, version: String): IO[AgentError, Unit] =
    for
      document <- io(s"parse $path") {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setNamespaceAware(true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        factory.newDocumentBuilder().parse(path.toFile).getDocumentElement
      }
      _ <- require(text(document, "groupId") == "com.weisgnosis", s"$module group")
      _ <- require(
        text(document, "artifactId") == s"weisgnosis-agents-${module}_3",
        s"$module artifact"
      )
      _ <- require(text(document, "version") == version, s"$module version")
      _ <- require(text(document, "description").trim.nonEmpty, s"$module missing description")
      dependencies = children(document, "dependencies").flatMap(children(_, "dependency"))
      parent       = if module == "storage-postgresql" then "harness" else "core"
      _ <- require(
        module == "core" || dependencies.exists(dependency =>
          text(dependency, "groupId") == "com.weisgnosis" &&
            text(dependency, "artifactId") == s"weisgnosis-agents-${parent}_3" &&
            text(dependency, "version") == version && text(
              dependency,
              "scope",
              "compile"
            ) == "compile"
        ),
        s"$module missing matching compile parent dependency"
      )
      _ <- ZIO.foreachDiscard(dependencies)(dependency =>
        val name = text(dependency, "artifactId")
        require(
          !testOnly.contains(name) || text(dependency, "scope", "compile") == "test",
          s"$module leaked test dependency $name"
        ) *>
          require(
            name != "snakeyaml-engine" || module == "harness",
            s"$module leaked YAML dependency"
          )
      )
    yield ()

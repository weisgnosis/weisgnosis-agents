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
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.zip.CRC32
import zio.*

object CheckPreparation extends ZIOAppDefault:
  def run: ZIO[ZIOAppArgs, Throwable, Unit] =
    for
      arguments <- getArgs
      options   <- Preparation.parse(arguments.toList)
      _ <- Preparation.require(!options.checkOnly, "fixture checks do not support --check-only")
      version <- Preparation.version(options.version)
      root    <- Preparation.root
      _       <- check(root, options, version)
    yield ()

  private def check(
      root: Path,
      options: Preparation.Options,
      version: String
  ): IO[AgentError, Unit] =
    val sources = Preparation.artifact(root, "harness", version, "-sources.jar")
    val docs    = Preparation.artifact(root, "harness", version, "-javadoc.jar")
    for
      _ <- Preparation.prepare(root, options)
      _ <- ZIO.scoped:
        for
          originals <- ZIO.acquireRelease(
            for
              sourceBytes <- Preparation.io("read source fixture baseline")(
                Files.readAllBytes(sources)
              )
              docBytes <- Preparation.io("read Scaladoc fixture baseline")(Files.readAllBytes(docs))
            yield (sourceBytes, docBytes)
          ) { case (sourceBytes, docBytes) =>
            Preparation
              .io("restore original source fixture")(Files.write(sources, sourceBytes))
              .unit
              .ensuring(
                Preparation
                  .io("restore original Scaladoc fixture")(Files.write(docs, docBytes))
                  .unit
                  .orDie
              )
              .orDie
          }
          _ <- expectRejected(
            root,
            options.copy(version = Some("../invalid")),
            "invalid selector",
            "explicit version"
          )
          _             <- Preparation.prepare(root, options)
          sourceEntries <- readEntries(sources)
          existing <- ZIO
            .fromOption(sourceEntries.find(_._1.endsWith(".scala")))
            .orElseFail(Preparation.failure("Fixture requires a packaged Scala source"))
          _ <- ZIO.foreachDiscard(
            List(
              (
                "unexpected Scala source",
                "com/weisgnosis/agents/Obsolete.scala" -> "package com.weisgnosis.agents\n"
                  .getBytes(UTF_8)
              ),
              ("duplicate Scala source", existing)
            )
          ) { case (label, entry) =>
            Preparation.io("restore source baseline")(Files.write(sources, originals._1)) *>
              Preparation.prepare(root, options) *>
              writeEntries(sources, sourceEntries :+ entry) *>
              expectRejected(root, options, label, "source archive entry names/counts")
          }
          _ <- Preparation.io("restore source archive")(Files.write(sources, originals._1)).unit
          docEntries <- readEntries(docs)
          _ <- ZIO.foreachDiscard(List("index.html", "META-INF/LICENSE"))(omitted =>
            Preparation.io("restore Scaladoc baseline")(Files.write(docs, originals._2)) *>
              Preparation.prepare(root, options) *>
              writeEntries(docs, docEntries.filterNot(_._1 == omitted)) *>
              expectRejected(
                root,
                options,
                "Scaladoc without " + omitted,
                if omitted == "index.html" then "missing Scaladoc index" else "license count"
              )
          )
        yield ()
      _ <- Preparation.prepare(root, options)
      _ <- Console
        .printLine(
          "Selector, source and Scaladoc archive regressions passed; original bytes restored and revalidated"
        )
        .mapError(error => Preparation.failure(error.toString))
    yield ()

  private def expectRejected(
      root: Path,
      options: Preparation.Options,
      label: String,
      expected: String
  ): IO[AgentError, Unit] =
    for
      result <- Preparation.prepare(root, options).either
      invalidated <- Preparation.io("check manifest invalidation")(
        !Files.exists(Preparation.manifest(root))
      )
      _ <- result match
        case Left(error) =>
          Preparation.require(
            error.message.contains(expected),
            s"$label failed for an unexpected reason: ${error.message}"
          )
        case Right(_) => ZIO.fail(Preparation.failure(s"$label must be rejected"))
      _ <- Preparation.require(invalidated, s"$label must invalidate the manifest")
      _ <- Console
        .printLine(s"$label: rejected, manifest invalidated")
        .mapError(error => Preparation.failure(error.toString))
    yield ()

  private def readEntries(path: Path): IO[AgentError, Vector[(String, Array[Byte])]] =
    Preparation.archive(path)(zip =>
      Preparation
        .entries(zip)
        .flatMap(names => ZIO.foreach(names)(name => Preparation.bytes(zip, name).map(name -> _)))
    )

  private def littleEndian(value: Long, width: Int): Array[Byte] =
    (0 until width).map(index => ((value >>> (index * 8)) & 0xff).toByte).toArray

  // ZIP's stored representation permits duplicate names, which ZipOutputStream rejects.
  private def writeEntries(
      path: Path,
      entries: Vector[(String, Array[Byte])]
  ): IO[AgentError, Unit] =
    Preparation.io(s"write ZIP fixture $path") {
      val output = new ByteArrayOutputStream()
      val central = entries.map { case (name, content) =>
        val encoded  = name.getBytes(UTF_8)
        val checksum = new CRC32()
        checksum.update(content)
        val offset = output.size()
        val header = List(
          littleEndian(0x04034b50L, 4),
          littleEndian(20, 2),
          littleEndian(0x800, 2),
          littleEndian(0, 2),
          littleEndian(0, 2),
          littleEndian(0, 2),
          littleEndian(checksum.getValue, 4),
          littleEndian(content.length, 4),
          littleEndian(content.length, 4),
          littleEndian(encoded.length, 2),
          littleEndian(0, 2),
          encoded,
          content
        )
        header.foreach(output.write)
        List(
          littleEndian(0x02014b50L, 4),
          littleEndian(20, 2),
          littleEndian(20, 2),
          littleEndian(0x800, 2),
          littleEndian(0, 2),
          littleEndian(0, 2),
          littleEndian(0, 2),
          littleEndian(checksum.getValue, 4),
          littleEndian(content.length, 4),
          littleEndian(content.length, 4),
          littleEndian(encoded.length, 2),
          littleEndian(0, 2),
          littleEndian(0, 2),
          littleEndian(0, 2),
          littleEndian(0, 2),
          littleEndian(0, 4),
          littleEndian(offset, 4),
          encoded
        )
      }
      val centralOffset = output.size()
      central.foreach(_.foreach(output.write))
      val centralSize = output.size() - centralOffset
      List(
        littleEndian(0x06054b50L, 4),
        littleEndian(0, 2),
        littleEndian(0, 2),
        littleEndian(entries.size, 2),
        littleEndian(entries.size, 2),
        littleEndian(centralSize, 4),
        littleEndian(centralOffset, 4),
        littleEndian(0, 2)
      ).foreach(output.write)
      Files.write(path, output.toByteArray)
      ()
    }

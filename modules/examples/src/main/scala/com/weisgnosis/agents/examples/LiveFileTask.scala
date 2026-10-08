/*
 * Copyright 2024-2026 WeisGnosis (https://weisgnosis.com)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.weisgnosis.agents.examples

import com.weisgnosis.agents.core.agent.{AgentResult, AgentRunState, ReActAgent}
import com.weisgnosis.agents.core.client.{
  ChatCompletionsClient,
  CompletionTransportLimits,
  LlmClient,
  LlmConfig
}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{Msg, Role}
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import zio.*
import zio.http.Client
import zio.json.*
import zio.json.ast.Json

/** An explicitly enabled model task with one disposable, host-owned file and no model-selected paths. */
object LiveFileTask extends ZIOAppDefault:
  private[examples] val ExpectedText: String = "rice: 1 cup\nwater: 2 cups\n"
  private val maximumFileBytes: Int          = 1024

  final private case class WriteInput(text: String) derives JsonCodec
  final private[examples] case class Verification(
      result: AgentResult,
      bytes: Chunk[Byte],
      writes: Int,
      reads: Int,
      directoryRemoved: Boolean
  )

  def run: ZIO[Any, Throwable, Unit] = LiveProviderSmoke.configuration.flatMap:
    case None =>
      Console.printLine("File task disabled; set WEISGNOSIS_LIVE_SMOKE=enabled to opt in.")
    case Some(config) =>
      ZIO
        .serviceWithZIO[Client](http =>
          ChatCompletionsClient
            .make(http, CompletionTransportLimits(responseBytes = 1024L * 1024))
            .flatMap(exercise(_, config))
            .flatMap(report =>
              ZIO.logInfo(
                s"File task passed: ${report.writes} write, ${report.reads} read, owned fixture removed"
              )
            )
        )
        .provide(Client.default)

  /** The same actual ReAct/filesystem path is exercised offline with an explicitly supplied client. */
  private[examples] def exercise(
      client: LlmClient,
      config: LlmConfig
  ): IO[AgentError, Verification] =
    withFixture: directory =>
      for
        writes <- Ref.make(0)
        reads  <- Ref.make(0)
        file = directory.resolve("recipe.txt")
        write = Tool.fromFunction[WriteInput, String](
          "fixture_write",
          "Write the exact requested recipe into the single owned fixture file.",
          Json.Obj(
            "type"                 -> Json.Str("object"),
            "properties"           -> Json.Obj("text" -> Json.Obj("type" -> Json.Str("string"))),
            "required"             -> Json.Arr(Json.Str("text")),
            "additionalProperties" -> Json.Bool(false)
          )
        )(input =>
          if input.text != ExpectedText || input.text
              .getBytes(StandardCharsets.UTF_8)
              .length > maximumFileBytes
          then
            ZIO.fail(
              AgentError.ToolExecutionError(
                "fixture_write",
                "Supply the exact requested bounded recipe text"
              )
            )
          else
            io(
              Files.write(
                file,
                input.text.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
              )
            ).unit *>
              writes.update(_ + 1).as("Recipe written; use fixture_read to inspect it.")
        )
        read = Tool.make(
          ToolDef(
            "fixture_read",
            "Read the single owned recipe file; no path argument is accepted.",
            Json.Obj(
              "type"                 -> Json.Str("object"),
              "properties"           -> Json.Obj(),
              "additionalProperties" -> Json.Bool(false)
            ),
            isReadOnly = true
          )
        )((input: Json) =>
          if input != Json.Obj() then
            ZIO.fail(AgentError.ToolExecutionError("fixture_read", "No arguments are accepted"))
          else
            writes.get.flatMap(count =>
              if count != 1 then
                ZIO.fail(AgentError.ToolExecutionError("fixture_read", "Write the recipe first"))
              else
                io(Files.readAllBytes(file)).flatMap(bytes =>
                  if bytes.length > maximumFileBytes then
                    ZIO.fail(AgentError.StorageError("Fixture file exceeds its byte limit"))
                  else reads.update(_ + 1).as(new String(bytes, StandardCharsets.UTF_8))
                )
            )
        )
        timeout <- LiveProviderSmoke.runTimeout
        agent <- ReActAgent
          .builder()
          .client(client)
          .llmConfig(config)
          .toolkit(Toolkit.single(write).updated(read))
          .maxIterations(3)
          .timeout(timeout)
          .build
        result <- agent.run(
          Msg.user(
            "Use fixture_write exactly once to write this exact text, including the final newline:\n" + ExpectedText +
              "After its result, call fixture_read exactly once. After reading it, briefly confirm completion."
          )
        )
        bytes      <- io(Files.readAllBytes(file)).map(Chunk.fromArray(_))
        writeCount <- writes.get
        readCount  <- reads.get
        toolTurns = result.history.count(message =>
          message.role == Role.Assistant && message.toolUses.nonEmpty
        )
        observedRead = result.history
          .flatMap(_.toolResults)
          .exists(result =>
            result.name.contains("fixture_read") && result.outputText == ExpectedText
          )
        _ <- ZIO
          .fail(
            AgentError.InvalidMessage(
              "File task requires completed write/read turns and exact persisted recipe bytes"
            )
          )
          .unless(
            result.state == AgentRunState.Completed && writeCount == 1 && readCount == 1 && toolTurns == 2 &&
              bytes == Chunk.fromArray(
                ExpectedText.getBytes(StandardCharsets.UTF_8)
              ) && observedRead
          )
      yield Verification(result, bytes, writeCount, readCount, directoryRemoved = false)
    .flatMap: (directory, report) =>
      io(!Files.exists(directory)).flatMap(removed =>
        ZIO
          .fail(AgentError.StorageError("Owned file-task fixture was not removed"))
          .unless(removed)
          .as(report.copy(directoryRemoved = removed))
      )

  /** Scope owns cleanup immediately; typed release causes and late caller interruption survive joining. */
  private[examples] def withFixture[A](
      use: Path => ZIO[Scope, AgentError, A]
  ): IO[AgentError, (Path, A)] =
    ZIO.checkInterruptible: status =>
      ZIO.uninterruptibleMask: restore =>
        for
          released <- Ref.make[Exit[AgentError, Unit]](Exit.unit)
          completed <- restore(ZIO.scoped:
            ZIO
              .acquireRelease(io(Files.createTempDirectory("weisgnosis-file-task-")))(directory =>
                (io(Files.deleteIfExists(directory.resolve("recipe.txt"))).unit *>
                  io(Files.delete(directory))).exit.flatMap(released.set)
              )
              .flatMap(directory => use(directory).map(directory -> _))).exit
          cleanup <- released.get
          cancellation <-
            if status.isInterruptible then
              ZIO.descriptor.map(descriptor =>
                val cause = descriptor.interrupters.foldLeft[Cause[Nothing]](Cause.empty)(
                  (cause, id) => cause ++ Cause.interrupt(id)
                )
                if cause.isEmpty then Exit.unit else Exit.failCause(cause)
              )
            else ZIO.succeed(Exit.unit)
          result <- completed
            .zipWith(cleanup)((value, _) => value, _ ++ _)
            .zipWith(cancellation)((value, _) => value, _ ++ _)
        yield result

  private def io[A](operation: => A): IO[AgentError, A] =
    ZIO.uninterruptible(
      ZIO
        .attemptBlocking(operation)
        .mapError(error =>
          AgentError.StorageError(
            Option(error.getMessage).getOrElse("File-task filesystem operation failed")
          )
        )
    )

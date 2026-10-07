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
package consumer

import com.weisgnosis.agents.core.agent.ReActAgent
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, ToolCallParallelism, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{AgentEvent, FinalAnswerFilter, ModelStreamContext}
import com.weisgnosis.agents.core.json.ExactJson
import com.weisgnosis.agents.core.model.{ContentBlock, Msg}
import com.weisgnosis.agents.core.tool.{Tool, ToolAuthorization, ToolAuthorizer, ToolDef, Toolkit}
import com.weisgnosis.agents.harness.permission.{PermissionBehavior, PermissionEngine}
import com.weisgnosis.agents.mcp.protocol.{McpCallOutcome, McpCallResult, McpContentBlock}
import java.io.File
import java.util.regex.Pattern
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import zio.*
import zio.json.*
import zio.json.ast.Json

/** The neutral package deliberately has no access to private[agents] implementation helpers. */
object ArtifactSmoke extends ZIOAppDefault:
  final private case class Manifest(version: String, sha256: Map[String, String]) derives JsonCodec

  private def failure(message: String): AgentError = AgentError.InvalidConfiguration(message)

  private def loadManifest: IO[AgentError, Manifest] =
    for
      configured <- System
        .property("weisgnosis.artifact.manifest")
        .mapError(_ => failure("Cannot read artifact manifest configuration"))
      path <- ZIO.fromOption(configured).orElseFail(failure("Artifact manifest is required"))
      raw <- ZIO
        .attemptBlocking(Files.readString(Path.of(path), StandardCharsets.UTF_8))
        .mapError(_ => failure("Cannot read prepared artifact manifest"))
      manifest <- ZIO.fromEither(
        raw.fromJson[Manifest].left.map(_ => failure("Invalid artifact manifest"))
      )
      _ <- ZIO
        .fail(failure("Unexpected artifact verification version"))
        .unless(manifest.version == "0.1.0-SNAPSHOT")
    yield manifest

  /** Hash the actual resolved module JAR, never the source used to prepare the expected manifest. */
  private def verifyJar(
      module: String,
      loaded: Class[?],
      manifest: Manifest
  ): IO[AgentError, Unit] =
    for
      location <- ZIO
        .attempt(
          Option(loaded.getProtectionDomain)
            .flatMap(domain => Option(domain.getCodeSource))
            .map(_.getLocation.toURI)
        )
        .mapError(_ => failure(s"Cannot locate resolved $module artifact"))
      uri <- ZIO.fromOption(location).orElseFail(failure(s"Missing $module code source"))
      observed <- ZIO
        .attemptBlocking:
          val jar   = Path.of(uri)
          val bytes = Files.readAllBytes(jar)
          val digest = MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .iterator
            .map(byte => f"${byte & 0xff}%02x")
            .mkString
          jar.getFileName.toString -> digest
        .mapError(_ => failure(s"Cannot inspect resolved $module JAR"))
      expected <- ZIO
        .fromOption(manifest.sha256.get(module))
        .orElseFail(failure(s"Missing $module hash"))
      _ <- ZIO
        .fail(failure(s"Resolved $module artifact differs from freshly packaged JAR"))
        .unless(
          observed._1 == s"weisgnosis-agents-${module}_3-${manifest.version}.jar" && observed._2 == expected
        )
    yield ()

  private def verifyClasspath: IO[AgentError, Unit] =
    for
      configured <- System
        .property("java.class.path")
        .mapError(_ => failure("Cannot read consumer runtime classpath"))
      raw <- ZIO.fromOption(configured).orElseFail(failure("Missing consumer runtime classpath"))
      ownLocation <- ZIO
        .attempt(
          Option(ArtifactSmoke.getClass.getProtectionDomain)
            .flatMap(domain => Option(domain.getCodeSource))
            .map(_.getLocation.toURI)
        )
        .mapError(_ => failure("Cannot locate consumer classes"))
      ownUri <- ZIO.fromOption(ownLocation).orElseFail(failure("Missing consumer code source"))
      valid <- ZIO
        .attemptBlocking:
          val own = Path.of(ownUri).toRealPath()
          val forbidden = Chunk(
            "zio-test_3-",
            "zio-test-sbt_3-",
            "mcp-core-",
            "mcp-json-jackson2-",
            "jackson-",
            "tomcat-",
            "slf4j-jdk14-"
          )
          Chunk
            .fromArray(raw.split(Pattern.quote(File.pathSeparator)))
            .forall: entry =>
              val path = Path.of(entry).toRealPath()
              val name = path.getFileName.toString
              if Files.isDirectory(path) then path == own
              else name.endsWith(".jar") && !forbidden.exists(name.startsWith)
        .mapError(_ => failure("Cannot inspect consumer runtime classpath"))
      _ <- ZIO
        .fail(failure("Consumer classpath contains project classes or test dependencies"))
        .unless(valid)
    yield ()

  def run: ZIO[Any, Throwable, Unit] =
    ZIO.scoped:
      for
        manifest <- loadManifest
        _        <- verifyClasspath
        _        <- verifyJar("core", ReActAgent.getClass, manifest)
        _        <- verifyJar("harness", PermissionEngine.getClass, manifest)
        _        <- verifyJar("mcp", McpCallResult.getClass, manifest)
        encoded  <- ZIO.fromEither(ExactJson.encode(Json.Num(Long.MinValue)))
        event = AgentEvent.ToolResultStartEvent(
          "call",
          "sum",
          metadata = Map("number" -> Json.Num(Long.MinValue))
        )
        wire <- ZIO.fromEither(ExactJson.encode[AgentEvent](event))
        decoded <- ZIO.fromEither(
          wire.fromJson[AgentEvent].left.map(AgentError.InvalidMessage.apply)
        )
        configured = LlmConfig(
          "packaged-model",
          parallelToolCalls = Some(ToolCallParallelism.Disabled)
        )
        turns <- Ref.make(0)
        client = new LlmClient:
          def streamTurn(
              history: Chunk[Msg],
              tools: Chunk[ToolDef],
              config: LlmConfig,
              eventHub: Hub[AgentEvent],
              context: ModelStreamContext
          ): ZIO[Scope, AgentError, TurnOutput] =
            turns
              .getAndUpdate(_ + 1)
              .map:
                case 0 =>
                  TurnOutput.ToolCalls(
                    Msg.assistant(Chunk(ContentBlock.ToolUse("call", "sum", Json.Obj())))
                  )
                case _ => TurnOutput.FinalText(Msg.assistant("5"))
        approvals <- Ref.make(Chunk.empty[String])
        toolkit <- ZIO.fromEither(
          Toolkit.empty.register(
            Tool.make(ToolDef("sum", "Deterministic consumer fixture", Json.Obj()))(_ =>
              ZIO.succeed("5")
            )
          )
        )
        agent <- ReActAgent
          .builder()
          .model(client)
          .llmConfig(configured)
          .toolkit(toolkit)
          .toolAuthorizer(
            ToolAuthorizer(request =>
              approvals
                .update(_ :+ request.call.name)
                .as(ToolAuthorization.Approved(request.call.input))
            )
          )
          .maxIterations(3)
          .timeout(1.minute)
          .build
        filter    <- ZIO.fromEither(FinalAnswerFilter.make())
        events    <- agent.streamEvents(Msg.user("Use sum")).via(filter).runCollect
        decisions <- approvals.get
        projected <- ZIO.fromEither(
          McpCallResult.read(
            Json.Obj(
              "content" -> Json.Arr(
                Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str("packaged"))
              )
            )
          )
        )
        permission = PermissionEngine().evaluate(
          "read",
          Json.Obj(),
          Some(PermissionBehavior.Allow),
          Some(PermissionBehavior.Deny)
        )
        _ <- ZIO
          .fail(failure("Packaged public API consumer contract failed"))
          .unless(
            encoded == "-9223372036854775808" && decoded == event &&
              configured.toJson.fromJson[LlmConfig] == Right(configured) && decisions == Chunk(
                "sum"
              ) &&
              events.collect { case result: AgentEvent.AgentResultEvent =>
                result.result.text
              } == Chunk("5") &&
              permission == PermissionBehavior.Deny && projected.outcome == McpCallOutcome.Success &&
              projected.content.map(_.block) == Chunk(McpContentBlock.Text("packaged"))
          )
        _ <- Console.printLine("Fresh packaged artifacts passed: external public API consumer")
      yield ()

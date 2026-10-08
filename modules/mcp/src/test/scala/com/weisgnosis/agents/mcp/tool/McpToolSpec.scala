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
package com.weisgnosis.agents.mcp.tool

import com.weisgnosis.agents.core.agent.ReActAgent
import com.weisgnosis.agents.core.client.{ChatCompletionsRequest, LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.{ToolDef, Toolkit, ToolSchemaStrictness}
import com.weisgnosis.agents.mcp.client.McpClient
import com.weisgnosis.agents.mcp.protocol.*
import com.weisgnosis.agents.mcp.transport.McpTransport
import zio.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

object McpToolSpec extends ZIOSpecDefault:
  private val definition: ToolDef = ToolDef(
    "local_echo",
    "Host-selected tool",
    Json.Obj("type" -> Json.Str("object")),
    isConcurrencySafe = false,
    isReadOnly = false,
    schemaStrictness = Some(ToolSchemaStrictness.Strict)
  )

  final private case class Peer(client: McpClient, requests: Ref[Chunk[McpMessage.Request]])

  private def peer(
      respond: McpMessage.Request => ZIO[Scope, AgentError, Either[McpRpcError, Json.Obj]]
  ): ZIO[Scope, AgentError, Peer] =
    for
      inbox    <- ZIO.acquireRelease(Queue.unbounded[McpMessage])(_.shutdown)
      requests <- Ref.make(Chunk.empty[McpMessage.Request])
      transport = new McpTransport:
        def incoming: ZStream[Any, AgentError, McpMessage] = ZStream.fromQueue(inbox)
        def send(message: McpMessage): ZIO[Scope, AgentError, Unit] = message match
          case request @ McpMessage.Request(id, method, _) =>
            val response =
              if method == "initialize" then
                ZIO.succeed(
                  Right(
                    Json.Obj(
                      "protocolVersion" -> Json.Str(McpInitialization.protocolVersion),
                      "serverInfo" -> Json
                        .Obj("name" -> Json.Str("peer"), "version" -> Json.Str("1")),
                      "capabilities" -> Json.Obj("tools" -> Json.Obj())
                    )
                  )
                )
              else respond(request)
            requests.update(_ :+ request) *> response.flatMap(value =>
              inbox
                .offer(
                  value.fold(
                    error => McpMessage.Failure(Some(id), error),
                    result => McpMessage.Success(id, result)
                  )
                )
                .unit
            )
          case _: McpMessage.Notification => ZIO.unit
          case _ => ZIO.fail(AgentError.McpError("Unexpected test message"))
      client <- McpClient.open(transport, McpImplementation("client", "1"))
    yield Peer(client, requests)

  private def block(kind: String, fields: (String, Json)*): Json.Obj =
    Json.Obj(Chunk("type" -> Json.Str(kind)) ++ Chunk.fromIterable(fields))

  private def embeddedResourceRequest: ZIO[Scope, AgentError, TestResult] =
    val text = "exact embedded text λ<&>"
    val resource = Json.Obj(
      "uri"            -> Json.Str("resource://fixture/text"),
      "text"           -> Json.Str(text),
      "mimeType"       -> Json.Str("text/plain"),
      "_meta"          -> Json.Obj("source" -> Json.Str("retained")),
      "futureResource" -> Json.Num(7)
    )
    val payload = Json.Obj("content" -> Json.Arr(block("resource", "resource" -> resource)))
    for
      server <- peer(_ => ZIO.succeed(Right(payload)))
      bound = McpTool.bind(server.client, "remote_echo", definition)
      toolkit    <- ZIO.fromEither(Toolkit.empty.register(bound))
      attempts   <- Ref.make(0)
      serialized <- Ref.make(Chunk.empty[Json.Obj])
      model = LlmClient.mockEffectful((history, tools, _) =>
        attempts.update(_ + 1) *>
          ZIO
            .fromEither(ChatCompletionsRequest.encode(history, tools, LlmConfig("test-model")))
            .flatMap(request => serialized.update(_ :+ request)) *>
          ZIO.succeed(
            if history.flatMap(_.toolResults).isEmpty then
              TurnOutput.ToolCalls(
                Msg.assistant(Chunk(ContentBlock.ToolUse("embedded", "local_echo", Json.Obj())))
              )
            else TurnOutput.FinalText(Msg.assistant("resource accepted"))
          )
      )
      agent <- ReActAgent.make(
        client = model,
        toolkit = toolkit,
        modelInputTransform = McpModelInputTransform()
      )
      result   <- agent.call(Msg.user("read embedded resource")).exit
      history  <- agent.history
      count    <- attempts.get
      requests <- serialized.get
      remote   <- server.requests.get
      modelToolText = requests
        .drop(1)
        .headOption
        .flatMap(_.get("messages"))
        .flatMap(_.asArray)
        .toList
        .flatten
        .flatMap(_.asObject)
        .filter(_.get("role").contains(Json.Str("tool")))
        .flatMap(_.get("content"))
        .flatMap(_.asString)
      literal =
        """Custom tool content: {"kind":"mcp/resource","payload":{"uri":"resource://fixture/text","body":{"text":"exact embedded text λ<&>"},"mimeType":"text/plain","meta":{"source":"retained"},"extensions":{"futureResource":7}}}"""
      expected = Chunk(
        ContentBlock.Text(text),
        ContentBlock.Custom(
          "mcp/resource",
          Json.Obj(
            "uri"        -> Json.Str("resource://fixture/text"),
            "body"       -> Json.Obj("text" -> Json.Str(text)),
            "mimeType"   -> Json.Str("text/plain"),
            "meta"       -> Json.Obj("source" -> Json.Str("retained")),
            "extensions" -> Json.Obj("futureResource" -> Json.Num(7))
          )
        )
      )
    yield assertTrue(
      result.isSuccess,
      count == 2,
      requests.size == 2,
      modelToolText == List(text + "\n" + literal),
      remote.map(_.method) == Chunk("initialize", "tools/call"),
      history
        .flatMap(_.toolResults)
        .exists(value =>
          value.id == "embedded" && value.name.contains("local_echo") &&
            value.state == ToolResultState.Success && value.output == expected && value.metadata
              .contains("mcp")
        )
    )

  private val resultMeta: Json.Obj = Json.Obj("serverTool" -> Json.Bool(true))
  private val structured: Json.Obj = Json.Obj("answer" -> Json.Num(42))
  private val priority: Json.Num   = Json.Num(BigDecimal("0.1234567890123456789"))
  private val size: Json.Num       = Json.Num(BigDecimal("9007199254740993"))
  private val resourceBodies: Chunk[Json.Obj] = Chunk(
    Json.Obj("text" -> Json.Str("body")),
    Json.Obj("blob" -> Json.Str("opaque blob")),
    Json.Obj("text" -> Json.Str("body"), "blob" -> Json.Str("opaque blob"))
  )
  private val fullResult: Json.Obj = Json.Obj(
    "isError"           -> Json.Bool(true),
    "structuredContent" -> structured,
    "_meta"             -> resultMeta,
    "futureResult"      -> Json.Str("result extension"),
    "content" -> Json.Arr(
      Chunk(
        block(
          "text",
          "text"  -> Json.Str("partial"),
          "_meta" -> Json.Obj("source" -> Json.Str("text")),
          "annotations" -> Json.Obj(
            "audience"     -> Json.Arr(Json.Str("user"), Json.Str("assistant")),
            "priority"     -> priority,
            "lastModified" -> Json.Str("opaque timestamp"),
            "future"       -> Json.Num(1)
          ),
          "futureContent" -> Json.Num(2)
        ),
        block("image", "data" -> Json.Str("opaque image"), "mimeType" -> Json.Str("image/png")),
        block("audio", "data" -> Json.Str("opaque audio"), "mimeType" -> Json.Str("audio/wav")),
        block(
          "resource_link",
          "uri"         -> Json.Str("opaque uri"),
          "name"        -> Json.Str("resource"),
          "title"       -> Json.Str("title"),
          "description" -> Json.Str("description"),
          "mimeType"    -> Json.Str("text/plain"),
          "size"        -> size
        )
      ) ++ resourceBodies.map(body =>
        block(
          "resource",
          "resource" -> Json.Obj(
            Chunk(
              "uri"            -> Json.Str("opaque uri"),
              "mimeType"       -> Json.Str("text/plain"),
              "_meta"          -> Json.Obj("source" -> Json.Str("resource")),
              "futureResource" -> Json.Num(3)
            ) ++ body.fields
          )
        )
      )
    )
  )

  def spec: Spec[TestEnvironment, Any] = suite("McpToolSpec")(
    test(
      "embedded resources expose text, images and binary descriptions while retaining opaque payloads"
    ):
      val uri       = "file:///resource.bin"
      val innerMeta = Json.Obj("origin" -> Json.Str("remote"))
      val outerMeta = Json.Obj("correlation" -> Json.Str("resource"))
      val text      = Json.Obj("text" -> Json.Str("File content"))
      val blob      = Json.Obj("blob" -> Json.Str("opaque base64"))
      val textCases: Chunk[(Json.Obj, Option[String], ContentBlock)] = Chunk(
        (text, Some("text/plain"), ContentBlock.Text("File content")),
        (
          blob,
          Some("application/octet-stream"),
          ContentBlock.Text(s"[Binary resource: application/octet-stream, URI: $uri]")
        ),
        (blob, None, ContentBlock.Text(s"[Binary resource: unknown MIME type, URI: $uri]")),
        (
          Json.Obj("text" -> Json.Str("File content"), "blob" -> Json.Str("opaque base64")),
          Some("image/png"),
          ContentBlock.Text("File content")
        )
      )
      val imageCases = Chunk("image/png", "image/jpeg", "image/gif", "image/svg+xml", "image/bmp")
        .map(mime => (blob, Some(mime), ContentBlock.Image.base64(mime, "opaque base64")))
      ZIO.scoped:
        for
          server <- peer(_ =>
            ZIO.fail(AgentError.McpError("Projection must not invoke a remote tool"))
          )
          bound = McpTool.bind(server.client, "remote", definition)
          checks <- ZIO.foreach(textCases ++ imageCases): (body, mime, visible) =>
            val resource = Json.Obj(
              Chunk(
                "uri"            -> Json.Str(uri),
                "_meta"          -> innerMeta,
                "futureResource" -> Json.Str("preserved")
              ) ++ body.fields ++
                Chunk.fromIterable(mime.map(value => "mimeType" -> Json.Str(value)))
            )
            val expectedCustom = ContentBlock.Custom(
              "mcp/resource",
              Json.Obj(
                Chunk("uri" -> Json.Str(uri), "body" -> body) ++
                  Chunk.fromIterable(mime.map(value => "mimeType" -> Json.Str(value))) ++
                  Chunk(
                    "meta"       -> innerMeta,
                    "extensions" -> Json.Obj("futureResource" -> Json.Str("preserved"))
                  )
              )
            )
            ZIO
              .fromEither(
                McpCallResult.read(
                  Json.Obj(
                    "content" -> Json.Arr(
                      block(
                        "resource",
                        "resource"      -> resource,
                        "_meta"         -> outerMeta,
                        "futureContent" -> Json.Num(7)
                      )
                    )
                  )
                )
              )
              .map: decoded =>
                val projected = bound.encodeResult(decoded)
                val remoteMetadata = projected.payload.metadata
                  .get("mcp")
                  .flatMap(_.asObject)
                  .flatMap(_.get("content"))
                  .flatMap(_.asArray)
                assertTrue(
                  projected.payload.output == Chunk(visible, expectedCustom),
                  remoteMetadata.contains(
                    Chunk(
                      Json.Obj(
                        "meta"       -> outerMeta,
                        "extensions" -> Json.Obj("futureContent" -> Json.Num(7))
                      )
                    )
                  ),
                  projected.state == ToolResultState.Success,
                  projected.output == (visible match
                    case ContentBlock.Text(value) => value
                    case _                        => "")
                )
          requests <- server.requests.get
        yield checks.foldLeft(assertTrue(requests.map(_.method) == Chunk("initialize")))(_ && _)
    ,
    test(
      "explicit binding retains host authority, routes the fixed remote name and publishes typed error content"
    ) {
      ZIO.scoped:
        for
          server <- peer(_ => ZIO.succeed(Right(fullResult)))
          bound = McpTool.bind(server.client, "remote_echo", definition)
          before   <- server.requests.get
          toolkit  <- ZIO.fromEither(Toolkit.empty.register(bound))
          observed <- Ref.make(Chunk.empty[ContentBlock.ToolResult])
          model = LlmClient.mockEffectful((history, _, _) =>
            if history.flatMap(_.toolResults).isEmpty then
              ZIO.succeed(
                TurnOutput.ToolCalls(
                  Msg.assistant(Chunk(ContentBlock.ToolUse("call", "local_echo", Json.Obj())))
                )
              )
            else
              observed
                .set(history.flatMap(_.toolResults))
                .as(TurnOutput.FinalText(Msg.assistant("done")))
          )
          agent   <- ReActAgent.make(client = model, toolkit = toolkit)
          _       <- agent.call(Msg.user("use tool"))
          results <- observed.get
          after   <- server.requests.get
          result = results.headOption
          embeddedChecks <- embeddedResourceRequest
          data            = result.toList.flatMap(_.output)
          mcp             = result.flatMap(_.metadata.get("mcp")).flatMap(_.asObject)
          contentMetadata = mcp.flatMap(_.get("content")).flatMap(_.asArray)
          firstMetadata   = contentMetadata.flatMap(_.headOption).flatMap(_.asObject)
          annotations     = firstMetadata.flatMap(_.get("annotations")).flatMap(_.asObject)
          custom = data.collect { case ContentBlock.Custom(kind, value: Json.Obj) => kind -> value }
        yield assertTrue(
          before.map(_.method) == Chunk("initialize"),
          bound.definition == definition,
          after.drop(1).map(_.params.flatMap(_.get("name"))) == Chunk(
            Some(Json.Str("remote_echo"))
          ),
          result.exists(value =>
            value.id == "call" && value.name.contains("local_echo") &&
              value.state == ToolResultState.Error && !value.isServerTool && !value.isSuspended
          ),
          data.take(3) == List(
            ContentBlock.Text("partial"),
            ContentBlock.Image(MediaSource.Base64("image/png", "opaque image")),
            ContentBlock.Audio(MediaSource.Base64("audio/wav", "opaque audio"))
          ),
          mcp.flatMap(_.get("structuredContent")).contains(structured),
          mcp.flatMap(_.get("meta")).contains(resultMeta),
          mcp
            .flatMap(_.get("extensions"))
            .contains(Json.Obj("futureResult" -> Json.Str("result extension"))),
          contentMetadata.exists(_.size == 7),
          contentMetadata.exists(_.drop(1).forall(_ == Json.Obj("extensions" -> Json.Obj()))),
          annotations.flatMap(_.get("priority")).contains(priority),
          annotations
            .flatMap(_.get("audience"))
            .contains(Json.Arr(Json.Str("user"), Json.Str("assistant"))),
          annotations.flatMap(_.get("lastModified")).contains(Json.Str("opaque timestamp")),
          annotations.flatMap(_.get("extensions")).contains(Json.Obj("future" -> Json.Num(1))),
          firstMetadata.flatMap(_.get("meta")).contains(Json.Obj("source" -> Json.Str("text"))),
          firstMetadata
            .flatMap(_.get("extensions"))
            .contains(Json.Obj("futureContent" -> Json.Num(2))),
          custom == List(
            "mcp/resource_link" -> Json.Obj(
              "uri"         -> Json.Str("opaque uri"),
              "name"        -> Json.Str("resource"),
              "title"       -> Json.Str("title"),
              "description" -> Json.Str("description"),
              "mimeType"    -> Json.Str("text/plain"),
              "size"        -> size
            )
          ) ++ resourceBodies.toList.map(body =>
            "mcp/resource" -> Json.Obj(
              "uri"        -> Json.Str("opaque uri"),
              "body"       -> body,
              "mimeType"   -> Json.Str("text/plain"),
              "meta"       -> Json.Obj("source" -> Json.Str("resource")),
              "extensions" -> Json.Obj("futureResource" -> Json.Num(3))
            )
          )
        ) && embeddedChecks
    },
    test(
      "invalid arguments send nothing while success and remote failure remain distinct toolkit observations"
    ) {
      ZIO.scoped:
        for
          server <- peer(request =>
            ZIO.succeed(
              if request.params
                  .flatMap(_.get("arguments"))
                  .flatMap(_.asObject)
                  .exists(_.get("reject").contains(Json.Bool(true)))
              then Left(McpRpcError(-32602, "rejected remotely"))
              else Right(Json.Obj("content" -> Json.Arr(block("text", "text" -> Json.Str("ok")))))
            )
          )
          bound = McpTool.bind(server.client, "remote_echo", definition)
          toolkit <- ZIO.fromEither(Toolkit.empty.register(bound))
          invalid <- ZIO.foreach(
            Chunk[Json](
              Json.Arr(),
              Json.Obj("x"      -> Json.Null, "x" -> Json.Null),
              Json.Obj("nested" -> Json.Obj("x" -> Json.Null, "x" -> Json.Null))
            )
          )(toolkit.executeSafe("local_echo", _))
          directInvalid <- bound.execute(Json.Obj("x" -> Json.Null, "x" -> Json.Null)).exit
          before        <- server.requests.get
          success <- toolkit.executeSafe(
            "local_echo",
            Json.Obj("name" -> Json.Str("cannot reroute"))
          )
          rejected <- toolkit.executeSafe("local_echo", Json.Obj("reject" -> Json.Bool(true)))
          after    <- server.requests.get
        yield assertTrue(
          invalid.forall(_.state == ToolResultState.Error),
          directInvalid.isFailure,
          before.size == 1,
          success.state == ToolResultState.Success,
          success.output == "ok",
          rejected.state == ToolResultState.Error,
          rejected.output.contains("rejected remotely"),
          after.drop(1).forall(_.params.flatMap(_.get("name")).contains(Json.Str("remote_echo")))
        )
    },
    test(
      "toolkit cancellation joins the borrowed client's active request cleanup and preserves its defect"
    ) {
      ZIO.scoped:
        for
          entered <- Promise.make[Nothing, Unit]
          cleanup <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          defect = new IllegalStateException("MCP tool cleanup failed")
          server <- peer(_ =>
            ZIO.acquireRelease(ZIO.unit)(_ =>
              cleanup.succeed(()).unit *> release.await *> ZIO.die(defect)
            ) *> entered.succeed(()) *> ZIO.never
          )
          toolkit <- ZIO.fromEither(
            Toolkit.empty.register(McpTool.bind(server.client, "remote_echo", definition))
          )
          running    <- toolkit.executeSafe("local_echo", Json.Obj()).forkScoped
          _          <- entered.await
          cancelling <- running.interrupt.forkScoped
          _          <- cleanup.await
          pending    <- cancelling.poll
          _          <- release.succeed(())
          exit       <- cancelling.join
        yield assertTrue(
          pending.isEmpty,
          exit.isInterrupted,
          exit.causeOption.exists(_.defects.contains(defect))
        )
    }
  ) @@ TestAspect.timeout(15.seconds)

# Getting started

This guide describes the current development APIs. Artifacts have not been publicly published, and dependent applications must rebuild when public signatures change. [Package migration notes](../project/README.md#compatibility-policy) are authoritative. The three complete applications below have been compiled against fresh candidate artifacts. This is compilation evidence, not a claim that every provider or MCP server has been exercised. The [completion checklist](architecture/tracker.md) records remaining work.

## Build and local installation

Install JDK 21 and the sbt version pinned in [project/build.properties](../project/build.properties). From this repository, run:

```text
sbt scalafmtAll scalafmtSbt
sbt verify packageArtifacts
sbt "core / publishLocal" "harness / publishLocal" "mcp / publishLocal"
```

The first two commands perform repository verification and packaging. For full backend verification, set `WEISGNOSIS_POSTGRES_URL`, `WEISGNOSIS_POSTGRES_USER` and `WEISGNOSIS_POSTGRES_PASSWORD` for a disposable PostgreSQL database before `verify`; without the URL those suites are skipped. The [fixture contract](../modules/storage-postgresql/src/test/scala/com/weisgnosis/agents/storage/postgresql/README.md) describes initialization and cleanup. `publishLocal` installs development artifacts into your local Ivy repository; it does not publish remotely. Publish `storagePostgresql / publishLocal` too if that optional backend is required. The separate [artifact consumer](../verification/artifact-consumer/README.md) validates actual packaged files without relying on `publishLocal`.

For a new sbt application, create `build.sbt` with:

```scala
scalaVersion := "3.9.0"

libraryDependencies ++= Seq(
  "com.weisgnosis" %% "weisgnosis-agents-core" % "0.1.0-SNAPSHOT",
  "com.weisgnosis" %% "weisgnosis-agents-harness" % "0.1.0-SNAPSHOT",
  "com.weisgnosis" %% "weisgnosis-agents-mcp" % "0.1.0-SNAPSHOT"
)
```

These coordinates resolve only after local installation. Core alone is sufficient for the first two applications; the MCP example also needs the MCP module. Put each complete application below in its own file under `src/main/scala` and run its named main class with sbt.

## Scripted agent

Save as `ScriptedAgent.scala`, then run `sbt "runMain example.ScriptedAgent"`. This makes no network requests.

```scala
package example

import com.weisgnosis.agents.core.agent.ReActAgent
import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.model.Msg
import zio.*

object ScriptedAgent extends ZIOAppDefault:
  def run: ZIO[Any, Throwable, Unit] =
    ZIO.scoped:
      for
        client <- LlmClient.mock(Chunk(TurnOutput.FinalText(Msg.assistant("Hello"))))
        agent <- ReActAgent.make(client = client)
        result <- agent.run(Msg.user("Hello"))
        _ <- Console.printLine(result.reply.text)
      yield ()
```

One agent owns one conversation and serializes its runs. Supply initial history explicitly when constructing another runtime; sharing a client does not share conversation state.

## HTTP completion with a typed tool

Select a compatible Chat Completions SSE endpoint explicitly. Set `WEISGNOSIS_EXAMPLE_ENDPOINT` to its complete URL, `WEISGNOSIS_EXAMPLE_MODEL` to the model identifier and optionally `WEISGNOSIS_EXAMPLE_API_KEY` using your host's secret configuration. This application sends model requests and may incur provider charges. It does not enable any optional media or reasoning profile.

Save as `HttpToolAgent.scala`, then run `sbt "runMain example.HttpToolAgent"`.

```scala
package example

import com.weisgnosis.agents.core.agent.ReActAgent
import com.weisgnosis.agents.core.client.{ChatCompletionsClient, LlmConfig}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.tool.{Tool, Toolkit}
import zio.*
import zio.http.Client
import zio.json.*
import zio.json.ast.Json

object HttpToolAgent extends ZIOAppDefault:
  final case class SumInput(a: Int, b: Int) derives JsonCodec

  private def required(name: String): ZIO[Any, Throwable, String] =
    System.env(name).flatMap(value =>
      ZIO.fromOption(value.filterNot(_.isBlank))
        .orElseFail(AgentError.InvalidConfiguration(s"Set $name"))
    )

  def run: ZIO[Any, Throwable, Unit] =
    ZIO.serviceWithZIO[Client](http =>
      ZIO.scoped:
        for
          endpoint <- required("WEISGNOSIS_EXAMPLE_ENDPOINT")
          model <- required("WEISGNOSIS_EXAMPLE_MODEL")
          key <- System.env("WEISGNOSIS_EXAMPLE_API_KEY")
          tool = Tool.fromFunction[SumInput, Long](
            "sum",
            "Add two integers.",
            Json.Obj(
              "type" -> Json.Str("object"),
              "properties" -> Json.Obj(
                "a" -> Json.Obj("type" -> Json.Str("integer")),
                "b" -> Json.Obj("type" -> Json.Str("integer"))
              ),
              "required" -> Json.Arr(Json.Str("a"), Json.Str("b")),
              "additionalProperties" -> Json.Bool(false)
            ),
            isReadOnly = true
          )(input => ZIO.succeed(input.a.toLong + input.b.toLong))
          client <- ChatCompletionsClient.make(http)
          agent <- ReActAgent.builder()
            .client(client)
            .llmConfig(LlmConfig(model, endpoint = endpoint, apiKey = key.filterNot(_.isBlank)))
            .toolkit(Toolkit.single(tool))
            .maxIterations(4)
            .timeout(1.minute)
            .build
          result <- agent.run(Msg.user("Use sum to add 2 and 3, then answer."))
          _ <- Console.printLine(result.reply.text)
        yield ()
    ).provide(Client.default)
```

Declared Draft 2020-12 constraints are checked by [runtime schema admission](../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#runtime-schema-admission) before fresh authorization and execution; `JsonDecoder[SumInput]` then validates typed input. Provider strictness is a separate generation preference. Optional-null normalization is validation-only and does not rewrite dispatched arguments. The model can choose not to call the tool. For an executable live check that asserts a tool cycle and consumes events to EOF, use the existing [LiveProviderSmoke](../modules/examples/src/main/scala/com/weisgnosis/agents/examples/LiveProviderSmoke.scala) with its [explicit opt-in configuration](../modules/examples/README.md).

Use `ChatCompletionsClient.makeNonStreaming` only when the selected endpoint supports the ordinary JSON mode described in the [client contract](../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#ordinary-json-completions). Reasoning, tool signatures and media extensions require their documented compatible-endpoint selections; no provider detection is performed.

## Owned MCP installation

This application starts a host-selected executable directly, initializes a checked MCP 2025-06-18 client, discovers tools and installs only one explicitly selected name. Set `WEISGNOSIS_EXAMPLE_MCP_EXECUTABLE` to an executable that starts an MCP stdio server without arguments and `WEISGNOSIS_EXAMPLE_MCP_TOOL` to the allowed remote tool name. For servers requiring arguments, construct `Chunk(executable, argument1, argument2)` in host configuration; no shell is invoked.

Save as `OwnedMcp.scala`, then run `sbt "runMain example.OwnedMcp"`.

```scala
package example

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.tool.ToolDef
import com.weisgnosis.agents.mcp.client.{McpClient, McpClientManager}
import com.weisgnosis.agents.mcp.protocol.McpImplementation
import com.weisgnosis.agents.mcp.transport.McpStdioTransport
import zio.*

object OwnedMcp extends ZIOAppDefault:
  private def required(name: String): ZIO[Any, Throwable, String] =
    System.env(name).flatMap(value =>
      ZIO.fromOption(value.filterNot(_.isBlank))
        .orElseFail(AgentError.InvalidConfiguration(s"Set $name"))
    )

  def run: ZIO[Any, Throwable, Unit] =
    ZIO.scoped:
      for
        executable <- required("WEISGNOSIS_EXAMPLE_MCP_EXECUTABLE")
        allowed <- required("WEISGNOSIS_EXAMPLE_MCP_TOOL")
        manager <- McpClientManager.make()
        _ <- manager.register(
          "selected-server",
          McpStdioTransport.open(Chunk(executable)).flatMap(transport =>
            McpClient.open(transport, McpImplementation("getting-started", "0.1.0"))
          ),
          maxPages = 8,
          select = definition => Right(
            Option.when(definition.name == allowed)(
              ToolDef(
                definition.name,
                definition.description.getOrElse("Selected remote tool"),
                definition.inputSchema,
                isConcurrencySafe = false,
                isReadOnly = false
              )
            )
          )
        )
        toolkit <- manager.toolkit
        _ <- Console.printLine(s"Installed tools: ${toolkit.tools.keys.toList.sorted.mkString(", ")}")
      yield ()
```

The acquisition places both transport and initialized client inside manager-owned resources. The final scope closes registrations and reaps the process. Use the returned Toolkit in `ReActAgent.make(toolkit = toolkit, client = client, modelInputTransform = McpModelInputTransform())` **before this scope closes**; import `com.weisgnosis.agents.mcp.tool.McpModelInputTransform`. Pass an existing request hook as `McpModelInputTransform(existingHook)` to retain its settings and scoped behavior. This explicit adapter describes Custom tool data for the request while preserving canonical MCP resources. Typed images still require an ImageParts endpoint profile; unsupported audio remains an error. A copied Toolkit does not transfer ownership. Remote read-only/idempotency annotations do not grant local permissions. Replace the default allow-authorizer with an explicitly configured permission adapter when required.

For HTTP servers, use the [Streamable HTTP transport](../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/transport/README.md) under its borrowed HTTP client and owned request/listener scopes. The [resource client contract](../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/client/README.md#resource-client-contract) covers resource discovery, reads and scoped change observation. Legacy HTTP/SSE and explicit revision profiles have independently approved source scopes and full verification in the [transport contract](../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/transport/README.md#legacy-http-sse-and-explicit-revision-selection). Version selection uses an explicit proposal and checked supported alternatives, matching the pinned SDK wire-selection behavior; see the [finite builder-method classification](architecture/test-parity.md#supported-version-builder-methods). Discovery-cache invalidation is separate.

## Streams, approvals and persistence

`agent.run` returns the authoritative `AgentResult`. For events and the result together, fully consume `agent.streamWithResult(message)`: `AgentRunUpdate.Event` carries events and `AgentRunUpdate.Result` carries the terminal value. A partial consumer may miss terminal output; cancellation still joins owned cleanup. Do not infer success from a final text delta or an early tool event. See the [runtime stream contract](../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md).

Interactive permissions use process-local Promises. The [checkpoint permission adapter](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md) instead yields serialized pending work; hosts authenticate approvals, persist the suspended result and call the checked resume APIs. External-tool suspension is distinct from approval. Dispatch externally only after receiving a validated suspended result from `run` or a fully consumed result stream. Correlation IDs confer no authentication or exactly-once authority.

For model-facing task enumeration, explicitly install [SubagentTaskListTool](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#model-facing-task-enumeration) for one host-owned registry. Its bounded ID/status pages do not expose child payloads or grant access to other sessions; its current verification and review status are recorded in that contract.

The [host assembly example](../modules/examples/README.md) composes existing capabilities without an additional library facade. Install compaction, eviction, recording and memory scheduling explicitly; constructors do not silently start them. Keep owner scopes open while their workers or sessions are needed, and inspect failure outcomes rather than discarding receipts.

Local stores require host-controlled filesystem ancestors; path canonicalization is not an atomic sandbox. The optional [PostgreSQL backend](../modules/storage-postgresql/README.md) requires a host-owned JDBC driver and DataSource. [Versioned adapters](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/storage/README.md) use explicit addresses, checked revisions and bounded data. CAS admission does not establish execution locks or durable workflow recovery. Physical remote pruning needs additional ownership/fencing contracts and is not implemented.

## Before production integration

- [ ] Select and validate the actual provider/profile, request limits and cancellation behavior.
- [ ] Configure tool authorization, authenticated approval delivery and secret/log privacy.
- [ ] Choose and own resource lifetimes, persistence, checkpoint claims and idempotency.
- [ ] Complete applicable platform, upstream parity and release gates in the [tracker](architecture/tracker.md).

These are host integration tasks, not completed guarantees inferred from the examples. The library's original-plan gaps remain visible in the [parity audit](architecture/test-parity.md#original-plan-disposition).

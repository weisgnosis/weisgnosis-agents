# Getting started

This guide uses local development snapshots. For versioned candidate artifacts, see [package installation](packages.md). Dependent applications must rebuild when public signatures change; [package migration notes](../project/README.md#compatibility-policy) are authoritative. The complete applications below have been compiled against fresh candidate artifacts. This is compilation evidence, not a claim that every provider or MCP server has been exercised. The [completion checklist](architecture/tracker.md) records remaining work.

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

These coordinates resolve only after local installation. `ScriptedAgent`, `HttpToolAgent` and `ReleaseAssistant` need only core; `OwnedMcp` also needs the MCP module. Put each complete application below in its own file under `src/main/scala` and run its named main class with sbt.

## Run a model-driven file task

The repository includes [LiveFileTask](../modules/examples/src/main/scala/com/weisgnosis/agents/examples/LiveFileTask.scala), a complete application that asks a selected model to write and then read one disposable file. Set these through process-local secret configuration:

- `WEISGNOSIS_LIVE_SMOKE=enabled`
- `WEISGNOSIS_LIVE_ENDPOINT`: the complete Chat Completions URL, including `/chat/completions`.
- `WEISGNOSIS_LIVE_MODEL`: a chat model selected from your endpoint's model catalog.
- `WEISGNOSIS_LIVE_API_KEY`: a bearer credential when required; never commit it.

Run `sbt "examples / runMain com.weisgnosis.agents.examples.LiveFileTask"`. Success reports one write, one read and removal of the owned fixture. Verification checks actual bytes, the read observation in conversation history and authoritative Completed state. The model receives no path or command capability. The program permits three iterations and defaults to a five-minute run deadline. Set `WEISGNOSIS_LIVE_TIMEOUT_SECONDS` to 10–1,800 seconds to select another deadline; joined native cleanup can extend beyond it. See [example limits and live evidence](../modules/examples/README.md#model-driven-file-task).

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

One agent owns one conversation and serializes its runs. Supply initial history explicitly when constructing another runtime; sharing a client does not share conversation state. The default agent run deadline is five minutes; the HTTP example below explicitly selects one minute. Deadline expiration requests cancellation, and returning still joins owned cleanup, which can exceed that budget.

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

## Release evidence assistant

This larger version of the [first agent](../README.md#first-agent) accepts an injected evidence-reading service function. It uses a real compatible SSE Chat Completions endpoint with a **fixed read-only evidence fixture**; it does not read your CI system. Replace `readEvidence` with your own service function to connect real checks. The host supplies the tool; model text never authorizes a deployment.

Install core with the [release resolver](packages.md), or use the [source setup](#build-and-local-installation) above. Set `WEISGNOSIS_EXAMPLE_ENDPOINT` to the full URL ending in `/chat/completions`, `WEISGNOSIS_EXAMPLE_MODEL` to a compatible tool-calling model and optionally `WEISGNOSIS_EXAMPLE_API_KEY` through the process environment. Requests may incur provider charges.

Save this complete application as `src/main/scala/ReleaseAssistant.scala` and run `sbt "runMain example.ReleaseAssistant"`:

```scala
package example

import com.weisgnosis.agents.core.agent.{AgentResult, ReActAgent}
import com.weisgnosis.agents.core.client.{ChatCompletionsClient, LlmClient, LlmConfig}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.tool.{Tool, Toolkit}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.http.Client

final case class ReleaseQuery(release: String) derives JsonCodec

def inspectRelease(
    client: LlmClient,
    config: LlmConfig,
    readEvidence: ReleaseQuery => IO[AgentError, String]
): IO[AgentError, AgentResult] =
  ZIO.scoped:
    val releaseQuerySchema: Json.Obj = Json.Obj(
      "type" -> Json.Str("object"),
      "properties" -> Json.Obj("release" -> Json.Obj("type" -> Json.Str("string"))),
      "required" -> Json.Arr(Json.Str("release")),
      "additionalProperties" -> Json.Bool(false)
    )
    val evidence: Tool[Any, ReleaseQuery, String] = Tool.fromFunction[ReleaseQuery, String](
      "read_release_evidence",
      "Read build, test and security findings for a release.",
      releaseQuerySchema,
      isReadOnly = true
    )(readEvidence)
    for
      agent <- ReActAgent.builder()
        .client(client)
        .llmConfig(config)
        .toolkit(Toolkit.single(evidence))
        .maxIterations(6)
        .timeout(1.minute)
        .build
      result <- agent.run(Msg.user("Read the evidence for RC1 and explain any release blockers."))
    yield result

object ReleaseAssistant extends ZIOAppDefault:
  private def required(name: String): ZIO[Any, Throwable, String] =
    System.env(name).flatMap(value =>
      ZIO.fromOption(value.filterNot(_.isBlank))
        .orElseFail(AgentError.InvalidConfiguration(s"Set $name"))
    )

  def run: ZIO[Any, Throwable, Unit] =
    val readEvidence: ReleaseQuery => IO[AgentError, String] = query =>
      if query.release == "RC1" then
        ZIO.succeed("BUILD-1 passed; TEST-1 passed; SEC-1 failed: high severity")
      else ZIO.fail(AgentError.InvalidConfiguration("No fixture evidence for that release"))
    ZIO.serviceWithZIO[Client](http =>
      ZIO.scoped:
        for
          endpoint <- required("WEISGNOSIS_EXAMPLE_ENDPOINT")
          model <- required("WEISGNOSIS_EXAMPLE_MODEL")
          key <- System.env("WEISGNOSIS_EXAMPLE_API_KEY")
          client <- ChatCompletionsClient.make(http)
          result <- inspectRelease(
            client,
            LlmConfig(model, endpoint = endpoint, apiKey = key.filterNot(_.isBlank)),
            readEvidence
          )
          _ <- Console.printLine(s"${result.state}: ${result.reply.text}")
        yield ()
    ).provide(Client.default)
```

A tool-using run receives the fixture's passed build/tests and high-severity security failure, then explains the release blocker. The program prints the runtime state and model reply. The model may skip the tool or phrase the answer differently; this application is an integration example, not a tool-execution or answer-quality assertion. Provider failures fail the effect. For checked tool execution and host-validated decisions, use the [live coordination fixture](../modules/examples/README.md#useful-coordination-and-pipeline-controls).

The application has been compiled without compiler warnings against the published RC1 core artifact, then driven with a scripted client through the actual tool path. This is not a new live-provider run. `isReadOnly` describes the tool to runtime policy; install a host authorizer for restricted tools. Cancellation joins owned cleanup, which can extend the one-minute deadline.

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

`McpClient.open` defaults to a 30-second budget for initialization and separately for each list/call operation. Use its checked `McpClient.Deadlines` overload to select independent initialization and request budgets. Expiration requests cancellation; returning still joins owned cleanup, which may exceed the selected budget.

## Streams, approvals and persistence

`agent.run` returns the authoritative `AgentResult`. For events and the result together, fully consume `agent.streamWithResult(message)`: `AgentRunUpdate.Event` carries accepted diagnostics and `AgentRunUpdate.Result` carries the complete terminal value when both execution and delivery succeed. The default host policy bounds queued diagnostics to 256 events, 4 MiB of encoded UTF-8 bytes and 1 MiB per event. Overflow closes diagnostics immediately and reports delivery failure after execution cleanup; it does not roll back tools or interrupt execution. Retain a prepared invocation to observe its exact execution outcome through `termination` separately. A partial consumer cancels attached execution, joins cleanup and may miss terminal output. Full results and history are outside the diagnostic budget. Do not infer success from a final text delta or an early tool event. See the [runtime stream contract](../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#result-bearing-streams).

Interactive permissions use process-local Promises. The [checkpoint permission adapter](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md) instead yields serialized pending work; hosts authenticate approvals, persist the suspended result and call the checked resume APIs. External-tool suspension is distinct from approval. Dispatch externally only after receiving a validated suspended result from `run` or a fully consumed result stream. Correlation IDs confer no authentication or exactly-once authority.

For model-facing task enumeration, explicitly install [SubagentTaskListTool](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#model-facing-task-enumeration) for one host-owned registry. Its bounded ID/status pages do not expose child payloads or grant access to other sessions; the linked contract describes ownership, pagination and supported behavior.

The [host assembly example](../modules/examples/README.md) composes existing capabilities without an additional library facade. Install compaction, eviction, recording and memory scheduling explicitly; constructors do not silently start them. Keep owner scopes open while their workers or sessions are needed, and inspect failure outcomes rather than discarding receipts.

Local stores require host-controlled filesystem ancestors; path canonicalization is not an atomic sandbox. The optional [PostgreSQL backend](../modules/storage-postgresql/README.md) requires a host-owned JDBC driver and DataSource. [Versioned adapters](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/storage/README.md) use explicit addresses, checked revisions and bounded data. CAS admission does not establish execution locks or durable workflow recovery. Physical remote pruning needs additional ownership/fencing contracts and is not implemented.

## Before production integration

- [ ] Select and validate the actual provider/profile, request limits and cancellation behavior.
- [ ] Configure tool authorization, authenticated approval delivery and secret/log privacy.
- [ ] Choose and own resource lifetimes, persistence, checkpoint claims and idempotency.
- [ ] Complete applicable platform, upstream parity and release gates in the [tracker](architecture/tracker.md).

These are host integration tasks, not completed guarantees inferred from the examples. The library's original-plan gaps remain visible in the [parity audit](architecture/test-parity.md#original-plan-disposition).

## Verify selected provider profiles

After configuring the explicit live environment above, run
`sbt "examples / runMain com.weisgnosis.agents.examples.LiveProviderProfiles"`.
The [profile guide](../modules/examples/README.md#selected-structured-output-and-cancellation-profiles)
explains its exact native/synthetic JSON checks, joined local-tool cancellation and per-profile bounds.
The default All profile reports success only after all three checks pass. Use
WEISGNOSIS_LIVE_PROFILE=synthetic or cancellation for independent selected checks; require the
explicit selected-profile success message, since a zero sbt exit code alone is insufficient. HTTP-response cancellation and universal provider
compatibility remain outside this application.

## Agent collaboration and bounded outcomes

Use [MsgHub and Pipeline](../modules/core/src/main/scala/com/weisgnosis/agents/core/coordination/README.md) for attributed sequential handoff or bounded parallel runs. After parallel participants finish, flush queued hub observations before starting the next round. Queued delivery is not model execution or a durable acknowledgement; inspect or transfer retained data before releasing the hub handle. Direct passive observation still rejects busy participants.

Explicit provider token exhaustion retains visible partial text and returns a Stopped result. Check AgentResult.state before accepting an answer or advancing a workflow; partial structured output never succeeds. Protocol termination remains mandatory; only validated non-truncated calls can execute. See the [token-budget contract](../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#token-budget-exhaustion).

Persistent memory aggregates now default to 1 MiB encoded UTF-8 and 4,096 combined entries. Previously oversized records fail without modification. Hosts can explicitly select larger checked limits for controlled migration; the adapter does not delete archives automatically. See [persistent memory](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md).

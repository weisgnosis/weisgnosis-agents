# WeisGnosis Agents

<picture>
  <source media="(max-width: 600px)" srcset="docs/assets/weisgnosis-hero-mobile.png">
  <img src="docs/assets/weisgnosis-hero.png" width="100%" alt="WeisGnosis Agents — Models reason. Applications decide. Amber proposal paths pass through a cyan application-control gate.">
</picture>

**Tool-using agents and collaborating teams, built into your Scala service.**
WeisGnosis Agents is an embeddable Scala 3 / ZIO 2 runtime: connect your business tools and models, then keep execution, permissions and resource ownership in application code.

[![Scala 3.9.0](https://img.shields.io/badge/Scala-3.9.0-CB3544)](https://www.scala-lang.org/)
[![ZIO 2.1.26](https://img.shields.io/badge/ZIO-2.1.26-087E8B)](https://zio.dev/)
[![Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-475569)](LICENSE)

[**Run the proof**](#quickstart) · [**Build your first agent**](docs/getting-started.md#release-evidence-assistant) · [Install RC1](docs/packages.md) · [Run a team](#multi-agent-workflows) · [API guides](docs/README.md)

## A release decision backed by evidence

The build passes. Tests pass. Security reports a high-severity finding. An **Analyst** reads the evidence, a **Checker** receives the findings, and a **Reporter** proposes a blocked release. Your host validates the proposal against the actual tool observations.

<picture>
  <source media="(max-width: 600px)" srcset="docs/assets/release-team-mobile.svg">
  <img src="docs/assets/release-team.svg" width="100%" alt="BUILD-1 passed, TEST-1 passed and SEC-1 high severity feed an Analyst. A Checker and Reporter receive attributed findings. The host validates the proposed blocked-release decision against the actual evidence.">
</picture>

This is an executable, host-guided fixture with [selected same-model and mixed-model live results](docs/live-e2e-results.md#e4-useful-room-and-pipeline-evidence). Each agent keeps its own conversation; a peer's tool calls supply context without gaining local execution authority. [Read the application](modules/examples/src/main/scala/com/weisgnosis/agents/examples/LiveCoordinationEvidence.scala) or [run the team](#multi-agent-workflows).

## Quickstart

**JDK 21 · sbt 1.12.11 · no credentials or model downloads.** From a terminal:

```sh
git clone https://github.com/weisgnosis/weisgnosis-agents.git
cd weisgnosis-agents
sbt "examples / runMain com.weisgnosis.agents.examples.SyntheticRuntimeComparison"
```

This runs **real tools through the public ReAct runtime with a scripted model**. The executable checks overlapping tool execution, repeated interruption, held cleanup and continuation. It prints a JSON receipt; require every contract field to be `true`.

<details>
<summary><strong>Expected receipt — these are assertions, not a model's success claim</strong></summary>

```json
{
  "implementation": "scala-zio-react",
  "parallelOverlap": true,
  "callsPersistedBeforeEntry": true,
  "completedResultPreserved": true,
  "unfinishedOnlyReconciled": true,
  "cancellationJoinedCleanup": true,
  "interruptionPreserved": true,
  "recoveryExactlyOnce": true,
  "historyComplete": true,
  "sameRuntimeContinuation": true
}
```

The receipt is shown formatted for readability. The application emits one JSON line and fails its assertions if any contract is false.

</details>

[Read the complete executable](modules/examples/src/main/scala/com/weisgnosis/agents/examples/SyntheticRuntimeComparison.scala) · [Inspect the lifecycle contract](modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md) · [Selected real-model interruption results](docs/live-e2e-results.md#e3-cooperative-interruption-and-same-runtime-continuation)

This demonstrates process-local history and owned local cleanup. It does not imply rollback of external side effects, cancellation of remote inference or recovery of executing work after a process restart.

## Interrupt halfway. Keep the work already done.

Now stop an evidence-gathering turn halfway through. One read has finished; the other is still running. In the deterministic scenario below, the host requests interruption twice while the second tool deliberately holds cleanup open.

**Keep the successful observation. Wait for owned cleanup. Continue with a complete history.**

<picture>
  <source media="(max-width: 600px)" srcset="docs/assets/execution-trace-mobile.svg">
  <img src="docs/assets/execution-trace.svg" width="100%" alt="Two tool calls are recorded before execution. completed_read succeeds and its result is preserved. held_read is interrupted; cancellation waits for cleanup, then only its unfinished call receives Error. The same runtime continues without repeating the tools.">
</picture>

WeisGnosis records assistant calls before local dispatch, preserves completed observations, joins owned cleanup, and reconciles only unfinished calls. The next turn can use the same runtime and its intact conversation history.

## Why this exists

> Scala 3 was the language I wanted to build with. I couldn't find agent tooling that fit what I needed, so I started with AgentScope Java. I valued its approach, even though it wasn't the right fit for me. That experience inspired WeisGnosis Agents.
>
> — The project's author

The goal is to make agent development feel at home in Scala: native data types and APIs, composable effects, explicit dependencies and owned resources. AgentScope's ideas are the starting point; Scala and ZIO shape the implementation.

**An agent becomes application code the moment it calls your tools.**

A model proposes an action. Your service has to authorize it, validate its arguments, execute the operation and know whether it actually finished. Add another agent, a waiting approval or a user interruption, and the lifecycle becomes part of the product.

WeisGnosis brings that work into the Scala/ZIO model your service already uses. Dependencies and recoverable failures remain explicit in effect signatures. Tool execution failures become observations the model can respond to; interruption retains its own meaning.

Use it when you want to:

- **Embed an assistant in a Scala service:** expose selected business operations as typed tools and compose agent work with existing ZIO dependencies.
- **Keep humans in consequential workflows:** install permission gates, suspend before acting, then resume a checked checkpoint under current host authorization.
- **Coordinate specialists:** hand off completed replies, fan out independent work or share attributed findings between agents with separate histories.

[Why Scala and ZIO](docs/scala-zio-positioning.md) explains the architectural choice and its limits.

## From a tool call to a service workflow

**Your service function becomes the agent's tool.** In this example, the host supplies `readEvidence`: an effect that reads build, test and security findings for a release. The schema controls the model-facing arguments; Scala keeps the decoded input and handler types connected.

Input: `{"release":"RC1"}`. The handler returns evidence text such as `BUILD-1 passed; TEST-1 passed; SEC-1 failed: high severity`. The agent receives that text as a tool observation before its next model turn. Your real handler supplies the data; these values illustrate its contract.

The adapter is small; `readEvidence` remains your ordinary effectful Scala function. `ReleaseQuery` and its explicit schema are defined in the complete function below.

```scala
val evidence: Tool[Any, ReleaseQuery, String] = Tool.fromFunction[ReleaseQuery, String](
  "read_release_evidence",
  "Read build, test and security findings for a release.",
  releaseQuerySchema,
  isReadOnly = true
)(readEvidence)
```

<details>
<summary><strong>Complete integration: input type, schema, tool and scoped agent run</strong></summary>

```scala
import com.weisgnosis.agents.core.agent.{AgentResult, ReActAgent}
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.tool.{Tool, Toolkit}
import zio.*
import zio.json.*
import zio.json.ast.Json

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
```

</details>

`AgentResult.state` distinguishes `Completed`, `Suspended`, `Interrupted` and `Stopped`. The reply is model content; the state describes the runtime outcome. Deadline cancellation still waits for owned cleanup, so elapsed time may exceed the deadline.

**Tools are capabilities you explicitly supply.** Typed functions use `JsonCodec` plus an explicit JSON Schema; external arguments are checked before execution. Add [MCP tools/resources](docs/getting-started.md#owned-mcp-installation) when the capability lives behind a server. Install authorization for restricted operations: the default tool authorizer allows execution.

[Run this release-evidence assistant](docs/getting-started.md#release-evidence-assistant) · [Permission and approval guide](modules/harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md)

## Multi-agent workflows

Run the release-evidence team with a live model after [configuring the live environment](modules/examples/README.md#live-endpoint-verification):

```sh
sbt "examples / runMain com.weisgnosis.agents.examples.LiveCoordinationEvidence"
```

The host guides the tool phases and checks the exact decision. This demonstrates controlled collaboration rather than autonomous planning. Live execution requires a configured endpoint, selected models and any required credentials, and may incur provider charges. For a small no-network introduction to peer delivery, use [MsgHubConversation](modules/examples/README.md#persistent-agent-conversation).

Choose the composition your workflow needs:

| Composition | What it gives your application |
| --- | --- |
| **Sequential pipeline** | Attributed handoff to the next specialist after a completed stage. |
| **Bounded fanout** | Concurrent independent work; outcomes collected in declaration order. |
| **MsgHub** | Attributed peer observations, separate histories and host-selected turns. Flush queued observations explicitly between rounds. |
| **Supervised subagents** | Scoped child tasks; promoted work transfers to an explicit supervisor. |

[Pipeline and MsgHub guide](modules/core/src/main/scala/com/weisgnosis/agents/core/coordination/README.md) · [Subagent ownership](modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md)

## Install what your host needs

**[0.1.0-RC1](https://github.com/weisgnosis/weisgnosis-agents/releases/tag/v0.1.0-RC1) is available through GitHub Packages and a downloadable Maven repository.** The [installation guide](docs/packages.md) gives exact resolver, authentication and checksum instructions. All artifacts use organization `com.weisgnosis` and Scala `%%` coordinates. Requires Scala 3.9.0 / JDK 21; candidate APIs may change. Artifacts are unsigned and are not on Maven Central.

<details>
<summary><strong>Copy the release dependency — no GitHub token required with the Maven ZIP</strong></summary>

Download `weisgnosis-agents-0.1.0-RC1-maven.zip` and `SHA256SUMS` from the [release](https://github.com/weisgnosis/weisgnosis-agents/releases/tag/v0.1.0-RC1). Verify the archive checksum, extract it, and set the resolver to the directory immediately above `com/`. In your application's `build.sbt`:

```scala
scalaVersion := "3.9.0"

resolvers += "WeisGnosis release archive" at
  file("/absolute/path/to/extracted-maven-repository").toURI.toString

libraryDependencies +=
  "com.weisgnosis" %% "weisgnosis-agents-core" % "0.1.0-RC1"
```

Set `sbt.version=1.12.11` in `project/build.properties`. Other dependencies resolve from Maven Central. If you prefer GitHub Packages, use the [authenticated resolver](docs/packages.md#github-packages).

</details>

| Module | Add it for |
| --- | --- |
| [core](modules/core/README.md) | ReAct, typed tools, structured output, model clients, pipelines and MsgHub. Start here. |
| [harness](modules/harness/README.md) | Approvals, planning, compaction, output offload, memory, transcripts, skills and subagents. |
| [mcp](modules/mcp/README.md) | Tools/resources over stdio, Streamable HTTP and legacy HTTP/SSE. |
| [storage-postgresql](modules/storage-postgresql/README.md) | Conditional JSON persistence through your DataSource. |
| [otel](modules/otel/README.md) | Tracing through your OpenTelemetry SDK/exporter. |

Artifact names are `weisgnosis-agents-<module>`, for example `weisgnosis-agents-core`. For source development, `sbt "core / publishLocal"` installs `0.1.0-SNAPSHOT`; [the source setup](docs/getting-started.md#build-and-local-installation) covers the separate consumer application and optional modules.

## Boundaries to know

- **You own the host:** authentication, business tools, durable orchestration and idempotency. There is no bundled general shell/file/web toolkit or RAG engine.
- **History is not a running process:** persistence restores checked data or suspension; it does not resurrect fibers. Process-local approvals and hub queues do not survive restarts automatically.
- **Compatibility is explicit:** OpenAI-compatible endpoints and native Anthropic/Gemini adapters have [documented profiles](modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md). Selected live results do not certify every model, provider or platform.
- **Infrastructure keeps its own limits:** path checks are not an atomic filesystem sandbox. PostgreSQL needs a host-owned pool/schema; uncertain writes are not automatically replayed. Frozen binary compatibility and Scala 3.3 cross-builds are not supplied.

## Learn more and contribute

[Getting started](docs/getting-started.md) · [API guides](docs/README.md) · [Architecture](ARCHITECTURE.md) · [Live evidence](docs/live-e2e-results.md) · [Completion scope](docs/architecture/tracker.md)

Concrete counterexamples, bug reports and small working integrations are welcome. [Contributing](CONTRIBUTING.md) explains checks and independent review; run `sbt verify` for a contribution and `sbt packageArtifacts` for local archives. [Open an issue](https://github.com/weisgnosis/weisgnosis-agents/issues) or [help other Scala developers discover the project](https://github.com/weisgnosis/weisgnosis-agents/stargazers).

## License and acknowledgements

[Apache-2.0](LICENSE). Adapted agent mechanics are attributed to [AgentScope Java](https://github.com/agentscope-ai/agentscope-java); [upstream mappings](docs/architecture/test-parity.md) explain transferred behavior and intentional adaptations. WeisGnosis Agents is not a drop-in AgentScope replacement.

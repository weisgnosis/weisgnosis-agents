# WeisGnosis Agents

**Build tool-using agents and collaborating teams in Scala 3 and ZIO 2.**

[![Scala 3.9.0](https://img.shields.io/badge/Scala-3.9.0-red)](https://www.scala-lang.org/)
[![ZIO 2](https://img.shields.io/badge/ZIO-2.1.26-blue)](https://zio.dev/)
[![Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue)](LICENSE)
[![GitHub stars](https://img.shields.io/github/stars/weisgnosis/weisgnosis-agents?style=social)](https://github.com/weisgnosis/weisgnosis-agents/stargazers)

[Quickstart](#quickstart) · [Multi-agent workflows](#multi-agent-workflows) · [Examples](#try-it) · [Documentation](docs/README.md) · [Contributing](CONTRIBUTING.md)

Give a model typed tools, connect MCP servers, and compose agents into sequential or parallel workflows. Keep conversation state, permissions and completion under application control. Everything runs as ZIO effects inside scopes your application owns.

Use it to embed assistants, tool-driven automation and agent teams in an existing Scala service or CLI. Bring your own model endpoint and business tools; add context management, approvals, persistence and tracing as needed.

## Why WeisGnosis Agents?

| What you need | What the library provides |
| --- | --- |
| Tools you can control | Typed inputs/outputs, runtime argument validation and explicit authorization hooks. Tool failures become model observations. |
| Cancellation that preserves context | Calls enter conversation history before execution. Interruption joins owned cleanup and reconciles unfinished local calls, preserving completed results. |
| Agents that work together | Sequential handoff, bounded parallel fanout, scoped subagents and MsgHub message sharing with separate agent histories. Peer tool calls remain inert observations. |
| Context that stays usable | Pair-preserving compaction, merged summaries and offloading of oversized tool output; failed persistence retains the original observation. |
| Integration with your stack | MCP tools/resources, OpenAI-compatible endpoints, native Anthropic and Gemini adapters, plus optional PostgreSQL and OpenTelemetry modules. |

The principle is simple: **the runtime owns state and completion; the model proposes actions.** A model saying “done” does not make a tool complete or grant permission to execute it.

## Quickstart

**Requirements:** JDK 21, Scala 3.9.0 and sbt 1.12.11. Artifacts currently install from source; Maven Central coordinates are not published. Public API changes may require rebuilding consumers.

### 1. Install core locally

```sh
git clone https://github.com/weisgnosis/weisgnosis-agents.git
cd weisgnosis-agents
sbt "core / publishLocal"
```

Create a separate application directory. Add `project/build.properties`:

```properties
sbt.version=1.12.11
```

And `build.sbt`:

```scala
scalaVersion := "3.9.0"

libraryDependencies +=
  "com.weisgnosis" %% "weisgnosis-agents-core" % "0.1.0-SNAPSHOT"
```

### 2. Give a real model a typed tool

Save this as `src/main/scala/HttpToolAgent.scala` in your application:

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
          _ <- Console.printLine(s"${result.state}: ${result.reply.text}")
        yield ()
    ).provide(Client.default)
```

Set these environment variables in the application's process:

| Variable | Value |
| --- | --- |
| `WEISGNOSIS_EXAMPLE_ENDPOINT` | Complete compatible SSE Chat Completions URL, ending in `/chat/completions`. |
| `WEISGNOSIS_EXAMPLE_MODEL` | A tool-calling chat model available at your endpoint. |
| `WEISGNOSIS_EXAMPLE_API_KEY` | Bearer credential, if your endpoint requires one. Keep it out of source control. |

Then run:

```sh
sbt "runMain example.HttpToolAgent"
```

The model receives a `sum` tool backed by a Scala function. A successful tool-using run returns the sum of 2 and 3; output includes the runtime state and the model's answer. The model can choose not to call a tool, so this small application is not a tool-execution assertion. Network/provider failures fail the effect; `Interrupted`, `Stopped` and `Suspended` are distinct returned states. See the [live smoke](modules/examples/README.md#live-endpoint-verification) for an executable check that requires a tool call and successful completion.

`ZIO.scoped` owns execution resources. The example selects a one-minute deadline; cancellation still waits for owned cleanup, which can extend beyond that deadline. Calls to a real model may incur provider charges.

Prefer a first run without credentials? The [scripted quickstart](docs/getting-started.md#scripted-agent) runs entirely offline.

## Multi-agent workflows

Choose the composition that matches your application:

- **Sequential pipeline:** hand a completed reply to the next agent, with sender attribution and a separate conversation per stage.
- **Parallel fanout:** ask independent agents concurrently with an explicit parallelism limit; collect outcomes in declaration order.
- **MsgHub:** let persistent agents share replies while the host chooses who takes the next turn. Busy recipients queue observations for an explicit between-round flush.
- **Subagents:** delegate through scoped child execution, with supervisor-owned promotion and explicit result delivery.

[Pipeline and MsgHub guide](modules/core/src/main/scala/com/weisgnosis/agents/core/coordination/README.md) · [Runnable conversation](modules/examples/src/main/scala/com/weisgnosis/agents/examples/MsgHubConversation.scala) · [Subagent guide](modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md)

## Try it

Run these from the cloned library repository:

| Example | What it demonstrates |
| --- | --- |
| `sbt "examples / runMain com.weisgnosis.agents.examples.MsgHubConversation"` | Offline conversation: Bob receives and summarizes Alice's recommendation. No model credentials needed. |
| `sbt "examples / runMain com.weisgnosis.agents.examples.LiveFileTask"` | A real model writes and reads a disposable file; the application checks bytes, observations, completion and cleanup. Requires live configuration. |
| `sbt "examples / runMain com.weisgnosis.agents.examples.LiveCoordinationEvidence"` | Live sequential, parallel and mixed-model room scenarios with checked peer-context isolation. Requires live configuration. |

[Configure live examples](modules/examples/README.md#live-endpoint-verification) before running networked applications. [Recorded live results](docs/live-e2e-results.md) distinguish successful selected runs, failed profiles and untested behavior. [MCP setup](docs/getting-started.md#owned-mcp-installation) shows how to attach external tools without adding service-specific adapters to the runtime.

## Modules

Start with core. Install the other modules only when your application needs them.

| Artifact | Adds |
| --- | --- |
| [weisgnosis-agents-core](modules/core/README.md) | ReAct execution, messages/events, typed tools, structured output, model clients, pipelines and MsgHub. |
| [weisgnosis-agents-harness](modules/harness/README.md) | Compaction, output offload, memory, transcripts, approvals, planning, skills and subagents. |
| [weisgnosis-agents-mcp](modules/mcp/README.md) | MCP tools/resources over stdio, Streamable HTTP and legacy HTTP/SSE. |
| [weisgnosis-agents-storage-postgresql](modules/storage-postgresql/README.md) | Conditional JSON persistence using a host-supplied DataSource. |
| [weisgnosis-agents-otel](modules/otel/README.md) | Tracing through a host-owned OpenTelemetry SDK/exporter. |

Install optional modules with their sbt project names: `harness`, `mcp`, `storagePostgresql` or `otel`, for example `sbt "mcp / publishLocal"`. PostgreSQL also needs harness installed: `sbt "harness / publishLocal" "storagePostgresql / publishLocal"`. Add the corresponding artifact to your application's dependencies. All use organization `com.weisgnosis`, Scala `%%` coordinates and version `0.1.0-SNAPSHOT`.

## Boundaries to know

- This is an embeddable library. Your host supplies business tools or MCP servers, authentication, durable workflow orchestration and idempotency. There is no bundled general shell/file/web toolkit or RAG engine.
- Conversation history is runtime state, not automatic durable storage. Process-local approvals and hub queues do not survive restarts. Persistent sessions restore checked data; they do not resurrect executing fibers.
- Provider features are explicit profiles, not universal compatibility. See [supported model adapters](modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md). Native-provider integration checks and selected live OpenAI-compatible runs are different evidence.
- Local path validation is not an atomic filesystem sandbox. PostgreSQL requires a host-owned pool/schema; the library does not automatically replay uncertain writes. Scala 3.3 cross-builds and frozen binary compatibility are not supplied.

## Learn more and contribute

[Getting started](docs/getting-started.md) · [Architecture](ARCHITECTURE.md) · [Full documentation](docs/README.md) · [Current completion scope](docs/architecture/tracker.md)

To check a contribution, run `sbt verify`; to build local distribution archives, run `sbt packageArtifacts`. See [CONTRIBUTING.md](CONTRIBUTING.md) for focused checks, optional backend fixtures and independent review. Bug reports and concrete counterexamples are welcome in [GitHub Issues](https://github.com/weisgnosis/weisgnosis-agents/issues).

## Star history

If this library is useful to you, star the repository to help other Scala developers discover it.

[![GitHub star history for WeisGnosis Agents](https://api.star-history.com/svg?repos=weisgnosis/weisgnosis-agents&type=Date)](https://www.star-history.com/#weisgnosis/weisgnosis-agents&Date)

## License and acknowledgements

[Apache-2.0](LICENSE). Agent mechanics are adapted from [AgentScope Java](https://github.com/agentscope-ai/agentscope-java); [upstream mappings](docs/architecture/test-parity.md) document transferred behavior and intentional adaptations. WeisGnosis Agents uses native Scala/ZIO APIs and is not a drop-in AgentScope replacement.

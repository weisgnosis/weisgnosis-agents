# weisgnosis-agents

A Scala 3 and ZIO 2 library for agent execution with explicit resource ownership. The runtime owns conversation state and completion; model output is untrusted input. Agent mechanics are adapted from [AgentScope Java](https://github.com/agentscope-ai/agentscope-java).

**Development status:** use local artifacts from this source tree. Public distribution, frozen binary compatibility and complete upstream transfer are not yet established. The [completion checklist](docs/architecture/tracker.md) lists remaining library work.

## Start here

Use JDK 21 and the sbt version pinned in [project/build.properties](project/build.properties). From this repository:

```text
sbt verify
sbt "core / publishLocal" "harness / publishLocal" "mcp / publishLocal"
```

The [getting-started guide](docs/getting-started.md#build-and-local-installation) supplies consumer dependencies and complete applications. After installing core, save this as src/main/scala/ScriptedAgent.scala in your consumer project:

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

Run `sbt "runMain example.ScriptedAgent"`. It prints Hello without a network request. Continue with a [real HTTP model and typed tool](docs/getting-started.md#http-completion-with-a-typed-tool), or [owned MCP installation](docs/getting-started.md#owned-mcp-installation).

## Modules

| Module | Purpose |
| --- | --- |
| [core](modules/core/README.md) | Messages, events, typed tools, ReAct execution, checkpoints, structured output and OpenAI-compatible HTTP completion. |
| [harness](modules/harness/README.md) | Context compaction, output offload, memory, transcripts, permissions, planning, skills and owned subagents. |
| [mcp](modules/mcp/README.md) | Scoped MCP tools/resources over direct stdio, legacy HTTP/SSE and Streamable HTTP. |
| [storage-postgresql](modules/storage-postgresql/README.md) | Optional conditional JSON storage using a host-supplied DataSource and schema ownership. |
| [examples](modules/examples/README.md) | Host composition, lifecycle logging and an opt-in live-provider smoke application. |

The runtime saves tool calls before local execution. Tool failures become observations; interruption joins owned cleanup and reconciles unfinished calls. Context compaction preserves call/result pairs, and offloading preserves the original observation when persistence fails. See [architecture contracts](docs/architecture/README.md) for precise boundaries.

## Current limits

- General shell, file and web tools are supplied by the host or MCP; there is no bundled general OS toolkit.
- The model adapter implements OpenAI-compatible completion, with explicit reasoning/media profiles. Native Anthropic and Gemini APIs are not implemented.
- Persistent named-session restoration and other remaining upstream obligations stay open in the [tracker](docs/architecture/tracker.md#remaining-library-completion-work).
- Hosts own authentication, durable workflow replay, exclusive execution claims and idempotency. Process-local Promises do not survive restarts. Local path validation is not an atomic filesystem sandbox.
- PostgreSQL storage borrows the host's DataSource. It does not supply a pool, deployment or safe automatic replay of uncertain writes.

## Verification and contribution

`sbt verify` checks formatting, fatal-warning compilation and tests. Configure the disposable [PostgreSQL fixture](modules/storage-postgresql/src/test/scala/com/weisgnosis/agents/storage/postgresql/README.md) to run its integration scenarios; skipped scenarios are not successful backend verification. Public model requests require the explicitly enabled [live smoke](modules/examples/README.md#live-endpoint-verification).

Run `sbt packageArtifacts` for local candidate packaging. This does not publish artifacts remotely. See [CONTRIBUTING.md](CONTRIBUTING.md) for proportionate checks and substantive independent review, and the [documentation index](docs/README.md) for guides and compatibility contracts.

Licensed under [Apache-2.0](LICENSE).

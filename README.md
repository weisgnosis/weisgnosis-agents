# weisgnosis-agents

Pure functional agent runtime components for Scala 3.9.0 and ZIO 2.1.26. The project ports selected agent mechanics from [AgentScope Java](https://github.com/agentscope-ai/agentscope-java) under Apache-2.0.

This repository is in development. The [implementation tracker](docs/architecture/tracker.md) distinguishes available components from pending integration; this is not a completed production distribution.

## Implemented components

- **core:** immutable messages, 39 typed event variants, JSON codecs, typed tools, path screening, SSE parsing and chunk assembly, and ReAct execution with interruption recovery, bounded thinking retries, and structured output.
- **harness:** conversation compaction, result eviction and local storage, immutable permission decisions, interactive confirmation requests, and serializable durable approval data.
- **mcp:** architecture and package scaffolding only.

LlmClient is a port with scripted test clients. A live provider HTTP transport is not implemented. Harness components are not automatically installed into ReActAgent. Durable storage, middleware integration, memory services, subagent supervision, and plan-mode enforcement remain separate work.

## Build and verification

Use JDK 21 and the sbt version pinned in project/build.properties.

```text
sbt scalafmtAll scalafmtSbt
sbt verify
```

The verify alias checks formatting, compiles with fatal warnings, and runs all tests. See [CONTRIBUTING.md](CONTRIBUTING.md) for semantic review and documentation requirements.

## Minimal local example

This example uses a scripted client and performs no provider request. Build from this repository; publication of snapshot artifacts is not guaranteed.

```scala
package com.weisgnosis.agents.examples

import com.weisgnosis.agents.core.agent.ReActAgent
import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.model.Msg
import zio.*

object SimpleAgentApp extends ZIOAppDefault:
  def run: ZIO[Any, Throwable, Unit] =
    ZIO.scoped:
      for
        client <- LlmClient.mock(Chunk(TurnOutput.FinalText(Msg.assistant("Hello"))))
        agent <- ReActAgent.make(client = client)
        result <- agent.run(Msg.user("Hello"))
        _ <- Console.printLine(result.reply.text)
      yield ()
```

Model transports implement LlmClient.streamTurn. The agent owns request lifetime and conversation state. Filesystem stores and approval workflows must honor their documented trust and persistence contracts.

## Documentation

- [Architecture and delivery boundary](ARCHITECTURE.md)
- [Documentation index](docs/README.md)
- [Module index](modules/README.md)
- [Core API and harness implementation notes](docs/architecture/phase-4-implementation.md)
- [Engineering standards](AGENTS.md)

All repository documentation, comments, identifiers, commit messages, and PR descriptions must be English. Use protocol-level interfaces; retain upstream names for attribution and traceability.

## License

[Apache-2.0](LICENSE).

## Current acceptance

- [x] Core lifecycle/path fixes and harness compactor/store fixes at `fb80428` received independent final approval after corrections and cross-review.
- [x] Structured-output preservation and interruption fixes received final independent approval; accepted revisions and current contracts are in the core agent README.
- [x] Current verification: 366 tests passed, zero compiler warnings.
- [ ] Complete the remaining work in the [progress tracker](docs/architecture/tracker.md).

The [core README](modules/core/README.md) and [harness README](modules/harness/README.md) link the exact accepted scopes. Closed review reports live in Git history; current acceptance is maintained in these READMEs.

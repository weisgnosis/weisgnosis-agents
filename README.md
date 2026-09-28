# weisgnosis-agents

<p align="center">
  <strong>Production-Ready, Pure FP Multi-Agent Engine for Scala 3 and ZIO 2</strong>
</p>

<p align="center">
  <a href="https://github.com/weisgnosis/weisgnosis-agents/blob/main/LICENSE">
    <img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg" alt="License: Apache-2.0" />
  </a>
  <a href="#">
    <img src="https://img.shields.io/badge/Scala-3.9.0_LTS-red.svg" alt="Scala 3.9.0 LTS" />
  </a>
  <a href="#">
    <img src="https://img.shields.io/badge/ZIO-2.1.26+-orange.svg" alt="ZIO 2" />
  </a>
  <a href="https://github.com/weisgnosis/weisgnosis-agents">
    <img src="https://img.shields.io/badge/PRs-welcome-brightgreen.svg" alt="PRs Welcome" />
  </a>
</p>

---

## What is `weisgnosis-agents`?

**`weisgnosis-agents`** is an open-source, purely functional (Pure FP) multi-agent framework designed from the ground up for **Scala 3** and **ZIO 2**. 

It takes the battle-tested, enterprise-grade agentic mechanics of [AgentScope 2.0](https://github.com/agentscope-ai/agentscope-java) (by Alibaba) and reimagines them as idiomatic, non-blocking, type-safe functional abstractions:

* 🌊 **Unified 31-Event Stream:** Real-time event streaming (`ZStream[R, E, AgentEvent]`) covering reasoning deltas, tool executions, and human approvals.
* 🧠 **Thinking & Reasoning Streaming:** Native support for reasoning/CoT streaming (`ThinkingBlockDelta`) compatible with modern reasoning and thinking models.
* 🛡️ **Edge-Case Resilience:** Built-in guards against dangling tool calls on cancellation, empty responses from thinking models, and automatic failovers.
* 🧹 **Context Compaction & Eviction:** Adaptive LLM conversation compaction with summary deduplication and automatic off-context eviction of heavy tool outputs (e.g. large diffs and file reads).
* 🚦 **Human-in-the-Loop (HITL):** First-class permission gating (`Allow`, `RequireConfirm`, `Deny`) with non-blocking promise-based resumption.
* 🧩 **Native MCP:** Model Context Protocol client implementation (Stdio and SSE over HTTP) directly in ZIO.
* ⚡ **Stateless Micro-Engine:** Perfectly decoupled from external databases and workflow engines, making it the ideal cognitive core for **Temporal**, Kafka, or HTTP services.

---

## Modules

```
weisgnosis-agents
├── weisgnosis-agents-core       # Core ReAct engine, 31-event stream, tool engine, SSE client
├── weisgnosis-agents-harness    # Context compaction, tool result eviction, HITL, subagents
└── weisgnosis-agents-mcp        # Native ZIO Model Context Protocol (MCP) client
```

### 1. `weisgnosis-agents-core`
The minimal, zero-dependency cognitive kernel:
- Immutable message model: `Msg`, `Role`, `ContentBlock` (Text, Thinking, ToolUse, ToolResult, Data).
- Typed event hierarchy: 31 events as a single Scala 3 `enum AgentEvent derives JsonCodec`.
- Pure FP ReAct loop using ZIO tail recursion.
- Compile-time JSON schema and codecs for tools via `zio-json`.
- Streaming SSE client with support for server-side tools (e.g. provider search, sandbox execution).

### 2. `weisgnosis-agents-harness`
Batteries-included enterprise engineering:
- **`ToolResultEvictor`**: Prevents context explosion by offloading large tool outputs (>2,000 chars) and replacing them with previews.
- **`ConversationCompactor`**: Summarizes conversation prefixes, merges duplicate summaries, and preserves recent message tails.
- **`PermissionGate`**: Three-tier gating (`Allow | RequireConfirm | Deny`) with suspended tokens for Temporal or async user approvals.
- **`FiberSubagents`**: In-turn subagent execution on ZIO fibers with event multiplexing (`parent/child` tagging).

### 3. `weisgnosis-agents-mcp`
- Connects your agent to the growing ecosystem of Model Context Protocol (MCP) servers (Postgres, GitHub, Filesystem, Slack).
- Built on `zio-http` and `zio.process.Command`.

---

## Quickstart

Add the dependency to your `build.sbt`:

```scala
libraryDependencies ++= Seq(
  "com.weisgnosis" %% "weisgnosis-agents-core"    % "0.1.0-SNAPSHOT",
  "com.weisgnosis" %% "weisgnosis-agents-harness" % "0.1.0-SNAPSHOT"
)
```

### Running your first agent:

```scala
import com.weisgnosis.agents.core.msg.*
import com.weisgnosis.agents.core.loop.*
import com.weisgnosis.agents.core.tool.*
import zio.*

object SimpleAgentApp extends ZIOAppDefault:

  val calculatorTool = Tool.make[Any, (Int, Int), Int](
    name = "add",
    description = "Adds two numbers together"
  ) { case (a, b) => ZIO.succeed(a + b) }

  def run =
    for {
      agent <- Agent.builder
                 .withSystemPrompt("You are a helpful assistant with math tools.")
                 .withModel("standard-chat-model")
                 .withTools(calculatorTool)
                 .build
      
      response <- agent.run("What is 42 + 58?")
      _        <- Console.printLine(s"Assistant: ${response.textContent}")
    } yield ()
```

---

## Architectural Role in `weisgnosis-os`

`weisgnosis-agents` was extracted and designed as the official cognitive runtime for **[WeisGnosis OS](https://github.com/weisgnosis/weisgnosis-os)**:

```
[weisgnosis-os: Temporal Workflow DAG]
              │
              ▼ (Activity Execution)
[WeisGnosisAgentProvider (ZIO Adapter)]
              │
              ▼ (Micro-Reasoning Loop)
[weisgnosis-agents: Pure FP Core & Harness]
```

- **Temporal (Macro-layer)** owns long-running state, transaction rollbacks, multi-day human workflows, and database idempotency.
- **`weisgnosis-agents` (Micro-layer)** runs inside individual Temporal Activities to handle reasoning, prompt expansion, tool invocation, and token eviction without state leaks.

---

## Roadmap

- [x] Architecture specification and domain modeling.
- [ ] Phase 1: Core ADTs (`Msg`, `ContentBlock`, 31 `AgentEvent`) and `zio-json` codecs.
- [ ] Phase 2: Streaming SSE LLM Client with Thinking block extraction.
- [ ] Phase 3: ReAct Engine with interruption reconciliation and empty-response retry.
- [ ] Phase 4: Harness Compaction (`ConversationCompactor`) & Tool Eviction (`ToolResultEvictor`).
- [ ] Phase 5: Human-in-the-Loop (`PermissionGate`) & Fiber-level Subagents.
- [ ] Phase 6: Native ZIO MCP Client (Stdio & SSE).
- [ ] Phase 7: Production cutover in `weisgnosis-os`.

---

## Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) and [AGENTS.md](AGENTS.md) for code rules and guidelines.

## License

This project is licensed under the **Apache License, Version 2.0**. See the [LICENSE](LICENSE) file for details.

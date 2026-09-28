# weisgnosis-agents-core

The cognitive kernel of the multi-agent framework built with Scala 3.9 LTS and ZIO 2. It implements a pure, type-safe Reason + Act loop (ReAct loop), real-time streaming events, immutable message model, and type-safe tool execution.

---

## 📦 Package Structure

| Package | Purpose | Upstream AgentScope Java Reference | SLOC Forecast (Main / Test) |
| :--- | :--- | :--- | :---: |
| [`model`](src/main/scala/com/weisgnosis/agents/core/model/README.md) | Immutable messages (`Msg`), roles (`Role`), content blocks (`ContentBlock`) | `agentscope-core/.../message/` | ~150 / ~200 |
| [`event`](src/main/scala/com/weisgnosis/agents/core/event/README.md) | 31 typed events (`AgentEvent`) for SSE streaming | `agentscope-core/.../hook/` & `event/` | ~120 / ~180 |
| [`tool`](src/main/scala/com/weisgnosis/agents/core/tool/README.md) | Type-safe tools (`Tool`, `Toolkit`) powered by `zio-json` | `agentscope-core/.../tool/` | ~300 / ~350 |
| [`client`](src/main/scala/com/weisgnosis/agents/core/client/README.md) | Streaming model client (`zio-http`), SSE parser, reasoning block adapter | `agentscope-core/.../model/` | ~450 / ~400 |
| [`agent`](src/main/scala/com/weisgnosis/agents/core/agent/README.md) | Tail-recursive ReAct loop, state machine, dangling tool reconciliation | `agentscope-core/.../agent/ReActAgent.java` | ~450 / ~650 |

---

## 🎯 Key Architectural Invariants
1. **Zero Null / Zero Mutation:** No `var`, `null`, `throw`, or mutable collections. Strictly immutable `case class`, `Chunk`, `Option`, and pure ZIO effects.
2. **Pure Codecs:** Fast JSON codecs using `zio.json` and `zio.json.ast.Json` without heavyweight schema reflections.
3. **Structured Concurrency:** Fiber cancellation deterministically and immediately interrupts model streaming and tool execution.
4. **LLM Fault Tolerance:** Built-in safeguards against 400 Bad Request on interruptions (Dangling Tool Reconciliation) and empty reasoning outputs (Thinking-Only Loopback).

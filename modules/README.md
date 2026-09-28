# `weisgnosis-agents` Modules

Central navigation index and package registry. Each directory contains an in-repo mini-wiki tracking specification details, references to the upstream AgentScope Java source code, invariants, and 1:1 test parity maps.

---

## 🗺️ Module and Package Map

### 1. `weisgnosis-agents-core` (Cognitive Kernel & ReAct Loop)
* [**Module README**](core/README.md)
* ├── [`model`](core/src/main/scala/com/weisgnosis/agents/core/model/README.md) — Domain entities: `Msg`, `Role`, `ContentBlock` *(Tests: [model/README.md](core/src/test/scala/com/weisgnosis/agents/core/model/README.md))*
* ├── [`event`](core/src/main/scala/com/weisgnosis/agents/core/event/README.md) — 31 typed events in `AgentEvent` *(Tests: [event/README.md](core/src/test/scala/com/weisgnosis/agents/core/event/README.md))*
* ├── [`tool`](core/src/main/scala/com/weisgnosis/agents/core/tool/README.md) — Type-safe `Tool` definitions and `Toolkit` registry *(Tests: [tool/README.md](core/src/test/scala/com/weisgnosis/agents/core/tool/README.md))*
* ├── [`client`](core/src/main/scala/com/weisgnosis/agents/core/client/README.md) — Streaming SSE client with reasoning block support *(Tests: [client/README.md](core/src/test/scala/com/weisgnosis/agents/core/client/README.md))*
* └── [`agent`](core/src/main/scala/com/weisgnosis/agents/core/agent/README.md) — Pure tail-recursive `ReActAgent` loop *(Tests: [agent/README.md](core/src/test/scala/com/weisgnosis/agents/core/agent/README.md))*

### 2. `weisgnosis-agents-harness` (Context Management & Coordination)
* [**Module README**](harness/README.md)
* ├── [`memory`](harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md) — History compaction: `ConversationCompactor` *(Tests: [memory/README.md](harness/src/test/scala/com/weisgnosis/agents/harness/memory/README.md))*
* ├── [`eviction`](harness/src/main/scala/com/weisgnosis/agents/harness/eviction/README.md) — Large output offloading: `ToolResultEvictor` *(Tests: [eviction/README.md](harness/src/test/scala/com/weisgnosis/agents/harness/eviction/README.md))*
* ├── [`permission`](harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md) — Human-in-the-Loop promise-based gating *(Tests: [permission/README.md](harness/src/test/scala/com/weisgnosis/agents/harness/permission/README.md))*
* ├── [`subagent`](harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md) — In-turn subagent execution on ZIO Fibers *(Tests: [subagent/README.md](harness/src/test/scala/com/weisgnosis/agents/harness/subagent/README.md))*
* └── [`plan`](harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md) — Planning and execution mode transitions *(Tests: [plan/README.md](harness/src/test/scala/com/weisgnosis/agents/harness/plan/README.md))*

### 3. `weisgnosis-agents-mcp` (Model Context Protocol)
* [**Module README**](mcp/README.md)
* ├── [`protocol`](mcp/src/main/scala/com/weisgnosis/agents/mcp/protocol/README.md) — JSON-RPC 2.0 schemas for MCP *(Tests: [protocol/README.md](mcp/src/test/scala/com/weisgnosis/agents/mcp/protocol/README.md))*
* ├── [`transport`](mcp/src/main/scala/com/weisgnosis/agents/mcp/transport/README.md) — Stdio (`zio.process`) and SSE (`zio-http`) transports *(Tests: [transport/README.md](mcp/src/test/scala/com/weisgnosis/agents/mcp/transport/README.md))*
* └── [`client`](mcp/src/main/scala/com/weisgnosis/agents/mcp/client/README.md) — `McpClient` and bridge to `Toolkit` *(Tests: [client/README.md](mcp/src/test/scala/com/weisgnosis/agents/mcp/client/README.md))*

---

## 🎯 Tracker Governance Rules
1. Every package mini-wiki tracker is maintained and updated as implementations and test suites are added.
2. Zero stubs without unit tests: each domain component is accompanied by paired ZIO Test specs.
3. Progress is tracked via checkboxes (`[ ]` $\to$ `[x]`).

# High-Level Architecture Design (HLD)

> **Document:** High-Level Architecture Design (HLD)  
> **System:** `weisgnosis-agents`  
> **Namespace:** `com.weisgnosis.agents`  
> **Platform:** Scala 3.9.0 LTS, ZIO 2.1.26+, zio-json, zio-http  
> **License:** Apache-2.0  
> **Status:** Approved / Living Document  

---

## 1. Executive Summary & Architectural Mission

`weisgnosis-agents` is a purely functional (Pure FP), production-grade multi-agent library implemented in **Scala 3** and **ZIO 2**. It ports and refactors the core mechanics of [AgentScope Java 2.0](https://github.com/agentscope-ai/agentscope-java) (by Alibaba) into an idiomatic, non-blocking, type-safe architecture.

### Architectural Law
> **Runtime owns truth and completion.**  
> Cognitive models are reasoning executors, not epistemic authorities. A model may propose assertions, invoke tools, and hypothesize conclusions, but only deterministic runtime gates (Acceptance Contracts and Epistemic Arbiters) possess the authority to certify facts or declare work completed.

### Mission & Boundaries
- **What it is:** A stateless, deterministic **Micro-Engine** executing ReAct reasoning loops over individual task slices (`WorkItem`). It streams real-time typed events (`ZStream[R, E, AgentEvent]`), manages context budgets, executes tools, and enforces safety boundaries.
- **What it is NOT:** It is not a distributed workflow engine, does not run background cron daemons, and contains no bespoke database or task scheduler. Macro-level workflow orchestration, persistent transactions, multi-day human workflows, and global database storage belong to the workflow layer (e.g. **WeisGnosis OS** on **Temporal**).

---

## 2. System Context & Layering

The diagram below illustrates how `weisgnosis-agents` integrates into the broader enterprise architecture:

```
┌────────────────────────────────────────────────────────────────────────┐
│                   MACRO WORKFLOW LAYER (e.g. Temporal)                 │
│                                                                        │
│   • Multi-day durable workflows & state machines                       │
│   • Epistemic SSOT (PostgreSQL Events + Projections)                   │
│   • Acceptance Contracts & Deterministic Arbiter                       │
│   • Targeted Repair Routing & Human-in-the-Loop browser portals        │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Invokes Activity
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                  MICRO ENGINE (weisgnosis-agents)                      │
│                                                                        │
│   ┌────────────────────────────────────────────────────────────────┐   │
│   │ weisgnosis-agents-harness                                      │   │
│   │   • ConversationCompactor (atomic turn pairing & safe cutoff)  │   │
│   │   • ToolResultEvictor (80k char workspace offload & previews)  │   │
│   │   • PermissionGate (Dual-mode HITL: Promise & Durable Resume)  │   │
│   │   • FiberSubagents (scoped in-turn & TaskSupervisor promotion) │   │
│   │   • PlanMode (mutation lockdown & shell command inspection)    │   │
│   │   • Two-Layer Memory (Daily scratchpad vs Curated MEMORY.md)   │   │
│   └───────────────────────────────┬────────────────────────────────┘   │
│                                   │                                    │
│   ┌───────────────────────────────▼────────────────────────────────┐   │
│   │ weisgnosis-agents-core                                         │   │
│   │   • ReAct Loop (tail-recursive ZIO effect with state ref)      │   │
│   │   • 31-Event SSE Stream (ZStream[Any, AgentError, AgentEvent]) │   │
│   │   • Immutable Domain Model (Msg, Role, ContentBlock)           │   │
│   │   • Structured Output Dual-Engine (Native vs generate_response)│   │
│   │   • Type-safe Toolkit (JSON schema validation & dispatch)      │   │
│   │   • Streaming Model Client (SSE chunk index accumulator)       │   │
│   └───────────────────────────────┬────────────────────────────────┘   │
│                                   │                                    │
│   ┌───────────────────────────────▼────────────────────────────────┐   │
│   │ weisgnosis-agents-mcp                                          │   │
│   │   • Native JSON-RPC 2.0 client & MCP 2024-11-05 schemas        │   │
│   │   • Transports: Stdio (zio.process) & SSE (zio-http)           │   │
│   │   • Tool error normalization & metadata leak protection        │   │
│   │   • Automatic bridge to agent Toolkit                          │   │
│   └────────────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Module Architecture & Responsibilities

The codebase is organized into three decoupled modules:

### 3.1. `weisgnosis-agents-core` (Cognitive Kernel)
The minimal, zero-dependency foundation:
1. **Domain Model (`com.weisgnosis.agents.core.model`):**
   - Strictly immutable message structures: `Msg`, `Role`, `ContentBlock` (Text, Thinking, ToolUse, ToolResult, Image, Custom), and `ToolResultState`.
   - Support for reasoning details metadata on `Thinking` and cryptographic `thoughtSignature` on `ToolUse`.
   - Fast, zero-reflection JSON codecs powered by `zio-json`.
2. **Unified Event Streaming (`com.weisgnosis.agents.core.event`):**
   - A complete 31-event hierarchy (`AgentEvent`) serialized with discriminator `event`.
   - Carries stream chunk indices on `ToolCallDeltaEvent` to support parallel tool call interleaving.
   - Emits real-time tokens, thinking blocks, tool lifecycles, and subagent execution milestones into a `ZStream`.
3. **Type-Safe Tool Engine (`com.weisgnosis.agents.core.tool`):**
   - Declarative `Tool[R, In, Out]` definitions validated against JSON Schema.
   - Explicit concurrency safety flag (`isConcurrencySafe: Boolean`) for safe parallel tool execution.
   - Immutable `Toolkit` registry with collision detection and sensitive path protection (`toRealPath()`).
   - Normalization of execution failures to `ToolResultState.Error` rather than failing the outer reasoning loop.
4. **Streaming Client & Adapters (`com.weisgnosis.agents.core.client`):**
   - Non-blocking HTTP SSE client using `zio-http`.
   - Chunk accumulator mapping `streamIndex -> (callId, toolName, buffer)` to handle fragmented parallel tool calls.
   - Vendor-neutral `ReasoningModelAdapter` preserving thinking tokens preceding tool turns while trimming them from older dialogue turns.
   - Provider server tool bypass (`isServerTool = true`).
5. **ReAct Engine (`com.weisgnosis.agents.core.agent`):**
   - Tail-recursive execution loop maintaining state in a fiber-safe accumulator.
   - **Dangling Tool Reconciliation:** On fiber cancellation, detects incomplete tool calls, synthesizes `ToolResultState.Interrupted`, appends a conversational recovery message, and returns reconciled history.
   - **Thinking-Only Loopback:** Automatically injects `<system-reminder>` when models emit thinking without user-facing text or tool calls.
   - **Structured Output Dual-Engine:** Toggles between native `response_format` and synthetic `generate_response` tool with root `$defs` hoisting and context clean-up.

### 3.2. `weisgnosis-agents-harness` (Context & Coordination)
Enterprise-grade context engineering and execution safeguards:
1. **Memory Compaction & Two-Stage Pruning (`com.weisgnosis.agents.harness.memory`):**
   - `ConversationCompactor`: Binary search token window (`findTokenBasedCutoff`) preserving recent dialogue turns (`preserveTailMessages`).
   - **Safe Cutoff Invariant:** Protects `Assistant(tool_calls)` and `Tool(tool_results)` turn pairs, with forward fallback for orphaned results.
   - Consecutive User Turn Coalescing: Prevents duplicate consecutive `User` turns after summary prepending.
   - Two-Stage Pruning: Lightweight non-LLM pruning of older tool results (>2k chars) and oversized tool arguments before invoking summarization.
   - Summary Deduplication: Aggregates pre-existing `__compaction_summary__` blocks into a unified flat summary.
2. **Two-Layer Memory Architecture (`com.weisgnosis.agents.harness.memory`):**
   - `MemoryFlushManager`: Writes append-only bullets solely to daily logs (`memory/YYYY-MM-DD.md`); handles `NO_REPLY`.
   - `MemoryConsolidator`: Sole writer to curated long-term memory (`MEMORY.md`) within a bounded 4,000-token budget; tracks watermarks in `.consolidation_state`.
   - Flush coalescing per `(agentId, sessionId)`.
3. **Tool Result Eviction (`com.weisgnosis.agents.harness.eviction`):**
   - `ToolResultEvictor`: Immediate turn-level eviction for tool outputs exceeding `maxResultChars` (default `80,000` chars).
   - Offloads raw payloads to workspace files (`large_tool_results/{agentId}/{toolCallId}-{hash}`) so the model can inspect them via standard `read_file`.
   - Injects structured head (2,000 chars) and tail (2,000 chars) previews with explicit read instructions.
   - Excludes critical inspection tools (`read_file`, `write_file`, `edit_file`, memory tools).
4. **Dual-Mode Permission Gate & HITL (`com.weisgnosis.agents.harness.permission`):**
   - Three-tier authorization: `Allow | RequireConfirm | Deny` evaluated via a strict 6-stage waterfall.
   - **Interactive Mode:** In-memory suspension via `zio.Promise[Nothing, ConfirmResult]` with timeout.
   - **Durable Mode (Temporal/Workflow Parity):** Suspends turn, persists partial state, and yields `AgentResult(state = AwaitingApproval)`. Resumes with `ConfirmResult` (supporting argument edits and new permanent rules) without re-invoking the model.
5. **Subagents & Coordination (`com.weisgnosis.agents.harness.subagent`):**
   - In-turn child agent spawning on ZIO fibers with recursion depth cap (`MAX_SPAWN_DEPTH = 3`).
   - Stream multiplexing: child events merged into parent stream with hierarchical attribution (`parentSessionId/childAgentId`).
   - Synchronous Timeout Promotion (30s): Promotes long-running tasks into a persistent `TaskSupervisor` scope, returning `taskId`.
   - Background task inspection tool suite: `task_output`, `task_list`, `task_cancel`.
6. **Plan Mode (`com.weisgnosis.agents.harness.plan`):**
   - Enforces read-only operations during planning; mutating tools are rejected.
   - Hardened shell classifier rejecting redirection (`>`), piping to destructive binaries, and mutating commands.
   - Mandatory human approval barrier on `plan_exit`.

### 3.3. `weisgnosis-agents-mcp` (Model Context Protocol)
Integration with external tool and resource servers:
1. **Protocol Specifications (`com.weisgnosis.agents.mcp.protocol`):**
   - JSON-RPC 2.0 base protocol structures and MCP 2024-11-05+ schema bindings (`InitializeParams`, `InitializeResult`, `ListToolsResult`, `CallToolParams`, `CallToolResult`).
   - Post-handshake `notifications/initialized` notification.
2. **Transports (`com.weisgnosis.agents.mcp.transport`):**
   - `StdioTransport`: Child process lifecycle managed via `zio.process.Command` within ZIO `Scope`.
   - `SseTransport`: Remote MCP server streaming over `zio-http`.
3. **Client Bridge (`com.weisgnosis.agents.mcp.client`):**
   - `McpClient`: Dynamic tool discovery and automatic bridging into native agent `Toolkit`.
   - Tool Error Normalization: Maps `CallToolResult.isError = true` into `ToolResultState.Error` rather than failing the effect.
   - Metadata Protection: Strips internal `_meta` by default (`propagateMeta: Boolean`) to prevent sensitive token leakage to remote MCP hosts.

---

## 4. Fundamental Architectural Invariants

| ID | Invariant | Description |
| :--- | :--- | :--- |
| **INV-1** | **Zero Mutable State** | Zero `var`, `throw`, `null`, `while`, or mutable collections. All state is maintained via immutable case classes and pure ZIO effects. |
| **INV-2** | **Structured Concurrency** | Fiber lifecycles are strictly scoped. Canceling a parent agent terminates all descendant subagents, active tool executions, and HTTP streams deterministically. |
| **INV-3** | **Dangling Tool Reconciliation** | Every assistant `ToolUse` block in conversation history must have a matching `ToolResult` block. On interruption, only in-flight tool calls receive synthetic `ToolResultState.Interrupted`, preserving completed tools and appending a recovery message. |
| **INV-4** | **Atomic Turn Pairing & Safe Cutoff** | History compaction never severs a tool call from its result. The cutoff boundary shifts backwards to encompass complete turn pairs, with forward fallback for orphaned tool results. |
| **INV-5** | **Two-Layer Memory Isolation** | Daily scratch flushes write only to `memory/YYYY-MM-DD.md`; memory consolidator is the sole writer to curated long-term memory (`MEMORY.md`). |
| **INV-6** | **Dual-Mode HITL Resumption** | Interactive approvals suspend via `Promise`; workflow/durable approvals suspend the turn to session store and resume via `ConfirmResult` without re-prompting the model. |
| **INV-7** | **Two-Stage Context Pruning** | Large tool outputs (>80k chars) are evicted immediately to workspace files; older tool results (>2k chars) and large arguments are pruned during compaction before LLM summarization. |
| **INV-8** | **Sensitive Path Protection** | All file-related tools enforce canonical path resolution (`toRealPath()`) to block symlink escapes and sensitive file access (`.ssh`, `.env`). |

---

## 5. Technology Stack & Dependencies

```
weisgnosis-agents
├── Scala 3.9.0 LTS (Pure FP, Enums, Givens, Union Types)
├── ZIO 2.1.26+ (Structured Concurrency, Fibers, FiberRef, Scope)
├── ZIO Streams 2.1.26+ (Event stream pipelines, backpressure)
├── ZIO JSON 0.9.1+ (AST manipulation, fast compile-time codecs)
├── ZIO HTTP 3.11.3+ (Client for SSE streaming and MCP transports)
└── ZIO Test 2.1.26+ (Deterministic property testing, TestClock)
```

Zero external database drivers, Spring frameworks, or Project Reactor dependencies are permitted in this library.

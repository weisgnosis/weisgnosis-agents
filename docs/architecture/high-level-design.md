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
│   │   • ConversationCompactor (atomic turn retention)              │   │
│   │   • ToolResultEvictor (large output offloading)                │   │
│   │   • PermissionGate (Promise-based HITL approval)               │   │
│   │   • FiberSubagents (scoped in-turn delegation)                 │   │
│   │   • PlanMode (mutation lockdown & approval barriers)           │   │
│   └───────────────────────────────┬────────────────────────────────┘   │
│                                   │                                    │
│   ┌───────────────────────────────▼────────────────────────────────┐   │
│   │ weisgnosis-agents-core                                         │   │
│   │   • ReAct Loop (tail-recursive ZIO effect)                     │   │
│   │   • 31-Event SSE Stream (ZStream[Any, AgentError, AgentEvent]) │   │
│   │   • Immutable Domain Model (Msg, Role, ContentBlock)           │   │
│   │   • Type-safe Toolkit (JSON schema validation & dispatch)      │   │
│   │   • Streaming Model Client (SSE parser, reasoning stream)      │   │
│   └───────────────────────────────┬────────────────────────────────┘   │
│                                   │                                    │
│   ┌───────────────────────────────▼────────────────────────────────┐   │
│   │ weisgnosis-agents-mcp                                          │   │
│   │   • Native JSON-RPC 2.0 client                                 │   │
│   │   • Transports: Stdio (zio.process) & SSE (zio-http)           │   │
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
   - Fast, zero-reflection JSON codecs powered by `zio-json`.
2. **Unified Event Streaming (`com.weisgnosis.agents.core.event`):**
   - A complete 31-event hierarchy (`AgentEvent`) serialized with discriminator `event`.
   - Emits real-time tokens, thinking blocks, tool lifecycles, and subagent execution milestones into a `ZStream`.
3. **Type-Safe Tool Engine (`com.weisgnosis.agents.core.tool`):**
   - Declarative `Tool[R, In, Out]` definitions validated against JSON Schema.
   - Immutable `Toolkit` registry with collision detection and sensitive path protection.
4. **Streaming Client & Adapters (`com.weisgnosis.agents.core.client`):**
   - Non-blocking HTTP SSE client using `zio-http`.
   - Vendor-neutral `ReasoningModelAdapter` preserving thinking tokens preceding tool turns while trimming them from older dialogue turns.
5. **ReAct Engine (`com.weisgnosis.agents.core.agent`):**
   - Tail-recursive execution loop.
   - Built-in safeguards: **Dangling Tool Reconciliation** (synthesizes `ToolResultBlock(INTERRUPTED)` on fiber cancellation) and **Thinking-Only Loopback** (prompts models that emit thinking without final output).

### 3.2. `weisgnosis-agents-harness` (Context & Coordination)
Enterprise-grade context engineering and execution safeguards:
1. **Memory Compaction (`com.weisgnosis.agents.harness.memory`):**
   - `ConversationCompactor`: Adaptive summarization preserving recent dialogue turns (`preserveTailMessages`).
   - Invariant: **Atomic Turn Pairing** ensures a model tool call turn is never separated from its matching tool result turn across the cutoff line.
   - Summary Deduplication: Aggregates pre-existing summary blocks into a unified flat summary.
2. **Tool Result Eviction (`com.weisgnosis.agents.harness.eviction`):**
   - `ToolResultEvictor`: Offloads outputs exceeding `maxChars` (>2,000 characters) into an off-context `ToolResultStore`.
   - Retains structured head and tail previews in-context so the model can inspect initiation and exit statuses without exhausting context limits.
3. **Permission Gate & HITL (`com.weisgnosis.agents.harness.permission`):**
   - Three-tier authorization: `Allow | RequireConfirm | Deny`.
   - Non-blocking operator suspension using `zio.Promise[Nothing, PermissionDecision]`.
4. **Subagents (`com.weisgnosis.agents.harness.subagent`):**
   - In-turn child agent spawning on ZIO fibers (`forkScoped`).
   - Bounded recursion (`MAX_SPAWN_DEPTH = 3`).
   - Stream multiplexing: child events are merged into the parent stream with hierarchical source attribution (`parentSessionId/childAgentId`).
5. **Plan Mode (`com.weisgnosis.agents.harness.plan`):**
   - Enforces read-only operations during planning; mutating tools are rejected.
   - Mandatory human approval barrier on `plan_exit`.

### 3.3. `weisgnosis-agents-mcp` (Model Context Protocol)
Integration with external tool and resource servers:
1. **Protocol Specifications (`com.weisgnosis.agents.mcp.protocol`):**
   - JSON-RPC 2.0 base protocol structures and MCP 2024-11-05+ schema bindings.
2. **Transports (`com.weisgnosis.agents.mcp.transport`):**
   - `StdioTransport`: Child process lifecycle managed via `zio.process.Command` within ZIO `Scope`.
   - `SseTransport`: Remote MCP server streaming over `zio-http`.
3. **Client Bridge (`com.weisgnosis.agents.mcp.client`):**
   - `McpClient`: Dynamic tool discovery and automatic bridging into native agent `Toolkit`.

---

## 4. Fundamental Architectural Invariants

| ID | Invariant | Description |
| :--- | :--- | :--- |
| **INV-1** | **Zero Mutable State** | Zero `var`, `throw`, `null`, `while`, or mutable collections. All state is maintained via immutable case classes and effects. |
| **INV-2** | **Structured Concurrency** | Fiber lifecycles are strictly scoped. Canceling a parent agent terminates all descendant subagents, active tool executions, and HTTP streams deterministically. |
| **INV-3** | **Dangling Tool Reconciliation** | Every assistant `ToolUse` block in conversation history must have a matching `ToolResult` block. On interruption, missing blocks are synthesized with `ToolResultState.Interrupted`. |
| **INV-4** | **Atomic Turn Pairing** | History compaction never severs a tool call from its result. The cutoff index automatically shifts backwards to encompass complete turn pairs. |
| **INV-5** | **Two-Layer Memory Isolation** | Short-term turn flushes write only to daily scratch logs (`memory/YYYY-MM-DD.md`); the memory consolidator is the sole writer to curated long-term memory (`MEMORY.md`). |
| **INV-6** | **Non-Blocking HITL** | Human confirmation requests suspend execution via a lightweight `zio.Promise` without thread-blocking. |
| **INV-7** | **Vendor Neutrality** | Model client layers abstract vendor protocols into generic streaming deltas (text, thinking tokens, tool calls). |
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

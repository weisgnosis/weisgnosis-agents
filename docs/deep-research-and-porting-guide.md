> Research and target architecture reference. This document preserves upstream analysis and planned platform concepts; examples and forecasts are not current exported APIs or delivery claims. Consult [the implementation tracker](architecture/tracker.md), [current contracts](architecture/low-level-design.md), and [parity audit](architecture/test-parity.md) for implementation status.

# Deep Research & Porting Guide: `weisgnosis-agents`

> **Document:** Architectural Research Report and Porting Guide  
> **Source System:** AgentScope Java 2.0.2 / 2.0.3 (Alibaba)  
> **Target System:** `weisgnosis-agents` (Scala 3.9.0 LTS + ZIO 2.1.26+)  
> **Organization:** `com.weisgnosis`  
> **License:** Apache-2.0  
> **Last Updated:** September 2026  

---

## 1. Measured Codebase and Estimate Correction

The original line-count and calendar estimates are obsolete. They underestimated explicit runtime state machines, strict protocol validation, scoped ownership, recoverable persistence and meaningful safety scenarios. Code volume and test totals are not quality or completion targets; do not compress away domain guarantees to match an earlier projection.

The [implementation tracker](architecture/tracker.md#immediate-next-work) records the current finite outcomes and next task. No replacement calendar forecast, final line ceiling or project-completion percentage is established here.

---

## 2. Architectural Separation: Temporal (Macro) vs `weisgnosis-agents` (Micro)

The upstream design combines local agent execution with host responsibilities that can also be supplied by an external durable execution engine such as Temporal:

1. **`WorkspaceTaskRepository` + JSON files + 30s heartbeat + 5m orphan sweeper:** Task persistence and node failure recovery.
2. **`MessageBus` + inbox queues + `agentscope:wakeups`:** Message delivery and waking sleeping sessions.
3. **`AgentStateStore` with CAS versioning (`saveIfVersion`, OCC locks):** Concurrent session update coordination.
4. **`PeriodicGate` + thread pools:** Periodic maintenance admission and scheduling.

The port separates these responsibilities through explicit capabilities. Temporal is a possible macro-layer host, not a dependency or an implemented integration of this library. The diagram shows the target host arrangement; current delivery boundaries are defined in [ARCHITECTURE.md](../ARCHITECTURE.md).

### Layer Separation of Concerns (Macro vs Micro)

```
┌────────────────────────────────────────────────────────────────────────┐
│                      weisgnosis-os (MACRO LAYER)                       │
│                                                                        │
│   • Long-lived Temporal Workflows (days, weeks, months)                │
│   • Process execution graphs (FlowDefinition, DAG workflows)           │
│   • Human-in-the-Loop via Temporal Signals (browser interaction)       │
│   • Cross-service transactions (Saga), budget balancing, RBAC          │
│   • Persistence in PostgreSQL (Flyway, Doobie, Outbox Pattern)         │
│   • Heavy asynchronous jobs (Worker fleets, background rendering)      │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Dispatches Activity
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                   weisgnosis-agents (MICRO LAYER)                      │
│                                                                        │
│   • Effectful ReAct runtime with scoped, in-memory state              │
│   • Typed AgentEvent streams and correlated model deltas             │
│   • ReAct Loop: Model -> Tool Call -> Local/MCP Execution -> History   │
│   • Model protocol resilience (Dangling Reconcile, Thinking tokens)   │
│   • Context management (ConversationCompactor, Tool Eviction)          │
│   • Local lightweight subagents on ZIO Fibers (forkScoped/merge)       │
│   • Scoped ownership and optional cooperative local storage          │
└────────────────────────────────────────────────────────────────────────┘
```

**Core Principle:**  
`weisgnosis-agents` combines pure immutable transformations with effects suspended in ZIO. ReActAgent retains private in-memory state and serializes runs per instance. Scoped supervisors and queues can run background work while their explicit host-owned scopes remain open; thread-sleeping loops and unowned daemon lifetimes are not the execution model.

Optional local adapters persist memory, transcripts, tool results and plan drafts under cooperative ownership. This is a supported local capability, not distributed workflow or task-database authority. An external host owns durable replay, cross-process coordination, authentication, checkpoints and execution idempotency. Structured concurrency controls resource lifetime; it does not provide process or filesystem isolation. The same micro runtime can be embedded in a CLI, a service or a durable host activity without claiming that those host integrations are already implemented.

---

## 3. Catalog of Production Edge Cases (35 Hard Lessons)

This catalogue records research requirements, not implemented-capability claims. Current contracts and acceptance are linked from the [tracker](architecture/tracker.md). The following conditional obligations distinguish library contracts from host responsibilities; no absent backend is silently claimed implemented or required as a new platform inside this library.

- Implement lesson 15 through explicit host-selected [unavailable-channel authorization](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#unavailable-approval-channel): unresolved Ask/Passthrough is denied without waiting or checkpointing, including standard child construction. Interactive and durable adapters retain their separate contracts; this adds no remote HTTP transport.
- Classify lesson 26: namespace-owning adapters must define anonymous-user isolation if they accept absent identity. Current typed StorageAddress requires explicit nonblank host-selected components and does not infer an anonymous tenant.
- Classify lesson 28: container isolation applies to a sandbox backend; current library resource scopes and path checks do not claim container isolation.
- Classify lesson 29: archive traversal protection applies to an extracting adapter. No production archive unpacker is supplied; canonical path screening is not claimed as ZipSlip verification.
- Classify lessons 30–33: snapshot lineage, snapshot-before-session-finalization, mount exclusion and native file permissions belong to an installed sandbox/container backend. No such backend is supplied or implied by local persistence.

These checked items record applicability and activation boundaries, not completion of hypothetical sandbox/remote subsystems. Durable named-session restoration remains a concrete library obligation under lesson 18. WeisGnosis OS deployment and Temporal product tests are separate from library completion by explicit user instruction.

An analysis of 336 unit and integration tests across `agentscope-core` and `agentscope-harness` identified 35 critical production edge cases:

### 3.1. Core ReAct Loop & Model Protocol (`agents-core`)

1. **Dangling Tool Call Reconciliation on Interruption (Issue #2409)**:
   * *Issue:* If a ZIO fiber is interrupted (`fiber.interrupt`, Temporal activity cancellation, or SLA timeout) after a model emits `ToolUseBlock` but before the tool result is recorded, a dangling tool call remains in history. Subsequent model calls fail with a fatal `400 Bad Request`.
   * *Resolution:* In `ZIO.ensuring`, inspect the trailing message: if an open `ToolUseBlock` exists without a matching result, synthesize a `ToolResultBlock(INTERRUPTED)`.
2. **Reasoning Content Preservation & Hygiene**:
   * *Issue:* Reasoning models require preserving reasoning blocks preceding immediate tool calls, but stripping them from older dialogue turns prevents prompt explosion.
   * *Resolution:* Dedicated transformer `preserveReasoningOnlyForToolSegments(history: Chunk[Msg]): Chunk[Msg]`.
3. **Guard Against Empty/Thinking-Only Responses**:
   * *Issue:* Reasoning models occasionally consume reasoning budgets but return empty content without tool calls.
   * *Resolution:* Automated loopback prompt: *"You generated internal reasoning but produced no user-facing output or tool calls. Please provide the final response."*
4. **Provider Server Tools (`isServerTool = true`)**:
   * *Issue:* Hosted tools (e.g. provider search, remote code execution) execute on provider infrastructure. Both invocation and result stream together.
   * *Resolution:* The `isServerTool` flag bypasses local dispatch.
5. **Atomic Turn Pairing in Conversation Compactor**:
   * *Issue:* Splitting history at an arbitrary message index can sever an assistant's `ToolUse` turn from the following `ToolResult` turn, corrupting dialogue schema.
   * *Resolution:* Cutoff boundary shifts backwards to protect tool call/result pairs.
6. **Summary Deduplication During Re-Compaction**:
   * *Issue:* Repeated compaction passes can create nested "summaries of summaries".
   * *Resolution:* Extract prior `__compaction_summary__` blocks and flatten them into a single summary block.
7. **Tool Result Eviction**:
   * *Issue:* Commands producing massive outputs (e.g. large file diffs) exhaust context windows.
   * *Resolution:* Outputs exceeding `maxChars` are offloaded to storage and replaced with head/tail previews.
8. **Dual-Engine Structured Output**:
   * *Issue:* Native `response_format` conflicts with active tool definitions on certain provider endpoints.
   * *Resolution:* Use native format when tools are absent; inject synthetic terminal tool `generate_response` when tools are active.

---

### 3.2. Subagents & Coordination (`agents-harness`)

9. **Delegation Depth Cap (`MAX_SPAWN_DEPTH = 3`)**:
   * Prevents infinite recursive delegation loops between agents.
10. **Synchronous Timeout Promotion**:
    * When a synchronous turn exceeds timeout (e.g. 30s), the task is transitioned smoothly into a background fiber with status `timeout_promoted`, returning a task reference.
11. **Guaranteed Stream Finalization on Interruption**:
    * Ensures `AgentEndEvent` is deterministically emitted when an agent stream is interrupted.
12. **Orphan Subagent Prevention**:
    * Parent fiber interruption cascades deterministically to all child subagents using ZIO structured concurrency (`forkScoped`).
13. **Delivery Batch Throttling (`MAX_DELIVERIES_PER_REMINDER = 10`)**:
    * When many background tasks complete concurrently, at most 10 are delivered per prompt turn to avoid overwhelming the model's attention window.
14. **At-Least-Once Delivery Acknowledgement**:
    * Background results are marked as acknowledged only after the parent agent successfully completes its reasoning turn.
15. **Automatic HITL Rejection for Non-Interactive Remote Invocations**:
    * When a remote agent requests approval without an interactive channel, the request safely defaults to `Denied` to prevent deadlocks.
16. **Cascading Plan Mode and Constraint Enforcement**:
    * Planning mode restrictions and safety rules automatically cascade to all spawned subagents.
17. **Continuous Fiber Environment Propagation**:
    * ZIO `ZEnvironment` and `FiberRef` pass continuously down the effect stack without thread-local context loss.
18. **Deterministic Persistent Session Addressing**:
    * Spawning an agent with `persist_session=true` reuses state addressed by `(parentSessionId, agentId, label)`.

---

### 3.3. Memory, State Stores, and Plan Mode (`agents-harness`)

19. **Two-Layer Memory Invariant**:
    * Short-term memory manager writes only to dated daily logs (`memory/YYYY-MM-DD.md`).
    * Long-term memory consolidator is the sole writer to `MEMORY.md`. Separating writers prevents memory corruption.
20. **Async Tools: Computation Retention on Offload**:
    * When execution exceeds synchronous thresholds, the model receives a placeholder status while the underlying fiber continues executing.
21. **Mandatory Placeholder in History**:
    * Synthetic tool placeholders must appear immediately in conversation history to maintain provider schema validity.
22. **Anti-Spinning Guardrail**:
    * Repeated empty polling checks trigger explicit tool failure to prevent idle token consumption.
23. **Mandatory Human Confirmation on Plan Exit**:
    * Transitioning out of Plan Mode (`plan_exit`) strictly requires operator confirmation (`PermissionDecision.ask`).
24. **Permitted Read-Only Tools in Planning Mode**:
    * Mutating operations are disallowed during planning; reading, search, and plan drafts remain available.
25. **CAS Version Integrity**:
    * Unversioned updates return the exact generated version within the atomic write to avoid stale concurrency conflicts.
26. **Anonymous Namespace Isolation (`__anon__`)**:
    * Unspecified user contexts map to `"__anon__"` rather than empty strings, preventing accidental collision with empty identities.
27. **Synchronous Accounting Before Async Dispatch**:
    * Task registration executes synchronously on the initiating thread before dispatch to async execution pools.

---

### 3.4. Sandboxing and Filesystem Security

28. **Sandbox Session Isolation**:
    * Per-session sandbox references are isolated in `FiberRef` rather than shared singletons.
29. **Path Traversal Protection (Zip Slip & Relative Escapes)**:
    * Archive unpackers validate canonical root boundaries (`dest.startsWith(canonicalRoot)`) and reject `..` and null bytes.
30. **Snapshot Identifier Retention Across Failovers**:
    * When reconnecting to an environment fails, new containers inherit the previous snapshot identifier to preserve workspace lineage.
31. **Strict Finalization Ordering**:
    * Teardown routines record snapshot state before writing final session metadata.
32. **Host Mount Exclusions in Container Snapshots**:
    * Workspace snapshots exclude host bind mounts to prevent recursive filesystem capture.
33. **Container File Permissions**:
    * Temporary host files copied to isolated environments are explicitly normalized to readable permissions (`0644`).
34. **Symlink Canonicalization (`toRealPath()`)**:
    * Path safety checks resolve symlinks to canonical paths before verifying access rules against sensitive targets (`.ssh`, `.env`).
35. **Memory Flush Coalescing**:
    * Sequential memory flush requests collapse into a single operation evaluating the latest history slice.

---

## 4. Architectural Transformation Map (Java/Reactor $\to$ Scala 3.9/ZIO 2)

| Pattern in Java / AgentScope | Upstream Reactor Approach | `weisgnosis-agents` Pure FP Approach |
| :--- | :--- | :--- |
| **Asynchronous Effects** | `reactor.core.publisher.Mono[A]` | `zio.ZIO[R, AgentError, A]` |
| **Event Streaming** | `reactor.core.publisher.Flux[Event]` | `zio.stream.ZStream[R, AgentError, AgentEvent]` |
| **Data Immutability** | Builders, setters, defensive copies | Immutable Scala 3 `case class` with `derives JsonCodec` |
| **Execution Context** | Mutable `RuntimeContext` map | `zio.ZEnvironment` + `zio.FiberRef` (thread-safe) |
| **Cancellation** | Manual `interrupt()` flags | Deterministic fiber interruption via ZIO Structured Concurrency |
| **Tool Execution** | Reflection (`@Tool`, `Method.invoke`) | Type-safe `Tool[R, In, Out]` with `zio.json.ast.Json` |
| **Human-in-the-Loop** | State serialization & `Mono.empty()` | Non-blocking `zio.Promise[Nothing, PermissionDecision]` |
| **Subagents** | `WorkspaceTaskRepository` + thread pools | Child fibers (`forkScoped`) merged via `ZStream.merge` |
| **Resource Safety** | Lifecycle singletons with `volatile` | Scoped effects via `ZIO.acquireReleaseWith` |
| **Testing** | `reactor.test.StepVerifier` + sleeps | `ZIOSpecDefault` + deterministic `TestClock` |

---

## 5. Implementation sequencing

The original research proposed progressing from domain models and streaming foundations to the ReAct engine, harness components, MCP/subagents, and host integration. This is historical sequencing context, not a second delivery checklist.

The [completion checklist](architecture/tracker.md) is the sole current list of verified outcomes, remaining work and the next task. The [parity reference](architecture/test-parity.md) maps upstream behavior to executable scenarios and intentional adaptations; its legacy checkboxes are not a separate completion plan. Maintain delivery status in the tracker rather than duplicating it here.

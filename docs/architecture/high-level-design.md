# High-level architecture

## Mission and authority

Build a pure functional Scala 3/ZIO 2 runtime with deterministic authority over lifecycle, state, permissions, and completion. Selected AgentScope Java mechanics inform the design; attribution does not imply exhaustive parity.

The [tracker](tracker.md) distinguishes available components from the complete target platform.

## Dependency structure

```text
Host application / workflow orchestrator
  | supplies model transport, approval authentication, durable
  | checkpoints, filesystem ownership, and middleware composition
  v
harness: compactor, eviction/store port, permission components
  |
  v
core: models, codecs, events, tools, client port,
      SSE parsing/assembly, scoped HTTP completion, ReAct lifecycle, structured output

mcp: design scaffolding; implementation pending
```

The host owns durable transactions, replay, tenant authentication, and execution idempotency. ReActAgent owns in-memory conversation state and serializes runs. Per-run and per-request scopes bound resources independently of the caller's outer scope.

## Available components

Core provides pure domain models and codecs, 39 event variants, typed tools, effectful path screening, SSE framing/assembly, scripted clients, and the ReAct state machine. ReAct supports bounded retries/iterations, timeout and interruption reconciliation, inline provider tools, and native/synthetic structured output. ChatCompletionsClient integrates scoped text/function HTTP completion with correlated incremental events.

Harness provides pair-safe compaction, summary replacement and user provenance, token estimation, injected flush/offload effects, immediate eviction, permission decisions, interactive Promise coordination, and serializable approval values.

Local persistence explicitly requires WorkspaceTrust.HostControlled, OS-protected ancestors, and hard-link support. Untrusted fails closed. Canonical checks do not make attacker-writable directories safe.

ToolResultEvictor.processor is an opt-in adapter for completed local results in ReAct. Compaction remains standalone. InteractivePermissionAuthorizer is an opt-in adapter for permission policy and active-invocation confirmation. Permission decisions are not durable execution checkpoints. Core ToolAuthorizer provides scoped local execution decisions and exact approved-input persistence; its default allows calls, and hosts can install the interactive harness adapter. Durable checkpoints remain pending.

## Planned platform

- [ ] Remaining provider/event integrations and retry/failover policies.
- [ ] Agent middleware and durable HITL integration.
- [ ] MemoryFlushManager and MemoryConsolidator.
- [ ] Scoped subagents, depth limits, and persistent TaskSupervisor promotion.
- [ ] Plan-mode tool restrictions and shell inspection.
- [ ] MCP protocol, transports, discovery, and toolkit bridging.

See [mid-level requirements](mid-level-design.md) and the [parity audit](test-parity.md).

## Engineering contracts

[AGENTS.md](../../AGENTS.md) owns INV-1 through INV-12. Interrupted local calls receive Error observations under INV-2. INV-5 governs summary deduplication, INV-6 immediate eviction, and INV-7 permission priority.

All external effects belong in ZIO. Use total immutable transformations, typed errors, scoped resources, derived codecs where appropriate, explicit public types, and ADTs. Formatting, compilation, and tests are automated; semantic purity, English, security assumptions, and scenario parity require independent review.

The build pins Scala 3.9.0, ZIO/ZIO Streams 2.1.26, zio-json 0.9.1, and zio-http 3.11.3. A dependency does not imply a corresponding transport implementation.

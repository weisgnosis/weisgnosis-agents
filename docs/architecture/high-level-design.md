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

Harness provides pair-safe compaction, summary replacement and user provenance, token estimation, daily-memory extraction with scoped local storage, atomic in-memory consolidation, and recoverable local consolidation, offload effects, immediate eviction, permission decisions, interactive Promise coordination, and serializable approval values.

Local persistence explicitly requires WorkspaceTrust.HostControlled and OS-protected ancestors. Tool-result publication requires hard-link support; daily-memory publication requires atomic-move support. Untrusted fails closed. Canonical checks do not make attacker-writable directories safe.

ToolResultEvictor.processor is an opt-in adapter for completed local and matched inline provider results in ReAct. ConversationCompactor.preparer installs compaction before model requests through the validated core history port. InteractivePermissionAuthorizer is an opt-in adapter for permission policy and active-invocation confirmation. Permission decisions are not durable execution checkpoints. Core ToolAuthorizer provides scoped local execution decisions and exact approved-input persistence; its default allows calls, and hosts can install the interactive harness adapter. Durable checkpoints remain pending.

ToolResultEvictor.preparer can precede ConversationCompactor.preparer via HistoryPreparer.andThen. Changed stages are validated before the next runs; runtime commits once after scope cleanup. Store-backed preview verification prevents cache chains while valid original content remains available in the configured key namespace. Custom stores must implement resolve; missing or forged references are raw candidates, while typed storage failures preserve input. See the [eviction contract and migration](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/eviction/README.md).

ToolResultStore.local accepts ResultStorageLocation.WorkspaceDefault or AbsoluteDirectory(path). Absolute selection requires an existing host-owned directory and leaves the unused workspace untouched. Canonical/type checks run before each operation; protection of external ancestors remains a host requirement. The default path and publication semantics are unchanged.

ModelInputTransform projects a request-only history after canonical preparation commits and before ModelRequestStart. Its scope closes before structural validation and dispatch. Canonical history, recovery positions, and usage remain authoritative; projection failures use ModelInputError and cannot trigger native fallback. Complete pairs may be omitted without losing canonical eviction or stored payloads. The port transforms history only; semantic fidelity and newly injected content remain host responsibilities.

LocalDailyMemoryStore.open provides a scoped daily-only backend for a host-controlled workspace. A CREATE_NEW ownership file excludes cooperating competitors; admitted filesystem work completes before cancellation/release. Whole UTF-8 ledgers are published through atomic replacement. Stale leases require explicit operator recovery; crash durability and untrusted workspace protection are not provided.

MemoryConsolidator validates replacement memory against a 1–4,000 token budget using the supplied counter before committing. Its store snapshot revision covers curated and daily state, so stale output conflicts instead of overwriting newer memory. MemoryRepository.inMemory atomically updates curated content and acknowledged daily inputs; LocalMemoryRepository gives cooperating API users coherent content/progress visibility through an exclusive lease and roll-forward journal. Raw filesystem readers can observe intermediate files; no fsync or power-loss durability is claimed.

## Planned platform

- [ ] Remaining provider/event integrations and retry/failover policies.
- [ ] Agent middleware and durable HITL integration.
- [ ] Remote memory storage and timestamp-watermark migration, remote transcript persistence, and memory scheduling.
- [ ] Scoped subagents, depth limits, and persistent TaskSupervisor promotion.
- [ ] Plan-mode tool restrictions and shell inspection.
- [ ] MCP protocol, transports, discovery, and toolkit bridging.

See [mid-level requirements](mid-level-design.md) and the [parity audit](test-parity.md).

## Engineering contracts

[AGENTS.md](../../AGENTS.md) owns INV-1 through INV-12. Interrupted local calls receive Error observations under INV-2. INV-5 governs summary deduplication, INV-6 immediate eviction, and INV-7 permission priority.

All external effects belong in ZIO. Use total immutable transformations, typed errors, scoped resources, derived codecs where appropriate, explicit public types, and ADTs. Formatting, compilation, and tests are automated; semantic purity, English, security assumptions, and scenario parity require independent review.

The build pins Scala 3.9.0, ZIO/ZIO Streams 2.1.26, zio-json 0.9.1, and zio-http 3.11.3. A dependency does not imply a corresponding transport implementation.

## Transcript observations

SessionTranscriptWriter records summaries, text/thinking and bounded tool observations through one transcript-bound store. TranscriptStore.inMemory owns atomic batch deduplication and parent linkage; stable keys preserve the first observation. The compactor offload callback and required TurnCompletionObserver adapter are available; the host explicitly selects stable-only or repeated-anonymous snapshot recording. Remote persistence and memory scheduling remain pending. LocalTranscriptStore now supplies strict versioned JSONL in one explicitly selected host-controlled directory, with cooperative scope ownership and atomic whole-log replacement. See the [transcript contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/transcript/README.md).

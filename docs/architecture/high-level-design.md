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
- [x] Static plan inspection, opt-in tool restrictions and in-memory reviewed sessions.
- [x] Truthful request-only plan projection with preserved canonical history.
- [ ] Safe plan files, control tools and child propagation.
- [ ] MCP protocol, transports, discovery, and toolkit bridging.

See [mid-level requirements](mid-level-design.md) and the [parity audit](test-parity.md).

## Engineering contracts

[AGENTS.md](../../AGENTS.md) owns INV-1 through INV-12. Interrupted local calls receive Error observations under INV-2. INV-5 governs summary deduplication, INV-6 immediate eviction, and INV-7 permission priority.

All external effects belong in ZIO. Use total immutable transformations, typed errors, scoped resources, derived codecs where appropriate, explicit public types, and ADTs. Formatting, compilation, and tests are automated; semantic purity, English, security assumptions, and scenario parity require independent review.

The build pins Scala 3.9.0, ZIO/ZIO Streams 2.1.26, zio-json 0.9.1, and zio-http 3.11.3. A dependency does not imply a corresponding transport implementation.

## Transcript observations

SessionTranscriptWriter records summaries, text/thinking and bounded tool observations through one transcript-bound store. TranscriptStore.inMemory owns atomic batch deduplication and parent linkage; stable keys preserve the first observation. The compactor offload callback and required TurnCompletionObserver adapter are available; the host explicitly selects stable-only or repeated-anonymous snapshot recording. Remote persistence remains pending; owned local flush/consolidation scheduling is available below. LocalTranscriptStore now supplies strict versioned JSONL in one explicitly selected host-controlled directory, with cooperative scope ownership and atomic whole-log replacement. See the [transcript contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/transcript/README.md).

## Memory lifecycle policy

Typed MemoryTrigger policies and a shared in-memory MemoryTriggerGate now provide operation/scope admission with monotonic intervals and cleanup that preserves active windows. The host chooses the owner and explicitly shares its capability. This is admission timing only. Generic owned queue supervision is available as described below; completion-triggered flush is integrated, with opt-in background consolidation available below. See the [memory contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md).

## Owned background task foundations

MemoryTaskQueue supplies one bounded serial worker per explicit memory owner, coalesces only pending work for the same runtime conversation token, and exposes terminal outcomes through tickets. Worker lifetime belongs to the host Scope, surviving individual agent invocation scopes. Shutdown cancels queued work and joins running cleanup. Completion-triggered flush integration is available; consolidation scheduling is also opt-in; see the [memory contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md).

## Completion-triggered flush integration

MemoryFlushScheduler now connects successful ReAct completion to owned background gate admission and daily-memory extraction. Empty/internal-only snapshots return Empty without queue admission; eligible work captures the immutable completed conversation. The host explicitly retains task receipts and preserves prefixes through awaited compactor flushing. Owned consolidation is available; combined daily-retention maintenance is available below; distributed scheduling remains pending; see the [memory lifecycle contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md).

## Owned background consolidation

MemoryConsolidationScheduler shares the owner's bounded queue and claims an independent Maintenance interval inside the worker. It reads currently committed daily memory and reports every returned consolidation outcome. The host retains tickets and result reports; post-commit reporting failure cannot undo memory. Completion observer composition orders admission, while FIFO replacement can run consolidation before a newer flush. Combined retention policy is available below; remote ownership remains roadmap work.

## Conservative daily archival

MemoryRepository now separates daily, consolidation and retention authority. Explicit archival preserves old unacknowledged content, stores acknowledged versions by date/content identity, and never discards an active file before verifying its archive copy. Local failures may leave duplicate copies or pending daily content; inspection and later consolidation remain possible. Automatic retention policy, session pruning and remote storage are not implemented by this capability.

## Combined daily maintenance

The opt-in MemoryMaintenanceScheduler owns one job for cutoff selection, consolidation, acknowledged archival and aggregate reporting. It is an alternative to the consolidation-only scheduler for the same Maintenance window. Daily retention defaults to 90 calendar days with UTC interpretation, while KeepAll explicitly disables archival. A later failure cannot undo an earlier committed phase. Automatic session discovery remains unfinished; explicit transcript retirement and immutable memory policy configuration are available below.

## Transcript retirement boundary

Explicit local transcript pruning now shares writer ownership and validates complete persisted history before removing the fixed payload. Recency uses both filesystem metadata and every recorded observation. Successful pruning ends the persisted transcript generation; the host retires that logical session. The explicit 180-day policy is available below; automatic session enumeration remains separate work; explicit batch integration is implemented below.

## Transcript expiry policy

Selected transcript pruning now accepts KeepAll or a validated fixed-day policy, defaulting explicitly to 180 days. The disabled policy performs no clock/filesystem work; enabled policy uses one validated cutoff before ownership. Explicit batch selection and integration into the existing maintenance job are implemented below.

## Combined explicit maintenance

An opt-in MemoryMaintenanceCoordinator now joins daily maintenance and an ordered host-selected transcript batch under one existing owned job. Transcript cutoff validation precedes memory work; returned memory outcomes permit independent pruning, while effect failure stops later phases. Partial commits/deletions survive later failure. Session discovery remains separate work; immutable configuration is implemented below.

## Immutable memory policy

MemoryConfig now combines defaults and overrides without a mutable builder. Validated budgets and a fixed-shape consolidation template preserve total rendering and runtime size enforcement. Hosts explicitly share gates, queues, repositories and extractors; configuration does not install ownership or select a model. All ten pinned MemoryConfigTest methods are classified through documented Scala adaptations.

## Plan-mode foundation

Static shell eligibility now uses a small positive grammar with typed rejection and parsed argv. It deliberately replaces upstream shell-name escape hatches with conservative inspection. Opt-in runtime plan admission is implemented below; safe plan storage, model-facing transition tools and child propagation remain pending. Host executors still own executable binding, canonical paths, resource limits and process scope.

## Plan admission authority

PlanPermissionGate now integrates explicit host-owned mode and tool classifications into normal permission admission. It restricts capabilities without granting permission, and execution requests still require confirmation. The runtime persists and streams denied results. Mode changes affect later checks; they do not revoke already admitted work. In-memory host-approved transitions are implemented below; plan persistence, model-facing transition tools and child restrictions remain separate work.

## Plan-session review authority

An in-memory PlanSession now distinguishes unprepared planning, concrete drafts, pending review and a host-approved draft in Build. The host retains approval authority separately from model-facing control. Edits and re-entry invalidate previous requests; old waiters are settled rather than left suspended. Approval records exactly the reviewed draft. This is not evidence of a plan file or a durable user approval record.

## Plan request context

PlanInputTransform now projects one session snapshot into each model request without mutating canonical history or System messages. It distinguishes absent drafts, pending review and exact approved content, never inventing files or completed execution. Current permission state remains authoritative if the mode changes during model reasoning.

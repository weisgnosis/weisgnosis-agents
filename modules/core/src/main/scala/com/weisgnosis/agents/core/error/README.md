# Typed domain failures

[AgentError.scala](AgentError.scala) defines the sealed domain failure hierarchy, including configuration, serialization, permission, model, timeout, and tool errors. Domain effects expose these failures in the ZIO error channel. Tool execution errors become model observations at the toolkit boundary; cancellation remains cancellation.

The dedicated exhaustive upstream error-scenario mapping remains tracked with the wider core parity audit. Current scoped acceptance is recorded below.

## Message API migration acceptance

- [x] `review_fp` and `review_harness` approved this package's changed Scala files against `9c47bf5` on 2026-09-29, with no unresolved findings in the message-boundary migration.
- [x] Repository verification passed 242 core and 40 harness tests with zero compiler warnings.

The accepted migration is recorded in commit `a701612`. See the [validated message API](../model/README.md) for source compatibility changes, scope, and evidence. Core callers preserve roles and optional fields; reasoning/structured-output filtering preserves message identity and metadata. Harness transformations preserve tool pairing, retained-user provenance, eviction fallback, and cancellation. This acceptance covers the changed files only; broader package and upstream parity gaps remain tracked.

AgentError.InvalidMessage represents invalid role/content combinations from dynamic construction and updates. MsgConstructionSpec verifies its typed channel and JSON round trip. An exhaustive error-hierarchy scenario suite remains pending.

## Text transformation failure boundary

TextTransformationError is the sole typed error accepted by ModelTextTransform. External failures must be mapped explicitly to it; this distinguishes host transformation failures from ModelError provider rejections eligible for native structured fallback. Runtime propagation retains the typed error, and cancellation remains interruption. Adding a sealed-hierarchy leaf requires exhaustive AgentError matches to handle it.

- [x] `review_fp` and `review_harness` approved the final seven-file text-transformation increment against `02b4654` on 2026-09-29 after the fallback-provenance correction. No findings remain within this scope. Full formatting, fatal-warning compilation, and verification passed 434 core and 40 harness tests.

The [error spec](../../../../../../../test/scala/com/weisgnosis/agents/core/error/AgentErrorSpec.scala) covers the leaf codec/diagnostic contract. Client tests additionally verify compile-time separation and no fallback after a mapped provider-like host error. Broader hierarchy parity remains pending.

The [client transformation table](../client/README.md) records unchanged text-transformation identities; commit `2a400e0` preserves all seven identities from that increment.

## Reasoning policy failure boundary

ReasoningHookError is the sole typed callback failure for PostReasoningHook and also reports invalid replacement roles. It cannot be confused with a ModelError native-format rejection. PostReasoningHookSpec verifies its derived codec and the compile-time channel boundary; runtime tests verify no fallback after policy failure. Exhaustive error matches must handle this additional sealed-hierarchy leaf.

- [x] `review_fp` and `review_harness` approved the final six-file post-reasoning increment against `2a400e0` on 2026-09-29 after the legacy-usage correction. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 456 core and 40 harness tests.

The [agent table](../agent/README.md) records unchanged post-reasoning identities; commit `8850a60` preserves all six identities from that increment.

## History preparation failure boundary

HistoryPreparationError is the sole typed HistoryPreparer callback failure and reports invalid structural rewrites. The compactor adapter maps summarizer/hook failures to this leaf, preventing provider-status confusion and Auto fallback. HistoryPreparerSpec covers its codec and compile-time boundary; core and harness integration tests verify no model dispatch or fallback on preparation failure. Exhaustive error matches must handle this added leaf.

- [x] `review_fp` and `review_harness` approved the final nine-file history-preparation increment against `3c6958f` on 2026-09-29. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 497 core and 73 harness tests, including twenty added tests. Acceptance covers structural rewrite validation and scoped compactor integration; semantic summary fidelity and exhaustive provider-format validation are excluded. Current identities are in the [agent table](../agent/README.md).

- [x] `review_fp` and `review_harness` approved the final seven-file model-input scope against `40f6f05` on 2026-09-29. Sixteen added regressions and full formatting/fatal-warning verification passed: 529 core and 101 harness tests. No findings remain within request-only projection, structural validation, canonical-state isolation, and eviction integration. Broader legacy hook/middleware parity remains pending. Exact identities and migration are in the [agent contract](../agent/README.md).

ModelInputError is the dedicated callback/structural-validation channel for ModelInputTransform. It cannot trigger provider-native fallback. ModelInputTransformSpec covers derived JSON and compile-time channel separation; runtime tests verify dispatch exclusion and no fallback. Exhaustive matches must include the new sealed-hierarchy leaf.

## Memory storage failure boundary

MemoryStorageError is the derived-codec leaf for local memory I/O, ownership and closed-handle failures. Path-policy rejection remains PermissionDenied. Ownership cleanup failure is observable as a scope-finalizer defect containing the storage error. Exhaustive matches on the sealed AgentError hierarchy must include the new leaf. AgentErrorSpec verifies JSON and diagnostic identity; filesystem and lease suites verify the runtime boundaries.

- [x] `review_fp` and `review_harness` approved the final seven-file local daily-memory backend against `d1a4cf2` on 2026-09-29. Nineteen added tests exposed and corrected pending cancellation being reported as success after admitted work. Full formatting/fatal-warning verification passed 530 core / 154 harness tests. Acceptance covers cooperative ownership, scoped cleanup, day-derived paths, atomic replacement, and typed storage errors on the tested Windows filesystem. Crash durability, exactly-once delivery, hostile mutation protection, dynamic symlink/junction behavior, and automatic stale-lease recovery are excluded. Current identities are in the [memory contract](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md).

## Completion observation failure boundary

CompletionObservationError is the dedicated derived-codec leaf for required TurnCompletionObserver failures. It cannot trigger native structured fallback. TurnCompletionObserverSpec verifies its codec and compile-time channel separation; ReActAgentCompletionSpec verifies original/observer sequential causes, publication suppression, and exactly one observation attempt. Cancellation and scope-finalizer defects remain in Cause. Exhaustive AgentError matches must include this leaf.

- [x] `review_fp` and `review_harness` approved the final eight-file completion-observer increment against `0349b1a` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 552 core / 264 harness tests, including 28 new cases, with zero compiler warnings. No findings remain within scoped once-per-invocation observation, canonical snapshots, cause preservation, explicit transcript identity policy, and local persistence integration. Background memory scheduling, remote persistence, delivery acknowledgement, and exhaustive upstream middleware parity remain outside this scope.

Current source identities are in the [agent completion table](../agent/README.md).

# Typed error verification

[AgentErrorSpec](AgentErrorSpec.scala) verifies TextTransformationError and MemoryStorageError codec identities and diagnostics. The client transformation suite checks that provider ModelError cannot inhabit the transform callback error channel, and that explicitly mapped host failures do not trigger native-format fallback.

- [x] `review_fp` and `review_harness` approved the final seven-file text-transformation increment against `02b4654` on 2026-09-29 after the fallback-provenance correction. No findings remain within this scope. Full formatting, fatal-warning compilation, and verification passed 434 core and 40 harness tests.
- [ ] Complete exhaustive error-hierarchy and upstream scenario mapping; this suite does not claim it.

The [client transformation table](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md) records unchanged text-transformation identities; commit `2a400e0` preserves all seven identities from that increment.

[PostReasoningHookSpec](../agent/PostReasoningHookSpec.scala) separately verifies ReasoningHookError codec/provenance. The current [agent acceptance](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md) records that increment.

[HistoryPreparerSpec](../agent/HistoryPreparerSpec.scala) verifies the HistoryPreparationError codec and narrow callback channel. Runtime and harness tests additionally distinguish summary failures from native provider rejection; acceptance is recorded in the [agent table](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md).

[ModelInputTransformSpec](../agent/ModelInputTransformSpec.scala) verifies ModelInputError JSON and compile-time separation from ModelError. Runtime projection tests verify no dispatch or native fallback on this channel. Acceptance belongs to the agent model-input scope.

- [x] `review_fp` and `review_harness` approved the final seven-file local daily-memory backend against `d1a4cf2` on 2026-09-29. Nineteen added tests exposed and corrected pending cancellation being reported as success after admitted work. Full formatting/fatal-warning verification passed 530 core / 154 harness tests. Acceptance covers cooperative ownership, scoped cleanup, day-derived paths, atomic replacement, and typed storage errors on the tested Windows filesystem. Crash durability, exactly-once delivery, hostile mutation protection, dynamic symlink/junction behavior, and automatic stale-lease recovery are excluded. Current identities are in the [memory contract](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md).

AgentErrorSpec also checks PlanStorageError's diagnostic and individual derived JSON codec. Plan filesystem and ownership suites exercise failure provenance. This does not claim exhaustive hierarchy/upstream error coverage.

- [x] `review_fp` and `review_harness` approved the final nine-file standalone plan-storage increment against `083617b` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 556 core / 520 harness tests, including fifteen new cases, with zero compiler warnings. No findings remain within exact content/encoding, scoped cooperative ownership, atomic replacement, hardlink preservation, typed errors and cancellation ordering. Session/publication integration, persisted approval, hostile filesystem isolation, crash durability and new whole-method upstream parity are excluded.

## Spawn depth failure boundary

SpawnDepthExceeded(maximumDepth) is the individual derived-codec error for runtime lineage rejection before callback invocation or UUID allocation. Exhaustive AgentError matches must include this new leaf. AgentErrorSpec verifies diagnostics and codec identity; nested ReAct harness tests verify ordinary tool Error normalization and continued completion. The error is data, not a spawn capability. Current source identities are in the [lineage table](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#current-lineage-identities).

- [x] `review_fp` and `review_harness` approved the final five-file lineage scope against `279b7a3` on 2026-09-29, including the corrected ancestry assertion. Full `sbt scalafmtAll scalafmtSbt verify` passed 557 core / 601 harness tests, including nine new cases, with zero compiler warnings. No findings remain within runtime-owned depth <=3, suspended identity/callback effects, sibling lineage, nested cancellation and actual ReAct depth-error normalization. Registry, child factory configuration, automatic source tagging, inherited permission/plan policy and model-facing spawn/send remain excluded.

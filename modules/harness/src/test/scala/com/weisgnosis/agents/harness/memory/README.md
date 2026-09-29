# Conversation compaction verification

- [ConversationCompactorSpec](ConversationCompactorSpec.scala): 27 tests for pair-safe cutoff, summary/flush/tail routing, repeated compaction, user provenance, pruning, unchanged below-trigger history, and typed/interrupted hooks.
- [ConversationCompactorIntegrationSpec](ConversationCompactorIntegrationSpec.scala): 12 tests for canonical history installation, shrinking-history recovery, failure policy, preparation/downstream cancellation, mixed cleanup causes, and separate-agent isolation.
- [TokenCounterSpec](TokenCounterSpec.scala): deterministic additive estimation.

The [method mapping](../../../../../../../../../../docs/architecture/test-parity.md#compaction-method-applicability) accounts for seven upstream compactor and ten middleware methods. It distinguishes tested adaptations, deliberate failure-policy divergences, and the inapplicable cyclic-Throwable mechanism. The concurrent tests coordinate entry and release with Promise; each agent owns a separate history. No timing sleeps or shared-agent multi-session claims are involved.

Existing hardening regressions retain occurrence pairing, inline/local isolation, protected signed arguments, stale argument-cache removal, and retained-user provenance. Summary content is supplied by test effects; these tests do not prove semantic summary fidelity.

## Current review acceptance

- [x] `review_fp` and `review_harness` approved both changed compactor suites against `52da827` on 2026-09-29; no findings remain in this scope.
- [x] Full formatting, fatal-warning compilation, and verification passed 529 core / 114 harness tests, including all 39 compactor cases. Thirteen cases were added and existing routing assertions strengthened.
- [ ] Add curated consolidation, transcript, and scheduling suites with their implementations.

Exact current test identities and failure/ownership contracts are maintained in the [memory README](../../../../../../../main/scala/com/weisgnosis/agents/harness/memory/README.md). Prior acceptance remains in Git through `52da827`. Run `sbt "harness / test"` or repository-wide `sbt verify`.

## Daily-memory verification

- [MemoryFlushManagerSpec](MemoryFlushManagerSpec.scala): 14 tests including both named upstream daily-flush methods, context/date binding, midnight rollover, provenance, preview bounds, no-content output, resource cleanup, storage errors, and one-manager serialization.
- [MemoryExtractorSpec](MemoryExtractorSpec.scala): five client-adapter tests for inputs/configuration, scoped hub cleanup, thinking-only output, rejected tool observations, interruption, and typed failure cleanup.
- [MemoryFlushManagerIntegrationSpec](MemoryFlushManagerIntegrationSpec.scala): three runtime tests for append/offload/summary/dispatch ordering, append failure preserving canonical input, and cancelled extraction with cleanup/recovery.

The store is an injected capability exercised with Ref-backed test implementations. These tests do not establish filesystem persistence, cross-process atomicity, semantic deduplication, or curated writes. Current source identities and review evidence belong in the [memory contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/memory/README.md).

- [x] `review_fp` and `review_harness` approved the seven-file daily-memory extraction increment against `738b216` on 2026-09-29. No findings remain within the injected-store capability, scoped client extraction, provenance/date handling, one-manager serialization, and compactor integration. Full formatting/fatal-warning verification passed 529 core / 136 harness tests, including 22 new tests. Filesystem persistence, cross-manager/process guarantees, consolidation, watermarks, and transcript offloading are excluded.

## Filesystem verification

- [LocalDailyMemoryStoreSpec](LocalDailyMemoryStoreSpec.scala): 12 real filesystem tests for missing context, dated UTF-8 appends, independent days, parallel whole entries, competing owners, scope reuse/closure/cancellation, unsafe/nonregular paths, manager integration, and hard-linked curated preservation.
- [DailyMemoryLeaseSpec](DailyMemoryLeaseSpec.scala): six deterministic ownership tests for stale tokens, blocked scope closure, queued cancellation, admitted-work completion before cancellation, foreign-token retention, and explicit caller masking.
- [MemoryTestSupport](MemoryTestSupport.scala): scoped temporary directories and directory streams; all filesystem operations are suspended.

The admitted-cancellation test failed before the explicit pending-interruption check was added. Both ordinary and explicitly masked caller behavior are now covered. These tests exercise the local Windows filesystem; cross-process, dynamic symlink/junction, crash, and fsync guarantees are not claimed.

- [x] `review_fp` and `review_harness` approved the final seven-file local daily-memory backend against `d1a4cf2` on 2026-09-29. Nineteen added tests exposed and corrected pending cancellation being reported as success after admitted work. Full formatting/fatal-warning verification passed 530 core / 154 harness tests. Acceptance covers cooperative ownership, scoped cleanup, day-derived paths, atomic replacement, and typed storage errors on the tested Windows filesystem. Crash durability, exactly-once delivery, hostile mutation protection, dynamic symlink/junction behavior, and automatic stale-lease recovery are excluded.

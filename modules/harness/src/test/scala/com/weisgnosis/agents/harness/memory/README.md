# Conversation compaction verification

- [ConversationCompactorSpec](ConversationCompactorSpec.scala): 27 tests for pair-safe cutoff, summary/flush/tail routing, repeated compaction, user provenance, pruning, unchanged below-trigger history, and typed/interrupted hooks.
- [ConversationCompactorIntegrationSpec](ConversationCompactorIntegrationSpec.scala): 12 tests for canonical history installation, shrinking-history recovery, failure policy, preparation/downstream cancellation, mixed cleanup causes, and separate-agent isolation.
- [TokenCounterSpec](TokenCounterSpec.scala): deterministic additive estimation.

The [method mapping](../../../../../../../../../../docs/architecture/test-parity.md#compaction-method-applicability) accounts for seven upstream compactor and ten middleware methods. It distinguishes tested adaptations, deliberate failure-policy divergences, and the inapplicable cyclic-Throwable mechanism. The concurrent tests coordinate entry and release with Promise; each agent owns a separate history. No timing sleeps or shared-agent multi-session claims are involved.

Existing hardening regressions retain occurrence pairing, inline/local isolation, protected signed arguments, stale argument-cache removal, and retained-user provenance. Summary content is supplied by test effects; these tests do not prove semantic summary fidelity.

## Current review acceptance

- [x] `review_fp` and `review_harness` approved both changed compactor suites against `52da827` on 2026-09-29; no findings remain in this scope.
- [x] Full formatting, fatal-warning compilation, and verification passed 529 core / 114 harness tests, including all 39 compactor cases. Thirteen cases were added and existing routing assertions strengthened.
- [ ] Add transcript and scheduling suites with their implementations; local consolidation acceptance follows below.

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

## Consolidation verification

- [MemoryRepositorySpec](MemoryRepositorySpec.scala): six tests for daily/curated writer separation, atomic content/progress observations, stale revisions, later pending appends, domain codecs, and compile-time rejection of cross-store revision tokens.
- [MemoryConsolidatorSpec](MemoryConsolidatorSpec.scala): 15 tests for deterministic prompts, blank/no-input behavior, exact budget boundaries, default estimation, malformed inputs, scope-before-commit, errors/cancellation, concurrent mutation, competing consolidators, per-instance serialization, and shared daily/client-backed integration.

These are process-local atomicity and service-contract tests. The earlier increment left all five upstream watermark filesystem methods pending; current local adaptations are tracked in the [parity mapping](../../../../../../../../../../docs/architecture/test-parity.md). No timestamp watermark, local fallback, filesystem transaction, or crash guarantee is inferred from the in-memory repository.

- [x] `review_fp` and `review_harness` approved the final six-file consolidation increment against `46c6174` on 2026-09-29. Twenty-one new tests and full formatting/fatal-warning verification passed 530 core / 175 harness tests. No findings remain within process-local atomic content/progress commits, separate writer capabilities, scoped extraction, configured-counter budget enforcement, and conflict handling. Filesystem transactions, persistent watermarks, crash recovery, and the five upstream filesystem mappings remain outside this scope.

## Recovery and persistence verification

- [LocalMemoryRepositorySpec](LocalMemoryRepositorySpec.scala): 25 cases for exact checkpoint placement, reopen/progress, stale revisions, identical-content generations, all four old/new publication combinations through both entry points, recovery before every operation, mismatch retention, absent/empty distinction, malformed/legacy data, shared ownership, stale leases, and consolidator integration.
- [MemoryJournalSpec](MemoryJournalSpec.scala): six strict-decoding and fingerprint cases.
- [MemoryFilesSpec](MemoryFilesSpec.scala): three real filesystem cases for enumeration, nonregular targets, and journal-only removal.

The recovery matrix seeds deterministic interrupted-publication states. These are not real process-crash tests or injected I/O failures midway through a live commit. Existing DailyMemoryLeaseSpec verifies admitted-work cancellation and scope ordering; LocalDailyMemoryStoreSpec remains the shared backend's daily regression suite. Test evidence does not establish fsync/power-loss durability, cross-process races, or dynamic symlink/junction resistance. Three upstream filesystem methods have explicit local adaptations; two remote scenarios remain pending in the parity mapping.

Current persistence review status and exact identities are maintained in the source memory README.

- [x] `review_fp` and `review_harness` approved the final nine-file persistence increment against `e4bd6b0` on 2026-09-29. No findings remain within cooperative local ownership, strict journal validation, recovery before API operations, conditional content/progress publication, and persisted generation tracking. Full `sbt scalafmtAll scalafmtSbt verify` passed 530 core / 209 harness tests, including 34 new persistence tests, with zero compiler warnings. Acceptance covers constructed interrupted-publication states on the tested Windows filesystem; live crash/fault-injection, fsync durability, remote storage, and hostile mutation are excluded.

DailyMemoryLeaseSpec now exercises the extracted WorkspaceLease through the existing memory adapter. MemoryTestSupport is harness-internal for shared scoped filesystem fixtures; no test behavior changed. Current extraction identity and approval are recorded in the transcript persistence README.

## Lifecycle trigger verification

- [MemoryIntervalSpec](MemoryIntervalSpec.scala): three exact-duration, invalid-bound and strict numeric-codec cases.
- [MemoryTriggerSpec](MemoryTriggerSpec.scala): four zero/positive/negative trigger, immutable defaults, and derived DTO codec cases.
- [MemoryTriggerGateSpec](MemoryTriggerGateSpec.scala): sixteen cases covering first admission at clock origin, exact expiry, anonymous/user/session/owner isolation, independent operations across eight scope/order combinations, shared caller authority, delimiter preservation, 100 simultaneous claims, stale/mixed cleanup, active-window protection, cleanup/renewal races, explicit policy reset, signed wraparound and backward clock movement.

TestClock controls ordinary timing. The wraparound fixture delegates clock services and supplies only nanoTime through Ref, because TestClock's Instant-to-nanosecond conversion overflows at that boundary. No blocking sleeps or process-global test resets are used. The [parity audit](../../../../../../../../../../docs/architecture/test-parity.md#memory-trigger-admission-applicability) records local adaptations and pending remote/scheduling evidence.

- [x] `review_fp` and `review_harness` approved the final six-file memory-trigger increment against `73cd376` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 552 core / 287 harness tests, including 23 new cases, with zero compiler warnings. No findings remain within typed finite intervals, strict numeric codecs, immutable policies, explicit shared ownership, atomic monotonic admission and active-window-safe cleanup. Scheduling, execution locks, remote authority and full memory configuration parity remain outside this scope.

## Scoped task queue verification

- [MemoryTaskTicketSpec](MemoryTaskTicketSpec.scala): three runtime identity, independent waiter cancellation and rejection-codec cases.
- [MemoryTaskQueueSpec](MemoryTaskQueueSpec.scala): nine positive-capacity/idle, nonblocking submission, pending-only/FIFO coalescing, running-token isolation, execution-time freshness, bounded replacement, cleanup serialization, failure/self-interruption progression and independent-owner cases.
- [MemoryTaskQueueLifecycleSpec](MemoryTaskQueueLifecycleSpec.scala): eight shutdown, pending cancellation, cleanup-cause, post-close rejection, concurrent submit/close, immediate-close settlement, masked-acquisition and host-versus-invocation scope cases.

The masked-acquisition regression failed before the worker was made explicitly interruptible. Its red version manually released the blocked task after checking closure, avoiding a leaked test fiber. Ticket assertions preserve interrupted/defective task outcomes. Admission/close races and repeated immediate closure exercise settlement; they are not an exhaustive proof of every scheduler interleaving. These queue-only suites do not install real memory completion hooks; the scheduler suites below provide that integration evidence.

- [x] `review_fp` and `review_harness` approved the final five-file scoped queue increment against `ad12c7b` on 2026-09-29. Twenty new tests reproduced and corrected masked-acquisition shutdown blocking; full `sbt scalafmtAll scalafmtSbt verify` passed 552 core / 307 harness tests with zero compiler warnings. No findings remain within bounded pending work, FIFO coalescing, runtime ticket outcomes, host-owned worker/task scopes and cancellation-safe cleanup. Real memory hooks, trigger/extractor integration, durable tasks and distributed scheduling remain outside this scope.

## Completion-triggered flush verification

[MemoryFlushSchedulerSpec](MemoryFlushSchedulerSpec.scala) has ten cases. Four named upstream adaptations exercise real ReAct completion, queue admission, manager preparation and extraction: completion before blocked extraction, serial extraction, same-conversation pending coalescing, and independent pending conversations sharing one owner. Further cases verify empty/internal no-op before admission, invalid provenance, consumed throttling after extraction failure, immutable snapshot capture across history reset, local-store reopen, and awaited compactor prefix preservation.

[MemoryFlushSchedulerLifecycleSpec](MemoryFlushSchedulerLifecycleSpec.scala) has seven cases for blocked/failed worker-side gates, host-retained tickets after invocation scope closure, failed/cancelled-execution exclusion, explicit cooperative-success policy, registration failure without rollback, and closed/capacity observation errors. Existing fourteen MemoryFlushManagerSpec cases continue to exercise the shared pure preparation and deferred flush effects.

These seventeen added tests cover flush integration only. Maintenance gate/consolidator scheduling and automatic outcome monitoring remain excluded.

- [x] `review_fp` and `review_harness` approved the final four-file flush-scheduler increment against `1c8e3ee` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 552 core / 324 harness tests, including 17 new integration cases, with zero compiler warnings. No findings remain within shared pure preparation, successful-completion queue integration, immutable snapshots, worker-side triggers, ticket handoff, local reopen and awaited compactor flushing. Maintenance scheduling, automatic receipt monitoring, live-history reads and stronger durability remain outside this scope.

## Consolidation scheduler verification

- [MemoryConsolidationSchedulerSpec](MemoryConsolidationSchedulerSpec.scala): six cases for a blocked real gate outside agent completion, all returned results after cleanup, post-commit reporting failure, execution-time storage reads, shared-owner coalescing and disabled maintenance.
- [MemoryConsolidationSchedulerLifecycleSpec](MemoryConsolidationSchedulerLifecycleSpec.scala): six cases for owner cancellation after commit, no-pending interval consumption, gate errors without retry, failed/cancelled turns, registration failure and closed/full admission.
- [MemoryLifecycleIsolationSpec](MemoryLifecycleIsolationSpec.scala): eight local scope/order combinations use actual flush/consolidation work, shared gates, rebuilt adapters, invocation/report counts and committed curated state.
- [MemorySchedulerCompositionSpec](MemorySchedulerCompositionSpec.scala): two cases establish opportunistic FIFO replacement order and local consolidation persistence after reporting failure and owner reopen.

The [production contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/memory/README.md) owns source identities and acceptance. Background receipts acknowledge pipeline termination, not exactly-once reporting, unchanged storage after failure, or strict per-turn flush/consolidation dependencies. Remote matrix combinations and session/transcript retention remain unfinished; daily archival and combined policy are verified below.

- [x] `review_fp` and `review_harness` approved the final seven-file consolidation/composition increment against `3e817e3` on 2026-09-29 after correcting nested test-scope ownership. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 346 harness tests, including 25 new cases, with zero compiler warnings. No findings remain within separately scoped observer composition, owned consolidation/result reporting, eight local throttle integration combinations, FIFO replacement and post-commit cancellation/failure. Retention, remote authority, strict cross-job dependencies and transactional reporting are excluded.

## Daily retention verification

- [DailyMemoryRetentionSpec](DailyMemoryRetentionSpec.scala): two fingerprint/codec tests and twelve shared in-memory/local cases for exact acknowledgement, strict cutoff, pending preservation, independent acknowledgement retention, equal-content recreation/stale revision rejection, multiple versions, concurrent archival and empty-pass stability.
- [LocalDailyMemoryRetentionSpec](LocalDailyMemoryRetentionSpec.scala): ten real-filesystem cases for reopen, complete-batch preflight, nonregular paths, reusable published copies, pending content after checkpoint advancement, verified-copy/source removal guards, corruption detection, consolidation recovery before retention and closed ownership.

The UTF-16 surrogate aliasing test failed before strict hashing validation was added. Six existing MemoryJournalSpec cases remain required because consolidation reuses the fingerprint implementation. Intermediate publication tests seed stable states; they do not inject errors midway through a live filesystem operation or establish crash/power-loss durability. The [production contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/memory/README.md) records the seven current identities and accepted scope. Combined retention policy is verified below; session pruning remains unfinished.

- [x] `review_fp` and `review_harness` approved the final seven-file daily-retention increment against `b2ab23e` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 370 harness tests, including 24 new cases, with zero compiler warnings. No findings remain within typed archive identity, exact acknowledged eligibility, atomic in-memory retirement, verified local copy/checkpoint/removal ordering, conservative partial progress and strict shared fingerprinting. Automatic policy, session pruning, crash durability and hostile concurrent filesystem mutation are excluded.

## Combined maintenance verification

- [MemoryRetentionPolicySpec](MemoryRetentionPolicySpec.scala): four cases for daily defaults, strict numeric codecs, leap-day arithmetic, typed underflow, KeepAll and immutable policy replacement.
- [MemoryMaintenanceSpec](MemoryMaintenanceSpec.scala): nine cases for cleanup-before-archive, blank pending preservation, Conflict/EmptyResponse deferral, typed/cleanup failures, cancellation, committed state after archive failure, KeepAll, extreme dates and one zoned cutoff across midnight.
- [MemoryMaintenanceSchedulerSpec](MemoryMaintenanceSchedulerSpec.scala): eight cases for one worker-side gate claim, real agent completion, execution-time cutoff/coalescing, disabled/throttled suppression, failed/interrupting aggregate reports, local archive failure/reopen and the intentionally shared consolidation-only/combined throttle window.

MemoryMaintenanceTask is a private extraction of the existing scheduling boundary; these tests and the unchanged MemoryConsolidationSchedulerSpec/LifecycleSpec, MemoryLifecycleIsolationSpec and MemorySchedulerCompositionSpec jointly verify its behavior. Its extraction does not add another worker or gate. The [production contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/memory/README.md) records current identities and acceptance. Twenty-one new tests do not close the six full upstream configuration scenarios or session retention.

- [x] `review_fp` and `review_harness` approved the final eight-file maintenance increment against `b3d2723` on 2026-09-29, including corrected consolidation-only ScalaDoc. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 391 harness tests, including 21 new cases, with zero compiler warnings. No findings remain within validated calendar policy, repository-bound sequential maintenance, typed aggregate outcomes, one owned gate/job and preserved consolidation-only behavior. Session pruning, unified configuration, cross-phase atomicity and durable reporting are excluded.

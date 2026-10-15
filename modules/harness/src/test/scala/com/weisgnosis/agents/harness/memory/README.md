# Conversation compaction verification

The [shared initialization contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/storage/README.md#scoped-initialization) requires ownership release before failed/interrupted local opening reaches a still-live host Scope; successful resources retain the host lifetime. Tests cover same-host repair/reopen and exact cleanup causes.

Toolkit construction and selection follow the [checked registry identity and migration contract](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#checked-registry-identity).

Compactor/flush groups verify exact nested numeric retained-user provenance through later extraction, pruning head/tail text and extraction argument previews.

- [ConversationCompactorSpec](ConversationCompactorSpec.scala): pair-safe cutoff tests, including same-ID/name local crossing and completed-prefix controls beside unresolved provider calls, plus summary/flush/tail routing, repeated compaction, user provenance, pruning, unchanged below-trigger history, and typed/interrupted hooks.
- [ConversationCompactorIntegrationSpec](ConversationCompactorIntegrationSpec.scala): coverage of canonical history installation, shrinking-history recovery, failure policy, preparation/downstream cancellation, mixed cleanup causes, and separate-agent isolation.
- [TokenCounterSpec](TokenCounterSpec.scala): exact representable estimates and conservative overflow ceiling.

The [method mapping](../../../../../../../../../../docs/architecture/test-parity.md#compaction-method-applicability) accounts for seven upstream compactor and ten middleware methods. It distinguishes tested adaptations, deliberate failure-policy divergences, and the inapplicable cyclic-Throwable mechanism. The concurrent tests coordinate entry and release with Promise; each agent owns a separate history. No timing sleeps or shared-agent multi-session claims are involved.

Existing hardening regressions retain occurrence pairing, inline/local isolation, protected signed arguments and sibling inputs under empty/nonempty Thinking detail arrays, stale argument-cache removal, and retained-user provenance. Summary content is supplied by test effects; these tests do not prove semantic summary fidelity.

Transcript and memory scheduling have paired suites under the [transcript contracts](../transcript/README.md) and memory lifecycle sections below; this does not establish durable host scheduling. Run `sbt "harness / testOnly com.weisgnosis.agents.harness.memory.*"` from the repository root.

## Daily-memory verification

- [MemoryFlushManagerSpec](MemoryFlushManagerSpec.scala): coverage including both named upstream daily-flush methods, context/date binding, midnight rollover, provenance, preview bounds, no-content output, resource cleanup, storage errors, and one-manager serialization.
- [MemoryExtractorSpec](MemoryExtractorSpec.scala): client-adapter checks for inputs/configuration, scoped hub cleanup, thinking-only output, rejected tool observations, interruption, and typed failure cleanup.
- [MemoryFlushManagerIntegrationSpec](MemoryFlushManagerIntegrationSpec.scala): runtime checks for append/offload/summary/dispatch ordering, append failure preserving canonical input, and cancelled extraction with cleanup/recovery.

The store is an injected capability exercised with Ref-backed test implementations. These tests do not establish filesystem persistence, cross-process atomicity, semantic deduplication, or curated writes.

## Filesystem verification

- [LocalDailyMemoryStoreSpec](LocalDailyMemoryStoreSpec.scala): real filesystem checks for missing context, dated UTF-8 appends, independent days, parallel whole entries, competing owners, scope reuse/closure/cancellation, unsafe/nonregular paths, manager integration, and hard-linked curated preservation.
- [DailyMemoryLeaseSpec](DailyMemoryLeaseSpec.scala): deterministic ownership checks for stale tokens, blocked scope closure, queued cancellation, admitted-work completion before cancellation, foreign-token retention, and explicit caller masking.
- [MemoryTestSupport](MemoryTestSupport.scala): scoped temporary directories and directory streams; all filesystem operations are suspended.

Both ordinary and explicitly masked caller cancellation are covered. These tests exercise the local Windows filesystem; cross-process, dynamic symlink/junction, crash, and fsync guarantees are not claimed.

## Consolidation verification

- [MemoryRepositorySpec](MemoryRepositorySpec.scala): checks for daily/curated writer separation, atomic content/progress observations, stale revisions, later pending appends, domain codecs, and compile-time rejection of cross-store revision tokens.
- [MemoryConsolidatorSpec](MemoryConsolidatorSpec.scala): coverage of deterministic prompts, blank/no-input behavior, exact budget boundaries, default estimation, malformed inputs, scope-before-commit, errors/cancellation, concurrent mutation, competing consolidators, per-instance serialization, and shared daily/client-backed integration.

These are process-local atomicity and service-contract tests. Current local adaptations are tracked in the [parity mapping](../../../../../../../../../../docs/architecture/test-parity.md). No timestamp watermark, local fallback, filesystem transaction, or crash guarantee is inferred from the in-memory repository.

## Recovery and persistence verification

- [LocalMemoryRepositorySpec](LocalMemoryRepositorySpec.scala): coverage of exact checkpoint placement, reopen/progress, stale revisions, identical-content generations, all four old/new publication combinations through both entry points, recovery before every operation, mismatch retention, absent/empty distinction, malformed/legacy data, shared ownership, stale leases, and consolidator integration.
- [MemoryJournalSpec](MemoryJournalSpec.scala): strict-decoding and fingerprint checks.
- [MemoryFilesSpec](MemoryFilesSpec.scala): real filesystem checks for enumeration, nonregular targets, and journal-only removal.

The recovery matrix seeds deterministic interrupted-publication states. These are not real process-crash tests or injected I/O failures midway through a live commit. DailyMemoryLeaseSpec verifies admitted-work cancellation and scope ordering; LocalDailyMemoryStoreSpec is the shared backend's daily regression suite. Test evidence does not establish fsync/power-loss durability, cross-process races, or dynamic symlink/junction resistance. The parity mapping records local filesystem adaptations and remote applicability.

DailyMemoryLeaseSpec exercises WorkspaceLease through the memory adapter. MemoryTestSupport supplies shared scoped filesystem fixtures. The [storage contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/storage/README.md#scoped-initialization) describes shared acquisition and cleanup ownership.

## Explicit watermark migration evidence

[LocalMemoryWatermarkMigrationSpec](LocalMemoryWatermarkMigrationSpec.scala) preserves curated/daily content, proves both older and newer inputs remain pending and unarchivable, then runs the actual consolidator and reopens its acknowledged checkpoint. It also checks absent/current byte preservation and strict rejection of unknown/noncanonical formats, rejects every present journal before recovery, preserves all payloads, checks owner exclusion and subsequent admission, and rejects untrusted storage. This does not test mid-publication cancellation, process crashes or remote storage.

## Lifecycle trigger verification

- [MemoryIntervalSpec](MemoryIntervalSpec.scala): exact-duration, invalid-bound and strict numeric-codec checks.
- [MemoryTriggerSpec](MemoryTriggerSpec.scala): zero/positive/negative trigger, immutable defaults, and derived DTO codec checks.
- [MemoryTriggerGateSpec](MemoryTriggerGateSpec.scala): Checks covering first admission at clock origin, exact expiry, anonymous/user/session/owner isolation, independent operations across eight scope/order combinations, shared caller authority, delimiter preservation, 100 simultaneous claims, stale/mixed cleanup, active-window protection, cleanup/renewal races, explicit policy reset, signed wraparound and backward clock movement.

TestClock controls ordinary timing. The wraparound fixture delegates clock services and supplies only nanoTime through Ref, because TestClock's Instant-to-nanosecond conversion overflows at that boundary. No blocking sleeps or process-global test resets are used. The [parity audit](../../../../../../../../../../docs/architecture/test-parity.md#memory-trigger-admission-applicability) records local adaptations and pending remote/scheduling evidence.

## Scoped task queue verification

- [MemoryTaskTicketSpec](MemoryTaskTicketSpec.scala): runtime identity, independent waiter cancellation and rejection-codec checks.
- [MemoryTaskQueueSpec](MemoryTaskQueueSpec.scala): positive-capacity/idle, nonblocking submission, pending-only/FIFO coalescing, running-token isolation, execution-time freshness, bounded replacement, cleanup serialization, failure/self-interruption progression and independent-owner checks.
- [MemoryTaskQueueLifecycleSpec](MemoryTaskQueueLifecycleSpec.scala): shutdown, pending cancellation, cleanup-cause, post-close rejection, concurrent submit/close, immediate-close settlement, masked-acquisition and host-versus-invocation scope checks.

Ticket assertions preserve interrupted/defective task outcomes, including shutdown after masked acquisition. Admission/close races and repeated immediate closure exercise settlement; they are not an exhaustive proof of every scheduler interleaving. These queue-only suites do not install real memory completion hooks; the scheduler suites below provide that integration evidence.

## Completion-triggered flush verification

[MemoryFlushSchedulerSpec](MemoryFlushSchedulerSpec.scala) exercises real ReAct completion, queue admission, manager preparation and extraction: completion before blocked extraction, serial extraction, same-conversation pending coalescing, and independent pending conversations sharing one owner. It also verifies empty/internal no-op before admission, invalid provenance, consumed throttling after extraction failure, immutable snapshot capture across history reset, local-store reopen, and awaited compactor prefix preservation.

[MemoryFlushSchedulerLifecycleSpec](MemoryFlushSchedulerLifecycleSpec.scala) covers blocked/failed worker-side gates, host-retained tickets after invocation scope closure, failed/cancelled-execution exclusion, explicit cooperative-success policy, registration failure without rollback, and closed/capacity observation errors. MemoryFlushManagerSpec exercises shared pure preparation and deferred flush effects.

These suites cover flush integration only; maintenance and consolidation scheduling have separate suites below. They do not establish automatic outcome monitoring.

## Consolidation scheduler verification

- [MemoryConsolidationSchedulerSpec](MemoryConsolidationSchedulerSpec.scala): coverage of a blocked real gate outside agent completion, all returned results after cleanup, post-commit reporting failure, execution-time storage reads, shared-owner coalescing and disabled maintenance.
- [MemoryConsolidationSchedulerLifecycleSpec](MemoryConsolidationSchedulerLifecycleSpec.scala): coverage of owner cancellation after commit, no-pending interval consumption, gate errors without retry, failed/cancelled turns, registration failure and closed/full admission.
- [MemoryLifecycleIsolationSpec](MemoryLifecycleIsolationSpec.scala): local scope/order checks use actual flush/consolidation work, shared gates, rebuilt adapters, invocation/report counts and committed curated state.
- [MemorySchedulerCompositionSpec](MemorySchedulerCompositionSpec.scala): checks establish opportunistic FIFO replacement order and local consolidation persistence after reporting failure and owner reopen.

The [production contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/memory/README.md) describes current behavior. Background receipts acknowledge pipeline termination, not exactly-once reporting, unchanged storage after failure, or strict per-turn flush/consolidation dependencies. Remote throttle coverage is bounded by its linked integration scope; broader maintenance outcomes are tracked in the [tracker](../../../../../../../../../../docs/architecture/tracker.md). Daily archival and combined policy have separate verification below.

## Daily retention verification

- [DailyMemoryRetentionSpec](DailyMemoryRetentionSpec.scala): fingerprint/codec and shared in-memory/local checks for exact acknowledgement, strict cutoff, pending preservation, independent acknowledgement retention, equal-content recreation/stale revision rejection, multiple versions, concurrent archival and empty-pass stability.
- [LocalDailyMemoryRetentionSpec](LocalDailyMemoryRetentionSpec.scala): real-filesystem checks for reopen, complete-batch preflight, nonregular paths, reusable published copies, pending content after checkpoint advancement, verified-copy/source removal guards, corruption detection, consolidation recovery before retention and closed ownership.

UTF-16 surrogate aliasing checks and MemoryJournalSpec cover the shared fingerprint implementation used by consolidation. Intermediate publication tests seed stable states; they do not inject errors midway through a live filesystem operation or establish crash/power-loss durability. Combined retention policy is verified below; transcript retirement has its own package coverage.

## Combined maintenance verification

- [MemoryRetentionPolicySpec](MemoryRetentionPolicySpec.scala): coverage of daily defaults, strict numeric codecs, leap-day arithmetic, typed underflow, KeepAll and immutable policy replacement.
- [MemoryMaintenanceSpec](MemoryMaintenanceSpec.scala): coverage of cleanup-before-archive, blank pending preservation, Conflict/EmptyResponse deferral, typed/cleanup failures, cancellation, committed state after archive failure, KeepAll, extreme dates and one zoned cutoff across midnight.
- [MemoryMaintenanceSchedulerSpec](MemoryMaintenanceSchedulerSpec.scala): coverage of one worker-side gate claim, real agent completion, execution-time cutoff/coalescing, disabled/throttled suppression, failed/interrupting aggregate reports, local archive failure/reopen and the intentionally shared consolidation-only/combined throttle window.

These tests and MemoryConsolidationSchedulerSpec/LifecycleSpec, MemoryLifecycleIsolationSpec and MemorySchedulerCompositionSpec jointly verify the shared scheduling boundary. Full upstream configuration and session-retention applicability are recorded in the parity reference.

## Coordinated maintenance verification

- [MemoryMaintenanceCoordinatorSpec](MemoryMaintenanceCoordinatorSpec.scala): coverage of all four memory outcomes, pre-memory cutoff validation, memory failure/cancellation, captured timing and codecs, and real persisted partial progress when a later target is actively owned.
- [MemoryMaintenanceCoordinatorSchedulerSpec](MemoryMaintenanceCoordinatorSchedulerSpec.scala): coverage of actual agent observers, coalescing/one gate claim, later target failure, aggregate-report failure and owned cleanup.

## Configuration verification

- [MemoryTokenBudgetSpec](MemoryTokenBudgetSpec.scala): coverage of bounds, strict wire input and all 4,000 supported round trips.
- [ConsolidationPromptSpec](ConsolidationPromptSpec.scala): coverage of literal rendering, fixed insertion positions and unchanged default wording.
- [MemoryConfigSpec](MemoryConfigSpec.scala): six named upstream method adaptations covering defaults, overrides, template shape, invalid values, duration rules and independent immutable copies.
- [MemoryConfigIntegrationSpec](MemoryConfigIntegrationSpec.scala): coverage of actual prompt delivery, daily/transcript retention propagation, budget failure without content/acknowledgement changes, independent gate windows and legacy validation.

The [configuration contract and canonical identities](../../../../../../../main/scala/com/weisgnosis/agents/harness/memory/README.md#immutable-memory-configuration) distinguish typed adaptations from Java builder/printf/null compatibility and model selection.

Provider/local ownership follows the [runtime contract](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#provider-owned-pending-calls).

## Versioned repository integration

MemoryRepository/retention/consolidation suites verify shared in-memory transitions. The separate [VersionedMemoryRepositorySpec](../../../../../../../../../storage-postgresql/src/test/scala/com/weisgnosis/agents/storage/postgresql/VersionedMemoryRepositorySpec.scala) covers real PostgreSQL competing append/reopen/namespace isolation; stale consolidation and retention races; and strict persisted schemas, archive fingerprint checks, bounded actual CAS conflicts and ambiguous acknowledgement with one committed append.

## Persistent admission verification

The [versioned gate contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/memory/README.md#versioned-memory-trigger-gate) reuses MemoryScope normalization while preserving in-memory monotonic admission. The optional module's VersionedMemoryTriggerGateSpec covers real-PostgreSQL bounded persistent admission/cleanup, shared authority and conditional-publication failures. Database fixtures require explicit enablement; skipped fixtures do not establish backend verification.

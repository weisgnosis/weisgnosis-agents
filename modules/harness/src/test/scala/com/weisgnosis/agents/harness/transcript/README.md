# Session transcript verification

The registered-writer tables in [SessionTranscriptWriterSpec](SessionTranscriptWriterSpec.scala) exercise both layouts through an actual ReAct completion and a fresh catalog/store reader. They check append-before-registration, explicit index repair after failure without duplicate observations, empty projection and joined cancellation with exact cleanup defects. The Ref-backed backend is integration evidence for composition, not database or remote-provider certification.

The [shared initialization contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/storage/README.md#scoped-initialization) corrects failed/interrupted local opening: ownership is released before failure reaches a still-live host Scope; successful resources retain the host lifetime. The unchanged-production regression failed before correction; same-host repair/reopen and exact cleanup-cause tests now pass. Both independent reviewers approve the eight-Scala source manifest linked there; full verification, artifact/archive admission and standalone consumption pass; review_fp and review_invariants approve the final combined scope against 73b0fb1: eight unchanged Scala identities and twenty-two Markdown files, with no unresolved findings.

Toolkit construction and selection follow the [checked registry identity and migration contract](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#checked-registry-identity). The linked source/spec manifest owns the current migration review scope and verification; existing lifecycle and permission contracts remain in force.

- [TranscriptEntrySpec](TranscriptEntrySpec.scala): two codec/structural-key cases.
- [TranscriptStoreSpec](TranscriptStoreSpec.scala): seven cases for duplicate gaps, first-observation retention, anonymous replay, store isolation, lazy recording time, deterministic UUID-collision batch rollback, concurrent overlapping batches, and empty input.
- [SessionTranscriptWriterSpec](SessionTranscriptWriterSpec.scala): thirteen cases for overlapping histories, structured tool payloads, image/empty placeholders, absent/blank IDs, summaries, thinking/context filtering, repeated tools/delimiters, exact 500/501 and 1,000/1,001 preview boundaries, non-text output, compactor ordering, typed errors, and cancellation cleanup.

Tests use Ref, Promise, scoped fibers, TestClock and deterministic TestRandom seeds. Those 22 process-local tests involve no timing sleeps or filesystem claims. The three offload-delegate behaviors and three writer behaviors have local observation evidence; current local persistence adaptations are described below; the selected immutable publication adaptation is verified separately below; its final documentation approval passed.

- [x] `review_fp` and `review_harness` approved the final six-file transcript increment against `73458da` on 2026-09-29. No findings remain within pure observation projection, atomic in-memory batch deduplication/linkage, first-observation retention, collision rollback, and compactor callback integration. Full `sbt scalafmtAll scalafmtSbt verify` passed 530 core / 231 harness tests, including 22 new cases, with zero compiler warnings. Filesystem/remote persistence, automatic turn-boundary recording, strict persisted-input validation, and Java wire compatibility remain outside this scope.

Exact identities and limits are in the [source contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/transcript/README.md).

## Local persistence and strict restoration

- [LocalTranscriptStoreSpec](LocalTranscriptStoreSpec.scala): 12 real filesystem cases for missing logs, four-row overlap/reopen, anonymous/summarized content, unchanged duplicate batches, separate transcript ownership, escaped handles, parallel batches, corruption retention, collision rollback, unsafe paths, stale/nonregular targets, and exact Unicode input/output preview persistence.
- [TranscriptFilesSpec](TranscriptFilesSpec.scala): two cases for hard-link preservation and scoped temporary cleanup after rejected nonregular replacement.
- [TranscriptLogSpec](TranscriptLogSpec.scala): six framing/codec cases, including empty files, escaped newlines, Unicode, LF/CRLF, missing final newline, strict versions/fields/numbers, explicit-null rejection, and complete-chain validation.
- [TranscriptStateSpec](TranscriptStateSpec.scala): four shared-validation cases for malformed drafts, key/payload compatibility, duplicate-key complete JSON, preview length claims, duplicate IDs/keys, broken parents, and nonmonotonic recording times.

WorkspaceLeaseSpec adds three ownership-mode tests in the [storage test package](../storage/README.md). Existing DailyMemoryLeaseSpec supplies six cancellation/finalizer regressions through the compatibility adapter. The real Unicode test reproduced an encoding failure before both preview paths were corrected; exact intact prefixes/original lengths and reopened equality now prevent silent replacement from passing.

The named summary projection test establishes compaction-summary identity and exact stable keys; the local reopen fixture establishes persisted text/deduplication. Together they support the explicit upstream adaptation. These tests do not establish Java storage layout/wire equivalence, remote segments, power-loss durability, cross-process behavior, or hostile mutation resistance.

- [x] `review_fp` and `review_harness` approved the final 14-file local transcript increment against `e4dfc67` on 2026-09-29. Twenty-seven new tests reproduced and fixed Unicode surrogate splitting; full `sbt scalafmtAll scalafmtSbt verify` passed 530 core / 258 harness tests with zero compiler warnings. No findings remain within shared cooperative ownership, strict complete-log validation, atomic batch replacement, reopen/deduplication, and Unicode-safe previews. Remote segments, Java layout/wire equivalence, hostile mutation, fsync/power-loss durability, and automatic turn scheduling remain excluded.

Existing numeric regression rows verify exact minimum-Long/scaled nested inputs across store closure/reopening and independently authored preview text/length. TranscriptLogSpec now checks the typed encoding result; empty-log and strict framing behavior remain unchanged. The [numeric-integrity acceptance](../../../../../../../main/scala/com/weisgnosis/agents/harness/transcript/README.md#exact-owned-numeric-output) owns the current six transcript identities, paired structured correction, reproduced failures and full verification. No new groups or Java method mappings were added.

## Runtime completion integration

[TranscriptCompletionSpec](TranscriptCompletionSpec.scala) adds six cases: stable-key recording over two runs and filesystem reopen, repeated anonymous snapshots, strict-policy rejection before append without identity mutation, reconciled tool cancellation persisted before scope close, compactor offload preserving removed prefixes, and policy codecs/storage-error mapping. Filesystem assertions use scoped temporary directories. These are completion-boundary hardening cases; the pinned upstream has no TranscriptMiddlewareTest counterpart.

- [x] `review_fp` and `review_harness` approved the final eight-file completion-observer increment against `0349b1a` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 552 core / 264 harness tests, including 28 new cases, with zero compiler warnings. No findings remain within scoped once-per-invocation observation, canonical snapshots, cause preservation, explicit transcript identity policy, and local persistence integration. Background memory scheduling, remote persistence, delivery acknowledgement, and exhaustive upstream middleware parity remain outside this scope.

## Explicit pruning verification

[LocalTranscriptRetentionSpec](LocalTranscriptRetentionSpec.scala) adds thirteen real-filesystem cases: missing/empty distinctions, cutoff equality and codecs, recent metadata, recent entries, nonmonotonic/future recording times, corrupt/broken-chain preservation, active and stale leases, unrelated-file isolation, fresh identity after reopening, nonregular/unsafe/missing directories and competing pruners. The tests operate only on scoped temporary directories.

Cancellation-after-admission and scope-release mechanics remain inherited from the unchanged WorkspaceLease and its existing tests; no deterministic mid-delete interruption or process-crash injection is claimed here. Pruning is explicit and fixed-target. The explicit 180-day policy is verified below; bounded local discovery is covered below; automatic maintenance scheduling and remote behavior remain pending. Current source identities and limits are in the [transcript contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/transcript/README.md).

- [x] `review_fp` and `review_harness` approved the final three-file transcript-pruning increment against `9ddee3a` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 404 harness tests, including thirteen new cases, with zero compiler warnings. No findings remain within explicit local targets, shared writer ownership, strict log validation, observation-aware activity, fixed-payload deletion and generation retirement. Session discovery, automatic expiry, remote pruning and crash/hostile-mutation guarantees are excluded.

## Transcript policy verification

- [TranscriptRetentionPolicySpec](TranscriptRetentionPolicySpec.scala): four cases for 180-day defaults/strict validated codecs, fixed-day subtraction with nanoseconds, range boundaries, and distinct disabled/evaluated result codecs.
- [LocalTranscriptRetentionPolicySpec](LocalTranscriptRetentionPolicySpec.scala): four cases for a failing-clock/untrusted-target KeepAll bypass, one sampled cutoff before pruning, typed underflow before ownership, and missing/equality outcomes.

The existing MemoryRetentionPolicySpec remains required for the shared positive day type and unchanged daily calendar semantics. Explicit policy support does not itself install discovery or background scheduling. The [transcript contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/transcript/README.md) records the five-file scope and remaining integration work.

- [x] `review_fp` and `review_harness` approved the final five-file transcript-policy increment against `f1a005e` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 412 harness tests, including eight new cases, with zero compiler warnings. No findings remain within explicit 180-day policy, strict shared day validation, safe Instant arithmetic, one pre-ownership clock sample and complete KeepAll bypass. Batch/scheduler integration and complete upstream configuration parity are excluded.

## Batch verification

- [TranscriptRetentionTargetSpec](TranscriptRetentionTargetSpec.scala): three cases for exact ID codecs, lazy replay and deferred local ownership.
- [TranscriptRetentionBatchSpec](TranscriptRetentionBatchSpec.scala): explicit and discovered selection contracts: duplicate rejection, KeepAll bypass, fresh prepared snapshots, one cutoff/order/codecs, complete failure causes, joined selection cancellation, replay and enabled empty batches.

See the [current batch contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/transcript/README.md#ordered-explicit-retention-batches) and its linked canonical identity table. Bounded filesystem discovery is covered below; no automatic inactivity proof is claimed.

## Bounded local discovery evidence

[LocalTranscriptDiscoverySpec](LocalTranscriptDiscoverySpec.scala) has three grouped contracts: cold sorted selection across maintenance runs with old/recent activity and retained directories; limits, trust, traversal, sensitive entries and nonregular payload rejection before memory effects; and later writer/body failures preserving earlier pruning while skipping subsequent targets. The batch suite adds a fresh-snapshot/duplicate-selection/joined-cancellation group and extends KeepAll with a failing discovery effect and clock. The cancellation fixture asserts both interruption and the exact cleanup defect before any pruning.

These are controlled local filesystem and scoped-effect tests, not symlink/junction probes, hostile mutation, mid-syscall cancellation or remote-storage tests. The [current discovery contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/transcript/README.md#bounded-local-transcript-discovery) owns exact identities and acceptance. Upstream pruneOldSessions provides source-level intent; no new named Java test mapping is claimed.

## Versioned transcript verification

[VersionedTranscriptStoreSpec](../../../../../../../../../storage-postgresql/src/test/scala/com/weisgnosis/agents/storage/postgresql/VersionedTranscriptStoreSpec.scala) exercises the actual PostgreSQL backend through independent adapters and shared PostgresFixture resources. Its selected contracts cover stable-key/anonymous batch publication, persistent parent linkage, fixed candidate identity/time across definite conflicts, bounded attempts, strict malformed aggregate rejection and ambiguous acknowledgements. These tests do not establish remote immutable-segment, discovery or pruning parity. Final source/documentation approvals, full verification and packaged-consumer checks pass; current identities and bounded acceptance belong to the [remote-transcript contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/transcript/README.md#versioned-remote-transcript).

The [checkpoint/shared-helper scope](../../../../../../../main/scala/com/weisgnosis/agents/harness/storage/README.md#versioned-acting-checkpoints) extracts canonical comparison and separately invoked Unicode checks without changing general duplicate validation or transcript behavior. Final source approvals and focused/full verification pass; fresh artifact consumption passes; final documentation approval passed in the linked manifest. Real database claims rely on the separate PostgreSQL fixture.

The [segmented adapter](../../../../../../../main/scala/com/weisgnosis/agents/harness/transcript/README.md#immutable-transcript-segments) has separate real-PostgreSQL verification through SegmentedTranscriptStoreSpec. Three grouped contracts cover concurrent immutable publication/reopen, conditional conflicts and uncertain acknowledgements, and strict chain/capacity failures. Existing aggregate tests remain unchanged. Final source/full/artifact gates and combined documentation approval passed; this does not establish Java mirror, glob or session-index equivalence.

The [explicit catalog contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/transcript/README.md#explicit-transcript-session-catalog) has a separate three-group actual PostgreSQL suite for registration/discovery at a known address, collision/capacity checks and uncertain publication. Shared segment behavior is unchanged; source/full/artifact verification and combined documentation approval passed.

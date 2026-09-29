# Session transcript verification

- [TranscriptEntrySpec](TranscriptEntrySpec.scala): two codec/structural-key cases.
- [TranscriptStoreSpec](TranscriptStoreSpec.scala): seven cases for duplicate gaps, first-observation retention, anonymous replay, store isolation, lazy recording time, deterministic UUID-collision batch rollback, concurrent overlapping batches, and empty input.
- [SessionTranscriptWriterSpec](SessionTranscriptWriterSpec.scala): thirteen cases for overlapping histories, structured tool payloads, image/empty placeholders, absent/blank IDs, summaries, thinking/context filtering, repeated tools/delimiters, exact 500/501 and 1,000/1,001 preview boundaries, non-text output, compactor ordering, typed errors, and cancellation cleanup.

Tests use Ref, Promise, scoped fibers, TestClock and deterministic TestRandom seeds. Those 22 process-local tests involve no timing sleeps or filesystem claims. The three offload-delegate behaviors and three writer behaviors have local observation evidence; current local persistence adaptations are described below; the immutable segment method remains pending.

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

## Runtime completion integration

[TranscriptCompletionSpec](TranscriptCompletionSpec.scala) adds six cases: stable-key recording over two runs and filesystem reopen, repeated anonymous snapshots, strict-policy rejection before append without identity mutation, reconciled tool cancellation persisted before scope close, compactor offload preserving removed prefixes, and policy codecs/storage-error mapping. Filesystem assertions use scoped temporary directories. These are completion-boundary hardening cases; the pinned upstream has no TranscriptMiddlewareTest counterpart.

- [x] `review_fp` and `review_harness` approved the final eight-file completion-observer increment against `0349b1a` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 552 core / 264 harness tests, including 28 new cases, with zero compiler warnings. No findings remain within scoped once-per-invocation observation, canonical snapshots, cause preservation, explicit transcript identity policy, and local persistence integration. Background memory scheduling, remote persistence, delivery acknowledgement, and exhaustive upstream middleware parity remain outside this scope.

## Explicit pruning verification

[LocalTranscriptRetentionSpec](LocalTranscriptRetentionSpec.scala) adds thirteen real-filesystem cases: missing/empty distinctions, cutoff equality and codecs, recent metadata, recent entries, nonmonotonic/future recording times, corrupt/broken-chain preservation, active and stale leases, unrelated-file isolation, fresh identity after reopening, nonregular/unsafe/missing directories and competing pruners. The tests operate only on scoped temporary directories.

Cancellation-after-admission and scope-release mechanics remain inherited from the unchanged WorkspaceLease and its existing tests; no deterministic mid-delete interruption or process-crash injection is claimed here. Pruning is explicit and fixed-target. Session enumeration, the 180-day policy, automatic maintenance scheduling and remote behavior remain pending. Current source identities and limits are in the [transcript contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/transcript/README.md).

- [x] `review_fp` and `review_harness` approved the final three-file transcript-pruning increment against `9ddee3a` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 404 harness tests, including thirteen new cases, with zero compiler warnings. No findings remain within explicit local targets, shared writer ownership, strict log validation, observation-aware activity, fixed-payload deletion and generation retirement. Session discovery, automatic expiry, remote pruning and crash/hostile-mutation guarantees are excluded.

# Session transcript observations

[SessionTranscriptWriter](SessionTranscriptWriter.scala) records typed observations through a transcript-bound [TranscriptStore](TranscriptStore.scala). appendMessages returns only newly committed entries; offload discards that result for installation as ConversationCompactor's offload callback. Transcript recording is independent of memory extraction: summaries and thinking remain eligible, while user messages containing `<session_context>` are skipped. System messages remain eligible. Installing the compactor callback alone does not record every turn; install the completion observer described below as well.

## Projection and data contract

Projection combines nonblank text/thinking in block order, then emits tool uses and tool results in per-kind order. Media-only and empty messages receive observable placeholders. Tool input JSON is complete through 500 UTF-16 characters; larger input carries a typed truncated preview and original character count. Preview boundaries never split a surrogate pair, so a prefix may be one UTF-16 unit shorter than the limit. Joined tool output follows the same rule at 1,000 characters. Result state and output block types are retained. Complete and truncated variants are distinct ADT cases; no truncated JSON is represented as complete arguments.

[TranscriptEntry](TranscriptEntry.scala) contains a storage-generated UUID, optional parent UUID, recordedAt, optional structural key, and payload. Keys distinguish message/use/result and per-kind occurrence; arbitrary message ID delimiters and repeated call IDs cannot collide structurally. IDs absent or blank in Msg produce anonymous drafts and append on every invocation. Stable keys retain the first observation even if later content changes through eviction or compaction. Use a separate store capability per transcript/session; sharing a capability intentionally shares its deduplication domain.

These are lossy observations, not restorable canonical Msg values. Metadata, media bytes, and reasoning signatures are not copied. Compared with upstream, payloads retain block types for visible messages and preserve tool-result state. Joined output uses canonical ToolResult.outputText, including separators around empty text blocks; upstream omits some leading empty separators. UUID entry identities and structural keys deliberately replace Java's concatenated IDs; derived JSON codecs are Scala DTO codecs, not Java SessionEntry wire adapters or strict persisted-state validators.

## Atomic store authority

TranscriptStore.inMemory allocates recording time and candidate UUIDs in ZIO before a pure Ref.modify transition. Within that transition, each accepted row updates the stable-key set, selects the latest accepted parent, and appends. Duplicate rows neither append nor change the parent. A UUID collision rejects the entire proposed batch and preserves previous state. Independent concurrent writers on one store therefore publish whole batches into one linked sequence. Empty and all-duplicate batches return no committed entries.

recordedAt is sampled before candidate allocation and commit; it is an observation timestamp, not a monotonic ordering authority. Follow the stored sequence and parent links. Cancellation/failure after commit may have an uncertain acknowledgement; do not infer rollback from an interrupted caller. Anonymous retries are not idempotent. TranscriptStore.inMemory is process-local: no files, segments, indexes, remote mirrors, crash durability, or automatic runtime scheduling are provided. LocalTranscriptStore, described below, validates persisted input and preserves the same atomic deduplication/linkage contract.

## Verification and acceptance

- [x] `review_fp` and `review_harness` approved the final six-file transcript increment against `73458da` on 2026-09-29. No findings remain within pure observation projection, atomic in-memory batch deduplication/linkage, first-observation retention, collision rollback, and compactor callback integration. Full `sbt scalafmtAll scalafmtSbt verify` passed 530 core / 231 harness tests, including 22 new cases, with zero compiler warnings. Filesystem/remote persistence, automatic turn-boundary recording, strict persisted-input validation, and Java wire compatibility remain outside this scope.
- [x] `review_docs` approved the corrected documentation against `73458da`, including all six identities, 22 tests, wire/timestamp limits, and explicit pending persistence mappings. All 61 Markdown files passed link/script checks; git diff --check passed.
- [x] Implement scoped local transcript persistence and four explicit local method adaptations, with storage-layout/wire differences documented below.
- [ ] Implement immutable segment storage and session indexes.

[Tests](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/README.md) and [parity mapping](../../../../../../../../../../docs/architecture/test-parity.md#transcript-observation-applicability) distinguish behavioral coverage from pending filesystem assertions. [Tracker](../../../../../../../../../../docs/architecture/tracker.md).

Current Git-normalized identities:

| Source | Blob identity |
| --- | --- |
| [TranscriptEntry.scala](TranscriptEntry.scala) | ce02c2fa3a1cb38c6153e9745474fe21ab7ecc5a |
| [SessionTranscriptWriterSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/SessionTranscriptWriterSpec.scala) | e7ee7663adae6b2382bf07acc68335b533662b5d |
| [TranscriptEntrySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/TranscriptEntrySpec.scala) | 4bbae7448ce93a6cc3763e1b5dd44acf74eecb05 |
| [TranscriptStoreSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/TranscriptStoreSpec.scala) | 9e582e4d6b8e76ddbfb943b24424dd86b4f4ad4a |

## Scoped local transcript store

[LocalTranscriptStore.open](LocalTranscriptStore.scala) binds one existing directory to one transcript. WorkspaceTrust.HostControlled is required before filesystem access; the host protects the directory and every ancestor. Traversal, sensitive locations, filesystem roots, non-directory storage, and nonregular/symlink payloads are rejected. The runtime derives a fixed transcript.jsonl destination and .transcript-writer.lease ownership file. There is no tenant/session path interpolation or fallback storage choice; the host binds separate directories to separate transcript identities.

The internal [WorkspaceLease](../storage/WorkspaceLease.scala) shares the tested daily-memory ownership state machine. Waiting can be interrupted; admitted filesystem work finishes before ordinary pending cancellation is delivered. Scope closure waits for admitted work, closes the handle, verifies the ownership token, then removes the lease. Explicit caller masking is preserved. Competing owners never overwrite or remove a lease; stale ownership requires operator confirmation that every previous owner has stopped before manual removal. Slow storage can delay interruption and scope closure. Cleanup-token failures are observable finalizer defects.

Every read and append validates the canonical directory, reads all current rows after admission, strictly decodes them, and restores the complete identity/key/parent chain. An append uses the same [TranscriptState](TranscriptState.scala) validation and pure batch transition as the in-memory backend. Invalid stable keys, mismatched key/payload kinds, invalid preview length claims, ambiguous complete input JSON, duplicate persisted IDs/keys, and broken parents fail without publishing a candidate. Recording timestamps need not increase. Empty and all-duplicate batches preserve the payload file. The backend rewrites the complete log through a scoped temporary file and ATOMIC_MOVE, with no incremental-write or non-atomic fallback; work and memory scale with the complete log size per append.

[TranscriptLog](TranscriptLog.scala) stores one private version-1 envelope per physical JSONL row. Empty files are valid; the final newline is optional and LF/CRLF are supported. Interior blank rows, malformed/truncated rows, duplicate JSON keys, trailing documents, unknown fields, unsupported versions, and decoder coercions fail closed. Canonical DTO shape validation allows object-field ordering and numeric formatting differences, but optional values must use omitted fields rather than explicit JSON null. The complete log is validated before replacement. Decoding never skips a bad row or resets corrupt input to empty state. Versioned disk envelopes are separate from public derived DTO codecs.

Atomic replacement protects cooperative readers from partial file contents and preserves hard-linked source files by replacing the directory entry. It does not provide fsync/power-loss durability, exactly-once anonymous replay, hostile mutation protection, or an identity guarantee against uncoordinated directory replacement. Cancellation may be delivered after publication. Failed temporary cleanup is logged; abandoned temporary files can remain after process termination. Cross-process contention and dynamic symlink/junction behavior need platform-specific validation.

Migration: shared lease extraction preserves memory/.daily-writer.lease and existing DailyMemoryLease entry points. The local transcript backend deliberately uses one host-selected directory and one versioned transcript.jsonl; it does not reproduce Java `agents/<agent>/sessions/<session>.jsonl/.log.jsonl` layout, mirror dual files, or wire IDs. The four local method mappings are behavioral/reopen adaptations with those differences; opt-in background memory flush is implemented in the memory package; owned consolidation scheduling is also available; explicit local transcript pruning is implemented below; immutable remote segments, indexes and automatic transcript retention remain pending. Previous changed transcript, lease, and test-helper identities are preserved in e4dfc67.

- [x] `review_fp` and `review_harness` approved the final 14-file local transcript increment against `e4dfc67` on 2026-09-29. Twenty-seven new tests reproduced and fixed Unicode surrogate splitting; full `sbt scalafmtAll scalafmtSbt verify` passed 530 core / 258 harness tests with zero compiler warnings. No findings remain within shared cooperative ownership, strict complete-log validation, atomic batch replacement, reopen/deduplication, and Unicode-safe previews. Remote segments, Java layout/wire equivalence, hostile mutation, fsync/power-loss durability, and automatic turn scheduling remain excluded.
- [x] `review_docs` approved the corrected local-transcript documentation against `e4dfc67`, including 14 identities, 27 new tests, strict storage/Unicode contracts, four local adaptations, and explicit remaining work. All 63 Markdown files passed link/script checks; git diff --check passed.
- [x] Integrate required terminal snapshot recording through the completion observer described below.
- [x] Integrate opt-in background flush and combined daily-memory maintenance in the memory package.
- [x] Add explicit transcript-retention policy below.
- [ ] Integrate session enumeration and owned batch pruning.
- [ ] Implement remote immutable segments/indexes and platform-specific ownership validation.

Unchanged Git-normalized local persistence identities; the changed TranscriptFiles identity is preserved in 9ddee3a and indexed in the pruning table below:

| Source | Blob identity |
| --- | --- |
| [DailyMemoryLease.scala](../memory/DailyMemoryLease.scala) | 7c1cddd14656f11e33228ecef785e75a9f60b4a4 |
| [WorkspaceLease.scala](../storage/WorkspaceLease.scala) | 0e254e633efc59e5be52959e6a6c683f14f1fc34 |
| [LocalTranscriptStore.scala](LocalTranscriptStore.scala) | 3352dc8a5b68bb455d9f484584ca86a8d675f6eb |
| [TranscriptLog.scala](TranscriptLog.scala) | 5566ad5cd64653e2b0f23c8cdc93b7ba8f28e398 |
| [TranscriptState.scala](TranscriptState.scala) | e202ba895f83df0990d2c78e2e69118fae0cf131 |
| [TranscriptStore.scala](TranscriptStore.scala) | d5978648ebdd370635c91bd7bef45d270796b764 |
| [MemoryTestSupport.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryTestSupport.scala) | d891b7e44834d27897845c544c9050114adb2630 |
| [WorkspaceLeaseSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/storage/WorkspaceLeaseSpec.scala) | b148510a580fd041c7f4211baa8deee5949db001 |
| [LocalTranscriptStoreSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/LocalTranscriptStoreSpec.scala) | 04159733e59d6a00f82c940428cb0e58f1745dd8 |
| [TranscriptFilesSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/TranscriptFilesSpec.scala) | fd5db63a28bc71b56bd645e1fd7db32c72301db4 |
| [TranscriptLogSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/TranscriptLogSpec.scala) | 1ddd6fb146a2819f3b9afa217936293597d72b79 |
| [TranscriptStateSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/TranscriptStateSpec.scala) | f3be77abf723e891e8a103d8482120fe40f0dd8f |

## Terminal canonical snapshot recording

Install writer.completionObserver(policy) through ReActAgent.make(completionObserver = ...) or Builder.completionObserver. The policy is mandatory: RequireStableIds rejects a snapshot containing any eligible anonymous draft before append; RecordEachSnapshot appends anonymous observations on every terminal snapshot. Stable keys still deduplicate across turns. Skipped internal session-context messages do not require IDs. The adapter never assigns or changes canonical message identities. Runtime-created system, tool, summary, and recovery messages can be anonymous, so strict mode requires an identity strategy covering every eligible message; RecordEachSnapshot explicitly accepts repeated retained anonymous history.

The observer records success, final failure, timeout, and reconciled cancellation snapshots once per admitted invocation. It maps storage failures to CompletionObservationError and does not swallow them or retry after uncertain publication. Keep the store scope alive around every agent invocation. The standalone offload callback retains its existing anonymous append behavior and does not enforce the completion adapter policy. Install writer.offload on the compactor as well when removed prefixes must remain in the transcript; a terminal snapshot alone cannot recover content already removed by compaction.

Required observation completes before successful result publication and before cancellation returns. Late cancellation may still coexist with an AgentResultEvent representing committed execution. Direct calls and drained streams expose terminal observer errors; early consumers abandon that error queue and cannot treat their own completion as persistence acknowledgement. Observers must not await operations requiring the same held agent run lock. See the [core completion contract](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md) for cause, scope, and invocation boundaries.

- [x] `review_fp` and `review_harness` approved the final eight-file completion-observer increment against `0349b1a` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 552 core / 264 harness tests, including 28 new cases, with zero compiler warnings. No findings remain within scoped once-per-invocation observation, canonical snapshots, cause preservation, explicit transcript identity policy, and local persistence integration. Background memory scheduling, remote persistence, delivery acknowledgement, and exhaustive upstream middleware parity remain outside this scope.

- [x] Integrate scoped background flush and combined daily-memory maintenance; choose the appropriate owner scheduler.
- [x] Add explicit transcript-retention policy below.
- [ ] Integrate session enumeration and owned batch pruning.
- [ ] Implement remote immutable segments and session indexes.

The previous SessionTranscriptWriter.scala identity is preserved in `0349b1a`; its current identity and acceptance are in the completion-observer table in the [core agent contract](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md).

- [x] `review_docs` approved completion-observer documentation against `0349b1a`, including all eight identities, 28 new cases, late cancellation, early-stream error visibility, explicit anonymous policy, compactor offload and remaining lifecycle work. Full verification passed 552 core / 264 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed.

## Explicit local transcript pruning

[LocalTranscriptRetention.prune](LocalTranscriptRetention.scala) takes one existing host-selected directory, explicit WorkspaceTrust and an exclusive Instant cutoff. It acquires the same WorkspaceOwner.Transcript lease as LocalTranscriptStore, inside its own Scope, and protects the entire inspection/deletion operation. Active or stale leases cause typed storage failure and are never reclaimed. This observes storage ownership, not application-level user presence; hosts must keep writer ownership alive while the corresponding session is active.

Only the fixed transcript.jsonl payload is eligible. Missing payload yields TranscriptPruneResult.Missing; an existing empty file is a valid zero-entry transcript. A missing directory is an error. The directory and payload must pass existing canonical/sensitive/nonregular checks, and the complete log must pass strict version/shape/key/parent-chain validation before deletion is considered. Corrupt logs are preserved for diagnosis, including logs that appear expired.

Activity is the maximum of the file modification time and every persisted recordedAt timestamp. Recording clocks may move backwards, so the final row alone is insufficient. Strictly earlier activity is pruned; equality, recent metadata, recent observations and future timestamps retain the file. Retained reports the activity timestamp; Pruned reports that timestamp and the removed entry count. File metadata times retain the underlying filesystem's precision.

Deletion removes only that validated payload entry. It does not recurse, enumerate sibling sessions, remove directories, touch unrelated files, or remove another owner's lease. The scoped finalizer releases this operation's lease. Admitted work retains WorkspaceLease's cancellation rule: deletion may finish before pending cancellation is delivered, so interruption/failure is not proof of unchanged storage. Slow filesystem work can delay cancellation; no rollback, undelete or power-loss durability is supplied.

Successful pruning retires the persisted deduplication keys and parent chain. A later open starts an empty transcript and accepts formerly used stable keys as new observations. The host must retire the corresponding logical session rather than assume identity continuity or a retained tombstone. This is an explicit destructive storage capability for a host-selected inactive session, not automatic application session discovery or authentication.

The backend still requires a host-controlled directory and ancestors. Cooperative leases and canonical checks do not protect against hostile external path replacement. This is a hardened source-level adaptation of upstream pruneOldSessions: explicit target selection, strict validation, active-owner refusal and observation-aware recency replace a broad filename scan, mtime-only decision and swallowed failures. No new named upstream pruning test is claimed. The 180-day policy is implemented below; session enumeration, maintenance scheduling and remote pruning remain unfinished. Prior TranscriptFiles.scala is preserved in 9ddee3a.

## Current pruning identities

Unchanged identities from the three-file pruning increment against `9ddee3a`; LocalTranscriptRetention changed for policy support and its previous identity is preserved in f1a005e. Independent acceptance of the earlier scope is recorded below.

| Source | Git blob |
| --- | --- |
| [TranscriptFiles.scala](TranscriptFiles.scala) | 603cd66973f453f7bedeb4d06f21eca175538cc9 |
| [LocalTranscriptRetentionSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/LocalTranscriptRetentionSpec.scala) | 2065dc1bedcabfa2710969db9bb7b641292ae097 |

- [x] `review_fp` and `review_harness` approved the final three-file transcript-pruning increment against `9ddee3a` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 404 harness tests, including thirteen new cases, with zero compiler warnings. No findings remain within explicit local targets, shared writer ownership, strict log validation, observation-aware activity, fixed-payload deletion and generation retirement. Session discovery, automatic expiry, remote pruning and crash/hostile-mutation guarantees are excluded.

- [x] `review_docs` approved transcript-pruning documentation against `9ddee3a`: three identities, thirteen tests, activity/cutoff rules, shared ownership, cancellation limits and generation retirement. Full verification passed 555 core / 404 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed. Automatic policy/discovery, remote pruning and crash guarantees remain excluded.

## Explicit transcript retention policy

[TranscriptRetentionPolicy](TranscriptRetentionPolicy.scala) is KeepAll or PruneAfter(RetentionDays). Its explicit default is 180 days; the validated positive day type is shared with daily memory policy, with separately named DailyDefault and SessionDefault values. Transcript days mean exact 24-hour intervals, not zoned calendar subtraction. Cutoff arithmetic preserves the input nanoseconds, accepts the full validated positive Int range and returns typed InvalidConfiguration on Instant underflow.

LocalTranscriptRetention.pruneWithPolicy is additive; the original explicit-cutoff prune API keeps its behavior. KeepAll returns TranscriptRetentionResult.Disabled without reading the clock or validating/accessing the directory, trust or payload. Enabled policy samples Clock.instant once, validates its cutoff before acquiring ownership, then delegates to the same scoped explicit-target pruner. Evaluated(cutoff, result) preserves the cutoff and Missing/Retained/Pruned outcome; Disabled is not confused with a missing file or a retained payload.

Calling this policy API explicitly selects the target and enables the chosen operation. It does not enumerate directories, install a timer, create a session catalog or claim a separate maintenance gate. Existing strict decoding, activity calculation, ownership refusal, generation retirement and uncertain cancellation acknowledgement remain unchanged. The default is not silently applied to the older prune API. Owned multi-target coordination remains the next integration step.

Previous LocalTranscriptRetention.scala and MemoryRetentionPolicy.scala identities are preserved in f1a005e. Existing callers remain source-compatible. The new default/validation assertions add evidence to the partial MemoryConfig mapping; a complete unified configuration or upstream builder port is not claimed.

## Current transcript-policy identities

Final formatted five-file increment against `f1a005e`; independent acceptance is recorded below.

| Source | Git blob |
| --- | --- |
| [MemoryRetentionPolicy.scala](../memory/MemoryRetentionPolicy.scala) | ab432f467f13a90d931371fd40aba929e1551117 |
| [LocalTranscriptRetention.scala](LocalTranscriptRetention.scala) | 6bdbf68975968b22a13441ab473782fc8eb32fb5 |
| [TranscriptRetentionPolicy.scala](TranscriptRetentionPolicy.scala) | 7174ebf8629c722090ed4dfbfb203341ec75a53d |
| [LocalTranscriptRetentionPolicySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/LocalTranscriptRetentionPolicySpec.scala) | 8010eb1e6985b80790f475225af9e3930479a31f |
| [TranscriptRetentionPolicySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/TranscriptRetentionPolicySpec.scala) | ccf0ccbc082334dbb87f94b9b6a563a152b1f73e |

- [x] `review_fp` and `review_harness` approved the final five-file transcript-policy increment against `f1a005e` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 412 harness tests, including eight new cases, with zero compiler warnings. No findings remain within explicit 180-day policy, strict shared day validation, safe Instant arithmetic, one pre-ownership clock sample and complete KeepAll bypass. Batch/scheduler integration and complete upstream configuration parity are excluded.

- [x] `review_docs` approved transcript-policy documentation against `f1a005e`: five identities, eight tests, fixed-day arithmetic, KeepAll bypass, additive API behavior and partial configuration mappings. Full verification passed 555 core / 412 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed. Batch, discovery, scheduling and remote integration remain excluded.

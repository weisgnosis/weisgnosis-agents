# Session transcript observations

[SessionTranscriptWriter](SessionTranscriptWriter.scala) records typed observations through a transcript-bound [TranscriptStore](TranscriptStore.scala). appendMessages returns only newly committed entries; offload discards that result for installation as ConversationCompactor's offload callback. Transcript recording is independent of memory extraction: summaries and thinking remain eligible, while user messages containing `<session_context>` are skipped. System messages remain eligible. Installing the compactor callback alone does not record every turn; install the completion observer described below as well.

## Projection and data contract

Projection combines nonblank text/thinking in block order, then emits tool uses and tool results in per-kind order. Media-only and empty messages receive observable placeholders. Tool input JSON uses exact decimal rendering and is complete through 500 UTF-16 characters; larger input carries a typed truncated preview and original character count. Preview boundaries never split a surrogate pair, so a prefix may be one UTF-16 unit shorter than the limit. Joined tool output follows the same rule at 1,000 characters. Result state and output block types are retained. Complete and truncated variants are distinct ADT cases; no truncated JSON is represented as complete arguments.

[TranscriptEntry](TranscriptEntry.scala) contains a storage-generated UUID, optional parent UUID, recordedAt, optional structural key, and payload. Keys distinguish message/use/result and per-kind occurrence; arbitrary message ID delimiters and repeated call IDs cannot collide structurally. IDs absent or blank in Msg produce anonymous drafts and append on every invocation. Stable keys retain the first observation even if later content changes through eviction or compaction. Use a separate store capability per transcript/session; sharing a capability intentionally shares its deduplication domain.

These are lossy observations, not restorable canonical Msg values. Metadata, media bytes, and reasoning signatures are not copied. Compared with upstream, payloads retain block types for visible messages and preserve tool-result state. Joined output uses canonical ToolResult.outputText, including separators around empty text blocks; upstream omits some leading empty separators. UUID entry identities and structural keys deliberately replace Java's concatenated IDs; derived JSON codecs are Scala DTO codecs, not Java SessionEntry wire adapters or strict persisted-state validators.

## Atomic store authority

TranscriptStore.inMemory allocates recording time and candidate UUIDs in ZIO before a pure Ref.modify transition. Within that transition, each accepted row updates the stable-key set, selects the latest accepted parent, and appends. Duplicate rows neither append nor change the parent. A UUID collision rejects the entire proposed batch and preserves previous state. Independent concurrent writers on one store therefore publish whole batches into one linked sequence. Empty and all-duplicate batches return no committed entries.

recordedAt is sampled before candidate allocation and commit; it is an observation timestamp, not a monotonic ordering authority. Follow the stored sequence and parent links. Cancellation/failure after commit may have an uncertain acknowledgement; do not infer rollback from an interrupted caller. Anonymous retries are not idempotent. TranscriptStore.inMemory is process-local: no files, segments, indexes, remote mirrors, crash durability, or automatic runtime scheduling are provided. LocalTranscriptStore, described below, validates persisted input and preserves the same atomic deduplication/linkage contract.

## Exact owned numeric output

Synthetic structured completion uses exact JSON rendering for its closing assistant text, preserving agreement with the typed value and structured metadata. Local transcript rows and bounded tool-input previews use the same renderer. Preview length is measured on that exact text, with the existing UTF-16 and surrogate-pair boundaries. Complete numeric inputs survive actual store closure and reopening.

Public transcript store APIs, file format, trust checks, lease ownership and atomic replacement remain unchanged. The internal TranscriptLog.encode now returns a typed Either; conversion/rendering finishes under the existing append lease before any file write. Empty/all-duplicate appends still skip encoding and writing. The separate [domain/tool integration](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/json/README.md#exact-domain-and-tool-output) covers compactor provenance/previews, checked factory output and opt-in host checkpoint/event encoding; direct dependency codecs remain outside these guarantees.

| Reviewed file | Git blob |
| --- | --- |
| [ReActAgent.scala](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/ReActAgent.scala) | [Current ReActAgent.scala identity](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/client/README.md#optional-reasoning-effort) |
| [StructuredOutputSpec.scala](../../../../../../../../../core/src/test/scala/com/weisgnosis/agents/core/agent/StructuredOutputSpec.scala) | `412794e93602a061639795f9bb1ac8ec3fef597d` |
| [LocalTranscriptStore.scala](LocalTranscriptStore.scala) | `21a05fc889fc7a1e33c57953ad1a5749041e4b7d` |
| [SessionTranscriptWriter.scala](SessionTranscriptWriter.scala) | `a0578e096d12e39920db0524d1896fbd815c6b06` |
| [TranscriptLog.scala](TranscriptLog.scala) | [Current remote-transcript identity](#versioned-remote-transcript) |
| [LocalTranscriptStoreSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/LocalTranscriptStoreSpec.scala) | `4d0fc2a79d6deff29a587de30c96acead7cdfd4b` |
| [SessionTranscriptWriterSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/SessionTranscriptWriterSpec.scala) | `34789a3976438638aa26fa80ec933afcddd25265` |
| [TranscriptLogSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/TranscriptLogSpec.scala) | `1999df3399829e8f811bced218faab9b6bba4247` |

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for these eight identities, with no findings. Existing structured, preview, codec and reopen groups were extended; no new groups were added.
- [x] `structured-numeric-red.log` recorded 14 passes/one failure against ReActAgent `b3cc1d60cfd50473bd5a4f8c3c0e33b87c3c6a25`: synthetic closing text disagreed with the correct minimum-Long typed value. `owned-numeric-red.log` then recorded 23 transcript passes/two failures against TranscriptLog `5566ad5cd64653e2b0f23c8cdc93b7ba8f28e398` and SessionTranscriptWriter `da27d3446e30ee7f77f4eaaac2d424200ac17383`: reopened nested values and numeric preview text/length were corrupted. The corrected structured section passed 15 tests in 471 ms.
- [x] Full formatting and verification passed in `owned-numeric-verify.log` with zero compiler warnings or failures. Current totals/environment belong to the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance). These are reproduced numeric-integrity regressions, not new upstream whole-method mappings.

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
| [SessionTranscriptWriterSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/SessionTranscriptWriterSpec.scala) | [Current numeric-integrity identity](#exact-owned-numeric-output) |
| [TranscriptEntrySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/TranscriptEntrySpec.scala) | 4bbae7448ce93a6cc3763e1b5dd44acf74eecb05 |
| [TranscriptStoreSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/TranscriptStoreSpec.scala) | 9e582e4d6b8e76ddbfb943b24424dd86b4f4ad4a |

## Versioned remote transcript

[VersionedTranscriptStore.make](VersionedTranscriptStore.scala) is a pure checked factory returning TranscriptStore over one host-selected VersionedJsonStore and StorageAddress. maxAttempts defaults to eight and must be positive. Construction performs no storage, clock or randomness effects. Missing storage reads as an empty transcript; an empty append does not create an aggregate.

Each nonempty append samples Clock.instant and candidate UUIDs once before conditional publication. The shared TranscriptState transition validates the entire batch, retains the first observation for each stable key and derives parent links from the current persistent tail. A definite ConditionalWrite.Conflict permits bounded reread/recomputation using the same timestamp/UUID candidates and new parent links. Exhaustion fails with MemoryStorageError. Successful append returns exactly the newly committed entries in order; all-duplicate batches perform no write. Anonymous drafts append on each invocation and are not idempotent.

The private version-1 aggregate requires schemaVersion and entries. Before restoring the complete chain, decoding rejects duplicate JSON fields, malformed Unicode strings/object keys, unsupported versions and noncanonical DTO representations, including ignored fields and decoder coercions. Optional fields use the writer's omitted-field representation. TranscriptLog.sameRepresentation is shared only within this package; local JSONL behavior is unchanged. TranscriptState.restore rejects duplicate IDs/stable keys, broken parents, invalid stable-key/payload pairings and invalid preview claims. Encoding applies the same validation before publication. Exact decimal rendering preserves complete numeric input values; object order and equivalent numeric spelling do not determine validity.

Only explicit conflicts authorize retry. Backend failures, cancellation and lost acknowledgements propagate without replay; publication may already have committed. Hosts coordinate recovery by reading persistent state. This adapter supplies no authenticated checkpoint claim, execution deduplication or exactly-once delivery. All entries live in one aggregate; every read validates the complete chain and every update replaces the complete value. Hosts must bound transcript growth and backend payload size. TranscriptStore has no deletion/retention API: immutable segments, listing/indexes, remote discovery/pruning, compaction and layout migration remain unfinished.

Migration: this factory is additive. Existing TranscriptStore/in-memory/local APIs and local version-1 JSONL rows retain their contracts. The remote envelope is separate and does not automatically import local logs or reproduce upstream session-file/segment formats. Backend address/revision and uncertain-publication semantics belong to the [versioned publication contract](../storage/README.md#versioned-json-publication).

| Reviewed source/spec against bbadf13 | Git blob |
| --- | --- |
| [VersionedTranscriptStore.scala](VersionedTranscriptStore.scala) | `a7b743c3d8ba62d69c671a408d8755c1f8a93ff6` |
| [TranscriptLog.scala](TranscriptLog.scala) | `4e3a671c3a412954705a5af2af1b0f9593a7f74c` |
| [VersionedTranscriptStoreSpec.scala](../../../../../../../../../storage-postgresql/src/test/scala/com/weisgnosis/agents/storage/postgresql/VersionedTranscriptStoreSpec.scala) | `6a0c7068d7911597a2e849154d6a1815e7eaff9b` |

- [x] review_fp and review_invariants approved these three final identities against bbadf13, with no unresolved findings.
- [x] Focused verification passes 19 existing transcript regressions and all nine real-PostgreSQL groups, including three new transcript groups.
- [x] versioned-transcript-verify.log passes formatting, fatal-warning compilation, four artifact packages and 1,492 tests: 667 core / 726 harness / 85 MCP / 9 PostgreSQL / 5 examples, with zero compiler warnings or failed tests. versioned-transcript-artifact-preparation.log passes all four archive/POM checks.
- [x] versioned-transcript-artifact-consumer.log passes standalone formatting, clean compilation and forked execution with four loaded module hashes. Its existing selected API smoke does not construct this adapter.
- [x] review_fp and review_invariants approved the final combined three-file/documentation scope against bbadf13 with no unresolved findings; English inspection and all 2,684 local references in 78 Markdown documents passed.

## Scoped local transcript store

[LocalTranscriptStore.open](LocalTranscriptStore.scala) binds one existing directory to one transcript. WorkspaceTrust.HostControlled is required before filesystem access; the host protects the directory and every ancestor. Traversal, sensitive locations, filesystem roots, non-directory storage, and nonregular/symlink payloads are rejected. The runtime derives a fixed transcript.jsonl destination and .transcript-writer.lease ownership file. There is no tenant/session path interpolation or fallback storage choice; the host binds separate directories to separate transcript identities.

The internal [WorkspaceLease](../storage/WorkspaceLease.scala) shares the tested daily-memory ownership state machine. Waiting can be interrupted; admitted filesystem work finishes before ordinary pending cancellation is delivered. Scope closure waits for admitted work, closes the handle, verifies the ownership token, then removes the lease. Explicit caller masking is preserved. Competing owners never overwrite or remove a lease; stale ownership requires operator confirmation that every previous owner has stopped before manual removal. Slow storage can delay interruption and scope closure. Cleanup-token failures are observable finalizer defects.

Every read and append validates the canonical directory, reads all current rows after admission, strictly decodes them, and restores the complete identity/key/parent chain. An append uses the same [TranscriptState](TranscriptState.scala) validation and pure batch transition as the in-memory backend. Invalid stable keys, mismatched key/payload kinds, invalid preview length claims, ambiguous complete input JSON, duplicate persisted IDs/keys, and broken parents fail without publishing a candidate. Recording timestamps need not increase. Empty and all-duplicate batches preserve the payload file. The backend rewrites the complete log through a scoped temporary file and ATOMIC_MOVE, with no incremental-write or non-atomic fallback; work and memory scale with the complete log size per append.

[TranscriptLog](TranscriptLog.scala) stores one private version-1 envelope per physical JSONL row. Empty files are valid; the final newline is optional and LF/CRLF are supported. Interior blank rows, malformed/truncated rows, duplicate JSON keys, trailing documents, unknown fields, unsupported versions, and decoder coercions fail closed. Canonical DTO shape validation allows object-field ordering and numeric formatting differences, but optional values must use omitted fields rather than explicit JSON null. The internal encode method returns Either[MemoryStorageError, String]: every typed row converts to a checked JSON AST and then exact numeric text before files.write. Encoding failure cannot fall back to lossy text or publish a partial log. The complete log is validated before replacement. Decoding never skips a bad row or resets corrupt input to empty state. Versioned disk envelopes are separate from public derived DTO codecs.

Atomic replacement protects cooperative readers from partial file contents and preserves hard-linked source files by replacing the directory entry. It does not provide fsync/power-loss durability, exactly-once anonymous replay, hostile mutation protection, or an identity guarantee against uncoordinated directory replacement. Cancellation may be delivered after publication. Failed temporary cleanup is logged; abandoned temporary files can remain after process termination. Cross-process contention and dynamic symlink/junction behavior need platform-specific validation.

Migration: shared lease extraction preserves memory/.daily-writer.lease and existing DailyMemoryLease entry points. The local transcript backend deliberately uses one host-selected directory and one versioned transcript.jsonl; it does not reproduce Java `agents/<agent>/sessions/<session>.jsonl/.log.jsonl` layout, mirror dual files, or wire IDs. The four local method mappings are behavioral/reopen adaptations with those differences; opt-in background memory flush is implemented in the memory package; owned consolidation scheduling is also available; explicit local transcript pruning is implemented below; immutable remote segments, indexes and automatic transcript retention remain pending. Previous changed transcript, lease, and test-helper identities are preserved in e4dfc67.

- [x] `review_fp` and `review_harness` approved the final 14-file local transcript increment against `e4dfc67` on 2026-09-29. Twenty-seven new tests reproduced and fixed Unicode surrogate splitting; full `sbt scalafmtAll scalafmtSbt verify` passed 530 core / 258 harness tests with zero compiler warnings. No findings remain within shared cooperative ownership, strict complete-log validation, atomic batch replacement, reopen/deduplication, and Unicode-safe previews. Remote segments, Java layout/wire equivalence, hostile mutation, fsync/power-loss durability, and automatic turn scheduling remain excluded.
- [x] `review_docs` approved the corrected local-transcript documentation against `e4dfc67`, including 14 identities, 27 new tests, strict storage/Unicode contracts, four local adaptations, and explicit remaining work. All 63 Markdown files passed link/script checks; git diff --check passed.
- [x] Integrate required terminal snapshot recording through the completion observer described below.
- [x] Integrate opt-in background flush and combined daily-memory maintenance in the memory package.
- [x] Add explicit transcript-retention policy below.
- [x] Integrate explicit owned batch pruning below.
- [x] Implement bounded host-controlled local enumeration for maintenance; see [discovery](#bounded-local-transcript-discovery).
- [ ] Implement remote immutable segments/indexes and platform-specific ownership validation.

Unchanged Git-normalized local persistence identities; the changed TranscriptFiles identity is preserved in 9ddee3a and indexed in the pruning table below:

| Source | Blob identity |
| --- | --- |
| [DailyMemoryLease.scala](../memory/DailyMemoryLease.scala) | 7c1cddd14656f11e33228ecef785e75a9f60b4a4 |
| [LocalTranscriptStore.scala](LocalTranscriptStore.scala) | [Current numeric-integrity identity](#exact-owned-numeric-output) |
| [TranscriptLog.scala](TranscriptLog.scala) | [Current numeric-integrity identity](#exact-owned-numeric-output) |
| [TranscriptState.scala](TranscriptState.scala) | e202ba895f83df0990d2c78e2e69118fae0cf131 |
| [TranscriptStore.scala](TranscriptStore.scala) | d5978648ebdd370635c91bd7bef45d270796b764 |
| [MemoryTestSupport.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryTestSupport.scala) | d891b7e44834d27897845c544c9050114adb2630 |
| [LocalTranscriptStoreSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/LocalTranscriptStoreSpec.scala) | [Current numeric-integrity identity](#exact-owned-numeric-output) |
| [TranscriptFilesSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/TranscriptFilesSpec.scala) | fd5db63a28bc71b56bd645e1fd7db32c72301db4 |
| [TranscriptLogSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/TranscriptLogSpec.scala) | [Current numeric-integrity identity](#exact-owned-numeric-output) |
| [TranscriptStateSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/TranscriptStateSpec.scala) | f3be77abf723e891e8a103d8482120fe40f0dd8f |

## Terminal canonical snapshot recording

Install writer.completionObserver(policy) through ReActAgent.make(completionObserver = ...) or Builder.completionObserver. The policy is mandatory: RequireStableIds rejects a snapshot containing any eligible anonymous draft before append; RecordEachSnapshot appends anonymous observations on every terminal snapshot. Stable keys still deduplicate across turns. Skipped internal session-context messages do not require IDs. The adapter never assigns or changes canonical message identities. Runtime-created system, tool, summary, and recovery messages can be anonymous, so strict mode requires an identity strategy covering every eligible message; RecordEachSnapshot explicitly accepts repeated retained anonymous history.

The observer records success, final failure, timeout, and reconciled cancellation snapshots once per admitted invocation. It maps storage failures to CompletionObservationError and does not swallow them or retry after uncertain publication. Keep the store scope alive around every agent invocation. The standalone offload callback retains its existing anonymous append behavior and does not enforce the completion adapter policy. Install writer.offload on the compactor as well when removed prefixes must remain in the transcript; a terminal snapshot alone cannot recover content already removed by compaction.

Required observation completes before successful result publication and before cancellation returns. Late cancellation may still coexist with an AgentResultEvent representing committed execution. Direct calls and drained streams expose terminal observer errors; early consumers abandon that error queue and cannot treat their own completion as persistence acknowledgement. Observers must not await operations requiring the same held agent run lock. See the [core completion contract](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md) for cause, scope, and invocation boundaries.

- [x] `review_fp` and `review_harness` approved the final eight-file completion-observer increment against `0349b1a` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 552 core / 264 harness tests, including 28 new cases, with zero compiler warnings. No findings remain within scoped once-per-invocation observation, canonical snapshots, cause preservation, explicit transcript identity policy, and local persistence integration. Background memory scheduling, remote persistence, delivery acknowledgement, and exhaustive upstream middleware parity remain outside this scope.

- [x] Integrate scoped background flush and combined daily-memory maintenance; choose the appropriate owner scheduler.
- [x] Add explicit transcript-retention policy below.
- [x] Integrate explicit owned batch pruning below.
- [x] Implement bounded host-controlled local enumeration for maintenance; see [discovery](#bounded-local-transcript-discovery).
- [ ] Implement remote immutable segments and session indexes.

The previous SessionTranscriptWriter.scala identity is preserved in `0349b1a`; its current identity and acceptance are in the completion-observer table in the [core agent contract](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md).

- [x] `review_docs` approved completion-observer documentation against `0349b1a`, including all eight identities, 28 new cases, late cancellation, early-stream error visibility, explicit anonymous policy, compactor offload and remaining lifecycle work. Full verification passed 552 core / 264 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed.

## Explicit local transcript pruning

[LocalTranscriptRetention.prune](LocalTranscriptRetention.scala) takes one existing host-selected directory, explicit WorkspaceTrust and an exclusive Instant cutoff. It acquires the same WorkspaceOwner.Transcript lease as LocalTranscriptStore, inside its own Scope, and protects the entire inspection/deletion operation. Active or stale leases cause typed storage failure and are never reclaimed. This observes storage ownership, not application-level user presence; hosts must keep writer ownership alive while the corresponding session is active.

Only the fixed transcript.jsonl payload is eligible. Missing payload yields TranscriptPruneResult.Missing; an existing empty file is a valid zero-entry transcript. A missing directory is an error. The directory and payload must pass existing canonical/sensitive/nonregular checks, and the complete log must pass strict version/shape/key/parent-chain validation before deletion is considered. Corrupt logs are preserved for diagnosis, including logs that appear expired.

Activity is the maximum of the file modification time and every persisted recordedAt timestamp. Recording clocks may move backwards, so the final row alone is insufficient. Strictly earlier activity is pruned; equality, recent metadata, recent observations and future timestamps retain the file. Retained reports the activity timestamp; Pruned reports that timestamp and the removed entry count. File metadata times retain the underlying filesystem's precision.

Deletion removes only that validated payload entry. It does not recurse, enumerate sibling sessions, remove directories, touch unrelated files, or remove another owner's lease. The scoped finalizer releases this operation's lease. Admitted work retains WorkspaceLease's cancellation rule: deletion may finish before pending cancellation is delivered, so interruption/failure is not proof of unchanged storage. Slow filesystem work can delay cancellation; no rollback, undelete or power-loss durability is supplied.

Successful pruning retires the persisted deduplication keys and parent chain. A later open starts an empty transcript and accepts formerly used stable keys as new observations. The host must retire the corresponding logical session rather than assume identity continuity or a retained tombstone. This is an explicit destructive storage capability for a host-selected inactive session, not automatic application session discovery or authentication.

The backend still requires a host-controlled directory and ancestors. Cooperative leases and canonical checks do not protect against hostile external path replacement. This is a hardened source-level adaptation of upstream pruneOldSessions: explicit target selection, strict validation, active-owner refusal and observation-aware recency replace a broad filename scan, mtime-only decision and swallowed failures. No new named upstream pruning test is claimed. The 180-day policy is implemented below; bounded local discovery is available below; remote pruning remains unfinished; owned batch maintenance is implemented below. Prior TranscriptFiles.scala is preserved in 9ddee3a.

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

Calling this policy API explicitly selects the target and enables the chosen operation. It does not enumerate directories, install a timer, create a session catalog or claim a separate maintenance gate. Existing strict decoding, activity calculation, ownership refusal, generation retirement and uncertain cancellation acknowledgement remain unchanged. The default is not silently applied to the older prune API. Owned multi-target coordination is implemented below.

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

## Ordered explicit retention batches

[TranscriptRetentionTarget](TranscriptRetentionTarget.scala) pairs an exact nonblank TranscriptTargetId with a suspended prune capability. IDs are correlation values, not paths, authorization or proof of inactivity; nonblank whitespace is preserved. The local constructor delegates to the existing scoped lease-protected pruner. Custom capabilities own their effect/resource contracts. Different IDs may alias one directory; the batch does not canonicalize or deduplicate paths, so a later alias can report Missing after an earlier deletion.

[TranscriptRetentionBatch](TranscriptRetentionBatch.scala) rejects duplicate exact IDs before invoking capabilities. KeepAll returns Disabled without reading Clock, running discovery or invoking targets. Enabled preparation samples Clock.instant once, validates the policy cutoff, resolves and checks selection, and captures sequential traversal without executing any target. Each target receives that cutoff in supplied order. An enabled empty batch reports Evaluated with an empty target list. Prepared execution is replayable, not a once-only receipt or durable plan.

A typed failure, defect or cancellation stops later targets and preserves the full cause. Earlier effects remain observable; no aggregate success is returned for a partially completed batch. Local lease refusal still protects active/stale-owned transcripts, and local pruning retains all validation and generation-retirement rules. The host supplies explicit immutable selection or opts into cold discovery. Neither path grants automatic activity authorization or a multi-file transaction.

The [memory coordinator contract and current identities](../memory/README.md#selected-transcript-maintenance) cover background integration, partial-progress reporting, all eight source identities and acceptance. Existing single-target APIs remain source-compatible.

The unchanged coordinator and target identities remain in the [memory contract](../memory/README.md#current-batch-and-coordinator-identities); current batch identities are recorded below.

## Bounded local transcript discovery

[LocalTranscriptDiscovery.discover](LocalTranscriptDiscovery.scala) is a cold, read-only scan of one explicit WorkspaceTrust.HostControlled root. LocalTranscriptDiscoveryLimits defaults to 4,096 root entries and 256 selected targets; both limits must be positive. Every immediate entry counts, including ignored regular files. Selected directories are sorted by filename, and their exact filenames become target IDs. Only directories with a present regular transcript.jsonl are selected; a genuinely absent payload skips that directory. Missing roots and other filesystem failures fail the whole selection. No directories are created.

Traversal is rejected before normalization. Sensitive entries and nonregular or symbolic-link entries are rejected before payload probing; selected directories and payloads must retain their canonical identity and direct parent. Enumeration closes before candidate validation. These checks rely on host-controlled ancestors and are not an atomic filesystem sandbox. Symlink/junction behavior and interruption during actual filesystem calls have not been integration-tested; joined blocking calls have no cancellation deadline.

TranscriptRetentionBatch.fromDiscovery stores an injected selection effect without running it. Every enabled preparation obtains a fresh snapshot and rejects duplicate IDs; KeepAll bypasses both discovery and Clock. Existing make calls retain explicit-target behavior. MemoryMaintenanceCoordinator completes discovery, cutoff and ID validation before memory work. Discovery does not acquire writer leases, parse transcript bodies or establish that a logical session is inactive. Each target retains the existing lease and complete-body validation at pruning time, so a later failure may follow committed memory and earlier deletions. Hosts retain responsibility for session retirement and receipt handling.

Migration is additive: existing batch construction and scheduler APIs are unchanged. A custom selection effect must finish its scoped cleanup before returning. Remote storage is not supplied by these local capabilities; a remote backend needs a concrete ownership, conditional publication and recovery contract rather than an inferred remote implementation of the local port.

| Source | Git blob |
| --- | --- |
| [LocalTranscriptDiscovery.scala](LocalTranscriptDiscovery.scala) | 2f7212d2f9d53deba2265572f5fd31f148131e56 |
| [LocalTranscriptDiscoverySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/LocalTranscriptDiscoverySpec.scala) | 58263f93ca9c38286d4b177e63ccc8eb7b24c79c |
| [TranscriptRetentionBatch.scala](TranscriptRetentionBatch.scala) | 764cace6c8b535b1cf611129137edd86963d3bb6 |
| [TranscriptRetentionBatchSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/TranscriptRetentionBatchSpec.scala) | 7f5ecad860a161f22c8e9a8d205b5056eec11ece |

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for these four identities, with no findings. Focused checks passed 40 tests in 682 ms. Full `scalafmtAll scalafmtSbt verify` passed in `transcript-discovery-verify.log` with zero compiler warnings or failures; current aggregate totals belong to the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).
- [ ] Remote discovery/pruning, automatic scheduling and platform-specific hostile-filesystem guarantees remain unfinished.

# Conversation compaction

ConversationCompactor accepts an effectful summarizer, a pure TokenCounter, and optional flush/offload callbacks. Defaults: triggerTokens=120000, keepTokens=40000, preserveTailMessages=6, maxPruneOutputChars=2000, protectTokens=40000. Valid histories below the trigger remain unchanged. Compaction prunes older payloads, preserves matched call/result pairs and protected tails, deduplicates summaries, and coalesces adjacent user messages. Retained-user provenance survives coalescing for later flushing. MemoryFlushManager implements daily extraction through an injected store; scoped local storage, atomic in-memory consolidation, and recoverable local consolidation are available.

## Pairing and pruning

Each call occurrence enters an immutable pending sequence. Inline results consume only eligible provider-hosted calls from their own assistant message. Remaining calls match subsequent Role.Tool observations once, by ID and by name when supplied; unnamed results consume the first matching occurrence. An unmatched provider-marked call remains provider-owned and creates no local pending entry or compaction span; a separate Tool observation cannot complete it. Inline observations cannot steal a pending local call from the same or an earlier assistant message.

Occurrence spans are computed once. A cutoff crossing any matched span moves backward to its assistant message; leading orphan Tool messages move forward into the prefix. This preserves repeated IDs, identical parallel calls, reversed named completion, and pairs across intervening messages. It does not validate all malformed input histories or guarantee completeness of unrelated preexisting unmatched calls/results; the installed HistoryPreparer validates actual changed histories before runtime commit.

Older text output is pruned while retaining non-text blocks and execution metadata/state. Oversized unsigned arguments are replaced by a preview object, and their cached content is cleared. The presence of either thoughtSignature or reasoningDetails protects arguments and cached content from pruning, including malformed or JSON-null metadata values. A reserved key on any Thinking block protects all sibling calls, including an empty detail array. This argument-pruning protection does not prevent whole-prefix summarization. Identity, state, metadata, protected tails, and below-trigger histories remain unchanged. Token thresholds trigger best-effort reduction; preservation rules can retain large signed arguments.

Prior summaries enter summarization once but never raw-memory flushing. A replacement summary is coalesced with an initial retained user turn, with validated provenance for later flushing. System messages are preserved. Typed hook/summarizer failures propagate, cancellation remains cancellation, and completed external flush/offload effects cannot be rolled back.

Retained-user provenance and numeric argument previews use [exact JSON boundaries](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/json/README.md#exact-domain-and-tool-output). Conversion errors fail preparation before a candidate is returned; message/block metadata decodes directly without numeric text conversion. review_fp and review_invariants approved the linked exact-output source/spec identities with no findings; full formatting, verification, packaging and packaged-consumer checks passed. The regression preserves provenance through later extraction exactly once. This does not change protected arguments, pruning limits, hook ownership or flush ordering.

## Implementation

- [ConversationCompactor](ConversationCompactor.scala)
- [CompactorConfig](CompactorConfig.scala)
- [TokenCounter](TokenCounter.scala)

See the [test index](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/README.md), [tracker](../../../../../../../../../../docs/architecture/tracker.md), and [parity audit](../../../../../../../../../../docs/architecture/test-parity.md). Prior provenance and message-migration acceptance is preserved in Git through `6ed928a`.

## Runtime installation

ConversationCompactor.preparer adapts compactIfNeeded to core HistoryPreparer and normalizes typed failures into HistoryPreparationError. Install it with ReActAgent.make(historyPreparer = compactor.preparer) or Builder.historyPreparer. Preparation runs in a nested scope before each model request, after prior acting work completes. Runtime validation preserves ordered system messages and complete tool pairing for actual rewrites; unchanged candidates retain the permissive supplied-history API. A changed candidate retaining unmatched calls/results is rejected without committing history. Pairing does not prove summary fidelity, and completed external hooks cannot be rolled back.

[ConversationCompactorIntegrationSpec](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/ConversationCompactorIntegrationSpec.scala) verifies cancellation after history shrinkage, typed summary failure without Auto fallback, hook cancellation cleanup, and chained preparation preserving a single summary and paired tool observations. Shared core ToolHistory supplies local pairing semantics. The [provider-ownership acceptance](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#provider-owned-pending-calls) includes the current compactor fixture: same-ID/name local pairs crossing the cutoff are preserved, while complete local prefixes can be removed without a false provider span.

## Failure policy and session ownership

Preparation errors retain original canonical input and stop model dispatch. This intentionally differs from upstream fallback to original input or a failure-text summary. ZIO Cause represents interruption directly; the implementation does not traverse wrapped or cyclic Throwable chains. Pure interruption produces one recovery, while mixed interruption/cleanup defects remain failures without a successful recovery result. Successful preparation is committed before downstream model execution; a later interruption does not undo that commit.

A compactor can be shared, subject to its injected dependencies' concurrency contracts. Each ReActAgent owns one history and serializes its runs. The concurrent tests use separate agents sharing a compactor/client; they do not establish a multi-session API on one agent.

ToolResultEvictor.preparer can precede ConversationCompactor.preparer via HistoryPreparer.andThen. Changed stages are validated before the next runs; runtime commits once after scope cleanup. See the [eviction contract and migration](../eviction/README.md).

## Current review acceptance

- [x] `review_fp` and `review_harness` approved the two test-file diff against `52da827` on 2026-09-29, with no findings. Thirteen additional cases and strengthened existing assertions cover exact summary/flush/tail routing, short-circuiting, preparation and downstream cancellation, mixed causes, and separate-agent isolation.
- [x] Full `sbt scalafmtAll scalafmtSbt verify` passed 529 core and 114 harness tests, including 39 compactor tests, with zero compiler warnings. Production code is unchanged in this increment.
- [x] All seven compactor and ten middleware methods have explicit [applicability decisions](../../../../../../../../../../docs/architecture/test-parity.md#compaction-method-applicability). Failure-policy divergences, summary identity, structured causes, session ownership, and the cyclic-Throwable exclusion are explicit; literal middleware equivalence is not claimed.
- [x] Complete the bounded local consolidation acceptance recorded below.

The documentation-only reconciliation against 75265e0 accounts for all 24 methods in three pinned suites at upstream f98a685: seven ConversationCompactorTest, ten CompactionMiddlewareTest and seven ToolResultEvictionMiddlewareTest. The compactor applicability decisions above and [seven eviction mappings](../../../../../../../../../../docs/architecture/test-parity.md#toolresultevictionmiddlewaretest-seven-methods) retain their existing source/spec acceptance. No code, tests or behavior changed, and this classification does not mean literal Java fallback, mutable middleware identity or session-API equivalence.

Backend applicability is explicit: compaction uses injected scoped callbacks; eviction uses an injected store and an opt-in trusted local/absolute backend. Local canonicalization is not an atomic hostile-filesystem sandbox. Platform-specific symlink/junction checks, untrusted-workspace isolation, crash durability and broader middleware/memory-service inventories remain unfinished.

- [x] review_fp and review_invariants approved the four-file reconciliation against 75265e0 with no unresolved findings; existing source acceptance is unchanged.
- [x] Manual English inspection, changed-script/diff checks and 78-document/2,989-local-reference checks pass; existing source verification remains applicable without a new build.

Previous implementation/hardening approvals remain in Git through `52da827`. Current reviewed test blobs (Git-normalized):

| Source | Blob identity |
| --- | --- |
| [ConversationCompactorSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/ConversationCompactorSpec.scala) | [Current identity](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/json/README.md#exact-domain-and-tool-output) |
| [ConversationCompactorIntegrationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/ConversationCompactorIntegrationSpec.scala) | [Current ConversationCompactorIntegrationSpec.scala identity](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#checked-registry-identity) |

## Daily-memory extraction

[MemoryFlushManager](MemoryFlushManager.scala) is constructed effectfully with `make(store, extractor, zone, prompt)`. Its `flush(messages)` can be passed directly as the compactor flush callback. [DailyMemoryStore](DailyMemoryStore.scala) exposes only readCurated, readDaily(LocalDate), and appendDaily(LocalDate, entry). Its contract targets memory/YYYY-MM-DD.md; no arbitrary path or curated write capability reaches the manager. Hosts choose an injected implementation or LocalDailyMemoryStore.open. The interface itself does not guarantee disk durability or concurrency behavior; the local backend contract is below.

The manager expands validated retained-user provenance before excluding summary text, system/internal context, thinking, and nontext media. Tool arguments receive 500-character previews and each textual tool result receives a 1,000-character preview. It reads curated/daily context, sends two messages to extraction, closes the extraction scope, then appends a timestamped Markdown section. Blank output and trimmed exact NO_REPLY skip append. Other output is preserved verbatim; semantic quality, prompt safety, and deduplication are not mechanically guaranteed.

One Clock instant and explicit zone (UTC default) bind the context lookup, entry timestamp, and append date even if extraction crosses midnight. One manager serializes the complete read/extract/append sequence. Share the manager for a ledger owner; separate managers, stores, processes, and external curated writers require host coordination. Failed appends are not retried automatically and may have backend-defined partial effects; completed external writes cannot be rolled back.

[MemoryExtractor](MemoryExtractor.scala) accepts a lazy scoped host function. Its fromClient adapter creates request correlation and a scoped event hub, sends no tools or response formatting, and returns visible text. Tool requests/observations fail; interrupted outputs remain interruption. The caller closes the scope. Manager extraction failures/cancellation/cleanup defects skip append. Typed storage reads fail explicitly instead of upstream's silent empty-context fallback.

- [x] `review_fp` and `review_harness` approved the seven-file daily-memory extraction increment against `738b216` on 2026-09-29. No findings remain within the injected-store capability, scoped client extraction, provenance/date handling, one-manager serialization, and compactor integration. Full formatting/fatal-warning verification passed 529 core / 136 harness tests, including 22 new tests. Filesystem persistence, cross-manager/process guarantees, consolidation, watermarks, and transcript offloading are excluded.
- [x] Full formatting/fatal-warning verification passed 529 core / 136 harness tests, including 22 new daily-memory tests and all 39 compactor regressions.
- [ ] Add remote transcript offloading and remaining scheduling integrations; bounded versioned remote memory is available below; explicit local watermark migration is described below.

Current daily-memory Git-normalized source identities; prior compactor implementation identity is preserved in `738b216`:

| Source | Blob identity |
| --- | --- |
| [ConversationCompactor.scala](ConversationCompactor.scala) | [Current identity](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/json/README.md#exact-domain-and-tool-output) |
| [DailyMemoryStore.scala](DailyMemoryStore.scala) | 204a494090531403e1a7b2f3108787770a9bd5a0 |
| [MemoryExtractorSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryExtractorSpec.scala) | 2db567e78995479fd0c733f1346db8720d5df566 |
| [MemoryFlushManagerSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryFlushManagerSpec.scala) | [Current identity](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/json/README.md#exact-domain-and-tool-output) |
| [MemoryFlushManagerIntegrationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryFlushManagerIntegrationSpec.scala) | f97c09f6e95790fbac180608591f1d22bd3b6b2c |

## Scoped local daily store

[LocalDailyMemoryStore.open](LocalDailyMemoryStore.scala) returns ZIO[Scope, AgentError, DailyMemoryStore] and requires WorkspaceTrust.HostControlled. The host must protect the workspace and every ancestor against uncoordinated mutation. Untrusted is rejected before filesystem access. The existing workspace is screened for traversal/sensitive paths and canonicalized; memory is created if absent and must be a canonical non-symlink directory. Payload targets must be regular non-symlink files within their expected parent. The daily capability can propose writes only to memory/<ISO-date>.md. Daily append preserves curated content even when a daily file was originally hard-linked to it. This entry point delegates to LocalMemoryRepository: opening or using the daily capability may publish a previously journaled curated replacement during recovery; it is not a globally read-only curated path.

[DailyMemoryLease](DailyMemoryLease.scala) atomically creates memory/.daily-writer.lease with an opaque owner token using CREATE_NEW. Competing acquisitions never remove or overwrite it. One store serializes admitted operations; its scope waits for them, marks the handle Closed, verifies the token, then deletes the lease. Queued operations can be cancelled without entering. Admitted filesystem work runs with interruption masked; ordinary pending cancellation is delivered afterward, while an explicitly uninterruptible caller retains its policy. Slow filesystem operations can delay cancellation and scope closure. Handles escaped from a closed scope fail with MemoryStorageError.

Append builds the full old-plus-new UTF-8 content in a scoped temporary file and publishes it with ATOMIC_MOVE. No non-atomic fallback is allowed. Providers may reject atomic replacement of an existing target; the resulting error remains typed. Temporary-file cleanup failures are logged; ownership cleanup failures surface as finalizer defects and keep the handle closed. The real Windows tests verify repeated replacement, complete concurrent appends, and preservation of hard-linked curated content.

Atomic publication is not fsync/crash durability or exactly-once delivery. Cancellation or failure may be observed after a complete append was published. A failed acquisition or abrupt process termination can leave a lease, and abandoned temporary files may remain after a crash. No automatic stale-lease detection or removal occurs. For recovery, the operator must establish that every previous owner has stopped, verify the canonical workspace, and only then remove the stale .daily-writer.lease. Other writers must honor this ownership protocol; canonical checks and token verification are not an atomic sandbox or filesystem-object identity guarantee.

The protocol uses the Java [CREATE_NEW contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/StandardOpenOption.html#CREATE_NEW) and [atomic move semantics](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption...)). These guarantees are distinct from application-level extraction deduplication. Share one MemoryFlushManager per store owner to serialize the complete context-read/extract/append sequence.

- [x] `review_fp` and `review_harness` approved the final seven-file local daily-memory backend against `d1a4cf2` on 2026-09-29. Nineteen added tests exposed and corrected pending cancellation being reported as success after admitted work. Full formatting/fatal-warning verification passed 530 core / 154 harness tests. Acceptance covers cooperative ownership, scoped cleanup, day-derived paths, atomic replacement, and typed storage errors on the tested Windows filesystem. Crash durability, exactly-once delivery, hostile mutation protection, dynamic symlink/junction behavior, and automatic stale-lease recovery are excluded.
- [ ] Verify cross-process contention and dynamic symlink/junction cases on each supported platform; the current executable contention checks use independent store instances in one JVM.
- [ ] Complete remaining remote transcript/scheduling integrations; bounded versioned remote memory is available below. The earlier daily-only increment added no new upstream method-parity claim.

Current local-backend Git-normalized identities:

| Source | Blob identity |
| --- | --- |
| [LocalDailyMemoryStoreSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/LocalDailyMemoryStoreSpec.scala) | 232a08a6ac50c6e4e32873cee36752765b4f51fb |
| [DailyMemoryLeaseSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/DailyMemoryLeaseSpec.scala) | 09a2cda7bb306becd4718b3e49f5e65a0dedd321 |

## Atomic consolidation and process-local storage

[ConsolidationStore](ConsolidationStore.scala) exposes an abstract path-dependent Revision, an atomic ConsolidationSnapshot, and commit(expected, content). Each snapshot includes current curated text and at most one complete pending entry per day. Every curated/daily/progress mutation must advance a never-reused revision. Commit must atomically update curated content and acknowledge exactly the consumed snapshot, or return Conflict without any change. The port has no daily append operation. Backend errors propagate; callers cannot infer rollback from an error or cancellation after a commit may have occurred.

[MemoryRepository.inMemory](MemoryRepository.scala) returns daily, consolidation and retention capabilities sharing one immutable Ref.Synchronized state. BigInt revisions cannot wrap; the public capability keeps their representation abstract. Appending daily content invalidates older consolidation snapshots. A successful conditional transition updates curated text and acknowledged inputs together. Later appends make the full changed day pending again, allowing the model to deduplicate against existing curated text. Raw daily content is never rewritten by consolidation. This repository is process-local and has no disk fallback or crash persistence.

[MemoryConsolidator.make](MemoryConsolidator.scala) validates maxTokens in 1..4000 and accepts a MemoryExtractor and TokenCounter. consolidate serializes calls on that consolidator, rejects duplicate snapshot days, skips blank/no-pending input, and sends deterministic chronological daily content alongside current curated text. Extraction scope closes before output validation or commit. Blank output returns EmptyResponse without acknowledgement; a validated replacement returns Committed or Conflict. Conflict does not automatically retry stale output; a later invocation must obtain a fresh snapshot and recompute.

The output budget is enforced against counter.count(Msg.assistant(strippedOutput)); negative counts and over-budget content fail without commit. TokenCounter.approximate estimates serialized message size, including its envelope. Applications requiring exact model token limits must inject an appropriate pure tokenizer. The budget applies to new replacements; supplied existing curated content is not retroactively truncated. Semantic factual accuracy, extraction quality, and deduplication are not guaranteed by structural/counter validation.

MemoryExtractor.fromClient also serves consolidation, preserving scoped text-only requests and rejecting tool observations. A MemoryFlushManager receives only the repository's daily writer; MemoryConsolidator receives only its curated writer. Hosts are trusted capability owners. Separate consolidator instances can extract concurrently, but only one can commit against a given revision.

The recoverable local implementation is described below; its journal and shared ownership gate supply the missing protocol around separate atomic file replacements.
- [ ] Complete the two remote filesystem mappings and broader transcript/scheduling integration. Explicit local watermark migration is described below. The earlier process-local increment did not establish filesystem equivalence.

- [x] `review_fp` and `review_harness` approved the final six-file consolidation increment against `46c6174` on 2026-09-29. Twenty-one new tests and full formatting/fatal-warning verification passed 530 core / 175 harness tests. No findings remain within process-local atomic content/progress commits, separate writer capabilities, scoped extraction, configured-counter budget enforcement, and conflict handling. Filesystem transactions, persistent watermarks, crash recovery, and the five upstream filesystem mappings remain outside this scope.

The previous MemoryExtractor comment identity is preserved in `46c6174`; its current scope is included below. Current Git-normalized consolidation identities:

| Source | Blob identity |
| --- | --- |
| [ConsolidationStore.scala](ConsolidationStore.scala) | bc4d87299b785335051b8c44553c0c3670b859dd |
| [MemoryExtractor.scala](MemoryExtractor.scala) | fe620774f366965032f4582046b8fc79282ec164 |
| [MemoryRepositorySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryRepositorySpec.scala) | 090804a4d20f01d838c8d7e381175287374cadc2 |
| [MemoryConsolidatorSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryConsolidatorSpec.scala) | 49f460b8ba9e77b202e146b50a0c6b9b561445b1 |

## Remote middleware throttle integration

The shared MemoryLifecycleIsolationSpec oracle now runs unchanged eight local scope/order groups and one real-PostgreSQL group covering four scopes in both flush/maintenance orders. Each remote row uses a versioned repository and independent backend bindings to the same per-row gate address, including a reopened gate with rebuilt schedulers. Exact extractor/report counts, curated content and queue-idle assertions are shared; reopened repository reads verify persisted curated content.

Distinct row addresses isolate the cases; they do not prove cross-scope collision handling. This bounded adaptation verifies middleware throttle/reconstruction combinations, not host restart, distributed scheduling, delivery, physical retention or broader middleware parity. Production API behavior is unchanged. storagePostgresql test compilation borrows the harness test oracle through compile->compile;test->test; fixture types remain private[agents] test infrastructure and do not enter production artifacts.

| Source/build approved by review_fp and review_invariants against 5caa18f | Git blob |
| --- | --- |
| [build.sbt](../../../../../../../../../../build.sbt) | d6d7eec1c3f36494d1be44d5fe420faf50391b99 |
| [Harness MemoryLifecycleIsolationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryLifecycleIsolationSpec.scala) | f2c942c2811b9755e9a30df88e0e47f2675fcd1e |
| [PostgreSQL MemoryLifecycleIsolationSpec.scala](../../../../../../../../../storage-postgresql/src/test/scala/com/weisgnosis/agents/storage/postgresql/MemoryLifecycleIsolationSpec.scala) | 291785b68ce0935c0af15c60cec55b784b230210 |

- [x] review_fp approved the final three source/build identities against 5caa18f.
- [x] review_invariants approved the same three final identities, with no unresolved findings.
- [x] remote-memory-lifecycle-focused.log passed eight local groups in 444 milliseconds and one actual PostgreSQL group in 2.414 seconds.
- [x] remote-memory-lifecycle-verify.log exited zero: 1,509 tests (669 core / 726 harness / 86 MCP / 22 PostgreSQL / 6 examples), zero compiler warnings/failures and four packages.
- [x] remote-memory-lifecycle-preparation.log passed source/archive regressions and restored package/POM staging, including the test-only fixture dependency boundary.
- [x] remote-memory-lifecycle-consumer.log passed fresh standalone formatting, clean compilation and forked selected-API execution without fixture classpath/dependency leakage.
- [x] review_fp and review_invariants approved final combined documentation against 5caa18f and the three unchanged identities; manual English review, script/diff checks and 78-document/2,839-local-reference checks pass with zero issues.

## Versioned remote memory

[VersionedMemoryRepository.make](VersionedMemoryRepository.scala) is a pure checked factory returning the existing MemoryRepository capabilities over one host-selected VersionedJsonStore and StorageAddress. maxAttempts defaults to eight and must be positive. Construction performs no read/write or initial curated publication; an absent aggregate means empty memory. Daily, consolidation and retention ports share the same whole-aggregate authority. [MemoryRepositoryState](MemoryRepositoryState.scala) contains private immutable transitions reused by MemoryRepository.inMemory and this adapter; process-local public signatures and revision semantics remain unchanged.

The private version-1 payload requires exactly schemaVersion, curated, daily, acknowledged and archived. curated is a string or explicit JSON null; all three collections are required arrays. Daily/acknowledged records require exactly day/content; archive records require exactly archive/content, with day/fingerprint in the nested archive identity. Missing/unknown/duplicate fields, unsupported versions, duplicate day/archive identities, empty ledger/archive content, malformed Unicode and incorrect archive content fingerprints fail with MemoryStorageError. Acknowledgements must reference a current day and match its content prefix; full changed ledgers remain pending after later appends. Acknowledged/archive progress requires present curated content. The private payload does not change public model codecs or import legacy local checkpoint/timestamp formats.

Snapshots read and validate one persistent revision. Consolidation commit publishes curated content and acknowledgement atomically only against that exact snapshot revision; stale summary output returns Conflict and is never rebased onto later inputs. Append and acknowledged-only retention may reread/recompute only after an explicit ConditionalWrite.Conflict, up to maxAttempts; exhaustion fails. Underlying failures, lost acknowledgements and interruption do not authorize blind replay. A write may have committed before its acknowledgement fails. Hosts must inspect persistent state and coordinate any semantic retry; this adapter does not supply request deduplication, authenticated checkpoints or exactly-once workflow claims.

One aggregate contains all daily entries, acknowledgements and archives. Each read validates the complete snapshot and hashes every archive; updates conditionally replace the whole value. This design suits host-bounded memory collections. It does not provide paged archives, unbounded-history scalability or multi-address distributed transactions. Host-selected backend size/availability limits and retention policy remain explicit integration responsibilities.

Migration: the factory is additive, and existing MemoryRepository.inMemory/source calls remain unchanged. Remote version 1 is a separate private aggregate format; local journal/checkpoint data is not migrated automatically. StorageError from the backend remains typed; invalid aggregate/domain consistency failures use MemoryStorageError. PostgreSQL schema/DataSource ownership and cancellation limits belong to the [named backend contract](../../../../../../../../../storage-postgresql/src/main/scala/com/weisgnosis/agents/storage/postgresql/README.md). Remote transcript segmentation/discovery/pruning/layout migration and authenticated execution claims remain unfinished; the [acting-checkpoint adapter](../storage/README.md#versioned-acting-checkpoints) supplies separately reviewed conditional persistence.

| Reviewed source/spec | Git blob |
| --- | --- |
| [MemoryRepository.scala](MemoryRepository.scala) | `3684ec420d865e7b981d95a5e9b1e3ea30b31937` |
| [MemoryRepositoryState.scala](MemoryRepositoryState.scala) | `cac4e5ddd9af76b567138c4c764049200d73ace3` |
| [VersionedMemoryRepository.scala](VersionedMemoryRepository.scala) | `8695ca654d6cfd9678a80e0617a523f9748cec5c` |
| [PostgresFixture.scala](../../../../../../../../../storage-postgresql/src/test/scala/com/weisgnosis/agents/storage/postgresql/PostgresFixture.scala) | `c83790ecd9056955fb1bfa96388270f64d545528` |
| [PostgresVersionedJsonStoreSpec.scala](../../../../../../../../../storage-postgresql/src/test/scala/com/weisgnosis/agents/storage/postgresql/PostgresVersionedJsonStoreSpec.scala) | `17635a16f964462743c96964ea965e4cb3c4b4e2` |
| [VersionedMemoryRepositorySpec.scala](../../../../../../../../../storage-postgresql/src/test/scala/com/weisgnosis/agents/storage/postgresql/VersionedMemoryRepositorySpec.scala) | `14b5e250ca9d39bc4b60db3792e6ff5b51ef05e6` |

- [x] review_fp approved FP/ZIO correctness of these six final identities with no findings.
- [x] review_invariants approved the same six final identities and bounded memory/publication contracts with no findings.
- [x] versioned-memory-nested-green.log passes all six PostgreSQL groups in 2.237 seconds; three are new remote-memory groups. The existing 55 memory tests passed separately after the shared-transition refactor.
- [x] versioned-memory-verify.log passes formatting, fatal-warning compilation, four artifact packages and 1,489 tests: 667 core / 726 harness / 85 MCP / 6 PostgreSQL / 5 examples, with zero compiler warnings or failed tests. versioned-memory-artifact-preparation.log validates all four archives/POM/source sets.
- [x] versioned-memory-artifact-consumer.log passes standalone formatting, clean compilation and forked execution against four fresh packaged JAR hashes. The consumer exercises its existing selected APIs; it does not construct this repository.
- [x] review_fp and review_invariants approved these final six identities and the combined documentation scope against b1b3848, with no unresolved findings. English inspection and all 2,661 local references in 78 Markdown documents passed.

## Recoverable local consolidation

[LocalMemoryRepository.open](LocalMemoryRepository.scala) returns a scoped MemoryRepository for a host-controlled workspace. Share its daily, consolidation and retention ports instead of opening separate owners. All ports use the existing .daily-writer.lease, canonical location checks, and the same admission gate. [MemoryFiles](MemoryFiles.scala) derives every destination from an internal typed identifier; journal data never selects a path. Directory enumeration considers canonical ISO-date Markdown names and ignores metadata, temporary files, and unrelated names; a nonregular date-matching entry fails explicitly.

[MemoryJournal](MemoryJournal.scala) validates complete JSON documents, duplicate/unknown keys, numeric representation, supported versions, canonical dates/transaction identifiers, fingerprints, and generation transitions before recovery writes. Shared library-internal JsonBoundary supplies document/duplicate validation. Checkpoints retain a BigInt generation, transaction identity, and acknowledged SHA-256 daily content digests. Revisions bind curated presence/content, checkpoint, and current daily fingerprints; successful same-content commits still advance generation across reopen. Progress assumes cooperative append-only daily writes and SHA-256 collision resistance; external edits are outside the ownership contract.

A conditional commit first compares the current snapshot. Conflict publishes no candidate. On success it atomically publishes memory/.consolidation_journal, then replaces MEMORY.md and memory/.consolidation_state. Every open/read/append/snapshot/commit first recovers under the same lease. Recovery accepts each component only in its recorded old or new state and validates all daily inputs before its first write. Absent curated content differs from a present empty file. Unexpected data fails closed and retains the journal. Missing new components are published, both are re-read and verified, and only then is the journal removed. Raw daily ledgers remain unchanged by consolidation.

Journal publication commits the candidate to roll-forward recovery. An error or cancellation after publication can have uncertain acknowledgement: a later operation may complete the candidate. Inspect the recovered snapshot before deciding whether a new extraction is needed; do not automatically replay model output. API users honoring the shared lease observe coherent content/progress. Uncoordinated raw file readers can observe intermediate files. There is no fsync/power-loss durability, raw multi-file atomicity, exactly-once extraction, automatic stale-lease removal, or hostile-filesystem guarantee. The existing operator procedure for abandoned leases still applies before reopening.

Migration: .consolidation_state is the upstream path, but its versioned JSON contents deliberately replace timestamp-only watermarks. Normal consolidation still rejects legacy timestamp content without reset or overwrite. The explicit local migration below converts canonical timestamps conservatively; no remote filesystem adapter is implemented. Daily-only open remains source compatible but participates in recovery and therefore may publish an already journaled curated transaction. The former LocalDailyMemoryStore and MemoryRepository identities are preserved in e4bd6b0.

- [x] `review_fp` and `review_harness` approved the final nine-file persistence increment against `e4bd6b0` on 2026-09-29. No findings remain within cooperative local ownership, strict journal validation, recovery before API operations, conditional content/progress publication, and persisted generation tracking. Full `sbt scalafmtAll scalafmtSbt verify` passed 530 core / 209 harness tests, including 34 new persistence tests, with zero compiler warnings. Acceptance covers constructed interrupted-publication states on the tested Windows filesystem; live crash/fault-injection, fsync durability, remote storage, and hostile mutation are excluded.
- [x] `review_docs` approved the final persistence documentation against `e4bd6b0`, including nine current identities, 34 new cases, three local upstream adaptations, two pending remote scenarios, migration, and durability limits. All 59 Markdown files passed local link/script checks; git diff --check passed.
- [ ] Add remote storage/migration, remote transcript persistence, and scheduling; verify cross-process and dynamic path behavior on each supported platform.

## Explicit watermark migration

LocalMemoryRepository.migrateLegacyWatermark(workspace, trust) is an explicit scoped operation returning WatermarkMigration.Missing, AlreadyCurrent or Migrated(previous). It requires HostControlled trust and the existing exclusive memory lease. Any present consolidation journal, including an empty or malformed file, blocks migration before checkpoint interpretation; this path never runs journal recovery. Resolve pending recovery separately rather than treating migration as a repair command.

An absent checkpoint is not created. A valid current checkpoint is left byte-for-byte unchanged. Opening the local files and lease may create the memory directory and transient ownership file even for Missing or AlreadyCurrent; these outcomes do not write checkpoint, curated or daily payloads. Otherwise, only an Instant whose canonical toString equals the stripped legacy text is accepted; unknown JSON, quoted timestamps and noncanonical offset forms fail without rewriting. Migration atomically replaces only .consolidation_state with a version-1, generation-1 checkpoint, a fresh transaction UUID and empty content acknowledgements, then reads and validates it. Curated and daily content are untouched. Every daily input must be reconsidered by normal consolidation, including dates before the old timestamp; migration does not make any input eligible for acknowledged-only archival.

The API is additive; normal open/snapshot validation stays strict and never migrates implicitly. No model is invoked by migration. Failure or cancellation can follow checkpoint publication, so inspect storage before retrying; a successful repeat returns AlreadyCurrent. Existing cooperative ownership, path checks and durability limits apply. This is a conservative local format conversion, not timestamp-authority preservation, remote storage or a new whole-method upstream parity claim.

| Source | Git blob |
| --- | --- |
| [LocalMemoryRepository.scala](LocalMemoryRepository.scala) | 2fe3f34cf680f653f6c57fdef188e0a1804b3e50 |
| [LocalMemoryWatermarkMigrationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/LocalMemoryWatermarkMigrationSpec.scala) | 1ea5632f33688c97c783c82b36cad6278d7b88c4 |

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for these two identities, with no findings. Focused checks passed 44 tests in 719 ms. Full `scalafmtAll scalafmtSbt verify` passed in `watermark-migration-verify.log` with zero compiler warnings or failures; aggregate totals belong to the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).

## Unchanged persistence identities

Unchanged Git-normalized persistence identities (earlier changes remain in e4bd6b0; the four files changed for retention are preserved in b2ab23e and indexed in the retention table below):

| Source | Blob identity |
| --- | --- |
| [JsonBoundary.scala](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/json/JsonBoundary.scala) | [Current JSON identity](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/client/README.md#optional-seed-and-exact-request-rendering) |
| [LocalDailyMemoryStore.scala](LocalDailyMemoryStore.scala) | 2a8c2ddfc0a7a9940bd0da7ead4a8b6c16ed5ed7 |
| [LocalMemoryRepositorySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/LocalMemoryRepositorySpec.scala) | 6fc453b560fd36612ba3eb5470d75814edc0b36d |
| [MemoryFilesSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryFilesSpec.scala) | 80b616760f88a68fb8fb289cc87d079dcba7f259 |
| [MemoryJournalSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryJournalSpec.scala) | 3ab28f0738b396ef5b277febb514564059cc5bf8 |

DailyMemoryLease now delegates to the shared internal [WorkspaceLease](../storage/WorkspaceLease.scala) with the same memory ownership filename and interruption policy. The former lease and test-helper identities are preserved in e4dfc67; current extraction acceptance and identities belong to the [transcript persistence scope](../transcript/README.md). Existing memory ownership/regression suites remain required.

The previous AgentError.scala identity is preserved in `0349b1a`; its current identity and acceptance are in the completion-observer table in the [core agent contract](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md).

## Memory lifecycle admission

[MemoryTrigger](MemoryTrigger.scala) models Always, Never and Throttled with a validated [MemoryInterval](MemoryInterval.scala). The smart constructor normalizes zero duration to Always; negative, infinite and overflowing intervals fail through InvalidConfiguration. A positive interval is strictly smaller than Long.MaxValue nanoseconds. Its custom JSON codec requires an integral JSON number in that range, rejecting quoted numbers and fractional/coerced values. Policy, operation, scope and decision ADTs use derived codecs; these are Scala configuration DTOs, not Java wire adapters.

MemoryTriggerPolicy is immutable and fixed for a gate lifetime. Flush defaults to Always; maintenance defaults to 30 minutes. Creating a new in-memory gate starts fresh windows, including when changing policy; the persistent adapter below instead validates the policy stored at its address. Never disables only admission through that operation; it does not disable compactor offload or flush callbacks installed independently.

[MemoryTriggerGate.inMemory](MemoryTriggerGate.scala) creates one explicitly shared authority. Hosts reuse that capability across callers belonging to the same memory domain. Separate gates are separate owners, even for equal textual identities; there is no static process-global registry. User and Session scope variants carry optional identities; absent and blank values share an anonymous slot within that scope, while distinct nonblank strings retain whitespace and delimiters. User scope does not fall back to session identity. Agent and Global each represent a constant slot within the host-selected owner. The gate does not authenticate callers, select filesystem paths, or infer workspace/agent ownership.

Operation and scope form structural keys, so flush and maintenance remain independent in all four scopes. Always and Never return Admitted or Disabled without allocating reservations. Throttled admission samples Clock.nanoTime inside Ref.Synchronized.modifyZIO and atomically records the protected sample on the first or elapsed claim. Later calls return Throttled until the exact interval boundary. No epoch sentinel or wall-clock timestamp is used. Elapsed subtraction tolerates signed clock wraparound while actual elapsed time remains below 2^63 nanoseconds; arbitrary multi-century pauses are outside that horizon. Backward test-clock movement preserves a previous claim and fails closed.

cleanup(retention) samples the same monotonic clock under the same state authority. It removes only claims whose reserved interval has elapsed and whose age strictly exceeds retention. A shorter retention therefore cannot reopen an active interval. Claim renewal racing cleanup retains the new active reservation. Cleanup is an explicit host operation; no background fiber is started and entries remain until renewal or cleanup.

A claim records permission to start at the protected sample, not completed work or exclusive execution ownership. Slow jobs can overlap after the interval unless a separate execution service serializes them. Cancellation after an admitted transition can leave its reservation committed; no acknowledgement rollback is promised. The in-memory capability supplies local admission only; the persistent admission adapter below is separate. The scoped queue foundation described below now supplies owned execution and pending coalescing. Completion-triggered flush integration is described below. Owned consolidation integration and explicit acknowledged-daily archival are available below; combined retention maintenance is available below; persistent admission is defined below; distributed execution authority and session deletion remain pending.

## Current memory-trigger identities

Final formatted six-file scope against `73cd376`:

| Source | Git blob |
| --- | --- |
| [MemoryInterval.scala](MemoryInterval.scala) | 0332f778d040180cbea43bbb7744a077f3a1c122 |
| [MemoryTrigger.scala](MemoryTrigger.scala) | [Current normalized-scope identity](#versioned-memory-trigger-gate) |
| [MemoryTriggerGate.scala](MemoryTriggerGate.scala) | [Current normalized-scope identity](#versioned-memory-trigger-gate) |
| [MemoryIntervalSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryIntervalSpec.scala) | 66085f594567f5703712911b93a26d4f909ab845 |
| [MemoryTriggerGateSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryTriggerGateSpec.scala) | 6cc2e51535a30f857fceecf0710caa4f1fb5bacf |
| [MemoryTriggerSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryTriggerSpec.scala) | 0bdce255c3008037ffb077011130836c60c46b02 |

- [x] `review_fp` and `review_harness` approved the final six-file memory-trigger increment against `73cd376` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 552 core / 287 harness tests, including 23 new cases, with zero compiler warnings. No findings remain within typed finite intervals, strict numeric codecs, immutable policies, explicit shared ownership, atomic monotonic admission and active-window-safe cleanup. Scheduling, execution locks, remote authority and full memory configuration parity remain outside this scope.

- [x] `review_docs` approved the corrected memory-trigger documentation against `73cd376`: six identities, 23 tests, finite/monotonic admission, active-window cleanup, explicit owner sharing and conservative upstream applicability. Full verification passed 552 core / 287 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed.

## Versioned memory trigger gate

[VersionedMemoryTriggerGate.make](VersionedMemoryTriggerGate.scala) is a pure checked factory over one VersionedJsonStore, StorageAddress and immutable MemoryTriggerPolicy. maxAttempts defaults to eight and must be positive. The injected now effect defaults to Clock.instant; construction neither evaluates it nor reads storage. One private version-1 aggregate stores the fixed policy and structural operation/scope reservations. MemoryScope.normalized is shared with the in-memory gate, preserving anonymous-slot and exact nonblank identity behavior without public API changes to existing callers.

A throttled claim samples now once for the operation and preserves that sample across explicit CAS-conflict retries. BigInt nanosecond arithmetic compares persisted Instants without overflow; first/exact-expiry claims admit, earlier claims throttle, and backward clock movement keeps the prior window active. Each mutation retries only a definite ConditionalWrite.Conflict up to maxAttempts, rereading current reservations; no-op decisions skip publication. Exhaustion fails with MemoryStorageError. Underlying errors, cancellation and ambiguous acknowledgements propagate without replay; a write may already have committed.

Always returns Admitted and Never returns Disabled without reading storage or sampling the clock. Consequently persisted policy/schema validation applies only to throttled claims and cleanup, not those bypass paths. Cleanup samples once and removes only reservations whose admission interval has elapsed and whose age strictly exceeds retention; equality or an active window stays retained. Cleanup conditionally publishes the remaining aggregate rather than deleting a backend address. No timer, worker or additional deletion capability is installed.

Stored data requires the exact canonical version/policy/reservation shape, recursively unique JSON fields and valid Unicode. Reservation keys must be unique, normalized and associated with throttled operations. Policy mismatch or invalid stored data fails closed without resetting state. Hosts must consistently bind the same policy/address, protect backend lifetime and bound aggregate growth. A policy change requires a fresh persistent address; reopening an existing address with a changed policy fails validation on throttled claims/cleanup. Caller identities are correlation slots, not authentication. All readers/writers need trusted synchronized clocks: ahead skew can expire another writer's window early; backward skew extends throttling. This is admission, not an execution lock, completion record or exactly-once workflow claim. Backend replacement/deletion, untrusted clocks and external mutation invalidate those assumptions.

Migration is additive. Existing in-memory monotonic clock behavior and gate/scheduler signatures are unchanged; normalized-scope extraction preserves existing local decisions. Persistent wall-clock reservations are a separate private format and are not imported from process-local counters. This bounded read/CAS adaptation does not establish complete remote middleware/coordination parity or distributed task execution.

| Reviewed source/spec against 072164f | Git blob |
| --- | --- |
| [VersionedMemoryTriggerGate.scala](VersionedMemoryTriggerGate.scala) | `6524a8192d420d2203b0ea98670a6951abcafe2c` |
| [MemoryTrigger.scala](MemoryTrigger.scala) | `81f433996f2a048910e565bb8e8b7350610d248a` |
| [MemoryTriggerGate.scala](MemoryTriggerGate.scala) | `e98988db1861cb11983ff4c762c363913a51ea22` |
| [VersionedMemoryTriggerGateSpec.scala](../../../../../../../../../storage-postgresql/src/test/scala/com/weisgnosis/agents/storage/postgresql/VersionedMemoryTriggerGateSpec.scala) | `7bfd89a9b7f4ce8d7840ecc521be8d51f75c1e38` |

- [x] review_fp and review_invariants approved these four final identities against 072164f, with no unresolved findings.
- [x] Focused verification passes 16 existing groups and three new real PostgreSQL gate groups in 1.621 seconds.
- [x] versioned-memory-gate-verify.log passes formatting, fatal-warning compilation, four packages and 1,501 tests: 669 core / 726 harness / 86 MCP / 15 PostgreSQL / 5 examples, with zero compiler warnings or failed tests.
- [x] versioned-memory-gate-artifact-preparation.log passes all four archive/POM checks.
- [x] versioned-memory-gate-artifact-consumer.log passes standalone formatting, clean compilation and forked execution against four fresh module hashes; its existing selected API smoke does not construct the gate.
- [x] English inspection and all 2,764 local references in 78 Markdown documents pass with zero issues.
- [x] review_fp and review_invariants approved final combined source/documentation closure against 072164f and these four unchanged identities, with no unresolved findings.

## Scoped memory task queue

[MemoryTaskQueue.open](MemoryTaskQueue.scala) creates a host-owned queue with one serial worker and a positive maxPending capacity, defaulting to 256. Bind one queue to one memory owner and keep its Scope outside individual agent invocations. Independent queues can run concurrently. The queue does not select a workspace, identify tenants, install an agent hook, claim trigger slots, or invoke MemoryFlushManager automatically.

[MemoryConversationToken](MemoryTaskTicket.scala) allocates an opaque runtime UUID in ZIO. Reuse the token for repeated work on one conversation; mint separate tokens for distinct agent/session owners even if their textual identifiers match. Tokens, tickets and task outcomes are runtime capabilities, not durable checkpoint DTOs. A host rebuilding a wrapper must retain the original token and queue to retain coalescing/ownership behavior.

submit(token)(task) atomically registers a suspended by-name scoped effect and returns Accepted(ticket), or Rejected(Closed/Capacity). Capacity counts pending slots, with at most one additional running task. A new token is rejected at capacity; replacing an existing pending token is allowed. Replacement preserves its FIFO position and atomically settles the displaced ticket as Superseded. The running task is never replaced, even when it has the same token. Concurrent admission order is the STM commit order.

The worker claims pending work and executes each task in a separate scoped child fiber. Typed failures, defects and task self-interruption become Completed(Exit) outcomes without stranding later work. The ticket preserves full Cause information, including finalizer defects. Completed denotes a terminal claimed-task outcome; a claimed task cancelled before child launch can receive an interrupted Exit without having executed. Pending jobs cancelled during shutdown receive CancelledBeforeStart. Interrupting ticket.await only cancels that waiter. Hosts must inspect ticket outcomes; there is no automatic retry, logging-based failure suppression or durable result journal.

Admission and shutdown share STM authority. Closing first rejects new work and settles pending tickets, then interrupts the worker, joins task cleanup and closes the queue. It cancels pending work rather than draining it. awaitIdle can support graceful draining before closing, but it neither closes admission nor prevents new submissions; a host needing a drain barrier must first stop producers. Slow or deliberately uninterruptible task/finalizer code can delay closure. Task receipts and queue closure do not promise rollback of external effects.

Acquisition and shutdown-finalizer registration are masked, while the persistent worker is explicitly interruptible regardless of the acquisition caller's status. This distinction fixes the reproduced masked-acquisition shutdown defect. A worker terminal finalizer settles any task claimed before child registration and closes outstanding admission, so cancellation cannot leave an indefinitely pending ticket. Running state remains active until task scope cleanup completes; awaitIdle accounts for both queued and running work.

Tasks must not await their own ticket, another task on this serial queue, or the queue's idleness. Those dependencies deadlock. Read mutable host-owned conversation state inside the submitted effect when execution-time freshness is intended. Coalescing is safe only when newer work subsumes displaced work; compaction can remove earlier content, so preserve prefixes through offload/flush independently. This queue supplies the scheduling mechanism, not a proof that arbitrary user effects are coalescible or that memory lifecycle integration is complete.

## Current task-queue identities

Final formatted five-file scope against `ad12c7b`:

| Source | Git blob |
| --- | --- |
| [MemoryTaskQueue.scala](MemoryTaskQueue.scala) | 4d69eeb49216544655bde81c4f2474f2baf73eac |
| [MemoryTaskTicket.scala](MemoryTaskTicket.scala) | 11c7e15541e0cd9359d7e083d8fcf50f7067c012 |
| [MemoryTaskQueueLifecycleSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryTaskQueueLifecycleSpec.scala) | 4a5f14172ec4e73ea3a0c23465bf0ae037028c6f |
| [MemoryTaskQueueSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryTaskQueueSpec.scala) | 149fed57f1be5d7848be0543e9d885ad8b478abb |
| [MemoryTaskTicketSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryTaskTicketSpec.scala) | d2604a73f310fe10ef04f11da85f4833d241c0e7 |

- [x] `review_fp` and `review_harness` approved the final five-file scoped queue increment against `ad12c7b` on 2026-09-29. Twenty new tests reproduced and corrected masked-acquisition shutdown blocking; full `sbt scalafmtAll scalafmtSbt verify` passed 552 core / 307 harness tests with zero compiler warnings. No findings remain within bounded pending work, FIFO coalescing, runtime ticket outcomes, host-owned worker/task scopes and cancellation-safe cleanup. Real memory hooks, trigger/extractor integration, durable tasks and distributed scheduling remain outside this scope.

- [x] `review_docs` approved the corrected scoped queue documentation against `ad12c7b`: five identities, twenty new tests, bounded coalescing, explicit runtime ticket outcomes, host-owned cleanup and pending real middleware integration. Full verification passed 552 core / 307 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed.

## Completion-triggered memory flush

[MemoryFlushScheduler.make](MemoryFlushScheduler.scala) binds a manager, shared queue, shared trigger gate and MemoryScope to one new runtime conversation token. Reuse that scheduler for the same agent/session conversation; separate instances retain independent pending work even with equal visible user/session identities. The host selects the memory owner and keeps the queue/store scopes alive around all agent calls and accepted work.

Install scheduler.completionObserver(registerTicket) as the ReAct completion observer. It schedules Exit.Success outcomes, including cooperative recovery results whose AgentRunState is Interrupted; failed execution, timeout and external cancellation exits do not schedule work. The existing required observation boundary still controls admission/reporting before AgentResultEvent. Background extraction itself does not delay successful agent completion.

MemoryFlushManager now exposes one package-private pure preparation path shared by direct flush and scheduling. Preparation validates retained-user provenance and renders the eligible conversation exactly once. Empty, blank and internal-only windows yield MemoryFlushSubmission.Empty before queue admission, so they cannot replace useful pending work or consume trigger intervals. Invalid provenance fails before admission. A ready plan retains lazy date/storage/extraction effects; queued work claims the Flush gate and executes the prepared plan only for Admitted. Disabled/Throttled outcomes complete the task successfully without extraction. A successful ticket therefore confirms pipeline completion, not that a ledger append occurred.

The conversation is an immutable snapshot of the completed turn, prepared before queue admission. This deliberately differs from upstream reading live mutable agent history when a job starts: later reset or in-flight conversation mutation cannot alter an admitted snapshot. Replacing pending work selects the newer completed snapshot; it never replaces running work. Storage context, date and extraction are read when the prepared effect executes, so manager serialization still sees preceding ledger writes. Gate reservations remain consumed if subsequent extraction fails.

registerTicket is a required synchronous handoff callback returning IO[CompletionObservationError, Unit], with no short-lived Scope supplied by this API. The host must retain tickets and inspect them in its own lifetime. Requiring the callback does not guarantee inspection: a callback may discard a ticket. Do not wait for ticket completion inside the observer when asynchronous agent completion is required, and do not attach monitoring to the short invocation scope. A callback failure occurs after queue admission and cannot roll back the job; it fails required observation and suppresses AgentResultEvent, while the job can still finish. Closed/capacity queue rejection also becomes CompletionObservationError without discarding canonical conversation state. Background gate/extraction errors remain in ticket Exit values rather than failing an already completed agent invocation.

Compaction preservation is separate. Install manager.flush as the compactor's awaited flush callback, and transcript offload as appropriate, before deleting a prefix. Enqueueing the old prefix on this coalescing scheduler is not a preservation guarantee because newer work could replace it. The integration tests establish local daily-store reopen and awaited-prefix extraction; they do not prove semantic memory fidelity, exactly-once facts, crash durability, automatic receipt monitoring or maintenance scheduling.

The previous MemoryFlushManager.scala identity is preserved in `1c8e3ee`; the completion-triggered flush identity is preserved in 005bacf, and the current identity is in the linked exact-output acceptance.

## Current completion-triggered flush identities

Three unchanged identities from the four-file scope against `1c8e3ee`:

| Source | Git blob |
| --- | --- |
| [MemoryFlushScheduler.scala](MemoryFlushScheduler.scala) | 1d42d91f92ed0a46816f28746a4ed2b9b7d722b4 |
| [MemoryFlushSchedulerLifecycleSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryFlushSchedulerLifecycleSpec.scala) | bf9eecccbea5143e2c2aec09c688649d623e66b2 |
| [MemoryFlushSchedulerSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryFlushSchedulerSpec.scala) | 02da0630ca918cbf9935adc1fd6533b1a4b21df2 |

- [x] `review_fp` and `review_harness` approved the final four-file flush-scheduler increment against `1c8e3ee` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 552 core / 324 harness tests, including 17 new integration cases, with zero compiler warnings. No findings remain within shared pure preparation, successful-completion queue integration, immutable snapshots, worker-side triggers, ticket handoff, local reopen and awaited compactor flushing. Maintenance scheduling, automatic receipt monitoring, live-history reads and stronger durability remain outside this scope.

- [x] `review_docs` approved the corrected flush-scheduler documentation against `1c8e3ee`: four identities, seventeen new tests, immutable completed snapshots, explicit receipt handoff, four upstream runtime adaptations and pending maintenance work. Full verification passed 552 core / 324 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed.

## Completion-triggered consolidation

[MemoryConsolidationScheduler](MemoryConsolidationScheduler.scala) binds one consolidator, result callback, shared trigger gate and queue to a fresh owner token. Reuse one scheduler across conversations of that memory owner. Fresh schedulers do not coalesce with old tokens; sharing the same gate preserves trigger reservations. The owner must keep storage and queue scopes alive through all admitted work.

submit only admits work. The serial worker claims Maintenance, then reads the latest committed storage snapshot through the consolidator. Disabled/Throttled jobs complete successfully without consolidation or result reporting. Admission reserves the interval even when consolidation returns NoPendingMemories or later fails. A subsequent daily append does not bypass the reserved interval.

The mandatory report callback receives every returned ConsolidationResult: Committed, Conflict, EmptyResponse and NoPendingMemories. It runs after consolidator extraction cleanup. Its typed failure, defect or interruption remains in the task receipt; it cannot undo a completed commit. A Unit receipt does not retain a result that the host failed to record or distinguish consolidation failure from reporting failure. Failure, missing reports and interrupted receipts do not prove that storage stayed unchanged: cancellation can interrupt acknowledgement after mutation. Inspect storage before deciding whether to repeat extraction; there is no automatic retry or exactly-once reporting guarantee.

completionObserver(registerTicket) admits only successful execution Exit values, including cooperative recovery, and skips failed/external-cancellation exits. The required synchronous ticket handoff follows the flush scheduler contract: host-owned retention and inspection, no invocation-scope monitor, and no implicit inspection guarantee. Closed/capacity rejection or registration failure fails required completion observation and suppresses AgentResultEvent; registration failure occurs after admission and does not roll back the job. Background gate, consolidation and reporting failures remain in tickets after the agent invocation finishes.

TurnCompletionObserver.andThen composes lazy, fail-fast observations in separate scopes, closing the first before starting the next. Use it to combine transcript observation, flush admission and consolidation admission. This orders observer execution and admission, not execution dependencies between replaced jobs. With F1 running and M1 pending, the next flush F2 joins behind M1; replacing M1 with M2 preserves that earlier queue position. M2 can therefore consolidate F1 before F2 writes. This is opportunistic consolidation of currently committed daily data; F2 stays pending for a later maintenance trigger. A host requiring strict flush-then-consolidate execution must use one composite job or an external continuation after the flush ticket settles. Never await same-queue tickets inside the single worker.

This scheduler performs consolidation only. Explicit acknowledged-daily archival is available below. The combined scheduler below adds retention policy. Session-file pruning, distributed authority, durable work receipts and operational monitoring remain unfinished. The local reopen regression establishes persisted consolidation despite a failed report, not crash or power-loss durability.

## Current consolidation/composition identities

Current linked harness identities from the consolidation/composition increment against `3e817e3`; the changed scheduler identity is preserved in b3d2723 and indexed in the maintenance table below. MemoryMaintenanceScheduler also changed for coordinator support; its previous identity is preserved in b86fd14. Independent acceptance of the earlier scope is recorded below.

| Source | Git blob |
| --- | --- |
| [MemoryConsolidationSchedulerLifecycleSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryConsolidationSchedulerLifecycleSpec.scala) | 52cb68b9d9151cfca1924e09337af6f86f2df965 |
| [MemoryConsolidationSchedulerSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryConsolidationSchedulerSpec.scala) | 978a32d2c3a77dd93cc9b6e01a1e8213c1dcf5bd |
| [MemoryLifecycleIsolationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryLifecycleIsolationSpec.scala) | [Current shared oracle identity](#remote-middleware-throttle-integration) |
| [MemorySchedulerCompositionSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemorySchedulerCompositionSpec.scala) | 09dce79b7ea16b2e64c7c2720ad7a76feb28723a |

- [x] `review_fp` and `review_harness` approved the final seven-file consolidation/composition increment against `3e817e3` on 2026-09-29 after correcting nested test-scope ownership. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 346 harness tests, including 25 new cases, with zero compiler warnings. No findings remain within separately scoped observer composition, owned consolidation/result reporting, eight local throttle integration combinations, FIFO replacement and post-commit cancellation/failure. Retention, remote authority, strict cross-job dependencies and transactional reporting are excluded.

- [x] `review_docs` approved the corrected consolidation/composition documentation against `3e817e3`: seven identities, 25 new cases, scoped composition, reporting limits, FIFO ordering and conservative parity classifications. Full verification passed 555 core / 346 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed.

## Acknowledged daily-memory archival

[DailyMemoryRetention](DailyMemoryRetention.scala) is the repository's separate archive capability. archiveBefore(cutoff) selects only days strictly before the explicit LocalDate whose current content is already acknowledged by consolidation. Unconfirmed or subsequently changed days stay active regardless of age. The cutoff is caller-supplied: this port does not read the clock, choose a retention duration, install a scheduler or prune sessions. Curated MEMORY.md is not changed by retention, except that the shared local entry point must first finish an already published consolidation journal.

DailyMemoryArchive combines the original date with a validated MemoryFingerprint. Digests are canonical lowercase SHA-256 of valid UTF-8 text; malformed UTF-16 surrogate input fails rather than silently sharing the replacement-character digest. Consolidation fingerprints use the same implementation. Typed archive references cannot select arbitrary paths. archives lists recognized identities deterministically; readArchive reads a selected version. Local reads verify payloads against their fingerprint. Different contents for the same day coexist; identical acknowledged content reuses the same archive identity.

The in-memory authority moves selected content to an immutable archive map, removes active entries and acknowledgements, and advances its revision in one synchronized transition. Existing archive identities must match exactly. Empty passes do not advance state. The local authority runs under the existing lease after location validation and journal recovery. It first validates every selected source and destination; then publishes and verifies every archive copy. Only after copies are verified does it advance checkpoint generation and transaction identity, remove selected acknowledgements, and delete the corresponding verified active files. Unrelated acknowledgements remain intact.

Local archives live at `memory/archive/<date>_<fingerprint>.md`. The archive directory and recognized entries must be canonical non-symlink directories/regular files under the owned memory directory. Conflicting contents or nonregular targets fail before replacing anything. Cooperative ownership and explicit target checks govern publication; atomic file replacement alone is not an exclusive-create guarantee. The backend still requires a host-controlled workspace and ancestors.

Retention is not an atomic multi-file filesystem transaction. A failure before checkpoint advancement can leave reusable archive copies alongside acknowledged daily files. A failure after advancement can leave surviving daily files pending again, requiring reconsolidation before later archival. Sources are removed only after a verified copy exists; stale acknowledgements are never restored to conceal a failure. Equal-content recreation remains pending because the acknowledgement was removed and the generation advanced; old consolidation snapshots conflict. Failure or interruption may therefore have partial effects and an uncertain acknowledgement. Inspect archives and active snapshots before deciding how to proceed. No automatic retry, archive deletion, crash/power-loss durability or hostile-mutation protection is promised.

This deliberately hardens upstream expireDailyFiles, which archives old files before consolidation without checking acknowledgement. It is a source-level behavioral adaptation with new regressions, not a newly mapped upstream test method. Combined maintenance is implemented below; session pruning remains separate. The local intermediate-state tests seed states after copy publication and checkpoint advancement; they are not live I/O fault injection or process-crash tests.

Migration: MemoryRepository now exposes retention alongside its existing ports. Public open/make call sites remain compatible; callers opt into archival explicitly. Archive files are outside daily enumeration and cannot become consolidation input or implicit acknowledgement. The existing checkpoint format remains version 1; retention legitimately advances its generation while removing acknowledged days. Previous changed source identities are preserved in b2ab23e.

## Current retention identities

Final formatted seven-file increment against `b2ab23e`; independent acceptance is recorded below.

| Source | Git blob |
| --- | --- |
| [DailyMemoryRetention.scala](DailyMemoryRetention.scala) | b679d771b1aee3100d223816cde76c043927caec |
| [LocalMemoryRepository.scala](LocalMemoryRepository.scala) | Current identity: [explicit watermark migration](#explicit-watermark-migration) |
| [MemoryFiles.scala](MemoryFiles.scala) | 1b1bf9a91f118c1b99d2f891ad6c2a58b41b831d |
| [MemoryJournal.scala](MemoryJournal.scala) | cc8f693e7fda49876f11ff07e842716ead0828d6 |
| [MemoryRepository.scala](MemoryRepository.scala) | [Current shared-transition identity](#versioned-remote-memory) |
| [DailyMemoryRetentionSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/DailyMemoryRetentionSpec.scala) | 87ca3e833ba23868dfdbe59f66dbe7ad6d47c5df |
| [LocalDailyMemoryRetentionSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/LocalDailyMemoryRetentionSpec.scala) | 8a6b13eb2f2736f869bb99be1c8cabeba74c4449 |

- [x] `review_fp` and `review_harness` approved the final seven-file daily-retention increment against `b2ab23e` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 370 harness tests, including 24 new cases, with zero compiler warnings. No findings remain within typed archive identity, exact acknowledged eligibility, atomic in-memory retirement, verified local copy/checkpoint/removal ordering, conservative partial progress and strict shared fingerprinting. Automatic policy, session pruning, crash durability and hostile concurrent filesystem mutation are excluded.

- [x] `review_docs` approved the corrected daily-retention documentation against `b2ab23e`: seven identities, 24 new tests, archive ordering, migration and conservative partial-progress limits. Full verification passed 555 core / 370 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed. Automatic policy, session pruning and crash guarantees remain excluded, with no new named upstream test parity claimed.

## Daily retention maintenance

[MemoryRetentionPolicy](MemoryRetentionPolicy.scala) is KeepAll or ArchiveAfter(RetentionDays), with an explicit daily default of 90 positive calendar days. RetentionDays has a strict numeric codec and rejects zero, negative, fractional, string-coerced and out-of-Int values. The pure cutoff calculation returns an exclusive LocalDate or a typed range error; it never clamps underflow. This is daily archival policy, not session expiry or the complete upstream memory configuration.

[MemoryMaintenance.make](MemoryMaintenance.scala) takes one MemoryRepository and constructs its consolidator from that repository's consolidation port, retaining the same repository's retention port. It accepts an explicit zone (UTC by default), extractor and validated consolidation budget. The service calculates and validates one cutoff at execution start before any consolidation effects. Clock/date conversion errors and cutoff underflow fail through InvalidConfiguration. KeepAll bypasses clock/date conversion entirely. A run crossing midnight retains its original cutoff.

run consolidates first. Committed and NoPendingMemories may proceed to archival; EmptyResponse and Conflict defer it. Typed failures, defects or interruption in extraction/cleanup stop the sequence. NoPendingMemories can include blank unacknowledged entries, so it never grants deletion authority: the retention port independently requires exact acknowledgement. KeepAll yields DailyArchivalResult.Kept; an enabled pass yields Archived(cutoff, entries), even when entries is empty. MemoryMaintenanceResult uses separate Committed/NoPendingMemories cases carrying the archival result and bare EmptyResponse/Conflict cases, preventing deferred outcomes from carrying archival success.

[MemoryMaintenanceScheduler](MemoryMaintenanceScheduler.scala) runs the complete service under one Maintenance admission and one owned queue job, then invokes the required aggregate report callback. Queue, owner-token coalescing, observer registration and error semantics are shared through the private [MemoryMaintenanceTask](MemoryMaintenanceTask.scala). The existing MemoryConsolidationScheduler remains source-compatible and consolidation-only. Choose the combined scheduler for an owner requiring archival; installing both with the same gate/scope shares one Maintenance window, so the first can suppress the other's work. They are alternatives, not stages to compose as separate gated observers.

Scheduled date selection happens in the worker, not when the observer admits the job. A superseded pending task never samples its cutoff or runs maintenance. Disabled/throttled gates skip the entire service and result report. Existing flush-versus-maintenance independence remains intact; flush/maintenance observer composition still orders admission only, with the FIFO replacement limitations described above.

Consolidation, archival and aggregate reporting are sequential effects, not one transaction. Archive failure can follow a committed consolidation and produces no aggregate success report. Report failure can follow completed archival. Tickets retain the failed cause, but neither a failed receipt nor an absent report implies unchanged storage or supplies a durable phase/result journal. Inspect storage before retrying. The queue serializes scheduled owner work; direct concurrent service calls can interleave phases, with each repository CAS/lease/acknowledgement check retaining its individual guarantees. No extra cross-phase lock, exactly-once report or rollback promise is made.

This intentionally differs from upstream expiry-before-consolidation and swallowed maintenance failures. The stronger acknowledged-only archival capability and observable task failures are retained. Bounded local transcript discovery is available; remote discovery and scheduling remain unfinished; typed custom prompts and immutable configuration are implemented below. Prior MemoryConsolidationScheduler.scala is preserved in b3d2723.

## Current maintenance identities

Unchanged identities from the eight-file maintenance increment against `b3d2723`; MemoryRetentionPolicy changed to add the shared session default, with its previous identity preserved in f1a005e and its current identity in the transcript policy contract. MemoryMaintenanceScheduler also changed for coordinator support; its previous identity is preserved in b86fd14. Independent acceptance of the earlier scope is recorded below.

| Source | Git blob |
| --- | --- |
| [MemoryConsolidationScheduler.scala](MemoryConsolidationScheduler.scala) | 215b1a897951f7ae1be0259c8c45107c82822656 |
| [MemoryMaintenanceTask.scala](MemoryMaintenanceTask.scala) | 2adffe5b970b0824d0092a15a78acf19ebd09512 |
| [MemoryMaintenanceSchedulerSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryMaintenanceSchedulerSpec.scala) | d31c5ab3e0628f94d691ad4865c855d6671d4cc7 |
| [MemoryMaintenanceSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryMaintenanceSpec.scala) | bbf02388ef56d1918059869fa8e6a37558ac28cd |
| [MemoryRetentionPolicySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryRetentionPolicySpec.scala) | 775e786516e5201925ede608ed1f5a9d3981c43d |

- [x] `review_fp` and `review_harness` approved the final eight-file maintenance increment against `b3d2723` on 2026-09-29, including corrected consolidation-only ScalaDoc. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 391 harness tests, including 21 new cases, with zero compiler warnings. No findings remain within validated calendar policy, repository-bound sequential maintenance, typed aggregate outcomes, one owned gate/job and preserved consolidation-only behavior. Session pruning, unified configuration, cross-phase atomicity and durable reporting are excluded.

- [x] `review_docs` approved the corrected maintenance documentation against `b3d2723`: eight identities, 21 new tests, policy and shared-window contracts, reporting limits and incomplete configuration mappings. Full verification passed 555 core / 391 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed.

RetentionDays now supplies separately named DailyDefault (90) and SessionDefault (180) values. The [transcript policy contract](../transcript/README.md) owns the updated MemoryRetentionPolicy.scala identity and acceptance. Daily calendar-cutoff behavior is unchanged; transcript policy uses exact 24-hour Instant subtraction.

- [x] `review_fp` and `review_harness` approved the final five-file transcript-policy increment against `f1a005e` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 412 harness tests, including eight new cases, with zero compiler warnings. No findings remain within explicit 180-day policy, strict shared day validation, safe Instant arithmetic, one pre-ownership clock sample and complete KeepAll bypass. Batch/scheduler integration and complete upstream configuration parity are excluded.

## Selected transcript maintenance

[MemoryMaintenanceCoordinator](MemoryMaintenanceCoordinator.scala) prepares the transcript cutoff and checked selection before invoking MemoryMaintenance, then executes the ordered target batch and returns MaintenanceResult. Invalid transcript time arithmetic, discovery failure or duplicate target IDs fail before memory extraction or writes. Discovery cleanup finishes before that phase; payload parsing and writer-lease admission remain per-target pruning operations. Daily and transcript policies read time separately; this is not a shared cross-policy clock snapshot. Every returned memory outcome, including EmptyResponse and Conflict, permits the independent transcript phase. A failed or interrupted memory effect prevents that phase.

MemoryMaintenanceScheduler.make has an additive coordinator overload. The same private task claims Maintenance once, runs all phases in the owned serial queue, then invokes the required MaintenanceResult report. Install this overload instead of a competing daily-only or consolidation-only scheduler for the same gate/scope. Pending work coalesces under the scheduler owner token, and superseded work never prepares cutoffs. Gate suppression skips all phases and reporting. Existing observer registration and receipt semantics are unchanged.

Effects are sequential and preserve partial progress: a later target failure can follow committed memory and earlier deletions; aggregate reporting can fail after every phase succeeds. Later targets are skipped after failure or cancellation, and owned shutdown waits for current cleanup. An absent aggregate or failed receipt is not proof of unchanged storage. There is no cross-phase transaction, rollback, durable result journal or exactly-once guarantee. Direct coordinator calls may interleave; scheduled calls use queue ownership. Hosts provide explicit targets or opt into bounded local discovery, retain active writer leases, retire deleted logical sessions and monitor receipts.

The [transcript batch contract](../transcript/README.md) defines exact correlation IDs, capability ownership and replayable preparation. Bounded local discovery is available through TranscriptRetentionBatch.fromDiscovery; remote pruning remains pending; the six MemoryConfig method adaptations are completed below. This is a hardened source-level maintenance adaptation, not a new named upstream test mapping.

## Current batch and coordinator identities

Unchanged target/coordinator identities retain their reviewed scope. Current batch source/spec identities and additive discovery acceptance are in the [transcript contract](../transcript/README.md#bounded-local-transcript-discovery).

| Source | Git blob |
| --- | --- |
| [TranscriptRetentionTarget.scala](../transcript/TranscriptRetentionTarget.scala) | c6d59b3dfb5c1552724d1fe01bd7e396a64eced4 |
| [MemoryMaintenanceCoordinator.scala](MemoryMaintenanceCoordinator.scala) | 73e81f2ce7e33e37dabcaf94e009d6de397515c3 |
| [MemoryMaintenanceScheduler.scala](MemoryMaintenanceScheduler.scala) | bf449d58b9f02088c3ddc8f0be4237d2eaab3a09 |
| [TranscriptRetentionTargetSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/TranscriptRetentionTargetSpec.scala) | a8996c67dfabf98a6dd1e4a3516316f1b8c43a1f |
| [MemoryMaintenanceCoordinatorSchedulerSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryMaintenanceCoordinatorSchedulerSpec.scala) | fb7a06ce627b87980f17080500522b666fa7d697 |
| [MemoryMaintenanceCoordinatorSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryMaintenanceCoordinatorSpec.scala) | fe6a61638f8dc8a296d187929a57e69e8fa7e759 |

- [x] `review_fp` and `review_harness` approved the final eight-file batch/coordinator increment against `b86fd14` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 434 harness tests, including 22 new cases, with zero compiler warnings. No findings remain within explicit ordered capabilities, cutoff preparation, sequential partial progress and one owned maintenance job. Cross-phase rollback, exactly-once execution and complete configuration parity are excluded; additive discovery has separate acceptance.

- [x] `review_docs` approved batch/coordinator documentation against `b86fd14`: eight identities, 22 new cases, explicit ordering, cutoff preparation and partial-progress contracts. Full verification passed 555 core / 434 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed. Remote integration remains unfinished; current discovery acceptance is linked above.

## Memory model selection

Hosts already select the primary or a dedicated memory model through [MemoryExtractor.fromClient](MemoryExtractor.scala), then inject that extractor into flush and consolidation/maintenance construction. The adapter preserves the selected client/settings while removing tools and response formatting; [MemoryExtractorSpec](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryExtractorSpec.scala) verifies the actual forwarded request and scoped cleanup. This deliberately replaces Java MemoryConfig.model plus HarnessAgent's override-or-primary assembly with explicit capabilities. MemoryConfig remains serializable policy data and contains no live client. String-ID model registry resolution is unimplemented and would require a separate host contract; no new selection API or whole upstream method acceptance is claimed.

## Immutable memory configuration

[MemoryConfig](MemoryConfig.scala) composes optional flush/custom consolidation prompts, a validated token budget, MemoryTriggerPolicy, daily retention and transcript retention. None selects the existing default prompt; Some(text), including an empty flush string, is literal customization. Defaults retain 4,000 tokens, Always flush, 30-minute maintenance, 90 calendar-day archival and 180 fixed-day transcript expiry. Copies are independent immutable values. Derived JSON codecs represent Scala configuration, not upstream wire compatibility.

[MemoryTokenBudget](MemoryTokenBudget.scala) is the single 1..4000 authority. Its smart constructor and strict JSON-number codec reject invalid bounds, fractions, quoted numbers and overflow. Both legacy Int factories and configured consumers use it. Custom prompts never bypass actual TokenCounter enforcement before content/progress commit. Approximate character guidance is four times the validated budget (at most 16,000), not a second enforced limit.

[ConsolidationPrompt](ConsolidationPrompt.scala) has three literal fields: beforeTokens, betweenBudgets and afterCharacters. render inserts the token count and approximate character count at two fixed positions. It performs no printf, interpolation-language or brace parsing; percent signs and apparent format directives remain literal. The default renderer preserves the previous prompt wording. This intentionally replaces Java placeholder-count validation with a typed template shape, rather than accepting legacy format strings.

MemoryFlushManager.fromConfig consumes the flush prompt. MemoryConsolidator.fromConfig consumes the custom template and budget. MemoryMaintenance.fromConfig additionally consumes daily retention. All retain existing scoped extraction, semaphores and storage invariants. Hosts explicitly pass config.triggers to their shared MemoryTriggerGate and config.transcriptRetention to TranscriptRetentionBatch.make. No factory installs a gate, queue, scheduler or transcript catalog. A copied policy does not change existing authority; constructing a replacement gate starts new windows. Extractor injection continues to select the model; upstream model-registry configuration is not implemented.

Existing make calls remain source-compatible; optional custom prompt parameters are appended to consolidation/maintenance factories. Binary compatibility is not asserted. The previous MemoryFlushManager, MemoryConsolidator and MemoryMaintenance identities are preserved in 005bacf; their retired table rows above are replaced by the current identities below. See the [test mapping](../../../../../../../../../../docs/architecture/test-parity.md#daily-policy-and-configuration-method-adaptations) for all six bounded method adaptations.

## Current configuration identities

Final formatted ten-file increment against 005bacf; this table owns current identities for the changed components and their new regression suites.

| Source | Git blob |
| --- | --- |
| [MemoryTokenBudget.scala](MemoryTokenBudget.scala) | ea4942bf39b8a4e0a4ab97b9ce48026d6fbc9f6b |
| [ConsolidationPrompt.scala](ConsolidationPrompt.scala) | 267aed5a80ab496b532fe17f540854ebea6f2efd |
| [MemoryConfig.scala](MemoryConfig.scala) | 43fc031c469ef8275619d8af97213722f677c624 |
| [MemoryConsolidator.scala](MemoryConsolidator.scala) | 9bda529fc30cd8f7999d5e86e63a5a5f84f876a8 |
| [MemoryMaintenance.scala](MemoryMaintenance.scala) | 55ef865e4cf556e932a2b94f9c0ea0bf5a3ce827 |
| [MemoryFlushManager.scala](MemoryFlushManager.scala) | [Current identity](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/json/README.md#exact-domain-and-tool-output) |
| [MemoryTokenBudgetSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryTokenBudgetSpec.scala) | 2b43e152c7ea5a14289e0cbbdca2790c43a0e182 |
| [ConsolidationPromptSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/ConsolidationPromptSpec.scala) | 4863fa628c7e10b034db9c6e187cd87042ecfebf |
| [MemoryConfigSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryConfigSpec.scala) | 4cfce91bf016eaaa72c6b5f73c6c84c664c050e2 |
| [MemoryConfigIntegrationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryConfigIntegrationSpec.scala) | 1fcd6e5da103fcd01bff6a1d3ae163386e35f869 |

- [x] `review_fp` and `review_harness` approved the final ten-file memory-configuration increment against `005bacf` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 451 harness tests, including 17 new cases, with zero compiler warnings. No findings remain within immutable policy values, strict budgets, total prompt rendering, configuration factories and the six explicit upstream method adaptations. Java builder/null/printf compatibility, live model fields, string-ID model registry resolution and automatic gate/queue installation are excluded.

- [x] `review_docs` approved corrected memory-configuration documentation against `005bacf`: ten identities, 17 new cases, preserved defaults, literal template semantics and all ten configuration-method classifications with explicit differences. Full verification passed 555 core / 451 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed. No findings remain in this scope.

Earlier AgentErrorSpec.scala identities are preserved in 083617b and 279b7a3; the [current lineage table](../subagent/README.md#current-lineage-identities) records their replacements and renewed acceptance.

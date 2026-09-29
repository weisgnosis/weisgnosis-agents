# Conversation compaction

ConversationCompactor accepts an effectful summarizer, a pure TokenCounter, and optional flush/offload callbacks. Defaults: triggerTokens=120000, keepTokens=40000, preserveTailMessages=6, maxPruneOutputChars=2000, protectTokens=40000. Valid histories below the trigger remain unchanged. Compaction prunes older payloads, preserves matched call/result pairs and protected tails, deduplicates summaries, and coalesces adjacent user messages. Retained-user provenance survives coalescing for later flushing. MemoryFlushManager implements daily extraction through an injected store; scoped local storage, atomic in-memory consolidation, and recoverable local consolidation are available.

## Pairing and pruning

Each call occurrence enters an immutable pending sequence. Inline results consume only eligible provider-hosted calls from their own assistant message. Remaining calls match subsequent Role.Tool observations once, by ID and by name when supplied; unnamed results consume the first matching occurrence. An unmatched provider-marked call uses the same name-sensitive local matching. Inline observations cannot steal a pending local call from the same or an earlier assistant message.

Occurrence spans are computed once. A cutoff crossing any matched span moves backward to its assistant message; leading orphan Tool messages move forward into the prefix. This preserves repeated IDs, identical parallel calls, reversed named completion, and pairs across intervening messages. It does not validate all malformed input histories or guarantee completeness of unrelated preexisting unmatched calls/results; the installed HistoryPreparer validates actual changed histories before runtime commit.

Older text output is pruned while retaining non-text blocks and execution metadata/state. Oversized unsigned arguments are replaced by a preview object, and their cached content is cleared. The presence of either thoughtSignature or reasoningDetails protects arguments and cached content from pruning, including malformed or JSON-null metadata values. Identity, state, metadata, protected tails, and below-trigger histories remain unchanged. Token thresholds trigger best-effort reduction; preservation rules can retain large signed arguments.

Prior summaries enter summarization once but never raw-memory flushing. A replacement summary is coalesced with an initial retained user turn, with validated provenance for later flushing. System messages are preserved. Typed hook/summarizer failures propagate, cancellation remains cancellation, and completed external flush/offload effects cannot be rolled back.

## Implementation

- [ConversationCompactor](ConversationCompactor.scala)
- [CompactorConfig](CompactorConfig.scala)
- [TokenCounter](TokenCounter.scala)

See the [test index](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/README.md), [tracker](../../../../../../../../../../docs/architecture/tracker.md), and [parity audit](../../../../../../../../../../docs/architecture/test-parity.md). Prior provenance and message-migration acceptance is preserved in Git through `6ed928a`.

## Runtime installation

ConversationCompactor.preparer adapts compactIfNeeded to core HistoryPreparer and normalizes typed failures into HistoryPreparationError. Install it with ReActAgent.make(historyPreparer = compactor.preparer) or Builder.historyPreparer. Preparation runs in a nested scope before each model request, after prior acting work completes. Runtime validation preserves ordered system messages and complete tool pairing for actual rewrites; unchanged candidates retain the permissive supplied-history API. A changed candidate retaining unmatched calls/results is rejected without committing history. Pairing does not prove summary fidelity, and completed external hooks cannot be rolled back.

[ConversationCompactorIntegrationSpec](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/ConversationCompactorIntegrationSpec.scala) verifies cancellation after history shrinkage, typed summary failure without Auto fallback, hook cancellation cleanup, and chained preparation preserving a single summary and paired tool observations. The shared core ToolHistory analysis replaces the prior local cutoff matcher without changing its reviewed occurrence semantics.

## Failure policy and session ownership

Preparation errors retain original canonical input and stop model dispatch. This intentionally differs from upstream fallback to original input or a failure-text summary. ZIO Cause represents interruption directly; the implementation does not traverse wrapped or cyclic Throwable chains. Pure interruption produces one recovery, while mixed interruption/cleanup defects remain failures without a successful recovery result. Successful preparation is committed before downstream model execution; a later interruption does not undo that commit.

A compactor can be shared, subject to its injected dependencies' concurrency contracts. Each ReActAgent owns one history and serializes its runs. The concurrent tests use separate agents sharing a compactor/client; they do not establish a multi-session API on one agent.

ToolResultEvictor.preparer can precede ConversationCompactor.preparer via HistoryPreparer.andThen. Changed stages are validated before the next runs; runtime commits once after scope cleanup. See the [eviction contract and migration](../eviction/README.md).

## Current review acceptance

- [x] `review_fp` and `review_harness` approved the two test-file diff against `52da827` on 2026-09-29, with no findings. Thirteen additional cases and strengthened existing assertions cover exact summary/flush/tail routing, short-circuiting, preparation and downstream cancellation, mixed causes, and separate-agent isolation.
- [x] Full `sbt scalafmtAll scalafmtSbt verify` passed 529 core and 114 harness tests, including 39 compactor tests, with zero compiler warnings. Production code is unchanged in this increment.
- [x] All seven compactor and ten middleware methods have explicit [applicability decisions](../../../../../../../../../../docs/architecture/test-parity.md#compaction-method-applicability). Failure-policy divergences, summary identity, structured causes, session ownership, and the cyclic-Throwable exclusion are explicit; literal middleware equivalence is not claimed.
- [x] Complete the bounded local consolidation acceptance recorded below.

Previous implementation/hardening approvals remain in Git through `52da827`. Current reviewed test blobs (Git-normalized):

| Source | Blob identity |
| --- | --- |
| [ConversationCompactorSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/ConversationCompactorSpec.scala) | 0788a195d6c0d15d232becf68de7d252e047088e |
| [ConversationCompactorIntegrationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/ConversationCompactorIntegrationSpec.scala) | 09c7ace7c4c4180baaf754774afa0e08a3be39fc |

## Daily-memory extraction

[MemoryFlushManager](MemoryFlushManager.scala) is constructed effectfully with `make(store, extractor, zone, prompt)`. Its `flush(messages)` can be passed directly as the compactor flush callback. [DailyMemoryStore](DailyMemoryStore.scala) exposes only readCurated, readDaily(LocalDate), and appendDaily(LocalDate, entry). Its contract targets memory/YYYY-MM-DD.md; no arbitrary path or curated write capability reaches the manager. Hosts choose an injected implementation or LocalDailyMemoryStore.open. The interface itself does not guarantee disk durability or concurrency behavior; the local backend contract is below.

The manager expands validated retained-user provenance before excluding summary text, system/internal context, thinking, and nontext media. Tool arguments receive 500-character previews and each textual tool result receives a 1,000-character preview. It reads curated/daily context, sends two messages to extraction, closes the extraction scope, then appends a timestamped Markdown section. Blank output and trimmed exact NO_REPLY skip append. Other output is preserved verbatim; semantic quality, prompt safety, and deduplication are not mechanically guaranteed.

One Clock instant and explicit zone (UTC default) bind the context lookup, entry timestamp, and append date even if extraction crosses midnight. One manager serializes the complete read/extract/append sequence. Share the manager for a ledger owner; separate managers, stores, processes, and external curated writers require host coordination. Failed appends are not retried automatically and may have backend-defined partial effects; completed external writes cannot be rolled back.

[MemoryExtractor](MemoryExtractor.scala) accepts a lazy scoped host function. Its fromClient adapter creates request correlation and a scoped event hub, sends no tools or response formatting, and returns visible text. Tool requests/observations fail; interrupted outputs remain interruption. The caller closes the scope. Manager extraction failures/cancellation/cleanup defects skip append. Typed storage reads fail explicitly instead of upstream's silent empty-context fallback.

- [x] `review_fp` and `review_harness` approved the seven-file daily-memory extraction increment against `738b216` on 2026-09-29. No findings remain within the injected-store capability, scoped client extraction, provenance/date handling, one-manager serialization, and compactor integration. Full formatting/fatal-warning verification passed 529 core / 136 harness tests, including 22 new tests. Filesystem persistence, cross-manager/process guarantees, consolidation, watermarks, and transcript offloading are excluded.
- [x] Full formatting/fatal-warning verification passed 529 core / 136 harness tests, including 22 new daily-memory tests and all 39 compactor regressions.
- [ ] Add remote memory storage, transcript offloading, watermark migration, and scheduling.

Current daily-memory Git-normalized source identities; prior compactor implementation identity is preserved in `738b216`:

| Source | Blob identity |
| --- | --- |
| [ConversationCompactor.scala](ConversationCompactor.scala) | d2d9fc060cd2c5a5108b8da2bd8d731cdf4acbb6 |
| [DailyMemoryStore.scala](DailyMemoryStore.scala) | 204a494090531403e1a7b2f3108787770a9bd5a0 |
| [MemoryFlushManager.scala](MemoryFlushManager.scala) | 5ceee51cb042628041b6c4745fac9429b215eb81 |
| [MemoryExtractorSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryExtractorSpec.scala) | 2db567e78995479fd0c733f1346db8720d5df566 |
| [MemoryFlushManagerSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryFlushManagerSpec.scala) | 79446b9c488527138681526e79d777895f0d3bca |
| [MemoryFlushManagerIntegrationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryFlushManagerIntegrationSpec.scala) | f97c09f6e95790fbac180608591f1d22bd3b6b2c |

## Scoped local daily store

[LocalDailyMemoryStore.open](LocalDailyMemoryStore.scala) returns ZIO[Scope, AgentError, DailyMemoryStore] and requires WorkspaceTrust.HostControlled. The host must protect the workspace and every ancestor against uncoordinated mutation. Untrusted is rejected before filesystem access. The existing workspace is screened for traversal/sensitive paths and canonicalized; memory is created if absent and must be a canonical non-symlink directory. Payload targets must be regular non-symlink files within their expected parent. The daily capability can propose writes only to memory/<ISO-date>.md. Daily append preserves curated content even when a daily file was originally hard-linked to it. This entry point delegates to LocalMemoryRepository: opening or using the daily capability may publish a previously journaled curated replacement during recovery; it is not a globally read-only curated path.

[DailyMemoryLease](DailyMemoryLease.scala) atomically creates memory/.daily-writer.lease with an opaque owner token using CREATE_NEW. Competing acquisitions never remove or overwrite it. One store serializes admitted operations; its scope waits for them, marks the handle Closed, verifies the token, then deletes the lease. Queued operations can be cancelled without entering. Admitted filesystem work runs with interruption masked; ordinary pending cancellation is delivered afterward, while an explicitly uninterruptible caller retains its policy. Slow filesystem operations can delay cancellation and scope closure. Handles escaped from a closed scope fail with MemoryStorageError.

Append builds the full old-plus-new UTF-8 content in a scoped temporary file and publishes it with ATOMIC_MOVE. No non-atomic fallback is allowed. Providers may reject atomic replacement of an existing target; the resulting error remains typed. Temporary-file cleanup failures are logged; ownership cleanup failures surface as finalizer defects and keep the handle closed. The real Windows tests verify repeated replacement, complete concurrent appends, and preservation of hard-linked curated content.

Atomic publication is not fsync/crash durability or exactly-once delivery. Cancellation or failure may be observed after a complete append was published. A failed acquisition or abrupt process termination can leave a lease, and abandoned temporary files may remain after a crash. No automatic stale-lease detection or removal occurs. For recovery, the operator must establish that every previous owner has stopped, verify the canonical workspace, and only then remove the stale .daily-writer.lease. Other writers must honor this ownership protocol; canonical checks and token verification are not an atomic sandbox or filesystem-object identity guarantee.

The protocol uses the Java [CREATE_NEW contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/StandardOpenOption.html#CREATE_NEW) and [atomic move semantics](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption...)). These guarantees are distinct from application-level extraction deduplication. Share one MemoryFlushManager per store owner to serialize the complete context-read/extract/append sequence.

- [x] `review_fp` and `review_harness` approved the final seven-file local daily-memory backend against `d1a4cf2` on 2026-09-29. Nineteen added tests exposed and corrected pending cancellation being reported as success after admitted work. Full formatting/fatal-warning verification passed 530 core / 154 harness tests. Acceptance covers cooperative ownership, scoped cleanup, day-derived paths, atomic replacement, and typed storage errors on the tested Windows filesystem. Crash durability, exactly-once delivery, hostile mutation protection, dynamic symlink/junction behavior, and automatic stale-lease recovery are excluded.
- [ ] Verify cross-process contention and dynamic symlink/junction cases on each supported platform; the current executable contention checks use independent store instances in one JVM.
- [ ] Implement transcript/scheduling services and remote memory storage. The earlier daily-only increment added no new upstream method-parity claim.

Current local-backend Git-normalized identities:

| Source | Blob identity |
| --- | --- |
| [AgentErrorSpec.scala](../../../../../../../../../core/src/test/scala/com/weisgnosis/agents/core/error/AgentErrorSpec.scala) | 1f914bd470342b0c7db3dbb9202de0ac7096e171 |
| [LocalDailyMemoryStoreSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/LocalDailyMemoryStoreSpec.scala) | 232a08a6ac50c6e4e32873cee36752765b4f51fb |
| [DailyMemoryLeaseSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/DailyMemoryLeaseSpec.scala) | 09a2cda7bb306becd4718b3e49f5e65a0dedd321 |

## Atomic consolidation and process-local storage

[ConsolidationStore](ConsolidationStore.scala) exposes an abstract path-dependent Revision, an atomic ConsolidationSnapshot, and commit(expected, content). Each snapshot includes current curated text and at most one complete pending entry per day. Every curated/daily/progress mutation must advance a never-reused revision. Commit must atomically update curated content and acknowledge exactly the consumed snapshot, or return Conflict without any change. The port has no daily append operation. Backend errors propagate; callers cannot infer rollback from an error or cancellation after a commit may have occurred.

[MemoryRepository.inMemory](MemoryRepository.scala) returns daily and consolidation capabilities sharing one immutable Ref state. BigInt revisions cannot wrap; the public capability keeps their representation abstract. Appending daily content invalidates older consolidation snapshots. A successful conditional transition updates curated text and acknowledged inputs together. Later appends make the full changed day pending again, allowing the model to deduplicate against existing curated text. Raw daily content is never rewritten by consolidation. This repository is process-local and has no disk fallback or crash persistence.

[MemoryConsolidator.make](MemoryConsolidator.scala) validates maxTokens in 1..4000 and accepts a MemoryExtractor and TokenCounter. consolidate serializes calls on that consolidator, rejects duplicate snapshot days, skips blank/no-pending input, and sends deterministic chronological daily content alongside current curated text. Extraction scope closes before output validation or commit. Blank output returns EmptyResponse without acknowledgement; a validated replacement returns Committed or Conflict. Conflict does not automatically retry stale output; a later invocation must obtain a fresh snapshot and recompute.

The output budget is enforced against counter.count(Msg.assistant(strippedOutput)); negative counts and over-budget content fail without commit. TokenCounter.approximate estimates serialized message size, including its envelope. Applications requiring exact model token limits must inject an appropriate pure tokenizer. The budget applies to new replacements; supplied existing curated content is not retroactively truncated. Semantic factual accuracy, extraction quality, and deduplication are not guaranteed by structural/counter validation.

MemoryExtractor.fromClient also serves consolidation, preserving scoped text-only requests and rejecting tool observations. A MemoryFlushManager receives only the repository's daily writer; MemoryConsolidator receives only its curated writer. Hosts are trusted capability owners. Separate consolidator instances can extract concurrently, but only one can commit against a given revision.

The recoverable local implementation is described below; its journal and shared ownership gate supply the missing protocol around separate atomic file replacements.
- [ ] Complete the two remote filesystem mappings, timestamp-watermark migration, and transcript/scheduling integration. The earlier process-local increment did not establish filesystem equivalence.

- [x] `review_fp` and `review_harness` approved the final six-file consolidation increment against `46c6174` on 2026-09-29. Twenty-one new tests and full formatting/fatal-warning verification passed 530 core / 175 harness tests. No findings remain within process-local atomic content/progress commits, separate writer capabilities, scoped extraction, configured-counter budget enforcement, and conflict handling. Filesystem transactions, persistent watermarks, crash recovery, and the five upstream filesystem mappings remain outside this scope.

The previous MemoryExtractor comment identity is preserved in `46c6174`; its current scope is included below. Current Git-normalized consolidation identities:

| Source | Blob identity |
| --- | --- |
| [ConsolidationStore.scala](ConsolidationStore.scala) | bc4d87299b785335051b8c44553c0c3670b859dd |
| [MemoryConsolidator.scala](MemoryConsolidator.scala) | 016e5611a60974cc47ac44e3bef109c573779be2 |
| [MemoryExtractor.scala](MemoryExtractor.scala) | fe620774f366965032f4582046b8fc79282ec164 |
| [MemoryRepositorySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryRepositorySpec.scala) | 090804a4d20f01d838c8d7e381175287374cadc2 |
| [MemoryConsolidatorSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryConsolidatorSpec.scala) | 49f460b8ba9e77b202e146b50a0c6b9b561445b1 |

## Recoverable local consolidation

[LocalMemoryRepository.open](LocalMemoryRepository.scala) returns a scoped MemoryRepository for a host-controlled workspace. Share its daily and consolidation ports instead of opening separate owners. Both ports use the existing .daily-writer.lease, canonical location checks, and the same admission gate. [MemoryFiles](MemoryFiles.scala) derives every destination from an internal typed identifier; journal data never selects a path. Directory enumeration considers canonical ISO-date Markdown names and ignores metadata, temporary files, and unrelated names; a nonregular date-matching entry fails explicitly.

[MemoryJournal](MemoryJournal.scala) validates complete JSON documents, duplicate/unknown keys, numeric representation, supported versions, canonical dates/transaction identifiers, fingerprints, and generation transitions before recovery writes. Shared library-internal JsonBoundary supplies document/duplicate validation. Checkpoints retain a BigInt generation, transaction identity, and acknowledged SHA-256 daily content digests. Revisions bind curated presence/content, checkpoint, and current daily fingerprints; successful same-content commits still advance generation across reopen. Progress assumes cooperative append-only daily writes and SHA-256 collision resistance; external edits are outside the ownership contract.

A conditional commit first compares the current snapshot. Conflict publishes no candidate. On success it atomically publishes memory/.consolidation_journal, then replaces MEMORY.md and memory/.consolidation_state. Every open/read/append/snapshot/commit first recovers under the same lease. Recovery accepts each component only in its recorded old or new state and validates all daily inputs before its first write. Absent curated content differs from a present empty file. Unexpected data fails closed and retains the journal. Missing new components are published, both are re-read and verified, and only then is the journal removed. Raw daily ledgers remain unchanged by consolidation.

Journal publication commits the candidate to roll-forward recovery. An error or cancellation after publication can have uncertain acknowledgement: a later operation may complete the candidate. Inspect the recovered snapshot before deciding whether a new extraction is needed; do not automatically replay model output. API users honoring the shared lease observe coherent content/progress. Uncoordinated raw file readers can observe intermediate files. There is no fsync/power-loss durability, raw multi-file atomicity, exactly-once extraction, automatic stale-lease removal, or hostile-filesystem guarantee. The existing operator procedure for abandoned leases still applies before reopening.

Migration: .consolidation_state is the upstream path, but its versioned JSON contents deliberately replace timestamp-only watermarks. Legacy timestamp content is rejected by consolidation without reset or overwrite; no timestamp migration or remote filesystem adapter is implemented. Daily-only open remains source compatible but participates in recovery and therefore may publish an already journaled curated transaction. The former LocalDailyMemoryStore and MemoryRepository identities are preserved in e4bd6b0.

- [x] `review_fp` and `review_harness` approved the final nine-file persistence increment against `e4bd6b0` on 2026-09-29. No findings remain within cooperative local ownership, strict journal validation, recovery before API operations, conditional content/progress publication, and persisted generation tracking. Full `sbt scalafmtAll scalafmtSbt verify` passed 530 core / 209 harness tests, including 34 new persistence tests, with zero compiler warnings. Acceptance covers constructed interrupted-publication states on the tested Windows filesystem; live crash/fault-injection, fsync durability, remote storage, and hostile mutation are excluded.
- [x] `review_docs` approved the final persistence documentation against `e4bd6b0`, including nine current identities, 34 new cases, three local upstream adaptations, two pending remote scenarios, migration, and durability limits. All 59 Markdown files passed local link/script checks; git diff --check passed.
- [ ] Add remote storage/migration, transcript persistence, and scheduling; verify cross-process and dynamic path behavior on each supported platform.

Current Git-normalized persistence identities (previous changed identities remain in e4bd6b0):

| Source | Blob identity |
| --- | --- |
| [JsonBoundary.scala](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/json/JsonBoundary.scala) | 12240a5fcf0d9e5d46dc2eb67963a5a963a003e5 |
| [LocalDailyMemoryStore.scala](LocalDailyMemoryStore.scala) | 2a8c2ddfc0a7a9940bd0da7ead4a8b6c16ed5ed7 |
| [LocalMemoryRepository.scala](LocalMemoryRepository.scala) | 9059a5c65117261b5b24d2d9d22c340685364cfe |
| [MemoryFiles.scala](MemoryFiles.scala) | cc7cd93dc6c64b40a2b956ed1341223ea7cf6521 |
| [MemoryJournal.scala](MemoryJournal.scala) | 443dc330686ac166d5146852bf872d221531c3db |
| [MemoryRepository.scala](MemoryRepository.scala) | 83df0fbf056c634b5f7cb44642a4c213f5ab7c0b |
| [LocalMemoryRepositorySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/LocalMemoryRepositorySpec.scala) | 6fc453b560fd36612ba3eb5470d75814edc0b36d |
| [MemoryFilesSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryFilesSpec.scala) | 80b616760f88a68fb8fb289cc87d079dcba7f259 |
| [MemoryJournalSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryJournalSpec.scala) | 3ab28f0738b396ef5b277febb514564059cc5bf8 |

DailyMemoryLease now delegates to the shared internal [WorkspaceLease](../storage/WorkspaceLease.scala) with the same memory ownership filename and interruption policy. The former lease and test-helper identities are preserved in e4dfc67; current extraction acceptance and identities belong to the [transcript persistence scope](../transcript/README.md). Existing memory ownership/regression suites remain required.

The previous AgentError.scala identity is preserved in `0349b1a`; its current identity and acceptance are in the completion-observer table in the [core agent contract](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md).

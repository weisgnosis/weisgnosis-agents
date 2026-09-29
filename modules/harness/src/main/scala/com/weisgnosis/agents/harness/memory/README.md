# Conversation compaction

ConversationCompactor accepts an effectful summarizer, a pure TokenCounter, and optional flush/offload callbacks. Defaults: triggerTokens=120000, keepTokens=40000, preserveTailMessages=6, maxPruneOutputChars=2000, protectTokens=40000. Valid histories below the trigger remain unchanged. Compaction prunes older payloads, preserves matched call/result pairs and protected tails, deduplicates summaries, and coalesces adjacent user messages. Retained-user provenance survives coalescing for later flushing. MemoryFlushManager implements daily extraction through an injected store; filesystem persistence and MemoryConsolidator remain pending.

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
- [ ] Implement filesystem daily-memory storage and curated-memory consolidation, with separate writer authority and budget enforcement.

Previous implementation/hardening approvals remain in Git through `52da827`. Current reviewed test blobs (Git-normalized):

| Source | Blob identity |
| --- | --- |
| [ConversationCompactorSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/ConversationCompactorSpec.scala) | 0788a195d6c0d15d232becf68de7d252e047088e |
| [ConversationCompactorIntegrationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/ConversationCompactorIntegrationSpec.scala) | 09c7ace7c4c4180baaf754774afa0e08a3be39fc |

## Daily-memory extraction

[MemoryFlushManager](MemoryFlushManager.scala) is constructed effectfully with `make(store, extractor, zone, prompt)`. Its `flush(messages)` can be passed directly as the compactor flush callback. [DailyMemoryStore](DailyMemoryStore.scala) exposes only readCurated, readDaily(LocalDate), and appendDaily(LocalDate, entry). Its contract targets memory/YYYY-MM-DD.md; no arbitrary path or curated write capability reaches the manager. Hosts supply the current store implementation. Disk durability, filesystem safety, and cross-process append guarantees are not implemented by this interface.

The manager expands validated retained-user provenance before excluding summary text, system/internal context, thinking, and nontext media. Tool arguments receive 500-character previews and each textual tool result receives a 1,000-character preview. It reads curated/daily context, sends two messages to extraction, closes the extraction scope, then appends a timestamped Markdown section. Blank output and trimmed exact NO_REPLY skip append. Other output is preserved verbatim; semantic quality, prompt safety, and deduplication are not mechanically guaranteed.

One Clock instant and explicit zone (UTC default) bind the context lookup, entry timestamp, and append date even if extraction crosses midnight. One manager serializes the complete read/extract/append sequence. Share the manager for a ledger owner; separate managers, stores, processes, and external curated writers require host coordination. Failed appends are not retried automatically and may have backend-defined partial effects; completed external writes cannot be rolled back.

[MemoryExtractor](MemoryExtractor.scala) accepts a lazy scoped host function. Its fromClient adapter creates request correlation and a scoped event hub, sends no tools or response formatting, and returns visible text. Tool requests/observations fail; interrupted outputs remain interruption. The caller closes the scope. Manager extraction failures/cancellation/cleanup defects skip append. Typed storage reads fail explicitly instead of upstream's silent empty-context fallback.

- [x] `review_fp` and `review_harness` approved the seven-file daily-memory extraction increment against `738b216` on 2026-09-29. No findings remain within the injected-store capability, scoped client extraction, provenance/date handling, one-manager serialization, and compactor integration. Full formatting/fatal-warning verification passed 529 core / 136 harness tests, including 22 new tests. Filesystem persistence, cross-manager/process guarantees, consolidation, watermarks, and transcript offloading are excluded.
- [x] Full formatting/fatal-warning verification passed 529 core / 136 harness tests, including 22 new daily-memory tests and all 39 compactor regressions.
- [ ] Add a filesystem store, curated consolidation with the 4,000-token budget, transcript offloading, watermarks, and scheduling.

Current daily-memory Git-normalized source identities; prior compactor implementation identity is preserved in `738b216`:

| Source | Blob identity |
| --- | --- |
| [ConversationCompactor.scala](ConversationCompactor.scala) | d2d9fc060cd2c5a5108b8da2bd8d731cdf4acbb6 |
| [DailyMemoryStore.scala](DailyMemoryStore.scala) | 204a494090531403e1a7b2f3108787770a9bd5a0 |
| [MemoryExtractor.scala](MemoryExtractor.scala) | 30164f03c2e9644ed674ad8abb70fd8dd84e5583 |
| [MemoryFlushManager.scala](MemoryFlushManager.scala) | 5ceee51cb042628041b6c4745fac9429b215eb81 |
| [MemoryExtractorSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryExtractorSpec.scala) | 2db567e78995479fd0c733f1346db8720d5df566 |
| [MemoryFlushManagerSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryFlushManagerSpec.scala) | 79446b9c488527138681526e79d777895f0d3bca |
| [MemoryFlushManagerIntegrationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/memory/MemoryFlushManagerIntegrationSpec.scala) | f97c09f6e95790fbac180608591f1d22bd3b6b2c |

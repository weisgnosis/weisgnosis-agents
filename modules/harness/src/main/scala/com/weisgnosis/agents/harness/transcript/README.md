# Session transcript observations

[SessionTranscriptWriter](SessionTranscriptWriter.scala) records typed observations through a transcript-bound [TranscriptStore](TranscriptStore.scala). appendMessages returns only newly committed entries; offload discards that result for installation as ConversationCompactor's offload callback. Transcript recording is independent of memory extraction: summaries and thinking remain eligible, while user messages containing `<session_context>` are skipped. System messages remain eligible. Installing the compactor callback alone does not record every turn; host turn-boundary integration remains pending.

## Projection and data contract

Projection combines nonblank text/thinking in block order, then emits tool uses and tool results in per-kind order. Media-only and empty messages receive observable placeholders. Tool input JSON is complete through 500 UTF-16 characters; larger input carries a typed truncated preview and original character count. Joined tool output follows the same rule at 1,000 characters. Result state and output block types are retained. Complete and truncated variants are distinct ADT cases; no truncated JSON is represented as complete arguments.

[TranscriptEntry](TranscriptEntry.scala) contains a storage-generated UUID, optional parent UUID, recordedAt, optional structural key, and payload. Keys distinguish message/use/result and per-kind occurrence; arbitrary message ID delimiters and repeated call IDs cannot collide structurally. IDs absent or blank in Msg produce anonymous drafts and append on every invocation. Stable keys retain the first observation even if later content changes through eviction or compaction. Use a separate store capability per transcript/session; sharing a capability intentionally shares its deduplication domain.

These are lossy observations, not restorable canonical Msg values. Metadata, media bytes, and reasoning signatures are not copied. Compared with upstream, payloads retain block types for visible messages and preserve tool-result state. Joined output uses canonical ToolResult.outputText, including separators around empty text blocks; upstream omits some leading empty separators. UUID entry identities and structural keys deliberately replace Java's concatenated IDs; derived JSON codecs are Scala DTO codecs, not Java SessionEntry wire adapters or strict persisted-state validators.

## Atomic store authority

TranscriptStore.inMemory allocates recording time and candidate UUIDs in ZIO before a pure Ref.modify transition. Within that transition, each accepted row updates the stable-key set, selects the latest accepted parent, and appends. Duplicate rows neither append nor change the parent. A UUID collision rejects the entire proposed batch and preserves previous state. Independent concurrent writers on one store therefore publish whole batches into one linked sequence. Empty and all-duplicate batches return no committed entries.

recordedAt is sampled before candidate allocation and commit; it is an observation timestamp, not a monotonic ordering authority. Follow the stored sequence and parent links. Cancellation/failure after commit may have an uncertain acknowledgement; do not infer rollback from an interrupted caller. Anonymous retries are not idempotent. The implementation is process-local: no files, segments, indexes, remote mirrors, crash durability, or automatic runtime scheduling are provided. A future local backend must validate persisted input and preserve the same atomic deduplication/linkage contract.

## Verification and acceptance

- [x] `review_fp` and `review_harness` approved the final six-file transcript increment against `73458da` on 2026-09-29. No findings remain within pure observation projection, atomic in-memory batch deduplication/linkage, first-observation retention, collision rollback, and compactor callback integration. Full `sbt scalafmtAll scalafmtSbt verify` passed 530 core / 231 harness tests, including 22 new cases, with zero compiler warnings. Filesystem/remote persistence, automatic turn-boundary recording, strict persisted-input validation, and Java wire compatibility remain outside this scope.
- [x] `review_docs` approved the corrected documentation against `73458da`, including all six identities, 22 tests, wire/timestamp limits, and explicit pending persistence mappings. All 61 Markdown files passed link/script checks; git diff --check passed.
- [ ] Implement scoped local transcript persistence and complete the filesystem assertions in four mapped upstream methods.
- [ ] Implement immutable segment storage, session indexes, and turn-boundary lifecycle integration.

[Tests](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/README.md) and [parity mapping](../../../../../../../../../../docs/architecture/test-parity.md#transcript-observation-applicability) distinguish behavioral coverage from pending filesystem assertions. [Tracker](../../../../../../../../../../docs/architecture/tracker.md).

Current Git-normalized identities:

| Source | Blob identity |
| --- | --- |
| [SessionTranscriptWriter.scala](SessionTranscriptWriter.scala) | 7f85374042063687611754d964bd0005ae8dcf89 |
| [TranscriptEntry.scala](TranscriptEntry.scala) | ce02c2fa3a1cb38c6153e9745474fe21ab7ecc5a |
| [TranscriptStore.scala](TranscriptStore.scala) | 79335fb7efd6030f0931b534d3606bfc687f37b3 |
| [SessionTranscriptWriterSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/SessionTranscriptWriterSpec.scala) | e7ee7663adae6b2382bf07acc68335b533662b5d |
| [TranscriptEntrySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/TranscriptEntrySpec.scala) | 4bbae7448ce93a6cc3763e1b5dd44acf74eecb05 |
| [TranscriptStoreSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/TranscriptStoreSpec.scala) | 9e582e4d6b8e76ddbfb943b24424dd86b4f4ad4a |

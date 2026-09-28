# Conversation compaction

ConversationCompactor accepts an effectful summarizer, a pure TokenCounter, and optional flush/offload callbacks. Defaults: triggerTokens=120000, keepTokens=40000, preserveTailMessages=6, maxPruneOutputChars=2000, protectTokens=40000. Histories below the trigger remain unchanged. Compaction prunes older payloads, preserves call/result pairs and protected tails, deduplicates summaries, and coalesces adjacent user messages. Retained-user provenance survives coalescing for later flushing. MemoryFlushManager and MemoryConsolidator remain planned; callbacks do not provide durable storage.

## Implementation

- [CompactorConfig](CompactorConfig.scala)
- [ConversationCompactor](ConversationCompactor.scala)
- [TokenCounter](TokenCounter.scala)

See the [repository architecture](../../../../../../../../../../ARCHITECTURE.md) for integration boundaries and invariant definitions.

## Current review acceptance

- [x] Reviewed revision: `fb80428`; accepted files: ConversationCompactor.scala and ConversationCompactorSpec.scala.
- [x] Independent final approval: `review_fp`, reconfirmed for unchanged code on 2026-09-29 after corrections and cross-review.
- [x] No unresolved findings in the accepted scope: Repeated-compaction user provenance and unchanged below-threshold history.
- [x] Verification at the reviewed revision: `sbt verify`, 108 core and 40 harness tests, no failed tests or compiler warnings.

This is the accepted reference behavior for the scope above. Memory services and exhaustive upstream parity remain pending. Changes to this behavior require renewed independent review and an updated acceptance entry.

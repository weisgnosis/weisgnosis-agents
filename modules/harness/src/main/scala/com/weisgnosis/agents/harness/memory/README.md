# Conversation compaction

ConversationCompactor accepts an effectful summarizer, a pure TokenCounter, and optional flush/offload callbacks. Defaults: triggerTokens=120000, keepTokens=40000, preserveTailMessages=6, maxPruneOutputChars=2000, protectTokens=40000. Histories below the trigger remain unchanged. Compaction prunes older payloads, preserves call/result pairs and protected tails, deduplicates summaries, and coalesces adjacent user messages. Retained-user provenance survives coalescing for later flushing. MemoryFlushManager and MemoryConsolidator remain planned; callbacks do not provide durable storage.

## Implementation

- [CompactorConfig](CompactorConfig.scala)
- [ConversationCompactor](ConversationCompactor.scala)
- [TokenCounter](TokenCounter.scala)

See the [repository architecture](../../../../../../../../../../ARCHITECTURE.md) for integration boundaries and invariant definitions.

# Conversation compaction verification

- [ConversationCompactorSpec](ConversationCompactorSpec.scala): pair-safe cutoff, prior summary rebuilding, user provenance, pruning, unchanged below-trigger history, and typed/interrupted hooks.
- [TokenCounterSpec](TokenCounterSpec.scala): deterministic additive estimation.

Eight added compactor regressions cover identical IDs, reversed named results, unnamed FIFO consumption, same-message and later inline/local collisions, unmatched provider calls, stale argument-cache removal, and signed/opaque reasoning metadata protection. All eight failed before the hardening change. Existing completed-turn ID reuse, protected-tail, and below-threshold regressions remain in the suite.

These tests cover selected upstream behavior and Scala runtime regressions, not exhaustive upstream parity. Run `sbt "harness / test"` or repository-wide `sbt verify`. The [memory contract and source identities](../../../../../../../main/scala/com/weisgnosis/agents/harness/memory/README.md) record the current acceptance scope; prior acceptance remains in Git through `6ed928a`.

## Runtime installation

ConversationCompactor.preparer adapts compactIfNeeded to core HistoryPreparer and normalizes typed failures into HistoryPreparationError. Install it with ReActAgent.make(historyPreparer = compactor.preparer) or Builder.historyPreparer. Preparation runs in a nested scope before each model request, after prior acting work completes. Runtime validation preserves ordered system messages and complete tool pairing for actual rewrites; unchanged candidates retain the permissive supplied-history API. A changed candidate retaining unmatched calls/results is rejected without committing history. Pairing does not prove summary fidelity, and completed external hooks cannot be rolled back.

[ConversationCompactorIntegrationSpec](ConversationCompactorIntegrationSpec.scala) verifies cancellation after history shrinkage, typed summary failure without Auto fallback, hook cancellation cleanup, and chained preparation preserving a single summary and paired tool observations. The shared core ToolHistory analysis replaces the prior local cutoff matcher without changing its reviewed occurrence semantics.

## Current review acceptance

Prior standalone hardening and its eight reproduced regressions are preserved in `3c6958f`.

- [x] `review_fp` and `review_harness` approved the final nine-file history-preparation increment against `3c6958f` on 2026-09-29. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 497 core and 73 harness tests, including twenty added tests. Acceptance covers structural rewrite validation and scoped compactor integration; semantic summary fidelity and exhaustive provider-format validation are excluded. See the [agent table](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md) for unchanged history-preparation identities; commit `2b99226` preserves the full nine-file scope.

# Conversation compaction verification

- [ConversationCompactorSpec](ConversationCompactorSpec.scala): 27 tests for pair-safe cutoff, summary/flush/tail routing, repeated compaction, user provenance, pruning, unchanged below-trigger history, and typed/interrupted hooks.
- [ConversationCompactorIntegrationSpec](ConversationCompactorIntegrationSpec.scala): 12 tests for canonical history installation, shrinking-history recovery, failure policy, preparation/downstream cancellation, mixed cleanup causes, and separate-agent isolation.
- [TokenCounterSpec](TokenCounterSpec.scala): deterministic additive estimation.

The [method mapping](../../../../../../../../../../docs/architecture/test-parity.md#compaction-method-applicability) accounts for seven upstream compactor and ten middleware methods. It distinguishes tested adaptations, deliberate failure-policy divergences, and the inapplicable cyclic-Throwable mechanism. The concurrent tests coordinate entry and release with Promise; each agent owns a separate history. No timing sleeps or shared-agent multi-session claims are involved.

Existing hardening regressions retain occurrence pairing, inline/local isolation, protected signed arguments, stale argument-cache removal, and retained-user provenance. Summary content is supplied by test effects; these tests do not prove semantic summary fidelity.

## Current review acceptance

- [x] `review_fp` and `review_harness` approved both changed compactor suites against `52da827` on 2026-09-29; no findings remain in this scope.
- [x] Full formatting, fatal-warning compilation, and verification passed 529 core / 114 harness tests, including all 39 compactor cases. Thirteen cases were added and existing routing assertions strengthened.
- [ ] Add memory-service suites when daily flushing and curated consolidation are implemented.

Exact current test identities and failure/ownership contracts are maintained in the [memory README](../../../../../../../main/scala/com/weisgnosis/agents/harness/memory/README.md). Prior acceptance remains in Git through `52da827`. Run `sbt "harness / test"` or repository-wide `sbt verify`.

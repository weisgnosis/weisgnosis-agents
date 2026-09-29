# Conversation compaction verification

- [ConversationCompactorSpec](ConversationCompactorSpec.scala): pair-safe cutoff, prior summary rebuilding, user provenance, pruning, unchanged below-trigger history, and typed/interrupted hooks.
- [TokenCounterSpec](TokenCounterSpec.scala): deterministic additive estimation.

Eight added compactor regressions cover identical IDs, reversed named results, unnamed FIFO consumption, same-message and later inline/local collisions, unmatched provider calls, stale argument-cache removal, and signed/opaque reasoning metadata protection. All eight failed before the hardening change. Existing completed-turn ID reuse, protected-tail, and below-threshold regressions remain in the suite.

These tests cover selected upstream behavior and Scala runtime regressions, not exhaustive upstream parity. Run `sbt "harness / test"` or repository-wide `sbt verify`. The [memory contract and source identities](../../../../../../../main/scala/com/weisgnosis/agents/harness/memory/README.md) record the current acceptance scope; prior acceptance remains in Git through `6ed928a`.

## Current review acceptance

- [x] `review_fp` and `review_harness` approved the final two-file compactor hardening against `6ed928a` on 2026-09-29. No findings remain in this scope. Eight new regressions failed before the correction; full formatting, fatal-warning compilation, and verification now pass 481 core and 69 harness tests. Acceptance covers standalone pairing and argument pruning; runtime integration and transformed-history validation remain pending.

# weisgnosis-agents-harness

Standalone context and permission components built on core.

| Package | Current status |
| --- | --- |
| [memory](src/main/scala/com/weisgnosis/agents/harness/memory/README.md) | Pair-safe compaction, summary/user provenance, token estimation; memory services pending |
| [eviction](src/main/scala/com/weisgnosis/agents/harness/eviction/README.md) | Above-80k eviction, 2k head/tail previews, explicit-trust local store |
| [permission](src/main/scala/com/weisgnosis/agents/harness/permission/README.md) | Immutable decisions, Promise coordination, serializable durable approval values |
| [subagent](src/main/scala/com/weisgnosis/agents/harness/subagent/README.md) | Planned supervision and task promotion |
| [plan](src/main/scala/com/weisgnosis/agents/harness/plan/README.md) | Planned mutation restrictions and shell inspection |

Agent middleware, durable workflow storage, MemoryFlushManager, and MemoryConsolidator are not implemented. Hosts supply summarization/flush/offload effects and authenticated approval routing.

Local storage requires WorkspaceTrust.HostControlled, protected filesystem ancestors, and hard-link support. It is not a sandbox for untrusted workspaces. See [implementation notes](../../docs/architecture/phase-4-implementation.md) and the [tracker](../../docs/architecture/tracker.md).

## Current review acceptance

- [x] Revision `fb80428`: compactor provenance and result-store fixes explicitly approved by independent reviewer `review_fp` after corrections and cross-review; unchanged-code approval reconfirmed on 2026-09-29.
- [x] No unresolved findings in those reviewed changes. Exact file/scope acceptance is recorded in the affected package READMEs above.
- [x] Current repository verification: 497 core and 73 harness tests passed, with no compiler warnings.
- [ ] Complete the remaining integration and parity tasks in the [tracker](../../docs/architecture/tracker.md).

Approval covers the named changes and their documented contracts; it does not mark all neighboring APIs or planned features as accepted.

- [x] Message construction and safe-update migration accepted by `review_fp` and `review_harness` against `9c47bf5`; [current model contracts and compatibility notes](../core/src/main/scala/com/weisgnosis/agents/core/model/README.md).

- [x] `review_fp` and `review_harness` approved the final eight-file local-result processing/eviction increment against `8850a60` on 2026-09-29. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 466 core and 45 harness tests. Scoped contracts and exact identities are linked from the affected package READMEs.

- [x] `review_fp` and `review_harness` approved the final five-file interactive permission increment against `5eb4bdc` on 2026-09-29. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 481 core and 61 harness tests, including sixteen added permission tests. Approval covers in-memory active-invocation authorization; durable resumption, authentication, and post-decision revocation remain outside scope. See the [permission contracts and exact identities](src/main/scala/com/weisgnosis/agents/harness/permission/README.md).

- [x] `review_fp` and `review_harness` approved the final two-file compactor hardening against `6ed928a` on 2026-09-29. No findings remain in this scope. Eight new regressions failed before the correction; full formatting, fatal-warning compilation, and verification now pass 481 core and 69 harness tests. That earlier acceptance covers standalone pairing and argument pruning; current integration acceptance is linked from the memory README.

- [x] `review_fp` and `review_harness` approved the final nine-file history-preparation increment against `3c6958f` on 2026-09-29. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 497 core and 73 harness tests, including twenty added tests. Acceptance covers structural rewrite validation and scoped compactor integration; semantic summary fidelity and exhaustive provider-format validation are excluded.

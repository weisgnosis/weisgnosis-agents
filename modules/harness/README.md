# weisgnosis-agents-harness

Standalone context and permission components built on core.

| Package | Current status |
| --- | --- |
| [memory](src/main/scala/com/weisgnosis/agents/harness/memory/README.md) | Pair-safe compaction, summary/user provenance, token estimation and daily extraction/local storage and atomic in-memory consolidation; persistent consolidation pending |
| [eviction](src/main/scala/com/weisgnosis/agents/harness/eviction/README.md) | Above-80k eviction, 2k head/tail previews, explicit-trust local store |
| [permission](src/main/scala/com/weisgnosis/agents/harness/permission/README.md) | Immutable decisions, Promise coordination, serializable durable approval values |
| [subagent](src/main/scala/com/weisgnosis/agents/harness/subagent/README.md) | Planned supervision and task promotion |
| [plan](src/main/scala/com/weisgnosis/agents/harness/plan/README.md) | Planned mutation restrictions and shell inspection |

MemoryFlushManager supplies daily extraction through an injected DailyMemoryStore and can be installed as the compactor flush callback. LocalDailyMemoryStore supplies a scoped backend for host-controlled workspaces with exclusive cooperative ownership and atomic daily replacement. MemoryConsolidator enforces its counter budget before an atomic content/progress commit; MemoryRepository.inMemory implements shared daily/consolidation authority. Persistent consolidation, transcript persistence, general middleware, and durable workflow storage remain pending. Hosts choose a memory store and supply summarization/offload effects and authenticated approval routing.

Local storage requires WorkspaceTrust.HostControlled and protected filesystem ancestors. Tool-result publication requires hard-link support; daily-memory publication requires atomic-move support. It is not a sandbox for untrusted workspaces. See [implementation notes](../../docs/architecture/phase-4-implementation.md) and the [tracker](../../docs/architecture/tracker.md).

## Current review acceptance

- [x] Revision `fb80428`: compactor provenance and result-store fixes explicitly approved by independent reviewer `review_fp` after corrections and cross-review; unchanged-code approval reconfirmed on 2026-09-29.
- [x] No unresolved findings in those reviewed changes. Exact file/scope acceptance is recorded in the affected package READMEs above.
- [x] Current repository verification: 530 core and 175 harness tests passed, with no compiler warnings.
- [ ] Complete the remaining integration and parity tasks in the [tracker](../../docs/architecture/tracker.md).

Approval covers the named changes and their documented contracts; it does not mark all neighboring APIs or planned features as accepted.

- [x] Message construction and safe-update migration accepted by `review_fp` and `review_harness` against `9c47bf5`; [current model contracts and compatibility notes](../core/src/main/scala/com/weisgnosis/agents/core/model/README.md).

- [x] `review_fp` and `review_harness` approved the final eight-file local-result processing/eviction increment against `8850a60` on 2026-09-29. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 466 core and 45 harness tests. Scoped contracts and exact identities are linked from the affected package READMEs.

- [x] `review_fp` and `review_harness` approved the final five-file interactive permission increment against `5eb4bdc` on 2026-09-29. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 481 core and 61 harness tests, including sixteen added permission tests. Approval covers in-memory active-invocation authorization; durable resumption, authentication, and post-decision revocation remain outside scope. See the [permission contracts and exact identities](src/main/scala/com/weisgnosis/agents/harness/permission/README.md).

- [x] `review_fp` and `review_harness` approved the final two-file compactor hardening against `6ed928a` on 2026-09-29. No findings remain in this scope. Eight new regressions failed before the correction; full formatting, fatal-warning compilation, and verification now pass 481 core and 69 harness tests. That earlier acceptance covers standalone pairing and argument pruning; current integration acceptance is linked from the memory README.

- [x] `review_fp` and `review_harness` approved the final nine-file history-preparation increment against `3c6958f` on 2026-09-29. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 497 core and 73 harness tests, including twenty added tests. Acceptance covers structural rewrite validation and scoped compactor integration; semantic summary fidelity and exhaustive provider-format validation are excluded.

- [x] `review_fp` and `review_harness` approved the final inline-processing and cancellation-cleanup scope against `2b99226` on 2026-09-29. Fourteen added regressions and the full formatting/fatal-warning verification passed: 510 core and 74 harness tests. No findings remain in this scope. Supplied-history eviction is covered by a later scope; exhaustive upstream middleware parity remains pending. Exact identities and contract limits are recorded in the affected agent/tool/eviction READMEs.

- [x] `review_fp` and `review_harness` approved the final nine-file history-eviction scope against `cc8fa7b` on 2026-09-29. Twenty-two added regressions and full formatting/fatal-warning verification passed: 516 core and 90 harness tests. Two new regressions exposed and closed strict-number and Windows storage-layout gaps. No findings remain within store-backed integrity, explicit workspace trust, and validated composition; absolute storage is covered by a later scope; request-only input views are covered by a later scope. See the [eviction contract/table](src/main/scala/com/weisgnosis/agents/harness/eviction/README.md).

- [x] `review_fp` and `review_harness` approved the final three-file storage-location scope against `3718e18` on 2026-09-29. Eight added tests and full formatting/fatal-warning verification passed: 516 core and 98 harness tests. No findings remain within explicit host ownership and canonical-location validation. Dynamic symlink/junction checks, hostile mutation protection, and filesystem object identity are not claimed. Exact identities and migration are in the [eviction contract](src/main/scala/com/weisgnosis/agents/harness/eviction/README.md).

- [x] `review_fp` and `review_harness` approved the final seven-file model-input scope against `40f6f05` on 2026-09-29. Sixteen added regressions and full formatting/fatal-warning verification passed: 529 core and 101 harness tests. No findings remain within request-only projection, structural validation, canonical-state isolation, and eviction integration. Broader legacy hook/middleware parity remains pending. Exact identities and migration are in the [agent contract](../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md).

- [x] Independently review exact compaction routing and interruption/session regressions against `52da827`; account for all 17 methods in the two pinned compaction suites with explicit adaptations/divergences/exclusion, and verify 529 core / 114 harness tests. See the [memory contract and current identities](src/main/scala/com/weisgnosis/agents/harness/memory/README.md).

- [x] `review_fp` and `review_harness` approved the seven-file daily-memory extraction increment against `738b216` on 2026-09-29. No findings remain within the injected-store capability, scoped client extraction, provenance/date handling, one-manager serialization, and compactor integration. Full formatting/fatal-warning verification passed 529 core / 136 harness tests, including 22 new tests. Filesystem persistence, cross-manager/process guarantees, consolidation, watermarks, and transcript offloading are excluded. Exact source identities are maintained in the [memory contract](src/main/scala/com/weisgnosis/agents/harness/memory/README.md).

- [x] `review_fp` and `review_harness` approved the final seven-file local daily-memory backend against `d1a4cf2` on 2026-09-29. Nineteen added tests exposed and corrected pending cancellation being reported as success after admitted work. Full formatting/fatal-warning verification passed 530 core / 154 harness tests. Acceptance covers cooperative ownership, scoped cleanup, day-derived paths, atomic replacement, and typed storage errors on the tested Windows filesystem. Crash durability, exactly-once delivery, hostile mutation protection, dynamic symlink/junction behavior, and automatic stale-lease recovery are excluded. Exact identities and operational limits are maintained in the [memory contract](src/main/scala/com/weisgnosis/agents/harness/memory/README.md).

- [x] `review_fp` and `review_harness` approved the final six-file consolidation increment against `46c6174` on 2026-09-29. Twenty-one new tests and full formatting/fatal-warning verification passed 530 core / 175 harness tests. No findings remain within process-local atomic content/progress commits, separate writer capabilities, scoped extraction, configured-counter budget enforcement, and conflict handling. Filesystem transactions, persistent watermarks, crash recovery, and the five upstream filesystem mappings remain outside this scope. See the [memory contract](src/main/scala/com/weisgnosis/agents/harness/memory/README.md) for current source identities and backend responsibilities.

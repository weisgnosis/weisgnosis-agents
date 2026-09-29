# weisgnosis-agents-core

Scala 3/ZIO 2 agent components with immutable models and effectful lifecycle management.

| Package | Available |
| --- | --- |
| [json](src/main/scala/com/weisgnosis/agents/core/json/README.md) | Core-internal complete-document and immutable AST validation |
| [model](src/main/scala/com/weisgnosis/agents/core/model/README.md) | Msg, content, roles, usage, codecs |
| [event](src/main/scala/com/weisgnosis/agents/core/event/README.md) | 39 typed variants and source tagging; not all emitters are implemented |
| [tool](src/main/scala/com/weisgnosis/agents/core/tool/README.md) | Typed tools, immutable registry, normalized observations, path screening |
| [client](src/main/scala/com/weisgnosis/agents/core/client/README.md) | Client port, scripted clients, validated text/function requests, scoped HTTP completion, SSE framing and indexed assembly |
| [agent](src/main/scala/com/weisgnosis/agents/core/agent/README.md) | ReAct lifecycle, interruption, thinking retries, structured output |

Typed failures are defined in [AgentError.scala](src/main/scala/com/weisgnosis/agents/core/error/AgentError.scala). Provider extensions, remaining event integrations, automatic failover, and exhaustive upstream parity remain pending.

ReActAgent retains in-memory history and serializes runs. Resources are scoped per run/request; cancellation reconciles unfinished calls without overwriting completed observations. See [architecture](../../ARCHITECTURE.md) and the [parity audit](../../docs/architecture/test-parity.md).

## Current review acceptance

- [x] Revision `fb80428`: agent lifecycle and path traversal fixes explicitly approved by independent reviewer `review_harness` after corrections and cross-review; unchanged-code approval reconfirmed on 2026-09-29.
- [x] No unresolved findings in those reviewed changes. Exact file/scope acceptance is recorded in the affected package READMEs above.
- [x] Structured-output completion fixes based on `a58b0ca` approved by `review_fp` and `review_harness`; the agent package README records accepted revisions and current contracts.
- [x] Repository verification: 556 core and 593 harness tests passed, with no compiler warnings.
- [x] Strict model decoding, legacy usage compatibility, Base64 wire naming, and role validation approved by review_fp and review_harness; exact scope and source identities are recorded in the model READMEs.
- [x] Correlated engine-attempt boundaries and private stream completion approved by review_fp and review_harness; the agent READMEs record accepted revisions, current contracts, and buffering assumptions.
- [x] Consistent concrete/base event codecs and immutable tool-result event metadata reviewed independently; the event READMEs record exact scope and migration details.
- [x] Protocol identifier lookup and canonical encoding cover 31 names and 11 legacy aliases with independent approval; event payload adaptation remains pending.
- [x] Typed generation metadata covers 12 reasons and nine upstream methods, with immutable updates and raw-extension preservation independently approved.
- [ ] Complete the remaining integration and parity tasks in the [tracker](../../docs/architecture/tracker.md).

Approval covers the named changes and their documented contracts; it does not mark all neighboring APIs or planned features as accepted.

- [x] Message construction and safe-update migration accepted by `review_fp` and `review_harness` against `9c47bf5`; [current model contracts and compatibility notes](src/main/scala/com/weisgnosis/agents/core/model/README.md).

## Content protocol verification

- [x] `review_fp` and `review_harness` approved the content protocol increment against `a701612` on 2026-09-29; no findings remain in the [current model contracts](src/main/scala/com/weisgnosis/agents/core/model/README.md).
- [x] Full verification passed 272 core and 40 harness tests with zero compiler warnings.

The accepted implementation and tests are recorded in commit `8b96ce3`. Canonical serverTool markers preserve inline results without local dispatch, as verified in ReActAgentSpec alongside the legacy marker scenario. The model tests cover all ten content codecs and all 17 upstream ToolUse methods. ToolCallState remains protocol data; lifecycle and permission integration are still tracked work.

- [x] The four-file Data identity/media increment was approved by `review_fp` and `review_harness` against `8b96ce3`; see the model READMEs for exact scope and source hashes.
- [x] Cumulative independently reviewed mappings cover all 119 message methods with explicit Pure FP and wire adaptations.

- [x] Eight additional event payloads and the 39-variant codec matrix were approved by `review_fp` and `review_harness` against `66bb42d`; remaining runtime emission, stop/resume authority, and envelope adapters remain pending.

- [x] Runtime inline-result emission, correlated iteration limits, and occurrence-sensitive reconciliation were independently approved against `9768064`; current agent READMEs record exact scope and source identities.

- [x] Finalized result publication and streamStructuredEvents were approved by `review_fp` and `review_harness` against `5451e03`; commit `3963104` preserves the four reviewed identities and 309+40 verification; the agent READMEs describe current contracts.

Deprecated v1 coarse-event envelopes are explicitly excluded from core after independent applicability review. Typed events remain authoritative; shared message tests do not establish legacy wire compatibility. Actual model streams carry reply/request correlation. Remaining model/tool ordering and richer subagent attribution remain in the tracker.

- [x] `review_fp` and `review_harness` approved the final five-file capability increment against `06bd2e1` on 2026-09-29 after the preset-format correction. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 420 core and 40 harness tests. Current capability contracts and exact identities are in the [client README](src/main/scala/com/weisgnosis/agents/core/client/README.md).

- [x] `review_fp` and `review_harness` approved the final seven-file text-transformation increment against `02b4654` on 2026-09-29 after the fallback-provenance correction. No findings remain within this scope. Full formatting, fatal-warning compilation, and verification passed 434 core and 40 harness tests. The [client README](src/main/scala/com/weisgnosis/agents/core/client/README.md) records exact identities and the narrow middleware scope.

- [x] `review_fp` and `review_harness` approved the final six-file post-reasoning increment against `2a400e0` on 2026-09-29 after the legacy-usage correction. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 456 core and 40 harness tests. The [agent README](src/main/scala/com/weisgnosis/agents/core/agent/README.md) records policy semantics and exact identities.

- [x] `review_fp` and `review_harness` approved the final eight-file local-result processing/eviction increment against `8850a60` on 2026-09-29. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 466 core and 45 harness tests. Scoped contracts and exact identities are linked from the affected package READMEs.

- [x] `review_fp` and `review_harness` approved the final five-file core authorization increment against `a34201f` on 2026-09-29. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 481 core and 45 harness tests, including fifteen added authorization tests. Interactive and durable policy adapters are excluded from this acceptance. See the [authorization table](src/main/scala/com/weisgnosis/agents/core/tool/README.md) for exact identities.

- [x] `review_fp` and `review_harness` approved the final nine-file history-preparation increment against `3c6958f` on 2026-09-29. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 497 core and 73 harness tests, including twenty added tests. Acceptance covers structural rewrite validation and scoped compactor integration; semantic summary fidelity and exhaustive provider-format validation are excluded.

- [x] `review_fp` and `review_harness` approved the final inline-processing and cancellation-cleanup scope against `2b99226` on 2026-09-29. Fourteen added regressions and the full formatting/fatal-warning verification passed: 510 core and 74 harness tests. No findings remain in this scope. Supplied-history eviction is covered by a later scope; exhaustive upstream middleware parity remains pending. Exact identities and contract limits are recorded in the affected agent/tool/eviction READMEs.

- [x] `review_fp` and `review_harness` approved the final nine-file history-eviction scope against `cc8fa7b` on 2026-09-29. Twenty-two added regressions and full formatting/fatal-warning verification passed: 516 core and 90 harness tests. Two new regressions exposed and closed strict-number and Windows storage-layout gaps. No findings remain within store-backed integrity, explicit workspace trust, and validated composition; absolute storage is covered by a later scope; request-only input views are covered by a later scope. See the [eviction contract/table](../harness/src/main/scala/com/weisgnosis/agents/harness/eviction/README.md).

- [x] `review_fp` and `review_harness` approved the final seven-file model-input scope against `40f6f05` on 2026-09-29. Sixteen added regressions and full formatting/fatal-warning verification passed: 529 core and 101 harness tests. No findings remain within request-only projection, structural validation, canonical-state isolation, and eviction integration. Broader legacy hook/middleware parity remains pending. Exact identities and migration are in the [agent contract](src/main/scala/com/weisgnosis/agents/core/agent/README.md).

- [x] `review_fp` and `review_harness` approved the final eight-file completion-observer increment against `0349b1a` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 552 core / 264 harness tests, including 28 new cases, with zero compiler warnings. No findings remain within scoped once-per-invocation observation, canonical snapshots, cause preservation, explicit transcript identity policy, and local persistence integration. Background memory scheduling, remote persistence, delivery acknowledgement, and exhaustive upstream middleware parity remain outside this scope.

The [core completion contract](src/main/scala/com/weisgnosis/agents/core/agent/README.md) defines callback lifetime, late cancellation, and early-stream failure visibility. Transcript installation requires an explicit anonymous-message policy. Completion observers compose with separate scopes; owned harness flush/consolidation scheduling is opt-in.

- [x] `review_fp` and `review_harness` approved the final seven-file consolidation/composition increment against `3e817e3` on 2026-09-29 after correcting nested test-scope ownership. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 346 harness tests, including 25 new cases, with zero compiler warnings. No findings remain within separately scoped observer composition, owned consolidation/result reporting, eight local throttle integration combinations, FIFO replacement and post-commit cancellation/failure. Retention, remote authority, strict cross-job dependencies and transactional reporting are excluded.

Standalone local plan-draft storage now preserves exact content under scoped cooperative ownership and atomic replacement. Persisted text does not restore session mode or approval. The plan package README records its boundaries and current reviewed identities.

- [x] `review_fp` and `review_harness` approved the final nine-file standalone plan-storage increment against `083617b` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 556 core / 520 harness tests, including fifteen new cases, with zero compiler warnings. No findings remain within exact content/encoding, scoped cooperative ownership, atomic replacement, hardlink preservation, typed errors and cancellation ordering. Session/publication integration, persisted approval, hostile filesystem isolation, crash durability and new whole-method upstream parity are excluded.

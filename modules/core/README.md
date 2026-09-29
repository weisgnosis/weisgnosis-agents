# weisgnosis-agents-core

Scala 3/ZIO 2 agent components with immutable models and effectful lifecycle management.

| Package | Available |
| --- | --- |
| [model](src/main/scala/com/weisgnosis/agents/core/model/README.md) | Msg, content, roles, usage, codecs |
| [event](src/main/scala/com/weisgnosis/agents/core/event/README.md) | 31 typed variants and source tagging; not all emitters are implemented |
| [tool](src/main/scala/com/weisgnosis/agents/core/tool/README.md) | Typed tools, immutable registry, normalized observations, path screening |
| [client](src/main/scala/com/weisgnosis/agents/core/client/README.md) | Client port, scripted clients, SSE framing and indexed assembly |
| [agent](src/main/scala/com/weisgnosis/agents/core/agent/README.md) | ReAct lifecycle, interruption, thinking retries, structured output |

Typed failures are defined in [AgentError.scala](src/main/scala/com/weisgnosis/agents/core/error/AgentError.scala). Live HTTP transport, automatic failover, and exhaustive upstream parity remain pending.

ReActAgent retains in-memory history and serializes runs. Resources are scoped per run/request; cancellation reconciles unfinished calls without overwriting completed observations. See [architecture](../../ARCHITECTURE.md) and the [parity audit](../../docs/architecture/test-parity.md).

## Current review acceptance

- [x] Revision `fb80428`: agent lifecycle and path traversal fixes explicitly approved by independent reviewer `review_harness` after corrections and cross-review; unchanged-code approval reconfirmed on 2026-09-29.
- [x] No unresolved findings in those reviewed changes. Exact file/scope acceptance is recorded in the affected package READMEs above.
- [x] Structured-output completion fixes based on `a58b0ca` approved by `review_fp` and `review_harness`; the agent package README records exact source identities and scope.
- [x] Repository verification: 284 core and 40 harness tests passed, with no compiler warnings.
- [x] Strict model decoding, legacy usage compatibility, Base64 wire naming, and role validation approved by review_fp and review_harness; exact scope and source identities are recorded in the model READMEs.
- [x] Correlated engine-attempt boundaries and private stream completion approved by review_fp and review_harness; the agent READMEs record exact scope, buffering assumptions, and source identities.
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

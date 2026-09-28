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
- [x] Repository verification: 169 core and 40 harness tests passed, with no compiler warnings.
- [x] Strict model decoding, legacy usage compatibility, Base64 wire naming, and role validation approved by review_fp and review_harness; exact scope and source identities are recorded in the model READMEs.
- [ ] Complete the remaining integration and parity tasks in the [tracker](../../docs/architecture/tracker.md).

Approval covers the named changes and their documented contracts; it does not mark all neighboring APIs or planned features as accepted.

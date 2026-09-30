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

- [x] `path_review` approved the advisory command-screening correction and regressions for FP/ZIO correctness and relevant invariants. The [tool README](src/main/scala/com/weisgnosis/agents/core/tool/README.md#current-review-acceptance) records exact source/test blobs and the lexical helper's limits.
- [x] Full `scalafmtAll scalafmtSbt verify` passed on 2026-09-30: 561 core / 609 harness tests, zero compiler warnings or failures (`resumed-implementation-verify.log`).
- [x] Existing model, event, client, agent and tool contracts retain their scoped independent acceptance and source identities in the package READMEs above.
- [ ] Complete remaining provider, event, lifecycle and upstream parity work in the [tracker](../../docs/architecture/tracker.md).

The core runtime retains canonical history and scoped completion authority. Result processing, authorization, history preparation, request projection and completion observers are explicit ports; hosts install harness adapters. Durable replay and authenticated resumption are not core runtime guarantees. Deprecated upstream v1 event envelopes remain excluded; typed events are authoritative.

Approval covers recorded revisions and named contracts, not unreviewed working-tree changes or exhaustive parity.

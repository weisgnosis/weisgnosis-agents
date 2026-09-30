# weisgnosis-agents-core

Scala 3/ZIO 2 agent components with immutable models and effectful lifecycle management.

| Package | Available |
| --- | --- |
| [json](src/main/scala/com/weisgnosis/agents/core/json/README.md) | Shared internal complete-document and immutable AST validation |
| [model](src/main/scala/com/weisgnosis/agents/core/model/README.md) | Msg, content, roles, usage, codecs |
| [event](src/main/scala/com/weisgnosis/agents/core/event/README.md) | 39 typed variants and source tagging; not all emitters are implemented |
| [tool](src/main/scala/com/weisgnosis/agents/core/tool/README.md) | Typed tools, immutable registry, typed completed observations, path screening |
| [client](src/main/scala/com/weisgnosis/agents/core/client/README.md) | Client port, scripted clients, validated text/function requests, scoped HTTP completion, SSE framing and indexed assembly |
| [agent](src/main/scala/com/weisgnosis/agents/core/agent/README.md) | ReAct lifecycle, interruption, thinking retries, structured output |

Typed failures are defined in [AgentError.scala](src/main/scala/com/weisgnosis/agents/core/error/AgentError.scala). Provider extensions, remaining event integrations, automatic failover, and exhaustive upstream parity remain pending.

ReActAgent retains in-memory history and serializes runs. Resources are scoped per run/request; cancellation reconciles unfinished calls without overwriting completed observations. See [architecture](../../ARCHITECTURE.md) and the [parity audit](../../docs/architecture/test-parity.md).

Tool.encodeResult preserves String/Success behavior by default and permits typed Success/Error observations. ToolExecutionResult now stores payload/state; String constructors and its text projection remain available, while generated copy/unapply and JSON shape change. Runtime call identity/control metadata remain protected. See the [migration and limits](src/main/scala/com/weisgnosis/agents/core/tool/README.md#typed-execution-observations); the current HTTP request codec still rejects non-text tool output.

## Current review acceptance

- [x] `path_review` and `current_docs` approved package-visible direct event forwarding with caller-owned results, ordered draining and complete failure causes. The [agent contract](src/main/scala/com/weisgnosis/agents/core/agent/README.md#direct-event-forwarding) records exact evidence; harness wiring remains pending.

- [x] `path_review` and `current_docs` approved explicit tool invocation context and scoped publication revocation, including actual ReAct cancellation. The [capability contract](src/main/scala/com/weisgnosis/agents/core/tool/README.md#invocation-event-capability) records migration, limits, exact identities and full verification.

- [x] `path_review` and `current_docs` approved ordinary/structured result-bearing event streams, preserving complete results, terminal causes and joined cancellation; exact identities and full verification are in the [agent contract](src/main/scala/com/weisgnosis/agents/core/agent/README.md#result-bearing-streams).

- [x] `path_review` and `current_docs` approved typed execution payloads and compound-cause preservation; full repository verification passed and the [tool acceptance](src/main/scala/com/weisgnosis/agents/core/tool/README.md#current-review-acceptance) records the final evidence.

- [x] `path_review` and `current_docs` approved the additive [McpError and McpRemoteError boundaries](src/main/scala/com/weisgnosis/agents/core/error/README.md#current-review-acceptance) and their request/client integration, including absent versus explicit-null remote data, with no findings.

- [x] `path_review` approved the advisory command-screening correction and regressions for FP/ZIO correctness and relevant invariants. The [tool README](src/main/scala/com/weisgnosis/agents/core/tool/README.md#current-review-acceptance) records exact source/test blobs and the lexical helper's limits.
- [x] Full formatting, fatal-warning compilation and tests pass; the [tracker](../../docs/architecture/tracker.md#current-acceptance) records current verification evidence.
- [x] `path_review` approved live clocks for the three real HTTP fixtures, preserving scoped cleanup and 20-second timeouts. Exact identities and repeated verification are in the [client test contract](src/test/scala/com/weisgnosis/agents/core/client/README.md#real-http-fixture-clocks).
- [x] Existing model, event, client, agent and tool contracts retain their scoped independent acceptance and source identities in the package READMEs above.
- [ ] Complete remaining provider, event, lifecycle and upstream parity work in the [tracker](../../docs/architecture/tracker.md).

The core runtime retains canonical history and scoped completion authority. Result processing, authorization, history preparation, request projection and completion observers are explicit ports; hosts install harness adapters. Durable replay and authenticated resumption are not core runtime guarantees. Deprecated upstream v1 event envelopes remain excluded; typed events are authoritative.

Approval covers recorded revisions and named contracts, not unreviewed working-tree changes or exhaustive parity.

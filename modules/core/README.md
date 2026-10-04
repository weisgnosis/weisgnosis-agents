# weisgnosis-agents-core

The [before-acting suspension contract](src/main/scala/com/weisgnosis/agents/core/agent/README.md#before-acting-suspension-and-restoration) is accepted: ordinary saved-batch resume with checked host-supplied results and checked data restoration use fresh host bindings and owned invocation scopes. This does not implement host persistence or partial confirmations.

Scala 3/ZIO 2 agent components with immutable models and effectful lifecycle management.

| Package | Available |
| --- | --- |
| [json](src/main/scala/com/weisgnosis/agents/core/json/README.md) | Shared internal complete-document and immutable AST validation |
| [model](src/main/scala/com/weisgnosis/agents/core/model/README.md) | Msg, content, roles, usage, codecs |
| [event](src/main/scala/com/weisgnosis/agents/core/event/README.md) | 39 typed variants and source tagging; not all emitters are implemented |
| [skill](src/main/scala/com/weisgnosis/agents/core/skill/README.md) | Checked preloaded catalogue, exact logical resources and explicit Meta targets; runtime loading uses ordinary authorization |
| [tool](src/main/scala/com/weisgnosis/agents/core/tool/README.md) | Typed tools, immutable host-selected groups and pure scoped notes, attached fixed raw arguments, host-selected descriptive output schemas, immutable registry, typed completed observations, path screening |
| [client](src/main/scala/com/weisgnosis/agents/core/client/README.md) | Client port, scripted clients, validated text/user-image/inline-audio/function requests, scoped HTTP completion, SSE framing, indexed assembly, explicit Google tool-signature replay and selected reasoning details |
| [agent](src/main/scala/com/weisgnosis/agents/core/agent/README.md) | ReAct lifecycle, run-owned group activation, preloaded skill loading and snapshot-consistent descriptions/results, interruption, thinking retries, structured output and reviewed host acting-stop decisions |

Typed failures are defined in [AgentError.scala](src/main/scala/com/weisgnosis/agents/core/error/AgentError.scala). Provider extensions, remaining event integrations, broader model-routing policies and exhaustive upstream parity remain pending; explicit bounded model failover is documented below.

ReActAgent retains in-memory history and serializes runs. Resources are scoped per run/request; cancellation reconciles unfinished calls without overwriting completed observations. See [architecture](../../ARCHITECTURE.md) and the [parity audit](../../docs/architecture/test-parity.md).

Tool.encodeResult preserves String/Success behavior by default and permits typed Success/Error observations. ToolExecutionResult now stores payload/state; String constructors and its text projection remain available, while generated copy/unapply and JSON shape change. Runtime call identity/control metadata remain protected. See the [migration and limits](src/main/scala/com/weisgnosis/agents/core/tool/README.md#typed-execution-observations); the current HTTP request codec still rejects non-text tool output.

The [incremental local execution contract](src/main/scala/com/weisgnosis/agents/core/tool/README.md#incremental-execution) separates provisional text/data events from one final typed observation; both independent reviews and full verification passed. Existing completed payloads and provider limitations retain their contracts.

## Current review acceptance

- [x] Both reviewers approved [selected reasoning details](src/main/scala/com/weisgnosis/agents/core/client/README.md#encrypted-reasoning): strict ordered encrypted/text/summary objects, all-historical replay, matching plaintext events, bounded opaque-only reminders and message-level argument protection. The linked contract owns current identities, verification and remaining provider limits.

- [x] `registry_contract` and `current_docs` approved checked [catalogue selection](src/main/scala/com/weisgnosis/agents/core/skill/README.md), with exact definitions, explicit empty selection and rejection of removed or unknown IDs. The linked contract owns source/spec identities and full verification.

- [x] Both reviewers approved [inert skill metadata](src/main/scala/com/weisgnosis/agents/core/skill/README.md), including checked recursive duplicates/reserved fields, declaration migration and preservation; full verification passed.

- [x] Both reviewers approved pure skill-group reminders and snapshot-consistent reset description/result integration; the [catalogue](src/main/scala/com/weisgnosis/agents/core/skill/README.md) and [runtime](src/main/scala/com/weisgnosis/agents/core/agent/README.md#preloaded-skills) record exact current identities, verification and advisory limits.
- [x] Both independent reviewers approved [transient admitted tool access](src/main/scala/com/weisgnosis/agents/core/tool/README.md#admitted-tool-access) and its ReAct propagation for opt-in [harness Plan admission](../harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md#dynamic-plan-admission); full plan-admission-verify.log verification passed. Exact identities and migration are in the linked contracts.

- [x] Both independent reviewers approved the [preloaded catalogue](src/main/scala/com/weisgnosis/agents/core/skill/README.md) and [runtime skill control](src/main/scala/com/weisgnosis/agents/core/agent/README.md#preloaded-skills); exact scope, identities and verification are recorded there.

- [x] `path_review` and `current_docs` approved [run-owned model-facing group activation](src/main/scala/com/weisgnosis/agents/core/agent/README.md#run-owned-tool-group-activation), with fresh invocation state, request/batch snapshots and shared structured fallback. Exact source/spec identities and full verification are recorded in the linked contract.

- [x] `path_review` and `current_docs` approved [immutable host-selected tool groups](src/main/scala/com/weisgnosis/agents/core/tool/README.md#host-selected-tool-groups), including pure scoped notes and actual ReAct snapshot visibility and dispatch, with no findings. Exact two-file identities and full verification are recorded in the linked contract; run-owned activation and MCP grouped installation have separate scoped acceptance.

- [x] `path_review` and `current_docs` approved [core fixed arguments and runtime authorization](src/main/scala/com/weisgnosis/agents/core/tool/README.md#fixed-raw-arguments), including the reproduced interrupted-authorizer cleanup correction. Scoped identities and full verification are recorded in the linked contract; MCP installation has its own [binding contract](../mcp/src/main/scala/com/weisgnosis/agents/mcp/tool/README.md#fixed-arguments).

- [x] `path_review` and `current_docs` approved host-selected descriptive output schemas, legacy optional codec semantics, actual model-call retention and current provider omission. The [tool contract](src/main/scala/com/weisgnosis/agents/core/tool/README.md#descriptive-output-schemas) records five identities, migration and full verification.

- [x] `registry_contract` and `current_docs` approved selected skill catalogue rendering and request-only system projection after host input validation, preserving canonical history and activation. The [runtime contract](src/main/scala/com/weisgnosis/agents/core/agent/README.md#request-only-skill-catalogue) and linked catalogue record the four current identities, migration and full verification.

- [x] `path_review` and `current_docs` approved the single-consumer delivery correction for direct forwarding and result-bearing streams, preserving caller-owned results, FIFO event order, complete failure causes and joined cancellation. The [agent contract](src/main/scala/com/weisgnosis/agents/core/agent/README.md#direct-event-forwarding) records exact core evidence; harness wiring has its own scoped acceptance.

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

- [x] Both independent reviewers approved [exact deferred skill capabilities](src/main/scala/com/weisgnosis/agents/core/skill/README.md#deferred-resource-capabilities), authorized scoped resolution before activation and logical-name prompt projection.

- [x] Both reviewers approved [bounded HTTP-status retries](src/main/scala/com/weisgnosis/agents/core/client/README.md#bounded-http-status-retries), preserving default single-attempt behavior, one logical invocation and joined cleanup; full verification passed.
- [x] Both reviewers approved [scoped model failover](src/main/scala/com/weisgnosis/agents/core/agent/README.md#scoped-model-failover), preserving prepared history, active schema/tools and complete failure causes; full verification passed.

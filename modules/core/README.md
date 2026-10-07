# weisgnosis-agents-core

The [interrupted transformed-progress correction](src/main/scala/com/weisgnosis/agents/core/client/README.md#interrupted-transformed-progress) has final source approval and focused verification; full/artifact verification passes; final combined documentation acceptance passed after an unchanged-production regression reproduced canonical partial-history loss. Public client/configuration contracts are unchanged; request-local snapshots grant no completion or dispatch authority.

The [model turn trust boundary](src/main/scala/com/weisgnosis/agents/core/agent/README.md#model-turn-trust-boundary) validates custom-client assistant roles and classification before runtime truth/persistence. Source/full/artifact verification passes; final combined documentation approval passed; public signatures/codecs are unchanged.

Explicit [ordinary JSON completion](src/main/scala/com/weisgnosis/agents/core/client/README.md#ordinary-json-completions) complements default SSE through additive client/transport factories and a pure request encoder. Full bounded EOF validation precedes transforms/events; existing request ownership, tool authority and cleanup contracts apply.

Toolkit has one canonical-name ToolBinding registry for local execution and checked external declarations; local fromMap and mixed fromBindings are checked. The [external binding/runtime scope](src/main/scala/com/weisgnosis/agents/core/tool/README.md#external-tool-bindings) has both final source approvals and artifact/consumer checks; final documentation acceptance passed. tools/resolve callers must migrate to bindings and rebuild. The [registry migration and review contract](src/main/scala/com/weisgnosis/agents/core/tool/README.md#checked-registry-identity) records source/JVM changes, the reproduced defect and current source/spec identities. Existing module lifecycle and permission contracts remain in force.

The [cache-usage decoder](src/main/scala/com/weisgnosis/agents/core/client/README.md#cache-usage-decoding) preserves compatible direct cache-hit and nested cache-write counters with strict validation and independent review; no public API migration is needed.

The [thinking-budget endpoint preference](src/main/scala/com/weisgnosis/agents/core/client/README.md#optional-thinking-budget) adds a checked positive optional integer, with explicit compatibility and sixteen/nine-field configuration/projection migration; source/full/artifact verification passes; final combined documentation approval passed.

The [typed stop-sequence contract](src/main/scala/com/weisgnosis/agents/core/client/README.md#optional-stop-sequences) adds checked provider request settings; the [reasoning-effort contract](src/main/scala/com/weisgnosis/agents/core/client/README.md#optional-reasoning-effort) records current configuration migration and optional generation preferences; runtime completion and tool authority remain unchanged.

Optional [per-tool provider schema strictness](src/main/scala/com/weisgnosis/agents/core/tool/README.md#provider-tool-schema-strictness) preserves declared schemas and records the current eight-field ToolDef migration; it adds no local validation or authorization authority.

The [scalar reasoning-alias contract](src/main/scala/com/weisgnosis/agents/core/client/README.md#scalar-reasoning-aliases) adds reasoning alongside reasoning_content/thinking, with equal-value coalescing and explicit conflict failure. It records the intentional validation change and independent review.

An explicit [completion-envelope profile](src/main/scala/com/weisgnosis/agents/core/client/README.md#completion-envelope-profiles) supports full-message-only SSE and suppresses final summary replay after deltas. The default remains indexed deltas; the contract records public factory migration, exact review scope and verification.

The [SSE frame-completion contract](src/main/scala/com/weisgnosis/agents/core/client/README.md#sse-frame-completion) requires a blank-line terminator before dispatch, including [DONE]; EOF discards unfinished LF/CRLF frames. It records the parser correction, regression evidence and independent scoped review.

Local artifact: `com.weisgnosis:weisgnosis-agents-core_3:0.1.0-SNAPSHOT`. Binary/source JAR and POM generation, dependency boundaries and isolated-consumer evidence are defined in the [build contract](../../project/README.md#local-artifact-packaging); no external publication is claimed.

The internal forwarding lifecycle also supports [host-only retained child resume](../harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#host-only-session-resume), preserving checkpoint context and joined cleanup under existing runtime ownership.

The [before-acting suspension contract](src/main/scala/com/weisgnosis/agents/core/agent/README.md#before-acting-suspension-and-restoration) is accepted: ordinary saved-batch resume and resumed streams with checked host-supplied results and checked data restoration use fresh host bindings and owned invocation scopes. Partial-confirmation checkpoints are independently reviewed; host persistence remains external.

Scala 3/ZIO 2 agent components with immutable models and effectful lifecycle management.

| Package | Available |
| --- | --- |
| [json](src/main/scala/com/weisgnosis/agents/core/json/README.md) | Shared internal complete-document/AST validation and exact decimal rendering for integrated requests and structured completion output |
| [model](src/main/scala/com/weisgnosis/agents/core/model/README.md) | Msg, content, roles, usage, codecs |
| [event](src/main/scala/com/weisgnosis/agents/core/event/README.md) | 39 typed variants, source tagging, [optional final-answer filtering](src/main/scala/com/weisgnosis/agents/core/event/README.md#optional-final-answer-filter) and [reviewed bounded lifecycle ordering](src/main/scala/com/weisgnosis/agents/core/client/README.md#runtime-event-ordering); model data-block production remains unimplemented |
| [skill](src/main/scala/com/weisgnosis/agents/core/skill/README.md) | Checked preloaded catalogue, exact logical resources and explicit Meta targets; runtime loading uses ordinary authorization |
| [tool](src/main/scala/com/weisgnosis/agents/core/tool/README.md) | Typed tools, immutable host-selected groups and pure scoped notes, attached fixed raw arguments, host-selected descriptive output schemas, immutable registry, typed completed observations, path screening |
| [client](src/main/scala/com/weisgnosis/agents/core/client/README.md) | Client port, scripted clients, validated text/user-media/function requests with inline image/audio Data and explicitly hinted image URL projection, explicit user WebM/MP4 video serialization, scoped HTTP completion, SSE framing, indexed assembly, explicit Google tool-signature replay and selected reasoning details |
| [agent](src/main/scala/com/weisgnosis/agents/core/agent/README.md) | ReAct lifecycle, selected-model request-only history/settings projection including optional topP, frequency/presence penalties and exact integer seed; separate host tool-choice and typed parallel-call steering preserve tool authority, run-owned group activation, preloaded skill loading and snapshot-consistent descriptions/results, interruption, provider-owned pending-call continuation, thinking retries, structured output, host acting-stop decisions, complete-local-batch denial observations and opt-in ordinary return-direct completion |

Typed failures are defined in [AgentError.scala](src/main/scala/com/weisgnosis/agents/core/error/AgentError.scala). Provider extensions, remaining event integrations, broader model-routing policies and exhaustive upstream parity remain pending; explicit bounded model failover is documented below.

ReActAgent retains in-memory history and serializes runs. Resources are scoped per run/request; cancellation reconciles unfinished calls without overwriting completed observations. See [architecture](../../ARCHITECTURE.md) and the [parity audit](../../docs/architecture/test-parity.md).

Tool.encodeResultChecked preserves custom projections by default and gives Tool.fromFunction exact JSON runtime output after joined cleanup. Typed projections permit Success/Error observations. ToolExecutionResult now stores payload/state; String constructors and its text projection remain available, while generated copy/unapply and JSON shape change. Runtime call identity/control metadata remain protected. See the [migration and limits](src/main/scala/com/weisgnosis/agents/core/tool/README.md#typed-execution-observations); the current HTTP request codec still rejects non-text tool output.

The [incremental local execution contract](src/main/scala/com/weisgnosis/agents/core/tool/README.md#incremental-execution) separates provisional text/data events from one final typed observation; both independent reviews and full verification passed. Existing completed payloads and provider limitations retain their contracts.

An [explicit tool-image profile](src/main/scala/com/weisgnosis/agents/core/client/README.md#tool-image-result-projection) has final scoped acceptance: TextOnly remains default, while compatible endpoints may receive successful ordered Text/Image tool observations. Both source reviews and full RC1/artifact/consumer verification pass; final combined documentation approval passed.

The bounded [inline video projection](src/main/scala/com/weisgnosis/agents/core/client/README.md#inline-video-projection) has source/focused/full/package approval; consumer verification passed; final combined documentation acceptance passed. It changes no public signature or configuration arity and requires a compatible host-selected endpoint.

The additive [opaque-signature reasoning profile](src/main/scala/com/weisgnosis/agents/core/client/README.md#opaque-signature-details) has final independent source approval and focused verification; full RC1/artifact/consumer verification passes; final combined documentation approval passed. Existing profile admission remains unchanged; hosts explicitly select compatible endpoints.

## Current review acceptance

- [x] review_fp and review_invariants approved exact domain/tool/event metadata output and typed parallel-call steering, with no findings. The [JSON contract](src/main/scala/com/weisgnosis/agents/core/json/README.md#verification-and-acceptance) and [client contract](src/main/scala/com/weisgnosis/agents/core/client/README.md#optional-parallel-tool-call-steering) record exact reviewed identities and full verification, including packaged-consumer execution.

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

The additive [ToolAuthorizer.preflight contract](../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#interactive-batch-preflight) supports stable terminal denials before remaining authorization. Default Continue grants no authority; live conditions remain execution-time decisions. Final source/full verification passed; artifact verification passed; final combined documentation approval passed in that contract.

# ReAct execution and structured output

ReActAgent retains history and serializes runs. Model requests receive their own Scope, closed before the next request. Assistant tool calls are persisted before dispatch. Completed observations survive cancellation; unfinished local occurrences receive Error observations followed by one recovery message for the run. Thinking-only responses trigger bounded synthetic reminders. AgentConfig uses ToolExecution and a five-minute timeout; AgentResult uses AgentRunState and optional ChatUsage.

- [AgentConfig](AgentConfig.scala)
- [AgentResult](AgentResult.scala)
- [PostReasoningHook](PostReasoningHook.scala)
- [ReActAgent](ReActAgent.scala)
- [StructuredOutput](StructuredOutput.scala)

## Runtime events and result pairing

One correlated AgentStart/AgentEnd pair surrounds each engine attempt. End follows loop termination/reconciliation, including failure or interruption. Successful preparation commits history and publishes one runtime AgentResultEvent before End. Ordinary cooperative interruption publishes its recovery reply; failed structured preparation publishes no finalized result. Streaming uses a private Take queue carrying the original exit, joins the claimed event prefix, then drains the buffered suffix before termination. A provider-published AgentEnd cannot terminate delivery. Early consumer completion closes owned producer/model scopes. Publishers must remain within those scopes; the unbounded hub/queue do not provide backpressure.

After all configured iterations complete without a final response, the runtime emits exactly one ExceedMaxItersEvent with the attempt's replyId, configured maximum, and completed iteration count before the normal end. Completion on the last permitted iteration does not emit a limit event. Unlike the pinned upstream guard, this event never intentionally uses an empty replyId.

Completed provider-hosted inline calls bypass local execution and do not create Role.Tool messages. Each inline observation is consumed once by call ID and occurrence, retaining pending call order. Local calls sharing a server ID remain executable. For each inline result, publication is Start, one TextDelta or DataDelta per output block in original order, then End. Empty output emits only boundaries; state and full result metadata are preserved. Data deltas carry the correlated replyId. Full metadata propagation is an intentional Scala difference: pinned upstream inline events synthesize only the server-tool marker.

Cancellation reconciliation also consumes completed local observations by occurrence, matching a provided tool name as well as ID. A completed result cannot suppress every unfinished same-ID call, and parallel completion of a different named tool does not satisfy a pending call. Nameless observations match by ID/occurrence. Completed observations remain unchanged.

## Structured output

StructuredOutput supports native formatting and synthetic generate_response with root definition hoisting. Synthetic output completes only after acting finishes in the Completed state, without another model turn. Ordinary tool pairs and unrelated empty history remain. Final replies retain signed thinking exactly once and generating-request usage; AgentResult usage aggregates the run. Incomplete runs fail with AgentError.Interrupted and retain recovery history even after payload capture.

runStructured and streamStructuredEvents share the same preparation and commit path. Structured payload resolution selects existing metadata or parses visible text exactly once; that AST supplies both decoding and committed metadata. Preparation remains interruptible and timeout-bounded. A short masked section commits finalized history and publishes AgentResultEvent before AgentEnd. Cancellation after this commit may follow an already published result. Auto fallback retains two attempt boundary pairs, with no result from the failed native attempt; the run lock covers both attempts. Input event payloads and ToolCallState metadata do not grant runtime permission or stop/resume authority.

## Model request correlation

ReAct allocates a fresh requestId under the current attempt replyId and passes ModelStreamContext explicitly to LlmClient.streamTurn. ModelRequestStart precedes invocation; ModelResponseEnd follows a non-interrupted result and request-scope cleanup. HTTP block deltas may be observed before completion, but incomplete/malformed responses never reach local dispatch. The [client contracts](../client/README.md) describe live ordering, source migration, and remaining parity limits.

## Completed tool observations

All four result-event variants now retain the originating ModelStreamContext. Local execution publishes Start, executes interruptibly, then records the completed observation before publishing its text Delta and End under the existing interruption mask. Empty text remains an explicit empty delta. Normalized failures remain Error observations and continue the loop. Each parallel call keeps its own ordered events; completion order may differ from call order. Later turns reusing a call ID remain distinguishable through requestId.

Inline provider results share the completed-output publisher, preserve text/data ordering and metadata, and remain inside the assistant message without separate Tool messages. Local tools produce completed text observations. An optional ToolResultProcessor rewrites payload blocks and metadata before completed publication; partial tool execution and native structured tool return values remain pending. Cancellation preserves completed results/events and reconciles unfinished history; it does not publish a false ToolResultEnd for interrupted work. AgentEnd still terminates the attempt.

## Strict structured JSON

Msg.getStructuredData and structured agent preparation share core.json.JsonBoundary. Text must contain one complete JSON value with unique object keys at every depth. Authoritative structured metadata is checked directly; invalid metadata fails without falling back to visible text. Captured synthetic payloads are checked before finalization. The synthetic tool also validates its entire input before extracting response, so duplicate wrapper keys or duplicates in ignored siblings cannot disappear during extraction. Valid wrapped and direct payload adaptation remains supported.

This tightens previously permissive parsing; trailing input and ambiguous fields now fail through existing typed/string error channels. It does not add JSON Schema validation. Failed structured preparation publishes no AgentResult or structured metadata; raw observations can remain in history. The shared validator performs no effects and does not depend on models, agents, or transports.

## Capability selection

LlmConfig.structuredOutputCapability is a host declaration for the configured model/endpoint, not automatic discovery. Unknown is the default for source and JSON compatibility. Native() permits application tools by default; Native(NativeToolSupport.Unsupported) restricts native formatting to requests without application tools. Unsupported cannot declare tool support independently.

| Declaration | Auto without application tools | Auto with application tools |
| --- | --- | --- |
| Unknown | Native with rejection fallback | Synthetic |
| Unsupported | Synthetic | Synthetic |
| Native() | Native with rejection fallback | Native with rejection fallback |
| Native(NativeToolSupport.Unsupported) | Native with rejection fallback | Synthetic |

Explicit Native and SyntheticTool modes override this selection. Native installs the supplied structured schema. Every synthetic attempt clears responseFormat while retaining other configuration, including when falling back from a rejected native attempt. Ordinary runs preserve the host's responseFormat. Auto native attempts still fall back on ModelError status 400, 422, or 501; other errors and cancellation propagate. This status policy is broader than provider-specific capability error classification.

The declaration is serialized with LlmConfig but excluded from the HTTP request body. Existing constructor calls retain their defaults; positional pattern matches against LlmConfig must account for the new final field. Unknown preserves prior routing, while declared Unsupported avoids a native probe entirely.

## Current acceptance

- [x] The accepted runtime baseline through `06bd2e1` includes lifecycle cleanup, finalized result ordering, occurrence-based reconciliation, correlated model/tool events, strict structured JSON, and loopback HTTP completion. Git preserves the earlier reviewer approvals and exact identities.
- [x] `review_fp` and `review_harness` approved the final five-file capability increment against `06bd2e1` on 2026-09-29 after the preset-format correction. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 420 core and 40 harness tests.
- [ ] Complete general model-call middleware and remaining legacy hook behavior.
- [ ] Complete provider continuation, incremental/structured local tool results, full event ordering, and event adapters.
- [ ] Integrate phase stopping, durable pending work, and harness middleware.

The [parity mapping](../../../../../../../../../../docs/architecture/test-parity.md) distinguishes verified contracts from remaining scenarios. General JSON Schema validation and exhaustive provider compatibility are not claimed.

The [client capability table](../client/README.md) records unchanged capability identities; commit `02b4654` preserves all five identities from that increment.

## Transformed live-model text

The optional [ModelTextTransform](../client/ModelTextTransform.scala) on ChatCompletionsClient replaces visible fragments before accumulation and event publication. Native structured extraction receives that same transformed text and still enforces complete unambiguous JSON. The real HTTP fence-removal scenario closes the mapped text-replacement behavior with an explicit typed adaptation; it does not establish general model-call or post-reasoning middleware parity.

- [x] `review_fp` and `review_harness` approved the final seven-file text-transformation increment against `02b4654` on 2026-09-29 after the fallback-provenance correction. No findings remain within this scope. Full formatting, fatal-warning compilation, and verification passed 434 core and 40 harness tests. Exact identities are in the [client README](../client/README.md); ReAct source was unchanged by this increment.

## Post-reasoning policy and invocation usage

ReActAgent.make and Builder.postReasoningHook accept a PostReasoningHook, defaulting to Keep. The suspended callback receives ModelStreamContext and the completed model message, and returns ZIO[Scope, AgentError.ReasoningHookError, ReasoningDecision]. The closed decision algebra is Keep, Replace(message), or Discard. The dedicated error channel prevents host-policy failures from triggering Auto's provider-rejection fallback; external errors require explicit mapping.

The model request scope closes and its successful ModelResponseEnd is published before policy evaluation. The hook owns a separate nested scope inside the engine-attempt timeout and cooperative cancellation race. Its scope closes before any accepted assistant message is persisted or tools dispatch. Interrupted model output bypasses the hook and retains normal reconciliation. Existing model block events and model-end metrics describe the original observation; replacement/discard does not rewrite already published events. Committed history, final AgentResult, and dispatched tools follow the accepted decision.

Keep retains the original TurnOutput. Replace requires an assistant message, restores the original canonical usage and legacy _chat_usage metadata, then classifies its content as tool calls, thinking-only, or final text. A host cannot mint billing metrics through either usage representation. Replaced tool calls are persisted before execution. Replacing calls with final text prevents dispatch. Invalid roles fail before persistence. Discard persists no model message or pending call and performs another iteration, consuming the iteration budget without replenishing the thinking retry budget. Repeated discards reach the normal iteration limit; they do not create dangling tool history.

Usage is now accumulated from returned model observations in one invocation-local Ref, using canonical-first chatUsage. It includes discarded and returned interrupted outputs, spans Auto native/synthetic attempts, excludes supplied history/new input, and resets for each public invocation. Accounting updates are briefly masked after model-scope completion. ModelResponseEnd uses the same canonical-first usage view. Synthetic completion transfers canonical and legacy usage exactly once, clearing both representations from any retained residual generating assistant. Reply usage remains the generating request's usage; AgentResult usage is the invocation aggregate.

Appending the optional make parameter preserves ordinary callers; positional Builder pattern matches must follow the current Builder constructor. The hook and builder are in-memory runtime values, not durable policy serialization. ReasoningDecision has a derived codec, but decoded replacements still pass runtime validation. This API covers replacement/removal through typed decisions; legacy mutable hooks, stop/goto-reasoning commands, and general model-call interception remain separate work.

- [x] `review_fp` and `review_harness` approved the final six-file post-reasoning increment against `2a400e0` on 2026-09-29 after the legacy-usage correction. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 456 core and 40 harness tests.

## Current post-reasoning identities

Unchanged post-reasoning identities from the scope against `2a400e0`; commit `8850a60` preserves its full scope. Current ReActAgent and AgentError identities belong to the history-preparation scope below.

| File | Git blob |
| --- | --- |
| [PostReasoningHook.scala](PostReasoningHook.scala) | 9817ce08dfefcbf69792204c8169914406e31fb9 |
| [StructuredOutput.scala](StructuredOutput.scala) | 5d6d81c944530f8ff7094733abfc21885729e456 |
| [PostReasoningHookSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/PostReasoningHookSpec.scala) | 0a024cbdaf20b5a7d3be015b30a60c07118310ab |
| [ReActAgentReasoningHookSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentReasoningHookSpec.scala) | 3eefd816ae8cc44a7346f8eec63d1b2fcc01191f |

## Local-result processing boundary

ReActAgent.make and Builder.toolResultProcessor accept a ToolResultProcessor, defaulting to identity. Its suspended process callback returns ZIO[Scope, AgentError, ToolResultPayload]. The payload contains output blocks and metadata only. applyTo preserves the original call ID, name, state, and serverTool/agentscope_server_tool/agentscope_suspended control keys. Composition applies each payload safely before passing the observation onward; host processors cannot change execution authority through these fields.

After local execution finishes, the runtime atomically appends the complete raw observation and captures its history position. It then runs processing interruptibly in a separate scope. Typed processing failures restore the original payload; defects propagate. Scope cleanup finishes before a short masked section replaces that exact observation and publishes its text/data deltas and End. Parallel calls own distinct append positions, including calls that reuse IDs; completion may arrive in a different order. Processing assumes history remains append-only during the acting phase under the run lock.

Cancellation during processing keeps the completed raw result and reconciles only unfinished executions. It does not rerun the tool or publish a false ToolResultEnd. A subsequent model request sees the committed processed payload; observers may see the raw result while processing is pending. Processor side effects already performed before failure/cancellation are not rolled back. Callbacks must not reenter a mutating agent operation that requires the held run lock.

Tool execution still returns completed text. A processor can replace its output blocks and metadata for the existing typed result publisher; this does not implement incremental tool execution or native structured tool return values. Empty processed output emits only End after the original Start. Matched inline provider results also use this processor. Supplied history uses the separate opt-in ToolResultEvictor.preparer adapter.

Appending the optional make parameter preserves ordinary callers; positional Builder pattern matches must follow the current Builder constructor. Processor values are runtime dependencies, not serialized agent configuration.

- [x] `review_fp` and `review_harness` approved the final eight-file local-result processing/eviction increment against `8850a60` on 2026-09-29. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 466 core and 45 harness tests. Exact current identities are in the [tool package table](../tool/README.md). The post-reasoning ReActAgent identity is preserved in `8850a60`.

## Authorization before local execution

The optional ToolAuthorizer runs after assistant persistence and before each local tool dispatch, with a fresh request identity and originating model context. Approved input is validated and committed to its exact call occurrence; changed inputs carrying either provider reasoning metadata key are denied. Failures and defects fail closed to Denied observations, while cancellation preserves completed work and reconciles unresolved calls. Matched inline results bypass the gate. See the [tool contracts](../tool/README.md) for argument provenance, scope ownership, failure semantics, migration, and exact current identities. The default allows calls; the harness InteractivePermissionAuthorizer provides opt-in policy/interactive confirmation. Durable approval resumption remains pending.

- [x] `review_fp` and `review_harness` approved the final five-file core authorization increment against `a34201f` on 2026-09-29. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 481 core and 45 harness tests, including fifteen added authorization tests. Interactive and durable policy adapters are excluded from this acceptance. See the [authorization table](../tool/README.md) for exact identities.

## History preparation before model requests

ReActAgent.make and Builder.historyPreparer accept HistoryPreparer, defaulting to identity. prepare returns ZIO[Scope, AgentError.HistoryPreparationError, Chunk[Msg]]. It runs before every model request, including thinking retries, discarded reasoning, and both Auto attempts. Preparation occurs only after prior authorization, tool execution, and result processing finish. Its scope closes before validation, canonical history commit, and ModelRequestStart. It shares the engine-attempt timeout and cooperative cancellation race; callbacks must not reenter mutating agent operations while the run lock is held.

An equal candidate preserves the existing permissive supplied-history API without adding global validation. An actual rewrite must preserve the exact ordered system-message subsequence and contain complete occurrence-aware tool pairing, with no unmatched inline/local results or unfinished calls. The internal shared ToolHistory analysis isolates provider inline results to their own assistant message and matches local observations by ID and optional name. A changed incomplete history fails with HistoryPreparationError before state commit or model dispatch. A trusted rewriter may remove old incomplete context; structural validation cannot prove summary fidelity or semantic preservation. It is not a provider-specific request validator.

The harness ConversationCompactor.preparer installs compaction through this port and maps summarizer/hook errors to the distinct preparation error. Such errors cannot masquerade as native-format ModelError rejection and trigger Auto fallback. Typed failure leaves canonical input history unchanged. Defects propagate; cancellation closes preparation resources and appends the current attempt's recovery. External flush/offload effects already completed cannot be rolled back.

Recovery ownership is attempt-local: Ready before preparation, PersistedAssistant(position) after accepted assistant or interrupted partial output, and Recovered(message) after reconciliation. History and progress writes use a narrow interruption mask. The persisted position is valid only during the append-only active tool phase and is cleared before another rewrite. Nested cancellation finalizers reuse the exact recovery value; a later run, including empty-input continuation, owns a fresh recovery. Unaccepted hook output and supplied historical calls never become the current reconciliation target. Invocation usage remains independent of rewritten history and shared across Auto attempts.

Appending the optional make argument preserves ordinary callers; positional Builder matches must follow the current Builder constructor, including historyPreparer. Adding HistoryPreparationError requires exhaustive AgentError matches to handle the new leaf. Preparer values are runtime dependencies, not serialized configuration. Core remains independent of harness implementations.

- [x] `review_fp` and `review_harness` approved the final nine-file history-preparation increment against `3c6958f` on 2026-09-29. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 497 core and 73 harness tests, including twenty added tests. Acceptance covers structural rewrite validation and scoped compactor integration; semantic summary fidelity and exhaustive provider-format validation are excluded.

## Current history-preparation identities

Unchanged identities from the scope against `3c6958f`; commit `2b99226` preserves the full nine-file scope. The current ReActAgent identity is recorded below:

| File | Git blob |
| --- | --- |
| [ToolHistory.scala](../model/ToolHistory.scala) | d6e19ed655b9df7a46a9bcfc0835b14ac858ebfe |
| [ReActAgentHistoryPreparerSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentHistoryPreparerSpec.scala) | 9f98df0f18d26e42766f1a41cdfe8c441b33c329 |
| [ToolHistorySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/model/ToolHistorySpec.scala) | 29ff87da90673afcaef220ab0ff5ec1593da508a |
| [ConversationCompactor.scala](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/memory/ConversationCompactor.scala) | 25d2843a2c2407773ae1871f1e168a595f14f617 |
| [ConversationCompactorIntegrationSpec.scala](../../../../../../../../../harness/src/test/scala/com/weisgnosis/agents/harness/memory/ConversationCompactorIntegrationSpec.scala) | caaf0660a6d54398e56fab046158c82758d6ea75 |

## Inline processing and failed acting phases

Matched inline provider observations now pass through the optional ToolResultProcessor after the raw assistant message is persisted. Processing owns an interruptible scope; cleanup finishes before the exact assistant/result occurrence is rewritten and Start/ordered text-data deltas/End are published. Duplicate IDs and identical result values retain independent ordinals. Processor payloads preserve runtime-owned identity, execution state, and control metadata. Typed failures retain raw content; partial processing cancellation retains already committed siblings and raw unprocessed inline observations. Provider completion never becomes a local Tool message, and final inline-only replies use the canonical processed assistant.

A bounded acting-phase finalizer runs after failed processors and parallel siblings close. For a non-interruption-only failure, it appends Error observations only for unrecorded local calls and preserves the original cause. It publishes neither synthetic completion events nor a user-interruption recovery. Pure cancellation continues to reconcile unfinished calls and append one recovery message. Work may have performed external effects before aborting; an Error observation does not claim otherwise.

Model, hook, history-preparation, and acting races now explicitly own a scoped worker. Only worker completion waiting races the cooperative signal. Both cooperative and external cancellation interrupt and await the worker, retaining non-interruption failures from resource cleanup. Successful completion inherits worker fiber references before returning its exact exit. This closes the previously reproduced loss of processor-finalizer defects during cancellation.

Installing a non-identity processor now includes matched inline observations; the identity default preserves payload behavior. Supplied-history eviction is available through an opt-in preparer; exhaustive upstream middleware parity remains pending.

- [x] `review_fp` and `review_harness` approved the final inline-processing and cancellation-cleanup scope against `2b99226` on 2026-09-29. Fourteen added regressions and the full formatting/fatal-warning verification passed: 510 core and 74 harness tests. No findings remain in this scope. Supplied-history eviction is covered by a later scope; exhaustive upstream middleware parity remains pending.

## Current inline-processing identities

Unchanged identities from the seven-file scope against `2b99226`; commit `cc8fa7b` preserves its complete source identities. Changed eviction identities belong to the eviction table linked below:

| File | Git blob |
| --- | --- |
| [Msg.scala](../model/Msg.scala) | d8c616052015b59c18b69cd52bfa66840edfcae6 |
| [ToolResultProcessor.scala](../tool/ToolResultProcessor.scala) | 8f9b40dca0a19f0b716eb02bc706405a3cfc941f |
| [ReActAgentInlineProcessingSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentInlineProcessingSpec.scala) | e9c65325833d69a340fb596f1cada358e3d257f1 |
| [ReActAgentToolResultProcessorSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentToolResultProcessorSpec.scala) | 9c76a00f1c3309520ed0823ff91e8c6ac49b3be8 |

Superseded history/eviction source identities remain in `cc8fa7b`; changed identities are in the [eviction table](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/eviction/README.md).

- [x] `review_fp` and `review_harness` approved the final nine-file history-eviction scope against `cc8fa7b` on 2026-09-29. Twenty-two added regressions and full formatting/fatal-warning verification passed: 516 core and 90 harness tests. Two new regressions exposed and closed strict-number and Windows storage-layout gaps. No findings remain within store-backed integrity, explicit workspace trust, and validated composition; absolute storage is covered by a later scope; request-only input views are covered by a later scope. See the [eviction contract/table](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/eviction/README.md).

HistoryPreparer.andThen runs stages in order within the caller's request scope. Each changed intermediate and final output is structurally validated before proceeding, so later stages cannot conceal an invalid prior rewrite. Failure/cancellation skips remaining stages and releases earlier resources. Only the complete prepared history is committed by ReAct; external stage side effects are not transactional. The eviction adapter can precede compaction to save raw payloads before pruning.

Superseded model-input scope identities remain in `40f6f05`; changed identities are in the [agent model-input table](README.md).

## Request-only model history

ModelInputTransform is distinct from HistoryPreparer: it returns a transient model-request view and has no canonical write authority. Install it through the optional ReActAgent.make argument or Builder.modelInputTransform; the default identity preserves existing callers. Its lazy callback receives ModelStreamContext and already-prepared canonical history, returning ZIO[Scope, AgentError.ModelInputError, Chunk[Msg]].

The runtime prepares, validates, and commits canonical history first, allocates a request context, runs projection in a separate interruptible scope, closes that scope, validates the view, then publishes ModelRequestStart and invokes the client with the view. The same context reaches model events. This runs for every request, including thinking retries, discarded reasoning, and native/synthetic Auto attempts. It does not change tool definitions or model configuration.

Shared HistoryRewrite validation retains unchanged-input compatibility and requires changed views to preserve exact ordered system messages and complete occurrence-aware call/result pairing. A host may omit complete closed pairs or rewrite request-only content/state/metadata. Structural checks do not prove semantic fidelity or provider grammar. The host controls projection semantics and any additional pruning of new content it injects.

Projected values never replace stateRef, AgentResult.history, accepted model output, recovery positions, or usage accounting. Canonical eviction therefore survives a view that removes its result; removed pairs are not reintroduced into the request. ModelInputTransform alone does not run eviction on newly injected view content. Install canonical eviction through ToolResultEvictor.preparer or immediate completion processing as appropriate.

Typed projection failures and invalid views use ModelInputError, never provider ModelError, and cannot trigger native-format fallback. Defects propagate. Cooperative/fiber cancellation and timeout close projection resources before returning, preserve prepared canonical history, and retain the existing recovery contract; cleanup defects are preserved rather than reported as a clean interruption. A shorter view cannot change the position used to reconcile a new in-flight call. Completed preparation side effects/state are not rolled back when projection fails. Callbacks must not reenter operations needing the held agent run lock.

The make argument is additive; positional Builder matches must follow its current constructor. Exhaustive AgentError matches must include ModelInputError. Callback values remain runtime dependencies, not serialized configuration. This history-only port is not the complete upstream ReasoningInput/middleware API; Java object identity and mutable hook ordering are intentionally adapted to immutable values and deterministic runtime authority.

- [x] `review_fp` and `review_harness` approved the final seven-file model-input scope against `40f6f05` on 2026-09-29. Sixteen added regressions and full formatting/fatal-warning verification passed: 529 core and 101 harness tests. No findings remain within request-only projection, structural validation, canonical-state isolation, and eviction integration. Broader legacy hook/middleware parity remains pending.

## Current model-input identities

Final formatted scope against `40f6f05`:

| File | Git blob |
| --- | --- |
| [HistoryPreparer.scala](HistoryPreparer.scala) | 088d1464febe6be050ce43856305a0efe3b7f0e5 |
| [ModelInputTransform.scala](ModelInputTransform.scala) | 4dd6b57426cdc37ac37c7453a75eb7159acdba99 |
| [ReActAgent.scala](ReActAgent.scala) | 1edf2d31529e00a994de063aa2b486ac6f712d40 |
| [AgentError.scala](../error/AgentError.scala) | a38fbf1d56ae73e0951a6142d1d4d9ed067155a5 |
| [ModelInputTransformSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ModelInputTransformSpec.scala) | 8a42440fcdbc2d5662cb67fbbf541483f2b865e5 |
| [ReActAgentModelInputSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentModelInputSpec.scala) | 84d4648bf879e523cbf39f8d3bce74a2f90fac99 |
| [ToolResultEvictorHistorySpec.scala](../../../../../../../../../harness/src/test/scala/com/weisgnosis/agents/harness/eviction/ToolResultEvictorHistorySpec.scala) | 3070eb8d3caf57c99243fb7675b5fbbe8d7f25e8 |

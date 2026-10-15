# ReAct and structured-output verification

InvocationContextSpec tests one identity across Auto attempts and hooks, fresh original/restored resume and pre-model failures. PreparedInvocationSpec checks lazy reservation, one-shot overlapping execution, queued cancellation isolation and joined cleanup defects. Existing HistoryPreparerSpec, ModelStreamContextSpec, ReActAgentForwardingSpec and AgentRunUpdateSpec groups retain exact contextual composition, codec, ordered forwarding and resumed-result assertions. PreparedInvocation additionally exposes its own typed status, cancellation and result-bearing stream controls; it does not clone Java AgentRun subscription semantics.

The external admission table checks decoder-valid/schema-invalid initial inputs and approved edits before host handoff. A separate authenticated legacy-wait fixture preserves previously issued input without reauthorization or replay. Tool-group integration assertions retain exact observation cardinality and identity while allowing validation failures before executable siblings. See the [schema admission contract](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#runtime-schema-admission) for current contracts.

The [failure fixtures](../tool/README.md#failure-fixtures) cover exact mixed Error/Success terminal events and history during parallel execution.

The [external execution checkpoint scope](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md#external-execution-checkpoints) is exercised by [ReActAgentExternalToolSpec](ReActAgentExternalToolSpec.scala), covering mixed execution, checked ordinal resume, issued legacy work and lifecycle/authority boundaries.

ReActAgentSpec checks the custom-client turn contract for malformed role/classification and permitted controls. Scoped client cleanup completes before rejection; invalid output must not change history, run post-reasoning hooks or succeed.

Toolkit construction and selection follow the [checked registry identity and migration contract](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#checked-registry-identity).

The mixed synthetic-completion case verifies exact minimum-Long closing text, equality with typed/metadata values, closing history identity and unchanged one-request usage/local-call pairing.

Fully drained invocations begin with AgentStart and end with correlated AgentEnd; early consumers abandon later output.

The [host-resume integration](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#host-only-session-resume) exercises the shared internal resume-forwarding path with retained checkpoint reply identity, a fresh execution source, held cleanup defects and ordered terminal delivery.

The request-projection tables additionally verify topP, frequency/presence penalty and seed replacement/clearing, intermediate validation and invalid original settings before custom-client dispatch.

[AllToolsDenied observation](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md#all-tools-denied-observation) is covered by ReActAgentToolAuthorizerSpec: complete repeated-ID batches across restoration, Deferred/Stop and state exclusions, native/synthetic continuation, then held processor cleanup and cancelled full-cause preservation. Tool-produced Denied is a negative control normalized to Error. Provider-ownership assertions require that same-name/ID provider calls bypass local effects, mixed cancellation reconciles only the local occurrence, repeated pauses remain bounded and a serialized provider-only checkpoint resumes.

[ReActAgentReturnDirectSpec](ReActAgentReturnDirectSpec.scala) covers reverse-completion/repeated-ID processed presentation and rejection tables; live/restored/supplied/partial completion with exact-once processing and trusted definition matching, including supplied Error and mixed modes that preserve observations for exactly one following model request without local replay; held cleanup/cancellation and native/synthetic exclusion. Existing ToolDef/request tables cover default-mode compatibility and provider omission.

The [checkpoint-event scope](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#checkpoint-permission-events) covers ordinal/restore/input validation and joined phase draining; generic event correlation does not establish human-source or durable-delivery authority.

The [current contract](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#partial-checkpoint-confirmations) covers checked ordinal-position ledgers, successive restored subsets, preserved inline/completed processing, saved activation and Stop state, structured rejection and joined cancellation.

[AgentRunUpdateSpec](AgentRunUpdateSpec.scala) covers resumed streams: lazy validation/claim, supplied-event correlation and terminal delivery, exact mixed observer failure, and joined early/cancelled consumption.

[PendingActingSpec](PendingActingSpec.scala) and [ReActAgentResumeSpec](ReActAgentResumeSpec.scala) cover checkpoint codecs/occurrence validation, mixed saved batches on the original or restored runtime, host binding and activation checks, inline-only completion/reset, unsupported boundaries and iteration limits, and joined cancellation.

[SuppliedToolResultSpec](SuppliedToolResultSpec.scala) and [ReActAgentSuppliedResultSpec](ReActAgentSuppliedResultSpec.scala) cover atomic checked augmentation, live/restored partial/all supplied batches, original-ordinal Stop after full processing, and held processor cancellation preserving every supplied result. Local dispatch and authorization run only for unsupplied calls.

[Acting-stop decisions](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md#acting-stop-decisions) cover original-ordinal selection across mixed inline/parallel calls and later reuse; caller-owned forwarding cancellation with held processor cleanup; native/synthetic structured rejection before captured decoding. Cancelled public stream consumers do not promise tail delivery.

The extended post-reasoning table verifies opaque-only reminders and empty-array controls; the authorization table rejects fixed-input changes for protected sibling calls before consulting the authorizer.

These ZIO Test suites cover selected upstream scenarios and Scala runtime regressions. Exhaustive upstream parity and complete runtime event ordering remain pending.

- [ReActAgentToolGroupsSpec](ReActAgentToolGroupsSpec.scala)
- [AgentRunUpdateSpec](AgentRunUpdateSpec.scala)
- [ReActAgentDeliverySpec](ReActAgentDeliverySpec.scala): bounded diagnostic delivery, independent execution outcomes and joined cancellation scenarios.
- [PostReasoningHookSpec](PostReasoningHookSpec.scala)
- [ReActAgentReasoningHookSpec](ReActAgentReasoningHookSpec.scala)
- [AgentConfigSpec](AgentConfigSpec.scala)
- [AgentResultSpec](AgentResultSpec.scala)
- [ReActAgentActingStopSpec](ReActAgentActingStopSpec.scala)
- [ReActAgentToolResultProcessorSpec](ReActAgentToolResultProcessorSpec.scala)
- [ReActAgentToolEventSpec](ReActAgentToolEventSpec.scala)
- [ReActAgentEmissionSpec](ReActAgentEmissionSpec.scala)
- [ReActAgentEventSpec](ReActAgentEventSpec.scala): checks fully drained empty/nonempty results, same-agent reuse and exact correlated boundary/result counts under a five-second liveness guard.
- [ReActAgentInterruptSpec](ReActAgentInterruptSpec.scala)
- [ReActAgentLifecycleSpec](ReActAgentLifecycleSpec.scala)
- [ReActAgentSpec](ReActAgentSpec.scala)
- [ReActAgentThinkingRetrySpec](ReActAgentThinkingRetrySpec.scala): enforces reminder-dependent retry and exact independent retry/iteration bounds under the [thinking-only retry contract](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md#thinking-only-retry-contract).
- [StructuredCompletionSpec](StructuredCompletionSpec.scala)
- [StructuredOutputDynamicSpec](StructuredOutputDynamicSpec.scala)
- [StructuredJsonBoundarySpec](StructuredJsonBoundarySpec.scala)
- [StructuredOutputHttpSpec](StructuredOutputHttpSpec.scala)
- [StructuredOutputCapabilityRoutingSpec](StructuredOutputCapabilityRoutingSpec.scala)
- [StructuredOutputSpec](StructuredOutputSpec.scala)

## Incremental local results

ReActAgentToolEventSpec verifies actual provisional text/image delivery before completion, persisted assistant calls without provisional Tool history, reserved-metadata filtering, final model input after cleanup and occurrence-local suppression across parallel repeated IDs. The legacy sibling still publishes its aggregate. Cancellation coverage includes a streaming unfinished sibling. Processor and inline-provider suites retain their separate contracts.

## Run-owned group verification

[ReActAgentToolGroupsSpec](ReActAgentToolGroupsSpec.scala) covers preloaded-skill contracts: sequential load union and exact content with next-request/fresh-run isolation; strict input/authorization/configuration and hidden-control collisions; native fallback and content-only Auto routing. It also covers next-request activation with one admitted batch snapshot and fixed policies; exact invalid/denied reset outcomes with Meta-only discovery and External isolation; fresh ordinary/streamed invocations and joined cancellation; native fallback after a successful reset with synthetic controls retained after deactivation; empty-host Auto routing and complete-base collision rejection. Reset flags exercise sequential dispatch even under Parallel configuration. Existing assertions verify descriptions match the admitted request snapshot, transition active/inactive notes across turns, and return notes from the same successful reset result while excluding External names/descriptions.

Deferred-reader integration verifies ordinary authorization before reader effects, unchanged activation after read/finalizer failure, success visibility only on the next request and actual cancellation joined through held failing cleanup.

## Result-bearing stream evidence

- AgentRunUpdateSpec verifies one actual ordinary execution with full history/aggregate usage and cleanup-before-result, a cooperatively interrupted result with one recovery, decoded structured values and exact mixed terminal causes, and held cleanup on early consumer completion or caller cancellation. No result is fabricated on failure.

[ReActAgentDeliverySpec](ReActAgentDeliverySpec.scala) specifies barrier-based overflow, execution authority and cancellation scenarios through public streams. Direct forwarding publishes through the source-bound destination without an intermediate forwarding worker. The [diagnostic queue scenarios](../event/README.md#diagnostic-publication-and-budget) cover admission and exact byte accounting separately. Execution and delivery failures remain distinct, including when cleanup also fails.

- Separate [harness integration evidence](../../../../../../../../../harness/src/test/scala/com/weisgnosis/agents/harness/subagent/README.md) covers child forwarding and promotion wiring. Early consumers can abandon later observer errors.

## Typed local-result evidence

[ReActAgentToolResultProcessorSpec](ReActAgentToolResultProcessorSpec.scala) verifies ordered text/media observations in history and correlated events, preserved Error state, and rejection of tool-supplied runtime control metadata. Legacy text tools retain their existing path. Media preservation here does not enable provider projection: ChatCompletions defaults to text-only tool output, with a separate explicitly selected Text/Image profile. Core tool tests separately cover unsupported states, result codecs and compound cancellation causes.

[LocalToolResultObserverSpec](LocalToolResultObserverSpec.scala) checks required observation after processed local-result persistence, approved dispatch identity, exclusion of synthetic/external observations, and joined failure/cancellation cleanup. [ModelResponseObserverSpec](ModelResponseObserverSpec.scala) checks accepted-response observation before local execution, actual selected input snapshots and failure reconciliation. Their callback contracts are distinct from optional result processing.

## Current evidence

- Lifecycle suites verify request/run ownership, timeout, cancellation, original failure propagation, private stream completion, ordered draining, and correlated boundaries.
- StructuredOutputSpec verifies schema hoisting, thinking/usage preservation, mixed tools, capture isolation, and interruption after capture.
- StructuredCompletionSpec verifies finalized-result ordering, exact metadata preservation, fallback boundaries, failures, and consumer cleanup.
- StructuredOutputDynamicSpec verifies both runtime-schema scenarios through direct and streaming APIs.
- ReActAgentEmissionSpec and ReActAgentToolEventSpec verify limits, inline metadata, occurrence matching, persistence before publication, normalized/empty observations, parallel completion, cancellation, and repeated IDs.
- StructuredJsonBoundarySpec and real-loopback StructuredOutputHttpSpec scenarios cover strict extraction, schema delivery, compression, usage/thinking, fallback, nonretryable errors, and invalid native JSON.
The [tracker](../../../../../../../../../../docs/architecture/tracker.md) is the sole current completion checklist; the parity reference records bounded mappings and intentional API adaptations.

StructuredOutputCapabilityRoutingSpec covers all declared/unknown capability routes, explicit overrides, application tool execution, native rejection fallback, and preset response formats. Client capability tests verify codecs, defaults, invalid declarations, and HTTP-body exclusion. These are local runtime tests; upstream mappings are recorded separately.

The [agent contracts](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md) and [parity mapping](../../../../../../../../../../docs/architecture/test-parity.md) describe runtime contracts and intentional adaptations.

## Post-reasoning verification

[ReActAgentReasoningHookSpec](ReActAgentReasoningHookSpec.scala) verifies same-agent gate reuse after typed hook failure.

The typed failure completes cleanup before a second same-agent invocation succeeds; exact call/cleanup counts exclude an automatic retry.

PostReasoningHookSpec tests verify decision/error codecs, suspended callbacks/default Keep, and compile-time exclusion of provider errors. ReActAgentReasoningHookSpec tests verify canonical/legacy usage ownership, typed discard continuation, replacement classification and persistence, iteration/thinking bounds, interrupted-output bypass, invalid roles/errors, scoped cleanup, cancellation/timeout, cross-attempt accounting, input-usage exclusion, invocation reset, correlated final publication, and single-transfer synthetic history.

The upstream null-message scenario is adapted to typed Discard followed by a valid capture. It establishes safe continuation without dispatching discarded calls, not nullable-hook API equivalence. General middleware and other legacy hook operations remain outside this scope.

## Shared-Toolkit destination isolation

ReActAgentToolEventSpec also holds two independently owned agents inside the same Tool stream from one shared Toolkit. Both enter before release; exact chunk/final sequences, distinct request/reply contexts and separate canonical histories reject cross-talk even with the same call ID. Scoped teardown releases the barrier before joining owned fibers. This is a bounded adaptation of Java's concurrent session callback contract, not concurrent-run support on one Scala agent.

## Completed-result processing verification

ReActAgentToolResultProcessorSpec scenarios verify raw persistence before processing, committed payloads before continuation/events, typed failure fallback, cooperative/fiber cancellation, reversed parallel completion with reused IDs, inline provider processing, composition cleanup, sequential/parallel defects, and failing processor finalizers. Cancellation assertions distinguish completed raw successes from genuinely unfinished calls.

## Scoped authorization verification

[ReActAgentToolAuthorizerSpec](ReActAgentToolAuthorizerSpec.scala) covers scenarios for persistence before approval, precise argument rewriting, denial events, typed/defect/finalizer failure normalization, ambiguous JSON, signed and opaque reasoning metadata, unchanged signed calls, cooperative/fiber cancellation, duplicate calls, inline pairing, and unmatched provider-marked local dispatch. This covers the core port, not interactive or durable policy adapters.

## History preparation verification

[HistoryPreparerSpec](HistoryPreparerSpec.scala) covers port validation and composition. [ReActAgentHistoryPreparerSpec](ReActAgentHistoryPreparerSpec.scala) covers lifecycle scenarios for cleanup/commit order, repeated cancellation, supplied-history isolation, invalid rewrite rejection, fallback provenance, interrupted partial output after shrinkage, thinking/discard budgets, and Auto attempt boundaries. Model tests cover shared occurrence analysis; harness tests cover the compactor adapter.

[ReActAgentInlineProcessingSpec](ReActAgentInlineProcessingSpec.scala) covers raw-before-processing persistence, exact duplicate ordinals, local/inline isolation, cleanup-before-publication, typed fallback, cooperative/fiber cancellation, processor defects, synthetic mixed causes, and real cooperative/external/timeout finalizer defects. Processor tests cover sequential, parallel, and finalizer defects.

[ModelInputTransformSpec](ModelInputTransformSpec.scala) covers port/history validation and selected-request contracts: lazy legacy composition and intermediate history/settings rejection. [ReActAgentModelInputSpec](ReActAgentModelInputSpec.scala) covers canonical preparation visibility, scope/event ordering, invalid projections, fallback isolation, shorter-view cancellation with reused IDs, cooperative/fiber cancellation, timeout, finalizer defects, thinking usage/retries, and native fallback contexts. Its existing invalid-output table also rejects invalid original settings through a legacy transform and custom client before events/dispatch. Held cancellation verifies joined projection cleanup. Harness eviction scenarios verify real stored payloads, request omissions across repeated requests, and view-only metadata/state rewrites.

## Completion observation verification

- [TurnCompletionObserverSpec](TurnCompletionObserverSpec.scala): port laziness/scope, ignore, codec and compile-time channel cases.
- [ReActAgentCompletionSpec](ReActAgentCompletionSpec.scala): canonical/native/Auto, publication ordering, typed/defect cause, and held-lock cases.
- [ReActAgentCompletionInterruptSpec](ReActAgentCompletionInterruptSpec.scala): cooperative/external/timeout/mixed-cause, observer-cleanup, late cancellation, and early stream termination cases. The early-consumer failure test deliberately establishes the abandoned-queue visibility limit.

## Completion observer composition

TurnCompletionObserverSpec covers composition: lazy execution with separate ordered scope cleanup, preservation of typed failure plus cleanup defects, and cancellation skipping the successor.

## Selected generation settings

The existing ModelFailoverSpec request table checks each target's stop-sequence and reasoning-effort snapshot, projection replacement/clearing and serialized request preservation. Local completion and cancellation behavior remain governed by the existing runtime contracts.

ReActAgentSpec checks invalid first-turn and cross-turn overflow observations, asserting no invalid-turn end event, hook or assistant persistence. Retention of prior accepted usage is an atomic Ref transition guarantee; this table does not directly observe that private accumulator.

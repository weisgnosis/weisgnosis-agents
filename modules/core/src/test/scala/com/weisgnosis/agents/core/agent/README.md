# ReAct and structured-output verification

The accepted [failure-fixture scope](../tool/README.md#failure-fixtures) extends existing parallel execution evidence with exact mixed Error/Success terminal events and history, without production changes or new test groups. Final source approvals and focused/full verification pass; final combined documentation acceptance is approved.

The [external execution checkpoint scope](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md#external-execution-checkpoints) is accepted within the named scope. [ReActAgentExternalToolSpec](ReActAgentExternalToolSpec.scala) supplies three proportional actual-runtime groups covering mixed execution, checked ordinal resume and lifecycle/authority boundaries; final documentation acceptance passed.

ReActAgentSpec adds one grouped custom-client turn contract regression for malformed role/classification and permitted controls. Scoped client cleanup completes before rejection; invalid output must not change history, run post-reasoning hooks or succeed. Final source/full/artifact verification passes; final combined documentation approval passed under the [trust-boundary manifest](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md#model-turn-trust-boundary).

Toolkit construction and selection follow the [checked registry identity and migration contract](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#checked-registry-identity). The linked source/spec manifest owns the current migration review scope and verification; existing lifecycle and permission contracts remain in force.

The existing mixed synthetic-completion case now verifies exact minimum-Long closing text, equality with typed/metadata values, closing history identity and unchanged one-request usage/local-call pairing. [Owned numeric-output acceptance](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/transcript/README.md#exact-owned-numeric-output) records the reproduced mismatch and current runtime/spec identities.

[Runtime ordering acceptance](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md#runtime-event-ordering) records exact current lifecycle-spec identities and bounded text/thinking/tool-cycle adaptations. Fully drained invocations begin with AgentStart and end with correlated AgentEnd; early consumers abandon later output.

The [host-resume integration](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#host-only-session-resume) exercises the shared internal resume-forwarding path with retained checkpoint reply identity, a fresh execution source, held cleanup defects and ordered terminal delivery.

The request-projection tables additionally verify topP, frequency/presence penalty and seed replacement/clearing, intermediate validation and invalid original settings before custom-client dispatch. The [settings contract](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md#optional-seed-and-exact-request-rendering) owns updated identities and full verification.

[AllToolsDenied observation](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md#all-tools-denied-observation) has two grouped additions in ReActAgentToolAuthorizerSpec: complete repeated-ID batches across restoration, Deferred/Stop and state exclusions, native/synthetic continuation, then held processor cleanup and cancelled full-cause preservation. Tool-produced Denied is a negative control normalized to Error. Both reviewers approved the exact linked pair; full all-denied-verify.log verification passed. The provider-ownership regression now replaces that incorrect dispatch expectation: same-name/ID provider calls bypass local effects, mixed cancellation reconciles only the local occurrence, repeated pauses remain bounded and a serialized provider-only checkpoint resumes. Exact current identities and RED/GREEN evidence are in [provider ownership](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md#provider-owned-pending-calls).

[ReActAgentReturnDirectSpec](ReActAgentReturnDirectSpec.scala) adds three grouped contracts: reverse-completion/repeated-ID processed presentation and rejection tables; live/restored/supplied/partial completion with exact-once processing and trusted definition matching, including supplied Error and mixed modes that preserve observations for exactly one following model request without local replay; held cleanup/cancellation and native/synthetic exclusion. Existing ToolDef/request tables cover default-mode compatibility and provider omission. Exact identities, reviews and full verification are in the [runtime contract](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md#host-selected-return-direct-completion).

The [checkpoint-event scope](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#checkpoint-permission-events) is accepted. It extends existing ordinal/restore/input tables and adds one joined phase-drain group; generic event correlation does not establish human-source or durable-delivery authority.

Partial-confirmation tests have final independent approval and full verification. The [current contract](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#partial-checkpoint-confirmations) covers checked ordinal-position ledgers, successive restored subsets, preserved inline/completed processing, saved activation and Stop state, structured rejection and joined cancellation.

The resumed-stream extension in [AgentRunUpdateSpec](AgentRunUpdateSpec.scala) is accepted: two grouped tables cover lazy validation/claim, supplied-event correlation and terminal delivery, exact mixed observer failure, and joined early/cancelled consumption. It reuses the ordinary stream path; [current identities and verification](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md#result-bearing-streams) are recorded with the runtime contract.

[PendingActingSpec](PendingActingSpec.scala) and [ReActAgentResumeSpec](ReActAgentResumeSpec.scala) are implemented and independently reviewed. Their six grouped contracts cover checkpoint codecs/occurrence validation, mixed saved batches on the original or restored runtime, host binding and activation checks, inline-only completion/reset, unsupported boundaries and iteration limits, and joined cancellation. Exact final identities and evidence are recorded in the [runtime acceptance](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md#before-acting-suspension-and-restoration); no durable partial-approval acceptance is implied.

[SuppliedToolResultSpec](SuppliedToolResultSpec.scala) and [ReActAgentSuppliedResultSpec](ReActAgentSuppliedResultSpec.scala) add one pure validation group and three runtime groups: atomic checked augmentation, live/restored partial/all supplied batches, original-ordinal Stop after full processing, and held processor cancellation preserving every supplied result. Local dispatch and authorization run only for unsupplied calls. The [seven-file acceptance](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md#supplied-results-for-suspended-batches) owns exact identities and verification; partial confirmations are covered by the accepted extension above.

[Acting-stop decisions](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md#acting-stop-decisions) have three grouped runtime cases: original-ordinal selection across mixed inline/parallel calls and later reuse; caller-owned forwarding cancellation with held processor cleanup; native/synthetic structured rejection before captured decoding. The caller-owned cancellation regression, cooperative recovery expectations and unchanged timeout/sibling-defect controls pass full verification. Exact identities and evidence are recorded in the linked contract. Cancelled public stream consumers do not promise tail delivery.

The extended post-reasoning table verifies opaque-only reminders and empty-array controls; the authorization table rejects fixed-input changes for protected sibling calls before consulting the authorizer. [Current identities and evidence](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md#encrypted-reasoning) include these cases without claiming protection from trusted host history/reasoning replacements.

- [x] Both independent reviewers approved fixed-argument preparation and runtime integration; full verification passed. The [current acceptance](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#fixed-raw-arguments) records exact scope and identities.

These ZIO Test suites cover selected upstream scenarios and Scala runtime regressions. Exhaustive upstream parity and complete runtime event ordering remain pending.

- [ReActAgentToolGroupsSpec](ReActAgentToolGroupsSpec.scala)
- [AgentRunUpdateSpec](AgentRunUpdateSpec.scala)
- [AgentEventForwarderSpec](AgentEventForwarderSpec.scala)
- [PostReasoningHookSpec](PostReasoningHookSpec.scala)
- [ReActAgentReasoningHookSpec](ReActAgentReasoningHookSpec.scala)
- [AgentConfigSpec](AgentConfigSpec.scala)
- [AgentResultSpec](AgentResultSpec.scala)
- [ReActAgentActingStopSpec](ReActAgentActingStopSpec.scala)
- [ReActAgentToolResultProcessorSpec](ReActAgentToolResultProcessorSpec.scala)
- [ReActAgentToolEventSpec](ReActAgentToolEventSpec.scala)
- [ReActAgentEmissionSpec](ReActAgentEmissionSpec.scala)
- [ReActAgentEventSpec](ReActAgentEventSpec.scala)
- [ReActAgentInterruptSpec](ReActAgentInterruptSpec.scala)
- [ReActAgentLifecycleSpec](ReActAgentLifecycleSpec.scala)
- [ReActAgentSpec](ReActAgentSpec.scala)
- [ReActAgentThinkingRetrySpec](ReActAgentThinkingRetrySpec.scala): five existing groups now directly enforce reminder-dependent retry and exact independent retry/iteration bounds under the [reviewed test scope](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md#thinking-only-retry-contract). Source/focused/full checks pass; final combined documentation approval passed.
- [StructuredCompletionSpec](StructuredCompletionSpec.scala)
- [StructuredOutputDynamicSpec](StructuredOutputDynamicSpec.scala)
- [StructuredJsonBoundarySpec](StructuredJsonBoundarySpec.scala)
- [StructuredOutputHttpSpec](StructuredOutputHttpSpec.scala)
- [StructuredOutputCapabilityRoutingSpec](StructuredOutputCapabilityRoutingSpec.scala)
- [StructuredOutputSpec](StructuredOutputSpec.scala)

## Incremental local results

Two added ReActAgentToolEventSpec groups verify actual provisional text/image delivery before completion, persisted assistant calls without provisional Tool history, reserved-metadata filtering, final model input after cleanup and occurrence-local suppression across parallel repeated IDs. The legacy sibling still publishes its aggregate. The existing cancellation group now uses a streaming unfinished sibling. Processor and inline-provider suites retain their separate contracts. Both independent reviewers approved the scope, and full tool-stream-verify.log verification passed; exact identities are under the [runtime contract](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md#incremental-local-tool-results).

## Run-owned group verification

[ReActAgentToolGroupsSpec](ReActAgentToolGroupsSpec.scala) uses ten integration groups, including three preloaded-skill contracts: sequential load union and exact content with next-request/fresh-run isolation; strict input/authorization/configuration and hidden-control collisions; native fallback and content-only Auto routing. The other groups cover next-request activation with one admitted batch snapshot and fixed policies; exact invalid/denied reset outcomes with Meta-only discovery and External isolation; fresh ordinary/streamed invocations and joined cancellation; native fallback after a successful reset with synthetic controls retained after deactivation; empty-host Auto routing and complete-base collision rejection. Reset flags exercise sequential dispatch even under Parallel configuration. Existing assertions verify descriptions match the admitted request snapshot, transition active/inactive notes across turns, and return notes from the same successful reset result while excluding External names/descriptions.

Both independent reviewers approved the final source/spec, and full verification passed. Current identities and acceptance are in the [request-only skill catalogue contract](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md#request-only-skill-catalogue). Filesystem skills, child propagation and durable state are not covered by this suite. One additional table group checks request-only catalogue insertion after host projection, complete first-system preservation, prepend/empty behavior and unchanged canonical history/preparer/transform/observer/results. Actual native fallback and repeated-request assertions verify one catalogue prompt and unchanged activation. `registry_contract` and `current_docs` approved the current runtime/spec identities; `skill-system-prompt-verify.log` passed. Existing skill scenarios also verify single binding reminders across requests/fallback and successful reset-to-empty with current inactive notes. Actual loader Plan admission has separate [harness evidence](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md#dynamic-plan-admission). MCP grouped installation has separate client-package acceptance.

The added deferred-reader runtime group verifies ordinary authorization before reader effects, unchanged activation after read/finalizer failure, success visibility only on the next request and actual cancellation joined through held failing cleanup. Current runtime/spec identities and final acceptance are in [deferred skill reads](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md#deferred-skill-reads).

## Result-bearing stream evidence

- [x] Three AgentRunUpdateSpec groups verify one actual ordinary execution with full history/aggregate usage and cleanup-before-result, a cooperatively interrupted result with one recovery, decoded structured values and exact mixed terminal causes, and held cleanup on early consumer completion or caller cancellation. No result is fabricated on failure.
- [x] `path_review` and `current_docs` approved the final source/spec identities; full verification passed. The [stream contract](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md#result-bearing-streams) owns exact hashes, commands and limitations.
- [x] [ToolInvocationSpec](../tool/ToolInvocationSpec.scala) verifies approved occurrence context and scoped event publication through actual ReAct runs; both reviewers approved the [capability contract](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#invocation-event-capability).
[ReActAgentForwardingSpec](ReActAgentForwardingSpec.scala) verifies direct result-preserving forwarding, nested attribution/order, exact compound causes and held cancellation cleanup, and completion through a closed port. Its unchanged ordering assertion exposed the competing-consumer race in `local-skill-verify.log`: ReActAgent blob `0fbbe1a282f1740efcb96b05d3d4befb39f44f4c` delivered ModelResponseEnd last instead of AgentEnd.

[AgentEventForwarderSpec](AgentEventForwarderSpec.scala) adds three groups using real Hub/Promise barriers: one consumer delivers a held claimed prefix before the queued suffix while finish waits; empty and claimed-only shutdown complete without leaking queue-shutdown interruption; delivery defects retain their exact cause and caller cancellation joins held publication. Both direct forwarding and result-bearing streams now signal completion to this sole consumer and join delivery before termination.

- [x] `path_review` and `current_docs` approved the [current forwarding identities](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md#direct-event-forwarding), with no findings. The focused helper, existing integration and stream suites passed 27 tests in 522 ms; full `event-forwarder-verify.log` verification passed with zero compiler warnings or failures. The linked acceptance records scoped totals and timing.
- [x] Separate [harness integration evidence](../../../../../../../../../harness/src/test/scala/com/weisgnosis/agents/harness/subagent/README.md) covers child forwarding and promotion wiring. Existing early-consumer abandonment of later observer errors is unchanged.

## Typed local-result evidence

[ReActAgentToolResultProcessorSpec](ReActAgentToolResultProcessorSpec.scala) verifies ordered text/media observations in history and correlated events, preserved Error state, and rejection of tool-supplied runtime control metadata. Legacy text tools retain their existing path. Media preservation here does not imply support in the text-only ChatCompletions request codec. Core tool tests separately cover unsupported states, result codecs and compound cancellation causes.

- [x] Both independent reviewers approved the final scope and full repository verification passed; current exact scope and identities belong to the [tool acceptance](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#current-review-acceptance).

## Current evidence

- [x] Lifecycle suites verify request/run ownership, timeout, cancellation, original failure propagation, private stream completion, ordered draining, and correlated boundaries.
- [x] StructuredOutputSpec verifies schema hoisting, thinking/usage preservation, mixed tools, capture isolation, and interruption after capture.
- [x] StructuredCompletionSpec verifies finalized-result ordering, exact metadata preservation, fallback boundaries, failures, and consumer cleanup.
- [x] StructuredOutputDynamicSpec verifies both runtime-schema scenarios through direct and streaming APIs.
- [x] ReActAgentEmissionSpec and ReActAgentToolEventSpec verify limits, inline metadata, occurrence matching, persistence before publication, normalized/empty observations, parallel completion, cancellation, and repeated IDs.
- [x] Six StructuredJsonBoundarySpec and five real-loopback StructuredOutputHttpSpec scenarios cover strict extraction, schema delivery, compression, usage/thinking, fallback, nonretryable errors, and invalid native JSON.
- [x] `review_fp` and `review_harness` approved the final five-file capability increment against `06bd2e1` on 2026-09-29 after the preset-format correction. No findings remain in this scope. Required formatting, fatal-warning compilation and verification passed for this reviewed scope.
- [ ] Complete remaining hook, provider continuation, event ordering and partial approval-resumption scenarios.

StructuredOutputCapabilityRoutingSpec covers all declared/unknown capability routes, explicit overrides, application tool execution, native rejection fallback, and preset response formats. The preset-format regression failed before correction. Four separate client capability tests verify codecs, defaults, invalid declarations, and HTTP-body exclusion. These are local tests, not newly mapped upstream methods. The added compound-failure regression requires exact Cause preservation and one model call, preventing inappropriate Auto synthetic fallback; both final reviews and full model-failover-verify.log verification are recorded in the [model-failover contract](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md#scoped-model-failover).

The [agent contracts](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md) and [parity mapping](../../../../../../../../../../docs/architecture/test-parity.md) describe acceptance and intentional adaptations. Earlier acceptance and exact source identities through `06bd2e1` remain in Git.

The [client capability table](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md) records unchanged capability identities; commit `02b4654` preserves all five identities from that increment.

## Post-reasoning verification

The [hook-error gate reuse contract](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md#hook-error-gate-reuse) owns the current ReActAgentReasoningHookSpec identity. Both independent reviewers approved the strengthened existing group, and hook-reuse-focused.log passes all 19 tests in 534 ms with zero failures/ignored tests. The typed failure completes cleanup before a second same-agent invocation succeeds; exact call/cleanup counts exclude an automatic retry. Full hook-reuse-verify.log passes all 1,522 tests with zero compiler warnings/failures/ignored tests and four RC1 packages; hook-reuse-preparation.log passes archive validation. Only final combined documentation approval remains pending. No production change or test group is added.

Three PostReasoningHookSpec tests verify decision/error codecs, suspended callbacks/default Keep, and compile-time exclusion of provider errors. Nineteen ReActAgentReasoningHookSpec tests verify canonical/legacy usage ownership, typed discard continuation, replacement classification and persistence, iteration/thinking bounds, interrupted-output bypass, invalid roles/errors, scoped cleanup, cancellation/timeout, cross-attempt accounting, input-usage exclusion, invocation reset, correlated final publication, and single-transfer synthetic history. The mixed legacy/canonical usage regression failed before the residual-history correction.

- [x] `review_fp` and `review_harness` approved the final six-file post-reasoning increment against `2a400e0` on 2026-09-29 after the legacy-usage correction. No findings remain in this scope. Required formatting, fatal-warning compilation and verification passed for this reviewed scope.

The upstream null-message scenario is adapted to typed Discard followed by a valid capture. It establishes safe continuation without dispatching discarded calls, not nullable-hook API equivalence. General middleware and other legacy hook operations remain outside this scope.

The [agent table](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md) links current post-reasoning identities; commit `8850a60` preserves all six identities from that increment.

## Shared-Toolkit destination isolation

ReActAgentToolEventSpec also holds two independently owned agents inside the same Tool stream from one shared Toolkit. Both enter before release; exact chunk/final sequences, distinct request/reply contexts and separate canonical histories reject cross-talk even with the same call ID. Scoped teardown releases the barrier before joining owned fibers. This is a bounded adaptation of Java's concurrent session callback contract, not concurrent-run support on one Scala agent.

- [x] Both reviewers approved the current fixture and full `shared-toolkit-verify.log` verification passed. Exact identity and focused evidence are in the [source acceptance](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md#shared-toolkit-destination-isolation).

## Completed-result processing verification

Ten ReActAgentToolResultProcessorSpec scenarios verify raw persistence before processing, committed payloads before continuation/events, typed failure fallback, cooperative/fiber cancellation, reversed parallel completion with reused IDs, inline provider processing, composition cleanup, sequential/parallel defects, and failing processor finalizers. Cancellation assertions distinguish completed raw successes from genuinely unfinished calls.

- [x] `review_fp` and `review_harness` approved the final eight-file local-result processing/eviction increment against `8850a60` on 2026-09-29. No findings remain in this scope. Required formatting, fatal-warning compilation and verification passed for this reviewed scope. Exact identities are in the [tool package table](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md).

## Scoped authorization verification

Fixed-argument integration verifies prepared authorizer input, allowed nonfixed rewrites, exact approved history/context/execution, original provenance, and original-call retention during pending/cancelled approval. A rejection table covers malformed preparation without callbacks, signed-input changes before approval, and approved fixed-field removal/replacement. Held authorizer cleanup verifies joined cancellation and preserved compound causes. Against unchanged ReActAgent blob `e8bdb5ecd30bdf1eeefc177e898bb7ef8f6d5b69`, `tool-authorization-cause-red.log` recorded one failed assertion: an authorizer joining its interrupted child lost the held cleanup defect and returned only interruption. The unchanged regression now verifies that exact defect remains; the handler refails the full cause with orDie. This is an actual inner-child interruption, not an externally cancelled parent. Exact identities and current acceptance are in the [fixed-argument contract](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#fixed-raw-arguments).


[ReActAgentToolAuthorizerSpec](ReActAgentToolAuthorizerSpec.scala) covers scenarios for persistence before approval, precise argument rewriting, denial events, typed/defect/finalizer failure normalization, ambiguous JSON, signed and opaque reasoning metadata, unchanged signed calls, cooperative/fiber cancellation, duplicate calls, inline pairing, and unmatched provider-marked local dispatch. Two unsafe reasoning-metadata rewrites were reproduced by a failing regression before the presence-based guard was corrected. This covers the core port, not interactive or durable policy adapters.

- [x] `path_review` and `current_docs` approved fixed-argument authorization and compound-cause preservation with no findings; full verification passed. The [scoped fixed-argument acceptance](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#fixed-raw-arguments) records exact identities and evidence. Durable policy adapters remain separate.

## History preparation verification

[HistoryPreparerSpec](HistoryPreparerSpec.scala) covers eleven port/validation/composition scenarios. [ReActAgentHistoryPreparerSpec](ReActAgentHistoryPreparerSpec.scala) adds eight lifecycle scenarios for cleanup/commit order, repeated cancellation, supplied-history isolation, invalid rewrite rejection, fallback provenance, interrupted partial output after shrinkage, thinking/discard budgets, and Auto attempt boundaries. Shared occurrence analysis has three model tests, and the actual compactor adapter has four harness tests.

- [x] `review_fp` and `review_harness` approved the final nine-file history-preparation increment against `3c6958f` on 2026-09-29. No findings remain in this scope. Required formatting, fatal-warning compilation and verification passed for this reviewed scope. Acceptance covers structural rewrite validation and scoped compactor integration; semantic summary fidelity and exhaustive provider-format validation are excluded. See the [agent table](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md) for exact identities.

- [x] `review_fp` and `review_harness` approved the final inline-processing and cancellation-cleanup scope against `2b99226` on 2026-09-29. Required checks and regressions passed for this reviewed scope. No findings remain in this scope. Supplied-history eviction is covered by a later scope; exhaustive upstream middleware parity remains pending. Exact current identities are in the [agent table](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md).

[ReActAgentInlineProcessingSpec](ReActAgentInlineProcessingSpec.scala) adds ten regressions for raw-before-processing persistence, exact duplicate ordinals, local/inline isolation, cleanup-before-publication, typed fallback, cooperative/fiber cancellation, processor defects, synthetic mixed causes, and real cooperative/external/timeout finalizer defects. Two actual cancellation regressions failed before explicit worker ownership replaced cause-discarding races. The three additional processor tests cover sequential, parallel, and finalizer defects.

- [x] `review_fp` and `review_harness` approved the final nine-file history-eviction scope against `cc8fa7b` on 2026-09-29. Required checks and regressions passed for this reviewed scope. Two new regressions exposed and closed strict-number and Windows storage-layout gaps. No findings remain within store-backed integrity, explicit workspace trust, and validated composition; absolute storage is covered by a later scope; request-only input views are covered by a later scope. See the [eviction contract/table](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/eviction/README.md).

- [x] `review_fp` and `review_harness` approved the final seven-file model-input scope against `40f6f05` on 2026-09-29. Required checks and regressions passed for this reviewed scope. No findings remain within request-only projection, structural validation, canonical-state isolation, and eviction integration. Broader legacy hook/middleware parity remains pending. Exact identities and migration are in the [agent contract](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md).

[ModelInputTransformSpec](ModelInputTransformSpec.scala) adds two grouped selected-request contracts to its existing port/history validation: lazy legacy composition and intermediate history/settings rejection. [ReActAgentModelInputSpec](ReActAgentModelInputSpec.scala) adds ten scenarios for canonical preparation visibility, scope/event ordering, invalid projections, fallback isolation, shorter-view cancellation with reused IDs, cooperative/fiber cancellation, timeout, finalizer defects, thinking usage/retries, and native fallback contexts. Its existing invalid-output table also rejects invalid original settings through a legacy transform and custom client before events/dispatch. Held cancellation verifies joined projection cleanup. Three harness eviction scenarios verify real stored payloads, request omissions across repeated requests, and view-only metadata/state rewrites. Current five-file request-projection acceptance is in the [agent contract](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md#current-model-input-identities).

## Completion observation verification

- [TurnCompletionObserverSpec](TurnCompletionObserverSpec.scala): three port laziness/scope, ignore, codec and compile-time channel cases.
- [ReActAgentCompletionSpec](ReActAgentCompletionSpec.scala): nine canonical/native/Auto, publication ordering, typed/defect cause, and held-lock cases.
- [ReActAgentCompletionInterruptSpec](ReActAgentCompletionInterruptSpec.scala): ten cooperative/external/timeout/mixed-cause, observer-cleanup, late cancellation, and early stream termination cases. The early-consumer failure test deliberately establishes the abandoned-queue visibility limit.

- [x] `review_fp` and `review_harness` approved the final eight-file completion-observer increment against `0349b1a` on 2026-09-29. Required `sbt scalafmtAll scalafmtSbt verify` passed with zero compiler warnings for this reviewed scope. No findings remain within scoped once-per-invocation observation, canonical snapshots, cause preservation, explicit transcript identity policy, and local persistence integration. Background memory scheduling, remote persistence, delivery acknowledgement, and exhaustive upstream middleware parity remain outside this scope.

Exact identities and callback restrictions are in the [source contract](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md).

## Completion observer composition

TurnCompletionObserverSpec now has six tests, including three added composition cases: lazy execution with separate ordered scope cleanup, preservation of typed failure plus cleanup defects, and cancellation skipping the successor. Current source identities and acceptance are in the [core agent contract](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md).

- [x] `review_fp` and `review_harness` approved the final seven-file consolidation/composition increment against `3e817e3` on 2026-09-29 after correcting nested test-scope ownership. Required `sbt scalafmtAll scalafmtSbt verify` passed with zero compiler warnings for this reviewed scope. No findings remain within separately scoped observer composition, owned consolidation/result reporting, eight local throttle integration combinations, FIFO replacement and post-commit cancellation/failure. Retention, remote authority, strict cross-job dependencies and transactional reporting are excluded.

## Selected generation settings

The existing ModelFailoverSpec request table checks each target's stop-sequence and reasoning-effort snapshot, projection replacement/clearing and serialized request preservation. The paired checked-value suite and exact reviewed identities belong to the [client contract](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md#optional-reasoning-effort). Local completion and cancellation behavior remain governed by the existing runtime contracts.

The checked-usage regression extends ReActAgentSpec with invalid first-turn and cross-turn overflow observations, directly asserting no invalid-turn end event, hook or assistant persistence. Retention of prior accepted usage is the inspected atomic Ref transition guarantee; this table does not directly observe that private accumulator. Existing usage assertions across runtime suites handle the typed arithmetic API; the [model contract](../../../../../../../main/scala/com/weisgnosis/agents/core/model/README.md#checked-usage-arithmetic) owns completed source/full/artifact evidence and final combined documentation approval.

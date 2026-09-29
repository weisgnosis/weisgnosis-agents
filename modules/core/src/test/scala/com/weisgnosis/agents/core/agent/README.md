# ReAct and structured-output verification

These ZIO Test suites cover selected upstream scenarios and Scala runtime regressions. Exhaustive upstream parity and complete runtime event ordering remain pending.

- [PostReasoningHookSpec](PostReasoningHookSpec.scala)
- [ReActAgentReasoningHookSpec](ReActAgentReasoningHookSpec.scala)
- [AgentConfigSpec](AgentConfigSpec.scala)
- [AgentResultSpec](AgentResultSpec.scala)
- [ReActAgentToolResultProcessorSpec](ReActAgentToolResultProcessorSpec.scala)
- [ReActAgentToolEventSpec](ReActAgentToolEventSpec.scala)
- [ReActAgentEmissionSpec](ReActAgentEmissionSpec.scala)
- [ReActAgentEventSpec](ReActAgentEventSpec.scala)
- [ReActAgentInterruptSpec](ReActAgentInterruptSpec.scala)
- [ReActAgentLifecycleSpec](ReActAgentLifecycleSpec.scala)
- [ReActAgentSpec](ReActAgentSpec.scala)
- [ReActAgentThinkingRetrySpec](ReActAgentThinkingRetrySpec.scala)
- [StructuredCompletionSpec](StructuredCompletionSpec.scala)
- [StructuredOutputDynamicSpec](StructuredOutputDynamicSpec.scala)
- [StructuredJsonBoundarySpec](StructuredJsonBoundarySpec.scala)
- [StructuredOutputHttpSpec](StructuredOutputHttpSpec.scala)
- [StructuredOutputCapabilityRoutingSpec](StructuredOutputCapabilityRoutingSpec.scala)
- [StructuredOutputSpec](StructuredOutputSpec.scala)

## Current evidence

- [x] Lifecycle suites verify request/run ownership, timeout, cancellation, original failure propagation, private stream completion, ordered draining, and correlated boundaries.
- [x] StructuredOutputSpec verifies schema hoisting, thinking/usage preservation, mixed tools, capture isolation, and interruption after capture.
- [x] StructuredCompletionSpec verifies finalized-result ordering, exact metadata preservation, fallback boundaries, failures, and consumer cleanup.
- [x] StructuredOutputDynamicSpec verifies both runtime-schema scenarios through direct and streaming APIs.
- [x] ReActAgentEmissionSpec and ReActAgentToolEventSpec verify limits, inline metadata, occurrence matching, persistence before publication, normalized/empty observations, parallel completion, cancellation, and repeated IDs.
- [x] Six StructuredJsonBoundarySpec and five real-loopback StructuredOutputHttpSpec scenarios cover strict extraction, schema delivery, compression, usage/thinking, fallback, nonretryable errors, and invalid native JSON.
- [x] `review_fp` and `review_harness` approved the final five-file capability increment against `06bd2e1` on 2026-09-29 after the preset-format correction. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 420 core and 40 harness tests.
- [ ] Complete remaining hook, provider continuation, event ordering, and permission/phase-stop scenarios.

Twelve StructuredOutputCapabilityRoutingSpec tests cover all declared/unknown capability routes, explicit overrides, application tool execution, native rejection fallback, and preset response formats. The preset-format regression failed before correction. Four separate client capability tests verify codecs, defaults, invalid declarations, and HTTP-body exclusion. These are local tests, not sixteen newly mapped upstream methods.

The [agent contracts](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md) and [parity mapping](../../../../../../../../../../docs/architecture/test-parity.md) describe acceptance and intentional adaptations. Earlier acceptance and exact source identities through `06bd2e1` remain in Git.

The [client capability table](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md) records unchanged capability identities; commit `02b4654` preserves all five identities from that increment.

## Post-reasoning verification

Three PostReasoningHookSpec tests verify decision/error codecs, suspended callbacks/default Keep, and compile-time exclusion of provider errors. Nineteen ReActAgentReasoningHookSpec tests verify canonical/legacy usage ownership, typed discard continuation, replacement classification and persistence, iteration/thinking bounds, interrupted-output bypass, invalid roles/errors, scoped cleanup, cancellation/timeout, cross-attempt accounting, input-usage exclusion, invocation reset, correlated final publication, and single-transfer synthetic history. The mixed legacy/canonical usage regression failed before the residual-history correction.

- [x] `review_fp` and `review_harness` approved the final six-file post-reasoning increment against `2a400e0` on 2026-09-29 after the legacy-usage correction. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 456 core and 40 harness tests.

The upstream null-message scenario is adapted to typed Discard followed by a valid capture. It establishes safe continuation without dispatching discarded calls, not nullable-hook API equivalence. General middleware and other legacy hook operations remain outside this scope.

The [agent table](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md) records unchanged post-reasoning identities; commit `8850a60` preserves all six identities from that increment.

## Completed-result processing verification

Ten ReActAgentToolResultProcessorSpec scenarios verify raw persistence before processing, committed payloads before continuation/events, typed failure fallback, cooperative/fiber cancellation, reversed parallel completion with reused IDs, inline provider processing, composition cleanup, sequential/parallel defects, and failing processor finalizers. Cancellation assertions distinguish completed raw successes from genuinely unfinished calls.

- [x] `review_fp` and `review_harness` approved the final eight-file local-result processing/eviction increment against `8850a60` on 2026-09-29. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 466 core and 45 harness tests. Exact identities are in the [tool package table](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md).

## Scoped authorization verification

[ReActAgentToolAuthorizerSpec](ReActAgentToolAuthorizerSpec.scala) adds twelve scenarios for persistence before approval, precise argument rewriting, denial events, typed/defect/finalizer failure normalization, ambiguous JSON, signed and opaque reasoning metadata, unchanged signed calls, cooperative/fiber cancellation, duplicate calls, inline pairing, and unmatched provider-marked local dispatch. Two unsafe reasoning-metadata rewrites were reproduced by a failing regression before the presence-based guard was corrected. This covers the core port, not interactive or durable policy adapters.

- [x] `review_fp` and `review_harness` approved the final five-file core authorization increment against `a34201f` on 2026-09-29. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 481 core and 45 harness tests, including fifteen added authorization tests. Interactive and durable policy adapters are excluded from this acceptance. See the [authorization table](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md) for exact identities.

## History preparation verification

[HistoryPreparerSpec](HistoryPreparerSpec.scala) covers eleven port/validation/composition scenarios. [ReActAgentHistoryPreparerSpec](ReActAgentHistoryPreparerSpec.scala) adds eight lifecycle scenarios for cleanup/commit order, repeated cancellation, supplied-history isolation, invalid rewrite rejection, fallback provenance, interrupted partial output after shrinkage, thinking/discard budgets, and Auto attempt boundaries. Shared occurrence analysis has three model tests, and the actual compactor adapter has four harness tests.

- [x] `review_fp` and `review_harness` approved the final nine-file history-preparation increment against `3c6958f` on 2026-09-29. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 497 core and 73 harness tests, including twenty added tests. Acceptance covers structural rewrite validation and scoped compactor integration; semantic summary fidelity and exhaustive provider-format validation are excluded. See the [agent table](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md) for exact identities.

- [x] `review_fp` and `review_harness` approved the final inline-processing and cancellation-cleanup scope against `2b99226` on 2026-09-29. Fourteen added regressions and the full formatting/fatal-warning verification passed: 510 core and 74 harness tests. No findings remain in this scope. Supplied-history eviction is covered by a later scope; exhaustive upstream middleware parity remains pending. Exact current identities are in the [agent table](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md).

[ReActAgentInlineProcessingSpec](ReActAgentInlineProcessingSpec.scala) adds ten regressions for raw-before-processing persistence, exact duplicate ordinals, local/inline isolation, cleanup-before-publication, typed fallback, cooperative/fiber cancellation, processor defects, synthetic mixed causes, and real cooperative/external/timeout finalizer defects. Two actual cancellation regressions failed before explicit worker ownership replaced cause-discarding races. The three additional processor tests cover sequential, parallel, and finalizer defects.

- [x] `review_fp` and `review_harness` approved the final nine-file history-eviction scope against `cc8fa7b` on 2026-09-29. Twenty-two added regressions and full formatting/fatal-warning verification passed: 516 core and 90 harness tests. Two new regressions exposed and closed strict-number and Windows storage-layout gaps. No findings remain within store-backed integrity, explicit workspace trust, and validated composition; absolute storage is covered by a later scope; request-only input views are covered by a later scope. See the [eviction contract/table](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/eviction/README.md).

- [x] `review_fp` and `review_harness` approved the final seven-file model-input scope against `40f6f05` on 2026-09-29. Sixteen added regressions and full formatting/fatal-warning verification passed: 529 core and 101 harness tests. No findings remain within request-only projection, structural validation, canonical-state isolation, and eviction integration. Broader legacy hook/middleware parity remains pending. Exact identities and migration are in the [agent contract](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md).

[ModelInputTransformSpec](ModelInputTransformSpec.scala) has three port/validation/error-channel tests. [ReActAgentModelInputSpec](ReActAgentModelInputSpec.scala) adds ten scenarios for canonical preparation visibility, scope/event ordering, invalid projections, fallback isolation, shorter-view cancellation with reused IDs, cooperative/fiber cancellation, timeout, finalizer defects, thinking usage/retries, and native fallback contexts. Three harness eviction scenarios verify real stored payloads, request omissions across repeated requests, and view-only metadata/state rewrites.

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

Seven ReActAgentToolResultProcessorSpec scenarios verify raw persistence before processing, committed payloads before continuation/events, typed failure fallback, cooperative/fiber cancellation, reversed parallel completion with reused IDs, inline provider exclusion, and composition cleanup. Cancellation assertions distinguish completed raw successes from genuinely unfinished calls.

- [x] `review_fp` and `review_harness` approved the final eight-file local-result processing/eviction increment against `8850a60` on 2026-09-29. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 466 core and 45 harness tests. Exact identities are in the [tool package table](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md).

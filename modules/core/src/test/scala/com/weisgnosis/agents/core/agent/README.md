# ReAct and structured-output verification

These ZIO Test suites cover selected upstream scenarios and Scala runtime regressions. Exhaustive upstream parity and complete runtime event ordering remain pending.

- [AgentConfigSpec](AgentConfigSpec.scala)
- [AgentResultSpec](AgentResultSpec.scala)
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

The [client capability table](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md) records all five exact reviewed identities.

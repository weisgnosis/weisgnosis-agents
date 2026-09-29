# ReAct and structured-output verification

These ZIO Test suites cover selected upstream scenarios and Scala runtime regressions. Exhaustive upstream parity, structured streaming, and complete runtime event ordering remain pending.

- [AgentConfigSpec](AgentConfigSpec.scala)
- [AgentResultSpec](AgentResultSpec.scala)
- [ReActAgentEmissionSpec](ReActAgentEmissionSpec.scala)
- [ReActAgentEventSpec](ReActAgentEventSpec.scala)
- [ReActAgentInterruptSpec](ReActAgentInterruptSpec.scala)
- [ReActAgentLifecycleSpec](ReActAgentLifecycleSpec.scala)
- [ReActAgentSpec](ReActAgentSpec.scala)
- [ReActAgentThinkingRetrySpec](ReActAgentThinkingRetrySpec.scala)
- [StructuredOutputSpec](StructuredOutputSpec.scala)

## Current evidence

- [x] Lifecycle suites verify request/run resource ownership, timeout, cancellation, original failure propagation, private stream completion, ordered draining, and fresh correlated boundaries.
- [x] StructuredOutputSpec verifies schema hoisting, thinking/usage/history preservation, mixed tools, capture isolation, and interruption after capture.
- [x] ReActAgentEmissionSpec verifies nine limit/inline-result/duplicate-call regressions without sleeps; four initial publication tests reproduced missing behavior before correction.
- [x] `review_fp` and `review_harness` approved the final two-file runtime increment against `9768064` on 2026-09-29 with no unresolved findings in that scope.
- [x] `sbt scalafmtAll scalafmtSbt verify` passed 300 core and 40 harness tests with zero compiler warnings.
- [ ] Complete remaining upstream ordering, structured streaming, provider continuation, local structured tool streams, and permission/phase-stop integration.

The [current agent contracts and reviewed blobs](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md) record exact scope, earlier accepted revisions, intentional upstream differences, and integration limits. The [parity mapping](../../../../../../../../../../docs/architecture/test-parity.md) retains incomplete tasks as unchecked items.

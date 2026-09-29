# ReAct and structured-output verification

These ZIO Test suites cover selected upstream scenarios and Scala runtime regressions. Exhaustive upstream parity and complete runtime event ordering remain pending.

- [AgentConfigSpec](AgentConfigSpec.scala)
- [AgentResultSpec](AgentResultSpec.scala)
- [ReActAgentEmissionSpec](ReActAgentEmissionSpec.scala)
- [ReActAgentEventSpec](ReActAgentEventSpec.scala)
- [ReActAgentInterruptSpec](ReActAgentInterruptSpec.scala)
- [ReActAgentLifecycleSpec](ReActAgentLifecycleSpec.scala)
- [ReActAgentSpec](ReActAgentSpec.scala)
- [ReActAgentThinkingRetrySpec](ReActAgentThinkingRetrySpec.scala)
- [StructuredCompletionSpec](StructuredCompletionSpec.scala)
- [StructuredOutputDynamicSpec](StructuredOutputDynamicSpec.scala)
- [StructuredOutputSpec](StructuredOutputSpec.scala)

## Current evidence

- [x] Lifecycle suites verify request/run resource ownership, timeout, cancellation, original failure propagation, private stream completion, ordered draining, and fresh correlated boundaries.
- [x] StructuredOutputSpec verifies schema hoisting, thinking/usage/history preservation, mixed tools, capture isolation, and interruption after capture.
- [x] ReActAgentEmissionSpec verifies nine limit/inline-result/duplicate-call regressions without sleeps; four initial publication tests reproduced missing behavior before correction.
- [x] `review_fp` and `review_harness` approved the final four-file structured completion increment against `5451e03` on 2026-09-29 with no unresolved findings in that scope.
- [x] StructuredCompletionSpec verifies nine final-result/structured-stream scenarios, including exact metadata AST preservation, fallback boundaries, pre-commit failure, cooperative recovery, and consumer cleanup.
- [x] `sbt scalafmtAll scalafmtSbt verify` passed 311 core and 40 harness tests with zero compiler warnings.
- [ ] Complete remaining upstream ordering, capability/hook scenarios, provider continuation, local structured tool streams, and permission/phase-stop integration.

The [current agent contracts and reviewed blobs](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md) record exact scope, earlier accepted revisions, intentional upstream differences, and integration limits. The [parity mapping](../../../../../../../../../../docs/architecture/test-parity.md) retains incomplete tasks as unchecked items.

StructuredOutputDynamicSpec covers the two runtime-schema weather scenarios through Json values and verifies schema delivery, metadata/history preservation, and one-request completion. The upstream names mention nested structures, but these two fixtures are flat; nested definition resolution remains covered separately.

- [x] `review_fp` and `review_harness` approved the two-scenario [dynamic JSON spec](StructuredOutputDynamicSpec.scala) against `3963104` on 2026-09-29, with no findings. Exact reviewed Git blob: `9f4a3360e8064a9e5ae72d5bb88588b1997ef2e9`. Verification passed 311 core and 40 harness tests; general JSON Schema validation and legacy event envelopes are outside this scope.

- [x] `review_fp` and `review_harness` approved the ReActAgentThinkingRetrySpec migration to typed accumulator results and explicit finish markers against `10019c2` on 2026-09-29. Both original reminder/acting assertions remain verified. Current full verification passed 345 core and 40 harness tests; exact migrated file identity is recorded in the [client review scope](../client/README.md).

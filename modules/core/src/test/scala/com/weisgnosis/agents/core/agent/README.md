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

- [x] `review_fp` and `review_harness` approved the ReActAgentThinkingRetrySpec migration to typed accumulator results and explicit finish markers against `10019c2` on 2026-09-29. Both original reminder/acting assertions remain verified. Verification at that revision passed 345 core and 40 harness tests; exact migrated file identity is recorded in the [client review scope](../client/README.md).

## Model request correlation

ReAct allocates a fresh requestId under the current attempt replyId and passes ModelStreamContext explicitly to LlmClient.streamTurn. ModelRequestStart precedes invocation; ModelResponseEnd follows a non-interrupted result and request-scope cleanup. HTTP block deltas may be observed before completion, but incomplete/malformed responses never reach local dispatch. The [client contracts](../client/README.md) describe live ordering, source migration, and remaining parity limits.

- [x] `review_fp` and `review_harness` approved the final live client/event increment against `60b3799` on 2026-09-29. No findings remain in runtime correlation, validated incremental publication, scoped cleanup, or the migrated/new tests. Full verification passed 386 core and 40 harness tests. Full upstream ordering parity and local tool-result event integration are excluded.

The [client acceptance table](../client/README.md) records the exact final Scala file identities, including this package.

## Completed tool observations

All four result-event variants now retain the originating ModelStreamContext. Local execution publishes Start, executes interruptibly, then records the completed observation before publishing its text Delta and End under the existing interruption mask. Empty text remains an explicit empty delta. Normalized failures remain Error observations and continue the loop. Each parallel call keeps its own ordered events; completion order may differ from call order. Later turns reusing a call ID remain distinguishable through requestId.

Inline provider results share the completed-output publisher, preserve text/data ordering and metadata, and remain inside the assistant message without separate Tool messages. Local tools currently produce completed text observations, not partial streaming output or structured result metadata. Cancellation preserves completed results/events and reconciles unfinished history; it does not publish a false ToolResultEnd for interrupted work. AgentEnd still terminates the attempt.

- [x] Six ReActAgentToolEventSpec regressions cover persistence before delivery, normalized failures, empty text, controlled parallel completion, cancellation, and repeated-ID provenance. Inline result fixtures and the sixteen-variant context matrix pass.
- [x] `review_fp` and `review_harness` approved the final five-file tool-result increment against `84e4eb3` on 2026-09-29, including the corrected server/local delta expectation. No findings remain in this scope. Full verification passed 392 core and 40 harness tests.
- [ ] Implement incremental tool output and richer local structured-result metadata.

The identities below supersede earlier acceptance records for these files:

| File | Reviewed Git blob |
| --- | --- |
| [ReActAgent.scala](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/ReActAgent.scala) | 706361eadca57ef7a730bda6572bf8fc98a815f5 |
| [AgentEvent.scala](../../../../../../../main/scala/com/weisgnosis/agents/core/event/AgentEvent.scala) | 6f8726e99cf4a5dd32b0a8d498b176b5f91a8b51 |
| [ReActAgentEmissionSpec.scala](ReActAgentEmissionSpec.scala) | f921e3ec814d758f58c4e92aacb0c85b0e3446f5 |
| [ReActAgentToolEventSpec.scala](ReActAgentToolEventSpec.scala) | 84a2327121f2b5a85985fe8e74fb2085dfb50852 |
| [ModelStreamContextSpec.scala](../event/ModelStreamContextSpec.scala) | 6bf28be306402cdc081dfd4035a2563333a06600 |

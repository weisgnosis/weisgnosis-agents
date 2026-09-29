# Typed error verification

[AgentErrorSpec](AgentErrorSpec.scala) verifies TextTransformationError codec identity and diagnostics. The client transformation suite checks that provider ModelError cannot inhabit the transform callback error channel, and that explicitly mapped host failures do not trigger native-format fallback.

- [x] `review_fp` and `review_harness` approved the final seven-file text-transformation increment against `02b4654` on 2026-09-29 after the fallback-provenance correction. No findings remain within this scope. Full formatting, fatal-warning compilation, and verification passed 434 core and 40 harness tests.
- [ ] Complete exhaustive error-hierarchy and upstream scenario mapping; this suite does not claim it.

The [client transformation table](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md) records unchanged text-transformation identities; commit `2a400e0` preserves all seven identities from that increment.

[PostReasoningHookSpec](../agent/PostReasoningHookSpec.scala) separately verifies ReasoningHookError codec/provenance. The current [agent acceptance](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md) records that increment.

[HistoryPreparerSpec](../agent/HistoryPreparerSpec.scala) verifies the HistoryPreparationError codec and narrow callback channel. Runtime and harness tests additionally distinguish summary failures from native provider rejection; acceptance is recorded in the [agent table](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md).

# Model client and stream verification

These ZIO Test suites cover selected upstream scenarios and Scala runtime regressions. They do not certify exhaustive upstream parity or live-provider behavior.

- [LlmClientSpec](LlmClientSpec.scala)
- [ReasoningAdapterSpec](ReasoningAdapterSpec.scala)
- [ReasoningAdapterValidationSpec](ReasoningAdapterValidationSpec.scala)
- [SseParserSpec](SseParserSpec.scala)
- [StreamAccumulatorSpec](StreamAccumulatorSpec.scala)

## Current evidence

- [x] Existing parsing tests preserve reasoning, tool fragments, and cached/reasoning usage through the typed Either API.
- [x] Fifteen strict-boundary regressions cover malformed envelopes/fields, required arrays, missing/invalid/duplicate indexes, fractional/overflow numbers, multi-choice rejection, valid empty/usage chunks, provider errors, unsupported deltas, duplicate extension fields, and reasoning precedence.
- [x] Full `sbt scalafmtAll scalafmtSbt verify` passed 326 core and 40 harness tests with zero compiler warnings.
- [ ] Complete live transport/resource integration, model-stream correlation, and provider-specific scenario mapping.

The [client contracts](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md) record source migration, exact review scope, and remaining limits. The [parity audit](../../../../../../../../../../docs/architecture/test-parity.md) separates runtime hardening from mapped upstream methods. Earlier message-boundary migration is retained in commit `a701612`.

- [x] `review_fp` and `review_harness` approved the final three-file strict-delta increment against `95a838f` on 2026-09-29 after corrections, with no unresolved findings. Scope is single-frame parsing; HTTP integration, cross-frame completion, and input-size bounds remain separate.

| File | Reviewed Git blob |
| --- | --- |
| [ReasoningModelAdapter.scala](../../../../../../../main/scala/com/weisgnosis/agents/core/client/ReasoningModelAdapter.scala) | fc605dd9c205326798ba5b363f4770b8d82041fa |
| [ReasoningAdapterSpec.scala](ReasoningAdapterSpec.scala) | fb4a5d5f69c8b09049b2cb5d63420ccc5ab41ae7 |
| [ReasoningAdapterValidationSpec.scala](ReasoningAdapterValidationSpec.scala) | d034821b0c165da585b87fafbc02fdafd5c41192 |

# Model client port and stream assembly

LlmClient.streamTurn requires Scope and returns TurnOutput. Implementations must acquire request resources in that scope. The package provides scripted clients, LlmConfig, SSE framing, strict completion-delta decoding, reasoning-history adaptation, and StreamAccumulator. A live HTTP client remains pending.

- [LlmChunk](LlmChunk.scala)
- [LlmClient](LlmClient.scala)
- [ReasoningModelAdapter](ReasoningModelAdapter.scala)
- [SseParser](SseParser.scala)
- [StreamAccumulator](StreamAccumulator.scala)
- [TurnOutput](TurnOutput.scala)

## Completion data boundary

ReasoningModelAdapter.parseChunk now returns Either[AgentError, Chunk[LlmChunk]]. This is a source-breaking replacement for the former silent empty-Chunk fallback. Lift the result with ZIO.fromEither; malformed recognized fields must fail the request rather than disappear. SSE framing and [DONE] handling remain outside this JSON decoder.

Normal frames require an explicit choices array. Exactly one choice with an explicit integer index of zero is supported; alternative choices are rejected instead of combined. Empty choices are permitted with usage. Role-only and empty terminal deltas are valid. Tool fragments require nonnegative integer stream indexes, unique within each frame; optional IDs/names support later fragments without inventing index zero. Duplicate JSON fields are rejected throughout the input tree, including ignored extension objects.

Missing/JSON-null usage counts normalize to zero. Supplied counts and indexes must be nonnegative JSON integers within Int bounds; strings, fractions, overflow, and malformed nested objects fail. Non-null provider error envelopes become ModelError with status 200, representing an error carried inside an otherwise successful streaming response. Missing/empty provider messages receive a deterministic fallback. Actual HTTP error status handling is still transport work.

Indexed function tools, text, and reasoning_content/thinking strings are supported. Non-assistant roles, nonempty refusal deltas, unsupported tool kinds, and non-null legacy function_call payloads fail explicitly. Canonical reasoning_content takes precedence over thinking. Unknown extension fields remain ignored unless they contain duplicate keys; this does not establish support for audio, richer reasoning details/signatures, or other provider payloads. Their integration remains pending.

The wire baseline uses [streamed function-call deltas](https://developers.openai.com/api/docs/guides/function-calling). Single-choice limits and strict validation are runtime policy. StreamAccumulator still groups parallel tools by streamIndex and sanitizes incomplete JSON; complete model block correlation, signed reasoning assembly, and strict completed-argument validation require further work before live transport acceptance.

## Current acceptance

- [x] Message-boundary migration accepted at `a701612`; [validated message contracts](../model/README.md) retain its scope.
- [x] Fifteen new ReasoningAdapterValidationSpec regressions and three migrated parsing scenarios pass; context hygiene behavior remains unchanged.
- [x] Full formatting, fatal-warning compilation, and verification passed 326 core and 40 harness tests.
- [ ] Implement request serialization, live scoped HTTP transport, response termination checks, and deterministic integration tests.
- [ ] Complete model-stream correlation, signed reasoning assembly, provider extensions, and exhaustive upstream parser/transport mapping.

See the [parity audit](../../../../../../../../../../docs/architecture/test-parity.md) and [tracker](../../../../../../../../../../docs/architecture/tracker.md). Acceptance is limited to named files and reviewed contracts.

- [x] `review_fp` and `review_harness` approved the final three-file strict-delta increment against `95a838f` on 2026-09-29 after corrections, with no unresolved findings. Scope is single-frame parsing; HTTP integration, cross-frame completion, and input-size bounds remain separate.

| File | Reviewed Git blob |
| --- | --- |
| [ReasoningModelAdapter.scala](ReasoningModelAdapter.scala) | fc605dd9c205326798ba5b363f4770b8d82041fa |
| [ReasoningAdapterSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ReasoningAdapterSpec.scala) | fb4a5d5f69c8b09049b2cb5d63420ccc5ab41ae7 |
| [ReasoningAdapterValidationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ReasoningAdapterValidationSpec.scala) | d034821b0c165da585b87fafbc02fdafd5c41192 |

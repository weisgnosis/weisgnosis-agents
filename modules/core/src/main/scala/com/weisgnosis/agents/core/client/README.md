# Model client port and stream assembly

LlmClient.streamTurn requires Scope and returns TurnOutput. Implementations must acquire request resources in that scope. The package provides scripted clients, LlmConfig, SSE framing, strict completion-delta decoding, reasoning-history adaptation, and StreamAccumulator. A live HTTP client remains pending.

- [ChatCompletionsRequest](ChatCompletionsRequest.scala)
- [LlmChunk](LlmChunk.scala)
- [LlmClient](LlmClient.scala)
- [ProtocolJson](ProtocolJson.scala)
- [ReasoningModelAdapter](ReasoningModelAdapter.scala)
- [SseParser](SseParser.scala)
- [StreamAccumulator](StreamAccumulator.scala)
- [TurnOutput](TurnOutput.scala)

## Completion data boundary

ReasoningModelAdapter.parseChunk now returns Either[AgentError, Chunk[LlmChunk]]. This is a source-breaking replacement for the former silent empty-Chunk fallback. Lift the result with ZIO.fromEither; malformed recognized fields must fail the request rather than disappear. SSE framing and [DONE] handling remain outside this JSON decoder.

Normal frames require an explicit choices array. Exactly one choice with an explicit integer index of zero is supported; alternative choices are rejected instead of combined. Empty choices are permitted with usage. Role-only and empty terminal deltas are valid. Tool fragments require nonnegative integer stream indexes, unique within each frame; optional IDs/names support later fragments without inventing index zero. Duplicate JSON fields are rejected throughout the input tree, including ignored extension objects.

Missing/JSON-null usage counts normalize to zero. Supplied counts and indexes must be nonnegative JSON integers within Int bounds; strings, fractions, overflow, and malformed nested objects fail. Non-null provider error envelopes become ModelError with status 200, representing an error carried inside an otherwise successful streaming response. Missing/empty provider messages receive a deterministic fallback. Actual HTTP error status handling is still transport work.

Indexed function tools, text, and reasoning_content/thinking strings are supported. Non-assistant roles, nonempty refusal deltas, unsupported tool kinds, and non-null legacy function_call payloads fail explicitly. Canonical reasoning_content takes precedence over thinking. Unknown extension fields remain ignored unless they contain duplicate keys; this does not establish support for audio, richer reasoning details/signatures, or other provider payloads. Their integration remains pending.

The wire baseline uses [streamed function-call deltas](https://developers.openai.com/api/docs/guides/function-calling). Single-choice limits and strict validation are runtime policy. The accumulator enforces the completion contracts below. End-to-end block publication, provider signature decoding, and HTTP integration remain separate work.

## Indexed assembly and completion

StreamAccumulator is now privately constructed through empty. feed returns Either[AgentError, StreamAccumulator], and toTurnOutput returns Either[AgentError, TurnOutput]; replace former fluent calls with flatMap. The former public case-class construction/copy and intermediate fields are removed. Normal completion requires stop with no tools or tool_calls with tools. Missing, repeated, truncated, filtered, or inconsistent completion markers cannot yield executable success. Usage snapshots may follow a finish marker; other later deltas fail.

Tool fragments remain keyed and emitted in numeric streamIndex order. IDs/names may arrive after argument fragments but must be nonblank, stable, present at completion, and IDs must be unique across completed calls. Arguments must be a complete JSON object with unique keys. Valid assembled raw text is retained in ToolUse.content exactly. Normal malformed/non-object/trailing input fails; toInterrupted instead sanitizes both input and content to {} and uses a deterministic placeholder only when the call ID has not arrived. This produces TurnOutput.Interrupted for reconciliation, never a completed executable turn.

Text and thinking use separate index namespaces and retain first-appearance block order. Thinking signatures concatenate exactly, including signature-only blocks and whitespace; this covers LlmChunk assembly, not provider signature decoding. Tool blocks follow these content blocks in numeric order. Usage is the latest complete snapshot, never a sum of streaming snapshots.

ProtocolJson is a package-private shared boundary. zio-json's ordinary decoder permits unconsumed suffixes, so a pure tail-recursive boundary scanner checks the full document, JSON whitespace, container/string escapes, and complete primitive tokens. AST parsing validates grammar; a second tail-recursive traversal rejects duplicate fields. This prevents both response frames and tool arguments from hiding trailing garbage.

## Request serialization

ChatCompletionsRequest.encode returns Either[AgentError, Json.Obj] without effects. It serializes ordered text messages, function definitions, canonical tool argument ASTs, streaming usage options, and an optional native response_format object. Credentials, endpoint, runtime identity, timestamps, usage, and unrelated message metadata are not request-body fields.

History must contain complete local call/result pairs. Orphan, duplicate, unknown, and unresolved results fail before serialization; another conversation message cannot split pending calls from their observations. Grouped or separately delivered parallel results may arrive in any order, and completed later turns may reuse an ID. A grouped Tool message becomes individual protocol messages. Error, Interrupted, and Denied outputs carry explicit labels; Running and suspended observations fail.

ReasoningReplay.Omit is the default. LatestAssistant replays unsigned thinking only from the last assistant message through reasoning_content. Signed/detailed thinking cannot be replayed by this codec. Media, provider-hosted server calls/results, signed calls, and non-text tool output fail explicitly. This is the text/function baseline, not a general provider formatter.

Validation covers nonblank models, finite temperature in [0, 2], positive optional token limits, protocol names, unique definitions/call IDs, object schemas/arguments/response formats, and duplicate JSON fields. ToolUse.input is authoritative; stale raw content is not sent. Forwarding a schema object does not validate arbitrary JSON Schema semantics. Endpoint, authentication headers, HTTP status handling, and request resource lifetime belong to the pending transport.

## Current acceptance

- [x] Message-boundary migration accepted at `a701612`; strict single-frame parsing accepted at `10019c2`.
- [x] Thirteen accumulator-completion regressions, five shared-JSON tests, and an additional frame-suffix regression pass alongside migrated assembly/thinking-retry tests.
- [x] Full formatting, fatal-warning compilation, and verification passed 361 core and 40 harness tests.
- [x] Implement pure text/function request serialization with sixteen paired regression tests.
- [ ] Implement live scoped HTTP transport, [DONE] termination, and deterministic integration tests.
- [ ] Complete provider signature/tool-metadata decoding, inline server results, richer reasoning details, input-size bounds, event publication, and exhaustive upstream parser/transport mapping.

See the [parity audit](../../../../../../../../../../docs/architecture/test-parity.md) and [tracker](../../../../../../../../../../docs/architecture/tracker.md). Acceptance is limited to named files and reviewed contracts.

- [x] `review_fp` and `review_harness` approved the final eight-file completion/JSON-boundary increment against `10019c2` on 2026-09-29 after fixing trailing-input acceptance. No findings remain within this scope.

| File | Reviewed Git blob |
| --- | --- |
| [ProtocolJson.scala](ProtocolJson.scala) | c1799a0f56d90f86ea3120463b099fd5c84df769 |
| [ReasoningModelAdapter.scala](ReasoningModelAdapter.scala) | 947f092612cdfc987472bb9054d43a2bbfca4170 |
| [StreamAccumulator.scala](StreamAccumulator.scala) | 038162ae8235c6d6432872c6acaf49db2dccb2d6 |
| [ProtocolJsonSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ProtocolJsonSpec.scala) | 8d0af6d6ea6ff3c8f704e1ed6e03ab7f4c858896 |
| [ReasoningAdapterValidationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ReasoningAdapterValidationSpec.scala) | 2e67ea902e6b8a50da802c9ccf3728e36d9b7c6c |
| [StreamAccumulatorSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/StreamAccumulatorSpec.scala) | 809ab37875025c99f3b6c870324b3b81c1a5a204 |
| [StreamAccumulatorCompletionSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/StreamAccumulatorCompletionSpec.scala) | 9e63fc534339d93594fe1c3f31c55e23b36b360c |
| [ReActAgentThinkingRetrySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentThinkingRetrySpec.scala) | cb149ca6c95027ace970b5a6fad5df1586b9c99e |

- [x] `review_fp` and `review_harness` approved the final request encoder and sixteen tests against `73f66d1` on 2026-09-29 after correcting pairing validation and negative fixtures. No findings remain within the text/function request scope; HTTP integration and exhaustive formatter parity remain pending.

| File | Reviewed Git blob |
| --- | --- |
| [ChatCompletionsRequest.scala](ChatCompletionsRequest.scala) | cbe28128f70333a2f7594cf4dbf70478c5c3a6f9 |
| [ChatCompletionsRequestSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsRequestSpec.scala) | 955e9811d921883838a5b71362c7c9a0488155d9 |

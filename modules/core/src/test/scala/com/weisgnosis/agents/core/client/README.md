# Model client and stream verification

These ZIO Test suites cover selected upstream scenarios and Scala runtime regressions. They do not certify exhaustive upstream parity or live-provider behavior.

- [ModelTextTransformSpec](ModelTextTransformSpec.scala)
- [ChatCompletionsTransformSpec](ChatCompletionsTransformSpec.scala)
- [ChatCompletionsClientSpec](ChatCompletionsClientSpec.scala)
- [ModelStreamPublisherSpec](ModelStreamPublisherSpec.scala)
- [HttpClientTestSupport](HttpClientTestSupport.scala)
- [ChatCompletionsTransportSpec](ChatCompletionsTransportSpec.scala)
- [ChatCompletionsRequestSpec](ChatCompletionsRequestSpec.scala)
- [StructuredOutputCapabilitySpec](StructuredOutputCapabilitySpec.scala)
- [LlmClientSpec](LlmClientSpec.scala)
- [JsonBoundarySpec](../json/JsonBoundarySpec.scala)
- [ReasoningAdapterSpec](ReasoningAdapterSpec.scala)
- [ReasoningAdapterValidationSpec](ReasoningAdapterValidationSpec.scala)
- [SseParserSpec](SseParserSpec.scala)
- [StreamAccumulatorCompletionSpec](StreamAccumulatorCompletionSpec.scala)
- [StreamAccumulatorSpec](StreamAccumulatorSpec.scala)

## Current evidence

- [x] Sixteen request-codec regressions cover ordered messages, schemas/options, authoritative arguments, paired parallel results, failure states, reasoning policies, private runtime data exclusion, and typed rejection of unsupported or malformed requests.

- [x] Sixteen strict-delta regressions cover field/index errors, duplicate keys, provider errors, unsupported calls, and complete frame boundaries; original reasoning/tool/usage parsing and context hygiene tests remain.
- [x] Thirteen StreamAccumulatorCompletionSpec regressions cover required finish markers, malformed/non-object/trailing tool arguments, interrupted sanitization, raw content, deferred/stable identity, duplicate IDs, interleaved indexes, signed blocks, usage snapshots, and post-finish rejection.
- [x] JsonBoundarySpec verifies six complete-document/duplicate/escape/numeric/direct-AST scenarios without effects.
- [x] Existing accumulator scenarios and two ReAct thinking-retry integration tests now handle Either and explicit successful finish markers.
- [x] Full `sbt scalafmtAll scalafmtSbt verify` passed 456 core and 40 harness tests with zero compiler warnings.
- [x] Fourteen transport tests cover real loopback HTTP plus deterministic scope, interruption, body-budget, protocol-termination, and failure fixtures.
- [x] Six live-client tests verify early deltas, real HTTP tool cycles, request identity, late call identity, malformed/incomplete streams, and cancellation.
- [x] Three publisher tests verify indexed content, interleaved calls, and atomic ending-batch validation; two event-context tests cover all sixteen correlated variants and legacy payloads.
- [ ] Complete remaining event integration, provider signature/metadata decoding, and exhaustive upstream scenario mapping.

## Current acceptance

- [x] Previously accepted request serialization, scoped transport, live client/events, and completed tool-result contracts are recorded at `38c48db`, `60b3799`, `84e4eb3`, and `9c165a7`. Unchanged contracts retain that acceptance; Git preserves earlier exact source identities.
- [x] `review_fp` and `review_harness` approved the shared JSON/structured HTTP increment against `9c165a7` on 2026-09-29 after correcting wrapper validation. The [JSON package acceptance table](../json/README.md) records the unchanged JSON-boundary identities from that scope; current capability identities are listed below. No findings remain within this scope.
- [x] Full formatting, fatal-warning compilation, and verification passed 456 core and 40 harness tests.
- [ ] Complete provider signatures/tool metadata, inline server response decoding, richer reasoning details, additional input-size bounds, incremental/structured local tool output, and exhaustive upstream mapping.

The validator moved from client.ProtocolJson to core.json.JsonBoundary as an internal implementation change. This keeps model and agent parsing independent of HTTP/client layering. decode validates exactly one textual JSON document; validate preserves an existing AST after rejecting duplicate fields. Public Msg structured extraction now rejects trailing input and ambiguous fields instead of accepting a prefix or one duplicate value.

- [x] `review_fp` and `review_harness` approved the final five-file capability increment against `06bd2e1` on 2026-09-29 after the preset-format correction. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 420 core and 40 harness tests.

The [client capability table](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md) records unchanged capability identities; commit `02b4654` preserves all five identities from that increment.

## Text transformation evidence

Four ModelTextTransformSpec tests cover identity, suspended ordered composition with stable context/index, typed fail-fast behavior, and compile-time exclusion of provider ModelError. Nine ChatCompletionsTransformSpec tests cover the real HTTP fenced-JSON scenario, unchanged thinking/tools/usage, scoped cleanup, malformed-frame and post-finish rejection before callbacks, transform failure, cooperative cancellation, strict transformed JSON, missing completion, and no Auto retry after an explicitly mapped host transformation error. The latter regression failed before narrowing the callback error channel. The HTTP scenario checks transformed deltas/end, finalized text/metadata/history, usage, and runtime context together.

- [x] `review_fp` and `review_harness` approved the final seven-file text-transformation increment against `02b4654` on 2026-09-29 after the fallback-provenance correction. No findings remain within this scope. Full formatting, fatal-warning compilation, and verification passed 434 core and 40 harness tests.

The [client contracts](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md) distinguish original diagnostic frames from transformed visible text and retain general middleware as pending work.

AgentErrorSpec verifies the new error codec and diagnostic message; the complete increment adds fourteen tests across these three suites.

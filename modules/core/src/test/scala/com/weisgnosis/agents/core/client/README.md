# Model client and stream verification

The [SSE frame-completion contract](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md#sse-frame-completion) records the reproduced EOF defect, exact three-file review identities and verification. One parser table adds fragmented LF/CRLF boundary coverage; the existing transport group requires the exact EOF failure for incomplete DONE. Complete frames remain accepted. These are local protocol regressions, not a nonexistent upstream SseParser suite or complete EventSource parity.

Toolkit construction and selection follow the [checked registry identity and migration contract](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#checked-registry-identity). The linked source/spec manifest owns the current migration review scope and verification; existing lifecycle and permission contracts remain in force.

Existing tool-choice tables verify omitted/default/null codecs, visible definitions and all encoded forms. The real HTTP tool-cycle table proves choice is not authorization: host-approved execution despite none, denied execution despite Specific, and zero HTTP for hidden Specific. Failover/structured rows inspect retained per-target choices. Exact identities and verification belong to the [tool-choice contract](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md#host-selected-tool-choice).

Existing request/failover tables cover optional topP and frequency/presence penalties: omission, explicit zero, inclusive endpoints, invalid values, per-target settings and projected clearing in serialized controlled-driver requests. Seed rows cover both Long endpoints, zero, omitted/null defaults, fractional/overflow codec rejection and exact serialized values. Settings rows also check retry reuse and native/synthetic requests. Current changed-file identities and verification are in the [settings contract](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md#optional-seed-and-exact-request-rendering). Existing rows also cover omitted/enabled/disabled parallel_tool_calls with and without tools, configuration migration, per-target preservation and actual HTTP dispatch/authorization when a provider returns multiple calls despite Disabled. Final steering acceptance is recorded in the [parallel-call contract](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md#optional-parallel-tool-call-steering). No new test groups or live-provider compatibility claim.

The [selected-reasoning contract](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md#encrypted-reasoning) records checked encrypted/text/summary values, ordered all-history replay and sibling-input protection. Two general-detail groups cover literal fields, profile rejection, scalar precedence, direct mixed-chunk rejection and canonical/event agreement. The real HTTP ReAct table checks both selected profiles: encrypted-only emits no Thinking events; general details emit exactly their plaintext projection. Both independent reviews and full reasoning-details-verify.log verification passed; the source contract owns exact identities and focused evidence. These are local protocol tests, not live OpenRouter interoperability.

These ZIO Test suites cover selected upstream scenarios and Scala runtime regressions. They do not certify exhaustive upstream parity or live-provider behavior.

- [ModelTextTransformSpec](ModelTextTransformSpec.scala)
- [ChatCompletionsTransformSpec](ChatCompletionsTransformSpec.scala)
- [EncryptedReasoningDetailSpec](EncryptedReasoningDetailSpec.scala)
- [ReasoningDetailSpec](ReasoningDetailSpec.scala)
- [ChatCompletionsClientSpec](ChatCompletionsClientSpec.scala)
- [ModelStreamPublisherSpec](ModelStreamPublisherSpec.scala)
- [HttpClientTestSupport](HttpClientTestSupport.scala)
- [ChatCompletionsTransportSpec](ChatCompletionsTransportSpec.scala)
- [CompletionRetryPolicySpec](CompletionRetryPolicySpec.scala)
- [ModelFailoverSpec](ModelFailoverSpec.scala)
- [ChatCompletionsRequestSpec](ChatCompletionsRequestSpec.scala)
- [StructuredOutputCapabilitySpec](StructuredOutputCapabilitySpec.scala)
- [LlmClientSpec](LlmClientSpec.scala)
- [JsonBoundarySpec](../json/JsonBoundarySpec.scala)
- [ReasoningAdapterSpec](ReasoningAdapterSpec.scala)
- [ReasoningAdapterValidationSpec](ReasoningAdapterValidationSpec.scala)
- [StopSequencesSpec](StopSequencesSpec.scala)
- [SseParserSpec](SseParserSpec.scala)
- [StreamAccumulatorCompletionSpec](StreamAccumulatorCompletionSpec.scala)
- [StreamAccumulatorSpec](StreamAccumulatorSpec.scala)

ChatCompletionsRequestSpec also verifies that descriptive ToolDef output metadata and local returnMode do not change the encoded provider request. The [output-schema acceptance](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#descriptive-output-schemas) records that scoped evidence; the current request/spec identities are in [user-media projection](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md#user-image-projection).

## Current evidence

- [x] Request-codec regressions cover ordered messages, schemas/options, authoritative arguments, paired parallel results, failure states, reasoning policies, private runtime data exclusion, and typed rejection of unsupported or malformed requests.

- [x] Sixteen strict-delta regressions cover field/index errors, duplicate keys, provider errors, unsupported calls, and complete frame boundaries; original reasoning/tool/usage parsing and context hygiene tests remain.
- [x] Thirteen StreamAccumulatorCompletionSpec regressions cover required finish markers, malformed/non-object/trailing tool arguments, interrupted sanitization, raw content, deferred/stable identity, duplicate IDs, interleaved indexes, signed blocks, usage snapshots, and post-finish rejection.
- [x] JsonBoundarySpec verifies six complete-document/duplicate/escape/numeric/direct-AST scenarios without effects.
- [x] Existing accumulator scenarios and two ReAct thinking-retry integration tests now handle Either and explicit successful finish markers.
- [x] The existing two media tables cover ordered typed image/audio and Data projection, exact inline MIME mappings, explicit image URL hints without extension inference, omitted hints/IDs/names, unsupported roles and whole-request failure. Both final MIME-hint reviews and full mime-hint-verify.log verification passed; the linked source contract owns current request/spec identities. Existing typed media, signature and reasoning behavior retains its accepted scope.
- [x] Transport tests cover real loopback HTTP plus deterministic scope, interruption, body-budget, protocol-termination, and failure fixtures.
- [x] Live-client tests verify early deltas, real HTTP tool cycles, request identity, late call identity, malformed/incomplete streams, and cancellation.
- [x] Three publisher tests verify indexed content, interleaved calls, and atomic ending-batch validation; two event-context tests cover all sixteen correlated variants and legacy payloads.
- [ ] Complete remaining event integration, other provider signature/metadata decoding, and exhaustive upstream scenario mapping.

- [x] Both final reviews and full signature-mcp-verify.log verification passed for selected Google tool-signature decoding/assembly/replay. Two unit groups cover stable indexed opaque values, JSON round-trip, interrupted sanitization and strict malformed/conflict rejection; one request group covers all-history replay; one real loopback ReAct group covers signed denial versus unsigned approval and the next HTTP request. No live-provider interoperability is claimed.

The [bounded status-retry contract](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md#bounded-http-status-retries) has final FP/ZIO and invariant approval plus full http-retry-verify.log verification. Its real HTTP table checks a rejected 429 followed by success and a terminal 400, with identical request body/auth, one input projection, one model context, no rejected-response chunks, and exact successful history/usage. Two controlled groups cover exact eligible statuses/budgets, same-valued public error exclusions, held cleanup, full failure causes, cancellation during body/cleanup and an interruptible delay. The unchanged cancellation regression now passes; no broader retry or scheduling coverage is claimed.

[ModelFailoverSpec](ModelFailoverSpec.scala) exercises the runtime-owned target boundary through the real completion adapter with controlled drivers. Both final reviews and full model-failover-verify.log verification are recorded in the [runtime contract](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md#scoped-model-failover). Its scope distinguishes eligible rejection from arbitrary public errors or successful-response failures, preserves canonical history with fresh target projections/contexts, and checks structured-plan compatibility and complete fallback failure causes. Its selected-request extension checks per-target settings and serialized views, unchanged tool/schema authority and fixed-input execution through controlled drivers; current acceptance is in the [projection contract](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md#current-model-input-identities). It does not establish live-provider routing, failover listeners or general network recovery.

## Per-tool strictness coverage

Existing request tables check omission, explicit true/false and mixed definitions with exact original schemas. Existing failover/structured requests require exact tool names/counts and the full lookup definition; synthetic requests require generate_response with strictness omitted. Codec, fixed-wrapper and MCP host-definition groups verify preservation without adding groups. The [strictness contract](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#provider-tool-schema-strictness) owns current identities and verification.

## Reasoning-effort coverage

Existing request/configuration tables cover every supported effort value, exact lowercase wire strings, omission/null and invalid enum decoding. Projection/failover tables verify each target's original preference, replacement, clearing and structured/retry preservation. The response-format table also checks json_object pass-through. No new groups were added. Exact identities, migration and verification belong to the [reasoning-effort contract](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md#optional-reasoning-effort).

## Stop-sequence coverage

The paired StopSequencesSpec checks nonempty construction, exact array round-trips and malformed input in one grouped table. Existing request/failover groups cover omission, canonical arrays, independent selected-target settings, replacement and clearing, structured formatting and retry body reuse. This is an added provider request setting; it is not a reproduced decoder defect. Exact final identities and verification belong to the [stop-sequence contract](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md#optional-stop-sequences).

## Cache usage evidence

Existing usage tables verify direct cache-hit precedence, explicit zero, absent/null fallback, cache-write preservation and strict malformed-count rejection, including overridden fallback counts. The existing real HTTP retry scenario checks these counters in canonical assistant history. No new test groups were added. Both independent reviewers approved the [exact source/spec identities](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md#cache-usage-decoding); that contract records RED/GREEN evidence and remaining verification gates.

## Current acceptance

- [x] Previously accepted request serialization, scoped transport, live client/events, and completed tool-result contracts are recorded at `38c48db`, `60b3799`, `84e4eb3`, and `9c165a7`. Unchanged contracts retain that acceptance; Git preserves earlier exact source identities.
- [x] `review_fp` and `review_harness` approved the shared JSON/structured HTTP increment against `9c165a7` on 2026-09-29 after correcting wrapper validation. The [JSON package acceptance table](../json/README.md) records the unchanged JSON-boundary identities from that scope; current capability identities are listed below. No findings remain within this scope.
- [x] `path_review` approved the three real HTTP fixtures at the identities below. Only those fixtures use `TestAspect.withLiveClock`; deterministic fixtures retain TestClock and suite timeouts remain 20 seconds.
- [ ] Complete other provider signatures/tool metadata, inline server response decoding, other reasoning-detail variants, additional input-size bounds, and exhaustive upstream mapping.

The validator moved from client.ProtocolJson to core.json.JsonBoundary as an internal implementation change. This keeps model and agent parsing independent of HTTP/client layering. decode validates exactly one textual JSON document; validate preserves an existing AST after rejecting duplicate fields. Public Msg structured extraction now rejects trailing input and ambiguous fields instead of accepting a prefix or one duplicate value.

- [x] Unchanged capability contracts retain `review_fp` and `review_harness` acceptance against `06bd2e1`; current source identities are linked below.

The [client capability table](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md) records unchanged capability identities; commit `02b4654` preserves all five identities from that increment.

## Text transformation evidence

Four ModelTextTransformSpec tests cover identity, suspended ordered composition with stable context/index, typed fail-fast behavior, and compile-time exclusion of provider ModelError. Nine ChatCompletionsTransformSpec tests cover the real HTTP fenced-JSON scenario, unchanged thinking/tools/usage, scoped cleanup, malformed-frame and post-finish rejection before callbacks, transform failure, cooperative cancellation, strict transformed JSON, missing completion, and no Auto retry after an explicitly mapped host transformation error. The latter regression failed before narrowing the callback error channel. The HTTP scenario checks transformed deltas/end, finalized text/metadata/history, usage, and runtime context together.

- [x] Unchanged text-transformation contracts retain `review_fp` and `review_harness` acceptance against `02b4654`; the real HTTP fixture's clock change is accepted below.

The [client contracts](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md) distinguish original diagnostic frames from transformed visible text and retain general middleware as pending work.

AgentErrorSpec verifies the text-transformation error codec and diagnostic message.

## Real HTTP fixture clocks

Real loopback client/server acquisition, use and release run with a live clock. zio-http 3.11.3 server shutdown polls active requests on a timed schedule; using an unadvanced TestClock can strand that cleanup. This path matches the reproduced transport timeout and TestClock warning in `subagent-tools-plan-verify.log`; that run did not capture a fiber stack. Scoped cleanup and existing assertions are unchanged.

`path_review` approved this three-file fixture scope for FP/ZIO lifecycle correctness with no findings. Five selected HTTP scenarios, including all three real loopback fixtures, passed twice before the complete verification in `subagent-tools-plan-final-verify.log`. These runs do not establish exhaustive scheduling coverage.

| Reviewed fixture suite | Git blob |
| --- | --- |
| ChatCompletionsTransportSpec | Current identity: [retry acceptance](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md#bounded-http-status-retries). |
| ChatCompletionsClientSpec | Current identity: [cache-usage acceptance](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md#cache-usage-decoding). |
| [ChatCompletionsTransformSpec](ChatCompletionsTransformSpec.scala) | [Current ChatCompletionsTransformSpec.scala identity](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#checked-registry-identity) |

# Model client and stream verification

Existing request tables now explicitly reject blank call names and two calls with only one matching result.

Existing parser/detail/HTTP tables cover the [scalar reasoning aliases](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md#scalar-reasoning-aliases), including exact coalescing, conflict rejection, null/empty/malformed values and authoritative details.

Three pure table groups and one integration group cover full-only responses, validated summary suppression, terminal/usage preservation and exact single-copy output/events/transforms. Existing incomplete-call and missing-DONE groups also exercise the selected profile. This remains bounded SSE behavior; ordinary JSON HTTP has its separate [non-streaming contract](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md#ordinary-json-completions).

One parser table adds fragmented LF/CRLF boundary coverage; the existing transport group requires the exact EOF failure for incomplete DONE. Complete frames remain accepted. These are local protocol regressions, not a nonexistent upstream SseParser suite or complete EventSource parity.

Toolkit construction and selection follow the [checked registry identity and migration contract](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#checked-registry-identity).

Existing tool-choice tables verify omitted/default/null codecs, visible definitions and all encoded forms. The real HTTP tool-cycle table proves choice is not authorization: host-approved execution despite none, denied execution despite Specific, and zero HTTP for hidden Specific. Failover/structured rows inspect retained per-target choices.

Existing request/failover tables cover optional topP and frequency/presence penalties: omission, explicit zero, inclusive endpoints, invalid values, per-target settings and projected clearing in serialized controlled-driver requests. Seed rows cover both Long endpoints, zero, omitted/null defaults, fractional/overflow codec rejection and exact serialized values. Settings rows also check retry reuse and native/synthetic requests. Existing rows also cover omitted/enabled/disabled parallel_tool_calls with and without tools, configuration migration, per-target preservation and actual HTTP dispatch/authorization when a provider returns multiple calls despite Disabled. The steering limits are described in the [parallel-call contract](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md#optional-parallel-tool-call-steering). No new test groups or live-provider compatibility claim.

The [selected-reasoning contract](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md#encrypted-reasoning) records checked encrypted/text/summary values, ordered all-history replay and sibling-input protection. Two general-detail groups cover literal fields, profile rejection, scalar precedence, direct mixed-chunk rejection and canonical/event agreement. The real HTTP ReAct table checks both selected profiles: encrypted-only emits no Thinking events; general details emit exactly their plaintext projection. These are local protocol tests, not live OpenRouter interoperability. The [summary text alias](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md#summary-text-alias) extends existing tables with legacy/equal-dual summary input, strict alias conflicts/malformed values and literal AST replay, plus full-message HTTP-client/ReAct text/legacy-summary/mixed fixtures.

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

ChatCompletionsRequestSpec also verifies that descriptive ToolDef output metadata and local returnMode do not change the encoded provider request.

## Current evidence

- Request-codec regressions cover ordered messages, schemas/options, authoritative arguments, paired parallel results, failure states, reasoning policies, private runtime data exclusion, and typed rejection of unsupported or malformed requests.

- Sixteen strict-delta regressions cover field/index errors, duplicate keys, provider errors, unsupported calls, and complete frame boundaries; original reasoning/tool/usage parsing and context hygiene tests remain.
- StreamAccumulatorCompletionSpec regressions cover required finish markers, malformed/non-object/trailing tool arguments, interrupted sanitization, raw content, deferred/stable identity, duplicate IDs, interleaved indexes, signed blocks, usage snapshots, and post-finish rejection.
- JsonBoundarySpec verifies six complete-document/duplicate/escape/numeric/direct-AST scenarios without effects.
- Existing accumulator scenarios and two ReAct thinking-retry integration tests now handle Either and explicit successful finish markers.
- Transport tests cover real loopback HTTP plus deterministic scope, interruption, body-budget, protocol-termination, and failure fixtures.
- Live-client tests verify early deltas, real HTTP tool cycles, request identity, late call identity, malformed/incomplete streams, and cancellation.
- publisher tests verify indexed content, interleaved calls, and atomic ending-batch validation; event-context tests cover all sixteen correlated variants and legacy payloads.
Unselected provider signatures, inline server-response dialects and reasoning-detail variants are not decoded automatically. These tests establish the listed checked boundaries, not every provider format or a global response-size limit.

[ChatCompletionsTransportSpec](ChatCompletionsTransportSpec.scala) checks a rejected 429 followed by success and a terminal 400, with identical request body/auth, one input projection, one model context, no rejected-response chunks, and exact successful history/usage. Two controlled groups cover exact eligible statuses/budgets, same-valued public error exclusions, held cleanup, full failure causes, cancellation during body/cleanup and an interruptible delay. The unchanged cancellation regression now passes; no broader retry or scheduling coverage is claimed.

[ModelFailoverSpec](ModelFailoverSpec.scala) exercises the runtime-owned target boundary through the real completion adapter with controlled drivers. Its scope distinguishes eligible rejection from arbitrary public errors or successful-response failures, preserves canonical history with fresh target projections/contexts, and checks structured-plan compatibility and complete fallback failure causes. It does not establish live-provider routing, failover listeners or general network recovery.

## Per-tool strictness coverage

Existing request tables check omission, explicit true/false and mixed definitions with exact original schemas. Existing failover/structured requests require exact tool names/counts and the full lookup definition; synthetic requests require generate_response with strictness omitted. Codec, fixed-wrapper and MCP host-definition groups verify preservation without adding groups.

## Reasoning-effort coverage

Existing request/configuration tables cover every supported effort value, exact lowercase wire strings, omission/null and invalid enum decoding. Projection/failover tables verify each target's original preference, replacement, clearing and structured/retry preservation. The response-format table also checks json_object pass-through. No new groups were added.

## Stop-sequence coverage

The paired StopSequencesSpec checks nonempty construction, exact array round-trips and malformed input in one grouped table. Existing request/failover groups cover omission, canonical arrays, independent selected-target settings, replacement and clearing, structured formatting and retry body reuse. This is an added provider request setting; it is not a reproduced decoder defect.

## Cache usage evidence

Existing usage tables verify direct cache-hit precedence, explicit zero, absent/null fallback, cache-write preservation and strict malformed-count rejection, including overridden fallback counts. The existing real HTTP retry scenario checks these counters in canonical assistant history. No new test groups were added.

## Inline video projection evidence

Existing positive/negative tables cover video/webm and video/mp4 exact data-URL projection, ordered mixed content, ignored private hints, invalid Base64/MIME rejection and unsupported roles/Data inference. This is pure serialization coverage, with no content/size inspection or live-provider evidence.

## Opaque signature profile evidence

The bounded extension targets strict signature-only decoding, unchanged existing profile admission, exact ordered arrays and explicit empty replay across historical assistant messages, Msg round-trip, separate scalar reasoning, pure full-completion decoding and a scripted ReAct cycle. It does not validate a live endpoint or change Google tool wire encoding.

## Current acceptance

These fixtures retain the selected-profile limits described above; they do not establish additional provider dialects.

The validator moved from client.ProtocolJson to core.json.JsonBoundary as an internal implementation change. This keeps model and agent parsing independent of HTTP/client layering. decode validates exactly one textual JSON document; validate preserves an existing AST after rejecting duplicate fields. Public Msg structured extraction now rejects trailing input and ambiguous fields instead of accepting a prefix or one duplicate value.

## Text transformation evidence

[ChatCompletionsTransformSpec](ChatCompletionsTransformSpec.scala) also verifies subscription-local text-delta filtering with an unfiltered positive control, and immutable projection of the returned Result metadata. Canonical history, full result events, runtime state, iteration and usage retain their original values; neither adaptation installs a before-broadcast mutable hook.

The cancellation regression requires already transformed/published text to survive in canonical partial history. Scripted HTTP transport is not a live endpoint test, and early external stream termination does not guarantee later terminal delivery.

ModelTextTransformSpec tests cover identity, suspended ordered composition with stable context/index, typed fail-fast behavior, and compile-time exclusion of provider ModelError. ChatCompletionsTransformSpec tests cover the real HTTP fenced-JSON scenario, unchanged thinking/tools/usage, scoped cleanup, malformed-frame and post-finish rejection before callbacks, transform failure, cooperative cancellation, strict transformed JSON, missing completion, and no Auto retry after an explicitly mapped host transformation error. The latter regression failed before narrowing the callback error channel. The HTTP scenario checks transformed deltas/end, finalized text/metadata/history, usage, and runtime context together.

The [client contracts](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md) distinguish original diagnostic frames from transformed visible text and retain general middleware as pending work.

AgentErrorSpec verifies the text-transformation error codec and diagnostic message.

## Real HTTP fixture clocks

Real loopback client/server acquisition, use and release run with a live clock. zio-http 3.11.3 server shutdown polls active requests on a timed schedule; using an unadvanced TestClock can strand that cleanup. Scoped cleanup and existing assertions are unchanged.

Existing transport/transform/reasoning usage assertions handle checked totalTokens/combine; serde fixtures preserve the unchanged wire fields.

ThinkingBudgetSpec supplies one checked-value group; existing request/failover tables verify exact optional thinking_budget, target initialization, override/clear and shared structured request encoding.

The [tool-image profile](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md#tool-image-result-projection) adds two grouped request/scripted-HTTP regressions for explicit ordered successful Text/Image observations, strict rejection and full message-array preservation in both response modes.

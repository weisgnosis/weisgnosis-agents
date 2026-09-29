# Model client port and stream assembly

LlmClient.streamTurn requires Scope and an explicit ModelStreamContext, and returns TurnOutput. Implementations must acquire request resources in that scope. The package provides scripted clients, LlmConfig, SSE framing, strict completion-delta decoding, reasoning-history adaptation, and StreamAccumulator. ChatCompletionsTransport provides a separate scoped HTTP completion operation. ChatCompletionsClient connects it to ReAct with incremental correlated events.

- [ChatCompletionsClient](ChatCompletionsClient.scala)
- [ModelStreamPublisher](ModelStreamPublisher.scala)
- [ChatCompletionsTransport](ChatCompletionsTransport.scala)
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

Missing/JSON-null usage counts normalize to zero. Supplied counts and indexes must be nonnegative JSON integers within Int bounds; strings, fractions, overflow, and malformed nested objects fail. Non-null provider error envelopes become ModelError with status 200, representing an error carried inside an otherwise successful streaming response. Missing/empty provider messages receive a deterministic fallback. The transport preserves actual HTTP rejection status separately.

Indexed function tools, text, and reasoning_content/thinking strings are supported. Non-assistant roles, nonempty refusal deltas, unsupported tool kinds, and non-null legacy function_call payloads fail explicitly. Canonical reasoning_content takes precedence over thinking. Unknown extension fields remain ignored unless they contain duplicate keys; this does not establish support for audio, richer reasoning details/signatures, or other provider payloads. Their integration remains pending.

The wire baseline uses [streamed function-call deltas](https://developers.openai.com/api/docs/guides/function-calling). Single-choice limits and strict validation are runtime policy. The accumulator enforces the completion contracts below. Indexed text/thinking/function block publication and agent HTTP integration are implemented below. Provider signature decoding remains separate work.

## Indexed assembly and completion

StreamAccumulator is now privately constructed through empty. feed returns Either[AgentError, StreamAccumulator], and toTurnOutput returns Either[AgentError, TurnOutput]; replace former fluent calls with flatMap. The former public case-class construction/copy and intermediate fields are removed. Normal completion requires stop with no tools or tool_calls with tools. Missing, repeated, truncated, filtered, or inconsistent completion markers cannot yield executable success. Usage snapshots may follow a finish marker; other later deltas fail.

Tool fragments remain keyed and emitted in numeric streamIndex order. IDs/names may arrive after argument fragments but must be nonblank, stable, present at completion, and IDs must be unique across completed calls. Arguments must be a complete JSON object with unique keys. Valid assembled raw text is retained in ToolUse.content exactly. Normal malformed/non-object/trailing input fails; toInterrupted instead sanitizes both input and content to {} and uses a deterministic placeholder only when the call ID has not arrived. This produces TurnOutput.Interrupted for reconciliation, never a completed executable turn.

Text and thinking use separate index namespaces and retain first-appearance block order. Thinking signatures concatenate exactly, including signature-only blocks and whitespace; this covers LlmChunk assembly, not provider signature decoding. Tool blocks follow these content blocks in numeric order. Usage is the latest complete snapshot, never a sum of streaming snapshots.

ProtocolJson is a package-private shared boundary. zio-json's ordinary decoder permits unconsumed suffixes, so a pure tail-recursive boundary scanner checks the full document, JSON whitespace, container/string escapes, and complete primitive tokens. AST parsing validates grammar; a second tail-recursive traversal rejects duplicate fields. This prevents both response frames and tool arguments from hiding trailing garbage.

## Request serialization

ChatCompletionsRequest.encode returns Either[AgentError, Json.Obj] without effects. It serializes ordered text messages, function definitions, canonical tool argument ASTs, streaming usage options, and an optional native response_format object. Credentials, endpoint, runtime identity, timestamps, usage, and unrelated message metadata are not request-body fields.

History must contain complete local call/result pairs. Orphan, duplicate, unknown, and unresolved results fail before serialization; another conversation message cannot split pending calls from their observations. Grouped or separately delivered parallel results may arrive in any order, and completed later turns may reuse an ID. A grouped Tool message becomes individual protocol messages. Error, Interrupted, and Denied outputs carry explicit labels; Running and suspended observations fail.

ReasoningReplay.Omit is the default. LatestAssistant replays unsigned thinking only from the last assistant message through reasoning_content. Signed/detailed thinking cannot be replayed by this codec. Media, provider-hosted server calls/results, signed calls, and non-text tool output fail explicitly. This is the text/function baseline, not a general provider formatter.

Validation covers nonblank models, finite temperature in [0, 2], positive optional token limits, protocol names, unique definitions/call IDs, object schemas/arguments/response formats, and duplicate JSON fields. ToolUse.input is authoritative; stale raw content is not sent. Forwarding a schema object does not validate arbitrary JSON Schema semantics. Endpoint, authentication headers, HTTP status handling, and request resource lifetime belong to ChatCompletionsTransport.

## Scoped HTTP completion

ChatCompletionsTransport.make accepts an injected zio-http Client, positive CompletionTransportLimits, and a ReasoningReplay policy. complete returns IO[AgentError, TurnOutput] and owns an inner request Scope. Success, failure, and cancellation close that scope before returning; cancellation stays interruption rather than a partial successful turn. The injected client layer and its connection pool belong to the host.

The transport sends POST JSON with Accept: text/event-stream and an optional Authorization bearer header. It requires an absolute HTTP(S) endpoint with a host, valid port, and no user information or fragment. Invalid limits, endpoints, credentials, or request bodies fail before dispatch. Transport failures use ModelError status zero without leaking exception text. HTTP rejection keeps the actual status and a bounded raw error payload; it does not retry automatically.

A successful HTTP status also requires one text/event-stream content type. UTF-8/SSE framing feeds the strict delta decoder and accumulator. Both a compatible finish marker and [DONE] are required. EOF without [DONE], malformed protocol data, truncated completion, or invalid tool arguments cannot yield executable output. [DONE] stops consumption without waiting for connection EOF. Usage frames after finish and before [DONE] remain available.

The default success-body budget is 8 MiB and the error-body prefix is 8 KiB, both configurable. The success budget counts received byte chunks before decoding, including any suffix already received in the same chunk as [DONE]; it is not a bound on the injected HTTP driver's allocation or total assembled JVM memory. Error prefixes may end inside a UTF-8 character and use replacement decoding. Request-size bounds, per-frame limits, deadlines, retry policy, TLS/proxy/pool configuration, and provider extensions are separate host or planned concerns.

Fourteen tests cover a real loopback POST/chunked SSE response plus deterministic driver fixtures for request headers/body, split UTF-8, parallel calls, usage, finish/[DONE] requirements, malformed data, provider/status failures, body limits, and interruption cleanup. The separate client integration suite verifies actual incremental publication, a real HTTP tool cycle, late tool identity, malformed-frame rejection, and cooperative cancellation.

## Live client and event correlation

ChatCompletionsClient.make accepts the injected HTTP client and transport options, producing LlmClient. ReAct assigns a fresh ModelStreamContext(replyId, requestId) for every model invocation. replyId belongs to the enclosing agent attempt; requestId changes on each iteration or retry. The streamTurn signature now requires this argument: custom implementations and direct callers must migrate. Scripted callbacks keep their existing factory signatures, but hand-written events are not automatically enriched.

The runtime emits ModelRequestStart before invocation and ModelResponseEnd only after a non-interrupted output and request-scope cleanup. The latter carries output/total token counts from the reply; request promptTokens remains zero because no estimate is available. Failed or interrupted requests have no successful response-end event. AgentEnd still closes the surrounding attempt. The live client publishes content events inside these model boundaries; direct streamTurn callers own their model-boundary publication.

Each valid SSE JSON frame produces one ModelResponseChunk, then ordered text/thinking/function events. The entire frame is decoded and accepted by the accumulator before publication. Earlier valid events remain observable if a later frame fails. Deltas are provisional input, not completed conversation state or authorization to execute a tool.

Text and thinking retain separate index namespaces and one start per index. Tool events use streamIndex as blockIndex; identity may arrive late, so arguments are buffered until both call ID and name exist, then emitted as one buffered delta. Subsequent fragments preserve their received order. Only a validated finish plus [DONE] closes blocks, in start order, and exposes the assembled turn. Failure/cancellation may leave observed starts without ends; consumers must use runtime termination/error rather than invent completion. Thinking signatures remain in the assembled Msg; these text-oriented events do not carry signature fragments.

Live deltas precede ModelResponseEnd. Open text/thinking blocks remain open until validated completion, allowing interleaving without premature endings. This deliberately differs from disabled upstream ordering sketches that place model-end before buffered content and close thinking before text starts. Full ordering parity, local tool-result correlation, and richer provider content remain pending.

## Current acceptance

- [x] Message-boundary migration accepted at `a701612`; strict single-frame parsing accepted at `10019c2`.
- [x] Thirteen accumulator-completion regressions, five shared-JSON tests, and an additional frame-suffix regression pass alongside migrated assembly/thinking-retry tests.
- [x] Full formatting, fatal-warning compilation, and verification passed 386 core and 40 harness tests.
- [x] Implement pure text/function request serialization with sixteen paired regression tests.
- [x] Implement scoped HTTP completion, [DONE] termination, bounded bodies, and fourteen tests including real loopback HTTP.
- [x] Integrate ChatCompletionsClient, runtime request correlation, and end-to-end ReAct streaming with eleven new client/publisher/context tests.
- [ ] Complete provider signature/tool-metadata decoding, inline server results, richer reasoning details, additional input-size bounds, remaining event publication, and exhaustive upstream parser/transport mapping.

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

- [x] `review_fp` and `review_harness` approved the final request encoder and sixteen tests against `73f66d1` on 2026-09-29 after correcting pairing validation and negative fixtures. No findings remain within the text/function request scope; HTTP integration and exhaustive formatter parity were excluded from that earlier approval.

| File | Reviewed Git blob |
| --- | --- |
| [ChatCompletionsRequest.scala](ChatCompletionsRequest.scala) | cbe28128f70333a2f7594cf4dbf70478c5c3a6f9 |
| [ChatCompletionsRequestSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsRequestSpec.scala) | 955e9811d921883838a5b71362c7c9a0488155d9 |

- [x] `review_fp` and `review_harness` approved the scoped HTTP transport and fourteen tests against `38c48db` on 2026-09-29. No findings remain within this scope; LlmClient/event integration, retries, and full provider parity are excluded. Full verification passed 375 core and 40 harness tests.

| File | Reviewed Git blob |
| --- | --- |
| [ChatCompletionsTransport.scala](ChatCompletionsTransport.scala) | 955e6ddcfe25efb4e42a9f3c731ea4c97bf6a242 |
| [ChatCompletionsTransportSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsTransportSpec.scala) | 938047f7320998feea85eae23e5d665e09f6619d |

- [x] `review_fp` and `review_harness` approved the final live client/event increment against `60b3799` on 2026-09-29. No findings remain in runtime correlation, validated incremental publication, scoped cleanup, or the migrated/new tests. Full verification passed 386 core and 40 harness tests. Full upstream ordering parity and local tool-result event integration are excluded.

Exact final integration scope (supersedes earlier identities for changed files):

| File | Reviewed Git blob |
| --- | --- |
| [ReActAgent.scala](../agent/ReActAgent.scala) | 508d37e17aa49cd88be4e80cfb990f698c03c1d0 |
| [ChatCompletionsClient.scala](ChatCompletionsClient.scala) | b80c5b8b3a46a07ba79804e311307df1d2bb7f39 |
| [ChatCompletionsTransport.scala](ChatCompletionsTransport.scala) | 696302acc494d81606ab264f29f0e1c97b2e2eab |
| [LlmClient.scala](LlmClient.scala) | 13094412a3b79d894182cc9b4e31d2bdf68364de |
| [ModelStreamPublisher.scala](ModelStreamPublisher.scala) | 1ad3bcf5c23e570bf0b86246c23d23efd435575b |
| [AgentEvent.scala](../event/AgentEvent.scala) | 33df0c53ce69281401cff314812497875b499a1c |
| [ModelStreamContext.scala](../event/ModelStreamContext.scala) | fc28f0f5accfa3c773f462eb1e2dd8f06c844d7c |
| [ReActAgentEventSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentEventSpec.scala) | afac7dc6d5d58feed4ffd138368705df20f4619c |
| [ReActAgentLifecycleSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentLifecycleSpec.scala) | 8e042037b425d84514741e42e2620f3d8b889891 |
| [StructuredOutputSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/StructuredOutputSpec.scala) | 3e98f032ca5ce23fc6b6a5e08cfc2256edaf967b |
| [ChatCompletionsClientSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsClientSpec.scala) | bd89eb872ee947587848fb9ef9d07ff4c4178641 |
| [ChatCompletionsTransportSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsTransportSpec.scala) | 1d1a5947403ba5750bd2890c4dbfd84479c96bb2 |
| [HttpClientTestSupport.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/HttpClientTestSupport.scala) | 2e1a8bf59a6dae2d45aebcfc79c2416940821e9f |
| [LlmClientSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/LlmClientSpec.scala) | 0f2cdb77254e8dbfa325265018a6a96bd85798d8 |
| [ModelStreamPublisherSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ModelStreamPublisherSpec.scala) | dbb35a012d48eda8be91a795b20588c49e1a3eff |
| [ModelStreamContextSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/event/ModelStreamContextSpec.scala) | bc75ea0694f96aed40e3d4d55c9617842edd644d |

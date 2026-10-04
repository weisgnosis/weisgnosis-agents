# Model client port and stream assembly

LlmClient.streamTurn requires Scope and an explicit ModelStreamContext, and returns TurnOutput. Implementations must acquire request resources in that scope. The package provides scripted clients, LlmConfig, SSE framing, strict completion-delta decoding, reasoning-history adaptation, and StreamAccumulator. ChatCompletionsTransport provides a separate scoped HTTP completion operation. ChatCompletionsClient connects it to ReAct with incremental correlated events.

- [ChatCompletionsClient](ChatCompletionsClient.scala)
- [ModelTextTransform](ModelTextTransform.scala)
- [ModelStreamPublisher](ModelStreamPublisher.scala)
- [ChatCompletionsTransport](ChatCompletionsTransport.scala)
- [CompletionRetryPolicy](CompletionRetryPolicy.scala)
- [ChatCompletionsRequest](ChatCompletionsRequest.scala)
- [LlmChunk](LlmChunk.scala)
- [EncryptedReasoningDetail](EncryptedReasoningDetail.scala)
- [ReasoningDetail](ReasoningDetail.scala)
- [LlmClient](LlmClient.scala)
- [ModelTarget](ModelTarget.scala)
- [BeforeResponseRejection](BeforeResponseRejection.scala)
- [StructuredOutputCapability](StructuredOutputCapability.scala)
- [JsonBoundary](../json/JsonBoundary.scala)
- [ReasoningModelAdapter](ReasoningModelAdapter.scala)
- [SseParser](SseParser.scala)
- [StreamAccumulator](StreamAccumulator.scala)
- [TurnOutput](TurnOutput.scala)

## Structured output capabilities

LlmConfig.structuredOutputCapability is a host declaration for the configured model/endpoint, not automatic discovery. Unknown is the default for source and JSON compatibility. Native() permits application tools by default; Native(NativeToolSupport.Unsupported) restricts native formatting to requests without application tools. Unsupported cannot declare tool support independently.

| Declaration | Auto without application tools | Auto with application tools |
| --- | --- | --- |
| Unknown | Native with rejection fallback | Synthetic |
| Unsupported | Synthetic | Synthetic |
| Native() | Native with rejection fallback | Native with rejection fallback |
| Native(NativeToolSupport.Unsupported) | Native with rejection fallback | Synthetic |

Explicit Native and SyntheticTool modes override this selection. Native installs the supplied structured schema. Every synthetic attempt clears responseFormat while retaining other configuration, including when falling back from a rejected native attempt. Ordinary runs preserve the host's responseFormat. Auto native attempts fall back only on an exact singleton ModelError failure with status 400, 422, or 501; other errors and cancellation propagate. This status policy is broader than provider-specific capability error classification.

The declaration is serialized with LlmConfig but excluded from the HTTP request body. Existing constructor calls retain their defaults; positional pattern matches against LlmConfig must account for the new final field. Unknown preserves prior routing, while declared Unsupported avoids a native probe entirely.

## Completion data boundary

ReasoningModelAdapter.parseChunk now returns Either[AgentError, Chunk[LlmChunk]]. This is a source-breaking replacement for the former silent empty-Chunk fallback. Lift the result with ZIO.fromEither; malformed recognized fields must fail the request rather than disappear. SSE framing and [DONE] handling remain outside this JSON decoder.

Normal frames require an explicit choices array. Exactly one choice with an explicit integer index of zero is supported; alternative choices are rejected instead of combined. Empty choices are permitted with usage. Role-only and empty terminal deltas are valid. Tool fragments require nonnegative integer stream indexes, unique within each frame; optional IDs/names support later fragments without inventing index zero. Duplicate JSON fields are rejected throughout the input tree, including ignored extension objects.

Missing/JSON-null usage counts normalize to zero. Supplied counts and indexes must be nonnegative JSON integers within Int bounds; strings, fractions, overflow, and malformed nested objects fail. Non-null provider error envelopes become ModelError with status 200, representing an error carried inside an otherwise successful streaming response. Missing/empty provider messages receive a deterministic fallback. The transport preserves actual HTTP rejection status separately.

Indexed function tools, text, and reasoning_content/thinking strings are supported. Non-assistant roles, nonempty refusal deltas, unsupported tool kinds, and non-null legacy function_call payloads fail explicitly. For the existing scalar profiles, reasoning_content takes precedence over thinking. Unknown extension fields remain ignored unless they contain duplicate keys; this does not establish support for response audio or other provider payloads. The explicit OpenRouterDetails profile below instead derives plaintext exclusively from its checked detail array; it preserves exact checked objects for replay.

The wire baseline uses [streamed function-call deltas](https://developers.openai.com/api/docs/guides/function-calling). Single-choice limits and strict validation are runtime policy. The accumulator enforces the completion contracts below. Indexed text/thinking/function block publication and agent HTTP integration are implemented below. The explicit Google tool-signature contract is described below; other signature formats remain separate.

## Indexed assembly and completion

StreamAccumulator is now privately constructed through empty. feed returns Either[AgentError, StreamAccumulator], and toTurnOutput returns Either[AgentError, TurnOutput]; replace former fluent calls with flatMap. The former public case-class construction/copy and intermediate fields are removed. Normal completion requires stop with no tools or tool_calls with tools. Missing, repeated, truncated, filtered, or inconsistent completion markers cannot yield executable success. Usage snapshots may follow a finish marker; other later deltas fail.

Tool fragments remain keyed and emitted in numeric streamIndex order. IDs/names may arrive after argument fragments but must be nonblank, stable, present at completion, and IDs must be unique across completed calls. Arguments must be a complete JSON object with unique keys. Valid assembled raw text is retained in ToolUse.content exactly. Normal malformed/non-object/trailing input fails; toInterrupted instead sanitizes both input and content to {} and uses a deterministic placeholder only when the call ID has not arrived. This produces TurnOutput.Interrupted for reconciliation, never a completed executable turn.

Text and thinking use separate index namespaces and retain first-appearance block order. Thinking signatures concatenate exactly, including signature-only blocks and whitespace; this covers ThinkingDelta assembly, not provider thinking-signature decoding. Tool blocks follow these content blocks in numeric order. Usage is the latest complete snapshot, never a sum of streaming snapshots.

JsonBoundary is a library-internal shared boundary in the independent json package. zio-json's ordinary decoder permits unconsumed suffixes, so a pure tail-recursive boundary scanner checks the full document, JSON whitespace, container/string escapes, and complete primitive tokens. AST parsing validates grammar; a second tail-recursive traversal rejects duplicate fields. This prevents response frames, tool arguments, and structured text from hiding trailing garbage. Existing request/metadata/captured ASTs use direct duplicate-field validation without a serialization round trip.

## Request serialization

ChatCompletionsRequest.encode returns Either[AgentError, Json.Obj] without effects. It serializes ordered text and user image/audio messages, including explicitly typed inline Data blocks, function definitions, canonical tool argument ASTs, streaming usage options, and an optional native response_format object. Credentials, endpoint, runtime identity, timestamps, usage, and unrelated message metadata are not request-body fields. ToolDef.returnMode is local runtime policy and is omitted from provider definitions. ToolDef.outputSchema remains descriptive metadata for custom clients; this encoder emits no output_schema extension. Its omission is covered by the [output-schema contract](../tool/README.md#descriptive-output-schemas).

History must contain complete local call/result pairs. Orphan, duplicate, unknown, and unresolved results fail before serialization; another conversation message cannot split pending calls from their observations. Grouped or separately delivered parallel results may arrive in any order, and completed later turns may reuse an ID. A grouped Tool message becomes individual protocol messages. Error, Interrupted, and Denied outputs carry explicit labels; Running and suspended observations fail.

ReasoningReplay.Omit is the default. LatestAssistant replays unsigned thinking only from the last assistant message through reasoning_content. OpenRouterEncryptedDetails validates and replays encrypted detail arrays from every historical assistant message. OpenRouterDetails additionally supports checked text and summary objects; other detailed/signed thinking formats remain unsupported. Unsupported media, provider-hosted server calls/results, calls signed with an unsupported format, and non-text tool output fail explicitly. User-media projection is defined below; broader provider formatting remains separate.

Validation covers nonblank models, finite temperature in [0, 2], positive optional token limits, protocol names, unique definitions/call IDs, object schemas/arguments/response formats, and duplicate JSON fields. ToolUse.input is authoritative; stale raw content is not sent. Forwarding a schema object does not validate arbitrary JSON Schema semantics. Endpoint, authentication headers, HTTP status handling, and request resource lifetime belong to ChatCompletionsTransport.

## User-image projection

User messages containing only text retain their original string wire shape. A message containing supported media becomes an ordered array of text, image_url and input_audio parts. HTTP(S) image URLs require a host and valid port, reject raw whitespace/control characters, and retain their original spelling. Inline images require an explicit image/png, image/jpeg, image/webp or image/gif media type and nonempty standard Base64 syntax. Image, audio and Data IDs and names are not provider fields. Invalid or unsupported blocks reject the whole request; no block is silently dropped or replaced with a placeholder.

Inline audio accepts only MediaSource.Base64 with exact audio/wav or audio/mpeg MIME values, projecting format wav or mp3 respectively and preserving the original Base64 string. Payloads require the same nonempty standard Base64 syntax as inline images. Audio URLs, MIME aliases, parameters and other formats fail; the codec never guesses a format or substitutes a text reference. The [official input-audio schema](https://raw.githubusercontent.com/openai/openai-python/main/src/openai/types/chat/chat_completion_content_part_input_audio_param.py) specifies input_audio.data and format wav|mp3; the exact MIME mapping is local boundary policy.

User ContentBlock.Data values accept only inline Base64 with those same four image MIME types or audio/wav and audio/mpeg. They project through the same validators and image_url/input_audio shapes in original block order. Data URLs, MIME inference, aliases and other MIME types fail the whole request.

This is pure serialization: it does not fetch URLs, read files, decode media bytes, inspect audio headers/duration, detect animation or validate provider size limits. In particular, accepting image/gif does not establish that the image is non-animated. Image/audio/Data content in system, assistant and tool messages remains unsupported; video and other Data media are also rejected. Standard tool content stays text-only. The [official user schema](https://raw.githubusercontent.com/openai/openai-python/main/src/openai/types/chat/chat_completion_user_message_param.py), [image-part schema](https://raw.githubusercontent.com/openai/openai-python/main/src/openai/types/chat/chat_completion_content_part_image_param.py), [tool-message schema](https://raw.githubusercontent.com/openai/openai-python/main/src/openai/types/chat/chat_completion_tool_message_param.py) and [image guide](https://developers.openai.com/api/docs/guides/images-vision) define the selected provider surface. This does not imply compatibility with every endpoint accepting Chat Completions requests.

No Scala API or domain-codec shape changes. Previously rejected supported user media now serialize; text-only requests and unsupported-role rejection retain their existing behavior. Full provider media validation remains the caller/provider responsibility.

The two existing media tables verify all six MIME mappings through typed media and Data blocks, ordered mixed content, local-field omission, malformed payloads, rejected MIME aliases/URLs and unsupported roles. There are no new test groups. Existing typed image/audio, signature and reasoning behavior remains unchanged.

| File | Reviewed Git blob |
| --- | --- |
| [ChatCompletionsRequest.scala](ChatCompletionsRequest.scala) | 17081b90f6fc314e5b47e782df2dcc7e0264559a |
| [ChatCompletionsRequestSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsRequestSpec.scala) | [Current fixture identity](../agent/README.md#host-selected-return-direct-completion) |

- [x] path_review approved FP/ZIO correctness and current_docs approved invariants for the two identities above, with no findings. The existing focused suites passed 19 tests in 212 ms (inline-data-focused.log); full scalafmtAll scalafmtSbt verify passed in inline-data-verify.log with zero compiler warnings or failed tests. Current aggregate totals belong to the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).
- [ ] Audio output, URL retrieval, additional media/provider formats and live-provider interoperability remain unfinished.

## Tool-call signatures

ToolSignatureFormat.Unsupported is the default. A host may explicitly choose GoogleExtraContent through ChatCompletionsClient.make or ChatCompletionsTransport.make; the transport uses the same immutable choice for response decoding and every historical request call. The selected wire field is tool_calls[].extra_content.google.thought_signature, following the [Google OpenAI-compatibility signature contract](https://ai.google.dev/gemini-api/docs/generate-content/thought-signatures#signatures-for-openai-compatibility). This does not enable generic provider metadata, ThinkingBlock signatures, reasoningDetails or server-hosted calls.

The decoder accepts an absent/JSON-null extra_content as absent. Present extra_content and google containers must be objects; a supplied thought_signature must be a nonblank string. Unsupported rejects a recognized Google signature before dispatch. Unknown extension fields remain ignored, apart from duplicate-key rejection; they cannot grant server-tool authority. The opaque string is retained exactly, without decoding or concatenation. Each stream index may receive the same value again but cannot change it. Completed and interrupted ToolUse metadata retain thoughtSignature, including when interruption sanitizes unfinished arguments to {}. Parallel calls never inherit another call's signature.

GoogleExtraContent replays that metadata on its original call across all historical assistant messages, independently of ReasoningReplay's latest-thinking policy. Other metadata is not copied to the wire. Malformed reserved signature metadata fails instead of being silently omitted. The existing runtime guard denies argument rewrites on signed calls, preserving the original canonical input; signatures are protocol data and confer no execution permission. Raw diagnostic frames may contain signatures; the added validation messages do not quote signature values.

Migration: LlmChunk.ToolCallChunk appends defaulted thoughtSignature: Option[String]; old JSON omitting it still decodes to None, while positional extractors/Product now have five fields. encode and both make methods append a defaulted format argument, preserving ordinary source calls; method values may need adaptation and dependent binaries must be rebuilt. ReasoningModelAdapter retains its one-argument parseChunk overload and adds an explicit-format overload. No ownership, retries or provider discovery are introduced.

Unchanged signature-scope identities remain below; current request/spec identities are in [user-media projection](#user-image-projection), and other changed client identities are in [selected reasoning](#encrypted-reasoning).

| File | Reviewed Git blob |
| --- | --- |
| ChatCompletionsClient.scala | Current identity: [retry acceptance](#bounded-http-status-retries). |

| [ReasoningAdapterValidationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ReasoningAdapterValidationSpec.scala) | af2751bfa6d0e9388411acc93928d6012ffed63d |

The signature contract retains its independent path_review/current_docs approval and signature-mcp-verify.log evidence. Its real HTTP fixture verifies per-call preservation and signed rewrite denial, not live Google interoperability.

## Encrypted reasoning

ReasoningReplay.OpenRouterEncryptedDetails selects response decoding and all-historical-assistant replay for reasoning.encrypted only. OpenRouterDetails additionally selects reasoning.text and reasoning.summary from the [OpenRouter reasoning-details contract](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens#reasoning-details-api-shape). Both preserve each checked Json.Obj exactly, including absent fields and explicit nulls.

ReasoningDetail accepts a strict local union: encrypted requires nonblank opaque data; text requires a text string and permits an absent, null or nonblank opaque signature; summary requires a summary string, not Java's legacy text field. Empty text and summary strings are valid. Each variant permits optional id (nonblank string or null), format (nonblank string) and index (nonnegative Int JSON integer). Duplicate, unknown, misplaced or malformed fields fail. EncryptedReasoningDetail retains its narrower validation and provides toDetail. Data and signatures are neither decoded nor concatenated; IDs/indexes never infer tool identity or merge objects.

ReasoningDetailsDelta retains objects in received order, including repeated IDs/indexes. Assembly stores one Thinking block at the first detail occurrence, with the exact reasoningDetails array and concatenated text/summary plaintext. Encrypted objects contribute no plaintext. Explicit [] is retained and replayed; absent/null wire fields remain absent. The general decoder treats any supplied array, including [], as authoritative over same-frame scalar reasoning_content/thinking aliases. Without an array, nonempty scalar aliases fail under OpenRouterDetails; empty strings are allowed and whitespace is nonempty. This avoids cross-frame alias deduplication and deliberately limits provider compatibility. Other profiles retain their scalar behavior. Direct accumulator callers cannot mix scalar ThinkingDelta blocks and details with nonempty plaintext in either order; opaque-only details and [] remain compatible with scalar blocks.

Selected replay validates original history and emits ordered arrays on every original assistant message, never on individual tool calls. It emits no reasoning_content and enables no other Thinking metadata. Unsupported replay profiles reject supplied arrays, including []. General detail plaintext produces one consistent Thinking event stream and canonical projection; encrypted-only details emit no Thinking events. Raw diagnostic frames remain unredacted. Google per-call signatures remain a separate host-selected format.

A nonempty detail array or a nonblank Thinking signature counts as reasoning for the bounded thinking-only reminder; [] alone does not. Presence of either reserved reasoning key in any Thinking block protects every sibling call's original arguments, even for [] or malformed metadata. Fixed preparation and approved rewrites must preserve them. Older protected Thinking blocks survive pruneObsoleteReasoning, and the compactor's argument-pruning pass preserves sibling inputs/cached content. Whole-prefix summarization and trusted host history/model-input/post-reasoning callbacks remain replacement boundaries; this is not indefinite storage or permission authority.

Migration: exhaustive ReasoningReplay matches must handle OpenRouterDetails. ReasoningDetailsDelta.details now contains Chunk[ReasoningDetail], replacing Chunk[EncryptedReasoningDetail]; direct producers can map encrypted values through toDetail and must recompile. Existing make arguments, decoder overloads and default policies remain. Msg JSON is unchanged. Checked AST codecs preserve validated representation rather than normalizing a DTO. Invalid history fails before serialization instead of silently dropping metadata.

| File | Reviewed Git blob |
| --- | --- |
| ReActAgent.scala | Encrypted-reasoning baseline ddfd6a7af17856c6c075d9674cceb7e01c14dbc5; current identity: [acting-stop decisions](../agent/README.md#acting-stop-decisions). |
| ChatCompletionsRequest.scala | Current identity: [user-media projection](#user-image-projection). |
| ChatCompletionsTransport.scala | Current identity: [retry acceptance](#bounded-http-status-retries). |
| [LlmChunk.scala](LlmChunk.scala) | a35e034b66a8139d05ee1c6b1622ca19871538b1 |
| [ModelStreamPublisher.scala](ModelStreamPublisher.scala) | 35b65c607b31f46da8213f4ffcfb12a6d7439128 |
| [ReasoningModelAdapter.scala](ReasoningModelAdapter.scala) | 8873f07ca50be4f4881bd6cd3ff05e4b50741b0d |
| [StreamAccumulator.scala](StreamAccumulator.scala) | 1a01caa33dd0bfbefac53cf4697c6168d73c2a79 |
| [Msg.scala](../model/Msg.scala) | e95148040f9bb3499aaf43fed3a8e6ed21237672 |
| [ReActAgentReasoningHookSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentReasoningHookSpec.scala) | fbf31dcaeb77162c6354047cb299c7773ff7e461 |
| [ReActAgentToolAuthorizerSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentToolAuthorizerSpec.scala) | 4ce0c254250b9428990dd24e6a0e6310d41cb867 |
| ChatCompletionsClientSpec.scala | Current identity: [retry acceptance](#bounded-http-status-retries). |
| [ReasoningAdapterSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ReasoningAdapterSpec.scala) | 1f17e10621d99b2cd79f0382f7c4cc7255b68cf0 |
| [StreamAccumulatorSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/StreamAccumulatorSpec.scala) | e90bd63fa63fa74f395d557a5199093dc77f8954 |
| [ConversationCompactor.scala](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/memory/ConversationCompactor.scala) | fc7dda775a912ce8c4eb567ce9d90ea44df0f640 |
| [ConversationCompactorSpec.scala](../../../../../../../../../harness/src/test/scala/com/weisgnosis/agents/harness/memory/ConversationCompactorSpec.scala) | 5eac7143da4b0c03200df7b9cd92395200f4838d |
| [EncryptedReasoningDetail.scala](EncryptedReasoningDetail.scala) | 49a0287e2476b00943ff69f480f4ebca2ee46c37 |
| [EncryptedReasoningDetailSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/EncryptedReasoningDetailSpec.scala) | f6e5ec577c8f7da54c0e8f582321465cf693af16 |
| [ReasoningDetail.scala](ReasoningDetail.scala) | 3cb2bbf21dbc02fe65b21435e962ca061eecff63 |
| [ReasoningDetailSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ReasoningDetailSpec.scala) | 99be2c6d4da164e11fe9f946bf681e5297f710ba |

- [x] path_review approved FP/ZIO correctness and current_docs approved invariants for the nine-file general-details scope at the identities above, with no open findings. Full scalafmtAll scalafmtSbt verify passed in reasoning-details-verify.log with zero compiler warnings or failed tests. Focused reasoning-details-green.log passed 39 tests in 5.262 seconds. Unchanged encrypted-reasoning contracts retain their prior acceptance; current aggregate totals belong to the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).
- [x] Two new pure groups and the existing HTTP table cover checked variants, literal null/empty values, scalar precedence, both mixed-chunk orders, exact historical replay and canonical/event agreement. The HTTP rows retain sibling-input protection for both selected profiles. This is loopback evidence, not live OpenRouter interoperability.
- [ ] Other provider reasoning/signature formats, non-streaming completion-message support and exhaustive upstream formatter parity remain unfinished.

The public mixed-chunk regression in ReasoningDetailSpec 99be2c6d4da164e11fe9f946bf681e5297f710ba failed against accumulator 504510573cf7e492f3ee2fc530dd9d4e81d8397a in reasoning-details-mixing-red.log (11 passed, one failed, 5.136 seconds): both scalar/plaintext-detail orders were accepted instead of rejected. The same spec passed after the guard; opaque-only and empty-array near-misses remain permitted.

## Scoped HTTP completion

ChatCompletionsTransport.make accepts an injected zio-http Client, positive CompletionTransportLimits, ReasoningReplay, ToolSignatureFormat and a default-disabled CompletionRetryPolicy. complete returns IO[AgentError, TurnOutput] and owns a separate Scope for each HTTP attempt. Success, failure, and cancellation close the attempt scope before returning or retrying; cancellation stays interruption rather than a partial successful turn. The injected client layer and its connection pool belong to the host.

The transport sends POST JSON with Accept: text/event-stream and an optional Authorization bearer header. It requires an absolute HTTP(S) endpoint with a host, valid port, and no user information or fragment. Invalid limits, endpoints, credentials, or request bodies fail before dispatch. Transport failures use ModelError status zero without leaking exception text. HTTP rejection keeps the actual status and a bounded raw error payload. Default behavior makes one attempt; explicit status retries are described below.

A successful HTTP status also requires one text/event-stream content type. UTF-8/SSE framing feeds the strict delta decoder and accumulator. Both a compatible finish marker and [DONE] are required. EOF without [DONE], malformed protocol data, truncated completion, or invalid tool arguments cannot yield executable output. [DONE] stops consumption without waiting for connection EOF. Usage frames after finish and before [DONE] remain available.

The default success-body budget is 8 MiB and the error-body prefix is 8 KiB, both configurable. The success budget counts received byte chunks before decoding, including any suffix already received in the same chunk as [DONE]; it is not a bound on the injected HTTP driver's allocation or total assembled JVM memory. Error prefixes may end inside a UTF-8 character and use replacement decoding. Request-size bounds, per-frame limits, deadlines, broader retry strategies, TLS/proxy/pool configuration, and provider extensions are separate host or planned concerns.

Transport tests cover a real loopback POST/chunked SSE response plus deterministic driver fixtures for request headers/body, split UTF-8, parallel calls, usage, finish/[DONE] requirements, malformed data, provider/status failures, body limits, and interruption cleanup. The separate client integration suite verifies actual incremental publication, a real HTTP tool cycle, late tool identity, malformed-frame rejection, and cooperative cancellation.

## Bounded HTTP-status retries

CompletionRetryPolicy.disabled preserves a single HTTP attempt. make(maxAttempts, delay = Duration.Zero) returns Either[InvalidConfiguration, CompletionRetryPolicy], requiring at least one total attempt and a finite nonnegative fixed delay. Construction performs no effects. Only actual HTTP rejection statuses 429, 500, 502, 503 and 504 are eligible; this host-selected allowlist is not inferred from a public ModelError.

A private rejection tag is created only after the configured bounded error-body read succeeds. Retry is considered after that attempt's response Scope has fully closed, and only for the exact singleton tagged failure. Cleanup defects, mixed causes, interruption, acquisition/network failures, error-body read failures, successful-status SSE/provider errors, framing/validation failures and callback failures are terminal. Exhaustion exposes the final rejection's original status and bounded payload; private tags do not escape public calls. The internal runtime attempt boundary may retain a restricted marker for a clean exhausted eligible rejection; custom/public ModelError values do not carry that authority. No Retry-After handling, exponential backoff or general network retry is provided. Runtime-owned [model failover](../agent/README.md#scoped-model-failover) has its own independently reviewed scope.

The validated request body and headers are prepared once, then each attempt acquires a fresh response Scope. The delay begins after cleanup and remains interruptible. These attempts share one logical model invocation and ModelStreamContext; they do not rerun model-input projection, create another history turn or publish rejected-response chunks. There is no retry after successful-status content has begun, so provisional model output cannot be replayed by this policy. The attempt budget counts this library's Client.streaming calls; middleware, redirects or retries inside the borrowed host client are outside that budget, and no provider billing/idempotency guarantee is implied. The host owns the injected client/pool and direct-call deadline; ReAct's outer timeout still bounds its invocation.

Migration: ChatCompletionsClient.make and ChatCompletionsTransport.make append a defaulted retryPolicy parameter. Ordinary source calls retain disabled behavior; method values may need adaptation and dependent binaries must be rebuilt. No LlmConfig or message JSON field is added. This policy is distinct from ReAct thinking-only retries and native structured-output fallback.

| File | Reviewed Git blob |
| --- | --- |
| [CompletionRetryPolicy.scala](CompletionRetryPolicy.scala) | 162be32d7454f3a7e2017a334dd07a715a11347b |
| ChatCompletionsTransport.scala | Current identity: [model failover](../agent/README.md#scoped-model-failover). |
| ChatCompletionsClient.scala | Current identity: [model failover](../agent/README.md#scoped-model-failover). |
| [CompletionRetryPolicySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/CompletionRetryPolicySpec.scala) | 5df81123863d6f3faa10cfd1c70206f2604baf63 |
| [ChatCompletionsTransportSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsTransportSpec.scala) | cb042aee28d6e082c9688481f4b08c0e1540df1d |
| [ChatCompletionsClientSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsClientSpec.scala) | fad2db089f26f5509e27f4ad6bc88d9834b6d59d |

- [x] path_review approved FP/ZIO correctness and current_docs approved invariants for the six identities above, with no open findings. Full scalafmtAll scalafmtSbt verify passed in http-retry-verify.log with zero compiler warnings or failed tests. The focused http-retry-green.log run passed 26 tests in 5.408 seconds, including policy validation, two attempt-lifecycle/status groups and the actual HTTP 429/400 client table. Current aggregate totals belong to the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).

The draft retry boundary at transport 1b7ad93c4ab006fdb50fd07850181549c84c9d0c lost pending interruption after held failing cleanup: http-retry-focused.log recorded 25 passing tests and one failure in 5.561 seconds because exit.isInterrupted was false, although the original HTTP rejection and cleanup defect remained. The unchanged TransportSpec cb042aee28d6e082c9688481f4b08c0e1540df1d passes after restoring caller interruption status while retaining the cleanup outcome. This correction does not infer retry authority from public error values.

## Live client and event correlation

ChatCompletionsClient.make accepts the injected HTTP client and transport options, producing LlmClient. ReAct assigns a fresh ModelStreamContext(replyId, requestId) for every model invocation. replyId belongs to the enclosing agent attempt; requestId changes on each new runtime model invocation; HTTP-status attempts within that invocation retain it. The streamTurn signature now requires this argument: custom implementations and direct callers must migrate. Scripted callbacks keep their existing factory signatures, but hand-written events are not automatically enriched.

The runtime emits ModelRequestStart before invocation and ModelResponseEnd only after a non-interrupted output and request-scope cleanup. The latter carries output/total token counts from the reply; request promptTokens remains zero because no estimate is available. Failed or interrupted requests have no successful response-end event. AgentEnd still closes the surrounding attempt. The live client publishes content events inside these model boundaries; direct streamTurn callers own their model-boundary publication.

Each valid SSE JSON frame produces one ModelResponseChunk, then ordered text/thinking/function events. The entire frame is decoded and accepted by the accumulator before publication. Earlier valid events remain observable if a later frame fails. Deltas are provisional input, not completed conversation state or authorization to execute a tool.

Text and thinking retain separate index namespaces and one start per index. Tool events use streamIndex as blockIndex; identity may arrive late, so arguments are buffered until both call ID and name exist, then emitted as one buffered delta. Subsequent fragments preserve their received order. Only a validated finish plus [DONE] closes blocks, in start order, and exposes the assembled turn. Failure/cancellation may leave observed starts without ends; consumers must use runtime termination/error rather than invent completion. Thinking and selected tool signatures remain in the assembled Msg; these text-oriented events do not carry signature fragments.

Live deltas precede ModelResponseEnd. Open text/thinking blocks remain open until validated completion, allowing interleaving without premature endings. This deliberately differs from disabled upstream ordering sketches that place model-end before buffered content and close thinking before text starts. Local completed tool observations now carry the same request context and are persisted before delta/end publication. Full ordering parity and richer provider content remain pending.

## Request-scoped text transformation

ChatCompletionsClient.make accepts an optional ModelTextTransform, defaulting to identity. transform receives ModelStreamContext and the indexed TextDelta and returns ZIO[Scope, AgentError.TextTransformationError, String]. andThen composes replacements in order with the same identity/index. The callback is suspended; resources and scoped children are owned by the HTTP request and close on success, failure, or cancellation. A host that needs cross-fragment state must supply explicit ZIO state with appropriate ownership; this API does not infer state lifetime.

The whole provider frame is decoded and checked against stream progress before any transformation. Only visible text changes: indexes, thinking, tool arguments, usage, and finish markers remain authoritative protocol data. Replacements feed the accumulator and typed text events together, so final text, TextBlockEnd, history, and structured extraction agree. ModelResponseChunk remains the original raw frame for diagnostics; it is not a redacted or transformed payload. Empty replacements remain valid empty deltas. Stateful transformations run sequentially; their external effects cannot be rolled back if a later callback fails.

The callback error channel accepts only TextTransformationError. Host operations must map external errors explicitly; provider ModelError cannot enter this interface and accidentally trigger Auto native-format fallback. A failed transformation publishes neither that frame nor its typed deltas. Earlier accepted events remain visible. TextTransformationError and interruption propagate through existing runtime handling, with no false model/content completion. Valid finish and [DONE] are still mandatory, and transformed native JSON still passes strict structured decoding. HTTP response-byte limits bound incoming bytes, not expansion performed by a trusted host transform.

This is a typed live-client text transformation point, not the upstream event-rewriting middleware API. It cannot rewrite requests, tools, reasoning, usage, or lifecycle events. Scripted/custom LlmClient implementations do not inherit it. General model-call middleware remains pending. Post-reasoning replacement/removal is a separate ReAct policy boundary documented in the [agent contracts](../agent/README.md).

## Current acceptance

- [x] Previously accepted request serialization, scoped transport, live client/events, and completed tool-result contracts are recorded at `38c48db`, `60b3799`, `84e4eb3`, and `9c165a7`. Unchanged contracts retain that acceptance; Git preserves earlier exact source identities.
- [x] `review_fp` and `review_harness` approved the shared JSON/structured HTTP increment against `9c165a7` on 2026-09-29 after correcting wrapper validation. The [JSON package acceptance table](../json/README.md) records the unchanged JSON-boundary identities from that scope; current capability identities are listed below. No findings remain within this scope.
- [x] The unchanged scopes retain their recorded verification; current changed client identities and verification are in [encrypted reasoning](#encrypted-reasoning), with repository totals in the tracker.
- [ ] Complete other provider signatures/tool metadata, inline server response decoding, other reasoning-detail variants, additional input-size bounds, and exhaustive upstream mapping.

The validator moved from client.ProtocolJson to core.json.JsonBoundary as an internal implementation change. This keeps model and agent parsing independent of HTTP/client layering. decode validates exactly one textual JSON document; validate preserves an existing AST after rejecting duplicate fields. Public Msg structured extraction now rejects trailing input and ambiguous fields instead of accepting a prefix or one duplicate value.

## Capability increment identities

Final formatted source identities against `06bd2e1`:

| File | Git blob |
| --- | --- |
| LlmClient.scala | Current identity: [model failover](../agent/README.md#scoped-model-failover). |
| [StructuredOutputCapability.scala](StructuredOutputCapability.scala) | 5b68a9b3d6c89651b05b46d42b61382987eb90ee |
| StructuredOutputCapabilityRoutingSpec.scala | Current identity: [model failover](../agent/README.md#scoped-model-failover). |
| [StructuredOutputCapabilitySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/StructuredOutputCapabilitySpec.scala) | bebc0adc73b60d08e784f4dcfbdcfa46bea91ddc |

- [x] `review_fp` and `review_harness` approved the final five-file capability increment against `06bd2e1` on 2026-09-29 after the preset-format correction. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 420 core and 40 harness tests.

- [x] `review_fp` and `review_harness` approved the final seven-file text-transformation increment against `02b4654` on 2026-09-29 after the fallback-provenance correction. No findings remain within this scope. Full formatting, fatal-warning compilation, and verification passed 434 core and 40 harness tests.

## Text transformation increment identities

Unchanged text-transformation contracts retain acceptance against `02b4654`; current changed client/transport identities are in [encrypted reasoning](#encrypted-reasoning). The current real HTTP fixture clock revision is independently approved by `path_review`; its scope and verification are in the [test contract](../../../../../../../test/scala/com/weisgnosis/agents/core/client/README.md#real-http-fixture-clocks).

| File | Git blob |
| --- | --- |
| [ModelTextTransform.scala](ModelTextTransform.scala) | b07cec74ae6bd35945b8558c2f9bb067df7a2555 |
| [ModelTextTransformSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ModelTextTransformSpec.scala) | dcdd1fef54fb9dd166171ce5b21682a9019a4fe3 |
| [ChatCompletionsTransformSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsTransformSpec.scala) | 396f6d75c01bcaac54750a0d5df97c219a4b8c31 |

The ReActAgent capability identity is preserved in `02b4654`, and the AgentError text-transformation identity in `2a400e0`. Their subsequent post-reasoning changes have current identities in the [agent acceptance](../agent/README.md).

The changed AgentErrorSpec.scala identity from the earlier table is preserved in `d1a4cf2`; current local-memory error acceptance is in the [memory contract](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md).

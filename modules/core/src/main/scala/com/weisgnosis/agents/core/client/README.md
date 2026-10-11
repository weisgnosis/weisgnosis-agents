# Model client port and stream assembly

## Streaming assembly and transport diagnostics

OpenAI-compatible tool argument deltas use immutable fragments, joined for completed or interrupted observations. Streaming keeps the latest validated accumulator without reparsing its argument prefix after every frame. Completed output is retained before owned HTTP cleanup; cancellation materializes an incomplete snapshot with invalid argument JSON replaced by `{}`. Unchanged text transformations reuse raw delta validation. Provider terminal framing, exact argument validation and joined cleanup remain mandatory.

Transport acquisition failures expose `HTTP request failed (category)`, with `dns`, `tls`, `connection`, `timeout` or `other`. External body-read failures use the same category suffix. Classification inspects at most eight exception-cause nodes by type; it never includes exception messages, endpoint details or credentials. Existing typed SSE/JSON body errors remain typed; HTTP rejection-body failures retain their sanitized boundary. `connection` identifies a connection exception, not proof of connection refusal. The `ModelError` data shape and cancellation authority are unchanged; hosts matching exact old generic messages must update those matches.

The [thinking-event contract](../event/README.md#incremental-and-accumulated-thinking) preserves exact fragments and optional per-index accumulated plaintext prefixes; opaque reasoning stays outside plaintext. Typed request settings preserve provider preferences without granting runtime authority.

LlmClient.streamTurn requires Scope and an explicit ModelStreamContext, and returns TurnOutput. Implementations must acquire request resources in that scope and return assistant messages with tags consistent with calls, visible text and reasoning. The runtime [trust boundary](../agent/README.md#model-turn-trust-boundary) validates returned turns after cleanup; invalid output is a fatal ModelError, not retry/fallback input. The unpublished [audited public-call example](../../../../../../../../../../modules/examples/README.md#audited-public-model-call) illustrates post-request-cleanup observation with a terminating finalizer; public decoration does not preserve private capability-based failover proof. The package provides scripted clients, LlmConfig, SSE framing, strict completion-delta decoding, reasoning-history adaptation, and StreamAccumulator. ChatCompletionsTransport provides a separate scoped HTTP completion operation. ChatCompletionsClient connects it to ReAct with incremental correlated events.

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
- [StopSequences](StopSequences.scala)
- [SseParser](SseParser.scala)
- [StreamAccumulator](StreamAccumulator.scala)
- [ThinkingBudget](ThinkingBudget.scala)
- [TurnOutput](TurnOutput.scala)

## Ordinary JSON completions

ChatCompletionsClient.makeNonStreaming and ChatCompletionsTransport.makeNonStreaming explicitly select ordinary JSON HTTP completion. ChatCompletionsRequest.encodeNonStreaming is the matching pure request encoder: it emits stream=false and omits streaming-only stream_options. Requests advertise Accept: application/json. Existing make and encode signatures and defaults remain unchanged and continue selecting SSE. Selection is host-owned; there is no content-type sniffing, provider inference or fallback between response modes.

A successful response requires exactly one Content-Type header whose media type is application/json, with optional parameters. The transport consumes the bounded UTF-8 body to EOF before decoding one complete JSON document; responseBytes applies to raw body bytes. It rejects trailing content, duplicate keys, malformed full messages, incomplete tool arguments and incompatible finish reasons. No SSE framing or [DONE] marker applies to this mode. EOF alone does not authorize success: the full-message decoder and accumulator must validate completion.

Choice zero must contain an assistant message with an explicit compatible finish reason and no delta field. Existing text/null content, selected scalar/detailed reasoning, complete function calls, usage and explicitly configured signatures retain their checked decoding rules. Array order supplies tool indices. Full-envelope validation completes before text transforms or diagnostic/semantic event publication. A successful JSON response produces one validated raw frame plus correlated semantic events under the existing ModelStreamContext contract; these events are not incremental network delivery. ReAct retains ordinary tool execution and structured-output authority.

Each attempt owns its response Scope and joins success/failure/cancellation cleanup before returning. Existing bounded HTTP-status retry and pre-response failover rules remain unchanged; malformed successful responses do not gain retry authority. Native/synthetic structured modes and settings projection reuse the selected client mode without modifying canonical history ownership.

Migration: the three new public helpers are additive; prior public factory/encoder signatures and defaults are unchanged. CompletionResponseMode and the full-message decoder remain package-private. No public Boolean execution switch, new configuration field or constructor/Product migration is introduced by this increment.

## Provider thinking metadata

`LlmChunk.ThinkingDelta` and `ReasoningDetailsDelta` accept trailing `providerMetadata: Map[String, Json] = Map.empty`. Explicit provider adapters may supply this immutable data; the current HTTP decoder does not invent or automatically collect arbitrary provider fields.

The accumulator merges metadata independently per scalar thinking index, or within the ordered-details channel. Two arrays concatenate; every other incoming value replaces the previous value for its key. Prior snapshots remain unchanged. Canonical Thinking blocks retain the map inside the explicit `providerMetadata` object namespace, separately from checked `reasoningDetails` and `thoughtSignature` fields.

Raw reserved keys `reasoningDetails`, `reasoning_details`, `thoughtSignature`, `signature` and `providerMetadata` fail validation. Duplicate nested keys and invalid Unicode also fail. Metadata alone is retained without becoming visible reasoning, triggering a thinking-only retry or fabricating plaintext. An empty scalar ThinkingDelta still follows ordinary stream-event publication; metadata retention does not suppress those empty events. Interrupted accumulation preserves accepted metadata alongside partial history.

Request encoding validates this known namespace then omits it from provider wire output. Existing signature/details profile requirements and rejection of other unknown canonical metadata remain unchanged; retaining data is not permission to replay it. Existing accumulator/detail suites and the actual ReAct tool-turn table verify merge, snapshot, interruption and next-request behavior.

Migration: ThinkingDelta product/extractor arity changes from three to four; ReasoningDetailsDelta changes from one to two. Ordinary constructor calls retain trailing defaults, and old JSON without providerMetadata uses empty data. Update positional patterns and rebuild dependent binaries.

## Scalar reasoning aliases

The decoder recognizes string or JSON-null reasoning_content, reasoning and thinking. In scalar profiles, absent/null fields contribute no value, identical supplied strings emit one ThinkingDelta before visible text, and different supplied strings fail with Conflicting scalar reasoning aliases. Empty strings emit no thinking; an empty string conflicting with a nonempty string is rejected. Exact text is preserved without trimming. Malformed recognized values fail even when another alias or detailed reasoning would otherwise take precedence.

OpenRouterDetails keeps reasoning_details authoritative: valid scalar aliases are ignored without duplication. Any nonempty scalar alias requires an explicit details array; an explicitly empty array is still authoritative. This rule applies to ordinary deltas and full-message SSE envelopes through the same decoder. No provider identity is inferred, and outbound replay still uses its existing configured wire representation.

Migration: public signatures/configuration are unchanged. Input validation intentionally tightens the former reasoning_content-over-thinking precedence: conflicting scalar strings now fail instead of silently losing one value. Providers emitting multiple scalar fields must make their values equal, omit redundant fields, or use the explicitly supported authoritative details profile. This extension was previously outside the selected alias scope; the pre-change failure run is new-contract evidence rather than a claim that an accepted contract was defective.

## Completion envelope profiles

CompletionEnvelopeProfile.IndexedDeltas remains the default. Hosts may explicitly choose DeltasWithFullMessageSummary on ChatCompletionsClient.make or ChatCompletionsTransport.make for SSE streams containing a final object=chat.completion envelope. Selection is independent of model names and does not change request JSON or LlmConfig. Ordinary JSON HTTP responses use the separate explicit [non-streaming factories](#ordinary-json-completions).

A full envelope requires exactly choice zero, an assistant message, an explicit compatible finish reason and no simultaneous delta field. String/null content and existing selected reasoning profiles are supported. Complete function calls require nonblank IDs/names, explicit string JSON-object arguments and type=function. Array order supplies stream indices; supplied indices must match. All known fields, usage and complete tool arguments are validated before either materialization or suppression.

Before any delta, one full message supplies semantic output. After any accepted delta, including a role-only delta, full-summary text, reasoning and tool arguments are suppressed; only validated usage and a compatible finish are retained. Usage-only frames do not establish a delta phase. An existing identical finish is not repeated; a conflicting finish fails. After a full summary, usage-only frames remain valid and subsequent choices fail. Suppressed summary values never reach text transforms or semantic delta events, never replace canonical output and cannot repair incomplete streamed calls. The actual accumulator and a blank-line-terminated [DONE] still authorize completion. Raw diagnostic frames retain the original data.

Migration: the appended defaulted envelopeProfile parameter changes ChatCompletionsTransport.make from five to six parameters and ChatCompletionsClient.make from six to seven. Ordinary omitted-argument source calls retain their behavior; explicit method values may require adaptation and dependent binaries must be rebuilt. The enum has a derived domain JSON codec; its names are host configuration values, not provider wire fields. The request-local decoder remains package-private and does not expose a host extension contract.

## SSE frame completion

The selected core SSE byte profile accepts LF and CRLF line separators, including separators fragmented across chunks. A data frame is dispatched only after an explicit blank line. EOF discards pending frame data and preserves already dispatched frames; an unterminated [DONE] cannot complete a model turn. The transport still requires a valid finish reason and complete indexed tool arguments before returning executable output. This follows the [event-stream EOF rule](https://html.spec.whatwg.org/multipage/server-sent-events.html#event-stream-interpretation). Standalone CR separators and complete browser EventSource semantics are outside this profile; MCP uses its separately reviewed framing implementation.

This corrects parser behavior without changing public signatures or configuration. Providers must terminate each data frame, including [DONE], with a blank line. No EOF-as-success fallback is added.

## Runtime event ordering

The held stream and real HTTP tool cycle in ChatCompletionsClientSpec assert the complete lifecycle projection, excluding only raw ModelResponseChunkEvent frames. Incremental content/tool-call Start and Delta events precede ModelResponseEnd; block End events follow validated completion. Thinking and text are independently indexed streams: the tested thinking-then-text input begins thinking first, but text may start before ThinkingEnd. A completed local result lifecycle occurs between the tool-call model response and the next model request. AgentResult precedes the final AgentEnd. Hosts may explicitly apply the [final-answer presentation filter](../event/README.md#optional-final-answer-filter) to delay/suppress correlated intermediate text; raw diagnostics, final results and canonical history remain unchanged.

ReActAgentEventSpec checks AgentStart first and the matching AgentEnd last for fully drained success, buffered events, failure, self/cooperative interruption and timeout. Early consumer termination still joins cleanup while abandoning subsequent output. Existing partial-confirmation tests establish correlated Ask after batch drain and before RequestStop, then Decision before result events in separate restored invocations; these generic events do not prove a human response. The [parity mapping](../../../../../../../../../../docs/architecture/test-parity.md#agenteventstreamtest-seven-disabled-ordering-declarations) records intentional differences from disabled upstream comments. Model DataBlock emission remains unspecified and unimplemented; tool-result data events are a separate supported path.

## Cache usage decoding

Compatible streamed usage preserves prompt_cache_hit_tokens and prompt_tokens_details.cache_write_tokens in the existing ChatUsage.cachedTokens and cacheCreationTokens fields. A supplied direct cache-hit count takes precedence over nested cached_tokens, including explicit zero. Missing or JSON-null direct counts fall back to the nested count, then zero. Missing/null cache-write counts become zero. All supplied recognized counts are validated as nonnegative JSON integers within Int bounds before precedence is applied; a malformed overridden fallback is still rejected.

This is a private decoder correction without public source/JVM or configuration migration. It changes previously lost usage observations, not token billing, model generation, permissions or lifecycle ownership. The parser table and existing real HTTP retry scenario verify decoded counters and canonical history. Ordinary full-response parsing has separate [accepted HTTP support](#ordinary-json-completions); other provider formats remain separate work.

Per-tool provider strictness has a separate [definition contract and migration](../tool/README.md#provider-tool-schema-strictness); it is not a model-request projection setting.

## Optional thinking budget

ThinkingBudget.fromInt returns a checked positive opaque value; zero, negative/sentinel values, fractional/out-of-range JSON and nonnumeric values are rejected. LlmConfig and ModelRequestSettings append optional thinkingBudget, default None. The shared request encoder emits exact top-level thinking_budget only when present, for an explicitly compatible host-selected endpoint. It supplies no local token limit, model detection or automatic compatibility policy. Ordinary JSON/SSE and native/synthetic structured requests share that encoding.

Each selected target initializes projection from its own configuration. Projected Some overrides that target preference and None clears it; neither changes model/client/tool authority. Provider preference is distinct from runtime completion/iteration limits. Pinned OpenAIChatFormatterTest.testApplyThinkingBudget (444), testApplyThinkingBudgetFromDefault (457), testApplyThinkingBudgetOverride (470) and testThinkingBudgetAbsent (484) have bounded explicit configuration/default/projection/omission adaptations; mutable Java default merging, arbitrary integers and extra-body overrides are excluded.

Migration: current LlmConfig has sixteen positional/Product fields and ModelRequestSettings has nine. Appended defaulted options preserve ordinary omitted-argument source calls and legacy absent/null configuration JSON, while positional extractors, constructor method values and JVM signatures require assessment and dependent binaries must be rebuilt. This does not freeze a release ABI.

## Optional reasoning effort

The supported ReasoningEffort.Low, Medium and High subset expresses explicit provider generation preferences; it is not a universal provider effort vocabulary. LlmConfig.reasoningEffort defaults to None; missing/JSON-null configuration keeps None and the provider field is omitted. Supplied values emit reasoning_effort as low, medium or high. Domain configuration uses the enum codec rather than lowercase provider strings; malformed enum values fail decoding. This bounded vocabulary follows the pinned upstream setting, without inferred model capabilities, provider-specific thinking budgets or a Java mutable-options merge.

Each primary/fallback target supplies its own preference to ModelRequestSettings.reasoningEffort. Scoped request projection may replace it or clear it with None; legacy history transforms retain it, retries reuse the serialized body, and native/synthetic formatting preserves the projected value. The runtime does not infer completion, permissions or reasoning replay policy from this preference. Endpoint support remains the host's responsibility.

Migration: current arities are sixteen LlmConfig fields and nine ModelRequestSettings fields under the [thinking-budget migration](#optional-thinking-budget). Appended defaulted options preserve ordinary omitted-argument construction and legacy JSON; positional patterns/constructor method values may need adaptation and dependent binaries must be rebuilt.

## Optional stop sequences

StopSequences is an opaque immutable nonempty Chunk[String]. one(first, rest*) constructs a valid value; fromChunk returns a typed InvalidConfiguration failure for an empty collection. Its checked JSON codec uses an array and rejects empty arrays, scalar strings and nonstring entries. It preserves exact strings, whitespace, empty strings, order and duplicates; no provider-specific count limit or automatic normalization is imposed. Endpoint support remains the host's responsibility.

LlmConfig.stopSequences defaults to None; absent/JSON-null configuration retains None, which omits provider stop. Some emits an array, including a singleton. Each selected primary/fallback target supplies its own value to ModelRequestSettings.stopSequences. Request projection can replace it or clear it with None; legacy history transforms retain it. HTTP retries reuse the encoded body and native/synthetic formatting retain projected settings. These are provider generation settings: the runtime does not trim output locally, bypass tool checks or infer successful completion from matching text.

Migration: current LlmConfig and ModelRequestSettings arities follow the [reasoning-effort migration](#optional-reasoning-effort). The appended defaults preserve ordinary omitted-argument construction and legacy configuration JSON. Adapt positional patterns/constructor method values and rebuild dependent binaries. Custom LlmClient implementations receive the typed settings and decide whether their provider supports them.

## Optional parallel tool-call steering

LlmConfig.parallelToolCalls is Option[ToolCallParallelism], defaulting to None. ToolCallParallelism.Enabled and Disabled encode as provider parallel_tool_calls true and false respectively; None omits the field. Explicit preferences remain present when no tools are advertised. Domain configuration uses the typed camelCase field; absent or JSON-null values decode as None, and malformed enum values fail decoding.

This is a model generation preference. It does not change AgentConfig.toolExecution, admitted tools, authorization or call dispatch: a model may return multiple calls despite Disabled, and the runtime still applies its normal policies to every call. Each primary/fallback target retains its own configured preference through request settings projection; ModelRequestSettings does not expose this capability. Native/synthetic formatting and retry body reuse preserve the configured value.

Migration: current LlmConfig arity follows the [stop-sequence migration](#optional-stop-sequences). Existing omitted-argument construction and legacy JSON retain defaults; positional patterns and constructor method values may need adaptation, and dependent binaries must be rebuilt. Domain enum JSON is distinct from the provider Boolean wire field.

## Optional seed and exact request rendering

LlmConfig.seed and ModelRequestSettings.seed are Option[Long], defaulting to None. Some values, including zero and both signed 64-bit endpoints, encode as exact JSON integers under seed; None omits the field. Configuration JSON rejects fractional and out-of-Long-range seeds, while absent/null values retain None. No narrowing to Java's formatter integer range or provider reproducibility guarantee is implied.

Each selected target supplies its own seed to request projection. The projected value replaces it, with None explicitly clearing it. Retries reuse the serialized body, and native/synthetic requests retain their projected settings. Seed changes no model identity, endpoint, tools, permissions or response format. Migration: current LlmConfig and ModelRequestSettings arities follow the [stop-sequence migration](#optional-stop-sequences); defaulted appended fields preserve ordinary omitted-argument construction and legacy configuration JSON. Adapt positional patterns/method values and rebuild dependent binaries.

The HTTP request body and historical tool-argument strings use the pure [exact JSON renderer](../json/README.md). Checked AST validation still precedes rendering. This avoids the dependency's decimal corruption at a minimum-Long unscaled coefficient, including nested values and nonzero scales. It is limited to explicitly integrated output boundaries: structured closing text and local transcript output have their own [reviewed integration](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/transcript/README.md#exact-owned-numeric-output). [Exact domain/tool output](../json/README.md#exact-domain-and-tool-output) covers compactor provenance/previews and checked factory execution; hosts can opt into ExactJson.encode. External dependency serialization and legacy direct tool encoding remain outside that guarantee.

## Optional frequency and presence penalties

LlmConfig and ModelRequestSettings carry optional frequencyPenalty and presencePenalty, both defaulting to None. ChatCompletions emits frequency_penalty and presence_penalty only for supplied values, including explicit zero. Each value must be finite and in the inclusive range [-2, 2]; validation rejects invalid values without clamping or changing the selected endpoint. LlmConfig JSON uses camelCase fields; missing or explicit-null values decode as None. The derived configuration codec represents data, while request encoding and runtime projection enforce these bounds.

Each primary/fallback target supplies its own initial settings to the scoped projection. Projection replaces the optional values, so None clears a configured penalty. Validation occurs after projection cleanup and before request events or custom-client dispatch, including invalid original settings through legacy transforms. Structured native/synthetic requests retain the projected values; transport retries reuse the encoded request. Tools, choice, response format and client authority remain unchanged. No penalty fields are added to subagent declaration YAML.

Migration: Current LlmConfig and ModelRequestSettings arities are recorded in the [seed migration](#optional-seed-and-exact-request-rendering). Appended defaulted fields preserve ordinary omitted-argument construction and legacy configuration JSON. Positional patterns and constructor method values may need adaptation; rebuild dependent binaries. Custom clients remain responsible for interpreting these model settings.

## Host-selected tool choice

LlmConfig.toolChoice defaults to Option.None, which omits tool_choice. Explicit ToolChoice.Auto, None and Required encode as auto, none and required; Specific(name) encodes the function-selection object. Auto and None are omitted when no tools are advertised. Required needs a nonempty visible definition set; Specific requires a syntactically valid exact name in that set. Validation rejects hidden, missing or invalid targets before HTTP acquisition. Definitions remain unchanged. The absent default intentionally differs from Java's null-to-auto helper behavior.

This is provider steering, not permission or completion authority. A provider may still return a call despite none; runtime visibility, ordinary authorization and structured capture remain authoritative. Request projection cannot change the choice. Each selected primary/fallback target retains its own configured choice; retries reuse the same encoded body, and native/synthetic requests retain the choice without overriding it to force capture. The host must select hints compatible with its structured-output mode; hints that prevent capture remain subject to the existing iteration and timeout bounds. Provider compliance is not guaranteed. Custom LlmClient implementations interpret this configuration themselves; the validation/encoding contract belongs to ChatCompletions.

Migration: LlmConfig has a defaulted toolChoice; its current positional/Product shape is recorded in the [settings migration](#optional-frequency-and-presence-penalties). Existing omitted-argument construction and old missing/JSON-null toolChoice decode as None; explicit enum cases round-trip through the domain codec. Constructor method values may need adaptation and dependent binaries must be rebuilt. Domain toolChoice JSON is distinct from provider tool_choice encoding.

## Optional nucleus sampling

LlmConfig.topP is optional, defaulting to None. ChatCompletions emits top_p only for Some(value), requiring a finite value in the inclusive range [0, 1]; None omits the field. There is no provider-specific clamping, inferred default or endpoint/capability change. Domain JSON uses topP; old missing or explicit-null values decode as None.

ModelRequestSettings carries the selected target's topP into the scoped request projection. Its output replaces that target's setting: None explicitly clears it. Validation runs after projection cleanup and before request events or custom-client dispatch; primary and fallback start from their own configurations, and transport retries reuse the encoded request. SubagentDeclaration.topP instead treats None as inheritance and Some as a child override. Its checked YAML profile accepts top_p or topP, rejects simultaneous key presence even when either value is null, and treats omitted/plain-null as inheritance; quoted numeric scalars parse, while quoted null and malformed/nonfinite/out-of-range values fail. It grants no execution authority.

Migration: the appended defaulted fields preserve ordinary omitted-argument construction and legacy JSON. SubagentDeclaration retains nine positional/Product fields; ModelRequestSettings and LlmConfig follow the [current settings migration](#optional-frequency-and-presence-penalties); constructor method values may need adaptation and dependent binaries must be rebuilt. Declaration validation and request validation remain explicit boundaries rather than validation performed by the derived data codecs.

## Structured output capabilities

LlmConfig.structuredOutputCapability is a host declaration for the configured model/endpoint, not automatic discovery. Unknown is the default for source and JSON compatibility. Native() permits application tools by default; Native(NativeToolSupport.Unsupported) restricts native formatting to requests without application tools. Unsupported cannot declare tool support independently.

| Declaration | Auto without application tools | Auto with application tools |
| --- | --- | --- |
| Unknown | Native with rejection fallback | Synthetic |
| Unsupported | Synthetic | Synthetic |
| Native() | Native with rejection fallback | Native with rejection fallback |
| Native(NativeToolSupport.Unsupported) | Native with rejection fallback | Synthetic |

Explicit Native and SyntheticTool modes override this selection. Native installs the supplied structured schema. Every synthetic attempt clears responseFormat while retaining other configuration, including when falling back from a rejected native attempt. Ordinary runs preserve the host's responseFormat. Auto native attempts fall back only on an exact singleton ModelError failure with status 400, 422, or 501; other errors and cancellation propagate. This status policy is broader than provider-specific capability error classification.

The declaration is serialized with LlmConfig but excluded from the HTTP request body. Existing constructor calls retain their defaults; positional pattern matches and binary rebuilding follow the [current LlmConfig migration](#optional-nucleus-sampling). Unknown preserves prior routing, while declared Unsupported avoids a native probe entirely.

## Completion data boundary

ReasoningModelAdapter.parseChunk now returns Either[AgentError, Chunk[LlmChunk]]. This is a source-breaking replacement for the former silent empty-Chunk fallback. Lift the result with ZIO.fromEither; malformed recognized fields must fail the request rather than disappear. SSE framing and [DONE] handling remain outside this JSON decoder.

Normal frames require an explicit choices array. Exactly one choice with an explicit integer index of zero is supported; alternative choices are rejected instead of combined. Empty choices are permitted with usage. Role-only and empty terminal deltas are valid. Tool fragments require nonnegative integer stream indexes, unique within each frame; optional IDs/names support later fragments without inventing index zero. Duplicate JSON fields are rejected throughout the input tree, including ignored extension objects.

Missing/JSON-null usage counts normalize to zero after the [cache-counter precedence rules](#cache-usage-decoding). Supplied counts and indexes must be nonnegative JSON integers within Int bounds; strings, fractions, overflow, and malformed nested objects fail. Non-null provider error envelopes become ModelError with status 200, representing an error carried inside an otherwise successful streaming response. Missing/empty provider messages receive a deterministic fallback. The transport preserves actual HTTP rejection status separately.

Indexed function tools, text, and reasoning_content/reasoning/thinking strings are supported. Non-assistant roles, nonempty refusal deltas, unsupported tool kinds, and non-null legacy function_call payloads fail explicitly. Scalar profiles follow the [equal-value coalescing and conflict contract](#scalar-reasoning-aliases). Unknown extension fields remain ignored unless they contain duplicate keys; this does not establish support for response audio or other provider payloads. The explicit OpenRouterDetails profile below instead derives plaintext exclusively from its checked detail array; it preserves exact checked objects for replay.

The wire baseline uses [streamed function-call deltas](https://developers.openai.com/api/docs/guides/function-calling). Single-choice limits and strict validation are runtime policy. The accumulator enforces the completion contracts below. Indexed text/thinking/function block publication and agent HTTP integration are implemented below. The explicit Google tool-signature contract is described below; other signature formats remain separate.

## Indexed assembly and completion

StreamAccumulator is now privately constructed through empty. feed returns Either[AgentError, StreamAccumulator], and toTurnOutput returns Either[AgentError, TurnOutput]; replace former fluent calls with flatMap. The former public case-class construction/copy and intermediate fields are removed. Normal completion requires stop with no tools or tool_calls with tools. Missing, repeated, filtered, or inconsistent completion markers cannot yield executable success. Explicit token exhaustion yields `TurnOutput.Truncated`, never executable success. Usage snapshots may follow a finish marker; other later deltas fail.

Tool fragments remain keyed and emitted in numeric streamIndex order. IDs/names may arrive after argument fragments but must be nonblank, stable, present at completion, and IDs must be unique across completed calls. Arguments must be a complete JSON object with unique keys. Valid assembled raw text is retained in ToolUse.content exactly. Normal malformed/non-object/trailing input fails; toInterrupted instead sanitizes both input and content to {} and uses a deterministic placeholder only when the call ID has not arrived. This produces TurnOutput.Interrupted for reconciliation, never a completed executable turn.

Text and thinking use separate index namespaces and retain first-appearance block order. Thinking signatures concatenate exactly, including signature-only blocks and whitespace; this covers ThinkingDelta assembly, not provider thinking-signature decoding. Tool blocks follow these content blocks in numeric order. Usage is the latest complete snapshot, never a sum of streaming snapshots.

JsonBoundary is a library-internal shared boundary in the independent json package. zio-json's ordinary decoder permits unconsumed suffixes, so a pure tail-recursive boundary scanner checks the full document, JSON whitespace, container/string escapes, and complete primitive tokens. AST parsing validates grammar; a second tail-recursive traversal rejects duplicate fields. This prevents response frames, tool arguments, and structured text from hiding trailing garbage. Existing request/metadata/captured ASTs use direct duplicate-field validation without a serialization round trip.

## Request serialization

ChatCompletionsRequest.encode returns Either[AgentError, Json.Obj] without effects. It serializes ordered text and user image/audio messages, including explicitly typed inline Data blocks, function definitions with optional host-selected strictness, canonical tool argument ASTs, streaming usage options, and an optional native response_format object. Credentials, endpoint, runtime identity, timestamps, usage, and unrelated message metadata are not request-body fields. ToolDef.returnMode is local runtime policy and is omitted from provider definitions. ToolDef.outputSchema remains descriptive metadata for custom clients; this encoder emits no output_schema extension. Its omission is covered by the [output-schema contract](../tool/README.md#descriptive-output-schemas).

History must contain complete local call/result pairs. Orphan, duplicate, unknown, and unresolved results fail before serialization; another conversation message cannot split pending calls from their observations. Grouped or separately delivered parallel results may arrive in any order, and completed later turns may reuse an ID. A grouped Tool message becomes individual protocol messages. Error, Interrupted, and Denied outputs carry explicit labels; Running and suspended observations fail.

ReasoningReplay.Omit is the default. LatestAssistant replays unsigned thinking only from the last assistant message through reasoning_content. OpenRouterEncryptedDetails validates and replays encrypted detail arrays from every historical assistant message. OpenRouterDetails additionally supports checked text and summary objects; other detailed/signed thinking formats remain unsupported. Unsupported media, provider-hosted server calls/results, calls signed with an unsupported format, and non-text tool output under the default TextOnly profile fail explicitly. User-media projection is defined below; broader provider formatting remains separate.

Validation covers nonblank models, finite temperature in [0, 2], positive optional token limits, protocol names, unique definitions/call IDs, object schemas/arguments/response formats, and duplicate JSON fields. ToolUse.input is authoritative; stale raw content is not sent. Forwarding a schema object does not validate arbitrary JSON Schema semantics. Endpoint, authentication headers, HTTP status handling, and request resource lifetime belong to ChatCompletionsTransport.

## Tool-image result projection

ToolResultFormat.TextOnly remains the default. Explicit ImageParts projects successful mixed Text/Image results into ordered content parts on the original tool role and tool_call_id; it does not inject a user message or synthesize an ID. Images reuse the existing checked URL/base64 image serializer. Unsupported output rejects the complete request: non-success non-text results, audio, video, Data, nested tool blocks, provider-hosted or suspended results are not projected. Existing text-only result state labels and Running rejection remain unchanged in both profiles.

This is an extension for an explicitly compatible host-selected endpoint; the standard tool-message schema does not guarantee image-part support. Serialization fetches no media, infers no MIME type and grants no broader provider compatibility. User/assistant media rules retain their separate contracts.

Migration: pure encode/encodeNonStreaming and both ChatCompletionsClient/ChatCompletionsTransport construction modes append defaulted toolResultFormat. Omitted-argument source calls retain TextOnly, but JVM method signatures and method values require assessment and dependent binaries must be rebuilt. LlmConfig and ModelRequestSettings retain sixteen/nine fields; the selector is not a generation setting.

Two new grouped regressions cover pure request projection/rejection and both scripted HTTP client response modes, including the complete assistant-call/tool-result message array. Pinned OpenAIMessageConverterTest.testToolMessageWithMultimodalResult (887) supplies bounded projection intent; testToolMessageWithoutResultBlock (916) fallback/invented-ID behavior remains excluded.

## User-image projection

User messages containing only text retain their original string wire shape. A message containing supported media becomes an ordered array of text, image_url, input_audio and supported video_url parts. HTTP(S) image URLs require a host and valid port, reject raw whitespace/control characters, and retain their original spelling. Inline images require an explicit image/png, image/jpeg, image/webp or image/gif media type and nonempty standard Base64 syntax. Image, audio, video and Data IDs and names are not provider fields. Invalid or unsupported blocks reject the whole request; no block is silently dropped or replaced with a placeholder.

Inline audio accepts only MediaSource.Base64 with exact audio/wav or audio/mpeg MIME values, projecting format wav or mp3 respectively and preserving the original Base64 string. Payloads require the same nonempty standard Base64 syntax as inline images. Audio URLs, MIME aliases, parameters and other formats fail; the codec never guesses a format or substitutes a text reference. The [official input-audio schema](https://raw.githubusercontent.com/openai/openai-python/main/src/openai/types/chat/chat_completion_content_part_input_audio_param.py) specifies input_audio.data and format wav|mp3; the exact MIME mapping is local boundary policy.

User ContentBlock.Data accepts inline Base64 with those same four image MIME types or audio/wav and audio/mpeg. Data URLs require an explicit MediaSource.Url.mimeType of image/png, image/jpeg, image/webp or image/gif and pass the existing HTTP(S) validator. The original URL is retained, including a misleading extension; no MIME type is inferred. The hint, ID and name are omitted from provider JSON. An explicit Image block keeps its existing URL behavior regardless of the descriptive hint. Missing/unsupported Data hints, audio/video Data URLs and malformed URLs reject the whole request. Inline Data continues to use the same image_url/input_audio validators and original block order.

This is pure serialization: it does not fetch URLs, read files, decode media bytes, inspect audio headers/duration, detect animation or validate provider size limits. In particular, accepting image/gif does not establish that the image is non-animated. Image/audio/video/Data content in system and assistant messages remains unsupported; tool images require the explicit [tool-image profile](#tool-image-result-projection) and other non-text tool content remains unsupported; video Data inference and unsupported inline video MIME types are rejected. Default tool content stays text-only; the explicit [tool-image extension](#tool-image-result-projection) is separately selected by the host. The [official user schema](https://raw.githubusercontent.com/openai/openai-python/main/src/openai/types/chat/chat_completion_user_message_param.py), [image-part schema](https://raw.githubusercontent.com/openai/openai-python/main/src/openai/types/chat/chat_completion_content_part_image_param.py), [tool-message schema](https://raw.githubusercontent.com/openai/openai-python/main/src/openai/types/chat/chat_completion_tool_message_param.py) and [image guide](https://developers.openai.com/api/docs/guides/images-vision) define the selected image/audio provider surface. Video URL projection below requires an explicitly compatible endpoint. This does not imply compatibility with every endpoint accepting Chat Completions requests.

Migration: MediaSource.Url appends mimeType: Option[String] = None with canonical mime_type JSON. Missing/null becomes None and None is omitted; invalid or duplicate canonical fields fail. Two-field Url patterns/Product and downstream binaries require updating. Defaulted construction calls remain valid; MediaSource.url now has two parameters, so method-value users must adapt rather than assuming the old function arity. Previously rejected supported user media now serialize; text-only requests and unsupported-role rejection retain their existing behavior. Full provider media validation remains the caller/provider responsibility.

Existing tables verify six inline MIME mappings, four image URL hints on a misleading .mp3 path, extensionless mixed content, ignored descriptive hints on explicit Image blocks, local-field omission and whole-request rejection. The existing MediaSource codec group covers concrete/base round-trips, omitted/null hints and duplicate/nonstring rejection. There are no new test groups. Existing typed image/audio, signature and reasoning behavior remains unchanged.

Audio output, automatic URL retrieval and unlisted media/provider formats are not supported by this selected profile. Live compatibility is established only by explicitly documented provider runs.

## User Video URL projection

An explicit user ContentBlock.Video(MediaSource.Url(...)) projects to a video_url part containing only the original url. Video-only and mixed text/image/video messages preserve block order. The same pure HTTP(S) syntax guard used for image URLs requires a host/valid port and rejects raw whitespace/control characters; original URL spelling, including query/fragment and percent escapes, is retained. Local IDs/names and descriptive MIME hints are omitted. No extension or MIME inference, fetching, media decoding or remote-content validation occurs.

The host must select an endpoint that supports this video_url extension. Supplying the typed Video block expresses request intent, as with existing image/audio input; there is no additional feature flag or universal Chat Completions compatibility claim. The separately scoped [inline video projection](#inline-video-projection) extends explicit user Video input. Data video inference and video in system/assistant/tool roles reject the complete request. Existing text-only shape, image/audio/Data behavior, request signatures and configuration defaults are unchanged. URL failures retain typed InvalidMessage errors through the shared media guard.

The existing positive/negative request tables add the bounded pinned OpenAIVideoSupportTest.testConvertVideoBlockWithUrlSource (154) and testConvertMixedTextAndVideoContent (219) adaptations, including exact wire order/URL preservation and rejected near-misses. No new test group or whole upstream suite mapping is introduced.

Inline video accepts only the MIME types in the profile above; Data blocks do not infer video. Other provider formats and endpoints require separate compatibility evidence.

## Inline video projection

An explicit user ContentBlock.Video(MediaSource.Base64(mediaType, data)) with exact lowercase video/webm or video/mp4 projects to video_url.url="data:<mediaType>;base64,<original data>". Existing standard-Base64 syntax validation requires nonempty, correctly padded input; the encoder preserves the supplied data without decoding it. It does not verify video content, container/codec validity or size limits. Ordered mixed user content and omission of private IDs/names remain unchanged, as does HTTP(S) Video URL validation.

This bounded extension requires a host-selected compatible video_url endpoint. Other inline video MIME types, Video input in system/assistant/tool roles and video Data inference reject the complete request. No fetching, MIME inference, profile selection, provider discovery or universal interoperability is added. Public signatures, defaults, enum cases and configuration arities are unchanged; changed implementation artifacts require renewed verification.

## Tool-call signatures

ToolSignatureFormat.Unsupported is the default. A host may explicitly choose GoogleExtraContent through ChatCompletionsClient.make or ChatCompletionsTransport.make; the transport uses the same immutable choice for response decoding and every historical request call. The selected wire field is tool_calls[].extra_content.google.thought_signature, following the [Google OpenAI-compatibility signature contract](https://ai.google.dev/gemini-api/docs/generate-content/thought-signatures#signatures-for-openai-compatibility). This does not enable generic provider metadata, ThinkingBlock signatures, reasoningDetails or server-hosted calls.

The decoder accepts an absent/JSON-null extra_content as absent. Present extra_content and google containers must be objects; a supplied thought_signature must be a nonblank string. Unsupported rejects a recognized Google signature before dispatch. Unknown extension fields remain ignored, apart from duplicate-key rejection; they cannot grant server-tool authority. The opaque string is retained exactly, without decoding or concatenation. Each stream index may receive the same value again but cannot change it. Completed and interrupted ToolUse metadata retain thoughtSignature, including when interruption sanitizes unfinished arguments to {}. Parallel calls never inherit another call's signature.

GoogleExtraContent replays that metadata on its original call across all historical assistant messages, independently of ReasoningReplay's latest-thinking policy. Other metadata is not copied to the wire. Malformed reserved signature metadata fails instead of being silently omitted. The existing runtime guard denies argument rewrites on signed calls, preserving the original canonical input; signatures are protocol data and confer no execution permission. Raw diagnostic frames may contain signatures; the added validation messages do not quote signature values.

Migration: LlmChunk.ToolCallChunk appends defaulted thoughtSignature: Option[String]; old JSON omitting it still decodes to None, while positional extractors/Product now have five fields. encode and both make methods append a defaulted format argument, preserving ordinary source calls; method values may need adaptation and dependent binaries must be rebuilt. ReasoningModelAdapter retains its one-argument parseChunk overload and adds an explicit-format overload. No ownership, retries or provider discovery are introduced.

[ChatCompletionsTransformSpec](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsTransformSpec.scala) verifies per-call preservation and signed rewrite denial over loopback HTTP; this is not live Google interoperability.

## Opaque signature details

ReasoningReplay.OpaqueSignatureDetails explicitly selects raw legacy reasoning_details objects for a host-selected compatible endpoint. Each object must have type="signature" and nonblank string data. Optional id, format and index retain the existing checked common-field rules; duplicates, unknown fields, misplaced plaintext and malformed values reject the complete object. ReasoningDetail.make and its AST codec admit this checked variant and retain its original Json.Obj; plaintext is empty. Opaque data is neither decoded nor inferred as reasoning text.

The new profile admits signature objects only. OpenRouterEncryptedDetails and OpenRouterDetails retain their original allowed types and reject signature objects; Omit and LatestAssistant remain unchanged. Scalar reasoning aliases remain independently supported under the existing opaque-profile rules, with conflicting aliases rejected. Unlike OpenRouterDetails, this profile does not make a details array authoritative over supplied scalar reasoning.

Historical replay preserves exact object order and representation on every original assistant message, including an explicitly empty reasoning_details array. It emits no scalar reasoning_content and does not infer tool identity, provider compatibility or Google tool-signature extensions. Ordinary JSON and SSE use the same checked detail decoder. Hosts explicitly select a legacy-fixture-compatible endpoint; this is not a universal reasoning or provider standard.

Migration: ReasoningReplay gains the additive OpaqueSignatureDetails case. Exhaustive matches must handle it and dependent binaries must be rebuilt; no LlmConfig or ModelRequestSettings arity changes. Existing defaults and selected-profile admission remain unchanged. This is an additive previously unsupported contract, not a defect claim against an accepted profile.

## Encrypted reasoning

ReasoningReplay.OpenRouterEncryptedDetails selects response decoding and all-historical-assistant replay for reasoning.encrypted only. OpenRouterDetails additionally selects reasoning.text and reasoning.summary from the [OpenRouter reasoning-details contract](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens#reasoning-details-api-shape). Both preserve each checked Json.Obj exactly, including absent fields and explicit nulls.

ReasoningDetail accepts a strict local union: the separately selected [legacy signature variant](#opaque-signature-details) requires nonblank opaque data and contributes no plaintext; encrypted requires nonblank opaque data; text requires a text string and permits an absent, null or nonblank opaque signature; summary accepts a summary string or the legacy text string through the [checked alias contract](#summary-text-alias). Empty text and summary strings are valid. Each variant permits optional id (nonblank string or null), format (nonblank string) and index (nonnegative Int JSON integer). Duplicate, unknown, misplaced or malformed fields fail. EncryptedReasoningDetail retains its narrower validation and provides toDetail. Data and signatures are neither decoded nor concatenated; IDs/indexes never infer tool identity or merge objects.

ReasoningDetailsDelta retains objects in received order, including repeated IDs/indexes. Assembly stores one Thinking block at the first detail occurrence, with the exact reasoningDetails array and concatenated text/summary plaintext. Encrypted objects contribute no plaintext. Explicit [] is retained and replayed; absent/null wire fields remain absent. The general decoder treats any supplied array, including [], as authoritative over same-frame scalar reasoning_content/reasoning/thinking aliases. Without an array, nonempty scalar aliases fail under OpenRouterDetails; empty strings are allowed and whitespace is nonempty. This avoids cross-frame alias deduplication and deliberately limits provider compatibility. Other profiles retain their scalar behavior. Direct accumulator callers cannot mix scalar ThinkingDelta blocks and details with nonempty plaintext in either order; opaque-only details and [] remain compatible with scalar blocks.

Selected replay validates original history and emits ordered arrays on every original assistant message, never on individual tool calls. It emits no reasoning_content and enables no other Thinking metadata. Unsupported replay profiles reject supplied arrays, including []. General detail plaintext produces one consistent Thinking event stream and canonical projection; encrypted-only details emit no Thinking events. Raw diagnostic frames remain unredacted. Google per-call signatures remain a separate host-selected format.

A nonempty detail array or a nonblank Thinking signature counts as reasoning for the bounded thinking-only reminder; [] alone does not. Presence of either reserved reasoning key in any Thinking block protects every sibling call's original arguments, even for [] or malformed metadata. Fixed preparation and approved rewrites must preserve them. Older protected Thinking blocks survive pruneObsoleteReasoning, and the compactor's argument-pruning pass preserves sibling inputs/cached content. Whole-prefix summarization and trusted host history/model-input/post-reasoning callbacks remain replacement boundaries; this is not indefinite storage or permission authority.

Migration: exhaustive ReasoningReplay matches must handle OpenRouterDetails. ReasoningDetailsDelta.details now contains Chunk[ReasoningDetail], replacing Chunk[EncryptedReasoningDetail]; direct producers can map encrypted values through toDetail and must recompile. Existing make arguments, decoder overloads and default policies remain. Msg JSON is unchanged. Checked AST codecs preserve validated representation rather than normalizing a DTO. Invalid history fails before serialization instead of silently dropping metadata.

- Two new pure groups and the existing HTTP table cover checked variants, literal null/empty values, scalar precedence, both mixed-chunk orders, exact historical replay and canonical/event agreement. The HTTP rows retain sibling-input protection for both selected profiles. This is loopback evidence, not live OpenRouter interoperability.
Only the explicitly selected reasoning/signature profiles are supported. Ordinary JSON completion follows its separate contract above; exhaustive formatter compatibility is not claimed.

## Summary text alias

Within the explicit ReasoningReplay.OpenRouterDetails profile, reasoning.summary accepts either the summary string or the legacy text string. When both are present, they must be strings with exactly equal values and contribute plaintext once. Missing aliases, JSON null, other types or conflicting strings reject the complete detail; a valid sibling cannot hide a malformed supplied field. Empty strings remain valid. Other profile selection, strict common/allowed fields and encrypted/text variants are unchanged.

The checked detail retains its literal original Json.Obj, including which alias fields were supplied. Thinking metadata and historical request replay preserve that AST rather than normalizing legacy text to summary or dropping equal dual fields. Ordered text/summary details concatenate plaintext in received order; no new signature interpretation or scalar precedence is introduced. Public signatures/defaults and existing omission/encrypted-only policies remain unchanged; callers relying on legacy-summary rejection must account for this bounded accepted input extension.

Existing ReasoningDetailSpec tables exercise current/legacy/equal-dual inputs, strict malformed/conflicting rejection, selected streaming plaintext and exact replay. The existing ChatCompletionsNonStreamingSpec HTTP-client/ReAct table exercises text-only details, legacy summary and ordered mixed details through full-message response decoding, preserving literal thinking metadata and visible answer behavior. These are controlled fixtures, not live-provider interoperability or complete parser-suite evidence. No group was added.

## Scoped HTTP completion

ChatCompletionsTransport.make accepts an injected zio-http Client, positive CompletionTransportLimits, ReasoningReplay, ToolSignatureFormat, a default-disabled CompletionRetryPolicy and an explicit CompletionEnvelopeProfile. complete returns IO[AgentError, TurnOutput] and owns a separate Scope for each HTTP attempt. Success, failure, and cancellation close the attempt scope before returning or retrying; cancellation stays interruption rather than a partial successful turn. The injected client layer and its connection pool belong to the host.

The transport sends POST JSON with Accept: text/event-stream and an optional Authorization bearer header. It requires an absolute HTTP(S) endpoint with a host, valid port, and no user information or fragment. Invalid limits, endpoints, credentials, or request bodies fail before dispatch. Transport failures use ModelError status zero without leaking exception text. HTTP rejection keeps the actual status and a bounded raw error payload. Default behavior makes one attempt; explicit status retries are described below.

A successful HTTP status also requires one text/event-stream content type. UTF-8/SSE framing feeds the strict delta decoder and accumulator. Both a compatible finish marker and [DONE] are required. EOF without [DONE], malformed protocol data, or invalid completed tool arguments cannot yield executable output. A `length` finish reason followed by [DONE] yields a stopped partial observation. [DONE] stops consumption without waiting for connection EOF. Usage frames after finish and before [DONE] remain available.

The default success-body budget is 8 MiB and the error-body prefix is 8 KiB, both configurable. The success budget counts received byte chunks before decoding, including any suffix already received in the same chunk as [DONE]; it is not a bound on the injected HTTP driver's allocation or total assembled JVM memory. Error prefixes may end inside a UTF-8 character and use replacement decoding. Request-size bounds, per-frame limits, deadlines, broader retry strategies, TLS/proxy/pool configuration, and provider extensions are separate host or planned concerns.

Transport tests cover a real loopback POST/chunked SSE response plus deterministic driver fixtures for request headers/body, split UTF-8, parallel calls, usage, finish/[DONE] requirements, malformed data, provider/status failures, body limits, and interruption cleanup. The separate client integration suite verifies actual incremental publication, a real HTTP tool cycle, late tool identity, malformed-frame rejection, and cooperative cancellation.

## Bounded HTTP-status retries

CompletionRetryPolicy.disabled preserves a single HTTP attempt. make(maxAttempts, delay = Duration.Zero) returns Either[InvalidConfiguration, CompletionRetryPolicy], requiring at least one total attempt and a finite nonnegative fixed delay. Construction performs no effects. Only actual HTTP rejection statuses 429, 500, 502, 503 and 504 are eligible; this host-selected allowlist is not inferred from a public ModelError.

A private rejection tag is created only after the configured bounded error-body read succeeds. Retry is considered after that attempt's response Scope has fully closed, and only for the exact singleton tagged failure. Cleanup defects, mixed causes, interruption, acquisition/network failures, error-body read failures, successful-status SSE/provider errors, framing/validation failures and callback failures are terminal. Exhaustion exposes the final rejection's original status and bounded payload; private tags do not escape public calls. The internal runtime attempt boundary may retain a restricted marker for a clean exhausted eligible rejection; custom/public ModelError values do not carry that authority. No Retry-After handling, exponential backoff or general network retry is provided. Runtime-owned [model failover](../agent/README.md#scoped-model-failover) has its own independently reviewed scope.

The validated request body and headers are prepared once, then each attempt acquires a fresh response Scope. The delay begins after cleanup and remains interruptible. These attempts share one logical model invocation and ModelStreamContext; they do not rerun model-input projection, create another history turn or publish rejected-response chunks. There is no retry after successful-status content has begun, so provisional model output cannot be replayed by this policy. The attempt budget counts this library's Client.streaming calls; middleware, redirects or retries inside the borrowed host client are outside that budget, and no provider billing/idempotency guarantee is implied. The host owns the injected client/pool and direct-call deadline; ReAct's outer timeout still bounds its invocation.

Migration: ChatCompletionsClient.make and ChatCompletionsTransport.make append a defaulted retryPolicy parameter. Ordinary source calls retain disabled behavior; method values may need adaptation and dependent binaries must be rebuilt. No LlmConfig or message JSON field is added. This policy is distinct from ReAct thinking-only retries and native structured-output fallback.

## Live client and event correlation

ChatCompletionsClient.make accepts the injected HTTP client and transport options, producing LlmClient. ReAct assigns a fresh ModelStreamContext(replyId, requestId) for every model invocation. replyId belongs to the enclosing agent attempt; requestId changes on each new runtime model invocation; HTTP-status attempts within that invocation retain it. The streamTurn signature now requires this argument: custom implementations and direct callers must migrate. Scripted callbacks keep their existing factory signatures, but hand-written events are not automatically enriched.

The runtime emits ModelRequestStart before invocation and ModelResponseEnd only after a non-interrupted output and request-scope cleanup. The latter carries output/total token counts from the reply; request promptTokens remains zero because no estimate is available. Failed or interrupted requests have no successful response-end event. AgentEnd still closes the surrounding attempt. The live client publishes content events inside these model boundaries; direct streamTurn callers own their model-boundary publication.

Each valid SSE JSON frame produces one ModelResponseChunk, then ordered text/thinking/function events. The entire frame is decoded and accepted by the accumulator before publication. Earlier valid events remain observable if a later frame fails. Deltas are provisional input, not completed conversation state or authorization to execute a tool.

Text and thinking retain separate index namespaces and one start per index. Tool events use streamIndex as blockIndex; identity may arrive late, so arguments are buffered until both call ID and name exist, then emitted as one buffered delta. Subsequent fragments preserve their received order. Only a validated finish plus [DONE] closes blocks, in start order, and exposes the assembled turn. Failure/cancellation may leave observed starts without ends; consumers must use runtime termination/error rather than invent completion. Thinking and selected tool signatures remain in the assembled Msg; these text-oriented events do not carry signature fragments.

Live deltas precede ModelResponseEnd. Open text/thinking blocks remain open until validated completion, allowing interleaving without premature endings. This deliberately differs from disabled upstream ordering sketches that place model-end before buffered content and close thinking before text starts. Local completed tool observations now carry the same request context and are persisted before delta/end publication. Full ordering parity and richer provider content remain pending.

## Request-scoped text transformation

ChatCompletionsClient.make accepts an optional ModelTextTransform, defaulting to identity. transform receives ModelStreamContext and the indexed TextDelta and returns ZIO[Scope, AgentError.TextTransformationError, String]. andThen composes replacements in order with the same identity/index. The callback is suspended; resources and scoped children are owned by the HTTP request and close on success, failure, or cancellation. A host that needs cross-fragment state must supply explicit ZIO state with appropriate ownership; this API does not infer state lifetime.

The whole provider frame is decoded and checked against stream progress before any transformation; ordinary JSON validates its complete body at bounded EOF before transforming visible text. Only visible text changes: indexes, thinking, tool arguments, usage, and finish markers remain authoritative protocol data. Replacements feed the accumulator and typed text events together, so final text, TextBlockEnd, history, and structured extraction agree. ModelResponseChunk remains the original raw frame for diagnostics; it is not a redacted or transformed payload. Empty replacements remain valid empty deltas. Stateful transformations run sequentially; their external effects cannot be rolled back if a later callback fails.

The callback error channel accepts only TextTransformationError. Host operations must map external errors explicitly; provider ModelError cannot enter this interface and accidentally trigger Auto native-format fallback. A failed transformation publishes neither that frame nor its typed deltas. Earlier accepted events remain visible. TextTransformationError and interruption propagate through existing runtime handling, with no false model/content completion. A valid finish remains mandatory in both modes. SSE additionally requires a blank-line-terminated [DONE]; ordinary JSON requires bounded body EOF and complete full-envelope validation. Transformed native JSON still passes strict structured decoding. HTTP response-byte limits bound incoming bytes, not expansion performed by a trusted host transform.

This is a typed live-client text transformation point, not the upstream event-rewriting middleware API. It cannot rewrite requests, tools, reasoning, usage, or lifecycle events. Scripted/custom LlmClient implementations do not inherit it. General model-call middleware remains pending. Post-reasoning replacement/removal is a separate ReAct policy boundary documented in the [agent contracts](../agent/README.md).

## Current acceptance

Use the explicit profiles above and the [test index](../../../../../../../test/scala/com/weisgnosis/agents/core/client/README.md) for their supported boundaries. Passing local fixtures does not establish universal provider interoperability.

Unselected provider signatures, inline server-response dialects and reasoning-detail variants are not decoded automatically. These tests establish the listed checked boundaries, not every provider format or a global response-size limit.

The validator moved from client.ProtocolJson to core.json.JsonBoundary as an internal implementation change. This keeps model and agent parsing independent of HTTP/client layering. decode validates exactly one textual JSON document; validate preserves an existing AST after rejecting duplicate fields. Public Msg structured extraction now rejects trailing input and ambiguous fields instead of accepting a prefix or one duplicate value.

## Capability increment identities

Current capability declarations are [StructuredOutputCapability](StructuredOutputCapability.scala) and [ModelTarget](ModelTarget.scala); the [routing tests](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/StructuredOutputCapabilityRoutingSpec.scala) exercise their runtime selection.

## Interrupted transformed progress

This is concrete history-loss evidence, not a claim that raw diagnostic frames should be rewritten.

The correction introduces internal request-local progress, separate from public streamTurn and the existing five-argument attemptTurn. The new restricted attemptTurnWithProgress default delegates to the existing attempt method, retaining legacy overrides and failover capability. The live client supplies validated snapshots; custom clients and public wrappers do not acquire progress support implicitly. Public method signatures, defaults, enum cases and configuration arities remain unchanged; dependent binaries follow the development rebuild policy.

Each actual HTTP attempt resets its progress. SSE snapshots are recorded only after complete raw-frame validation, every text transformation and successful transformed accumulation, before semantic/frame publication. A waiting transform, raw fragment or malformed frame cannot enter the snapshot. Ordinary JSON snapshots require complete body/full-message validation, all transforms and completed accumulation. A new target resets request-local state; successful live-client completion clears it after publisher finalization.

The runtime consults progress only after owned request cleanup has joined and the cause is interruption-only. It validates the partial assistant and checked usage before persistence, then reconciles interrupted calls through ordinary recovery. External fiber cancellation remains interruption and cannot emit a successful Result. Typed failures, defects and combined failure/interruption causes do not authorize partial acceptance. A fully consumed owning stream exposes its complete failed outcome; an external consumer that terminates early joins cleanup but abandons subsequent terminal delivery. Incomplete indexed tool arguments use the existing toInterrupted sanitization to {}; recovery produces Error observations without dispatching those local tools. No normal model/text/tool completion is inferred from a snapshot.

The expanded existing cancellation group targets cooperative/external interruption after accepted transformed text, interruption before accepted text, fully consumed cooperative cleanup failure and two interleaved incomplete calls. Early external stream consumers intentionally discard later terminal delivery, so cleanup-defect assertions must consume the owning stream to its failed outcome. This bounded contract adapts the intent of pinned transformedTextIsUsedForPartialMessageWhenModelCallIsInterrupted (425), without generic middleware/event rewriting or provider interoperability claims.

## Text transformation increment identities

[ModelTextTransform](ModelTextTransform.scala) defines request-scoped text transformation; [ChatCompletionsTransformSpec](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsTransformSpec.scala) exercises actual HTTP streams, transformed history and interruption boundaries. These tests also distinguish subscription-local event filtering and immutable host result projection from canonical runtime mutation.

## Native provider selection

Select a provider through the existing `ModelTarget(client, config)`; the host borrows a scoped `zio.http.Client` and supplies an explicit full endpoint and credentials. `ChatCompletionsClient` remains the OpenAI-compatible profile. Native profiles do not translate unsupported generation settings silently or infer safe retries from uncertain network failures.

`AnthropicLlmClient.make(http)` implements native Messages text, signed thinking and local tool turns with `x-api-key` and the selected API version. Use the full `/v1/messages` endpoint. System text is encoded separately; exact thinking signatures and indexed text/tool order survive continuation. Choose sufficient maxTokens for an explicitly selected thinking budget and temperature 1. Native responseFormat, penalties, seed, reasoningEffort, media and redacted thinking are rejected by this profile. Synthetic structured output uses the ordinary local `generate_response` mechanism instead of native formatting.

Select basic hosted search explicitly with `AnthropicWebSearch.make(maxUses)` and `AnthropicLlmClient.make(http, webSearch = Some(search))`. Search is provider-owned and adds no executable Toolkit binding. Canonical assistant messages retain inline search outcomes; no separate local Tool messages are created. Native search results, encrypted content and citations are retained for exact replay and checked against the current canonical content before reuse. A host transformation that changes those blocks must remove or regenerate compatible native replay data; stale hidden data fails explicitly. Remote search errors are observations, while unsupported/incomplete protocol termination fails the model request. Advanced dynamic-filtering search variants are outside this profile.

`GeminiLlmClient.make(http)` supports native streaming text and local function calling through the ordinary ReAct/Toolkit authorization and execution path. Supply the full selected-model `:streamGenerateContent` endpoint; the client requests SSE and uses `x-goog-api-key`. Tool definitions use `functionDeclarations` with `parametersJsonSchema`. Parallel function responses are grouped into one user content and matched by call identity and name. Provider IDs are returned unchanged; when an older response omits an ID, the runtime assigns a request-scoped identity and omits that synthetic ID from the native response. Repeated provider IDs in later steps remain separate call occurrences. Failed local tools become native error observations; interruption joins owned cleanup and reconciles unfinished calls before continuation.

Function names in this supported profile contain 1–64 ASCII letters, digits, underscores or hyphens. Declarations outside that profile fail before HTTP; incoming calls use the same validation.

The original ordered model parts, thought text and thought signatures survive checked continuation, as required by Google's [function-call wire contract](https://ai.google.dev/api/generate-content#FunctionCall) and [thought-signature rules](https://ai.google.dev/gemini-api/docs/generate-content/thought-signatures). Changed native replay content, unmatched responses and malformed calls fail closed. Metadata does not authorize local execution. Raw canonical thinking without native replay, media, native structured settings, tool-choice/parallel-generation preferences, schema strictness and realtime sessions remain unsupported. No live Gemini run is implied by the offline fixtures.

`GeminiLlmClient.make(http, execution = GeminiCodeExecution.Enabled)` selects provider-hosted Python execution instead. Code and terminal execution outcomes become server-marked call/inline-result pairs, including failed/deadline outcomes, and are never executed locally. Local tool declarations combined with this hosted profile are rejected before HTTP I/O.

Each native request owns its response scope and byte budget. Cancellation preserves validated partial data, sanitizes incomplete tool JSON and joins HTTP cleanup. Unsupported stop reasons and early EOF cannot become successful completion. Neither native profile currently grants a definite pre-response failover marker or automatic retry.

The actual loopback public contracts are [AnthropicLlmClientSpec](../../../../../../../test/scala/com/weisgnosis/agents/core/client/AnthropicLlmClientSpec.scala) and [GeminiLlmClientSpec](../../../../../../../test/scala/com/weisgnosis/agents/core/client/GeminiLlmClientSpec.scala). Loopback evidence is distinct from a real native-provider run. [NativeProviderSmoke](../../../../../../../../../examples/src/main/scala/com/weisgnosis/agents/examples/NativeProviderSmoke.scala) is an opt-in executable example. The supplied OpenAI-compatible gateway cannot verify these native wire protocols.

## Token-budget exhaustion

`TurnOutput.Truncated` distinguishes provider token exhaustion from a complete answer and caller cancellation. OpenAI `length`, Anthropic `max_tokens`, and Gemini `MAX_TOKENS` preserve visible text and usage only. Canonical history omits incomplete calls, tool results, thinking signatures, and native replay payloads. Received frames and deltas remain diagnostic events; truncated tool fragments do not emit successful tool-call end events.

Protocol termination remains mandatory: OpenAI streaming requires [DONE], Anthropic requires the matching named message_stop event, and Gemini requires its validated terminal candidate. Other unsupported finish reasons and premature EOF remain failures. Truncation does not trigger automatic retry or model fallback.

ReAct records the partial assistant message and returns `AgentRunState.Stopped("Model output token budget exhausted")`. It bypasses the post-reasoning rewrite hook, executes no tools from that response, and cannot succeed as structured output. Memory extraction similarly rejects partial candidates. The new sealed `TurnOutput` variant requires downstream exhaustive matches to be updated and consumers to rebuild; no transcript schema changes are required.

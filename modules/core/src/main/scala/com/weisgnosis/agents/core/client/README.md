# Model client port and stream assembly

Typed request settings preserve provider preferences without granting runtime authority. The contracts below record accepted source identities, migration and verification for each bounded scope.

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
- [StopSequences](StopSequences.scala)
- [SseParser](SseParser.scala)
- [StreamAccumulator](StreamAccumulator.scala)
- [TurnOutput](TurnOutput.scala)

## Ordinary JSON completions

ChatCompletionsClient.makeNonStreaming and ChatCompletionsTransport.makeNonStreaming explicitly select ordinary JSON HTTP completion. ChatCompletionsRequest.encodeNonStreaming is the matching pure request encoder: it emits stream=false and omits streaming-only stream_options. Requests advertise Accept: application/json. Existing make and encode signatures and defaults remain unchanged and continue selecting SSE. Selection is host-owned; there is no content-type sniffing, provider inference or fallback between response modes.

A successful response requires exactly one Content-Type header whose media type is application/json, with optional parameters. The transport consumes the bounded UTF-8 body to EOF before decoding one complete JSON document; responseBytes applies to raw body bytes. It rejects trailing content, duplicate keys, malformed full messages, incomplete tool arguments and incompatible finish reasons. No SSE framing or [DONE] marker applies to this mode. EOF alone does not authorize success: the full-message decoder and accumulator must validate completion.

Choice zero must contain an assistant message with an explicit compatible finish reason and no delta field. Existing text/null content, selected scalar/detailed reasoning, complete function calls, usage and explicitly configured signatures retain their checked decoding rules. Array order supplies tool indices. Full-envelope validation completes before text transforms or diagnostic/semantic event publication. A successful JSON response produces one validated raw frame plus correlated semantic events under the existing ModelStreamContext contract; these events are not incremental network delivery. ReAct retains ordinary tool execution and structured-output authority.

Each attempt owns its response Scope and joins success/failure/cancellation cleanup before returning. Existing bounded HTTP-status retry and pre-response failover rules remain unchanged; malformed successful responses do not gain retry authority. Native/synthetic structured modes and settings projection reuse the selected client mode without modifying canonical history ownership.

Migration: the three new public helpers are additive; prior public factory/encoder signatures and defaults are unchanged. CompletionResponseMode and the full-message decoder remain package-private. No public Boolean execution switch, new configuration field or constructor/Product migration is introduced by this increment.

| Reviewed source/spec | Git blob |
| --- | --- |
| [ChatCompletionsClient.scala](ChatCompletionsClient.scala) | `cdce8807a5f7f3eace9558139d6fe2578c77078d` |
| [ChatCompletionsRequest.scala](ChatCompletionsRequest.scala) | `a2a632bc1468b0c4912ef935a58edde3a9de9b36` |
| [ChatCompletionsTransport.scala](ChatCompletionsTransport.scala) | `2e96e2e0ca08e25cf620d496b68f1c626fdbb75f` |
| [CompletionEnvelopeDecoder.scala](CompletionEnvelopeDecoder.scala) | `464a7d3c69a2b7b7b847f38580d81b62a7db6534` |
| [ChatCompletionsRequestSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsRequestSpec.scala) | `047d556fb36d1db13fd2861bbca7b756f220b749` |
| [ChatCompletionsNonStreamingSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsNonStreamingSpec.scala) | `1a9ecf7cc350750d1697a7393b0d35f73981ec1f` |
| [ArtifactSmoke.scala](../../../../../../../../../../verification/artifact-consumer/ArtifactSmoke.scala) | `25451c691659322f0dc6de8812c6f246068faedc` |

- [x] review_fp approved FP/ZIO correctness and review_invariants approved the seven exact identities with no findings.
- [x] Focused verification passes 50 tests. Two new integration groups cover ordinary text/tool/reasoning turns and eligible HTTP-status retry, rejection before transforms/events, bounded EOF and joined failure/cancellation cleanup; existing request tables cover stream=false and absent stream_options.
- [x] nonstream-verify.log passes full formatting, verify and packageArtifacts: 667 core / 724 harness / 85 MCP / 5 examples (1,481 total), zero compiler warnings or failed tests. nonstream-artifact-preparation.log passes archive/POM inspection; nonstream-artifact-consumer.log passes standalone formatting, clean compilation and forked execution against fresh loaded module hashes.

## Scalar reasoning aliases

The decoder recognizes string or JSON-null reasoning_content, reasoning and thinking. In scalar profiles, absent/null fields contribute no value, identical supplied strings emit one ThinkingDelta before visible text, and different supplied strings fail with Conflicting scalar reasoning aliases. Empty strings emit no thinking; an empty string conflicting with a nonempty string is rejected. Exact text is preserved without trimming. Malformed recognized values fail even when another alias or detailed reasoning would otherwise take precedence.

OpenRouterDetails keeps reasoning_details authoritative: valid scalar aliases are ignored without duplication. Any nonempty scalar alias requires an explicit details array; an explicitly empty array is still authoritative. This rule applies to ordinary deltas and full-message SSE envelopes through the same decoder. No provider identity is inferred, and outbound replay still uses its existing configured wire representation.

Migration: public signatures/configuration are unchanged. Input validation intentionally tightens the former reasoning_content-over-thinking precedence: conflicting scalar strings now fail instead of silently losing one value. Providers emitting multiple scalar fields must make their values equal, omit redundant fields, or use the explicitly supported authoritative details profile. This extension was previously outside the selected alias scope; the pre-change failure run is new-contract evidence rather than a claim that an accepted contract was defective.

| Reviewed source/spec | Git blob |
| --- | --- |
| [ReasoningModelAdapter.scala](ReasoningModelAdapter.scala) | `8dd49ca8b602ae44bcdee42a51b558e884095cb9` |
| [ReasoningAdapterValidationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ReasoningAdapterValidationSpec.scala) | `e5ce3b8fc49bbff67ad3a6d0d19c0e412d934729` |
| [ReasoningDetailSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ReasoningDetailSpec.scala) | `74699ecd06022de8020863e050603083a08312cb` |
| [ChatCompletionsClientSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsClientSpec.scala) | `69bc307105d3a71fa5dbbbd9fd19da4c6c61781c` |

- [x] review_fp and review_invariants approved these four exact identities with no findings.
- [x] reasoning-alias-red.log records 28 passed / 4 failed against unchanged production: unsupported/malformed reasoning, conflict handling, details admission and HTTP thinking preservation failed the proposed contract. reasoning-alias-focused.log passes all 32 groups after implementation; existing tables were extended without new groups.
- [x] Formatting and verify packageArtifacts pass in reasoning-alias-verify.log: 665 core / 724 harness / 85 MCP / 5 examples (1,479 total), zero compiler warnings or failed tests. reasoning-alias-artifact-preparation.log validates archives/POMs; reasoning-alias-artifact-consumer.log clean-compiles and runs the neutral consumer against fresh loaded JAR hashes.
- [x] Both reviewers approved corrected final source/documentation closure. Changed prose/comments were reviewed in English; 75 Markdown documents / 2,554 local references pass with zero link issues. No temporary review report remains.

## Completion envelope profiles

CompletionEnvelopeProfile.IndexedDeltas remains the default. Hosts may explicitly choose DeltasWithFullMessageSummary on ChatCompletionsClient.make or ChatCompletionsTransport.make for SSE streams containing a final object=chat.completion envelope. Selection is independent of model names and does not change request JSON or LlmConfig. Ordinary JSON HTTP responses use the separate explicit [non-streaming factories](#ordinary-json-completions).

A full envelope requires exactly choice zero, an assistant message, an explicit compatible finish reason and no simultaneous delta field. String/null content and existing selected reasoning profiles are supported. Complete function calls require nonblank IDs/names, explicit string JSON-object arguments and type=function. Array order supplies stream indices; supplied indices must match. All known fields, usage and complete tool arguments are validated before either materialization or suppression.

Before any delta, one full message supplies semantic output. After any accepted delta, including a role-only delta, full-summary text, reasoning and tool arguments are suppressed; only validated usage and a compatible finish are retained. Usage-only frames do not establish a delta phase. An existing identical finish is not repeated; a conflicting finish fails. After a full summary, usage-only frames remain valid and subsequent choices fail. Suppressed summary values never reach text transforms or semantic delta events, never replace canonical output and cannot repair incomplete streamed calls. The actual accumulator and a blank-line-terminated [DONE] still authorize completion. Raw diagnostic frames retain the original data.

Migration: the appended defaulted envelopeProfile parameter changes ChatCompletionsTransport.make from five to six parameters and ChatCompletionsClient.make from six to seven. Ordinary omitted-argument source calls retain their behavior; explicit method values may require adaptation and dependent binaries must be rebuilt. The enum has a derived domain JSON codec; its names are host configuration values, not provider wire fields. The request-local decoder remains package-private and does not expose a host extension contract.

| Reviewed source/spec | Git blob |
| --- | --- |
| [CompletionEnvelopeDecoder.scala](CompletionEnvelopeDecoder.scala) | [Current ordinary-JSON identity](#ordinary-json-completions) |
| [ChatCompletionsClient.scala](ChatCompletionsClient.scala) | [Current ordinary-JSON identity](#ordinary-json-completions) |
| [ChatCompletionsTransport.scala](ChatCompletionsTransport.scala) | [Current ordinary-JSON identity](#ordinary-json-completions) |
| [StreamAccumulator.scala](StreamAccumulator.scala) | `3e7187b943491785d96ccb686343d8d48ff5de25` |
| [CompletionEnvelopeDecoderSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/CompletionEnvelopeDecoderSpec.scala) | `0d56b2ba1839634687b79c10a918e2048145c283` |
| [ChatCompletionsClientSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsClientSpec.scala) | [Current identity](#scalar-reasoning-aliases) |
| [ChatCompletionsTransportSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsTransportSpec.scala) | `7c3dc32221063194dc09ecd0f9a9afa8466de2cf` |
| [ArtifactSmoke.scala](../../../../../../../../../../verification/artifact-consumer/ArtifactSmoke.scala) | [Current ordinary-JSON identity](#ordinary-json-completions) |

- [x] review_fp approved FP/ZIO correctness and review_invariants approved protocol/runtime invariants for these exact identities, with no findings.
- [x] envelope-focused.log passes 29 groups. Three pure table groups and one client integration group cover full-only output, selected reasoning details, multiple indexed calls, strict failures, terminal reconciliation and single-copy semantic events/transforms. Existing missing-DONE and incomplete-tool tables verify cleanup and zero dispatch; no mechanically duplicated suite was added.
- [x] Formatting and verify packageArtifacts pass in envelope-verify.log: 665 core / 724 harness / 85 MCP / 5 examples (1,479 total), zero compiler warnings or failed tests. envelope-artifact-preparation.log validates current archives/POMs; envelope-artifact-consumer.log clean-compiles and runs the neutral consumer against fresh loaded JAR hashes, including the public profile enum codec.
- [x] Both reviewers approved final source/documentation closure. Changed prose/comments were reviewed in English; 75 Markdown documents / 2,538 local references pass with zero link issues. No temporary review report remains.

## SSE frame completion

The selected core SSE byte profile accepts LF and CRLF line separators, including separators fragmented across chunks. A data frame is dispatched only after an explicit blank line. EOF discards pending frame data and preserves already dispatched frames; an unterminated [DONE] cannot complete a model turn. The transport still requires a valid finish reason and complete indexed tool arguments before returning executable output. This follows the [event-stream EOF rule](https://html.spec.whatwg.org/multipage/server-sent-events.html#event-stream-interpretation). Standalone CR separators and complete browser EventSource semantics are outside this profile; MCP uses its separately reviewed framing implementation.

This corrects parser behavior without changing public signatures or configuration. Providers must terminate each data frame, including [DONE], with a blank line. No EOF-as-success fallback is added.

| Reviewed source/spec | Git blob |
| --- | --- |
| [SseParser.scala](SseParser.scala) | `3366a9989e9abc1104cb343676c375e599e7578a` |
| [SseParserSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/SseParserSpec.scala) | `b5db54ccfd26934b7b3eee22bd31f0c5db0df57a` |
| [ChatCompletionsTransportSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsTransportSpec.scala) | [Current identity](#completion-envelope-profiles) |

- [x] review_fp approved FP/ZIO correctness and review_invariants approved completion authority for these exact identities, with no findings.
- [x] sse-framing-red.log reproduces the defect against unchanged production: 19 passed / 2 failed. Incomplete parser frames were emitted, and all three unterminated DONE variants returned successful FinalText instead of the required EOF failure.
- [x] sse-framing-focused.log passes 21 groups. One parser table group covers complete/incomplete byte-fragmented LF/CRLF frames and retained completed prefixes; the existing transport completion group checks missing and truncated DONE. No additional upstream whole-method parity is claimed.
- [x] Formatting and verify packageArtifacts pass in sse-framing-verify.log: 661 core / 724 harness / 85 MCP / 5 examples (1,475 total), zero compiler warnings or failed tests. sse-framing-artifact-preparation.log validates archives/POMs; sse-framing-artifact-consumer.log clean-compiles and runs the neutral consumer against fresh loaded JAR hashes.
- [x] Both reviewers approved final source/documentation closure. Changed prose/comments were reviewed in English; 75 Markdown documents / 2,516 local references pass with zero link issues. No temporary review report remains.

## Runtime event ordering

The held stream and real HTTP tool cycle in ChatCompletionsClientSpec assert the complete lifecycle projection, excluding only raw ModelResponseChunkEvent frames. Incremental content/tool-call Start and Delta events precede ModelResponseEnd; block End events follow validated completion. Thinking and text are independently indexed streams: the tested thinking-then-text input begins thinking first, but text may start before ThinkingEnd. A completed local result lifecycle occurs between the tool-call model response and the next model request. AgentResult precedes the final AgentEnd. Hosts may explicitly apply the [final-answer presentation filter](../event/README.md#optional-final-answer-filter) to delay/suppress correlated intermediate text; raw diagnostics, final results and canonical history remain unchanged.

ReActAgentEventSpec checks AgentStart first and the matching AgentEnd last for fully drained success, buffered events, failure, self/cooperative interruption and timeout. Early consumer termination still joins cleanup while abandoning subsequent output. Existing partial-confirmation tests establish correlated Ask after batch drain and before RequestStop, then Decision before result events in separate restored invocations; these generic events do not prove a human response. The [parity mapping](../../../../../../../../../../docs/architecture/test-parity.md#agenteventstreamtest-seven-disabled-ordering-declarations) records intentional differences from disabled upstream comments. Model DataBlock emission remains unspecified and unimplemented; tool-result data events are a separate supported path.

| Reviewed spec | Git blob |
| --- | --- |
| [ReActAgentEventSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentEventSpec.scala) | `e2991912ecc419178a41373ae9b56d7467314faf` |
| [ChatCompletionsClientSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsClientSpec.scala) | [Current identity](#cache-usage-decoding) |

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for these two identities, with no findings. Existing groups were strengthened; no production behavior or test-group count changed. Focused `event-order-focused.log` passed seventeen tests in 7.747 seconds.
- [x] Formatting and full verification passed in `event-order-verify.log`, with zero compiler warnings or failures. Current totals and environment belong to the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).

## Cache usage decoding

Compatible streamed usage preserves prompt_cache_hit_tokens and prompt_tokens_details.cache_write_tokens in the existing ChatUsage.cachedTokens and cacheCreationTokens fields. A supplied direct cache-hit count takes precedence over nested cached_tokens, including explicit zero. Missing or JSON-null direct counts fall back to the nested count, then zero. Missing/null cache-write counts become zero. All supplied recognized counts are validated as nonnegative JSON integers within Int bounds before precedence is applied; a malformed overridden fallback is still rejected.

This is a private decoder correction without public source/JVM or configuration migration. It changes previously lost usage observations, not token billing, model generation, permissions or lifecycle ownership. The parser table and existing real HTTP retry scenario verify decoded counters and canonical history. Ordinary full-response parsing has separate [accepted HTTP support](#ordinary-json-completions); other provider formats remain separate work.

| Reviewed file | Git blob |
| --- | --- |
| [ReasoningModelAdapter.scala](ReasoningModelAdapter.scala) | [Current identity](#scalar-reasoning-aliases) |
| [ReasoningAdapterValidationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ReasoningAdapterValidationSpec.scala) | [Current identity](#scalar-reasoning-aliases) |
| [ChatCompletionsClientSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsClientSpec.scala) | [Current identity](#completion-envelope-profiles) |

- [x] review_fp and review_invariants independently approved these three identities with no findings.
- [x] The regression ran against unchanged adapter blob 8873f07ca50be4f4881bd6cd3ff05e4b50741b0d: cache-usage-red.log records 16 passes/2 failures, including actual cached=20/creation=0 instead of expected 60/12 and acceptance of a malformed direct count.
- [x] cache-usage-focused.log passes all 27 parser/client groups; existing tables were extended without adding groups.
- [x] Formatting, verify and packageArtifacts pass in cache-usage-verify.log: 659 core / 724 harness / 85 MCP / 5 examples (1,473 total), zero compiler warnings or failed tests. Current documentation links and English prose are checked before commit.

Per-tool provider strictness has a separate [definition contract and migration](../tool/README.md#provider-tool-schema-strictness); it is not a model-request projection setting.

## Optional reasoning effort

The supported ReasoningEffort.Low, Medium and High subset expresses explicit provider generation preferences; it is not a universal provider effort vocabulary. LlmConfig.reasoningEffort defaults to None; missing/JSON-null configuration keeps None and the provider field is omitted. Supplied values emit reasoning_effort as low, medium or high. Domain configuration uses the enum codec rather than lowercase provider strings; malformed enum values fail decoding. This bounded vocabulary follows the pinned upstream setting, without inferred model capabilities, provider-specific thinking budgets or a Java mutable-options merge.

Each primary/fallback target supplies its own preference to ModelRequestSettings.reasoningEffort. Scoped request projection may replace it or clear it with None; legacy history transforms retain it, retries reuse the serialized body, and native/synthetic formatting preserves the projected value. The runtime does not infer completion, permissions or reasoning replay policy from this preference. Endpoint support remains the host's responsibility.

Migration: LlmConfig has fifteen positional/Product fields and ModelRequestSettings has eight. Appended defaulted options preserve ordinary omitted-argument construction and legacy JSON; positional patterns/constructor method values may need adaptation and dependent binaries must be rebuilt.

| Reviewed file | Git blob |
| --- | --- |
| [ModelInputTransform.scala](../agent/ModelInputTransform.scala) | 5235e2b4b911aa548e54b86bf8401243bb0522fc |
| [ReActAgent.scala](../agent/ReActAgent.scala) | 70ba09d5499cfe862e814bb79b918668dc4c168e |
| [ChatCompletionsRequest.scala](ChatCompletionsRequest.scala) | [Current ordinary-JSON identity](#ordinary-json-completions) |
| [LlmClient.scala](LlmClient.scala) | c42e38120c873babf1cff3956d35c734d65f04eb |
| [ModelInputTransformSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ModelInputTransformSpec.scala) | 3a99d8850a8fa2905f1384bf04a818f386dd00da |
| [ChatCompletionsRequestSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsRequestSpec.scala) | [Current ordinary-JSON identity](#ordinary-json-completions) |
| [ModelFailoverSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ModelFailoverSpec.scala) | [Current identity](../tool/README.md#provider-tool-schema-strictness) |
| [ArtifactSmoke.scala](../../../../../../../../../../verification/artifact-consumer/ArtifactSmoke.scala) | [Current ordinary-JSON identity](#ordinary-json-completions) |

- [x] review_fp and review_invariants approved these eight source/spec identities, including the final formatted external consumer, with no findings.
- [x] reasoning-effort-focused.log passes 27 existing request/projection/failover groups; no new groups or reproduced-defect claim were added.
- [x] Formatting, verify and packageArtifacts pass in reasoning-effort-verify.log: 660 core / 724 harness / 85 MCP / 5 examples (1,474 total), zero compiler warnings or failed tests. reasoning-effort-artifact-preparation.log validates current archives/POMs; reasoning-effort-artifact-consumer.log clean-compiles and runs the neutral external consumer against fresh loaded artifact hashes. English prose and local links are checked before commit.

## Optional stop sequences

StopSequences is an opaque immutable nonempty Chunk[String]. one(first, rest*) constructs a valid value; fromChunk returns a typed InvalidConfiguration failure for an empty collection. Its checked JSON codec uses an array and rejects empty arrays, scalar strings and nonstring entries. It preserves exact strings, whitespace, empty strings, order and duplicates; no provider-specific count limit or automatic normalization is imposed. Endpoint support remains the host's responsibility.

LlmConfig.stopSequences defaults to None; absent/JSON-null configuration retains None, which omits provider stop. Some emits an array, including a singleton. Each selected primary/fallback target supplies its own value to ModelRequestSettings.stopSequences. Request projection can replace it or clear it with None; legacy history transforms retain it. HTTP retries reuse the encoded body and native/synthetic formatting retain projected settings. These are provider generation settings: the runtime does not trim output locally, bypass tool checks or infer successful completion from matching text.

Migration: current LlmConfig and ModelRequestSettings arities follow the [reasoning-effort migration](#optional-reasoning-effort). The appended defaults preserve ordinary omitted-argument construction and legacy configuration JSON. Adapt positional patterns/constructor method values and rebuild dependent binaries. Custom LlmClient implementations receive the typed settings and decide whether their provider supports them.

| Reviewed file | Git blob |
| --- | --- |
| [ModelInputTransform.scala](../agent/ModelInputTransform.scala) | [Current identity](#optional-reasoning-effort) |
| [ReActAgent.scala](../agent/ReActAgent.scala) | [Current identity](#optional-reasoning-effort) |
| [ChatCompletionsRequest.scala](ChatCompletionsRequest.scala) | [Current ordinary-JSON identity](#ordinary-json-completions) |
| [LlmClient.scala](LlmClient.scala) | [Current identity](#optional-reasoning-effort) |
| [StopSequences.scala](StopSequences.scala) | 07e81b7f93282be465a2db4d5eacb98bdac3dbca |
| [ModelInputTransformSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ModelInputTransformSpec.scala) | [Current identity](#optional-reasoning-effort) |
| [ChatCompletionsRequestSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsRequestSpec.scala) | [Current ordinary-JSON identity](#ordinary-json-completions) |
| [ModelFailoverSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ModelFailoverSpec.scala) | [Current identity](../tool/README.md#provider-tool-schema-strictness) |
| [StopSequencesSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/StopSequencesSpec.scala) | a55a615fc899a28cc14d610af8005aaa7f72d448 |
| [ArtifactSmoke.scala](../../../../../../../../../../verification/artifact-consumer/ArtifactSmoke.scala) | [Current ordinary-JSON identity](#ordinary-json-completions) |

- [x] review_fp and review_invariants approved the nine library/spec identities with no findings. The external consumer extension uses its final standalone formatting identity in the table.
- [x] stop-sequences-focused.log passes 28 groups. One new paired table group covers the checked type; existing request/projection/failover tables were extended. This additive feature has no pre-fix defect claim.
- [x] Formatting, verify and packageArtifacts pass in stop-sequences-verify.log: 660 core / 724 harness / 85 MCP / 5 examples (1,474 total), zero compiler warnings or failed tests. stop-sequences-artifact-preparation.log validates archives/POMs; stop-sequences-artifact-consumer.log clean-compiles and runs the external consumer against the fresh loaded JAR hashes. Current links and English prose are checked before commit.

## Optional parallel tool-call steering

LlmConfig.parallelToolCalls is Option[ToolCallParallelism], defaulting to None. ToolCallParallelism.Enabled and Disabled encode as provider parallel_tool_calls true and false respectively; None omits the field. Explicit preferences remain present when no tools are advertised. Domain configuration uses the typed camelCase field; absent or JSON-null values decode as None, and malformed enum values fail decoding.

This is a model generation preference. It does not change AgentConfig.toolExecution, admitted tools, authorization or call dispatch: a model may return multiple calls despite Disabled, and the runtime still applies its normal policies to every call. Each primary/fallback target retains its own configured preference through request settings projection; ModelRequestSettings does not expose this capability. Native/synthetic formatting and retry body reuse preserve the configured value.

Migration: current LlmConfig arity follows the [stop-sequence migration](#optional-stop-sequences). Existing omitted-argument construction and legacy JSON retain defaults; positional patterns and constructor method values may need adaptation, and dependent binaries must be rebuilt. Domain enum JSON is distinct from the provider Boolean wire field.

| Reviewed file | Git blob |
| --- | --- |
| [LlmClient.scala](LlmClient.scala) | [Current identity](#optional-reasoning-effort) |
| [ChatCompletionsRequest.scala](ChatCompletionsRequest.scala) | [Current ordinary-JSON identity](#ordinary-json-completions) |
| [ChatCompletionsRequestSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsRequestSpec.scala) | [Current ordinary-JSON identity](#ordinary-json-completions) |
| [ModelFailoverSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ModelFailoverSpec.scala) | [Current identity](../tool/README.md#provider-tool-schema-strictness) |
| [ChatCompletionsClientSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsClientSpec.scala) | [Current identity](#cache-usage-decoding) |

- [x] review_fp approved FP/ZIO correctness and review_invariants approved relevant invariants for these five identities, with no findings.
- [x] resume-focused.log passed 41 core tests in 8.172 seconds, including existing request/config/failover tables and the actual HTTP authorization cycle. Full formatting, verify and packageArtifacts passed in resume-verify.log; an isolated packaged consumer exercised typed configuration in resume-artifact-consumer.log. No new test groups or live-provider compliance claim are implied.

## Optional seed and exact request rendering

LlmConfig.seed and ModelRequestSettings.seed are Option[Long], defaulting to None. Some values, including zero and both signed 64-bit endpoints, encode as exact JSON integers under seed; None omits the field. Configuration JSON rejects fractional and out-of-Long-range seeds, while absent/null values retain None. No narrowing to Java's formatter integer range or provider reproducibility guarantee is implied.

Each selected target supplies its own seed to request projection. The projected value replaces it, with None explicitly clearing it. Retries reuse the serialized body, and native/synthetic requests retain their projected settings. Seed changes no model identity, endpoint, tools, permissions or response format. Migration: current LlmConfig and ModelRequestSettings arities follow the [stop-sequence migration](#optional-stop-sequences); defaulted appended fields preserve ordinary omitted-argument construction and legacy configuration JSON. Adapt positional patterns/method values and rebuild dependent binaries.

The HTTP request body and historical tool-argument strings use the pure [exact JSON renderer](../json/README.md). Checked AST validation still precedes rendering. This avoids the dependency's decimal corruption at a minimum-Long unscaled coefficient, including nested values and nonzero scales. It is limited to explicitly integrated output boundaries: structured closing text and local transcript output have their own [reviewed integration](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/transcript/README.md#exact-owned-numeric-output). [Exact domain/tool output](../json/README.md#exact-domain-and-tool-output) covers compactor provenance/previews and checked factory execution; hosts can opt into ExactJson.encode. External dependency serialization and legacy direct tool encoding remain outside that guarantee.

| Reviewed core file | Git blob |
| --- | --- |
| [ModelInputTransform.scala](../agent/ModelInputTransform.scala) | [Current identity](#optional-reasoning-effort) |
| [ReActAgent.scala](../agent/ReActAgent.scala) | [Current identity](#optional-reasoning-effort) |
| [ChatCompletionsRequest.scala](ChatCompletionsRequest.scala) | [Current ordinary-JSON identity](#ordinary-json-completions) |
| [ChatCompletionsTransport.scala](ChatCompletionsTransport.scala) | [Current ordinary-JSON identity](#ordinary-json-completions) |
| [LlmClient.scala](LlmClient.scala) | [Current identity](#optional-reasoning-effort) |
| [JsonBoundary.scala](../json/JsonBoundary.scala) | `6cf481403cc762117989e31d5e1dd2c0c1ffd3be` |
| [ModelInputTransformSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ModelInputTransformSpec.scala) | [Current identity](#optional-reasoning-effort) |
| [ChatCompletionsRequestSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsRequestSpec.scala) | [Current ordinary-JSON identity](#ordinary-json-completions) |
| [ModelFailoverSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ModelFailoverSpec.scala) | [Current identity](../tool/README.md#provider-tool-schema-strictness) |
| [JsonBoundarySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/json/JsonBoundarySpec.scala) | `aca1f4ce72d66ff5f6f28ec0d13dc767a14a3800` |

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for the ten core identities, with no findings. The core section of `exact-json-focused-red.log` passed 34 tests in 988 ms after correction. Existing seed/config/projection/serialized-request tables and the unchanged exact-renderer regression cover omission, zero, Long endpoints, escaping, nested values, scale and stack-safe traversal.
- [x] Full formatting and verification passed in `exact-json-verify.log` with zero compiler warnings or failures; aggregate totals are in the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance). MCP envelope integration has its separate [protocol acceptance](../../../../../../../../../mcp/src/main/scala/com/weisgnosis/agents/mcp/protocol/README.md#exact-envelope-rendering).

## Optional frequency and presence penalties

LlmConfig and ModelRequestSettings carry optional frequencyPenalty and presencePenalty, both defaulting to None. ChatCompletions emits frequency_penalty and presence_penalty only for supplied values, including explicit zero. Each value must be finite and in the inclusive range [-2, 2]; validation rejects invalid values without clamping or changing the selected endpoint. LlmConfig JSON uses camelCase fields; missing or explicit-null values decode as None. The derived configuration codec represents data, while request encoding and runtime projection enforce these bounds.

Each primary/fallback target supplies its own initial settings to the scoped projection. Projection replaces the optional values, so None clears a configured penalty. Validation occurs after projection cleanup and before request events or custom-client dispatch, including invalid original settings through legacy transforms. Structured native/synthetic requests retain the projected values; transport retries reuse the encoded request. Tools, choice, response format and client authority remain unchanged. No penalty fields are added to subagent declaration YAML.

Migration: Current LlmConfig and ModelRequestSettings arities are recorded in the [seed migration](#optional-seed-and-exact-request-rendering). Appended defaulted fields preserve ordinary omitted-argument construction and legacy configuration JSON. Positional patterns and constructor method values may need adaptation; rebuild dependent binaries. Custom clients remain responsible for interpreting these model settings.

| Reviewed file | Git blob |
| --- | --- |
| [ModelInputTransform.scala](../agent/ModelInputTransform.scala) | [Current identity](#optional-reasoning-effort) |
| [ReActAgent.scala](../agent/ReActAgent.scala) | [Current identity](#optional-reasoning-effort) |
| [ChatCompletionsRequest.scala](ChatCompletionsRequest.scala) | [Current ordinary-JSON identity](#ordinary-json-completions) |
| [LlmClient.scala](LlmClient.scala) | [Current identity](#optional-reasoning-effort) |
| [ModelInputTransformSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ModelInputTransformSpec.scala) | [Current identity](#optional-reasoning-effort) |
| [ReActAgentModelInputSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentModelInputSpec.scala) | [Current ReActAgentModelInputSpec.scala identity](../tool/README.md#checked-registry-identity) |
| [ChatCompletionsRequestSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsRequestSpec.scala) | [Current ordinary-JSON identity](#ordinary-json-completions) |
| [ModelFailoverSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ModelFailoverSpec.scala) | [Current identity](../tool/README.md#provider-tool-schema-strictness) |

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for these eight identities, with no findings. Existing tables cover omission, zero, endpoints, nonfinite/out-of-range values, codec defaults, projection override/clearing and validation before custom dispatch, per-target selection, retry reuse and structured requests. No new test groups were added. Focused `penalties-numeric-focused.log` passed 37 tests in 1.186 seconds.
- [x] Formatting and full verification passed in `penalties-verify.log`, with zero compiler warnings or failures. Current aggregate evidence and environment are in the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance). The [parity mapping](../../../../../../../../../../docs/architecture/test-parity.md) keeps seed and broader generation-option behavior separate.

## Host-selected tool choice

LlmConfig.toolChoice defaults to Option.None, which omits tool_choice. Explicit ToolChoice.Auto, None and Required encode as auto, none and required; Specific(name) encodes the function-selection object. Auto and None are omitted when no tools are advertised. Required needs a nonempty visible definition set; Specific requires a syntactically valid exact name in that set. Validation rejects hidden, missing or invalid targets before HTTP acquisition. Definitions remain unchanged. The absent default intentionally differs from Java's null-to-auto helper behavior.

This is provider steering, not permission or completion authority. A provider may still return a call despite none; runtime visibility, ordinary authorization and structured capture remain authoritative. Request projection cannot change the choice. Each selected primary/fallback target retains its own configured choice; retries reuse the same encoded body, and native/synthetic requests retain the choice without overriding it to force capture. The host must select hints compatible with its structured-output mode; hints that prevent capture remain subject to the existing iteration and timeout bounds. Provider compliance is not guaranteed. Custom LlmClient implementations interpret this configuration themselves; the validation/encoding contract belongs to ChatCompletions.

Migration: LlmConfig has a defaulted toolChoice; its current positional/Product shape is recorded in the [settings migration](#optional-frequency-and-presence-penalties). Existing omitted-argument construction and old missing/JSON-null toolChoice decode as None; explicit enum cases round-trip through the domain codec. Constructor method values may need adaptation and dependent binaries must be rebuilt. Domain toolChoice JSON is distinct from provider tool_choice encoding.

| Reviewed file | Git blob |
| --- | --- |
| [ChatCompletionsRequest.scala](ChatCompletionsRequest.scala) | [Current ordinary-JSON identity](#ordinary-json-completions) |
| [LlmClient.scala](LlmClient.scala) | [Current identity](#optional-reasoning-effort) |
| [ChatCompletionsClientSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsClientSpec.scala) | [Current identity](#cache-usage-decoding) |
| [ChatCompletionsRequestSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsRequestSpec.scala) | [Current ordinary-JSON identity](#ordinary-json-completions) |
| [ModelFailoverSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ModelFailoverSpec.scala) | [Current identity](../tool/README.md#provider-tool-schema-strictness) |

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for these five identities, with no findings. Existing tables cover all choices, empty definitions, exact visibility rejection and codec defaults. The extended real HTTP cycle verifies that a returned call despite none still passes through host authorization, Specific cannot bypass host denial, and hidden Specific performs no HTTP/authorization. Controlled failover/structured requests retain each target's own choice. Focused tool-choice-focused.log passed 31 tests in 7.402 seconds; no new groups were added.
- [x] Full scalafmtAll scalafmtSbt verify passed in tool-choice-verify.log with zero compiler warnings or failures. Current aggregate evidence is in the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).

## Optional nucleus sampling

LlmConfig.topP is optional, defaulting to None. ChatCompletions emits top_p only for Some(value), requiring a finite value in the inclusive range [0, 1]; None omits the field. There is no provider-specific clamping, inferred default or endpoint/capability change. Domain JSON uses topP; old missing or explicit-null values decode as None.

ModelRequestSettings carries the selected target's topP into the scoped request projection. Its output replaces that target's setting: None explicitly clears it. Validation runs after projection cleanup and before request events or custom-client dispatch; primary and fallback start from their own configurations, and transport retries reuse the encoded request. SubagentDeclaration.topP instead treats None as inheritance and Some as a child override. Its checked YAML profile accepts top_p or topP, rejects simultaneous key presence even when either value is null, and treats omitted/plain-null as inheritance; quoted numeric scalars parse, while quoted null and malformed/nonfinite/out-of-range values fail. It grants no execution authority.

Migration: the appended defaulted fields preserve ordinary omitted-argument construction and legacy JSON. SubagentDeclaration retains nine positional/Product fields; ModelRequestSettings and LlmConfig follow the [current settings migration](#optional-frequency-and-presence-penalties); constructor method values may need adaptation and dependent binaries must be rebuilt. Declaration validation and request validation remain explicit boundaries rather than validation performed by the derived data codecs.

| Reviewed file | Git blob |
| --- | --- |
| [ModelInputTransform.scala](../agent/ModelInputTransform.scala) | [Current identity](#optional-reasoning-effort) |
| [ReActAgent.scala](../agent/ReActAgent.scala) | [Current identity](#optional-reasoning-effort) |
| [ChatCompletionsRequest.scala](ChatCompletionsRequest.scala) | [Current ordinary-JSON identity](#ordinary-json-completions) |
| [LlmClient.scala](LlmClient.scala) | [Current identity](#optional-reasoning-effort) |
| [ModelInputTransformSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ModelInputTransformSpec.scala) | [Current identity](#optional-reasoning-effort) |
| [ReActAgentModelInputSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentModelInputSpec.scala) | [Current penalty-scope identity](#optional-frequency-and-presence-penalties) |
| [ChatCompletionsRequestSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsRequestSpec.scala) | [Current ordinary-JSON identity](#ordinary-json-completions) |
| [ModelFailoverSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ModelFailoverSpec.scala) | [Current identity](../tool/README.md#provider-tool-schema-strictness) |
| [SubagentDeclaration.scala](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/subagent/SubagentDeclaration.scala) | bf55c6a99b8c35bfaa28bd15eb8fba2617ad9faa |
| [SubagentDeclarationParser.scala](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/subagent/SubagentDeclarationParser.scala) | 2033e68dc48552d838cf7f96b7a53d9e1a2df45c |
| [SubagentDeclarationParserSpec.scala](../../../../../../../../../harness/src/test/scala/com/weisgnosis/agents/harness/subagent/SubagentDeclarationParserSpec.scala) | 3143e211c09c6a1f58b9eaaa4afd0e88868bbfa1 |
| [SubagentDeclarationSpec.scala](../../../../../../../../../harness/src/test/scala/com/weisgnosis/agents/harness/subagent/SubagentDeclarationSpec.scala) | [Current SubagentDeclarationSpec.scala identity](../tool/README.md#checked-registry-identity) |

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for these twelve identities, with no findings. Existing tables cover omission/endpoints, strict invalid values, original invalid custom-client configuration, per-target projection and clearing in serialized requests, declaration inheritance/override, JSON compatibility and YAML alias conflicts. The scope extends existing test groups. Focused top-p-focused.log passed 37 core tests in 1.023 seconds and seven harness tests in 485 ms; full scalafmtAll scalafmtSbt verify passed in top-p-verify.log with zero compiler warnings or failures. Current aggregate evidence is in the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).

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

StreamAccumulator is now privately constructed through empty. feed returns Either[AgentError, StreamAccumulator], and toTurnOutput returns Either[AgentError, TurnOutput]; replace former fluent calls with flatMap. The former public case-class construction/copy and intermediate fields are removed. Normal completion requires stop with no tools or tool_calls with tools. Missing, repeated, truncated, filtered, or inconsistent completion markers cannot yield executable success. Usage snapshots may follow a finish marker; other later deltas fail.

Tool fragments remain keyed and emitted in numeric streamIndex order. IDs/names may arrive after argument fragments but must be nonblank, stable, present at completion, and IDs must be unique across completed calls. Arguments must be a complete JSON object with unique keys. Valid assembled raw text is retained in ToolUse.content exactly. Normal malformed/non-object/trailing input fails; toInterrupted instead sanitizes both input and content to {} and uses a deterministic placeholder only when the call ID has not arrived. This produces TurnOutput.Interrupted for reconciliation, never a completed executable turn.

Text and thinking use separate index namespaces and retain first-appearance block order. Thinking signatures concatenate exactly, including signature-only blocks and whitespace; this covers ThinkingDelta assembly, not provider thinking-signature decoding. Tool blocks follow these content blocks in numeric order. Usage is the latest complete snapshot, never a sum of streaming snapshots.

JsonBoundary is a library-internal shared boundary in the independent json package. zio-json's ordinary decoder permits unconsumed suffixes, so a pure tail-recursive boundary scanner checks the full document, JSON whitespace, container/string escapes, and complete primitive tokens. AST parsing validates grammar; a second tail-recursive traversal rejects duplicate fields. This prevents response frames, tool arguments, and structured text from hiding trailing garbage. Existing request/metadata/captured ASTs use direct duplicate-field validation without a serialization round trip.

## Request serialization

ChatCompletionsRequest.encode returns Either[AgentError, Json.Obj] without effects. It serializes ordered text and user image/audio messages, including explicitly typed inline Data blocks, function definitions with optional host-selected strictness, canonical tool argument ASTs, streaming usage options, and an optional native response_format object. Credentials, endpoint, runtime identity, timestamps, usage, and unrelated message metadata are not request-body fields. ToolDef.returnMode is local runtime policy and is omitted from provider definitions. ToolDef.outputSchema remains descriptive metadata for custom clients; this encoder emits no output_schema extension. Its omission is covered by the [output-schema contract](../tool/README.md#descriptive-output-schemas).

History must contain complete local call/result pairs. Orphan, duplicate, unknown, and unresolved results fail before serialization; another conversation message cannot split pending calls from their observations. Grouped or separately delivered parallel results may arrive in any order, and completed later turns may reuse an ID. A grouped Tool message becomes individual protocol messages. Error, Interrupted, and Denied outputs carry explicit labels; Running and suspended observations fail.

ReasoningReplay.Omit is the default. LatestAssistant replays unsigned thinking only from the last assistant message through reasoning_content. OpenRouterEncryptedDetails validates and replays encrypted detail arrays from every historical assistant message. OpenRouterDetails additionally supports checked text and summary objects; other detailed/signed thinking formats remain unsupported. Unsupported media, provider-hosted server calls/results, calls signed with an unsupported format, and non-text tool output fail explicitly. User-media projection is defined below; broader provider formatting remains separate.

Validation covers nonblank models, finite temperature in [0, 2], positive optional token limits, protocol names, unique definitions/call IDs, object schemas/arguments/response formats, and duplicate JSON fields. ToolUse.input is authoritative; stale raw content is not sent. Forwarding a schema object does not validate arbitrary JSON Schema semantics. Endpoint, authentication headers, HTTP status handling, and request resource lifetime belong to ChatCompletionsTransport.

## User-image projection

User messages containing only text retain their original string wire shape. A message containing supported media becomes an ordered array of text, image_url and input_audio parts. HTTP(S) image URLs require a host and valid port, reject raw whitespace/control characters, and retain their original spelling. Inline images require an explicit image/png, image/jpeg, image/webp or image/gif media type and nonempty standard Base64 syntax. Image, audio and Data IDs and names are not provider fields. Invalid or unsupported blocks reject the whole request; no block is silently dropped or replaced with a placeholder.

Inline audio accepts only MediaSource.Base64 with exact audio/wav or audio/mpeg MIME values, projecting format wav or mp3 respectively and preserving the original Base64 string. Payloads require the same nonempty standard Base64 syntax as inline images. Audio URLs, MIME aliases, parameters and other formats fail; the codec never guesses a format or substitutes a text reference. The [official input-audio schema](https://raw.githubusercontent.com/openai/openai-python/main/src/openai/types/chat/chat_completion_content_part_input_audio_param.py) specifies input_audio.data and format wav|mp3; the exact MIME mapping is local boundary policy.

User ContentBlock.Data accepts inline Base64 with those same four image MIME types or audio/wav and audio/mpeg. Data URLs require an explicit MediaSource.Url.mimeType of image/png, image/jpeg, image/webp or image/gif and pass the existing HTTP(S) validator. The original URL is retained, including a misleading extension; no MIME type is inferred. The hint, ID and name are omitted from provider JSON. An explicit Image block keeps its existing URL behavior regardless of the descriptive hint. Missing/unsupported Data hints, audio/video Data URLs and malformed URLs reject the whole request. Inline Data continues to use the same image_url/input_audio validators and original block order.

This is pure serialization: it does not fetch URLs, read files, decode media bytes, inspect audio headers/duration, detect animation or validate provider size limits. In particular, accepting image/gif does not establish that the image is non-animated. Image/audio/Data content in system, assistant and tool messages remains unsupported; video and other Data media are also rejected. Standard tool content stays text-only. The [official user schema](https://raw.githubusercontent.com/openai/openai-python/main/src/openai/types/chat/chat_completion_user_message_param.py), [image-part schema](https://raw.githubusercontent.com/openai/openai-python/main/src/openai/types/chat/chat_completion_content_part_image_param.py), [tool-message schema](https://raw.githubusercontent.com/openai/openai-python/main/src/openai/types/chat/chat_completion_tool_message_param.py) and [image guide](https://developers.openai.com/api/docs/guides/images-vision) define the selected provider surface. This does not imply compatibility with every endpoint accepting Chat Completions requests.

Migration: MediaSource.Url appends mimeType: Option[String] = None with canonical mime_type JSON. Missing/null becomes None and None is omitted; invalid or duplicate canonical fields fail. Two-field Url patterns/Product and downstream binaries require updating. Defaulted construction calls remain valid; MediaSource.url now has two parameters, so method-value users must adapt rather than assuming the old function arity. Previously rejected supported user media now serialize; text-only requests and unsupported-role rejection retain their existing behavior. Full provider media validation remains the caller/provider responsibility.

Existing tables verify six inline MIME mappings, four image URL hints on a misleading .mp3 path, extensionless mixed content, ignored descriptive hints on explicit Image blocks, local-field omission and whole-request rejection. The existing MediaSource codec group covers concrete/base round-trips, omitted/null hints and duplicate/nonstring rejection. There are no new test groups. Existing typed image/audio, signature and reasoning behavior remains unchanged.

| File | Reviewed Git blob |
| --- | --- |
| [ChatCompletionsRequest.scala](ChatCompletionsRequest.scala) | [Current ordinary-JSON identity](#ordinary-json-completions) |
| [ChatCompletionsRequestSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsRequestSpec.scala) | [Current ordinary-JSON identity](#ordinary-json-completions) |
| [MediaSource.scala](../model/MediaSource.scala) | a443433df837fc8d4eb4b714664b90b2a7bcef3c |
| [MediaSourceSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/model/MediaSourceSpec.scala) | ac9619600ec48fe7f0c5713ae7f34c8f8c0f6ad6 |

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for these four identities, with no findings. Focused checks passed 23 tests in 282 ms (`mime-hint-focused.log`).
- [x] Full `scalafmtAll scalafmtSbt verify` passed in `mime-hint-verify.log` with zero compiler warnings or failures; current aggregate totals are in the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).
- [ ] Audio output, URL retrieval, additional media/provider formats and live-provider interoperability remain unfinished.

## Tool-call signatures

ToolSignatureFormat.Unsupported is the default. A host may explicitly choose GoogleExtraContent through ChatCompletionsClient.make or ChatCompletionsTransport.make; the transport uses the same immutable choice for response decoding and every historical request call. The selected wire field is tool_calls[].extra_content.google.thought_signature, following the [Google OpenAI-compatibility signature contract](https://ai.google.dev/gemini-api/docs/generate-content/thought-signatures#signatures-for-openai-compatibility). This does not enable generic provider metadata, ThinkingBlock signatures, reasoningDetails or server-hosted calls.

The decoder accepts an absent/JSON-null extra_content as absent. Present extra_content and google containers must be objects; a supplied thought_signature must be a nonblank string. Unsupported rejects a recognized Google signature before dispatch. Unknown extension fields remain ignored, apart from duplicate-key rejection; they cannot grant server-tool authority. The opaque string is retained exactly, without decoding or concatenation. Each stream index may receive the same value again but cannot change it. Completed and interrupted ToolUse metadata retain thoughtSignature, including when interruption sanitizes unfinished arguments to {}. Parallel calls never inherit another call's signature.

GoogleExtraContent replays that metadata on its original call across all historical assistant messages, independently of ReasoningReplay's latest-thinking policy. Other metadata is not copied to the wire. Malformed reserved signature metadata fails instead of being silently omitted. The existing runtime guard denies argument rewrites on signed calls, preserving the original canonical input; signatures are protocol data and confer no execution permission. Raw diagnostic frames may contain signatures; the added validation messages do not quote signature values.

Migration: LlmChunk.ToolCallChunk appends defaulted thoughtSignature: Option[String]; old JSON omitting it still decodes to None, while positional extractors/Product now have five fields. encode and both make methods append a defaulted format argument, preserving ordinary source calls; method values may need adaptation and dependent binaries must be rebuilt. ReasoningModelAdapter retains its one-argument parseChunk overload and adds an explicit-format overload. No ownership, retries or provider discovery are introduced.

Unchanged signature-scope identities remain below; current request/spec identities are in [user-media projection](#user-image-projection), and other changed client identities are in [selected reasoning](#encrypted-reasoning).

| File | Reviewed Git blob |
| --- | --- |
| ChatCompletionsClient.scala | [Current ordinary-JSON identity](#ordinary-json-completions) |

| [ReasoningAdapterValidationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ReasoningAdapterValidationSpec.scala) | [Current identity](#cache-usage-decoding) |

The signature contract retains its independent path_review/current_docs approval and signature-mcp-verify.log evidence. Its real HTTP fixture verifies per-call preservation and signed rewrite denial, not live Google interoperability.

## Encrypted reasoning

ReasoningReplay.OpenRouterEncryptedDetails selects response decoding and all-historical-assistant replay for reasoning.encrypted only. OpenRouterDetails additionally selects reasoning.text and reasoning.summary from the [OpenRouter reasoning-details contract](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens#reasoning-details-api-shape). Both preserve each checked Json.Obj exactly, including absent fields and explicit nulls.

ReasoningDetail accepts a strict local union: encrypted requires nonblank opaque data; text requires a text string and permits an absent, null or nonblank opaque signature; summary requires a summary string, not Java's legacy text field. Empty text and summary strings are valid. Each variant permits optional id (nonblank string or null), format (nonblank string) and index (nonnegative Int JSON integer). Duplicate, unknown, misplaced or malformed fields fail. EncryptedReasoningDetail retains its narrower validation and provides toDetail. Data and signatures are neither decoded nor concatenated; IDs/indexes never infer tool identity or merge objects.

ReasoningDetailsDelta retains objects in received order, including repeated IDs/indexes. Assembly stores one Thinking block at the first detail occurrence, with the exact reasoningDetails array and concatenated text/summary plaintext. Encrypted objects contribute no plaintext. Explicit [] is retained and replayed; absent/null wire fields remain absent. The general decoder treats any supplied array, including [], as authoritative over same-frame scalar reasoning_content/reasoning/thinking aliases. Without an array, nonempty scalar aliases fail under OpenRouterDetails; empty strings are allowed and whitespace is nonempty. This avoids cross-frame alias deduplication and deliberately limits provider compatibility. Other profiles retain their scalar behavior. Direct accumulator callers cannot mix scalar ThinkingDelta blocks and details with nonempty plaintext in either order; opaque-only details and [] remain compatible with scalar blocks.

Selected replay validates original history and emits ordered arrays on every original assistant message, never on individual tool calls. It emits no reasoning_content and enables no other Thinking metadata. Unsupported replay profiles reject supplied arrays, including []. General detail plaintext produces one consistent Thinking event stream and canonical projection; encrypted-only details emit no Thinking events. Raw diagnostic frames remain unredacted. Google per-call signatures remain a separate host-selected format.

A nonempty detail array or a nonblank Thinking signature counts as reasoning for the bounded thinking-only reminder; [] alone does not. Presence of either reserved reasoning key in any Thinking block protects every sibling call's original arguments, even for [] or malformed metadata. Fixed preparation and approved rewrites must preserve them. Older protected Thinking blocks survive pruneObsoleteReasoning, and the compactor's argument-pruning pass preserves sibling inputs/cached content. Whole-prefix summarization and trusted host history/model-input/post-reasoning callbacks remain replacement boundaries; this is not indefinite storage or permission authority.

Migration: exhaustive ReasoningReplay matches must handle OpenRouterDetails. ReasoningDetailsDelta.details now contains Chunk[ReasoningDetail], replacing Chunk[EncryptedReasoningDetail]; direct producers can map encrypted values through toDetail and must recompile. Existing make arguments, decoder overloads and default policies remain. Msg JSON is unchanged. Checked AST codecs preserve validated representation rather than normalizing a DTO. Invalid history fails before serialization instead of silently dropping metadata.

| File | Reviewed Git blob |
| --- | --- |
| ReActAgent.scala | [Current identity](#optional-reasoning-effort) |
| ChatCompletionsRequest.scala | [Current ordinary-JSON identity](#ordinary-json-completions) |
| ChatCompletionsTransport.scala | [Current ordinary-JSON identity](#ordinary-json-completions) |
| [LlmChunk.scala](LlmChunk.scala) | a35e034b66a8139d05ee1c6b1622ca19871538b1 |
| [ModelStreamPublisher.scala](ModelStreamPublisher.scala) | 35b65c607b31f46da8213f4ffcfb12a6d7439128 |
| [ReasoningModelAdapter.scala](ReasoningModelAdapter.scala) | [Current identity](#cache-usage-decoding) |
| [StreamAccumulator.scala](StreamAccumulator.scala) | [Current identity](#completion-envelope-profiles) |
| [Msg.scala](../model/Msg.scala) | [Current identity](../json/README.md#exact-domain-and-tool-output) |
| [ReActAgentReasoningHookSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentReasoningHookSpec.scala) | [Current ReActAgentReasoningHookSpec.scala identity](../tool/README.md#checked-registry-identity) |
| [ReActAgentToolAuthorizerSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentToolAuthorizerSpec.scala) | [Current ReActAgentToolAuthorizerSpec.scala identity](../tool/README.md#checked-registry-identity) |
| ChatCompletionsClientSpec.scala | [Current identity](#cache-usage-decoding) |
| [ReasoningAdapterSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ReasoningAdapterSpec.scala) | 1f17e10621d99b2cd79f0382f7c4cc7255b68cf0 |
| [StreamAccumulatorSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/StreamAccumulatorSpec.scala) | e90bd63fa63fa74f395d557a5199093dc77f8954 |
| [ConversationCompactor.scala](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/memory/ConversationCompactor.scala) | [Current identity](../json/README.md#exact-domain-and-tool-output) |
| [ConversationCompactorSpec.scala](../../../../../../../../../harness/src/test/scala/com/weisgnosis/agents/harness/memory/ConversationCompactorSpec.scala) | [Current identity](../json/README.md#exact-domain-and-tool-output) |
| [EncryptedReasoningDetail.scala](EncryptedReasoningDetail.scala) | 49a0287e2476b00943ff69f480f4ebca2ee46c37 |
| [EncryptedReasoningDetailSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/EncryptedReasoningDetailSpec.scala) | f6e5ec577c8f7da54c0e8f582321465cf693af16 |
| [ReasoningDetail.scala](ReasoningDetail.scala) | 3cb2bbf21dbc02fe65b21435e962ca061eecff63 |
| [ReasoningDetailSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ReasoningDetailSpec.scala) | [Current identity](#scalar-reasoning-aliases) |

- [x] path_review approved FP/ZIO correctness and current_docs approved invariants for the nine-file general-details scope at the identities above, with no open findings. Full scalafmtAll scalafmtSbt verify passed in reasoning-details-verify.log with zero compiler warnings or failed tests. Focused reasoning-details-green.log passed 39 tests in 5.262 seconds. Unchanged encrypted-reasoning contracts retain their prior acceptance; current aggregate totals belong to the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).
- [x] Two new pure groups and the existing HTTP table cover checked variants, literal null/empty values, scalar precedence, both mixed-chunk orders, exact historical replay and canonical/event agreement. The HTTP rows retain sibling-input protection for both selected profiles. This is loopback evidence, not live OpenRouter interoperability.
- [ ] Other provider reasoning/signature formats and exhaustive upstream formatter parity remain unfinished; ordinary JSON completion has its separate accepted scope above.

The public mixed-chunk regression in ReasoningDetailSpec 99be2c6d4da164e11fe9f946bf681e5297f710ba failed against accumulator 504510573cf7e492f3ee2fc530dd9d4e81d8397a in reasoning-details-mixing-red.log (11 passed, one failed, 5.136 seconds): both scalar/plaintext-detail orders were accepted instead of rejected. The same spec passed after the guard; opaque-only and empty-array near-misses remain permitted.

## Scoped HTTP completion

ChatCompletionsTransport.make accepts an injected zio-http Client, positive CompletionTransportLimits, ReasoningReplay, ToolSignatureFormat, a default-disabled CompletionRetryPolicy and an explicit CompletionEnvelopeProfile. complete returns IO[AgentError, TurnOutput] and owns a separate Scope for each HTTP attempt. Success, failure, and cancellation close the attempt scope before returning or retrying; cancellation stays interruption rather than a partial successful turn. The injected client layer and its connection pool belong to the host.

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
| ChatCompletionsTransport.scala | [Current ordinary-JSON identity](#ordinary-json-completions) |
| ChatCompletionsClient.scala | [Current ordinary-JSON identity](#ordinary-json-completions) |
| [CompletionRetryPolicySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/CompletionRetryPolicySpec.scala) | 5df81123863d6f3faa10cfd1c70206f2604baf63 |
| [ChatCompletionsTransportSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsTransportSpec.scala) | cb042aee28d6e082c9688481f4b08c0e1540df1d |
| [ChatCompletionsClientSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsClientSpec.scala) | [Current identity](#cache-usage-decoding) |

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

The whole provider frame is decoded and checked against stream progress before any transformation; ordinary JSON validates its complete body at bounded EOF before transforming visible text. Only visible text changes: indexes, thinking, tool arguments, usage, and finish markers remain authoritative protocol data. Replacements feed the accumulator and typed text events together, so final text, TextBlockEnd, history, and structured extraction agree. ModelResponseChunk remains the original raw frame for diagnostics; it is not a redacted or transformed payload. Empty replacements remain valid empty deltas. Stateful transformations run sequentially; their external effects cannot be rolled back if a later callback fails.

The callback error channel accepts only TextTransformationError. Host operations must map external errors explicitly; provider ModelError cannot enter this interface and accidentally trigger Auto native-format fallback. A failed transformation publishes neither that frame nor its typed deltas. Earlier accepted events remain visible. TextTransformationError and interruption propagate through existing runtime handling, with no false model/content completion. A valid finish remains mandatory in both modes. SSE additionally requires a blank-line-terminated [DONE]; ordinary JSON requires bounded body EOF and complete full-envelope validation. Transformed native JSON still passes strict structured decoding. HTTP response-byte limits bound incoming bytes, not expansion performed by a trusted host transform.

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
| LlmClient.scala | [Current identity](#optional-reasoning-effort) |
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
| [ChatCompletionsTransformSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsTransformSpec.scala) | [Current ChatCompletionsTransformSpec.scala identity](../tool/README.md#checked-registry-identity) |

The ReActAgent capability identity is preserved in `02b4654`, and the AgentError text-transformation identity in `2a400e0`. Their subsequent post-reasoning changes have current identities in the [agent acceptance](../agent/README.md).

The changed AgentErrorSpec.scala identity from the earlier table is preserved in `d1a4cf2`; current local-memory error acceptance is in the [memory contract](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md).

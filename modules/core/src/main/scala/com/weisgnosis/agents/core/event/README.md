# Typed agent event payloads

## Incremental and accumulated thinking

ThinkingBlockDeltaEvent keeps its exact `delta` and adds trailing `accumulatedThought: Option[String] = None`. The built-in model publisher supplies Some(prefix) after applying that fragment to the same block index. Prefixes are independent across interleaved indices and reset with each request publisher. A valid empty raw thinking fragment still has a defined prefix. Reasoning-details events project only their existing admitted plaintext; opaque encrypted/signature data does not become text or produce a plaintext delta by itself.

Hosts can use incremental fragments or the accumulated prefix without selecting a runtime execution mode. Source tagging and correlation preserve both. Custom event publishers may omit the prefix; None means it was not supplied, rather than an empty accumulated value. Final block text and canonical model history retain their existing assembly semantics.

Migration: existing constructor calls retain the trailing default, and old JSON without the field decodes None. Populated fields round-trip through the derived codec; malformed supplied values fail decoding. Positional patterns/product arity and JVM constructor/apply/copy signatures change, so dependent binaries must be rebuilt. The added wire field increases event payload size: cumulative transmission grows with the sum of prefix lengths and may be quadratic for many small fragments. Subscriber retention depends on the host; no constant-memory or arbitrary truncation guarantee is supplied.

The pinned ReActAgentThinkingCumulativeTest methods at lines 65 and 113 require successive prefixes and exact fragments respectively. This is an event-stream adaptation of those observations, not the Java ReasoningChunkEvent middleware API. ModelStreamPublisherSpec checks interleaved prefixes/fragments, empty fragments and unchanged block ends; AgentEventSerdeSpec checks old/new wire data and source copying; ChatCompletionsTransformSpec checks the public ReAct stream; ReasoningDetailSpec checks admitted plaintext and opaque exclusion. Existing groups are extended without adding mechanical test counts.

| Current reviewed file | Git blob |
| --- | --- |
| [ModelStreamPublisher.scala](../client/ModelStreamPublisher.scala) | `6bde0acbd255025c54e9592c7a8d94666f30abe8` |
| [AgentEvent.scala](AgentEvent.scala) | `e09c26385144e6d05f0a9b5798a5f3413a95819c` |
| [ChatCompletionsTransformSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsTransformSpec.scala) | `76c35efc086f16a17247756b9cf6cbb63f45531b` |
| [ModelStreamPublisherSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ModelStreamPublisherSpec.scala) | `2936fd12ea88af9933746f360639d127d060c145` |
| [ReasoningDetailSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ReasoningDetailSpec.scala) | `4a4f73eb589d967ad6f72f3999b853f3043df3b1` |
| [AgentEventSerdeSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/event/AgentEventSerdeSpec.scala) | `7459ccf6a440c2f9e1a59ce9314c7bb0abb4cfb6` |

- [x] review_fp and review_invariants approved all six exact Scala identities against 4cc1225 with no unresolved source findings. Sorted source manifest SHA-256: 1ef611a14703ea0040dd70689393520b88cef30942d4d61b961a90a5a45bcf0d.
- [x] thinking-prefix-focused-green.log passes 29 groups in 3.414 seconds, including existing encrypted-reasoning coverage selected by the wildcard. thinking-prefix-full-verify.log passes formatting, fatal-warning compilation, all 1,581 tests (687 core / 734 harness / 130 MCP / 22 fresh PostgreSQL / 8 examples), zero failures/ignored and four RC1 packages. The owned empty PostgreSQL fixture was stopped after label validation. thinking-prefix-preparation.log passes source/POM/LICENSE admission and three reversible negative archive checks.
- [x] Confirm fresh standalone artifact consumption in thinking-prefix-consumer.log. The unchanged external probe checks existing APIs and four hashes; per-chunk prefix behavior is verified in the core integration suites.
- [x] review_fp and review_invariants approved final combined documentation against 4cc1225 for six unchanged Scala files and fourteen Markdown files, with no unresolved findings in this scope.

## Existing event contracts

Four tool-result event metadata decoders retain numeric AST leaves directly. review_fp and review_invariants approved the final correction with no findings; the [JSON acceptance](../json/README.md#verification-and-acceptance) records exact source/spec identities and full verification.

Generic PermissionAskEvent and PermissionDecisionEvent runtime integration is accepted in the [checkpoint contract](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#checkpoint-permission-events). Ask follows successful batch cleanup and precedes RequestStop; a recorded resumed authorization decision precedes tool Start. UUID correlation is data, not evidence of a human response; event codecs are unchanged.

AgentEvent defines 39 immutable payload variants with JSON codecs and withSource tagging. Available runtime components publish through ZIO Hub, but class/codec support does not imply every event is emitted. Serialization never allocates IDs or reads the clock.

- [ModelStreamContext](ModelStreamContext.scala)
- [AgentEvent](AgentEvent.scala)
- [AgentEventType](AgentEventType.scala)

AllToolsDeniedEvent has [runtime integration](../agent/README.md#all-tools-denied-observation) after successful complete-local-batch cleanup and Deferred/Stop resolution. Its original-ordinal payload is observational: it neither stops execution nor proves a human decision. Codecs remain unchanged.

## Optional final-answer filter

[FinalAnswerFilter.make](FinalAnswerFilter.scala) returns an Either containing a reusable ZPipeline, with fresh immutable state per subscription. For an existing agent:

```scala
for
  filter <- ZIO.fromEither(FinalAnswerFilter.make())
  events <- agent.streamEvents(Msg.user("question")).via(filter).runCollect
yield events
```

The host explicitly applies this presentation filter; default runtime delivery is unchanged. Correlated TextBlock Start/Delta/End events are buffered by exact optional source and ModelStreamContext until ModelResponseEnd. A matching ToolCallStart discards buffered text and suppresses subsequent text in that round; a tool-free round flushes text immediately before its matching model End. Concurrent requests and nested sources remain independent. A repeated start replaces only that request's buffer. Events without a matching active request pass through. AgentEnd clears abandoned buffers for its source/reply; EOF or failure never flushes unfinished text.

Positive limits default to 4,096 buffered text events, 1,048,576 UTF-16 code units and 64 active requests across the whole subscription. Character accounting includes both delta strings and completed fullText, even when they repeat content. Suppression releases buffered event/character counts but retains the active-request slot. Matching model completion or AgentEnd removes the request; a repeated start resets its buffer while retaining one slot. Invalid limits return InvalidConfiguration; exceeding a limit fails the pipeline with InvalidMessage. Upstream causes remain intact and cancellation joins upstream cleanup; the filter creates no worker or execution owner.

Thinking, raw diagnostic frames, tool/result events and AgentResult pass unchanged. Canonical history and subsequent model requests retain intermediate text. This is not secret redaction or an authoritative final-result selector: a tool-free reasoning round can still be followed by further runtime work. The additive pipeline has no wire codec, installs no middleware automatically and changes no event/runtime API.

| Reviewed file | Git blob |
| --- | --- |
| [FinalAnswerFilter.scala](FinalAnswerFilter.scala) | `9040027e6836ab3694c3a389c3f55de0589ef0e1` |
| [FinalAnswerFilterSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/event/FinalAnswerFilterSpec.scala) | [Current FinalAnswerFilterSpec.scala identity](../tool/README.md#checked-registry-identity) |

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for this pair and removal of the redundant primitive-stream suite, with no findings. Three grouped tests cover isolation/global limits and reset behavior, an actual HTTP tool cycle retaining history/diagnostics/results, and exact failure causes plus joined held cancellation. Existing all-variant serde and actual runtime/forwarding suites retain source-tagging and delivery coverage. Focused `final-answer-filter-final-focused.log` passed three tests in 3.389 seconds.
- [x] Full formatting, `verify` and `packageArtifacts` passed in `final-answer-filter-verify.log`, with zero compiler warnings or failures. Packaged consumer execution also exercised the new filter; [build acceptance](../../../../../../../../../../project/README.md#local-artifact-packaging) records artifact boundaries, and the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance) owns current totals.

## Codec and metadata contracts

All 39 concrete/base codecs encode the same object with exactly one lowercase event discriminator. Concrete decoders also read legacy untagged payloads; a supplied tag must match. Base decoding requires a unique valid tag. Missing required fields, malformed tags, and duplicate discriminators fail. Immutable source updates preserve every other field.

ToolResultStartEvent, ToolResultDeltaEvent, ToolResultEndEvent, and ToolResultDataDeltaEvent retain immutable metadata independently of source. Their private payload decoder extracts object members directly without an AST-to-text round-trip; missing/JSON-null metadata defaults to empty, malformed nonobjects fail and duplicate map keys retain last-wins behavior. Exact numeric host serialization remains opt-in through [ExactJson.encode](../json/README.md#exact-domain-and-tool-output). Inline provider text/data events now preserve complete result metadata in output order; processed completed local output blocks retain metadata; provider multimodal projection remains pending; incremental local execution has separate accepted runtime evidence below. Isolated private payload derivation and a suspended root codec prevent encoder cycles; there is no silent AST encoding fallback.

## Additional payloads

| Variant | Fields |
| --- | --- |
| AgentResultEvent | Complete validated result Msg |
| DataBlockStartEvent / DataBlockEndEvent | replyId, blockId |
| DataBlockDeltaEvent | replyId, blockId, delta String |
| ToolResultDataDeltaEvent | replyId, toolCallId, toolCallName, arbitrary ContentBlock data, metadata |
| ExceedMaxItersEvent | replyId, maxIters, currentIter |
| RequestStopEvent | reason, typed generateReason; absent/JSON-null classification defaults to MiddlewareStopRequested |
| AllToolsDeniedEvent | Immutable deniedToolCalls; absent/JSON-null collection defaults to empty |

Supplied malformed reasons/collections fail decoding. Result messages and data blocks retain their nested validation. RequestStop describes a requested phase stop; receiving or decoding it does not cancel a fiber, suspend a phase, or resume pending work. The runtime publishes AgentResult after structured preparation and history commit, then AgentEnd; pre-commit failures publish no finalized result.

## Protocol identifiers and compatibility

AgentEventType remains a separate 31-name uppercase identifier contract with 11 aliases. fromValue is a pure lookup returning Either[AgentError, AgentEventType]; matching is case-sensitive and encoding uses the canonical uppercase name. Scala's 39 payload variants include extensions and differ from this identifier surface.

Existing lowercase event objects remain canonical. Concrete event encoding includes the discriminator; legacy untagged leaf decoding remains supported. The eight additions do not alter existing payload fields. Their presence does not translate upstream uppercase type objects or add the universal id/createdAt envelope. Explicit field/envelope adapters and effectful identity/time allocation remain separate work; full Java API/wire equivalence is not claimed.

## Current acceptance

- [x] `review_fp` and `review_harness` approved shared event codecs/metadata at `b2901eb` and protocol identifiers at `d3d003d`.
- [x] Both reviewers approved the final three-file payload increment against `66bb42d` on 2026-09-29; no findings remain in its scope.
- [x] AgentEventSerdeSpec exercises all 39 variants through concrete/base codecs, legacy leaf input, repeated source tagging, and strict discriminator validation.
- [x] AgentEventPayloadSpec adds seven payload tests, including toolResultDataDeltaEventMetadataRoundTrip, nested validation, immutable fields, and optional-value normalization.
- [x] Payload verification passed with zero compiler warnings; current repository totals belong to the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).
- [x] Correlated iteration-limit publication and inline provider text/data/metadata delivery are verified in ReActAgentEmissionSpec and reviewed in the [agent scope](../agent/README.md).
- [x] Independently review [bounded runtime ordering](../client/README.md#runtime-event-ordering) for current model/tool lifecycles and drained invocation boundaries.
- [ ] Define and implement model data-block production/encoding and remaining provider output-media projection.
- [x] Both reviewers and full verification accepted [host acting-stop publication](../agent/README.md#acting-stop-decisions).
- [x] Integrate generic checkpoint Ask/Decision and complete-batch AllToolsDenied publication under their linked runtime contracts.
- [ ] Integrate host-owned durable checkpoint storage and authenticated delivery; event data grants no authority.
- [x] Classify all ten deprecated coarse-event methods as excluded API/wire compatibility, without claiming their assertions pass.
- [x] Implement request/reply correlation for live model and content events.
- [ ] Complete explicit uppercase typed-payload/envelope adapters and remaining local tool/subagent attribution.

The [parity mapping](../../../../../../../../../../docs/architecture/test-parity.md) distinguishes the 13 enabled stream-test methods from seven disabled ordering placeholders and ten explicitly excluded deprecated coarse-event methods. Enabled payload/identifier method coverage is not runtime-ordering evidence. Earlier payload/identifier acceptance (the context increment below supersedes changed identities):

| File | Git blob |
| --- | --- |
| [AgentEvent.scala](AgentEvent.scala) | [Current numeric-metadata identity](../json/README.md#verification-and-acceptance) |
| [AgentEventType.scala](AgentEventType.scala) | 7d3238de6b51d9bb9df817dd4f866b91c993654c |
| [AgentEventSerdeSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/event/AgentEventSerdeSpec.scala) | e03c43bdbaf2d97c4e43d32ba3f2aa0c8700d46f |
| [AgentEventPayloadSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/event/AgentEventPayloadSpec.scala) | 0912c9e7f25e7e90440caeeb8aa3314eaa61488c |
| [AgentEventTypeSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/event/AgentEventTypeSpec.scala) | b881e98ba39ed97e292ac16acf3c4101f48b0d11 |

The deprecated v1 Event/EventType/EventSource envelope is outside this core API. Shared Msg/result preservation tests do not establish cumulative snapshots, isLast, filtering, Java diagnostics, or structured source compatibility. See the method-level decisions in the parity mapping. Richer typed subagent attribution remains planned.

- [x] `review_fp` and `review_harness` independently approved the deprecated-envelope scope decision against `e110321` on 2026-09-29 after inspecting all ten upstream methods. Approval covers applicability classification, not legacy API or wire implementation.

## Model stream context

Sixteen text/thinking/function/model/result event variants now carry optional ModelStreamContext(replyId, requestId). Legacy payloads without context still decode; malformed supplied context fails. Constructors append the optional field, so positional extractors must accept the additional argument. withSource retains correlation. Runtime model boundaries and the live client always supply context; arbitrary scripted event publishers remain responsible for theirs. The context constructor and codec allocate no identities or time.

ModelStreamContextSpec verifies all sixteen contextual root encodings, source preservation, legacy absence, and incomplete supplied context. See [live client ordering](../client/README.md); typed completed local blocks and metadata are supported; complete uppercase envelopes and provider multimodal projection remain pending; incremental local execution has separate accepted runtime evidence below.

Existing model/client correlation acceptance remains in the [client contracts](../client/README.md). Event codecs and context shapes are unchanged by typed local results.

- [x] `path_review` and `current_docs` approved typed completed local publication through Tool.encodeResult and ToolResultProcessor, including ordered data events and protected metadata. The [tool acceptance](../tool/README.md#current-review-acceptance) records the exact runtime/spec identities and current full verification.
- [ ] Provider multimodal projection and exhaustive upstream event ordering remain separate work.

- [x] Both reviewers approved and full verification passed for [incremental local execution](../tool/README.md#incremental-execution): provisional text/data use existing event types, with runtime correlation and protected metadata; final End remains authoritative and event codecs are unchanged.

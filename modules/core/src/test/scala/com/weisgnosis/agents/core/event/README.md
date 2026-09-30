# Agent event verification

These suites verify payload codecs and selected runtime boundaries. They do not establish exhaustive event integration or Java wire compatibility.

- [ModelStreamContextSpec](ModelStreamContextSpec.scala)
- [AgentEventSerdeSpec](AgentEventSerdeSpec.scala): all 39 concrete/base variants, strict tags, legacy leaf payloads, repeated source updates, and metadata preservation.
- [AgentEventPayloadSpec](AgentEventPayloadSpec.scala): eight added payload contracts through seven tests, including nested Msg/content validation and optional stop/denied-call defaults.
- [AgentEventTypeSpec](AgentEventTypeSpec.scala): 31 canonical protocol identifiers, 11 aliases, and nine upstream identifier methods.
- [AgentEventStreamingSpec](AgentEventStreamingSpec.scala): selected Hub/ZStream behavior.

## Current acceptance

- [x] `review_fp` and `review_harness` approved the final three-file payload increment against `66bb42d` on 2026-09-29 with no unresolved findings.
- [x] Full formatting, fatal-warning compilation, and tests passed: 291 core and 40 harness.
- [x] All 13 enabled AgentEventStreamTest declarations now have identifier/payload evidence with documented adaptations.
- [x] ReActAgentEmissionSpec verifies correlated iteration-limit publication and inline provider metadata/text/data order with occurrence-sensitive cancellation handling.
- [x] StructuredCompletionSpec verifies finalized result publication after history commit and before AgentEnd; see the reviewed [agent scope](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md).
- [x] Verify reply/request correlation in actual live model streams.
- [ ] Complete remaining runtime event ordering, provider multimodal projection, incremental execution, stop/resume integration, and richer source attribution.

The [current event contracts and reviewed source identities](../../../../../../../main/scala/com/weisgnosis/agents/core/event/README.md) distinguish previously accepted identifiers/codecs from the latest payload increment. The [parity mapping](../../../../../../../../../../docs/architecture/test-parity.md) records ten deprecated legacy methods as explicit exclusions and retains six ordering requirements as pending work; the seventh disabled upstream ordering declaration now has an implemented Scala limit-event regression. Decoding an event does not establish runtime authority or publication.

- [x] `review_fp` and `review_harness` independently approved the deprecated-envelope scope decision against `e110321` on 2026-09-29 after inspecting all ten upstream methods. Approval covers applicability classification, not legacy API or wire implementation.

## Model stream context

Sixteen text/thinking/function/model/result event variants now carry optional ModelStreamContext(replyId, requestId). Legacy payloads without context still decode; malformed supplied context fails. Constructors append the optional field, so positional extractors must accept the additional argument. withSource retains correlation. Runtime model boundaries and the live client always supply context; arbitrary scripted event publishers remain responsible for theirs. The context constructor and codec allocate no identities or time.

ModelStreamContextSpec verifies all sixteen contextual root encodings, source preservation, legacy absence, and incomplete supplied context. See [live client ordering](../client/README.md); typed completed local blocks and metadata are supported; complete uppercase envelopes, provider multimodal projection, and incremental execution remain pending.

Existing model/client correlation acceptance remains in the [client contracts](../client/README.md). Event codecs and context shapes are unchanged by typed local results.

- [x] `path_review` and `current_docs` approved typed completed local publication through Tool.encodeResult and ToolResultProcessor, including ordered data events and protected metadata. The [tool acceptance](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#current-review-acceptance) records the exact runtime/spec identities and current full verification.
- [ ] Provider multimodal projection, incremental execution and exhaustive upstream event ordering remain separate work.

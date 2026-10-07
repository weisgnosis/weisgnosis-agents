# Agent event verification

Toolkit construction and selection follow the [checked registry identity and migration contract](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#checked-registry-identity). The linked source/spec manifest owns the current migration review scope and verification; existing lifecycle and permission contracts remain in force.

Generic PermissionAskEvent and PermissionDecisionEvent runtime integration is accepted in the [checkpoint contract](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#checkpoint-permission-events). Ask follows successful batch cleanup and precedes RequestStop; a recorded resumed authorization decision precedes tool Start. UUID correlation is data, not evidence of a human response; event codecs are unchanged.

The existing RequestStop codec is unchanged. [Acting-stop runtime tests](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md#acting-stop-decisions) exercise host authority and publication after completed observations; the event payload alone grants no stop, cancellation or resume authority. Both independent reviews and full verification passed for this bounded integration.

[ExactJsonSpec](../json/ExactJsonSpec.scala) additionally verifies exact numeric metadata across all four tool-result event variants and missing/null/nonobject compatibility; current decoder identities and final verification belong to the [JSON contract](../../../../../../../main/scala/com/weisgnosis/agents/core/json/README.md#verification-and-acceptance).

These suites verify payload codecs and selected runtime boundaries. They do not establish exhaustive event integration or Java wire compatibility.

- [ModelStreamContextSpec](ModelStreamContextSpec.scala)
- [AgentEventSerdeSpec](AgentEventSerdeSpec.scala): all 39 concrete/base variants, strict tags, legacy leaf payloads, repeated source updates, and metadata preservation.
- [AgentEventPayloadSpec](AgentEventPayloadSpec.scala): eight added payload contracts through seven tests, including nested Msg/content validation and optional stop/denied-call defaults.
- [AgentEventTypeSpec](AgentEventTypeSpec.scala): 31 canonical protocol identifiers, 11 aliases, and nine upstream identifier methods.
- [FinalAnswerFilterSpec](FinalAnswerFilterSpec.scala): three grouped contracts for bounded correlated presentation filtering, a real HTTP ReAct cycle and full-cause/joined-cancellation preservation; [current acceptance](../../../../../../../main/scala/com/weisgnosis/agents/core/event/README.md#optional-final-answer-filter) records exact identities. Primitive Hub/ZStream checks are omitted; all-variant serde and real runtime suites cover source tagging and delivery.

## Current acceptance

- [x] `review_fp` and `review_harness` approved the final three-file payload increment against `66bb42d` on 2026-09-29 with no unresolved findings.
- [x] Full formatting, fatal-warning compilation and tests passed; current totals are maintained in the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance). Runtime sequence evidence is separate from codecs and is recorded in the [ordering acceptance](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md#runtime-event-ordering).
- [x] All 13 enabled AgentEventStreamTest declarations now have identifier/payload evidence with documented adaptations.
- [x] ReActAgentEmissionSpec verifies correlated iteration-limit publication and inline provider metadata/text/data order with occurrence-sensitive cancellation handling.
- [x] StructuredCompletionSpec verifies finalized result publication after history commit and before AgentEnd; see the reviewed [agent scope](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md).
- [x] Verify reply/request correlation in actual live model streams.
- [x] Verify bounded current runtime ordering, checkpoint Ask/Decision and optional final-answer presentation filtering.
- [ ] Define model data-block production, remaining provider output-media projection, richer source attribution and host durable checkpoint integration.

The [current event contracts and reviewed source identities](../../../../../../../main/scala/com/weisgnosis/agents/core/event/README.md) distinguish previously accepted identifiers/codecs from the latest payload increment. The [parity mapping](../../../../../../../../../../docs/architecture/test-parity.md) records ten deprecated legacy methods as explicit exclusions and records six bounded adaptations of disabled ordering declarations; model data-block production remains pending. Decoding an event does not establish runtime authority or publication.

- [x] `review_fp` and `review_harness` independently approved the deprecated-envelope scope decision against `e110321` on 2026-09-29 after inspecting all ten upstream methods. Approval covers applicability classification, not legacy API or wire implementation.

## Model stream context

Sixteen text/thinking/function/model/result event variants now carry optional ModelStreamContext(replyId, requestId). Legacy payloads without context still decode; malformed supplied context fails. Constructors append the optional field, so positional extractors must accept the additional argument. withSource retains correlation. Runtime model boundaries and the live client always supply context; arbitrary scripted event publishers remain responsible for theirs. The context constructor and codec allocate no identities or time.

ModelStreamContextSpec verifies all sixteen contextual root encodings, source preservation, legacy absence, and incomplete supplied context. See [live client ordering](../client/README.md); typed completed local blocks and metadata are supported; complete uppercase envelopes and provider multimodal projection remain pending; incremental local execution has separate accepted runtime evidence below.

Existing model/client correlation acceptance remains in the [client contracts](../client/README.md). Event codecs and context shapes are unchanged by typed local results.

- [x] `path_review` and `current_docs` approved typed completed local publication through Tool.encodeResult and ToolResultProcessor, including ordered data events and protected metadata. The [tool acceptance](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#current-review-acceptance) records the exact runtime/spec identities and current full verification.
- [ ] Provider multimodal projection and exhaustive upstream event ordering remain separate work.

- [x] Both reviewers approved and full verification passed for [incremental local execution](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#incremental-execution): provisional text/data use existing event types, with runtime correlation and protected metadata; final End remains authoritative and event codecs are unchanged.

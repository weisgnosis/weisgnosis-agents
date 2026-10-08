# Agent event verification

The [current invocation contract](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md#invocation-identity-and-prepared-execution) adds typed UUID correlation without runtime authority. InvocationContextSpec supplies runtime and codec evidence; ModelStreamContextSpec extends its existing sixteen-variant/source-copy table and legacy/malformed wire group. Missing invocation remains None for legacy context data; runtime resumes have fresh invocation identity and preserved saved acting reply/request correlation.

The [incremental and accumulated thinking contract](../../../../../../../main/scala/com/weisgnosis/agents/core/event/README.md#incremental-and-accumulated-thinking) preserves exact fragments and adds an optional per-index accumulated plaintext prefix. Existing test groups cover both pinned observations, interleaving, old/new codecs and public agent streaming; opaque reasoning stays outside plaintext.

Toolkit construction and selection follow the [checked registry identity and migration contract](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#checked-registry-identity).

Generic PermissionAskEvent and PermissionDecisionEvent runtime integration is accepted in the [checkpoint contract](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#checkpoint-permission-events). Ask follows successful batch cleanup and precedes RequestStop; a recorded resumed authorization decision precedes tool Start. UUID correlation is data, not evidence of a human response; event codecs are unchanged.

The existing RequestStop codec is unchanged. [Acting-stop runtime tests](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md#acting-stop-decisions) exercise host authority and publication after completed observations; the event payload alone grants no stop, cancellation or resume authority.

[ExactJsonSpec](../json/ExactJsonSpec.scala) additionally verifies exact numeric metadata across all four tool-result event variants and missing/null/nonobject compatibility; exact-number rules belong to the [JSON contract](../../../../../../../main/scala/com/weisgnosis/agents/core/json/README.md#verification-and-acceptance).

These suites verify payload codecs and selected runtime boundaries. They do not establish exhaustive event integration or Java wire compatibility.

- [ModelStreamContextSpec](ModelStreamContextSpec.scala)
- [AgentEventSerdeSpec](AgentEventSerdeSpec.scala): all 39 concrete/base variants, strict tags, legacy leaf payloads, repeated source updates, and metadata preservation.
- [AgentEventPayloadSpec](AgentEventPayloadSpec.scala): nested payload contracts, including nested Msg/content validation and optional stop/denied-call defaults.
- [AgentEventTypeSpec](AgentEventTypeSpec.scala): 31 canonical protocol identifiers, 11 aliases, and nine upstream identifier methods.
- [FinalAnswerFilterSpec](FinalAnswerFilterSpec.scala): three grouped contracts for bounded correlated presentation filtering, a real HTTP ReAct cycle and full-cause/joined-cancellation preservation; see the [filter contract](../../../../../../../main/scala/com/weisgnosis/agents/core/event/README.md#optional-final-answer-filter). Primitive Hub/ZStream checks are omitted; all-variant serde and real runtime suites cover source tagging and delivery.

## Current acceptance

- All 13 enabled AgentEventStreamTest declarations now have identifier/payload evidence with documented adaptations.
- ReActAgentEmissionSpec verifies correlated iteration-limit publication and inline provider metadata/text/data order with occurrence-sensitive cancellation handling.
- StructuredCompletionSpec verifies finalized result publication after history commit and before AgentEnd; see the reviewed [agent scope](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md).
- Verify reply/request correlation in actual live model streams.
- Verify bounded current runtime ordering, checkpoint Ask/Decision and optional final-answer presentation filtering.
- [ ] Define model data-block production, remaining provider output-media projection, richer source attribution and host durable checkpoint integration.

The [current event contracts](../../../../../../../main/scala/com/weisgnosis/agents/core/event/README.md) describe payload, correlation and publication boundaries. The [parity mapping](../../../../../../../../../../docs/architecture/test-parity.md) records ten deprecated legacy methods as explicit exclusions and records six bounded adaptations of disabled ordering declarations; model data-block production remains pending. Decoding an event does not establish runtime authority or publication.


Completed local publication through Tool.encodeResult and ToolResultProcessor produces ordered data events with protected metadata. This does not enable every provider output-media format; see the [tool contract](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#current-review-acceptance).

## Model stream context

Sixteen text/thinking/function/model/result event variants now carry optional ModelStreamContext(replyId, requestId, invocation = None). Legacy payloads without context still decode; malformed supplied context fails. Constructors append the optional field, so positional extractors must accept the additional argument. withSource retains correlation. Runtime model boundaries and the live client always supply context; arbitrary scripted event publishers remain responsible for theirs. The context constructor and codec allocate no identities or time.

ModelStreamContextSpec verifies all sixteen contextual root encodings, source preservation, legacy absence, and incomplete supplied context. See [live client ordering](../client/README.md); typed completed local blocks and metadata are supported; the opt-in Java wire adapter is separate from canonical Scala codecs, and provider multimodal projection remains profile-specific; incremental local execution has separate runtime evidence in the tool contract.

Model/client correlation behavior is described in the [client contracts](../client/README.md). Event codecs and context shapes are unchanged by typed local results.

- [ ] Provider multimodal projection and exhaustive upstream event ordering remain separate work.


[Incremental local execution](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#incremental-execution) uses existing event types for provisional text/data; final End remains authoritative and does not change event codecs.

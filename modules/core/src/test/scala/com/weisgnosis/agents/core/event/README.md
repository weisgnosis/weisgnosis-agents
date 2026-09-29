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
- [ ] Complete remaining runtime event ordering, local structured-result metadata, stop/resume integration, and richer source attribution.

The [current event contracts and reviewed source identities](../../../../../../../main/scala/com/weisgnosis/agents/core/event/README.md) distinguish previously accepted identifiers/codecs from the latest payload increment. The [parity mapping](../../../../../../../../../../docs/architecture/test-parity.md) records ten deprecated legacy methods as explicit exclusions and retains six ordering requirements as pending work; the seventh disabled upstream ordering declaration now has an implemented Scala limit-event regression. Decoding an event does not establish runtime authority or publication.

- [x] `review_fp` and `review_harness` independently approved the deprecated-envelope scope decision against `e110321` on 2026-09-29 after inspecting all ten upstream methods. Approval covers applicability classification, not legacy API or wire implementation.

## Model stream context

Sixteen text/thinking/function/model/result event variants now carry optional ModelStreamContext(replyId, requestId). Legacy payloads without context still decode; malformed supplied context fails. Constructors append the optional field, so positional extractors must accept the additional argument. withSource retains correlation. Runtime model boundaries and the live client always supply context; arbitrary scripted event publishers remain responsible for theirs. The context constructor and codec allocate no identities or time.

ModelStreamContextSpec verifies all sixteen contextual root encodings, source preservation, legacy absence, and incomplete supplied context. See [live client ordering](../client/README.md); complete uppercase envelope and incremental/structured local tool-result integration remain pending.

- [x] `review_fp` and `review_harness` approved the final live client/event increment against `60b3799` on 2026-09-29. No findings remain in runtime correlation, validated incremental publication, scoped cleanup, or the migrated/new tests. Full verification passed 386 core and 40 harness tests. Full upstream ordering parity and local tool-result event integration are excluded.

Earlier live client/event identities and approval are recorded in `84e4eb3`; the [current client contracts](../client/README.md) link subsequent accepted changes.

- [x] The completed local/inline result increment against `84e4eb3` was approved by `review_fp` and `review_harness`; commit `9c165a7` preserves its five final source identities and six new regressions; the [agent README](../agent/README.md) describes current runtime contracts. All sixteen contextual variants and full 392 core / 40 harness verification pass. Partial tool-output streaming and richer local structured output remain pending.

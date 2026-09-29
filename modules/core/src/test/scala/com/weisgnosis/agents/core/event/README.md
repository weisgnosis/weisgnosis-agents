# Agent event verification

These suites verify payload codecs and selected runtime boundaries. They do not establish exhaustive event integration or Java wire compatibility.

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
- [ ] Complete remaining runtime event ordering, local structured-result metadata, stop/resume integration, and the legacy coarse-event surface.

The [current event contracts and reviewed source identities](../../../../../../../main/scala/com/weisgnosis/agents/core/event/README.md) distinguish previously accepted identifiers/codecs from the latest payload increment. The [parity mapping](../../../../../../../../../../docs/architecture/test-parity.md) retains ten legacy methods and six ordering requirements as pending work; the seventh disabled upstream ordering declaration now has an implemented Scala limit-event regression. Decoding an event does not establish runtime authority or publication.

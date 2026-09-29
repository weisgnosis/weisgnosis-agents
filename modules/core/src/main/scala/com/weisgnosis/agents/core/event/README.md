# Typed agent event payloads

AgentEvent defines 39 immutable payload variants with JSON codecs and withSource tagging. Available runtime components publish through ZIO Hub, but class/codec support does not imply every event is emitted. Serialization never allocates IDs or reads the clock.

- [AgentEvent](AgentEvent.scala)
- [AgentEventType](AgentEventType.scala)

## Codec and metadata contracts

All 39 concrete/base codecs encode the same object with exactly one lowercase event discriminator. Concrete decoders also read legacy untagged payloads; a supplied tag must match. Base decoding requires a unique valid tag. Missing required fields, malformed tags, and duplicate discriminators fail. Immutable source updates preserve every other field.

ToolResultStartEvent, ToolResultDeltaEvent, ToolResultEndEvent, and ToolResultDataDeltaEvent retain immutable metadata independently of source. Empty metadata is the default. Inline provider text/data events now preserve complete result metadata in output order; local structured/streaming result metadata remains pending. Isolated private payload derivation and a suspended root codec prevent encoder cycles; there is no silent AST encoding fallback.

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
- [x] `sbt scalafmtAll scalafmtSbt verify` passed 291 core and 40 harness tests with zero compiler warnings.
- [x] Correlated iteration-limit publication and inline provider text/data/metadata delivery are verified in ReActAgentEmissionSpec and reviewed in the [agent scope](../agent/README.md).
- [ ] Implement remaining runtime publication/order, model data-block assembly, and local structured-result metadata.
- [ ] Implement phase-stop authority, durable pending-work resumption, and permission-driven denied-call publication.
- [ ] Complete legacy coarse-event classification and explicit uppercase payload/envelope adapters.

The [parity mapping](../../../../../../../../../../docs/architecture/test-parity.md) distinguishes the 13 enabled stream-test methods from seven disabled ordering placeholders and ten legacy coarse-event tests. Enabled payload/identifier method coverage is not runtime-ordering evidence. Current reviewed content:

| File | Git blob |
| --- | --- |
| [AgentEvent.scala](AgentEvent.scala) | 189673e0cd692fe7fddf8db5e586bd50a122796f |
| [AgentEventType.scala](AgentEventType.scala) | 7d3238de6b51d9bb9df817dd4f866b91c993654c |
| [AgentEventSerdeSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/event/AgentEventSerdeSpec.scala) | e03c43bdbaf2d97c4e43d32ba3f2aa0c8700d46f |
| [AgentEventPayloadSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/event/AgentEventPayloadSpec.scala) | 0912c9e7f25e7e90440caeeb8aa3314eaa61488c |
| [AgentEventTypeSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/event/AgentEventTypeSpec.scala) | b881e98ba39ed97e292ac16acf3c4101f48b0d11 |

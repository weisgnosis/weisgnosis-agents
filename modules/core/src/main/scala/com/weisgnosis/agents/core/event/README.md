# Typed agent events

AgentEvent defines 31 event variants with JSON codecs and source tagging through withSource. Runtime publication uses ZIO Hub; serialization does not allocate IDs or read the clock.

## Implementation

- [AgentEvent](AgentEvent.scala)
- [AgentEventType](AgentEventType.scala)

See the [repository architecture](../../../../../../../../../../ARCHITECTURE.md) for integration boundaries and invariant definitions.

## Codec compatibility and metadata

All 31 concrete and base event codecs encode the same object with exactly one lowercase `event` discriminator. Leaf decoders also read legacy untagged payloads; a supplied tag must match that leaf. Base decoding requires a tag. Missing required fields, malformed tags, and duplicate discriminators fail decoding. Source tagging returns a fresh event without changing its payload.

ToolResultStartEvent, ToolResultDeltaEvent, and ToolResultEndEvent carry immutable metadata maps, independent of their optional source. Empty metadata is the default; supplied malformed metadata fails decoding. This is a serialization contract: broader runtime metadata emission remains pending.

Private derived payload codecs are isolated from public tagged codecs to prevent recursive encoder selection. The parent uses normal discriminated-sum derivation. Custom glue validates compatibility input and shares the parent encoder; there is no manual field encoder, unchecked cast, or silent AST encoding fallback.

Migration: concrete event encoding now includes the discriminator. Existing tagged base payloads and legacy untagged leaf payloads remain readable. Tool-result event payloads can now include metadata. Upstream uppercase type identifiers and aliases are available through AgentEventType, but full `type` payloads require a separate adapter; these codecs do not claim cross-language wire compatibility.

## Codec review acceptance at b2901eb

- [x] Independent reviewers `review_fp` and `review_harness` approved the codec and metadata changes based on `62478ce`, on 2026-09-29, with no unresolved findings in this scope.
- [x] Formatting, fatal-warning compilation, and all 185 core / 40 harness tests passed. The focused suite reproduced concrete/base encoding, discriminator, and metadata defects before correction.
- [x] Classify all 30 upstream legacy/current event declarations, distinguishing enabled tests from seven disabled ordering placeholders.
- [x] Complete upstream type aliases in the separately reviewed identifier contract below.
- [ ] Complete payload adapters, missing event variants, runtime metadata emission, and ordering scenarios in the parity task list.

| Reviewed source | Git blob |
| --- | --- |
| AgentEvent.scala | 9a000a4072808bea4d615615028c38ed6087c4c4 |
| AgentEventSerdeSpec.scala | 7237d4965aa30732cd1a5d3fdaa85bcfb6524ed1 |

## Protocol event identifiers

AgentEventType provides 31 canonical uppercase names and all 11 legacy aliases, including REPLY_START/REPLY_END and THREAD_EXPOSED. fromValue is a pure immutable lookup returning Either[AgentError, AgentEventType]. Matching is case-sensitive; unknown identifiers and malformed JSON fail. Encoding always uses the canonical uppercase name.

This identifier codec is independent of the established AgentEvent object codec. It does not translate `type` payloads to `event` payloads, add missing variants, allocate IDs/timestamps, or establish emitter support.

- [x] `review_fp` and `review_harness` independently approved AgentEventType and AgentEventTypeSpec based on `b2901eb`, on 2026-09-29, with no unresolved findings in the identifier scope.
- [x] Verify all 31 names, 11 aliases, nine enabled upstream identifier methods, and the legacy 25-name surface; JSON null rejection replaces the Java null-reference test.
- [x] Formatting, fatal-warning compilation, and all 195 core / 40 harness tests passed.
- [ ] Complete missing payloads, typed generation reasons, runtime emitters, and explicit wire adapters.

| Reviewed source | Git blob |
| --- | --- |
| AgentEventType.scala | 7d3238de6b51d9bb9df817dd4f866b91c993654c |
| AgentEventTypeSpec.scala | b881e98ba39ed97e292ac16acf3c4101f48b0d11 |

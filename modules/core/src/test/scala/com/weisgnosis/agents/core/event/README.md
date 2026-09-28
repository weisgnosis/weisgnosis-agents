# Typed agent events: verification

These ZIO Test suites cover selected upstream scenarios and Scala runtime regressions. They do not certify exhaustive upstream parity.

- [AgentEventSerdeSpec](AgentEventSerdeSpec.scala)
- [AgentEventStreamingSpec](AgentEventStreamingSpec.scala)

Run `sbt "core / test"`, or `sbt verify` for repository-wide formatting, compilation, and tests. See the [parity audit](../../../../../../../../../../docs/architecture/test-parity.md) for gaps and intentional differences.

## Codec compatibility and metadata

All 31 concrete and base event codecs encode the same object with exactly one lowercase `event` discriminator. Leaf decoders also read legacy untagged payloads; a supplied tag must match that leaf. Base decoding requires a tag. Missing required fields, malformed tags, and duplicate discriminators fail decoding. Source tagging returns a fresh event without changing its payload.

ToolResultStartEvent, ToolResultDeltaEvent, and ToolResultEndEvent carry immutable metadata maps, independent of their optional source. Empty metadata is the default; supplied malformed metadata fails decoding. This is a serialization contract: broader runtime metadata emission remains pending.

Private derived payload codecs are isolated from public tagged codecs to prevent recursive encoder selection. The parent uses normal discriminated-sum derivation. Custom glue validates compatibility input and shares the parent encoder; there is no manual field encoder, unchecked cast, or silent AST encoding fallback.

Migration: concrete event encoding now includes the discriminator. Existing tagged base payloads and legacy untagged leaf payloads remain readable. Tool-result event payloads can now include metadata. The upstream uppercase `type` format and legacy names require a separate adapter; these codecs do not claim cross-language wire compatibility.

## Current review acceptance

- [x] Independent reviewers `review_fp` and `review_harness` approved the codec and metadata changes based on `62478ce`, on 2026-09-29, with no unresolved findings in this scope.
- [x] Formatting, fatal-warning compilation, and all 185 core / 40 harness tests passed. The focused suite reproduced concrete/base encoding, discriminator, and metadata defects before correction.
- [x] Classify all 30 upstream legacy/current event declarations, distinguishing enabled tests from seven disabled ordering placeholders.
- [ ] Complete upstream type aliases, payload adapters, missing event variants, runtime metadata emission, and ordering scenarios in the parity task list.

| Reviewed source | Git blob |
| --- | --- |
| AgentEvent.scala | 9a000a4072808bea4d615615028c38ed6087c4c4 |
| AgentEventSerdeSpec.scala | 7237d4965aa30732cd1a5d3fdaa85bcfb6524ed1 |

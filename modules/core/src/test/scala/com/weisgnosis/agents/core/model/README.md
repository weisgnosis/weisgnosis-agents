# Model verification

These ZIO Test suites verify all 119 pinned upstream message methods with explicit immutable, effect-boundary, and wire adaptations. They also exercise Scala-specific construction, cancellation, and nested-codec regressions. Full Java API/wire equivalence and broader repository parity are not claimed.

- [ChatUsageSpec](ChatUsageSpec.scala)
- [ContentBlockCodecSpec](ContentBlockCodecSpec.scala)
- [ContentBlockSerdeSpec](ContentBlockSerdeSpec.scala)
- [DataBlockSpec](DataBlockSpec.scala)
- [GenerateReasonSpec](GenerateReasonSpec.scala)
- [MediaSourceSpec](MediaSourceSpec.scala)
- [MsgConstructionSpec](MsgConstructionSpec.scala)
- [MsgFactorySpec](MsgFactorySpec.scala)
- [MsgSerdeSpec](MsgSerdeSpec.scala)
- [MsgSpec](MsgSpec.scala)
- [RoleContentValidationSpec](RoleContentValidationSpec.scala)
- [ToolUseSpec](ToolUseSpec.scala)

## Current evidence

- [x] MsgConstructionSpec rejects unchecked constructor/apply/copy/Mirror reconstruction, verifies validated updates and structural equality, and checks ordered interruptible tool mapping.
- [x] MsgFactorySpec adapts all 26 message factory/builder scenarios; GenerateReasonSpec covers all nine generation-reason methods.
- [x] ContentBlockCodecSpec checks all ten leaf/base representations and nested discriminators. ToolUseSpec plus ContentBlockSerdeSpec cover all 17 upstream ToolUse methods and canonical/legacy server markers.
- [x] DataBlockSpec covers all seven upstream DataBlock methods plus seeded lazy allocation/reuse; MediaSourceSpec covers both concrete/base codecs, aliases, and nested invalid input.
- [x] `review_fp` and `review_harness` approved the final four-file Data/media increment against `8b96ce3` on 2026-09-29; no findings remain in that scope. Earlier accepted scopes and current source hashes are in the [model README](../../../../../../../main/scala/com/weisgnosis/agents/core/model/README.md).
- [x] Full formatting, fatal-warning compilation, and verification passed 284 core and 40 harness tests.
- [ ] Complete remaining runtime state/HITL integration and broader core parity.

The [119-method mapping](../../../../../../../../../../docs/architecture/test-parity.md) states each adaptation. Data identity is opt-in inside ZIO; required-source compile-time checks and JSON rejection do not claim direct Java-null constructor protection. Seeded tests show effect construction is lazy and reusing identity consumes no randomness.

Exact reviewed test additions for the latest Data/media increment:

| Test | Git blob |
| --- | --- |
| [DataBlockSpec.scala](DataBlockSpec.scala) | 4211585b28ce57e12e825152133443199a35da9e |
| [MediaSourceSpec.scala](MediaSourceSpec.scala) | 0081a1db68dc2ce65a709af6260ac845d01aa0e4 |

## Strict structured JSON

Msg.getStructuredData and structured agent preparation share core.json.JsonBoundary. Text must contain one complete JSON value with unique object keys at every depth. Authoritative structured metadata is checked directly; invalid metadata fails without falling back to visible text. Captured synthetic payloads are checked before finalization. The synthetic tool also validates its entire input before extracting response, so duplicate wrapper keys or duplicates in ignored siblings cannot disappear during extraction. Valid wrapped and direct payload adaptation remains supported.

This tightens previously permissive parsing; trailing input and ambiguous fields now fail through existing typed/string error channels. It does not add JSON Schema validation. Failed structured preparation publishes no AgentResult or structured metadata; raw observations can remain in history. The shared validator performs no effects and does not depend on models, agents, or transports.

- [x] Six boundary regressions cover full text, duplicate text/metadata/captures/wrappers, metadata authority, and valid wrapped/direct values. Five real loopback HTTP tests cover native schema/thinking/usage, synthetic definition hoisting and compression, HTTP-400 fallback without duplicate input, no retry on HTTP 503, and rejection of trailing native JSON.
- [x] `review_fp` and `review_harness` approved the final shared JSON/structured HTTP scope against `9c165a7` on 2026-09-29 after the wrapper-boundary correction. Full verification passed 404 core and 40 harness tests. No findings remain; the [JSON package table](../json/README.md) records exact current identities.
- [ ] Complete capability/hook parity and broader provider compatibility; general JSON Schema validation is outside this acceptance.

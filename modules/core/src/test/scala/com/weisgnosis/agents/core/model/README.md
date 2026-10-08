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

[ExactJsonSpec](../json/ExactJsonSpec.scala) checks exact nested numeric message and Thinking/ToolUse/ToolResult metadata through checkpoint/event serialization and decoding. MsgSerdeSpec and ContentBlockCodecSpec retain malformed and legacy controls.

## Current evidence

- MsgConstructionSpec rejects unchecked constructor/apply/copy/Mirror reconstruction, verifies validated updates and structural equality, and checks ordered interruptible tool mapping.
- MsgFactorySpec adapts all 26 message factory/builder scenarios; GenerateReasonSpec covers all nine generation-reason methods.
- ContentBlockCodecSpec checks all ten leaf/base representations and nested discriminators. ToolUseSpec plus ContentBlockSerdeSpec cover all 17 upstream ToolUse methods and canonical/legacy server markers.
- DataBlockSpec covers all seven upstream DataBlock methods plus seeded lazy allocation/reuse; MediaSourceSpec covers concrete/base codecs, aliases, nested invalid input and canonical optional URL MIME hints, including absent/null omission and duplicate/nonstring rejection.
- [ ] Complete remaining applicable runtime and provider behavior in the [parity mapping](../../../../../../../../../../docs/architecture/test-parity.md); payload tests alone do not establish execution or human confirmation authority.

The [119-method mapping](../../../../../../../../../../docs/architecture/test-parity.md) states each adaptation. Data identity is opt-in inside ZIO; required-source compile-time checks and JSON rejection do not claim direct Java-null constructor protection. Seeded tests show effect construction is lazy and reusing identity consumes no randomness.

## Strict structured JSON

Msg.getStructuredData and structured agent preparation share core.json.JsonBoundary. Text must contain one complete JSON value with unique object keys at every depth. Authoritative structured metadata is checked directly; invalid metadata fails without falling back to visible text. Captured synthetic payloads are checked before finalization. The synthetic tool also validates its entire input before extracting response, so duplicate wrapper keys or duplicates in ignored siblings cannot disappear during extraction. Valid wrapped and direct payload adaptation remains supported.

This tightens previously permissive parsing; trailing input and ambiguous fields now fail through existing typed/string error channels. It does not add JSON Schema validation. Failed structured preparation publishes no AgentResult or structured metadata; raw observations can remain in history. The shared validator performs no effects and does not depend on models, agents, or transports.

- Six boundary regressions cover full text, duplicate text/metadata/captures/wrappers, metadata authority, and valid wrapped/direct values. Five real loopback HTTP tests cover native schema/thinking/usage, synthetic definition hoisting and compression, HTTP-400 fallback without duplicate input, no retry on HTTP 503, and rejection of trailing native JSON.
- [ ] Complete hook parity and broader provider compatibility; general JSON Schema validation is outside this acceptance.

The internal occurrence-specific Msg update is exercised through [ReActAgentToolAuthorizerSpec](../agent/ReActAgentToolAuthorizerSpec.scala), including persistence before execution and duplicate-ID parallel rewrites. Existing model construction/codec contracts are unchanged.

[ToolHistorySpec](ToolHistorySpec.scala) verifies three shared-analysis scenarios: reversed named spans, inline/local pool isolation including unmatched inline output, and FIFO unnamed-result consumption with wrong-name rejection and retained original ordinal. The inline table additionally verifies provider-owned pending exclusion, same-ID/name local matching and orphan rejection for separate Tool/later-assistant results.

ChatUsageSpec covers checked valid boundaries, invalid counters/time and overflow without saturation. Existing callers/serde fixtures now handle Either; wire fields are unchanged.

History rewrite validation and scoped compactor integration do not establish semantic summary fidelity or exhaustive provider-format validation.

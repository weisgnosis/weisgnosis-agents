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

[ExactJsonSpec](../json/ExactJsonSpec.scala) verifies nested numeric message and Thinking/ToolUse/ToolResult metadata through exact checkpoint/event serialization and decoding. Existing MsgSerdeSpec and ContentBlockCodecSpec retain malformed/legacy controls. The [current acceptance](../../../../../../../main/scala/com/weisgnosis/agents/core/json/README.md#exact-domain-and-tool-output) records the changed production identities; these paired model suites are unchanged.

## Current evidence

- [x] MsgConstructionSpec rejects unchecked constructor/apply/copy/Mirror reconstruction, verifies validated updates and structural equality, and checks ordered interruptible tool mapping.
- [x] MsgFactorySpec adapts all 26 message factory/builder scenarios; GenerateReasonSpec covers all nine generation-reason methods.
- [x] ContentBlockCodecSpec checks all ten leaf/base representations and nested discriminators. ToolUseSpec plus ContentBlockSerdeSpec cover all 17 upstream ToolUse methods and canonical/legacy server markers.
- [x] DataBlockSpec covers all seven upstream DataBlock methods plus seeded lazy allocation/reuse; MediaSourceSpec covers concrete/base codecs, aliases, nested invalid input and canonical optional URL MIME hints, including absent/null omission and duplicate/nonstring rejection.
- [x] `review_fp` and `review_harness` approved the final four-file Data/media increment against `8b96ce3` on 2026-09-29; no findings remain in that scope. Earlier accepted scopes and current source hashes are in the [model README](../../../../../../../main/scala/com/weisgnosis/agents/core/model/README.md).
- [x] Full formatting, fatal-warning compilation, and verification passed 284 core and 40 harness tests.
- [ ] Complete remaining runtime state/HITL integration and broader core parity.

The [119-method mapping](../../../../../../../../../../docs/architecture/test-parity.md) states each adaptation. Data identity is opt-in inside ZIO; required-source compile-time checks and JSON rejection do not claim direct Java-null constructor protection. Seeded tests show effect construction is lazy and reusing identity consumes no randomness.

Exact reviewed test additions for the latest Data/media increment:

| Test | Git blob |
| --- | --- |
| [DataBlockSpec.scala](DataBlockSpec.scala) | 4211585b28ce57e12e825152133443199a35da9e |
| [MediaSourceSpec.scala](MediaSourceSpec.scala) | [Current identity](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md#user-image-projection) |

## Strict structured JSON

Msg.getStructuredData and structured agent preparation share core.json.JsonBoundary. Text must contain one complete JSON value with unique object keys at every depth. Authoritative structured metadata is checked directly; invalid metadata fails without falling back to visible text. Captured synthetic payloads are checked before finalization. The synthetic tool also validates its entire input before extracting response, so duplicate wrapper keys or duplicates in ignored siblings cannot disappear during extraction. Valid wrapped and direct payload adaptation remains supported.

This tightens previously permissive parsing; trailing input and ambiguous fields now fail through existing typed/string error channels. It does not add JSON Schema validation. Failed structured preparation publishes no AgentResult or structured metadata; raw observations can remain in history. The shared validator performs no effects and does not depend on models, agents, or transports.

- [x] Six boundary regressions cover full text, duplicate text/metadata/captures/wrappers, metadata authority, and valid wrapped/direct values. Five real loopback HTTP tests cover native schema/thinking/usage, synthetic definition hoisting and compression, HTTP-400 fallback without duplicate input, no retry on HTTP 503, and rejection of trailing native JSON.
- [x] `review_fp` and `review_harness` approved the final shared JSON/structured HTTP scope against `9c165a7` on 2026-09-29 after the wrapper-boundary correction. Full verification passed 404 core and 40 harness tests. No findings remain; the [JSON package table](../json/README.md) records exact current identities.
- [ ] Complete hook parity and broader provider compatibility; general JSON Schema validation is outside this acceptance.

The internal occurrence-specific Msg update is exercised through [ReActAgentToolAuthorizerSpec](../agent/ReActAgentToolAuthorizerSpec.scala), including persistence before execution and duplicate-ID parallel rewrites. Existing model construction/codec contracts are unchanged.

- [x] `review_fp` and `review_harness` approved the final five-file core authorization increment against `a34201f` on 2026-09-29. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 481 core and 45 harness tests, including fifteen added authorization tests. Interactive and durable policy adapters are excluded from this acceptance. See the [authorization table](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md) for exact identities.

[ToolHistorySpec](ToolHistorySpec.scala) verifies three shared-analysis scenarios: reversed named spans, inline/local pool isolation including unmatched inline output, and FIFO unnamed-result consumption with wrong-name rejection and retained original ordinal. The inline table additionally verifies provider-owned pending exclusion, same-ID/name local matching and orphan rejection for separate Tool/later-assistant results. Current identity and approval are in the [provider-ownership contract](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md#provider-owned-pending-calls).

- [x] `review_fp` and `review_harness` approved the final nine-file history-preparation increment against `3c6958f` on 2026-09-29. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 497 core and 73 harness tests, including twenty added tests. Acceptance covers structural rewrite validation and scoped compactor integration; semantic summary fidelity and exhaustive provider-format validation are excluded. See the [agent table](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/README.md).

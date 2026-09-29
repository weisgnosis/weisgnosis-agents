# Model client and stream verification

These ZIO Test suites cover selected upstream scenarios and Scala runtime regressions. They do not certify exhaustive upstream parity or live-provider behavior.

- [LlmClientSpec](LlmClientSpec.scala)
- [ProtocolJsonSpec](ProtocolJsonSpec.scala)
- [ReasoningAdapterSpec](ReasoningAdapterSpec.scala)
- [ReasoningAdapterValidationSpec](ReasoningAdapterValidationSpec.scala)
- [SseParserSpec](SseParserSpec.scala)
- [StreamAccumulatorCompletionSpec](StreamAccumulatorCompletionSpec.scala)
- [StreamAccumulatorSpec](StreamAccumulatorSpec.scala)

## Current evidence

- [x] Sixteen strict-delta regressions cover field/index errors, duplicate keys, provider errors, unsupported calls, and complete frame boundaries; original reasoning/tool/usage parsing and context hygiene tests remain.
- [x] Thirteen StreamAccumulatorCompletionSpec regressions cover required finish markers, malformed/non-object/trailing tool arguments, interrupted sanitization, raw content, deferred/stable identity, duplicate IDs, interleaved indexes, signed blocks, usage snapshots, and post-finish rejection.
- [x] ProtocolJsonSpec verifies five complete-document/duplicate/escape/numeric scenarios without effects.
- [x] Existing accumulator scenarios and two ReAct thinking-retry integration tests now handle Either and explicit successful finish markers.
- [x] Full `sbt scalafmtAll scalafmtSbt verify` passed 345 core and 40 harness tests with zero compiler warnings.
- [ ] Complete live transport/resource integration, provider signature/metadata decoding, and exhaustive upstream scenario mapping.

The [client contracts](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md) record source migration, exact review scope, and remaining limits. The [parity audit](../../../../../../../../../../docs/architecture/test-parity.md) separates runtime hardening from mapped upstream methods. Earlier message migration and strict-delta acceptance remain in `a701612` and `10019c2`.

- [x] `review_fp` and `review_harness` approved the final eight-file completion/JSON-boundary increment against `10019c2` on 2026-09-29 after fixing trailing-input acceptance. No findings remain within this scope.

| File | Reviewed Git blob |
| --- | --- |
| [ProtocolJson.scala](../../../../../../../main/scala/com/weisgnosis/agents/core/client/ProtocolJson.scala) | c1799a0f56d90f86ea3120463b099fd5c84df769 |
| [ReasoningModelAdapter.scala](../../../../../../../main/scala/com/weisgnosis/agents/core/client/ReasoningModelAdapter.scala) | 947f092612cdfc987472bb9054d43a2bbfca4170 |
| [StreamAccumulator.scala](../../../../../../../main/scala/com/weisgnosis/agents/core/client/StreamAccumulator.scala) | 038162ae8235c6d6432872c6acaf49db2dccb2d6 |
| [ProtocolJsonSpec.scala](ProtocolJsonSpec.scala) | 8d0af6d6ea6ff3c8f704e1ed6e03ab7f4c858896 |
| [ReasoningAdapterValidationSpec.scala](ReasoningAdapterValidationSpec.scala) | 2e67ea902e6b8a50da802c9ccf3728e36d9b7c6c |
| [StreamAccumulatorSpec.scala](StreamAccumulatorSpec.scala) | 809ab37875025c99f3b6c870324b3b81c1a5a204 |
| [StreamAccumulatorCompletionSpec.scala](StreamAccumulatorCompletionSpec.scala) | 9e63fc534339d93594fe1c3f31c55e23b36b360c |
| [ReActAgentThinkingRetrySpec.scala](../agent/ReActAgentThinkingRetrySpec.scala) | cb149ca6c95027ace970b5a6fad5df1586b9c99e |

# Model client and stream verification

These ZIO Test suites cover selected upstream scenarios and Scala runtime regressions. They do not certify exhaustive upstream parity or live-provider behavior.

- [ChatCompletionsClientSpec](ChatCompletionsClientSpec.scala)
- [ModelStreamPublisherSpec](ModelStreamPublisherSpec.scala)
- [HttpClientTestSupport](HttpClientTestSupport.scala)
- [ChatCompletionsTransportSpec](ChatCompletionsTransportSpec.scala)
- [ChatCompletionsRequestSpec](ChatCompletionsRequestSpec.scala)
- [LlmClientSpec](LlmClientSpec.scala)
- [ProtocolJsonSpec](ProtocolJsonSpec.scala)
- [ReasoningAdapterSpec](ReasoningAdapterSpec.scala)
- [ReasoningAdapterValidationSpec](ReasoningAdapterValidationSpec.scala)
- [SseParserSpec](SseParserSpec.scala)
- [StreamAccumulatorCompletionSpec](StreamAccumulatorCompletionSpec.scala)
- [StreamAccumulatorSpec](StreamAccumulatorSpec.scala)

## Current evidence

- [x] Sixteen request-codec regressions cover ordered messages, schemas/options, authoritative arguments, paired parallel results, failure states, reasoning policies, private runtime data exclusion, and typed rejection of unsupported or malformed requests.

- [x] Sixteen strict-delta regressions cover field/index errors, duplicate keys, provider errors, unsupported calls, and complete frame boundaries; original reasoning/tool/usage parsing and context hygiene tests remain.
- [x] Thirteen StreamAccumulatorCompletionSpec regressions cover required finish markers, malformed/non-object/trailing tool arguments, interrupted sanitization, raw content, deferred/stable identity, duplicate IDs, interleaved indexes, signed blocks, usage snapshots, and post-finish rejection.
- [x] ProtocolJsonSpec verifies five complete-document/duplicate/escape/numeric scenarios without effects.
- [x] Existing accumulator scenarios and two ReAct thinking-retry integration tests now handle Either and explicit successful finish markers.
- [x] Full `sbt scalafmtAll scalafmtSbt verify` passed 392 core and 40 harness tests with zero compiler warnings.
- [x] Fourteen transport tests cover real loopback HTTP plus deterministic scope, interruption, body-budget, protocol-termination, and failure fixtures.
- [x] Six live-client tests verify early deltas, real HTTP tool cycles, request identity, late call identity, malformed/incomplete streams, and cancellation.
- [x] Three publisher tests verify indexed content, interleaved calls, and atomic ending-batch validation; two event-context tests cover all sixteen correlated variants and legacy payloads.
- [ ] Complete remaining event integration, provider signature/metadata decoding, and exhaustive upstream scenario mapping.

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

- [x] `review_fp` and `review_harness` approved the final request encoder and sixteen tests against `73f66d1` on 2026-09-29 after correcting pairing validation and negative fixtures. No findings remain within the text/function request scope; HTTP integration and exhaustive formatter parity were excluded from that earlier approval.

| File | Reviewed Git blob |
| --- | --- |
| [ChatCompletionsRequest.scala](../../../../../../../main/scala/com/weisgnosis/agents/core/client/ChatCompletionsRequest.scala) | cbe28128f70333a2f7594cf4dbf70478c5c3a6f9 |
| [ChatCompletionsRequestSpec.scala](ChatCompletionsRequestSpec.scala) | 955e9811d921883838a5b71362c7c9a0488155d9 |

- [x] `review_fp` and `review_harness` approved the scoped HTTP transport and fourteen tests against `38c48db` on 2026-09-29. No findings remain within this scope; LlmClient/event integration, retries, and full provider parity are excluded. Full verification passed 375 core and 40 harness tests.

| File | Reviewed Git blob |
| --- | --- |
| [ChatCompletionsTransport.scala](../../../../../../../main/scala/com/weisgnosis/agents/core/client/ChatCompletionsTransport.scala) | 955e6ddcfe25efb4e42a9f3c731ea4c97bf6a242 |
| [ChatCompletionsTransportSpec.scala](ChatCompletionsTransportSpec.scala) | 938047f7320998feea85eae23e5d665e09f6619d |

- [x] `review_fp` and `review_harness` approved the final live client/event increment against `60b3799` on 2026-09-29. No findings remain in runtime correlation, validated incremental publication, scoped cleanup, or the migrated/new tests. Full verification passed 386 core and 40 harness tests. Full upstream ordering parity and local tool-result event integration are excluded.

Exact final integration scope (supersedes earlier identities for changed files):

| File | Reviewed Git blob |
| --- | --- |
| [ReActAgent.scala](../../../../../../../main/scala/com/weisgnosis/agents/core/agent/ReActAgent.scala) | 508d37e17aa49cd88be4e80cfb990f698c03c1d0 |
| [ChatCompletionsClient.scala](../../../../../../../main/scala/com/weisgnosis/agents/core/client/ChatCompletionsClient.scala) | b80c5b8b3a46a07ba79804e311307df1d2bb7f39 |
| [ChatCompletionsTransport.scala](../../../../../../../main/scala/com/weisgnosis/agents/core/client/ChatCompletionsTransport.scala) | 696302acc494d81606ab264f29f0e1c97b2e2eab |
| [LlmClient.scala](../../../../../../../main/scala/com/weisgnosis/agents/core/client/LlmClient.scala) | 13094412a3b79d894182cc9b4e31d2bdf68364de |
| [ModelStreamPublisher.scala](../../../../../../../main/scala/com/weisgnosis/agents/core/client/ModelStreamPublisher.scala) | 1ad3bcf5c23e570bf0b86246c23d23efd435575b |
| [AgentEvent.scala](../../../../../../../main/scala/com/weisgnosis/agents/core/event/AgentEvent.scala) | 33df0c53ce69281401cff314812497875b499a1c |
| [ModelStreamContext.scala](../../../../../../../main/scala/com/weisgnosis/agents/core/event/ModelStreamContext.scala) | fc28f0f5accfa3c773f462eb1e2dd8f06c844d7c |
| [ReActAgentEventSpec.scala](../agent/ReActAgentEventSpec.scala) | afac7dc6d5d58feed4ffd138368705df20f4619c |
| [ReActAgentLifecycleSpec.scala](../agent/ReActAgentLifecycleSpec.scala) | 8e042037b425d84514741e42e2620f3d8b889891 |
| [StructuredOutputSpec.scala](../agent/StructuredOutputSpec.scala) | 3e98f032ca5ce23fc6b6a5e08cfc2256edaf967b |
| [ChatCompletionsClientSpec.scala](ChatCompletionsClientSpec.scala) | bd89eb872ee947587848fb9ef9d07ff4c4178641 |
| [ChatCompletionsTransportSpec.scala](ChatCompletionsTransportSpec.scala) | 1d1a5947403ba5750bd2890c4dbfd84479c96bb2 |
| [HttpClientTestSupport.scala](HttpClientTestSupport.scala) | 2e1a8bf59a6dae2d45aebcfc79c2416940821e9f |
| [LlmClientSpec.scala](LlmClientSpec.scala) | 0f2cdb77254e8dbfa325265018a6a96bd85798d8 |
| [ModelStreamPublisherSpec.scala](ModelStreamPublisherSpec.scala) | dbb35a012d48eda8be91a795b20588c49e1a3eff |
| [ModelStreamContextSpec.scala](../event/ModelStreamContextSpec.scala) | bc75ea0694f96aed40e3d4d55c9617842edd644d |

- [x] The completed local/inline result increment against `84e4eb3` was approved by `review_fp` and `review_harness`; its [five final source identities and six new regressions](../agent/README.md) supersede earlier entries for changed files. All sixteen contextual variants and full 392 core / 40 harness verification pass. Partial tool-output streaming and richer local structured output remain pending.

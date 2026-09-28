# Core agent verification

The Phase 3 suites port the applicable reasoning, tool-call, interruption, empty-response, server-tool, and structured-output scenarios from the upstream agent tests. Additional lifecycle regressions exercise ZIO cancellation and failure propagation directly.

| Scala suite | Upstream scenarios and local regressions |
| --- | --- |
| ReActAgentSpec | ReActAgentTest, ReActAgentServerToolTest: initialization, text and thinking, multiple tools, error observations, iteration bounds, inline server results, event streaming |
| ReActAgentInterruptSpec | ReActAgentInterruptToolCallTest: cooperative interruption and fiber cancellation |
| ReActAgentThinkingRetrySpec | ReActAgentEmptyResponseRetryTest: reminder injection and bounded retries |
| StructuredOutputSpec | ReActAgentStructuredOutputTest: synthetic output, schema definitions, metadata decoding |
| ReActAgentLifecycleSpec | Partial completion preservation, interruption on later turns, exactly one recovery, subsequent runs, virtual-clock timeout, streamed failures, native formatting and automatic fallback |

Interrupted local calls receive Error observations, matching the handover and upstream recovery contract. Completed results remain unchanged. The recovery assistant message carries agentscope_generate_reason=interrupted.

The model request and tool execution races operate on Exit values, so a model failure completes the race immediately. Recursive turns restore interruptibility before entering the next mask. Tool execution is interruptible; result persistence is protected by a narrow interruption mask.

Native structured output is represented in LlmConfig.responseFormat. Auto selects native formatting for an empty toolkit and falls back to the synthetic tool on unsupported-format responses. The client implementation remains responsible for serializing the request to its transport.

Run `sbt "core / test"`. The repository-wide verification is `sbt scalafmtAll scalafmtSbt`, followed by `sbt compile` and `sbt test`.

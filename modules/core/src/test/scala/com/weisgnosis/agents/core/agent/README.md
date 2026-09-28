# ReAct execution and structured output: verification

These ZIO Test suites cover selected upstream scenarios and Scala runtime regressions. They do not certify exhaustive upstream parity.

- [AgentConfigSpec](AgentConfigSpec.scala)
- [AgentResultSpec](AgentResultSpec.scala)
- [ReActAgentInterruptSpec](ReActAgentInterruptSpec.scala)
- [ReActAgentLifecycleSpec](ReActAgentLifecycleSpec.scala)
- [ReActAgentSpec](ReActAgentSpec.scala)
- [ReActAgentThinkingRetrySpec](ReActAgentThinkingRetrySpec.scala)
- [StructuredOutputSpec](StructuredOutputSpec.scala)

Run `sbt "core / test"`, or `sbt verify` for repository-wide formatting, compilation, and tests. See the [parity audit](../../../../../../../../../../docs/architecture/test-parity.md) for gaps and intentional differences.

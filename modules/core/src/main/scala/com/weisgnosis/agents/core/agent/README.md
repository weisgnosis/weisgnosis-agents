# ReAct execution and structured output

ReActAgent retains conversation history and serializes runs. Model requests receive their own scope, closed before the next request. Each run owns its event hub. Assistant tool calls are persisted before dispatch; completed observations survive cancellation, and only unfinished local calls receive Error observations followed by one recovery message for that run. Provider-hosted inline results bypass local execution. Thinking-only responses trigger bounded synthetic reminders. AgentConfig uses ToolExecution and a five-minute default timeout; AgentResult uses AgentRunState and optional ChatUsage. StructuredOutput supports native formatting or generate_response with root schema-definition hoisting. Synthetic capture completes immediately and preserves ordinary blocks in mixed turns.

## Implementation

- [AgentConfig](AgentConfig.scala)
- [AgentResult](AgentResult.scala)
- [ReActAgent](ReActAgent.scala)
- [StructuredOutput](StructuredOutput.scala)

See the [repository architecture](../../../../../../../../../../ARCHITECTURE.md) for integration boundaries and invariant definitions.

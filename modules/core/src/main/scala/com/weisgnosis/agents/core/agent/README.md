# ReAct execution and structured output

ReActAgent retains conversation history and serializes runs. Model requests receive their own scope, closed before the next request. Each run owns its event hub. Assistant tool calls are persisted before dispatch; completed observations survive cancellation, and only unfinished local calls receive Error observations followed by one recovery message for that run. Provider-hosted inline results bypass local execution. Thinking-only responses trigger bounded synthetic reminders. AgentConfig uses ToolExecution and a five-minute default timeout; AgentResult uses AgentRunState and optional ChatUsage. StructuredOutput supports native formatting or generate_response with root schema-definition hoisting. Synthetic capture completes immediately and preserves ordinary blocks in mixed turns.

## Implementation

- [AgentConfig](AgentConfig.scala)
- [AgentResult](AgentResult.scala)
- [ReActAgent](ReActAgent.scala)
- [StructuredOutput](StructuredOutput.scala)

See the [repository architecture](../../../../../../../../../../ARCHITECTURE.md) for integration boundaries and invariant definitions.

## Current review acceptance

- [x] Reviewed revision: `fb80428`; accepted files: ReActAgent.scala and ReActAgentLifecycleSpec.scala.
- [x] Independent final approval: `review_harness`, reconfirmed for unchanged code on 2026-09-29 after corrections and cross-review.
- [x] No unresolved findings in the accepted scope: Run/request resource lifetimes, cancellation and timeout cleanup, per-run recovery including empty-input continuation, and reconciliation idempotency.
- [x] Verification at the reviewed revision: `sbt verify`, 108 core and 40 harness tests, no failed tests or compiler warnings.

This is the accepted reference behavior for the scope above. Complete upstream parity and live model transport remain outside this acceptance. Changes to this behavior require renewed independent review and an updated acceptance entry.

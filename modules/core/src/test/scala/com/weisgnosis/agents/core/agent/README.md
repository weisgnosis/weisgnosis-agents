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

## Current review acceptance

- [x] Reviewed revision: `fb80428`; accepted files: ReActAgent.scala and ReActAgentLifecycleSpec.scala.
- [x] Independent final approval: `review_harness`, reconfirmed for unchanged code on 2026-09-29 after corrections and cross-review.
- [x] No unresolved findings in the accepted scope: Run/request resource lifetimes, cancellation and timeout cleanup, per-run recovery including empty-input continuation, and reconciliation idempotency.
- [x] Verification at the reviewed revision: `sbt verify`, 108 core and 40 harness tests, no failed tests or compiler warnings.

This is the accepted reference behavior for the scope above. Complete upstream parity and live model transport remain outside this acceptance. Changes to this behavior require renewed independent review and an updated acceptance entry.

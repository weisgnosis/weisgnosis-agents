# ReAct execution and structured output: verification

These ZIO Test suites cover selected upstream scenarios and Scala runtime regressions. They do not certify exhaustive upstream parity.

- [AgentConfigSpec](AgentConfigSpec.scala)
- [AgentResultSpec](AgentResultSpec.scala)
- [ReActAgentEventSpec](ReActAgentEventSpec.scala)
- [ReActAgentInterruptSpec](ReActAgentInterruptSpec.scala)
- [ReActAgentLifecycleSpec](ReActAgentLifecycleSpec.scala)
- [ReActAgentSpec](ReActAgentSpec.scala)
- [ReActAgentThinkingRetrySpec](ReActAgentThinkingRetrySpec.scala)
- [StructuredOutputSpec](StructuredOutputSpec.scala)

Run `sbt "core / test"`, or `sbt verify` for repository-wide formatting, compilation, and tests. See the [parity audit](../../../../../../../../../../docs/architecture/test-parity.md) for gaps and intentional differences.

Structured completion preserves signed thinking on the final reply exactly once. The reply carries the generating request's usage; AgentResult carries the aggregate for the run. Ordinary tool pairs and unrelated empty messages remain in history. If the run is incomplete, runStructured fails with AgentError.Interrupted and preserves recovery history even when generate_response already captured a payload.

## Structured-output review acceptance at 21bc69f

- [x] Independent reviewers `review_fp` and `review_harness` explicitly approved the final structured-output changes on 2026-09-29 after correction of the interruption-after-capture finding.
- [x] Accepted scope: completed-response thinking/signature transfer, per-request versus aggregate usage, unrelated empty history, mixed-tool pairing, capture isolation, and refusal to finalize incomplete runs.
- [x] No unresolved findings in this scope. Full upstream parity, model-capability selection, structured streaming, and hooks remain pending in the parity task list.
- [x] Verification: `sbt scalafmtAll scalafmtSbt verify`, 118 core and 40 harness tests passed with zero compiler warnings. Three newly added regressions failed before the preservation fixes and passed afterward.

Reviewed change set is based on `a58b0ca`; exact Git blob identities are:

| File | Reviewed blob |
| --- | --- |
| ReActAgent.scala | `d4cb87a525437a8d9193d078044ccd2d3ca9af25` |
| StructuredOutput.scala | `a8a6498baf961f792dba4226cab56d9c6905b0e6` |
| StructuredOutputSpec.scala | `620228e7ebe87e23aca045f8863c8aa856a3bcea` |

This acceptance applies to the named behavior and reviewed content. Changed behavior requires renewed independent review. Prior lifecycle regressions remain part of the full verification suite.

## Event delivery and lifecycle acceptance

The runtime publishes one correlated AgentStart/AgentEnd pair per engine attempt. End follows loop termination and reconciliation even on failure or interruption. Streaming completion uses a private Take queue carrying the original exit; a provider-published AgentEnd cannot terminate delivery. The bridge joins its claimed event prefix before appending the buffered suffix and terminal signal. Early consumer completion closes the owned producer and model scopes before returning.

Publishers must obey scoped ownership. The hub and delivery queue are unbounded; they do not provide backpressure. These boundaries describe engine attempts, not the entire structured-output API: Auto fallback can make two attempts, and structured decoding/history replacement follows the attempt boundary. Structured streaming remains pending.

- [x] Independent reviewers `review_fp` and `review_harness` approved the lifecycle implementation and eight new regressions based on `906cc4a`, on 2026-09-29, with no unresolved findings in this scope.
- [x] Formatting, fatal-warning compilation, and all 177 core / 40 harness tests passed. Three selected boundary regressions failed before this correction.
- [ ] Complete event wire/schema parity and structured streaming; this approval does not include those APIs.

| Reviewed source | Git blob |
| --- | --- |
| ReActAgent.scala | 52a77ff8cda912e8292ae470f765c795fd680d23 |
| ReActAgentEventSpec.scala | 7303fd85ffbb5142f46ca89319a2a785b29b3a74 |

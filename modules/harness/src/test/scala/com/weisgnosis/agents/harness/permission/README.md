# Permission verification

- [PermissionGateSpec](PermissionGateSpec.scala): priority combinations, restricted arguments, defaults, and suspended gate callbacks.
- [PermissionPromiseSpec](PermissionPromiseSpec.scala): registration before publication, one-shot resolution, duplicate requests, cancellation, and late responses.
- [DurableResumeSpec](DurableResumeSpec.scala): serializable values and immutable policy resumption only; no durable ReAct workflow is implied.
- [InteractivePermissionAuthorizerSpec](InteractivePermissionAuthorizerSpec.scala): nine adapter scenarios covering direct decisions, immediate confirmation, both modified-input gates, resource cleanup, concurrent rule additions, latest deny policy, declines, cancellation, typed failure, and publication defects.
- [InteractivePermissionIntegrationSpec](InteractivePermissionIntegrationSpec.scala): six runtime scenarios covering same-phase modified execution, exact denial event order/identity/text/state, parallel repeated IDs, cancellation with completed siblings, policy lifetime, and independently confirmed rules surviving a later signed-input denial.

Sixteen tests were added in this increment: one gate test, nine adapter tests, and six runtime tests. Run `sbt "harness / test"` or repository-wide `sbt verify`. The [permission contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/permission/README.md) owns current source identities and acceptance. The [parity audit](../../../../../../../../../../docs/architecture/test-parity.md) distinguishes verified shared behavior from pending upstream two-call confirmation methods.

- [x] `review_fp` and `review_harness` approved the final five-file interactive permission increment against `5eb4bdc` on 2026-09-29. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 481 core and 61 harness tests, including sixteen added permission tests. Approval covers in-memory active-invocation authorization; durable resumption, authentication, and post-decision revocation remain outside scope.

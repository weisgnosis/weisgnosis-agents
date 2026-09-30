# Permission verification

- [PermissionGateSpec](PermissionGateSpec.scala): priority combinations, restricted arguments, defaults, suspended callbacks, separately scoped sequential composition and failure/cancellation cleanup.
- [PermissionPromiseSpec](PermissionPromiseSpec.scala): registration before publication, one-shot resolution, duplicate requests, cancellation, and late responses.
- [DurableResumeSpec](DurableResumeSpec.scala): serializable values and immutable policy resumption only; no durable ReAct workflow is implied.
- [InteractivePermissionAuthorizerSpec](InteractivePermissionAuthorizerSpec.scala): ten adapter scenarios covering direct decisions, immediate confirmation, both modified-input gates, resource cleanup, concurrent rule additions, latest deny policy, declines, cancellation, typed failure, publication defects, and composed views retaining original authority and shared rules.
- [InteractivePermissionIntegrationSpec](InteractivePermissionIntegrationSpec.scala): six runtime scenarios covering same-phase modified execution, exact denial event order/identity/text/state, parallel repeated IDs, cancellation with completed siblings, policy lifetime, and independently confirmed rules surviving a later signed-input denial.

The three composition additions reuse the existing priority and authorization coverage: optional votes with ordered release, typed/defect/interrupted cleanup that skips later evaluation, and an added-gate view using the same confirmation authority with modified-input denial. Run `sbt "harness / test"` or repository-wide `sbt verify`.

- [x] `path_review` and `current_docs` approved the four changed source/test identities in the [permission contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/permission/README.md#current-review-acceptance), with no unresolved findings.
- [x] Full `scalafmtAll scalafmtSbt verify` passed 561 core and 648 harness tests (1,209 total), zero compiler warnings or failures (`permission-composition-verify.log`).
- [x] [SubagentConstructionSpec](../subagent/SubagentConstructionSpec.scala) verifies opt-in inherited-policy installation in actual nested and reused child runtimes.
- [ ] Complete remaining upstream two-call confirmation, authenticated transport and durable resumption behavior in the [parity audit](../../../../../../../../../../docs/architecture/test-parity.md).

The unchanged runtime integration suite retains its recorded review; composition closes no additional whole upstream method. Affirmative Allow can resolve the implicit default Ask; composition is not a restriction-only capability.

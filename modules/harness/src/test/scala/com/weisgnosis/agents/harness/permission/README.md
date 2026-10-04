# Permission verification

ConfirmedBatchAuthorizerSpec is accepted: four grouped contracts exercise restored repeated-ID batches, modified/fixed/signed input and live policy/gate denial, complete-set and request identity rejection, and cancellation with joined failing gate cleanup. Exact final scope and verification are recorded in the [batch contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/permission/README.md#complete-batch-checkpoint-confirmations).

- [PermissionGateSpec](PermissionGateSpec.scala): priority combinations, restricted arguments, defaults, suspended callbacks, separately scoped sequential composition and failure/cancellation cleanup.
- [PermissionPromiseSpec](PermissionPromiseSpec.scala): registration before publication, one-shot resolution, duplicate requests, cancellation, and late responses.
- [DurableResumeSpec](DurableResumeSpec.scala): serializable values and immutable policy resumption only; no durable ReAct workflow is implied.
- [InteractivePermissionAuthorizerSpec](InteractivePermissionAuthorizerSpec.scala): ten adapter scenarios covering direct decisions, immediate confirmation, both modified-input gates, resource cleanup, concurrent rule additions, latest deny policy, declines, cancellation, typed failure, publication defects, and composed views retaining original authority and shared rules.
- [InteractivePermissionIntegrationSpec](InteractivePermissionIntegrationSpec.scala): six runtime scenarios covering same-phase modified execution, exact denial event order/identity/text/state, parallel repeated IDs, cancellation with completed siblings, policy lifetime, and independently confirmed rules surviving a later signed-input denial.

The three composition additions reuse the existing priority and authorization coverage: optional votes with ordered release, typed/defect/interrupted cleanup that skips later evaluation, and an added-gate view using the same confirmation authority with modified-input denial. Run `sbt "harness / test"` or repository-wide `sbt verify`.

- [x] `path_review` and `current_docs` approved current gate/authorizer metadata forwarding and the retained composition suites in the [permission contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/permission/README.md#current-review-acceptance), with no unresolved findings.
- [x] Full `scalafmtAll scalafmtSbt verify` passed with zero compiler warnings or failures (`plan-admission-verify.log`). Actual metadata forwarding through both composition positions and confirmed-input rechecks is covered by [PlanPermissionIntegrationSpec](../plan/PlanPermissionIntegrationSpec.scala).
- [x] [SubagentConstructionSpec](../subagent/SubagentConstructionSpec.scala) verifies opt-in inherited-policy installation in actual nested and reused child runtimes.
- [ ] Complete remaining partial-confirmation/event mappings, authenticated transport and durable host persistence in the [parity audit](../../../../../../../../../../docs/architecture/test-parity.md).

The unchanged runtime integration suite retains its recorded review; composition closes no additional whole upstream method. Affirmative Allow can resolve the implicit default Ask; composition is not a restriction-only capability.

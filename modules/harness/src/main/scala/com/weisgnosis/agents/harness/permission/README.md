# Permission policy and interactive authorization

PermissionEngine combines independent policy, tool, and interceptor decisions as Deny > Ask > Passthrough > Allow. No matching decision defaults to Ask. PermissionRule applies content restrictions to its explicit argument path. PermissionRequests registers a Promise before publication, resolves once, rejects duplicate request IDs, and removes interrupted waiters. Its serializable PendingApproval/ConfirmResult values support host persistence but do not provide a durable runtime.

## Installation and ownership

Create PermissionRequests with make, then pass it and a publish: ToolAuthorizationRequest => UIO[Unit] callback to InteractivePermissionAuthorizer.make. Install the resulting authorizer with ReActAgent.make(toolAuthorizer = authorizer) or Builder.toolAuthorizer. Without installation, core ToolAuthorizer.allow preserves the explicit allow default. Once installed, the default empty policy asks for confirmation. Hosts resolve requests through PermissionRequests.resolve(requestId, confirmation), using the exact runtime-generated UUID delivered to publish.

The published value includes model context and the original call. Every occurrence gets a distinct approval UUID even if calls reuse an ID. Publication is a narrow callback, not an unrestricted event hub. The adapter does not emit RequireUserConfirm/UserConfirmResult envelopes or implement upstream mixed-batch ordering. Awaiting approval keeps the current invocation active; approval continues the same acting phase without another model request. The configured engine-attempt timeout covers approval waiting, and cancellation still applies. Matched inline provider observations bypass local authorization. Synthetic generate_response is a local call and is also gated; a host policy must allow or confirm it.

The authorizer owns an in-memory Ref of immutable PermissionEngine state, exposed through the effectful policy snapshot. Its lifetime belongs to the host: sharing the same instance shares confirmed rules across runs/agents. It is not serialized agent configuration, an authentication service, or a durable checkpoint. Hosts authenticate the recipient and confirmation source, keep each request associated with its actual session, and avoid calling mutating agent operations from callbacks while the run lock is held.

## Gate and confirmation semantics

PermissionGate evaluates the authoritative tool name and Json input, never cached raw argument text or model control metadata. Its additive overload also accepts optional AdmittedToolAccess from the runtime's admitted Toolkit. This transient, non-codec value uses the actual Tool.isReadOnly accessor; request JSON and model metadata cannot supply it. The default gate abstains. Both injected gates run in separate scopes; resources close before publication/waiting and before dispatch. Gates must handle arbitrary Json; core performs final unique-key JSON and signed/detailed reasoning checks after authorization.

`PermissionGate.and` evaluates both successful votes in order, including after Deny, and combines them with the existing priority. Each evaluator and its cleanup finish in a separate Scope before the next begins. A typed failure, defect or interruption stops composition and retains its cause. None abstains; Allow is an affirmative vote that can resolve the implicit Ask default when all other sources abstain.

`InteractivePermissionAuthorizer.withAdditionalGate` returns an immutable view with the same policy Ref, confirmation requests, publisher and tool gate, composing the added gate after the existing interceptor. The added gate runs for initial admission and confirmed-input rechecks. The original view remains unchanged; confirmed rules are shared. Hosts control which view is installed and must not treat composition as revocation of an older capability or automatic child-policy installation.

Initial decisions use the current policy after gate evaluation. Allow executes without prompting; Deny produces a completed Denied observation without execution. Ask and Passthrough await confirmation. A decline preserves a supplied reason, defaults to "Tool execution declined", installs no new rules, and does not rerun gates.

Positive confirmation reruns both gates against modifiedInput, or the original input if absent. The adapter then atomically resumes against the latest policy and merges newRules. Confirmation satisfies the interactive Ask/Passthrough decision but never overrides a policy or gate Deny. No policy lock spans the human wait, so other requests sharing the authorizer can complete and install a deny that applies to an earlier waiter. The agent run lock remains held; concurrent invocations on the same agent serialize. Gate typed failures remain PermissionDenied; core converts failures and defects into fail-closed Denied observations. Publication defects and cancellation remove pending waiters; late resolution returns false.

Composition forwards the same admitted-access sample to both gates, and confirmation retains it while reevaluating current mode/policy and approved input. Direct legacy calls provide no sample. Migration: existing gate and authorizer implementations keep their original methods through default overload delegation; metadata-aware wrappers must explicitly forward the new argument. SubagentFactory forwards it through live parent-denial checks; the [child construction contract](../subagent/README.md#admitted-tool-plan-access) records scoped acceptance and explicit Plan opt-in. Binary compatibility is not promised; rebuild dependents. PendingApproval and ConfirmResult wire shapes are unchanged. The complete-batch adapter appends optional occurrence data to ToolAuthorizationRequest, described below. Serialized values cannot reconstruct trusted runtime admission evidence; durable hosts must supply their own authenticated boundary.

The initial policy read or final policy.modify is the decision point. Later rule changes do not retroactively revoke an already returned approval. External gate state is not transactional with the policy Ref. Independently confirmed newRules remain installed even if core later denies invalid/signed argument edits, or cancellation prevents execution after policy modification; confirmation is not an execution transaction. Declines and cancellation before that modification do not install rules. Hosts needing atomic policy/execution checkpoints require the still-pending durable integration.

Core owns exact approved-input persistence, original-input provenance, stale-content removal, and occurrence-based cancellation reconciliation. This adapter returns only an input or denial and cannot change call identity/name/state. A denied observation follows the existing result processor and correlated Start/Delta/End path.

## Complete-batch checkpoint confirmations

ConfirmedBatchAuthorizer supplies checked complete-batch authorization for restored before-acting checkpoints. Its checked factory binds a suspended AgentResult to a complete set of ConfirmedToolCall values, each containing the original assistant call ordinal and a ConfirmResult. Ordinals distinguish repeated IDs; completed inline provider occurrences are excluded. Missing, duplicate, unknown or inline-completed confirmations reject the batch before execution. The [supplied-result contract](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#supplied-results-for-suspended-batches) excludes host-supplied completed ordinals from required coverage; supplied execution observations are distinct from human confirmations.

The adapter installs on a host-created runtime before restoring and resuming the checkpoint. It supplies only ordinary ToolAuthorization decisions; core retains history, fixed-argument checks, signature protection and scoped execution authority. Identity binds the saved model context, original assistant ordinal, call ID and name. Other requests fail closed without policy or gate effects, including later tool calls produced after this batch; this authorizer is not a general interactive fallback. Positive confirmations evaluate separately scoped gates on the modified input, or the runtime-prepared input when no replacement is supplied, including trusted admitted-access evidence. The current host policy is sampled only after both gates and their finalizers finish; that snapshot is the policy decision point. Later changes do not retroactively revoke returned approvals, and gate state is not transactional with that snapshot. A confirmation cannot override Deny on that candidate. Positive confirmations contribute immutable ruleUpdates in ordinal order; declined confirmations skip policy/gate effects and contribute no rules. The adapter overlays those updates on each sampled policy without mutating shared policy state. The host decides whether and how to persist those updates. Checkpoint authentication, storage, exclusive claims and side-effect idempotency remain host responsibilities.

Migration: ToolAuthorizationRequest appends optional callOrdinal, defaulting to None for old construction and missing JSON fields. Its case-class arity changes, so positional patterns and dependent binaries need updating. Decoded ordinal data is not trusted runtime authority. The checked adapter requires the matching runtime occurrence; existing interactive authorizers remain separately supported. Partial confirmations and partial-result resumption are not part of this batch contract.

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants at the nine identities below, with no remaining findings. Full `scalafmtAll scalafmtSbt verify` passed in `confirmed-batch-verify.log`, with zero compiler warnings or failures; the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance) owns current totals. Focused `confirmed-batch-green.log` passed 36 harness tests in 636 ms and 26 core tests in 434 ms.

| File | Reviewed Git blob |
| --- | --- |
| [ConfirmedBatchAuthorizer.scala](ConfirmedBatchAuthorizer.scala) | [Current supplied-result identity](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#supplied-results-for-suspended-batches) |
| [ConfirmResult.scala](ConfirmResult.scala) | dbae0b1f7588ec27e2aa5755f77a4a200541fb38 |
| [ConfirmedBatchAuthorizerSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/permission/ConfirmedBatchAuthorizerSpec.scala) | [Current supplied-result identity](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#supplied-results-for-suspended-batches) |
| [PendingActing.scala](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/PendingActing.scala) | [Current supplied-result identity](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#supplied-results-for-suspended-batches) |
| [ReActAgent.scala](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/ReActAgent.scala) | [Current supplied-result identity](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#supplied-results-for-suspended-batches) |
| [ToolHistory.scala](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/model/ToolHistory.scala) | 29b2733e5d9d9ce9906e8d6d05eeb8ea3314af33 |
| [ToolHistorySpec.scala](../../../../../../../../../core/src/test/scala/com/weisgnosis/agents/core/model/ToolHistorySpec.scala) | 36b9751869fc81a8ed6c708e874ac54339cbd5e1 |
| [ToolAuthorizer.scala](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/tool/ToolAuthorizer.scala) | 6a5d051a2c2fd07e5e22d4ab97f3db5e770e6501 |
| [ToolAuthorizerSpec.scala](../../../../../../../../../core/src/test/scala/com/weisgnosis/agents/core/tool/ToolAuthorizerSpec.scala) | b101c62056f31891f1c164fa0f79032066656453 |

Regression: `confirmed-batch-policy-red.log` reproduced stale-policy admission at authorizer `a212e408899e1833ae3c70174a2250028217f83e` with spec `2a97e9f98f87774d4ad2cfd3e66ba28cd4c065a4` (three passed, one failed, 579 ms): a Deny installed while a gate waited still allowed execution and changed history. Sampling policy after both gate scopes fixed the unchanged regression in `confirmed-batch-green.log`.

- [ ] Integrate partial confirmations and host persistence separately.

## Implementation

- [InteractivePermissionAuthorizer](InteractivePermissionAuthorizer.scala)
- [PermissionGate](PermissionGate.scala)
- [PermissionEngine](PermissionEngine.scala)
- [PermissionRule](PermissionRule.scala)
- [PermissionRequests](PermissionRequests.scala)
- [PermissionBehavior](PermissionBehavior.scala)
- [ConfirmResult and pending/outcome values](ConfirmResult.scala)

See the [test index](../../../../../../../test/scala/com/weisgnosis/agents/harness/permission/README.md) and [parity audit](../../../../../../../../../../docs/architecture/test-parity.md). Complete-batch checkpoint confirmation is defined above. Authenticated transport, checkpoint/idempotency storage, partial human confirmations, post-decision revocation and complete upstream permission mode/batch-event parity remain separate.

## Current review acceptance

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for current gate/authorizer metadata forwarding, composition and confirmation rechecks, with no findings. Unchanged composition suites retain their acceptance; the [Plan integration](../plan/README.md#current-admission-identities) records actual runtime evidence and its exact identities.
- [x] Full `scalafmtAll scalafmtSbt verify` passed with zero compiler warnings or failures (`plan-admission-verify.log`); the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance) records current totals.
- [x] The reviewed [SubagentConstruction](../subagent/README.md) path uses composition for inherited child policy. Raw authorizer views remain host-controlled and are not revoked.
- [x] Install checked complete-batch confirmation on restored core checkpoints.
- [ ] Implement partial human confirmations, authenticated durable delivery and remaining upstream permission mode/batch-event integration.

The unchanged runtime integration suite retains `review_fp` and `review_harness` approval against `5eb4bdc`. The table below retains the active-invocation authorization and composition scope. Complete-batch checkpoint confirmation has its own current identities above; neither scope establishes exhaustive upstream parity or post-decision revocation.

| File | Reviewed Git blob |
| --- | --- |
| [PermissionGate.scala](PermissionGate.scala) | `a7c5ba2384fd9eb94f42c23693ca3f07d55a8968` |
| [InteractivePermissionAuthorizer.scala](InteractivePermissionAuthorizer.scala) | `803cef552811b3f1974d954bc6cea257e0f02d15` |
| [PermissionGateSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/permission/PermissionGateSpec.scala) | `9b5b4cd0e4d28763fe0905bc6387b8213fc3fd5f` |
| [InteractivePermissionAuthorizerSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/permission/InteractivePermissionAuthorizerSpec.scala) | `79b0aa4b4c179f1baf45a79a5dff31f0c062cbc0` |
| [InteractivePermissionIntegrationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/permission/InteractivePermissionIntegrationSpec.scala), unchanged | `39018f4506ff02e122901c661f7d7d96cedfa192` |

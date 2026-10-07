# Permission policy and interactive authorization

PermissionEngine combines independent policy, tool, and interceptor decisions as Deny > Ask > Passthrough > Allow. No matching decision defaults to Ask. PermissionRule applies content restrictions to its explicit argument path. PermissionRequests registers a Promise before publication, resolves once, rejects duplicate request IDs, and removes interrupted waiters. Its serializable PendingApproval/ConfirmResult values support host persistence but do not provide a durable runtime.

## Installation and ownership

Create PermissionRequests with make, then pass it and a publish: ToolAuthorizationRequest => UIO[Unit] callback to InteractivePermissionAuthorizer.make. Install the resulting authorizer with ReActAgent.make(toolAuthorizer = authorizer) or Builder.toolAuthorizer. Without installation, core ToolAuthorizer.allow preserves the explicit allow default. Once installed, the default empty policy asks for confirmation. Hosts resolve requests through PermissionRequests.resolve(requestId, confirmation), using the exact runtime-generated UUID delivered to publish.

The published value includes model context and the original call. Every occurrence gets a distinct approval UUID even if calls reuse an ID. Publication is a narrow callback, not an unrestricted event hub. The adapter does not emit RequireUserConfirm/UserConfirmResult envelopes; its bounded initial-denial ordering is defined below. Awaiting approval keeps the current invocation active; approval continues the same acting phase without another model request. The configured engine-attempt timeout covers approval waiting, and cancellation still applies. Matched inline provider observations bypass local authorization. Synthetic generate_response is a local call and is also gated; a host policy must allow or confirm it.

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

## Interactive batch preflight

ToolAuthorizer.preflight is an initial denial screen returning ToolPreflight.Continue or Denied(reason). Its default Continue preserves legacy single-call authorization; it grants no approval. InteractivePermissionAuthorizer screens only current policy Deny rules, without evaluating dynamic gates, registering a Promise, publishing Ask or awaiting confirmation. Its shared policy adds rules without removing existing denials, so this early denial is monotonic. Every Continue later runs ordinary authorize afresh, including separately scoped live gates/current policy and existing confirmed-input rechecks; no gate result or approval is cached.

After persisting assistant calls, ReActAgent prepares fixed arguments and one request ID/original ordinal per unresolved local occurrence. Ephemeral prepared data preserves those identities through preflight and ordinary authorization, including saved checkpoint request IDs and repeated call IDs. Provider-owned/completed occurrences are excluded. Signed-input guards and approved JSON/fixed-input validation remain authoritative.

The runtime joins each preflight scope, then persists and processes initial policy-Denied/preparation-error observations through the existing result processor and correlated result events before any remaining authorization can publish Ask. Processor failure or cancellation suppresses later asks and retains existing cleanup/cause/reconciliation behavior. Remaining calls retain the configured sequential or concurrency-safe parallel execution policy. The ordering guarantee covers initial monotonic policy denials and preparation errors only. Dynamic gate decisions remain at execution-time authorization so earlier plan_enter/plan_exit operations can change later admission; fresh authorization or confirmation-time checks can deny later. Preflight is no execution lease or atomic revocation guarantee.

The interactive path remains inside one invocation; it does not silently suspend/restore a checkpoint or replace interactive policy updates with ConfirmedBatchAuthorizer. A custom preflight Denied is terminal and may be recorded ahead of earlier calls: it must remain valid despite their execution or confirmations. Mutable/live conditions must Continue through ordinary authorization. Custom forwarding authorizers must explicitly forward preflight to retain the wrapped early-denial capability; default Continue alone promises no initial denial detection.

Migration: ToolPreflight and the defaulted preflight extension are public additions. Existing source implementations retain their ordinary authorize method; downstream binaries and extension wrappers require compatibility assessment/rebuilding. No serialized checkpoint/configuration shape changes and no frozen ABI are claimed.

review_fp and review_invariants approved the final five-file source scope against c507dad; combined documentation approval passed:

| Source/spec | Final Git blob |
| --- | --- |
| [ToolAuthorizer.scala](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/tool/ToolAuthorizer.scala) | 946c1f63d9680828a752339c81443484f93ad9e3 |
| [ReActAgent.scala](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/ReActAgent.scala) | 5a342f24210d3885cf0778cfeb30f44ff274f6cb |
| [InteractivePermissionAuthorizer.scala](InteractivePermissionAuthorizer.scala) | 58bf407ac717804ba4f7ef8712864fc406fbedbf |
| [InteractivePermissionAuthorizerSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/permission/InteractivePermissionAuthorizerSpec.scala) | 9a16a26acd21c7592f8477fc75f39d2d24389147 |
| [InteractivePermissionIntegrationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/permission/InteractivePermissionIntegrationSpec.scala) | 68ca22bfdf29c865296eae5bd8b6c40f395304cf |

interactive-batch-order-red.log records the previously unsupported new-contract baseline against unchanged c507dad: the Ask snapshot lacked persisted denied observations and event order differed. Dynamic gate preflight is outside the narrowed accepted target. This is additive contract evidence, not a defect claim against the previously accepted profile. Three new groups (two runtime integrations and one authorizer group) cover this extension. The earlier draft incorrectly captured dynamic Plan denials: interactive-batch-order-verify.log records six unchanged Plan test failures. Policy-only screening fixes that reproduced regression; final focused/full verification and renewed independent source approval pass.

- [x] Both reviewers explicitly approved the five final identities after narrowing preflight to stable policy denials; no source findings remain.
- [x] interactive-batch-plan-green.log passes 36 focused tests in 1.520 seconds with unchanged Plan expectations. interactive-batch-final-verify.log passes all 1,520 tests (674 core / 730 harness / 86 MCP / 22 PostgreSQL / 8 examples), zero compiler warnings/failures/ignored tests, and four RC1 packages. The final authorizer oracle additionally proves both dynamic gates remain untouched by preflight.
- [x] interactive-batch-preparation.log passes exact RC1 source/POM/LICENSE admission and invalid-selector/extra/duplicate-source rejection with restoration. interactive-batch-consumer.log passes standalone formatting, clean compilation and forked public API execution against four fresh actual RC1 hashes; the unchanged custom authorizer also exercises default Continue during actual runtime dispatch.
- [x] review_fp and review_invariants approved the final seventeen-file source/documentation scope against c507dad with no unresolved findings. Manual English inspection, diff checks and 78-document/2,912-local-reference validation pass with zero issues.

## Complete-batch checkpoint confirmations

ConfirmedBatchAuthorizer supplies checked complete-batch authorization for restored before-acting checkpoints. Its checked factory binds a suspended AgentResult to a complete set of ConfirmedToolCall values, each containing the original assistant call ordinal and a ConfirmResult. Ordinals distinguish repeated IDs; completed inline provider occurrences are excluded. Missing, duplicate, unknown or inline-completed confirmations reject the batch before execution. The [supplied-result contract](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#supplied-results-for-suspended-batches) excludes host-supplied completed ordinals from required coverage; supplied execution observations are distinct from human confirmations.

The adapter installs on a host-created runtime before restoring and resuming the checkpoint. Core streamResumeEvents delivers resumed events through the shared invocation owner; its actual table checks custom/default denial history and the original-context Start/Delta/End sequence. The [stream acceptance](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#result-bearing-streams) owns the current runtime/test identities and verification. It supplies only ordinary ToolAuthorization decisions; core retains history, fixed-argument checks, signature protection and scoped execution authority. Identity binds the saved model context, original assistant ordinal, call ID and name. Other requests fail closed without policy or gate effects, including later tool calls produced after this batch; this authorizer is not a general interactive fallback. Positive confirmations evaluate separately scoped gates on the modified input, or the runtime-prepared input when no replacement is supplied, including trusted admitted-access evidence. The current host policy is sampled only after both gates and their finalizers finish; that snapshot is the policy decision point. Later changes do not retroactively revoke returned approvals, and gate state is not transactional with that snapshot. A confirmation cannot override Deny on that candidate. Positive confirmations contribute immutable ruleUpdates in ordinal order; declined confirmations skip policy/gate effects and contribute no rules. The adapter overlays those updates on each sampled policy without mutating shared policy state. The host decides whether and how to persist those updates. Checkpoint authentication, storage, exclusive claims and side-effect idempotency remain host responsibilities.

Migration: ToolAuthorizationRequest appends optional callOrdinal, defaulting to None for old construction and missing JSON fields. Its case-class arity changes, so positional patterns and dependent binaries need updating. Decoded ordinal data is not trusted runtime authority. The checked adapter requires the matching runtime occurrence; existing interactive authorizers remain separately supported. The partial extension below retains this complete factory; supplied-result resumption has its own core contract.

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants at the nine identities below, with no remaining findings. Full `scalafmtAll scalafmtSbt verify` passed in `confirmed-batch-verify.log`, with zero compiler warnings or failures; the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance) owns current totals. Focused `confirmed-batch-green.log` passed 36 harness tests in 636 ms and 26 core tests in 434 ms.

| File | Reviewed Git blob |
| --- | --- |
| [ConfirmedBatchAuthorizer.scala](ConfirmedBatchAuthorizer.scala) | [Current reviewed identity](README.md#partial-checkpoint-confirmations) |
| [ConfirmResult.scala](ConfirmResult.scala) | dbae0b1f7588ec27e2aa5755f77a4a200541fb38 |
| [ConfirmedBatchAuthorizerSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/permission/ConfirmedBatchAuthorizerSpec.scala) | [Current supplied-result identity](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#supplied-results-for-suspended-batches) |
| [PendingActing.scala](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/PendingActing.scala) | [Current reviewed event identity](README.md#checkpoint-permission-events) |
| [ReActAgent.scala](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/ReActAgent.scala) | [Current interactive-preflight identity](#interactive-batch-preflight), final combined documentation approval passed; prior behavioral scope retained. |
| [ToolHistory.scala](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/model/ToolHistory.scala) | [Current provider-ownership identity](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#provider-owned-pending-calls) |
| [ToolHistorySpec.scala](../../../../../../../../../core/src/test/scala/com/weisgnosis/agents/core/model/ToolHistorySpec.scala) | [Current provider-ownership identity](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#provider-owned-pending-calls) |
| [ToolAuthorizer.scala](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/tool/ToolAuthorizer.scala) | [Current interactive-preflight identity](#interactive-batch-preflight), final combined documentation approval passed; prior behavioral scope retained. |
| [ToolAuthorizerSpec.scala](../../../../../../../../../core/src/test/scala/com/weisgnosis/agents/core/tool/ToolAuthorizerSpec.scala) | [Current reviewed identity](README.md#partial-checkpoint-confirmations) |

Regression: `confirmed-batch-policy-red.log` reproduced stale-policy admission at authorizer `a212e408899e1833ae3c70174a2250028217f83e` with spec `2a97e9f98f87774d4ad2cfd3e66ba28cd4c065a4` (three passed, one failed, 579 ms): a Deny installed while a gate waited still allowed execution and changed history. Sampling policy after both gate scopes fixed the unchanged regression in `confirmed-batch-green.log`.

- [x] Independently review and verify the partial extension below; host persistence remains external.

## Partial checkpoint confirmations

`ConfirmedBatchAuthorizer.makePartial` accepts a checked subset of the unresolved original call ordinals. `make` retains complete-coverage validation. Duplicate, unknown, inline-completed, already-recorded and host-supplied ordinals reject either factory. A matching unresolved call without a confirmation returns `ToolAuthorization.Deferred("Awaiting host confirmation")` without reading policy or running gates. Positive and negative decisions retain the policy, fixed-input and signature checks described above. `PendingActing.pendingCalls` provides checked original-ordinal local choices; provider-owned pending calls cannot receive confirmations or supplied local results; repeated call IDs are not completion identities.

After all admitted execution and processor scopes join, ordinary deferred batches return `Suspended` with `PendingActingStage.AwaitingApproval(completed, latestToolGroups, stop)`. CompletedToolCall records map original call ordinals bijectively to the Tool-message tail after the saved assistant. Restore checks exact identity/state and the complete prefix, reconstructs executable tools from host bindings, and preserves original admission separately from latest group activation. Saved control tools and future model requests share that latest activation. Completed local/supplied observations and inline processors are not replayed. New supplied results remain supported for unresolved ordinals.

Deferred calls produce no Tool observation or result Start/Delta/End events. A blank deferral reason becomes Denied. Completed results remain in history; the assistant carries PermissionAsking while suspended. Resume preserves original context and iteration, clears only the runtime suspension tag, and executes only unresolved occurrences before another model request. A saved processor Stop is selected by original ordinal and takes effect only after every deferred call resolves. Cancellation joins cleanup, preserves completed results and reconciles only unfinished calls with one interrupted recovery. Structured modes reject deferral and finalize unfinished calls without publishing a checkpoint or selecting Auto fallback.

Migration: ToolAuthorization gains the codec case Deferred(reason), requiring exhaustive matches to handle it. PendingActing appends stage with default BeforeActing; old missing-field checkpoints retain that meaning. The event-correlation extension below appends default-empty permissionRequests and requires nine-field positional patterns and rebuilt binaries. AwaitingApproval is data, not authentication or execution authority. SubagentFactory forwards Deferred through existing live parent-denial checks; child sessions support [explicit host-only resume](../subagent/README.md#host-only-session-resume). InteractivePermissionAuthorizer remains the active-invocation Promise adapter. The nonblocking policy adapter below is accepted; storage, authentication, exclusive claims and idempotency remain host responsibilities.

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants at the ten formatted identities below, with no remaining findings. Full `scalafmtAll scalafmtSbt verify` passed in `partial-confirmations-final-verify.log` with zero compiler warnings or failures; current totals belong to the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance). Focused tests passed 31 core cases in 823 ms and eight harness cases in 508 ms. Five new groups cover ledger validation, structured rejection, successive restored approvals, saved Stop and joined cancellation.

| File | Reviewed Git blob |
| --- | --- |
| [PendingActing.scala](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/PendingActing.scala) | [Current reviewed event identity](README.md#checkpoint-permission-events) |
| [ReActAgent.scala](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/ReActAgent.scala) | [Current interactive-preflight identity](#interactive-batch-preflight), final combined documentation approval passed; prior behavioral scope retained. |
| [ToolAuthorizer.scala](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/tool/ToolAuthorizer.scala) | [Current interactive-preflight identity](#interactive-batch-preflight), final combined documentation approval passed; prior behavioral scope retained. |
| [ConfirmedBatchAuthorizer.scala](ConfirmedBatchAuthorizer.scala) | 89d5a44f35184fcfa15480de22325697b07a4e09 |
| [SubagentFactory.scala](../subagent/SubagentFactory.scala) | [Current host-resume identity](../subagent/README.md#host-only-session-resume) |
| [PendingActingSpec.scala](../../../../../../../../../core/src/test/scala/com/weisgnosis/agents/core/agent/PendingActingSpec.scala) | [Current reviewed event identity](README.md#checkpoint-permission-events) |
| [ReActAgentResumeSpec.scala](../../../../../../../../../core/src/test/scala/com/weisgnosis/agents/core/agent/ReActAgentResumeSpec.scala) | [Current ReActAgentResumeSpec.scala identity](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#checked-registry-identity) |
| [ReActAgentToolAuthorizerSpec.scala](../../../../../../../../../core/src/test/scala/com/weisgnosis/agents/core/agent/ReActAgentToolAuthorizerSpec.scala) | [Current reviewed fixture identity](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#all-tools-denied-observation) |
| [ToolAuthorizerSpec.scala](../../../../../../../../../core/src/test/scala/com/weisgnosis/agents/core/tool/ToolAuthorizerSpec.scala) | 1d5cdf458c1fc66d266c54af94d5ba3c59ab915d |
| [PartialConfirmationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/permission/PartialConfirmationSpec.scala) | [Current reviewed event identity](README.md#checkpoint-permission-events) |

## Nonblocking checkpoint policy

`CheckpointPermissionAuthorizer(currentPolicy, toolGate, interceptorGate)` is an opt-in ToolAuthorizer. Construction is pure. Each authorization evaluates the tool and interceptor gates in separate joined scopes, then samples the host-owned policy and delegates to `PermissionEngine.authorize`. Allow returns the prepared input, Deny retains the policy denial reason, and Ask or Passthrough returns Deferred without creating a Promise, registering a request, mutating policy or publishing approval envelopes. Gate failures, defects and interruption retain their causes; core applies its established fail-closed/cancellation boundary. Trusted admitted-access metadata reaches both gates.

The policy read after both gate finalizers is the decision point; later policy changes are not atomic revocation. The runtime finishes admitted work and returns the existing partial checkpoint. Hosts obtain request data with `PendingActing.pendingCalls`, then install a checked ConfirmedBatchAuthorizer on the receiving runtime and restore/resume. Pending calls retain original model arguments: framework-fixed arguments are prepared again by the receiving host bindings before authorization. A host that previews effective arguments must use those bindings rather than treating the checkpoint as prepared input or authorization.

This additive adapter leaves InteractivePermissionAuthorizer's active-invocation Promise workflow unchanged. No existing constructor or codec changes. Host authentication, storage, exclusive claims and side-effect idempotency remain external. Runtime checkpoint event correlation is accepted below; RequireUserConfirm/UserConfirmResult envelopes are not implemented by this adapter.

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for the three exact identities below, with no findings. Full `scalafmtAll scalafmtSbt verify` passed in `checkpoint-permission-verify.log` with zero compiler warnings or failures; the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance) owns current totals. Focused `checkpoint-permission-focused.log` passed 11 tests in 653 ms. Two unit groups cover precedence, lazy effects, admitted metadata, live policy after cleanup and joined cancellation; one runtime table covers Ask/Allow, returned pending data, fixed inputs and actual confirmed restoration without repeated reasoning.

| File | Reviewed Git blob |
| --- | --- |
| [CheckpointPermissionAuthorizer.scala](CheckpointPermissionAuthorizer.scala) | 74e612c981d9ae9c5b4c99bbbb782429741923a3 |
| [CheckpointPermissionAuthorizerSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/permission/CheckpointPermissionAuthorizerSpec.scala) | be8386e54d5d31fa880928d0604fe7ea168d69fe |
| [CheckpointPermissionIntegrationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/permission/CheckpointPermissionIntegrationSpec.scala) | [Current reviewed event identity](README.md#checkpoint-permission-events) |

## Checkpoint permission events

The runtime stores `PendingPermissionRequest(callOrdinal, requestId, input)` records for deferred local occurrences. Each record retains the authorization UUID and framework-prepared input; original assistant arguments remain unchanged until approval. Re-deferral keeps that UUID, refreshes the prepared-input snapshot and publishes another Ask. Nonempty records must cover every unfinished local ordinal exactly once, including supplied results not yet persisted, with unique nonblank IDs and valid unique-key JSON. BeforeActing requires no records; legacy AwaitingApproval checkpoints may retain an empty collection.

After all admitted execution and processor scopes join successfully, the runtime commits the partial checkpoint, emits PermissionAskEvent values in original-ordinal order, then RequestStopEvent(PermissionAsking). Completed denied results precede those asks. Failure or cancellation of the acting phase prevents this suspension publication. Events correlate through the recorded requestId; generic PermissionAsk/PermissionDecision payloads have no replyId, callId or human-source assertion. Hosts can map records to checked pending ordinals and the checkpoint's saved model context.

On resume, a recorded request's final Approved or Denied authorization emits PermissionDecisionEvent before its ToolResultStart. Core fixed-argument and signature checks run before that decision. Supplied results, preparation failures and legacy empty records create no fabricated decision. Correlation is not proof of user approval, execution or persistence; the installed host authorizer remains authoritative. Structured deferral still fails without a checkpoint or Ask. Existing stream early-consumer limits and host authentication/storage/exclusive-claim responsibilities remain unchanged.

Migration: PendingActing appends default-empty permissionRequests, making nine positional fields. Missing-field JSON preserves existing empty-record behavior; rebuild dependent binaries and update positional patterns. PermissionAskEvent and PermissionDecisionEvent wire shapes are unchanged. This integration uses generic runtime decisions, not Java RequireUserConfirm/UserConfirmResult envelopes.

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for the five identities below, with no findings. Full `scalafmtAll scalafmtSbt verify` passed in `permission-events-verify.log`, with zero compiler warnings or failures; the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance) owns current totals. Focused `permission-events-green.log` passed 25 core tests in 597 ms and ten harness tests in 716 ms. Existing tables cover repeated IDs and successive restored asks/decisions, fixed prepared input and malformed records; one new phase-drain group covers ordered denial publication, cleanup failure/cancellation suppression and supplied-result bypass.

| File | Reviewed Git blob |
| --- | --- |
| [PendingActing.scala](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/PendingActing.scala) | [Current provider-ownership identity](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#provider-owned-pending-calls) |
| [ReActAgent.scala](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/ReActAgent.scala) | [Current interactive-preflight identity](#interactive-batch-preflight), final combined documentation approval passed; prior behavioral scope retained. |
| [PendingActingSpec.scala](../../../../../../../../../core/src/test/scala/com/weisgnosis/agents/core/agent/PendingActingSpec.scala) | [Current provider-ownership identity](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#provider-owned-pending-calls) |
| [CheckpointPermissionIntegrationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/permission/CheckpointPermissionIntegrationSpec.scala) | [Current CheckpointPermissionIntegrationSpec.scala identity](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#checked-registry-identity) |
| [PartialConfirmationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/permission/PartialConfirmationSpec.scala) | [Current strengthened event-oracle identity](#checkpoint-event-order-oracle), final documentation review passed |

## Implementation

- [InteractivePermissionAuthorizer](InteractivePermissionAuthorizer.scala)
- [CheckpointPermissionAuthorizer](CheckpointPermissionAuthorizer.scala)
- [PermissionGate](PermissionGate.scala)
- [PermissionEngine](PermissionEngine.scala)
- [PermissionRule](PermissionRule.scala)
- [PermissionRequests](PermissionRequests.scala)
- [PermissionBehavior](PermissionBehavior.scala)
- [ConfirmResult and pending/outcome values](ConfirmResult.scala)

See the [test index](../../../../../../../test/scala/com/weisgnosis/agents/harness/permission/README.md) and [parity audit](../../../../../../../../../../docs/architecture/test-parity.md). Complete-batch checkpoint confirmation is defined above. Authenticated transport, checkpoint/idempotency storage, post-decision revocation and complete upstream permission mode/batch-event parity remain separate.

## Current review acceptance

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for current gate/authorizer metadata forwarding, composition and confirmation rechecks, with no findings. Unchanged composition suites retain their acceptance; the [Plan integration](../plan/README.md#current-admission-identities) records actual runtime evidence and its exact identities.
- [x] Full `scalafmtAll scalafmtSbt verify` passed with zero compiler warnings or failures (`plan-admission-verify.log`); the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance) records current totals.
- [x] The reviewed [SubagentConstruction](../subagent/README.md) path uses composition for inherited child policy. Raw authorizer views remain host-controlled and are not revoked.
- [x] Install checked complete-batch confirmation on restored core checkpoints.
- [x] Independently review and verify partial confirmations.
- [x] Independently review and verify nonblocking policy admission below.
- [x] Independently review and verify generic checkpoint permission-event correlation.
- [ ] Durable delivery and broader event parity remain separate.

The unchanged runtime integration suite retains `review_fp` and `review_harness` approval against `5eb4bdc`. The table below retains the active-invocation authorization and composition scope. Complete-batch checkpoint confirmation has its own current identities above; neither scope establishes exhaustive upstream parity or post-decision revocation.

| File | Reviewed Git blob |
| --- | --- |
| [PermissionGate.scala](PermissionGate.scala) | `a7c5ba2384fd9eb94f42c23693ca3f07d55a8968` |
| [InteractivePermissionAuthorizer.scala](InteractivePermissionAuthorizer.scala) | [Current interactive-preflight identity](#interactive-batch-preflight), final combined documentation approval passed; prior behavioral scope retained. |
| [PermissionGateSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/permission/PermissionGateSpec.scala) | `9b5b4cd0e4d28763fe0905bc6387b8213fc3fd5f` |
| [InteractivePermissionAuthorizerSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/permission/InteractivePermissionAuthorizerSpec.scala) | [Current interactive-preflight identity](#interactive-batch-preflight), final combined documentation approval passed; prior behavioral scope retained. |
| [InteractivePermissionIntegrationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/permission/InteractivePermissionIntegrationSpec.scala), behavior preserved | [Current interactive-preflight identity](#interactive-batch-preflight), final combined documentation approval passed; prior behavioral scope retained. |

## Checkpoint event-order oracle

The existing PartialConfirmationSpec denied-before-Ask group now checks the exact collected Start/Delta/End phase and call-ID sequence, exactly one Denied end state and one RequestStop, alongside End-before-Ask-before-Stop and existing failure/cancellation/cleanup cases. This removes reliance on chained indexWhere comparisons that could pass with a missing Start at index -1. It strengthens a test oracle, changes no production behavior, adds no group and claims no reproduced runtime defect.

- [x] review_fp and review_invariants approved PartialConfirmationSpec blob 529f0dbba249dd552365ed699fbab540ab9a0b0e against 2e16c3f, with no unresolved findings.
- [x] checkpoint-order-contract-focused.log passed four tests in 1.110 seconds.
- [x] checkpoint-order-contract-verify.log exited zero: 1,517 tests (674 core / 727 harness / 86 MCP / 22 PostgreSQL / 8 examples), zero compiler warnings/failures/ignored tests. Unchanged production/build/consumer retain scoped accepted 8792744 RC artifact evidence.
- [x] review_fp and review_invariants approved the final combined eight-file test/documentation scope against 2e16c3f, with PartialConfirmationSpec blob 529f0dbba249dd552365ed699fbab540ab9a0b0e unchanged. Full verification passed all 1,517 tests with zero compiler warnings/failures/ignored tests. Manual English inspection, diff checks and 78-document/2,885-local-reference validation pass with zero issues.

# Permission policy and approval channels

The [invocation and prepared-run contract](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#invocation-identity-and-prepared-execution) separates fresh execution correlation from saved model-request identity. Ordinary prepared runs expose a lazy single-attempt handle; Auto attempts share one invocation and resumed executions receive a new one. Confirmation authority remains bound to the exact saved reply/request pair and call occurrence, independently of observational invocation identity.

PermissionEngine combines independent policy, tool, and interceptor decisions as Deny > Ask > Passthrough > Allow. Default mode maps no matching decision to Ask. PermissionRule applies content restrictions to its explicit argument path. PermissionRequests registers a Promise before publication, resolves once, rejects duplicate request IDs, and removes interrupted waiters. Its serializable PendingApproval/ConfirmResult values support host persistence but do not provide a durable runtime.

## Permission rule advice

`InteractivePermissionAuthorizer.makeWithAdvisories` publishes a `PermissionAdvisory` containing the exact authorization request and immutable suggested rules. The original `make` callback remains unchanged and advice-free. Advice is produced only for a final Ask/Passthrough outcome, never an authoritative Deny. Each provider runs in a bounded Scope that closes before registering or publishing a pending approval. Provider failure or cancellation leaves no pending waiter.

`PermissionSuggestions.default` suggests an exact tool-level Allow rule; wildcard, blank and malformed Unicode names receive no default advice. Custom providers are trusted host presentation capabilities and must bound their work. Suggestions are neither permission votes nor automatic policy updates. Only explicit human confirmation with selected `ConfirmResult.newRules`, followed by current authorization checks, can install a rule. Existing Deny precedence and admitted tool access remain authoritative.

`CheckpointPermissionAuthorizer.advisories(checkpoint, suggestions)` validates an AwaitingApproval checkpoint and presents only its actual saved permission requests in original ordinal order, preserving checked prepared inputs, request IDs and context. It does not infer requests from other unfinished calls, reevaluate permission, resume execution or make a model call. Advice is regenerated through the supplied provider; hosts needing identical presentation after restart persist it separately. Presentation data grants no execution authority.

## Host-selected permission modes

`PermissionEngine(rules, mode = PermissionMode.Default)` has five explicit modes. Independent votes remain Deny > Ask > Passthrough > Allow; mode fallbacks never bypass a supplied safety vote.

| Mode | Fallback and restrictions |
| --- | --- |
| Default | No vote becomes Ask. |
| Bypass | No vote becomes Allow; explicit Ask, Passthrough and Deny remain effective. |
| DontAsk | Unresolved Ask/Passthrough becomes Deny; an actual Allow vote remains usable. |
| Explore | Only actual admitted ReadOnly access is eligible; mutation or absent evidence becomes Deny. Eligible calls retain explicit votes, otherwise Allow. |
| AcceptEdits | Admitted ReadOnly calls without votes become Allow; mutation or absent evidence follows normal Ask fallback. The name does not authorize arbitrary edits. |

The runtime supplies access from the admitted Toolkit. Model fields and host descriptive access maps cannot manufacture it. Interactive, checkpoint, unavailable-channel and confirmed-batch authorization all propagate the sample; positive confirmation still checks current restrictions. Interactive preflight retains stable explicit denials and Explore restrictions, but does not apply DontAsk fallback before live gates can supply Allow.

Child construction inherits only matching explicit parent Deny rules, checked before and after approved input rewriting. A parent's mode does not silently replace the child's independent policy. Parent Plan restrictions remain separate authoritative gates.

JSON uses exact scalar names `default`, `bypass`, `dont_ask`, `explore`, `accept_edits`; `fromString` additionally accepts case-insensitive host names. Trailing defaults preserve ordinary engine/evaluation calls; PermissionEngine product arity changes from one to two and affected JVM signatures require rebuilt consumers. The lower-level `resume` projects confirmation/rule updates only; install an authorizer to enforce current gates/mode before execution.

[PermissionModeSpec](../../../../../../../test/scala/com/weisgnosis/agents/harness/permission/PermissionModeSpec.scala), the checkpoint integration table and actual child construction cover these contracts. Java permission short-circuiting remains a separate applicability decision. Suggested rules are presentation advice, not authority granted by a mode.

## Installation and ownership

Create PermissionRequests with make, then pass it and a publish: ToolAuthorizationRequest => UIO[Unit] callback to InteractivePermissionAuthorizer.make. Install the resulting authorizer with ReActAgent.make(toolAuthorizer = authorizer) or Builder.toolAuthorizer. Without installation, core ToolAuthorizer.allow preserves the explicit allow default. Once installed, the default empty policy asks for confirmation. Hosts resolve requests through PermissionRequests.resolve(requestId, confirmation), using the exact runtime-generated UUID delivered to publish.

The published value includes model context and the original call. Every occurrence gets a distinct approval UUID even if calls reuse an ID. Publication is a narrow callback, not an unrestricted event hub. The adapter does not emit RequireUserConfirm/UserConfirmResult envelopes; its bounded initial-denial ordering is defined below. Awaiting approval keeps the current invocation active; approval continues the same acting phase without another model request. The configured engine-attempt timeout covers approval waiting, and cancellation still applies. Matched inline provider observations bypass local authorization. Synthetic generate_response is a local call and is also gated; a host policy must allow or confirm it.

The authorizer owns an in-memory Ref of immutable PermissionEngine state, exposed through the effectful policy snapshot. Its lifetime belongs to the host: sharing the same instance shares confirmed rules across runs/agents. It is not serialized agent configuration, an authentication service, or a durable checkpoint. Hosts authenticate the recipient and confirmation source, keep each request associated with its actual session, and avoid calling mutating agent operations from callbacks while the run lock is held.

## Unavailable approval channel

Install [UnavailablePermissionAuthorizer](UnavailablePermissionAuthorizer.scala) when the host explicitly has no channel for confirmation:

```scala
val authorizer = UnavailablePermissionAuthorizer(ZIO.succeed(PermissionEngine()))
```

Construction stores dependencies without reading policy or running gates. Each authorization evaluates both independent gates in separate joined scopes, then samples current policy. Deny retains its reason; Allow returns prepared input. Ask and Passthrough become Denied with an unavailable-channel reason. It creates no PermissionRequests, publisher, Promise or deferred checkpoint. Install it through ReActAgent's existing toolAuthorizer parameter/builder or as SubagentPermissions.authorizer.

This is explicit host selection, never inferred from model output, declarations or a saved remote URI. It does not replace interactive waiting or durable Deferred handling. `withAdditionalGate` retains the same live policy effect and composes the added gate after the existing interceptor; Plan restrictions and ancestor denials still apply through standard child construction. Gate/policy failures and cancellation retain the existing core fail-closed/interrupt boundary. Policy sampling is a decision snapshot, not atomic execution revocation. The host must bound its supplied effects; absence of a human wait does not make arbitrary gates terminate.

Migration: SubagentPermissions.authorizer now accepts InteractivePermissionAuthorizer or UnavailablePermissionAuthorizer. Existing constructor arguments remain valid, but callers using Interactive-only members through this field must pattern-match the variant. Product arity is unchanged; constructor/accessor/copy JVM signatures may change. Rebuild dependents; no binary compatibility is promised. Permission and checkpoint wire formats are unchanged.

The [adapter suite](../../../../../../../test/scala/com/weisgnosis/agents/harness/permission/UnavailablePermissionAuthorizerSpec.scala) checks priority, admitted input/access and policy after gate cleanup. [SubagentConstructionSpec](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentConstructionSpec.scala) checks actual child execution/denial and Plan mutation restrictions. Interactive and checkpoint suites remain regression controls.

## Gate and confirmation semantics

PermissionGate evaluates the authoritative tool name and Json input, never cached raw argument text or model control metadata. Its additive overload also accepts optional AdmittedToolAccess from the runtime's admitted Toolkit. This transient, non-codec value uses the actual Tool.isReadOnly accessor; request JSON and model metadata cannot supply it. The default gate abstains. Both injected gates run in separate scopes; resources close before publication/waiting and before dispatch. Gates must handle arbitrary Json; core performs final unique-key JSON and signed/detailed reasoning checks after authorization.

`PermissionGate.and` evaluates both successful votes in order, including after Deny, and combines them with the existing priority. Each evaluator and its cleanup finish in a separate Scope before the next begins. A typed failure, defect or interruption stops composition and retains its cause. None abstains; Allow is an affirmative vote that can resolve the implicit Ask default when all other sources abstain.

`InteractivePermissionAuthorizer.withAdditionalGate` returns an immutable view with the same policy Ref, confirmation requests, publisher and tool gate, composing the added gate after the existing interceptor. The added gate runs for initial admission and confirmed-input rechecks. The original view remains unchanged; confirmed rules are shared. Hosts control which view is installed and must not treat composition as revocation of an older capability or automatic child-policy installation.

Initial decisions use the current policy after gate evaluation. Allow executes without prompting; Deny produces a completed Denied observation without execution. Ask and Passthrough await confirmation. A decline preserves a supplied reason, defaults to "Tool execution declined", installs no new rules, and does not rerun gates.

Positive confirmation reruns both gates against modifiedInput, or the original input if absent. The adapter then atomically resumes against the latest policy and merges newRules. Confirmation satisfies the interactive Ask/Passthrough decision but never overrides a policy or gate Deny. No policy lock spans the human wait, so other requests sharing the authorizer can complete and install a deny that applies to an earlier waiter. The agent run lock remains held; concurrent invocations on the same agent serialize. Gate typed failures remain PermissionDenied; core converts failures and defects into fail-closed Denied observations. Publication defects and cancellation remove pending waiters; late resolution returns false.

Composition forwards the same admitted-access sample to both gates, and confirmation retains it while reevaluating current mode/policy and approved input. Direct legacy calls provide no sample. Migration: existing gate and authorizer implementations keep their original methods through default overload delegation; metadata-aware wrappers must explicitly forward the new argument. SubagentFactory forwards it through live parent-denial checks; the [child construction contract](../subagent/README.md#admitted-tool-plan-access) describes explicit Plan opt-in and scoped ownership. Binary compatibility is not promised; rebuild dependents. PendingApproval and ConfirmResult wire shapes are unchanged. The complete-batch adapter appends optional occurrence data to ToolAuthorizationRequest, described below. Serialized values cannot reconstruct trusted runtime admission evidence; durable hosts must supply their own authenticated boundary.

The initial policy read or final policy.modify is the decision point. Later rule changes do not retroactively revoke an already returned approval. External gate state is not transactional with the policy Ref. Independently confirmed newRules remain installed even if core later denies invalid/signed argument edits, or cancellation prevents execution after policy modification; confirmation is not an execution transaction. Declines and cancellation before that modification do not install rules. Hosts needing atomic policy/execution checkpoints require the still-pending durable integration.

Core owns exact approved-input persistence, original-input provenance, stale-content removal, and occurrence-based cancellation reconciliation. This adapter returns only an input or denial and cannot change call identity/name/state. A denied observation follows the existing result processor and correlated Start/Delta/End path.

## Interactive batch preflight

ToolAuthorizer.preflight is an initial denial screen returning ToolPreflight.Continue or Denied(reason). Its default Continue preserves legacy single-call authorization; it grants no approval. InteractivePermissionAuthorizer screens only current policy Deny rules, without evaluating dynamic gates, registering a Promise, publishing Ask or awaiting confirmation. Its shared policy adds rules without removing existing denials, so this early denial is monotonic. Every Continue later runs ordinary authorize afresh, including separately scoped live gates/current policy and existing confirmed-input rechecks; no gate result or approval is cached.

After persisting assistant calls, ReActAgent prepares fixed arguments and one request ID/original ordinal per unresolved local occurrence. Ephemeral prepared data preserves those identities through preflight and ordinary authorization, including saved checkpoint request IDs and repeated call IDs. Provider-owned/completed occurrences are excluded. Signed-input guards and approved JSON/fixed-input validation remain authoritative.

The runtime joins each preflight scope, then persists and processes initial policy-Denied/preparation-error observations through the existing result processor and correlated result events before any remaining authorization can publish Ask. Processor failure or cancellation suppresses later asks and retains existing cleanup/cause/reconciliation behavior. Remaining calls retain the configured sequential or concurrency-safe parallel execution policy. The ordering guarantee covers initial monotonic policy denials and preparation errors only. Dynamic gate decisions remain at execution-time authorization so earlier plan_enter/plan_exit operations can change later admission; fresh authorization or confirmation-time checks can deny later. Preflight is no execution lease or atomic revocation guarantee.

The interactive path remains inside one invocation; it does not silently suspend/restore a checkpoint or replace interactive policy updates with ConfirmedBatchAuthorizer. A custom preflight Denied is terminal and may be recorded ahead of earlier calls: it must remain valid despite their execution or confirmations. Mutable/live conditions must Continue through ordinary authorization. Custom forwarding authorizers must explicitly forward preflight to retain the wrapped early-denial capability; default Continue alone promises no initial denial detection.

Migration: ToolPreflight and the defaulted preflight extension are public additions. Existing source implementations retain their ordinary authorize method; downstream binaries and extension wrappers require compatibility assessment/rebuilding. No serialized checkpoint/configuration shape changes and no frozen ABI are claimed.

## Complete-batch checkpoint confirmations

ConfirmedBatchAuthorizer supplies checked complete-batch authorization for restored before-acting checkpoints. Its checked factory binds a suspended AgentResult to a complete set of ConfirmedToolCall values, each containing the original assistant call ordinal and a ConfirmResult. Ordinals distinguish repeated IDs; completed inline provider occurrences are excluded. Missing, duplicate, unknown or inline-completed confirmations reject the batch before execution. The [supplied-result contract](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#supplied-results-for-suspended-batches) excludes host-supplied completed ordinals from required coverage; supplied execution observations are distinct from human confirmations.

The adapter installs on a host-created runtime before restoring and resuming the checkpoint. Core streamResumeEvents delivers resumed events through the shared invocation owner; its actual table checks custom/default denial history and the original-context Start/Delta/End sequence. The [stream contract](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#result-bearing-streams) describes runtime ownership and consumer boundaries. It supplies only ordinary ToolAuthorization decisions; core retains history, fixed-argument checks, signature protection and scoped execution authority. Identity binds the saved model context, original assistant ordinal, call ID and name. Other requests fail closed without policy or gate effects, including later tool calls produced after this batch; this authorizer is not a general interactive fallback. Positive confirmations evaluate separately scoped gates on the modified input, or the runtime-prepared input when no replacement is supplied, including trusted admitted-access evidence. The current host policy is sampled only after both gates and their finalizers finish; that snapshot is the policy decision point. Later changes do not retroactively revoke returned approvals, and gate state is not transactional with that snapshot. A confirmation cannot override Deny on that candidate. Positive confirmations contribute immutable ruleUpdates in ordinal order; declined confirmations skip policy/gate effects and contribute no rules. The adapter overlays those updates on each sampled policy without mutating shared policy state. The host decides whether and how to persist those updates. Checkpoint authentication, storage, exclusive claims and side-effect idempotency remain host responsibilities.

Migration: ToolAuthorizationRequest appends optional callOrdinal, defaulting to None for old construction and missing JSON fields. Its case-class arity changes, so positional patterns and dependent binaries need updating. Decoded ordinal data is not trusted runtime authority. The checked adapter requires the matching runtime occurrence; existing interactive authorizers remain separately supported. The partial extension below retains this complete factory; supplied-result resumption has its own core contract.

## Partial checkpoint confirmations

`ConfirmedBatchAuthorizer.makePartial` accepts a checked subset of the unresolved original call ordinals. `make` retains complete-coverage validation. Duplicate, unknown, inline-completed, already-recorded and host-supplied ordinals reject either factory. A matching unresolved call without a confirmation returns `ToolAuthorization.Deferred("Awaiting host confirmation")` without reading policy or running gates. Positive and negative decisions retain the policy, fixed-input and signature checks described above. `PendingActing.pendingCalls` provides checked original-ordinal local choices; provider-owned pending calls cannot receive confirmations or supplied local results; repeated call IDs are not completion identities.

After all admitted execution and processor scopes join, ordinary deferred batches return `Suspended` with `PendingActingStage.AwaitingApproval(completed, latestToolGroups, stop)`. CompletedToolCall records map original call ordinals bijectively to the Tool-message tail after the saved assistant. Restore checks exact identity/state and the complete prefix, reconstructs executable tools from host bindings, and preserves original admission separately from latest group activation. Saved control tools and future model requests share that latest activation. Completed local/supplied observations and inline processors are not replayed. New supplied results remain supported for unresolved ordinals.

Deferred calls produce no Tool observation or result Start/Delta/End events. A blank deferral reason becomes Denied. Completed results remain in history; the assistant carries PermissionAsking while suspended. Resume preserves original context and iteration, clears only the runtime suspension tag, and executes only unresolved occurrences before another model request. A saved processor Stop is selected by original ordinal and takes effect only after every deferred call resolves. Cancellation joins cleanup, preserves completed results and reconciles only unfinished calls with one interrupted recovery. Structured modes reject deferral and finalize unfinished calls without publishing a checkpoint or selecting Auto fallback.

Migration: ToolAuthorization gains the codec case Deferred(reason), requiring exhaustive matches to handle it. PendingActing appends stage with default BeforeActing; old missing-field checkpoints retain that meaning. The event-correlation extension below appends default-empty permissionRequests and requires updated positional patterns and rebuilt binaries under the [current checkpoint migration](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#external-tool-bindings). AwaitingApproval is data, not authentication or execution authority. SubagentFactory forwards Deferred through existing live parent-denial checks; child sessions support [explicit host-only resume](../subagent/README.md#host-only-session-resume). InteractivePermissionAuthorizer remains the active-invocation Promise adapter. The nonblocking policy adapter below supplies that admission path; storage, authentication, exclusive claims and idempotency remain host responsibilities.

## Nonblocking checkpoint policy

`CheckpointPermissionAuthorizer(currentPolicy, toolGate, interceptorGate)` is an opt-in ToolAuthorizer. Construction is pure. Each authorization evaluates the tool and interceptor gates in separate joined scopes, then samples the host-owned policy and delegates to `PermissionEngine.authorize`. Allow returns the prepared input, Deny retains the policy denial reason, and Ask or Passthrough returns Deferred without creating a Promise, registering a request, mutating policy or publishing approval envelopes. Gate failures, defects and interruption retain their causes; core applies its established fail-closed/cancellation boundary. Trusted admitted-access metadata reaches both gates.

The policy read after both gate finalizers is the decision point; later policy changes are not atomic revocation. The runtime finishes admitted work and returns the existing partial checkpoint. Hosts obtain request data with `PendingActing.pendingCalls`, then install a checked ConfirmedBatchAuthorizer on the receiving runtime and restore/resume. Pending calls retain original model arguments: framework-fixed arguments are prepared again by the receiving host bindings before authorization. A host that previews effective arguments must use those bindings rather than treating the checkpoint as prepared input or authorization.

This additive adapter leaves InteractivePermissionAuthorizer's active-invocation Promise workflow unchanged. No existing constructor or codec changes. Host authentication, storage, exclusive claims and side-effect idempotency remain external. Runtime checkpoint event correlation is described below; RequireUserConfirm/UserConfirmResult envelopes are not implemented by this adapter.

## Checkpoint permission events

The runtime stores `PendingPermissionRequest(callOrdinal, requestId, input)` records for deferred local occurrences. Each record retains the authorization UUID and framework-prepared input; original assistant arguments remain unchanged until approval. Re-deferral keeps that UUID, refreshes the prepared-input snapshot and publishes another Ask. Nonempty records must cover every unfinished local ordinal exactly once, including supplied results not yet persisted, with unique nonblank IDs and valid unique-key JSON. BeforeActing requires no records; legacy AwaitingApproval checkpoints may retain an empty collection.

After all admitted execution and processor scopes join successfully, the runtime commits the partial checkpoint, emits PermissionAskEvent values in original-ordinal order, then RequestStopEvent(PermissionAsking). Completed denied results precede those asks. Failure or cancellation of the acting phase prevents this suspension publication. Events correlate through the recorded requestId; generic PermissionAsk/PermissionDecision payloads have no replyId, callId or human-source assertion. Hosts can map records to checked pending ordinals and the checkpoint's saved model context.

On resume, a recorded request's final Approved or Denied authorization emits PermissionDecisionEvent before its ToolResultStart. Core fixed-argument and signature checks run before that decision. Supplied results, preparation failures and legacy empty records create no fabricated decision. Correlation is not proof of user approval, execution or persistence; the installed host authorizer remains authoritative. Structured deferral still fails without a checkpoint or Ask. Existing stream early-consumer limits and host authentication/storage/exclusive-claim responsibilities remain unchanged.

Migration: PendingActing retains default-empty permissionRequests; the [current external-binding migration](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#external-tool-bindings) records its extended case-class shape. Missing-field JSON preserves existing empty-record behavior; rebuild dependent binaries and update positional patterns. PermissionAskEvent and PermissionDecisionEvent wire shapes are unchanged. This integration uses generic runtime decisions, not Java RequireUserConfirm/UserConfirmResult envelopes.

## Implementation

- [InteractivePermissionAuthorizer](InteractivePermissionAuthorizer.scala)
- [UnavailablePermissionAuthorizer](UnavailablePermissionAuthorizer.scala)
- [CheckpointPermissionAuthorizer](CheckpointPermissionAuthorizer.scala)
- [ConfirmedBatchAuthorizer](ConfirmedBatchAuthorizer.scala)
- [PermissionGate](PermissionGate.scala)
- [PermissionEngine](PermissionEngine.scala)
- [PermissionRule](PermissionRule.scala)
- [PermissionRequests](PermissionRequests.scala)
- [PermissionBehavior](PermissionBehavior.scala)
- [ConfirmResult and pending/outcome values](ConfirmResult.scala)

See the [test index](../../../../../../../test/scala/com/weisgnosis/agents/harness/permission/README.md) and [parity audit](../../../../../../../../../../docs/architecture/test-parity.md). Complete-batch checkpoint confirmation is defined above. Authenticated transport, checkpoint/idempotency storage, post-decision revocation and broader upstream rule/batch-event parity remain separate.

## Current review acceptance

Current permission behavior is described above and exercised by the [test index](../../../../../../../test/scala/com/weisgnosis/agents/harness/permission/README.md). SubagentConstruction composes the live inherited policy and Plan gates; raw authorizer views remain host-controlled and are not revoked. The [tracker](../../../../../../../../../../docs/architecture/tracker.md) records remaining library outcomes. Review decisions belong in the task or PR conversation rather than this package document.

Durable delivery, authenticated transport, post-decision revocation and broader upstream permission/event parity are separate contracts.

## Checkpoint event-order oracle

PartialConfirmationSpec checks the exact collected Start/Delta/End phase and call-ID sequence, exactly one Denied end state and one RequestStop, alongside End-before-Ask-before-Stop and existing failure/cancellation/cleanup cases. Exact sequence and count assertions prevent missing events from satisfying relative-order comparisons. This is a test oracle, not additional runtime behavior.

# Scoped child execution and configured factories

[TaskSupervisor](TaskSupervisor.scala), [SupervisedTask](SupervisedTask.scala) and [SubagentSpawner](SubagentSpawner.scala) provide host-owned execution and runtime lineage. [SubagentFactory](SubagentFactory.scala) assembles fresh configured ReAct children with a final [static tool boundary](SubagentToolBoundary.scala) and optional live parent-denial inheritance. [SubagentTaskRegistry](SubagentTaskRegistry.scala) adds bounded task admission and inspection. These APIs are accepted within the exact reviewed scope below.

## Ownership, cancellation and promotion

`TaskSupervisor.open[A]` creates an execution owner in the host Scope. `attach` accepts a suspended scoped task and a separate parent cancellation lease. Admission is serialized against host closure; closed admission evaluates no task callback. Launch registration is masked, but task execution remains interruptible. Task effects and completion waits never hold the admission permit.

The supervisor owns each fiber from launch. Closing the attaching parent Scope cancels attached work and joins all task cleanup, including cleanup already started by explicit cancellation. `await` observes the complete Exit without cancelling. `poll` observes completed work without waiting and reports completion only after owned cleanup finishes. `cancel` also stops promoted work and waits for cleanup. Parent/host finalizers join tasks; task failures remain in the handle Exit. Live handles, admissions and lineage capabilities have no JSON codecs.

`awaitOrPromote` races completion against a fresh 30-second budget on each call, without restarting work. Only the winning timer's masked transition promotes the attached lease. Released work cannot be promoted. Promoted work survives parent closure; explicit cancellation and host closure still stop it. At simultaneous completion/deadline, the race winner decides. Cancelling a waiting observer does not by itself cancel the task or reverse committed promotion.

Host shutdown closes worker scopes in parallel, preventing one blocked finalizer from postponing interruption of other workers. Each worker closes its own sequential scope on completion, avoiding the ZIO 2.1.26 `forkIn` self-join deadlock. Task resources keep ordinary scoped cleanup and complete failure causes. Explicitly masked work or blocked finalizers can delay cancellation indefinitely. Tasks/finalizers must not wait for an owner or parent whose closure is joining them, or for their own handle. The host must retain its Scope for all desired background work; no daemon lifetime, remote adoption or restart durability exists.

## Shared resource ownership

`TaskSupervisor.own(initialize)` acquires a [TaskResource](TaskResource.scala) in both the supervisor and acquiring caller Scope. Closed admission evaluates no initializer. The initializer receives a nested supervisor and runs interruptibly; its scoped acquisitions outlive initialization and individual nested tasks. Initialization failure, defect or cancellation joins partial cleanup before returning, preserving the failure Exit supplied to resource finalizers.

Explicit `resource.close`, acquiring-caller finalization and supervisor shutdown share one closure authority. It closes nested admission, joins initialization and all nested workers (including queued and promoted work), then releases shared resources exactly once. Competing library close paths wait for the same complete cleanup outcome, including defects. The first close supplies the resource finalizer Exit: explicit close and normal supervisor shutdown use success; an initialization failure or caller finalizer can supply failure. This does not alter concurrent calls to a raw ZIO `Scope.close`. Completed resource registrations detach from the supervisor.

The host must retain the acquiring Scope for the intended resource lifetime. Owned workers and finalizers must not close or await their own resource owner. Resource values and handles are process-local capabilities, with no codecs, session lookup, restart recovery or child-runtime reuse implied.

Migration: `TaskSupervisor` adds abstract `own[B](TaskSupervisor[A] => ZIO[Scope, AgentError, B]): ZIO[Scope, AgentError, TaskResourceAdmission[B]]`. Custom implementations and test doubles must implement or delegate this ownership contract. A short-lived `ZIO.scoped` acquisition or `attach` call is not an equivalent implementation.

## Runtime-owned lineage

`SubagentSpawner.root[A]` suspends creation of a UUID-derived root at depth zero. Trusted hosts control root and retained capability distribution. Private construction and no codec prevent descriptive fields from recreating authority. A retained spawner can be reused under another caller Scope while its supervisor is open.

`spawn` supplies the callback a new descendant capability under the supervisor worker Scope. It preserves structural ancestry and the supervisor, admitting depths one through three. Depth three rejects another spawn with `AgentError.SpawnDepthExceeded(3)` before identity allocation or callback invocation. Constructing a spawn effect and closed admission invoke no callback. At an eligible depth, identity allocation precedes admission and may consume an unused UUID if admission is closed.

`source` is slash-separated UUID lineage for diagnostics, not a path, registry credential or automatically assigned event tag. Nested attached cancellation waits for descendant cleanup and ReAct reconciliation. Promoted descendants remain supervisor-owned. The tool boundary normalizes depth failure to an Error observation. Model-facing controls require the explicit installation below.

Migration: exhaustive `AgentError` matches must handle the existing `SpawnDepthExceeded(maximumDepth)` leaf. Its codec conveys diagnostics only.

## Current lineage identities

These unchanged files retain the scoped `review_fp` and `review_harness` approval against `279b7a3` for runtime-owned depth, lineage, nested cancellation and typed depth errors.

| Source | Git blob |
| --- | --- |
| [SubagentSpawner.scala](SubagentSpawner.scala) | `75cd20da3998b0d66b8afb050a8c2e5012ce44b4` |
| [SubagentSpawnerSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentSpawnerSpec.scala) | `b2866e1bab047dc31f463a1f809d45fe4676b7ba` |
| [SubagentSpawnerAgentSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentSpawnerAgentSpec.scala) | `56b59a7fa9a7af22f4d63b8578533a068312cd21` |
| [AgentError.scala](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/error/AgentError.scala) | `4b4b37d57e469975eaf208e8466fa1640fa8e11f` |
| [AgentErrorSpec.scala](../../../../../../../../../core/src/test/scala/com/weisgnosis/agents/core/error/AgentErrorSpec.scala) | `b564c375448d4a6789dc2e45f9261c02a8075650` |

## Configured child construction

`SubagentFactory.make` validates AgentConfig and combines host-supplied parent and child boundaries without acquiring resources. `narrowed` returns an immutable factory whose selection is intersected and denials accumulated. Exact tool names are used. `AllAvailable` means all tools present at construction, including automatic registrations; `Only(Set.empty)` permits none. Unknown selected names do not create tools.

`build` suspends the registration callback, validates inherited and registered map keys against declared names, merges all registrations with collision rejection, and only then applies the static filter before client acquisition and `ReActAgent.make`. Invalid keys are checked in lexical key order, inherited before registered. Registration collisions are checked in lexical key order even for excluded names. The inherited Toolkit is immutable. Tool existential types are preserved without casts.

Each build creates fresh runtime/history state with the supplied AgentConfig, LlmConfig and ToolAuthorizer. The authorizer still controls actual execution of statically available tools. Static filtering is separate from the explicit live denial wrapper described below. Host callbacks are trusted adapters and must suspend external work, translate boundary failures and acquire resources with scoped combinators.

The caller must retain `build`'s Scope for the runtime lifetime; acquired registration/client resources are released when that Scope closes, including after construction failure. `spawn` delegates to `SubagentSpawner` so construction and execution share a supervised worker Scope, including cancellation and cleanup. Depth rejection and closed admission precede factory callbacks. Ordinary `spawn` does not itself wait or promote; the returned handle supplies those operations.

A raw spawner does not automatically carry the factory's static boundary or inherited-denial wrapper. Trusted host callbacks can construct another factory or task. Model-facing controls retain the registry's configured factory; hosts must separately configure any nested child controls. Declaration parsing, automatic plan propagation, persistent child reuse and send integration remain pending. No production automatic registrations are introduced: the callback supplies host-selected registrations.

## Live parent-denial inheritance

`inheritingParentDenials(readParentPolicy: UIO[PermissionEngine])` returns an immutable factory derivative. The supplied policy effect runs only during authorization. It reads parent policy before delegating to the child's existing authorizer, then reads again against the exact `Approved(input)` returned by that authorizer. A matching parent Deny produces a Denied observation without execution. All other parent decisions abstain; the child's Allow, Ask, Deny, confirmation requests and confirmed rules remain its own. Call identity is unchanged, and the parent's authorizer or approval request service is never invoked or copied.

Hosts can supply the existing interactive authorizer's effectful policy read. This inherits rules from that engine, not external parent tool/interceptor gates, permission modes or working directories. `narrowed` preserves the wrapper; multiple inherited wrappers retain their separate denial checks. Provider defects fail closed through the existing ReAct authorization boundary, while interruption remains cancellation and joins scoped cleanup.

Positive child confirmation is checked against both modified arguments and a fresh parent policy read. The final read is the decision point; later updates do not atomically revoke already returned approval. Live replacement does not accumulate historical parent rules, and confirmed child rules may remain installed even when the final parent check denies execution. Durable policy history, execution transactions and automatic inheritance through future spawn/send tools are host integration work.

## Bounded task registry

`SubagentTaskRegistry.open(parent, factory, maxTracked = 256)` acquires a registry in a host Scope and requires positive capacity. Each `submit` creates a fresh configured child through the retained factory and parent spawner. Accepted submissions return a registry-local UUID task ID; other outcomes are Capacity, Closed or IdentityCollision. Depth rejection remains a typed spawn error. Rejected admission acquires no child factory resources and retains no record. IDs identify execution records within one registry; they are not durable sessions or standalone authority.

Capacity counts every retained record, including completed results. `forgetCompleted` removes only terminal work, returning Removed, Running or Missing. This frees record capacity without restarting or replacing a child. The bound is a record count, not a bound on the bytes retained by full AgentResult histories and causes. Hosts must retire completed records according to their retention needs.

`inspect` and `list` observe Running or Completed with a complete Exit, preserving typed failures, defects, interruption and cleanup causes. A list captures membership once and polls each entry independently; it is not an atomic completion snapshot. Lookup-based await, promotion and cancellation return None for missing IDs and otherwise delegate to the existing task handle. They do not hold the admission permit while waiting or joining cleanup. Completed records remain inspectable after registry closure until explicitly forgotten or the registry becomes unreachable.

Each submission owns a private caller cancellation lease. Admission records the task before attaching that lease's finalizer to the caller Scope, outside the admission permit. An already closed caller can therefore join its child cleanup without hiding the retained record or blocking unrelated capacity checks. Closing a normal caller cancels and joins attached work; promoted work survives caller closure. Closing the registry first closes admission, then cancels registered tasks in parallel and joins cleanup, including promoted tasks, even if the underlying supervisor outlives the registry. No persistent child reuse, remote ownership or durable restart recovery is provided.

## Model-facing task controls

[SubagentTools.make](SubagentTools.scala) returns an immutable opt-in Toolkit bound to one registry and its fixed parent lineage and factory. `agent_spawn` accepts exactly one string field, `prompt`. `agent_task` and `agent_cancel` accept exactly one string field, `task_id`, using canonical hyphenated UUID syntax. Missing, duplicate, extra or wrong-type fields are rejected. Model input cannot select another factory, parent, depth, timeout or session. Only inspection is marked read-only; trusted host plan classifications still control eligibility.

`agent_spawn` submits a fresh child and waits through the existing 30-second promotion budget. Its invocation owns a scoped caller lease. Completion returns the retained ID and result projection; promotion returns that same ID while the same execution continues. Caller cancellation before promotion interrupts the wait and closes the attached lease, joining child cleanup. Committed promotion survives that tool invocation, so the host must keep both registry and supervisor Scopes alive for the desired background lifetime. Either owner closing still stops its owned work.

`agent_task` polls retained status without a model request or restart. `agent_cancel` waits for task cleanup and leaves the terminal record inspectable. Completed results remain unchanged by cancellation. Neither tool changes configured child capabilities or supplies persistent conversation reuse.

Spawn rejection, missing IDs, child failure and a child's interrupted result become Error observations through the standard tool boundary. Interruption of the calling fiber remains cancellation, not a manufactured success or Error reply. Inspecting or cancelling an existing failed/interrupted child is a successful observation of that terminal state. Result DTOs expose IDs and Running, Promoted, Completed, Suspended, Failed, Interrupted or Missing projections, never task handles. Typed failure text is retained; defect-only detail uses a generic message.

Reply and failure text previews are bounded to 4,000 UTF-16 code units, including a head/tail separator; truncation avoids splitting surrogate pairs. `omittedCharacters` counts omitted original UTF-16 units, excluding the separator. JSON framing is outside this text bound. The registry retains the complete original Exit and AgentResult, so bounded tool output does not bound retained memory.

The host can install [PlanPermissionGate.inherited](../plan/PlanPermissionGate.scala) in the child's authorizer to restrict admission whenever the live parent or child mode is Plan. This adapter changes neither session nor review authority, and it adds no default exemption for spawn/task/cancel names. Nested factories, automatic tool installation, persistent reuse and `agent_send` still require explicit integration.

## Verification and acceptance

The [test index](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/README.md) maps current contract evidence and bounded upstream adaptations. The unchanged lineage and static boundary retain their recorded approvals; the supervisor's current resource extension is reviewed below.

On 2026-09-30, `path_review` and `current_docs` independently approved the final supervisor/resource source and tests below, including the registry fixture's `own` delegation, with no unresolved FP/ZIO or lifecycle findings. Acceptance covers retained resource acquisition, joined initialization/worker cleanup, first-close Exit preservation and competing-close outcome sharing. Existing task controls, registry/polling, factory and boundary approvals remain scoped to their listed identities; inherited plan admission is recorded at the [plan admission identities](../plan/README.md#current-admission-identities). Full formatting and `verify` passed 561 core and 631 harness tests (1,192 total), with zero compiler warnings or failed tests; evidence is `resource-owner-focused.log` and `resource-owner-verify.log`.

| Reviewed source | Git blob |
| --- | --- |
| [TaskSupervisor.scala](TaskSupervisor.scala) | `83aefd60831a5dbd60778327d216f589acbb7372` |
| [TaskResource.scala](TaskResource.scala) | `1398310565fc51bb5f525a55fd7acb3868969217` |
| [TaskResourceSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/TaskResourceSpec.scala) | `d8e62d19ef0b00ab6d68cc47afcc6d42ce43a85f` |
| [SubagentTools.scala](SubagentTools.scala) | `6f7d8556997377723229aad775ed92c2d0071c5a` |
| [SubagentToolsSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentToolsSpec.scala) | `045d006f2e3304604d802d5280fb8fa4c208d032` |
| [SubagentTaskRegistry.scala](SubagentTaskRegistry.scala) | `bf32c16f1eba0ac244d7ffa42781a40af2d593d7` |
| [SupervisedTask.scala](SupervisedTask.scala) | `f3ddf1c653699ecd98cb021d4065608d561d591f` |
| [SubagentTaskRegistrySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentTaskRegistrySpec.scala) | `adcb1e66ff2105137a35339d6bf7ab5e5b9b5475` |
| [SubagentFactory.scala](SubagentFactory.scala) | `2d0800879664810ca2bb46ebad2673fc504f7466` |
| [SubagentFactorySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentFactorySpec.scala) | `532a6c2c77cb991ed98024c88877fcd047fbafea` |
| [SubagentToolBoundary.scala](SubagentToolBoundary.scala), unchanged accepted boundary | `f2e7df236e266050c733d1974bed02f42119efea` |
| [SubagentToolBoundarySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentToolBoundarySpec.scala), unchanged accepted boundary tests | `20d9c69ced9d6c68c6307ba0961a0b5703aeef1f` |

Declaration parsing, automatic nested plan propagation, persistent child runtime/session construction, reuse/send, correlated child-event forwarding, external parent permission gates and durable historical policy accumulation remain outside this accepted scope. The resource primitive supplies ownership but does not construct reusable child sessions.

- [x] Verify scoped admission, cancellation leases, full-Exit observation, same-execution promotion and parallel shutdown with sequential worker cleanup.
- [x] Verify runtime-owned depth <=3, suspended identity/callback effects and actual nested ReAct reconciliation.
- [x] Verify and independently approve the configured factory and final static tool boundary baseline in `f92772b`.
- [x] Verify and independently approve bounded registry admission, inspection, retention and shutdown at the recorded identities.
- [x] Verify and independently approve live parent-denial inheritance without approval-authority copying at the recorded identities.
- [x] Verify and independently approve strict model-facing spawn, inspection and cancellation through the retained registry/factory.
- [x] Verify the host-installed inherited plan gate through configured-child execution without copying review authority.
- [x] Verify and independently approve shared resource ownership, failure/cancellation cleanup and competing closure.
- [ ] Implement persistent child reuse and session construction on the resource ownership contract.
- [ ] Implement declared/general-purpose integration, automatic nested/reused-child plan propagation and send tools while retaining narrowed factory authority.
- [ ] Implement correlated event forwarding and optional remote/durable task routing.

Next task: construct reusable child sessions and integrate send with current permission and plan boundaries. No exhaustive upstream parity is claimed.

# Scoped child execution and configured factories

[TaskSupervisor](TaskSupervisor.scala), [SupervisedTask](SupervisedTask.scala) and [SubagentSpawner](SubagentSpawner.scala) provide host-owned execution and runtime lineage. [SubagentFactory](SubagentFactory.scala) assembles fresh configured ReAct children with a final [static tool boundary](SubagentToolBoundary.scala) and optional live parent-denial inheritance. [SubagentTaskRegistry](SubagentTaskRegistry.scala) adds bounded task admission and inspection. These APIs are accepted within the exact reviewed scope below.

## Ownership, cancellation and promotion

`TaskSupervisor.open[A]` creates an execution owner in the host Scope. `attach` accepts a suspended scoped task and a separate parent cancellation lease. Admission is serialized against host closure; closed admission evaluates no task callback. Launch registration is masked, but task execution remains interruptible. Task effects and completion waits never hold the admission permit.

The supervisor owns each fiber from launch. Closing the attaching parent Scope cancels attached work and joins all task cleanup, including cleanup already started by explicit cancellation. `await` observes the complete Exit without cancelling. `poll` observes completed work without waiting and reports completion only after owned cleanup finishes. `cancel` also stops promoted work and waits for cleanup. Parent/host finalizers join tasks; task failures remain in the handle Exit. Live handles, admissions and lineage capabilities have no JSON codecs.

`awaitOrPromote` races completion against a fresh 30-second budget on each call, without restarting work. Only the winning timer's masked transition promotes the attached lease. Released work cannot be promoted. Promoted work survives parent closure; explicit cancellation and host closure still stop it. At simultaneous completion/deadline, the race winner decides. Cancelling a waiting observer does not by itself cancel the task or reverse committed promotion.

Host shutdown closes worker scopes in parallel, preventing one blocked finalizer from postponing interruption of other workers. Each worker closes its own sequential scope on completion, avoiding the ZIO 2.1.26 `forkIn` self-join deadlock. Task resources keep ordinary scoped cleanup and complete failure causes. Explicitly masked work or blocked finalizers can delay cancellation indefinitely. Tasks/finalizers must not wait for an owner or parent whose closure is joining them, or for their own handle. The host must retain its Scope for all desired background work; no daemon lifetime, remote adoption or restart durability exists.

## Runtime-owned lineage

`SubagentSpawner.root[A]` suspends creation of a UUID-derived root at depth zero. Trusted hosts control root and retained capability distribution. Private construction and no codec prevent descriptive fields from recreating authority. A retained spawner can be reused under another caller Scope while its supervisor is open.

`spawn` supplies the callback a new descendant capability under the supervisor worker Scope. It preserves structural ancestry and the supervisor, admitting depths one through three. Depth three rejects another spawn with `AgentError.SpawnDepthExceeded(3)` before identity allocation or callback invocation. Constructing a spawn effect and closed admission invoke no callback. At an eligible depth, identity allocation precedes admission and may consume an unused UUID if admission is closed.

`source` is slash-separated UUID lineage for diagnostics, not a path, registry credential or automatically assigned event tag. Nested attached cancellation waits for descendant cleanup and ReAct reconciliation. Promoted descendants remain supervisor-owned. The tool boundary normalizes depth failure to an Error observation; no model-facing spawn tool is installed.

Migration: exhaustive `AgentError` matches must handle the existing `SpawnDepthExceeded(maximumDepth)` leaf. Its codec conveys diagnostics only.

## Configured child construction

`SubagentFactory.make` validates AgentConfig and combines host-supplied parent and child boundaries without acquiring resources. `narrowed` returns an immutable factory whose selection is intersected and denials accumulated. Exact tool names are used. `AllAvailable` means all tools present at construction, including automatic registrations; `Only(Set.empty)` permits none. Unknown selected names do not create tools.

`build` suspends the registration callback, validates inherited and registered map keys against declared names, merges all registrations with collision rejection, and only then applies the static filter before client acquisition and `ReActAgent.make`. Invalid keys are checked in lexical key order, inherited before registered. Registration collisions are checked in lexical key order even for excluded names. The inherited Toolkit is immutable. Tool existential types are preserved without casts.

Each build creates fresh runtime/history state with the supplied AgentConfig, LlmConfig and ToolAuthorizer. The authorizer still controls actual execution of statically available tools. Static filtering is separate from the explicit live denial wrapper described below. Host callbacks are trusted adapters and must suspend external work, translate boundary failures and acquire resources with scoped combinators.

The caller must retain `build`'s Scope for the runtime lifetime; acquired registration/client resources are released when that Scope closes, including after construction failure. `spawn` delegates to `SubagentSpawner` so construction and execution share a supervised worker Scope, including cancellation and cleanup. Depth rejection and closed admission precede factory callbacks. Ordinary `spawn` does not itself wait or promote; the returned handle supplies those operations.

A raw spawner does not automatically carry the factory's static boundary or inherited-denial wrapper. Trusted host callbacks can construct another factory or task. Future model-facing nested spawning must retain the configured factory capability. Declaration parsing, plan propagation, persistent child reuse and model-facing spawn/send integration remain pending. No production automatic registrations are introduced: the callback supplies host-selected registrations.

## Live parent-denial inheritance

`inheritingParentDenials(readParentPolicy: UIO[PermissionEngine])` returns an immutable factory derivative. The supplied policy effect runs only during authorization. It reads parent policy before delegating to the child's existing authorizer, then reads again against the exact `Approved(input)` returned by that authorizer. A matching parent Deny produces a Denied observation without execution. All other parent decisions abstain; the child's Allow, Ask, Deny, confirmation requests and confirmed rules remain its own. Call identity is unchanged, and the parent's authorizer or approval request service is never invoked or copied.

Hosts can supply the existing interactive authorizer's effectful policy read. This inherits rules from that engine, not external parent tool/interceptor gates, permission modes or working directories. `narrowed` preserves the wrapper; multiple inherited wrappers retain their separate denial checks. Provider defects fail closed through the existing ReAct authorization boundary, while interruption remains cancellation and joins scoped cleanup.

Positive child confirmation is checked against both modified arguments and a fresh parent policy read. The final read is the decision point; later updates do not atomically revoke already returned approval. Live replacement does not accumulate historical parent rules, and confirmed child rules may remain installed even when the final parent check denies execution. Durable policy history, execution transactions and automatic inheritance through future spawn/send tools are host integration work.

## Bounded task registry

`SubagentTaskRegistry.open(parent, factory, maxTracked = 256)` acquires a registry in a host Scope and requires positive capacity. Each `submit` creates a fresh configured child through the retained factory and parent spawner. Accepted submissions return a registry-local UUID task ID; other outcomes are Capacity, Closed or IdentityCollision. Depth rejection remains a typed spawn error. Rejected admission acquires no child factory resources and retains no record. IDs identify execution records within one registry; they are not durable sessions or standalone authority.

Capacity counts every retained record, including completed results. `forgetCompleted` removes only terminal work, returning Removed, Running or Missing. This frees record capacity without restarting or replacing a child. The bound is a record count, not a bound on the bytes retained by full AgentResult histories and causes. Hosts must retire completed records according to their retention needs.

`inspect` and `list` observe Running or Completed with a complete Exit, preserving typed failures, defects, interruption and cleanup causes. A list captures membership once and polls each entry independently; it is not an atomic completion snapshot. Lookup-based await, promotion and cancellation return None for missing IDs and otherwise delegate to the existing task handle. They do not hold the admission permit while waiting or joining cleanup. Completed records remain inspectable after registry closure until explicitly forgotten or the registry becomes unreachable.

Each submission owns a private caller cancellation lease. Admission records the task before attaching that lease's finalizer to the caller Scope, outside the admission permit. An already closed caller can therefore join its child cleanup without hiding the retained record or blocking unrelated capacity checks. Closing a normal caller cancels and joins attached work; promoted work survives caller closure. Closing the registry first closes admission, then cancels registered tasks in parallel and joins cleanup, including promoted tasks, even if the underlying supervisor outlives the registry. No persistent child reuse, remote ownership or durable restart recovery is provided.

## Verification and acceptance

The [test index](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/README.md) maps current contract evidence and bounded upstream adaptations. The supervisor/lineage foundations retain their recorded approvals against `26f524c` and `279b7a3`; the unchanged static boundary retains its acceptance in `f92772b`.

On 2026-09-30, `path_review` approved the final registry, polling and paired-test identities below; `current_docs` approved the final factory and paired-test identities for inherited denial. Both independent FP/ZIO and invariant reviews report no findings in their named scopes. Acceptance covers registry admission, count-bounded retention, private caller leases, complete-Exit inspection and shutdown, plus live parent-denial checks around the existing child authorizer. Full `sbt scalafmtAll scalafmtSbt verify` passed 561 core and 618 harness tests (1,179 total), with zero compiler warnings or failed tests; execution evidence is `registry-policy-verify.log`.

| Reviewed source | Git blob |
| --- | --- |
| [SubagentTaskRegistry.scala](SubagentTaskRegistry.scala) | `bf32c16f1eba0ac244d7ffa42781a40af2d593d7` |
| [SupervisedTask.scala](SupervisedTask.scala) | `f3ddf1c653699ecd98cb021d4065608d561d591f` |
| [SubagentTaskRegistrySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentTaskRegistrySpec.scala) | `9fcf06b9c2fb17571415b6fb91a1aacff8bfc899` |
| [SubagentFactory.scala](SubagentFactory.scala) | `2d0800879664810ca2bb46ebad2673fc504f7466` |
| [SubagentFactorySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentFactorySpec.scala) | `532a6c2c77cb991ed98024c88877fcd047fbafea` |
| [SubagentToolBoundary.scala](SubagentToolBoundary.scala), unchanged accepted boundary | `f2e7df236e266050c733d1974bed02f42119efea` |
| [SubagentToolBoundarySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentToolBoundarySpec.scala), unchanged accepted boundary tests | `20d9c69ced9d6c68c6307ba0961a0b5703aeef1f` |

Declaration parsing, inherited plan restrictions, persistent reuse, model-facing spawn/send, external parent gates and durable historical policy accumulation remain outside this accepted scope.

- [x] Verify scoped admission, cancellation leases, full-Exit observation, same-execution promotion and parallel shutdown with sequential worker cleanup.
- [x] Verify runtime-owned depth <=3, suspended identity/callback effects and actual nested ReAct reconciliation.
- [x] Verify and independently approve the configured factory and final static tool boundary baseline in `f92772b`.
- [x] Verify and independently approve bounded registry admission, inspection, retention and shutdown at the recorded identities.
- [x] Verify and independently approve live parent-denial inheritance without approval-authority copying at the recorded identities.
- [ ] Implement persistent child reuse and session ownership.
- [ ] Implement declared/general-purpose host integration and model-facing spawn/send tools while retaining narrowed factory authority.
- [ ] Implement inherited plan restrictions for new/reused children without copying approval authority.
- [ ] Implement correlated event forwarding and optional remote/durable task routing.

Next task: implement opt-in model-facing spawn, inspection and cancellation through the bounded registry while retaining configured factory authority. Inherited plan restrictions and persistent reuse remain separate unfinished work. No exhaustive upstream parity is claimed.

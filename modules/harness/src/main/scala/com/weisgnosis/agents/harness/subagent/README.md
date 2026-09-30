# Scoped child execution and configured factories

[TaskSupervisor](TaskSupervisor.scala), [SupervisedTask](SupervisedTask.scala) and [SubagentSpawner](SubagentSpawner.scala) provide host-owned execution and runtime lineage. [SubagentFactory](SubagentFactory.scala) assembles fresh configured ReAct children with a final [static tool boundary](SubagentToolBoundary.scala). The configured factory and static boundary are accepted within the reviewed scope below; registry integration remains unaccepted.

## Ownership, cancellation and promotion

`TaskSupervisor.open[A]` creates an execution owner in the host Scope. `attach` accepts a suspended scoped task and a separate parent cancellation lease. Admission is serialized against host closure; closed admission evaluates no task callback. Launch registration is masked, but task execution remains interruptible. Task effects and completion waits never hold the admission permit.

The supervisor owns each fiber from launch. Closing the attaching parent Scope cancels attached work and joins all task cleanup, including cleanup already started by explicit cancellation. `await` observes the complete Exit without cancelling. `cancel` also stops promoted work and waits for cleanup. Parent/host finalizers join tasks; task failures remain in the handle Exit. Live handles, admissions and lineage capabilities have no JSON codecs.

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

Each build creates fresh runtime/history state with the supplied AgentConfig, LlmConfig and ToolAuthorizer. The authorizer still controls actual execution of statically available tools. Static filtering is not dynamic permission inheritance: arguments, approval decisions and plan restrictions are not copied or inferred. Host callbacks are trusted adapters and must suspend external work, translate boundary failures and acquire resources with scoped combinators.

The caller must retain `build`'s Scope for the runtime lifetime; acquired registration/client resources are released when that Scope closes, including after construction failure. `spawn` delegates to `SubagentSpawner` so construction and execution share a supervised worker Scope, including cancellation and cleanup. Depth rejection and closed admission precede factory callbacks. Ordinary `spawn` does not itself wait or promote; the returned handle supplies those operations.

A raw spawner does not automatically carry the factory's static boundary. Trusted host callbacks can construct another factory or task. Future model-facing nested spawning must retain the narrowed factory capability. Declarative parsing, dynamic permission inheritance, plan propagation, registry/reuse and model-facing spawn/send integration remain pending. No production automatic registrations are introduced: the callback supplies host-selected registrations.

## Verification and acceptance

The [test index](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/README.md) maps the current suites and bounded upstream adaptations. The supervisor and lineage foundations retain their `review_fp`, `review_harness` and `review_docs` approvals against `26f524c` and `279b7a3` respectively. Those approvals do not extend to registry changes or the new `SupervisedTask.poll` API.

`path_review` approved the following exact factory revision on 2026-09-30. Accepted scope covers configured fresh child construction, final filtering after inherited and construction-time registration, monotone exact-name selection/denial, deterministic validation and collisions, supplied authorization, scoped resource cleanup, and spawning through runtime-owned descendant authority. No findings remain within that scope. Full `sbt scalafmtAll scalafmtSbt verify` passed 561 core and 609 harness tests (1,170 total), with zero compiler warnings or failed tests, before registry integration.

| Reviewed source | Git blob |
| --- | --- |
| [SubagentFactory.scala](SubagentFactory.scala) | `a87164bde938363ea7d5b5585081cce66fc64e9a` |
| [SubagentToolBoundary.scala](SubagentToolBoundary.scala) | `f2e7df236e266050c733d1974bed02f42119efea` |
| [SubagentFactorySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentFactorySpec.scala) | `f6a329caf4ffc7653274a5fcc1ece35eb07a7fad` |
| [SubagentToolBoundarySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentToolBoundarySpec.scala) | `20d9c69ced9d6c68c6307ba0961a0b5703aeef1f` |

This acceptance excludes the in-progress task registry and `SupervisedTask.poll`, declaration parsing, dynamic permission inheritance, plan propagation, persistent reuse, model-facing spawn/send, and automatic event routing. These require their own final review and verification.

- [x] Verify scoped admission, cancellation leases, full-Exit observation, same-execution promotion and parallel shutdown with sequential worker cleanup.
- [x] Verify runtime-owned depth <=3, suspended identity/callback effects and actual nested ReAct reconciliation.
- [x] Verify and independently approve the configured factory and final static tool boundary scope at the four identities above, including its paired scenarios and pinned upstream adaptations.
- [ ] Add task registry/inspection, bounded admission and persistent child reuse.
- [ ] Implement declared/general-purpose host integration and model-facing spawn/send tools while retaining narrowed factory authority.
- [ ] Implement dynamic permission inheritance and plan restrictions for new/reused children without copying approval authority.
- [ ] Implement correlated event forwarding and optional remote/durable task routing.

Next task: complete bounded task registry admission and inspection, including its `SupervisedTask.poll` dependency, then obtain independent review and verification before accepting that scope. No exhaustive upstream parity is claimed.

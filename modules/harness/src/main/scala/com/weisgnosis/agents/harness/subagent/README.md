# Scoped task supervision

[TaskSupervisor.open[A]](TaskSupervisor.scala) creates a local execution owner in the host Scope. [TaskAdmission](TaskAdmission.scala) and [SupervisedTask](SupervisedTask.scala) carry live authority and complete Exit values; they intentionally have no JSON codecs. This is the execution-ownership foundation for subagents, not a child factory, registry or model-facing spawn tool.

## Ownership and lifetime

attach accepts a suspended ZIO[Scope, AgentError, A] and requires a separate parent Scope. Admission returns Accepted(handle) or Closed. Construction performs no task effects; closed admission evaluates no task. A short semaphore serializes launch against the host finalizer's admission closure. Acquire/release masks registration; task execution is explicitly interruptible even under a masked caller. User task effects and completion waits never hold the admission permit.

The supervisor owns each fiber from creation. The parent receives a cancellation lease represented by Attached, Promoted or Released runtime state. Parent closure atomically releases and interrupts Attached work, waiting for its cleanup. If another caller already released the lease through cancel, parent cleanup still joins completion. Promoted work survives parent scope closure; host scope closure always cancels and joins owned work. Explicit cancel also cancels promoted work and returns its complete Exit.

The host owner closes worker scopes in parallel, so one blocked task finalizer cannot postpone interruption of other tasks. Each worker uses a sequential child scope and explicitly closes it on completion. This matters with ZIO 2.1.26 forkIn: its same-fiber interruption guard must run on the completing worker, not on a parallel finalizer fiber that would wait for that worker. Completed worker scopes unregister from the owner. Task-local resources retain ordinary scoped cleanup and full failure causes.

The host must keep the supervisor Scope alive for all desired background work. No global/daemon lifetime, remote adoption or restart durability is provided. Tasks and finalizers must not await closure of the owner currently joining them, their own handle, or a parent whose finalizer is joining that task. Explicitly masked task effects or blocked finalizers can delay cancellation indefinitely; parallel shutdown does not bypass cleanup.

## Waiting and promotion

await returns the complete Exit[AgentError, A], including typed failures, defects, interruption and cleanup defects. It observes without cancelling work. cancel masks lease release through interruption and cleanup joining. Automatic parent/host cleanup joins fibers; callers inspect the handle Exit for task failures rather than receiving those failures through scope finalization.

awaitOrPromote races completion against a fresh 30-second budget beginning with each invocation. Every invocation has its own budget, including a later call on an already promoted but unfinished task. Completed work returns Completed(exit) promptly. No waiting call restarts execution. The timer branch changes no ownership; only after it wins does a masked transition commit promotion. Released work cannot be promoted and instead waits for its terminal Exit.

If completion and the deadline coincide, the race winner decides; Promoted can refer to work that finishes immediately afterward. Cancelling an observer before promotion commits does not itself cancel the child; closing its attaching parent Scope does. Cancellation after committed promotion does not restore the parent's lease. Explicit cancel or host closure still stops promoted work. These are runtime decisions, not durable acknowledgements.

## Verification and remaining integration

The [test index](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/README.md) covers sixteen supervisor cases, two direct ReAct cases and the later lineage cases below. The original lifecycle review applies to its recorded five-file scope. The upstream inventory records related behavioral evidence without claiming whole spawn/promotion tool parity.

- [x] Implement masked admission, explicit child interruption policy and scoped parent cancellation leases.
- [x] Implement completion observation and same-execution promotion with a 30-second wait budget.
- [x] Verify parallel shutdown with sequential worker cleanup, including reproduced forkIn closure deadlock correction.
- [x] Verify actual ReAct history reconciliation and continuation after parent closure.
- [x] Add runtime-owned lineage and maximum descendant depth of three.
- [ ] Add task identities, registry inspection and bounded admission policy for model-facing spawning.
- [ ] Implement declared/general-purpose child factories and capability narrowing.
- [ ] Enforce inherited plan restrictions for new and reused children without copying approval authority.
- [ ] Implement persistent child reuse, spawn/send tools and correlated event forwarding.
- [ ] Implement separate remote/durable tasks, push delivery and host routing if required.

## Current source identities

Final formatted five-file scope against 26f524c, including the parallel-owner/sequential-worker correction.

| Source | Git blob |
| --- | --- |
| [TaskSupervisor.scala](TaskSupervisor.scala) | 56cc017c3507f145a726ce3c930947087d52b2a5 |
| [SupervisedTask.scala](SupervisedTask.scala) | 7c69264e6c894248ae8f2e8304a2a31f54bba0e0 |
| [TaskAdmission.scala](TaskAdmission.scala) | b7c0abcfc3fcfd24ef22d329b7684721860108bf |
| [TaskSupervisorSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/TaskSupervisorSpec.scala) | 62946d851394f01cd07141557460b10f4b248a10 |
| [TaskSupervisorAgentSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/TaskSupervisorAgentSpec.scala) | 98a892bcdbc6f2b9a9f7d021fdf14a571f3c4fa1 |

- [x] `review_fp` and `review_harness` renewed approval of the final five-file supervision scope against `26f524c` on 2026-09-29 after reproducing and correcting the parallel forkIn self-join. Full `sbt scalafmtAll scalafmtSbt verify` passed 556 core / 593 harness tests, including eighteen new cases, with zero compiler warnings. No findings remain within local scoped admission, cancellation leases, same-execution promotion, full-Exit observation, parallel shutdown and actual ReAct continuation/reconciliation. Registry, depth enforcement, spawn/send tools, persistence, event forwarding and plan propagation are excluded.

- [x] `review_docs` approved corrected supervision documentation against `26f524c`: five identities, eighteen new cases and 556 core / 593 harness tests. Parent/host scope ownership, per-invocation deadlines, full-Exit observation and the forkIn cleanup correction are explicit. Depth, registry, spawn/send, event forwarding and plan propagation remain pending; no whole upstream method is marked complete. All 63 Markdown files passed local-link/script checks and git diff --check passed. No findings remain within this scope.

## Runtime-owned descendant lineage

[SubagentSpawner.root[A]](SubagentSpawner.scala) takes a TaskSupervisor[A] and lazily mints a UUID-derived root capability at depth zero. Only trusted hosts should create or distribute roots. The private constructor and absence of a JSON codec prevent descriptive source/depth fields from reconstructing a spawner. Retaining or creating a separate root is host authority; this API is not a sandbox for arbitrary Scala code.

spawn receives a callback that will run under the existing supervisor's worker Scope. The callback receives only the newly minted descendant spawner, with immutable structural lineage, the same supervisor and depth increased by one. Depths one through three are admitted. A depth-three capability fails with AgentError.SpawnDepthExceeded(3) before identity allocation or callback invocation. The effect is lazy; neither building the spawn effect nor closed supervisor admission invokes the callback. At an eligible depth, identity allocation precedes supervisor admission, so a closed admission can consume an unused UUID.

source joins the runtime lineage into descriptive slash-separated UUID text. It is not a filesystem path, registry lookup credential or automatic AgentEvent source tag. There is no identity registry/collision protocol in this primitive. Sibling requests mint independent identities without changing their parent. No public API accepts a depth, source string or model JSON as authority.

The callback's Scope supplies the default cancellation lease for further nested attached tasks. Cascading cancellation waits for descendant cleanup and reconciliation. A descendant promoted through its handle survives the earlier parent scope and still belongs to the supervisor owner. Spawner capabilities are not automatically revoked when the callback finishes; a trusted holder can reuse one under another caller Scope while the supervisor remains open, preserving its original depth/lineage. Hosts must control capability distribution.

At a tool boundary, Toolkit normalizes SpawnDepthExceeded as an Error observation; the containing ReAct run can still finish. Actual three-level tests establish this and cancellation reconciliation at each running level. Their spawn_child tool is a test fixture, not a library-registered model tool. No factory policy, registry, declaration, parent permission, plan propagation or event forwarding is implied.

Migration: the sealed AgentError hierarchy now includes SpawnDepthExceeded(maximumDepth), with an individual derived codec and diagnostic message. Exhaustive matches must handle the new leaf. Decoding this diagnostic grants no spawn authority.

## Current lineage identities

Final formatted five-file scope against 279b7a3. Earlier AgentError/AgentErrorSpec identities remain in that commit and are retired from the plan-storage table.

| Source | Git blob |
| --- | --- |
| [SubagentSpawner.scala](SubagentSpawner.scala) | 75cd20da3998b0d66b8afb050a8c2e5012ce44b4 |
| [SubagentSpawnerSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentSpawnerSpec.scala) | b2866e1bab047dc31f463a1f809d45fe4676b7ba |
| [SubagentSpawnerAgentSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentSpawnerAgentSpec.scala) | 56b59a7fa9a7af22f4d63b8578533a068312cd21 |
| [AgentError.scala](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/error/AgentError.scala) | 4b4b37d57e469975eaf208e8466fa1640fa8e11f |
| [AgentErrorSpec.scala](../../../../../../../../../core/src/test/scala/com/weisgnosis/agents/core/error/AgentErrorSpec.scala) | b564c375448d4a6789dc2e45f9261c02a8075650 |

- [x] `review_fp` and `review_harness` approved the final five-file lineage scope against `279b7a3` on 2026-09-29, including the corrected ancestry assertion. Full `sbt scalafmtAll scalafmtSbt verify` passed 557 core / 601 harness tests, including nine new cases, with zero compiler warnings. No findings remain within runtime-owned depth <=3, suspended identity/callback effects, sibling lineage, nested cancellation and actual ReAct depth-error normalization. Registry, child factory configuration, automatic source tagging, inherited permission/plan policy and model-facing spawn/send remain excluded.

- [x] `review_docs` approved corrected lineage documentation against `279b7a3`: five identities, nine new cases and 557 core / 601 harness tests. Host root authority, retained capabilities, descriptive source, depth rejection and the sealed-error migration are explicit. Factories, registry, inherited policy and model-facing spawn/send remain pending; no additional whole upstream method is closed. All 63 Markdown files passed local-link/script checks and git diff --check passed. No findings remain within this scope.

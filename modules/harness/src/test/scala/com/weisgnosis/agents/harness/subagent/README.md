# Scoped task supervision verification

[TaskSupervisorSpec](TaskSupervisorSpec.scala) supplies sixteen cases covering completed/repeated observation, typed failure plus cleanup defect, self-interruption, parent cleanup joining, 30-second same-execution promotion, host closure and closed admission, early/late parent cancellation, explicit cancellation, masked acquisition, per-invocation wait budgets, concurrent parent/explicit cancellation, parallel shutdown fan-out, retained interruption/cleanup causes, cancellation/deadline races and admission/shutdown races. Race scenarios repeat twenty times using ZIO Test clocks and explicit promises; they do not establish exhaustive schedule coverage.

[TaskSupervisorAgentSpec](TaskSupervisorAgentSpec.scala) supplies two actual ReAct cases. Attached cancellation verifies assistant-before-tool persistence, exactly one Error result, recovery history and tool cleanup. Promotion verifies one tool execution continues after parent closure and completes the following model turn without interrupted recovery. No model-facing spawn tool or event forwarding is installed.

The [current contract and source identities](../../../../../../../main/scala/com/weisgnosis/agents/harness/subagent/README.md) define scope ownership, per-wait deadlines and cause observation. Parallel owner closure with sequential worker scopes prevents the reproduced forkIn self-join hang while cancelling all children before waiting on blocked cleanup.

- [x] Verify eighteen supervision and runtime integration cases.
- [x] Verify runtime-owned depth enforcement before callback/model invocation.
- [ ] Verify child identity/registry, capability narrowing and actual spawn/send entry points.
- [ ] Verify correlated child event forwarding and exact source attribution.
- [ ] Verify declared/general-purpose and reused-child plan propagation.
- [ ] Complete applicable upstream task/tool mappings after those APIs exist.

- [x] `review_fp` and `review_harness` renewed approval of the final five-file supervision scope against `26f524c` on 2026-09-29 after reproducing and correcting the parallel forkIn self-join. Full `sbt scalafmtAll scalafmtSbt verify` passed 556 core / 593 harness tests, including eighteen new cases, with zero compiler warnings. No findings remain within local scoped admission, cancellation leases, same-execution promotion, full-Exit observation, parallel shutdown and actual ReAct continuation/reconciliation. Registry, depth enforcement, spawn/send tools, persistence, event forwarding and plan propagation are excluded.

## Lineage verification

[SubagentSpawnerSpec](SubagentSpawnerSpec.scala) adds six cases for private construction/no codec, lazy identity and callback effects, exact depth/ancestry with UUID allocation unchanged after rejection, concurrent siblings, closed admission and promoted descendants. [SubagentSpawnerAgentSpec](SubagentSpawnerAgentSpec.scala) adds two actual nested ReAct cases: fourth-level rejection becomes an Error observation without constructing that agent; root cancellation reconciles and cleans all three active levels. AgentErrorSpec adds the dedicated error codec/diagnostic case. Nine new cases cover this increment.

The test spawn_child tool is a local fixture; no production spawn/send tool, registry, automatic event source or inherited policy is installed. Current identities and capability limits are in the [lineage contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/subagent/README.md#runtime-owned-descendant-lineage).

- [x] `review_fp` and `review_harness` approved the final five-file lineage scope against `279b7a3` on 2026-09-29, including the corrected ancestry assertion. Full `sbt scalafmtAll scalafmtSbt verify` passed 557 core / 601 harness tests, including nine new cases, with zero compiler warnings. No findings remain within runtime-owned depth <=3, suspended identity/callback effects, sibling lineage, nested cancellation and actual ReAct depth-error normalization. Registry, child factory configuration, automatic source tagging, inherited permission/plan policy and model-facing spawn/send remain excluded.

# Scoped child execution verification

The [current contracts and acceptance](../../../../../../../main/scala/com/weisgnosis/agents/harness/subagent/README.md) define accepted supervision, shared resource ownership, reusable host-held sessions, lineage, configured construction, bounded registry, live parent denial and model-facing task controls. Automatic nested/reused-child installation, session routing and model-facing send remain pending.

## Current suites

| Suite | Contract evidence |
| --- | --- |
| [TaskSupervisorSpec](TaskSupervisorSpec.scala) | Sixteen cases cover masked admission, parent/host cancellation and cleanup joining, explicit cancellation, full typed/defect/interruption causes, completed and repeated observation, 30-second same-execution promotion, per-wait budgets, closed admission, parallel shutdown and cancellation/admission races. Race cases repeat twenty times; this is not exhaustive schedule coverage. |
| [TaskSupervisorAgentSpec](TaskSupervisorAgentSpec.scala) | Two actual ReAct cases verify assistant-before-tool persistence, one interrupted Error result and recovery, and promoted continuation after parent closure without repeated execution. |
| [TaskResourceSpec](TaskResourceSpec.scala) | Five grouped scenarios cover resources surviving initialization and completed tasks, typed/defect/interrupted initialization cleanup, caller/root cancellation, joined queued/promoted work, and seven competing explicit/caller/root closure pairs with first-Exit preservation and cleanup-defect replay. |
| [SubagentSpawnerSpec](SubagentSpawnerSpec.scala) | Six cases cover private runtime capability construction/no codec, lazy identity and callback effects, exact ancestry/depth rejection without identity consumption, concurrent siblings, closed admission and promoted descendants. |
| [SubagentSpawnerAgentSpec](SubagentSpawnerAgentSpec.scala) | Two actual nested ReAct cases verify fourth-level rejection as an Error observation and cancellation/reconciliation at all three running depths. The spawn tool is a test fixture. |
| [SubagentToolBoundarySpec](SubagentToolBoundarySpec.scala) | Selection/denial intersections across three generations and JSON round trips, plus deterministic malformed-key and excluded-collision rejection. |
| [SubagentFactorySpec](SubagentFactorySpec.scala) | Model-visible final inherited/automatic filtering with attempted forbidden dispatch, derived factory narrowing, fresh configured history with effective injected authorization, lazy/invalid/closed construction, actual descendant spawn/depth rejection, success/failure resource cleanup, and cleanup-joining cancellation during registration, client acquisition and execution. Three inherited-denial scenarios exercise child-policy independence and argument near-misses, live policy/modified-confirmation rechecks, and fail-closed defects versus interruption with cleanup on either parent-policy read. |
| [SubagentTaskRegistrySpec](SubagentTaskRegistrySpec.scala) | Six scenarios cover concurrent bounded admission and fresh filtered children, retained terminal capacity and explicit forgetting, collision/instance isolation, complete failure/defect/interruption snapshots, caller cancellation and promoted retention, parallel shutdown with closed-caller admission, and rejected depth/capacity/closed-supervisor construction. |
| [SubagentSessionSpec](SubagentSessionSpec.scala) | Five scenarios cover one filtered depth-three runtime with retained history/resources and distinct task IDs, queued cancellation without history changes, live parent-denial/Plan changes between turns, joined promoted turn and actual descendant cleanup, and invalid/closed/failed acquisition. Serial execution is verified without claiming FIFO scheduling. |
| [SubagentToolsSpec](SubagentToolsSpec.scala) | Five scenarios cover a parent ReAct calling the fixed filtered child, same-execution promotion and cleanup-joining cancellation without new model requests, strict inputs/missing IDs, bounded result and failure projections, and caller cancellation with scoped child cleanup. |
| [PlanInheritedPermissionSpec](../plan/PlanInheritedPermissionSpec.scala) | Three configured-child scenarios verify parent Plan restrictions with child-owned controls, child Plan retention until its own exact review, and live parent/modified-input rechecks during confirmation. |

AgentErrorSpec verifies the depth diagnostic and codec. Factory and boundary scenarios use table-driven cases for distinct selection and resource phases. No tests execute external commands or use a real model.

The closed-caller regression forces task cleanup to block while checking that the admitted record remains visible and a contender promptly receives Capacity. Before the private caller-lease correction, the unchanged defective registry produced zero passing and one failing focused test: visible size was 0 instead of 1, and the contender poll was None instead of completed Capacity (`registry-lease-red.log`). The passing regression verifies cleanup joining without holding the admission permit; it releases all barriers before assertions even on the defective implementation.

## Pinned upstream adaptation

The source comparison uses `agentscope-java` revision `f98a685fdc4589f81e626e1686ac2c63d14a2304`. Static-boundary methods are in `agentscope-harness/src/test/java/io/agentscope/harness/agent/ManagedSubagentBoundaryTest.java`; permission methods are in `agentscope-harness/src/test/java/io/agentscope/harness/agent/tool/AgentSpawnToolPermissionTest.java`.

| Upstream method | Scala evidence and intentional boundary |
| --- | --- |
| `declarationFiltersAutoRegisteredToolsAsWellAsInheritedTools` | Factory final-filter scenario checks both inherited and construction-registered tools through the actual child's model input and attempts to dispatch `write_file`, verifying zero executions when excluded and one execution when allowed. Registration callbacks replace Java filesystem/builder wiring; no declaration parser or filesystem auto-installation is claimed. |
| `childCannotReenableParentDeniedTool` | The same scenario verifies a child selecting a parent-denied inherited or construction-registered tool sees an empty toolkit; parent allowlist intersection and derived factories extend monotonicity coverage. Attempted excluded `write_file` dispatch produces an Error observation without execution. This is exact-name static capability narrowing, not dynamic permission inheritance. |
| `harnessChildReceivesParentDenyInActualExecutionSlot` | Actual configured-child runs install the inherited-denial wrapper and attempt an argument-matched denied call, yielding Denied without execution. Permitted near-misses still execute. Java harness/session wiring and permission-mode parity are excluded. |
| `directReactChildKeepsItsOwnPermissionContextAndAddsOnlyParentDeny` | Actual child runs preserve their own Allow, Ask and Deny behavior and confirmation policy while parent non-Deny decisions abstain. Live policy and confirmed-input rechecks extend the bounded adaptation; parent modes and working-directory inheritance are not implemented. |

These bounded adaptations are reviewed and verified at the recorded source identities. `declarationCanDisableParentDenyInheritance` remains pending declaration integration; a host's choice not to install the wrapper does not complete that method. `persistentReuseAndAgentSendAddCurrentParentDenyWithoutDuplicates` remains pending: the host-held session verifies current installed policy across reused turns, but supplies neither model-facing send nor historical denial accumulation. No complete upstream permissions-suite or model-facing spawn/send parity is claimed.

`AgentSpawnToolPromotionTest.innerTimeoutPromotesToAdoptedTaskRunSpec` has supporting tool-level evidence for a retained task ID, one continuing execution and later inspection/cancellation. The Scala adapter uses a fixed 30-second budget and the existing supervisor/registry; it does not implement Java external repository adoption or configurable timeout/force-sync modes. `AgentSpawnToolOrphanCancelTest.orphanAgentInterruptedOnParentCancel` has a scoped ownership adaptation: cancelling the actual spawn invocation joins child cleanup without detaching a continuing worker. Correlated child event forwarding remains outside these tests. The plan package's bounded parent/child gate evidence does not close the automatic declared/general-purpose or persistent spawn/send plan methods.

## Acceptance and remaining checks

On 2026-09-30, `path_review` and `current_docs` independently approved the final session, owned-child spawner extension, registry launcher and paired session tests, with no unresolved FP/ZIO or invariant findings. The [main acceptance record](../../../../../../../main/scala/com/weisgnosis/agents/harness/subagent/README.md#verification-and-acceptance) records exact current and retained identities; the [plan admission identities](../../../../../../../main/scala/com/weisgnosis/agents/harness/plan/README.md#current-admission-identities) retain separate adapter approval. Full `scalafmtAll scalafmtSbt verify` passed 561 core and 636 harness tests (1,197 total), with zero compiler warnings or failed tests (`subagent-session-verify.log`). Model-facing send and session lookup remain outside acceptance. The defensive suspended-session guard has no integration evidence because current ReAct execution does not produce Suspended.

Both resource defects were reproduced before correction. The initialization regression observed `Success(())` instead of the failed/interrupted initialization Exit (`resource-exit-red.log`: zero passed, one failed). The competing-close regression observed a completed rival before cleanup release and lost the cleanup defect (`resource-close-red.log`: zero passed, one failed). Barrier-based regressions now pass and release all held cleanup before assertions. These tests establish library-controlled closure contracts, not new guarantees for arbitrary concurrent calls to raw `Scope.close`.

- [x] Verify supervisor/runtime integration and lineage/error contracts.
- [x] Verify and independently approve configured-factory/static-boundary scenarios and the two bounded upstream adaptations at the recorded identities.
- [x] Verify and independently approve bounded registry admission, retention, inspection, caller leases and scoped shutdown.
- [x] Verify and independently approve live parent-denial checks before authorization and after confirmation without copying approval authority.
- [x] Verify actual model-facing spawn/inspect/cancel entry points, bounded observations and scoped caller cancellation.
- [x] Verify opt-in inherited Plan admission with child-owned controls and review authority.
- [x] Verify retained shared resources, initialization failure/cancellation, worker-before-resource teardown and competing-close outcome sharing.
- [x] Verify host-held reusable child sessions with per-execution IDs, serialized history, current policy and bounded descendant lifetime.
- [ ] Verify model-facing send and session lookup when implemented.
- [ ] Verify declared/general-purpose/reused-child plan propagation.
- [ ] Verify correlated child event forwarding and automatic source attribution.
- [ ] Complete remaining applicable upstream method mappings after those APIs exist.

Next task: verify model-facing send bound to a trusted session, then session routing and automatic child policy integration.

# Scoped child execution verification

The [current contracts and acceptance](../../../../../../../main/scala/com/weisgnosis/agents/harness/subagent/README.md) define the accepted supervisor, lineage, configured-factory, bounded-registry and live parent-denial scopes. Persistent child reuse, inherited plan restrictions and model-facing spawn/send remain pending.

## Current suites

| Suite | Contract evidence |
| --- | --- |
| [TaskSupervisorSpec](TaskSupervisorSpec.scala) | Sixteen cases cover masked admission, parent/host cancellation and cleanup joining, explicit cancellation, full typed/defect/interruption causes, completed and repeated observation, 30-second same-execution promotion, per-wait budgets, closed admission, parallel shutdown and cancellation/admission races. Race cases repeat twenty times; this is not exhaustive schedule coverage. |
| [TaskSupervisorAgentSpec](TaskSupervisorAgentSpec.scala) | Two actual ReAct cases verify assistant-before-tool persistence, one interrupted Error result and recovery, and promoted continuation after parent closure without repeated execution. |
| [SubagentSpawnerSpec](SubagentSpawnerSpec.scala) | Six cases cover private runtime capability construction/no codec, lazy identity and callback effects, exact ancestry/depth rejection without identity consumption, concurrent siblings, closed admission and promoted descendants. |
| [SubagentSpawnerAgentSpec](SubagentSpawnerAgentSpec.scala) | Two actual nested ReAct cases verify fourth-level rejection as an Error observation and cancellation/reconciliation at all three running depths. The spawn tool is a test fixture. |
| [SubagentToolBoundarySpec](SubagentToolBoundarySpec.scala) | Selection/denial intersections across three generations and JSON round trips, plus deterministic malformed-key and excluded-collision rejection. |
| [SubagentFactorySpec](SubagentFactorySpec.scala) | Model-visible final inherited/automatic filtering with attempted forbidden dispatch, derived factory narrowing, fresh configured history with effective injected authorization, lazy/invalid/closed construction, actual descendant spawn/depth rejection, success/failure resource cleanup, and cleanup-joining cancellation during registration, client acquisition and execution. Three inherited-denial scenarios exercise child-policy independence and argument near-misses, live policy/modified-confirmation rechecks, and fail-closed defects versus interruption with cleanup on either parent-policy read. |
| [SubagentTaskRegistrySpec](SubagentTaskRegistrySpec.scala) | Six scenarios cover concurrent bounded admission and fresh filtered children, retained terminal capacity and explicit forgetting, collision/instance isolation, complete failure/defect/interruption snapshots, caller cancellation and promoted retention, parallel shutdown with closed-caller admission, and rejected depth/capacity/closed-supervisor construction. |

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

These bounded adaptations are reviewed and verified at the recorded source identities. `declarationCanDisableParentDenyInheritance` remains pending declaration integration; a host's choice not to install the wrapper does not complete that method. `persistentReuseAndAgentSendAddCurrentParentDenyWithoutDuplicates` remains pending: registry submission creates fresh children, and live policy replacement does not accumulate historical denials. No complete upstream permissions-suite or model-facing spawn/send parity is claimed.

## Acceptance and remaining checks

On 2026-09-30, `path_review` approved the final registry/polling scope and `current_docs` approved the final inherited-denial factory scope with no findings. The [main acceptance record](../../../../../../../main/scala/com/weisgnosis/agents/harness/subagent/README.md#verification-and-acceptance) records their exact source/test identities and the retained static-boundary identities. Full `sbt scalafmtAll scalafmtSbt verify` passed 561 core and 618 harness tests (1,179 total), with zero compiler warnings or failed tests (`registry-policy-verify.log`). This evidence covers current execution, ownership, static capability and live-denial contracts, not the remaining declaration, persistent-reuse or model-tool integrations.

- [x] Verify supervisor/runtime integration and lineage/error contracts.
- [x] Verify and independently approve configured-factory/static-boundary scenarios and the two bounded upstream adaptations at the recorded identities.
- [x] Verify and independently approve bounded registry admission, retention, inspection, caller leases and scoped shutdown.
- [x] Verify and independently approve live parent-denial checks before authorization and after confirmation without copying approval authority.
- [ ] Verify persistent reuse and actual model-facing spawn/send entry points when implemented.
- [ ] Verify declared/general-purpose/reused-child plan propagation.
- [ ] Verify correlated child event forwarding and automatic source attribution.
- [ ] Complete remaining applicable upstream method mappings after those APIs exist.

Next task: verify model-facing spawn, inspection and cancellation through the registry. Preserve current static and live permission boundaries; inherited plan restrictions and persistent reuse require their own integration evidence.

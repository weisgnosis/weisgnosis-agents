# Scoped child execution verification

The [current contracts and acceptance](../../../../../../../main/scala/com/weisgnosis/agents/harness/subagent/README.md) define the accepted supervisor, lineage and configured-factory scopes. In-progress registry integration and `SupervisedTask.poll` remain outside that acceptance.

## Current suites

| Suite | Contract evidence |
| --- | --- |
| [TaskSupervisorSpec](TaskSupervisorSpec.scala) | Sixteen cases cover masked admission, parent/host cancellation and cleanup joining, explicit cancellation, full typed/defect/interruption causes, completed and repeated observation, 30-second same-execution promotion, per-wait budgets, closed admission, parallel shutdown and cancellation/admission races. Race cases repeat twenty times; this is not exhaustive schedule coverage. |
| [TaskSupervisorAgentSpec](TaskSupervisorAgentSpec.scala) | Two actual ReAct cases verify assistant-before-tool persistence, one interrupted Error result and recovery, and promoted continuation after parent closure without repeated execution. |
| [SubagentSpawnerSpec](SubagentSpawnerSpec.scala) | Six cases cover private runtime capability construction/no codec, lazy identity and callback effects, exact ancestry/depth rejection without identity consumption, concurrent siblings, closed admission and promoted descendants. |
| [SubagentSpawnerAgentSpec](SubagentSpawnerAgentSpec.scala) | Two actual nested ReAct cases verify fourth-level rejection as an Error observation and cancellation/reconciliation at all three running depths. The spawn tool is a test fixture. |
| [SubagentToolBoundarySpec](SubagentToolBoundarySpec.scala) | Selection/denial intersections across three generations and JSON round trips, plus deterministic malformed-key and excluded-collision rejection. |
| [SubagentFactorySpec](SubagentFactorySpec.scala) | Model-visible final inherited/automatic filtering with attempted forbidden dispatch, derived factory narrowing, fresh configured history with effective injected authorization, lazy/invalid/closed construction, actual descendant spawn/depth rejection, success/failure resource cleanup, and cleanup-joining cancellation during registration, client acquisition and execution. |

AgentErrorSpec verifies the depth diagnostic and codec. Factory and boundary scenarios use table-driven cases for distinct selection and resource phases. No tests execute external commands or use a real model.

## Pinned upstream adaptation

The source comparison uses `agentscope-java` revision `f98a685fdc4589f81e626e1686ac2c63d14a2304`, `agentscope-harness/src/test/java/io/agentscope/harness/agent/ManagedSubagentBoundaryTest.java`.

| Upstream method | Scala evidence and intentional boundary |
| --- | --- |
| `declarationFiltersAutoRegisteredToolsAsWellAsInheritedTools` | Factory final-filter scenario checks both inherited and construction-registered tools through the actual child's model input and attempts to dispatch `write_file`, verifying zero executions when excluded and one execution when allowed. Registration callbacks replace Java filesystem/builder wiring; no declaration parser or filesystem auto-installation is claimed. |
| `childCannotReenableParentDeniedTool` | The same scenario verifies a child selecting a parent-denied inherited or construction-registered tool sees an empty toolkit; parent allowlist intersection and derived factories extend monotonicity coverage. Attempted excluded `write_file` dispatch produces an Error observation without execution. This is exact-name static capability narrowing, not dynamic permission inheritance. |

These bounded adaptations are reviewed and verified within the four-file factory scope. They do not establish parity with the complete upstream harness factory. Runtime-only supervisor/lineage evidence does not complete model-facing spawn/send methods.

## Acceptance and remaining checks

`path_review` approved the exact four factory/source-test identities listed in the [main acceptance record](../../../../../../../main/scala/com/weisgnosis/agents/harness/subagent/README.md#verification-and-acceptance) on 2026-09-30. Full `sbt scalafmtAll scalafmtSbt verify` passed 561 core and 609 harness tests (1,170 total), with zero compiler warnings or failed tests. Accepted evidence covers configured construction, final static filtering and attempted dispatch, monotone boundary narrowing, deterministic validation, supplied authorization, descendant spawning, and scoped cleanup. The supervisor/lineage foundations retain their recorded approvals; in-progress registry files and `SupervisedTask.poll` are excluded from all factory approval and verification claims.

- [x] Verify supervisor/runtime integration and lineage/error contracts.
- [x] Verify and independently approve configured-factory/static-boundary scenarios and the two bounded upstream adaptations at the recorded identities.
- [ ] Verify registry/reuse and actual model-facing spawn/send entry points when implemented.
- [ ] Verify dynamic permission inheritance and declared/general-purpose/reused-child plan propagation.
- [ ] Verify correlated child event forwarding and automatic source attribution.
- [ ] Complete remaining applicable upstream method mappings after those APIs exist.

Next task: verify bounded task registry admission and inspection with the required polling dependency, and obtain independent review of that exact scope before accepting it.

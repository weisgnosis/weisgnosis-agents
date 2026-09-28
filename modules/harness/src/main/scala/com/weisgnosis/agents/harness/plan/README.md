# com.weisgnosis.agents.harness.plan

> Status: planned design and test targets. The APIs and suites described here are not implemented yet.

Planning mode management package (`PlanMode`) governing transitions from architectural design to execution.

---

## 🎯 Purpose and Types

1. **`PlanModeState`**:
   - `active: Boolean` (whether planning mode is currently enforced)
   - `planFile: String = "plans/PLAN.md"`
2. **`PlanModeInterceptor`**:
   - Tool execution filter:
     - Allowed: Read-only tools, search, `plan_write`, `todo_write`, `agent_spawn`.
     - Blocked: File writes, edits, mutating shell commands.
   - Blocked tool invocations receive `ToolResultState.Denied` with the message: *"File mutation is blocked in PLAN mode. Call plan_exit to request execution approval."*
3. **`PlanModeTools`**:
   - `plan_enter`: Activates planning mode.
   - `plan_write(content: String)`: Writes plan draft to `plans/PLAN.md`.
   - `plan_exit`: Solicits operator approval (`PermissionDecision.ask`) to transition into execution phase.

---

## 🔍 References in AgentScope Java

* `agentscope-harness/src/main/java/io/agentscope/harness/agent/workspace/plan/PlanModeManager.java`
* `agentscope-harness/src/main/java/io/agentscope/harness/agent/tool/PlanModeTools.java`
* `agentscope-harness/src/main/java/io/agentscope/harness/agent/middleware/PlanModeMiddleware.java`

---

## 🧪 Test Parity Map

| Upstream AgentScope Java Test | Target ZIO Spec | Verification Focus |
| :--- | :--- | :--- |
| `PlanModeMiddlewareTest.java` | `PlanModeInterceptorSpec.scala` | Blocking mutating operations, allowing read-only and `plan_write` calls |
| `PlanModeSubagentPropagationTest.java` | `PlanModePropagationSpec.scala` | Inheritance of plan mode constraints by spawned child agents |

---

## ⚠️ Critical Invariants
* Exiting Plan Mode (`plan_exit`) **must** require operator confirmation via the HITL Gate. An agent cannot unilaterally grant itself mutation authority.
* Spawned subagents cannot bypass the active constraints of the parent session.

---

## 📋 Implementation Checklist
- [ ] State model `PlanModeState`
- [ ] Tool definitions `PlanModeTools`
- [ ] Invocation interceptor `PlanModeInterceptor`
- [ ] Unit tests: `PlanModeInterceptorSpec.scala`
- [ ] Unit tests: `PlanModePropagationSpec.scala`

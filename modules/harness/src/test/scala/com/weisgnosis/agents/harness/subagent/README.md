# com.weisgnosis.agents.harness.subagent (Tests)

> Status: planned design and test targets. The APIs and suites described here are not implemented yet.

ZIO Test parity suite for `com.weisgnosis.agents.harness.subagent`.

---

## 🧪 Planned Test Specs

1. **`SubagentSpawnSpec.scala`** (corresponds to `AgentSpawnToolTest.java`):
   - Invoking `agent_spawn` and initializing child agent state.
   - Enforcing spawn depth cap (`MAX_SPAWN_DEPTH = 3`).
   - Tool isolation: verifying child agents cannot invoke unauthorized parent tools.

2. **`SubagentCancelSpec.scala`** (corresponds to `AgentSpawnToolCancelEndEventTest.java`):
   - Interrupting the parent fiber during child execution.
   - Verifying child fiber termination.
   - Verifying emission of `SubagentEndEvent(status = "interrupted")`.

3. **`SubagentPlanModePropagationSpec.scala`** (corresponds to `AgentSpawnToolPlanModeTest.java`):
   - Propagating plan mode restrictions from parent to child.
   - Disallowing write tools in child agents when parent is in plan mode.

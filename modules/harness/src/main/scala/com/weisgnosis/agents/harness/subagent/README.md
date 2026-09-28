# com.weisgnosis.agents.harness.subagent

> Status: planned design and test targets. The APIs and suites described here are not implemented yet.

Subagent coordination, hierarchical task delegation, and event stream multiplexing package running on ZIO fibers.

---

## 🎯 Purpose and Types

1. **`SubagentDeclaration`**:
   - `id: String` (unique subagent type identifier)
   - `description: String`
   - `systemPrompt: String`
   - `allowedTools: Set[String]` (permitted tool subset)
   - `persistSession: Boolean = false`
2. **`SubagentCoordinator`**:
   - `spawn(agentId: String, task: String, options: SpawnOptions): ZIO[Scope, AgentError, SubagentHandle]`
   - `send(handle: SubagentHandle, message: String): ZIO[Any, AgentError, Msg]`
3. **Event Stream Multiplexing:**
   - Parent and child event streams are merged via `ZStream.merge`.
   - Child events are stamped with hierarchical sources: `withSource(s"$parentSessionId/$childAgentId")`.
   - Emission of `AgentStartEvent` and `AgentEndEvent` is guaranteed for each subagent even under fiber cancellation.
4. **Guards:**
   - Maximum recursion depth: `MAX_SPAWN_DEPTH = 3`.
   - Timeout Promotion: when synchronous execution exceeds threshold, the task transitions to a background fiber.

---

## 🔍 References in AgentScope Java

* `agentscope-harness/src/main/java/io/agentscope/harness/agent/subagent/AgentSpawnTool.java` (lines 300–700)
* `agentscope-harness/src/main/java/io/agentscope/harness/agent/subagent/AgentEventEmitter.java`
* `agentscope-harness/src/main/java/io/agentscope/harness/agent/middleware/SubagentsMiddleware.java`

---

## 🧪 Test Parity Map

| Upstream AgentScope Java Test | Target ZIO Spec | Verification Focus |
| :--- | :--- | :--- |
| `AgentSpawnToolTest.java` | `SubagentSpawnSpec.scala` | Subagent creation, context inheritance, recursion depth enforcement (depth 3) |
| `AgentSpawnToolCancelEndEventTest.java` | `SubagentCancelSpec.scala` | Guaranteed event stream closure and cascading fiber cancellation |
| `AgentSpawnToolPlanModeTest.java` | `SubagentPlanModePropagationSpec.scala` | End-to-end plan mode restriction propagation to subagents |

---

## ⚠️ Critical Invariants
* On parent fiber interruption (`fiber.interrupt`), all child agent fibers are terminated immediately via structured concurrency (`forkScoped`).
* Events emitted by child agents must never leak untagged into the stream without `source` attribution.

---

## 📋 Implementation Checklist
- [ ] Models `SubagentDeclaration`, `SpawnOptions`, `SubagentHandle`
- [ ] Tools `AgentSpawnTool` (`agent_spawn`, `agent_send`)
- [ ] Event multiplexer with stream tagging via `ZStream.merge`
- [ ] Unit tests: `SubagentSpawnSpec.scala`
- [ ] Unit tests: `SubagentCancelSpec.scala`
- [ ] Unit tests: `SubagentPlanModePropagationSpec.scala`

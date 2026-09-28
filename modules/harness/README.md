# weisgnosis-agents-harness

Enterprise context engineering and agent coordination built with Scala 3.9 LTS and ZIO 2: conversation memory management, large tool result eviction, three-tier Human-in-the-Loop permission gating, fiber-based subagents, and plan mode transitions.

---

## 📦 Package Structure

| Package | Purpose | Upstream AgentScope Java Reference | SLOC Forecast (Main / Test) |
| :--- | :--- | :--- | :---: |
| [`memory`](src/main/scala/com/weisgnosis/agents/harness/memory/README.md) | Conversation compaction, summary deduplication, token estimation | `agentscope-harness/.../memory/compaction/` | ~200 / ~300 |
| [`eviction`](src/main/scala/com/weisgnosis/agents/harness/eviction/README.md) | Tool output offloading (>2k chars) to off-context cache | `agentscope-harness/.../middleware/ToolResultEvictionMiddleware.java` | ~150 / ~250 |
| [`permission`](src/main/scala/com/weisgnosis/agents/harness/permission/README.md) | Human-in-the-Loop gating (Allow, Deny, Ask via `zio.Promise`) | `agentscope-core/.../permission/` | ~180 / ~300 |
| [`subagent`](src/main/scala/com/weisgnosis/agents/harness/subagent/README.md) | Child agents on fibers (`forkScoped`), event stream multiplexing | `agentscope-harness/.../subagent/` | ~250 / ~400 |
| [`plan`](src/main/scala/com/weisgnosis/agents/harness/plan/README.md) | Plan mode, mutation lockouts, mandatory approval gates | `agentscope-harness/.../workspace/plan/` | ~150 / ~200 |

---

## 🎯 Key Architectural Invariants
1. **Atomic Turn Pairing:** Conversation compactor never separates an assistant tool call from its matching tool result block.
2. **Summary Deduplication:** Re-compaction never stacks summaries into recursive "summaries of summaries"; it flattens and merges key facts into a single summary block.
3. **Promise-Based HITL:** Operator confirmation requests suspend execution via a pure `zio.Promise` without thread blocking.
4. **Subagent Scoping:** Bounded spawn depth (`MAX_SPAWN_DEPTH = 3`). Parent agent interruption cascades cleanly to all descendant fibers.

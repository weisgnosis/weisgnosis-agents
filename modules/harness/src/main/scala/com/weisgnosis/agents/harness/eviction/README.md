# com.weisgnosis.agents.harness.eviction

Tool result eviction package for offloading large outputs from the conversation context into an out-of-band store.

---

## 🎯 Purpose and Types

1. **`EvictionConfig`**:
   - `maxChars: Int = 2000` (threshold above which tool output content is truncated)
   - `previewHeadChars: Int = 800`
   - `previewTailChars: Int = 400`
2. **`ToolResultEvictor`**:
   - `def evict(block: ContentBlock.ToolResult): ZIO[ToolResultStore, Nothing, ContentBlock.ToolResult]`
   - When `output.length > maxChars`, full content is persisted to `ToolResultStore`, and replaced in-context with:
     ```text
     <first 800 chars>
     ... [Full output truncated: 1420 lines (48200 chars). Offloaded to cache: cache_id_123] ...
     <last 400 chars>
     ```
3. **`ToolResultStore`**:
   - Storage service trait: `InMemory`, `LocalFile`, or object storage.

---

## 🔍 References in AgentScope Java

* `agentscope-harness/src/main/java/io/agentscope/harness/agent/middleware/ToolResultEvictionMiddleware.java` (lines 40–130)
* `agentscope-harness/.../ToolResultEvictionConfig.java`

---

## 🧪 Test Parity Map

| Upstream AgentScope Java Test | Target ZIO Spec | Verification Focus |
| :--- | :--- | :--- |
| `ToolResultEvictionMiddlewareTest.java` | `ToolResultEvictorSpec.scala` | Eviction of payloads exceeding threshold, preservation of head/tail previews, retrieval from store |

---

## ⚠️ Critical Invariants
* Retaining both head and tail previews is essential for model reasoning so it can observe command initialization as well as terminal status lines (e.g. exit codes, trailing stack traces).
* Small outputs (under `maxChars`) pass through without store latency or payload mutation.

---

## 📋 Implementation Checklist
- [ ] Config `EvictionConfig`
- [ ] Trait `ToolResultStore` + `InMemoryToolResultStore`
- [ ] Eviction transformation in `ToolResultEvictor.evict`
- [ ] Unit tests: `ToolResultEvictorSpec.scala`

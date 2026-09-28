# com.weisgnosis.agents.harness.memory

Conversation compaction, token estimation, and context management package.

---

## 🎯 Purpose and Types

1. **`CompactorConfig`**:
   - `triggerTokens: Int = 120000` (threshold to trigger compaction)
   - `targetTokens: Int = 40000` (target size post-compaction)
   - `preserveTailMessages: Int = 6` (number of recent turns protected from compaction)
2. **`ConversationCompactor`**:
   - `def compact(history: Chunk[Msg], client: LlmClient): ZIO[Any, AgentError, Chunk[Msg]]`
   - Safe cutoff index algorithm `calculateCutoffIndex`: shifts the cutoff boundary backwards to guarantee that assistant tool calls are never severed from their corresponding tool results.
   - Summary deduplication: extracts existing `__compaction_summary__` blocks and flattens them into a unified summary rather than nesting summaries recursively.
3. **`TokenCounter`**:
   - Fast character and BPE heuristic estimation to evaluate context pressure without external tokenizer latency.

---

## 🔍 References in AgentScope Java

* `agentscope-harness/src/main/java/io/agentscope/harness/agent/memory/compaction/ConversationCompactor.java` (lines 60–184)
* `agentscope-harness/src/main/java/io/agentscope/harness/agent/memory/compaction/TokenCounterUtil.java`
* `agentscope-harness/src/main/java/io/agentscope/harness/agent/memory/compaction/CompactionConfig.java`

---

## 🧪 Test Parity Map

| Upstream AgentScope Java Test | Target ZIO Spec | Verification Focus |
| :--- | :--- | :--- |
| `ConversationCompactorTest.java` | `ConversationCompactorSpec.scala` | History compaction, atomic turn pairing preservation, summary deduplication |
| `TokenCounterTest.java` | `TokenCounterSpec.scala` | Token estimation for prose, code, and structured JSON |

---

## ⚠️ Critical Invariants
* Severing a tool call from its matching tool result corrupts dialogue structure, resulting in model API rejections. The cutoff boundary must strictly maintain turn pairing integrity.
* Summary messages carry the metadata attribute `__compaction_summary__ = true`.

---

## 📋 Implementation Checklist
- [ ] Config `CompactorConfig` and heuristic `TokenCounter`
- [ ] Safe turn boundary algorithm in `ConversationCompactor.calculateCutoff`
- [ ] Summary generation effect with deduplication
- [ ] Unit tests: `ConversationCompactorSpec.scala`
- [ ] Unit tests: `TokenCounterSpec.scala`

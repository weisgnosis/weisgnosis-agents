# com.weisgnosis.agents.harness.memory (Tests)

ZIO Test parity suite for `com.weisgnosis.agents.harness.memory`.

---

## 🧪 Planned Test Specs

1. **`ConversationCompactorSpec.scala`** (corresponds to `ConversationCompactorTest.java`):
   - Compaction triggered when token threshold is exceeded.
   - Preserving tail turns untouched (`preserveTailMessages`).
   - Atomic turn pairing: verifying tool call and result pairs are never split across the cutoff line.
   - Deduplication: merging pre-existing `__compaction_summary__` blocks into a single flat summary.

2. **`TokenCounterSpec.scala`** (corresponds to `TokenCounterTest.java`):
   - Estimation accuracy across multi-language prose, source code, and JSON schemas.
   - Boundary tests on empty strings and huge content blocks.

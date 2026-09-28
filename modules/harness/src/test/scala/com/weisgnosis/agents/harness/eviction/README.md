# com.weisgnosis.agents.harness.eviction (Tests)

ZIO Test parity suite for `com.weisgnosis.agents.harness.eviction`.

---

## 🧪 Planned Test Specs

1. **`ToolResultEvictorSpec.scala`** (corresponds to `ToolResultEvictionMiddlewareTest.java`):
   - Passthrough behavior for outputs below `maxChars`.
   - Splitting and preview generation for outputs above `maxChars`.
   - Persisting original raw content into `ToolResultStore`.
   - Idempotency when processing pre-evicted blocks.

# com.weisgnosis.agents.core.event (Tests)

ZIO Test parity suite for `com.weisgnosis.agents.core.event`.

---

## 🧪 Implemented Test Specs (Passing)

1. **`AgentEventSerdeSpec.scala`** `[x]` (corresponds to `EventBusTest.java`):
   - Round-trip serialization for all 31 `AgentEvent` cases.
   - Verification of the `"event"` discriminator field in JSON payloads.
   - Verification that unknown event types produce clean decoding failures without crashing.

2. **`AgentEventStreamingSpec.scala`** `[x]` (corresponds to `HookTest.java`):
   - ZStream buffering and ordering verification.
   - Verification of `withSource` tagging when events flow through subagents.
   - Non-blocking emission and broadcasting into `Hub` with `ZIO.scoped`.

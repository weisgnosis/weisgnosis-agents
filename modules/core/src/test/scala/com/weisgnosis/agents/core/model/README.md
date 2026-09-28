# com.weisgnosis.agents.core.model (Tests)

ZIO Test parity suite for `com.weisgnosis.agents.core.model`.

---

## 🧪 Planned Test Specs

1. **`MsgSpec.scala`** (corresponds to `MsgTest.java`):
   - Message construction with various `Role` values.
   - Text extraction from single and multi-block messages.
   - Metadata persistence and immutability.
   - Message transformation (`copy`, block appending).

2. **`MsgSerdeSpec.scala`** (corresponds to `MsgJacksonModuleTest.java`):
   - JSON encoding and decoding round-trip property tests.
   - Wire format backwards compatibility tests with standard OpenAI-compatible and Anthropic message formats.
   - Handling of optional fields and empty chunks.

3. **`ContentBlockSerdeSpec.scala`** (corresponds to `ContentBlockTest.java`):
   - Polymorphic discrimination on `type: "text" | "thinking" | "tool_use" | "tool_result" | "image" | "custom"`.
   - Parsing of `ToolUse` arguments with nested JSON structures.
   - Handling unrecognized custom blocks gracefully.

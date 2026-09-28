# com.weisgnosis.agents.core.model (Tests)

ZIO Test parity suite for `com.weisgnosis.agents.core.model`.

---

## 🧪 Implemented Test Specs (Passing - 42 tests)

1. **`MsgSpec.scala`** `[x]` (corresponds to `MsgTest.java` and `RoleContentValidationTest.java`):
   - Message construction with various `Role` values.
   - Text extraction from single and multi-block messages.
   - Metadata persistence and immutability.
   - Role content validation invariants.

2. **`MsgSerdeSpec.scala`** `[x]` (corresponds to `MsgUsageSerializationTest.java`):
   - JSON encoding and decoding round-trip tests.
   - `ChatUsage` metrics and cached tokens serialization.
   - Rejection of invalid messages during deserialization.

3. **`ContentBlockSerdeSpec.scala`** `[x]` (corresponds to `ToolUseBlockTest.java`, `ToolResultBlockTest.java`):
   - Polymorphic discrimination on `type: "text" | "thinking" | "tool_use" | "tool_result" | "image" | "custom"`.
   - Parsing of `ToolUse` arguments with nested JSON structures.
   - `ToolResult` error factory and server tool markers.
   - Flexible decoding of string vs block output.

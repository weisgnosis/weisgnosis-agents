# com.weisgnosis.agents.mcp.protocol (Tests)

ZIO Test parity suite for `com.weisgnosis.agents.mcp.protocol`.

---

## 🧪 Planned Test Specs

1. **`McpProtocolSpec.scala`**:
   - JSON-RPC 2.0 serialization of requests, responses, and notifications.
   - Validation of `RequestId` serialization (numbers and strings).
   - Error code deserialization.

2. **`McpToolCodecSpec.scala`**:
   - Decoding MCP `tools/list` payloads.
   - Decoding MCP `tools/call` inputs and outputs.

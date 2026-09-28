# com.weisgnosis.agents.mcp.client (Tests)

ZIO Test parity suite for `com.weisgnosis.agents.mcp.client`.

---

## 🧪 Planned Test Specs

1. **`McpClientSpec.scala`**:
   - Initializing connection and negotiating protocol versions.
   - Querying server capabilities and available tools.
   - Calling tools and handling successful and error results.

2. **`McpToolkitBridgeSpec.scala`**:
   - Converting `Chunk[McpTool]` into `Toolkit`.
   - Dispatching calls from agent ReAct loop into MCP tools.
   - Propagating tool failure payloads back to the agent without crashing the loop.

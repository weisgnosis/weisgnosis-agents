# weisgnosis-agents-mcp

Native ZIO client implementation for the **Model Context Protocol (MCP)**. Enables seamless connection to external MCP servers (filesystems, databases, GitHub, web browsers) and automatic bridging of their declared tools into the agent's type-safe `Toolkit`.

---

## 📦 Package Structure

| Package | Purpose | SLOC Forecast (Main / Test) |
| :--- | :--- | :---: |
| [`protocol`](src/main/scala/com/weisgnosis/agents/mcp/protocol/README.md) | JSON-RPC 2.0 schemas for MCP messages, tool schemas, and resource definitions | ~150 / ~150 |
| [`transport`](src/main/scala/com/weisgnosis/agents/mcp/transport/README.md) | Transports: Stdio (`zio.process`) and SSE (`zio-http`) | ~200 / ~200 |
| [`client`](src/main/scala/com/weisgnosis/agents/mcp/client/README.md) | High-level `McpClient`, tool discovery, and bridge to `Toolkit` | ~150 / ~150 |

---

## 🎯 Key Architectural Invariants
1. **Strict Specification Compliance:** Complete support for MCP 2024-11-05+ specifications over JSON-RPC 2.0.
2. **Resource Safety:** Local server child processes (Stdio) are isolated and guaranteed to terminate cleanly when leaving the ZIO `Scope`.
3. **Seamless Toolkit Integration:** Tools exposed by any MCP server integrate directly into an agent's `Toolkit` in one line of code:
   ```scala
   val agentToolkit = toolkit ++ mcpClient.asToolkit
   ```

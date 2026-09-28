# com.weisgnosis.agents.mcp.client

> Status: planned design and test targets. The APIs and suites described here are not implemented yet.

High-level Model Context Protocol client, dynamic tool discovery, and runtime agent integration.

---

## 🎯 Purpose and Types

1. **`McpClient`**:
   - `def initialize: ZIO[Any, Throwable, InitializeResult]`
   - `def listTools: ZIO[Any, Throwable, Chunk[McpTool]]`
   - `def callTool(name: String, args: zio.json.ast.Json): ZIO[Any, Throwable, CallToolResult]`
   - `def asToolkit: ZIO[Any, Throwable, Toolkit]` (automatic bridge to agent `Toolkit`)
2. **`McpToolBridge`**:
   - Converts each discovered `McpTool` into a native `Tool[Any, Json, String]`.
   - Proxies invocations directly into `mcpClient.callTool`.

---

## 🧪 Test Parity Map

| Test Specification | Target ZIO Spec | Verification Focus |
| :--- | :--- | :--- |
| MCP Client Lifecycle | `McpClientSpec.scala` | Initialization handshake, listing available server tools |
| Toolkit Bridge Test | `McpToolkitBridgeSpec.scala` | Registering MCP tools in agent toolkit and dispatching model executions |

---

## 📋 Implementation Checklist
- [ ] Class `McpClient` with lifecycle methods
- [ ] Toolkit bridge adapter `asToolkit`
- [ ] Unit & Mock tests: `McpClientSpec.scala`
- [ ] Unit & Mock tests: `McpToolkitBridgeSpec.scala`

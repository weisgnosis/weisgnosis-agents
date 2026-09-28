# com.weisgnosis.agents.mcp.protocol

> Status: planned design and test targets. The APIs and suites described here are not implemented yet.

Model Context Protocol (MCP) data models and schemas built on JSON-RPC 2.0.

---

## 🎯 Purpose and Types

1. **JSON-RPC 2.0 Base Types:**
   - `JsonRpcRequest(jsonrpc: String = "2.0", id: RequestId, method: String, params: Option[zio.json.ast.Json])`
   - `JsonRpcResponse(jsonrpc: String = "2.0", id: RequestId, result: Option[zio.json.ast.Json], error: Option[JsonRpcError])`
   - `JsonRpcNotification(jsonrpc: String = "2.0", method: String, params: Option[zio.json.ast.Json])`
   - `JsonRpcError(code: Int, message: String, data: Option[zio.json.ast.Json])`
2. **MCP Specific Types:**
   - `InitializeParams`, `InitializeResult`, `ServerCapabilities`
   - `ListToolsResult`, `McpTool(name: String, description: Option[String], inputSchema: zio.json.ast.Json)`
   - `CallToolParams(name: String, arguments: Option[zio.json.ast.Json])`
   - `CallToolResult(content: Chunk[McpContent], isError: Boolean = false)`

---

## 🧪 Test Parity Map

| Test Specification | Target ZIO Spec | Verification Focus |
| :--- | :--- | :--- |
| MCP Protocol Spec | `McpProtocolSpec.scala` | Serialization of requests/responses, enforcement of `jsonrpc: "2.0"` |
| Tool Schema Parsing | `McpToolCodecSpec.scala` | Precise parsing of tool schemas and error payloads |

---

## 📋 Implementation Checklist
- [ ] Base JSON-RPC 2.0 types + codecs
- [ ] MCP initialization, tool listing, and execution schemas
- [ ] Unit tests: `McpProtocolSpec.scala`
- [ ] Unit tests: `McpToolCodecSpec.scala`

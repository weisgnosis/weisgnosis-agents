# com.weisgnosis.agents.mcp.transport

Bidirectional communication transport layer for Model Context Protocol servers (Stdio and SSE).

---

## 🎯 Purpose and Types

1. **`McpTransport`**:
   - `def send(request: JsonRpcRequest): ZIO[Any, Throwable, JsonRpcResponse]`
   - `def notify(notification: JsonRpcNotification): ZIO[Any, Throwable, Unit]`
   - `def incoming: ZStream[Any, Throwable, JsonRpcNotification]`
2. **`StdioTransport`**:
   - Process launcher using `zio.process.Command(command, args*)`.
   - Line-delimited JSON-RPC message exchange across stdin and stdout.
   - Guaranteed process teardown upon leaving the resource `Scope`.
3. **`SseTransport`**:
   - Remote MCP server connectivity over HTTP SSE using `zio-http`.
   - Reading server event streams from `/sse` and issuing commands via HTTP POST.

---

## 🧪 Test Parity Map

| Test Specification | Target ZIO Spec | Verification Focus |
| :--- | :--- | :--- |
| Stdio Echo Test | `StdioTransportSpec.scala` | Process spawning, request/response exchange, clean stdin closure |
| Sse Transport Test | `SseTransportSpec.scala` | HTTP mock server connection, SSE frame parsing and message routing |

---

## ⚠️ Critical Invariants
* Stdio transport must handle stream buffering safely without hanging on partial line fragments.
* If a sub-process terminates unexpectedly, the transport surfaces a typed failure containing the exit code and stderr tail.

---

## 📋 Implementation Checklist
- [ ] Trait `McpTransport`
- [ ] Implementation `StdioTransport` via `zio.process`
- [ ] Implementation `SseTransport` via `zio-http`
- [ ] Integration tests: `StdioTransportSpec.scala`
- [ ] Integration tests: `SseTransportSpec.scala`

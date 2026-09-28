# com.weisgnosis.agents.mcp.transport (Tests)

ZIO Test parity suite for `com.weisgnosis.agents.mcp.transport`.

---

## 🧪 Planned Test Specs

1. **`StdioTransportSpec.scala`**:
   - Spawning echo/mock CLI process.
   - Sending JSON-RPC request and receiving matching response.
   - Resource cleanup: ensuring child process is terminated when Scope closes.

2. **`SseTransportSpec.scala`**:
   - Connecting to a local mock HTTP server.
   - Receiving SSE event notifications.
   - Dispatching POST requests and matching RPC responses.

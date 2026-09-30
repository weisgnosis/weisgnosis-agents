# com.weisgnosis.agents.mcp.client

Status: planned. No MCP client, discovery mechanism, or Toolkit bridge is implemented. The [protocol package](../protocol/README.md) provides accepted checked envelopes and pure 2025-06-18 initialization payload helpers accepted within the documented payload scope; neither implements a runtime handshake.

The future client must follow explicitly selected version-specific method/lifecycle contracts, discover capabilities and tools, and distinguish remote JSON-RPC errors from tool-call result failures. Tool adapters must preserve cancellation and turn ordinary tool failures into model observations. Connection authentication and trust configuration belong to explicit host integration.

## Remaining work

- [ ] Next: define the scoped 2025-06-18 client lifecycle after call-result content contracts are implemented. List/call request helpers and a one-page list parser alone do not discover or execute tools.
- [ ] Implement the selected revision's capability and discovery behavior. MCP 2026-07-28 does not use `initialize`; older initialization and negotiation require explicit legacy support.
- [ ] Implement tool invocation and scoped connection shutdown.
- [ ] Bridge discovered tools into Toolkit with explicit metadata and naming policy.
- [ ] Add lifecycle and ReAct integration tests, then obtain independent review and verification.

AgentScope Java wrapper `listTools`, `callTool`, and `close` methods are future behavioral mappings; `initialize` maps only to an explicitly supported older lifecycle. Existing SDK-backed wrapper tests do not establish support in this module. No supported MCP protocol revision is currently claimed.

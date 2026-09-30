# com.weisgnosis.agents.mcp.transport

Status: planned. No process, HTTP, SSE, or other MCP transport is implemented. The [protocol package](../protocol/README.md) currently provides checked envelopes only.

Transport design must make ownership, request correlation, inbound server messages, cancellation, message size limits, and shutdown explicit. Local processes and network streams must remain scoped; cancellation must join owned cleanup. Authentication and server trust are host responsibilities.

## Remaining work

- [ ] Next: select transport/revision scope after method schemas and client lifecycle are defined.
- [ ] Implement scoped stdio process transport and bounded framing.
- [ ] Define and implement the selected HTTP transport; any legacy SSE compatibility needs separate scope and tests.
- [ ] Implement request correlation, server requests/notifications, cancellation, and terminal failure handling.
- [ ] Add local integration tests for framing, errors, and cleanup before independent review and verification.

Transport and protocol-version support must be established by implementation and tests. No wire connectivity or complete MCP revision support follows from the envelope codecs.

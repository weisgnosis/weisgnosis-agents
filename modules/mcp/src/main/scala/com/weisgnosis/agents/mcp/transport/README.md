# com.weisgnosis.agents.mcp.transport

[McpTransport](McpTransport.scala) is an injected duplex message port. There is no concrete process, HTTP or SSE implementation. Its lifecycle integration is independently reviewed and accepted with [McpConnection](../client/README.md).

The host acquires the transport before its connection and releases it after connection cleanup. The incoming stream emits checked [McpMessage](../protocol/McpMessage.scala) values; malformed framing or decoding must fail the stream with a typed AgentError. The connection checks outbound envelopes before sending. Implementations must support concurrent sends without interleaving wire frames, suspend all real-world interaction, and make cancellation wait for owned cleanup. Every send receives a fresh scope.

A local send failure fails that operation. A transport may remain reusable only if the failed or interrupted write leaves framing intact. Ambiguous or fatal writes must also fail incoming so the connection terminates; a future byte-stream backend cannot silently treat partial writes as recoverable.

The connection bounds active operations and queued server responses, not payload bytes. Framing, message-size limits, process/network resources, authentication, server trust and transport-specific reconnect policy remain explicit adapter/host responsibilities.

## Verification and remaining work

- [x] `path_review` and `current_docs` approved the port with the connection source and controlled-transport suite; full formatting, compilation and repository tests passed.
- [ ] Next: implement a scoped concrete transport with bounded framing and local integration tests.
- [ ] Define HTTP revision and legacy SSE support separately; no connectivity follows from this port.
- [ ] Add explicit deadline and cancellation-notification policy before claiming a complete client lifecycle.

Exact reviewed identities and verification are recorded in the [client acceptance](../client/README.md#verification-and-acceptance). The protocol helpers alone do not perform a handshake.

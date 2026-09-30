# mcp: test package index

- [protocol](protocol/README.md): seventeen accepted envelope, initialization, and tool request/list/call-result groups.
- [transport](transport/README.md): planned local transport and cleanup tests.
- [client](client/README.md): four request-correlation and ownership groups; transport/lifecycle and Toolkit integration tests remain pending.

The current tests validate specification-derived wire boundaries and local request ownership. They do not exercise a server, connection handshake, transport, or complete AgentScope wrapper parity.

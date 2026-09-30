# mcp: test package index

- [protocol](protocol/README.md): five accepted envelope groups plus four 2025-06-18 initialization payload groups accepted within the documented payload scope.
- [transport](transport/README.md): planned local transport and cleanup tests.
- [client](client/README.md): planned lifecycle and Toolkit integration tests.

The current tests validate specification-derived wire boundaries. They do not exercise a server, connection handshake, transport, or complete AgentScope wrapper parity.

# mcp: test package index

- [protocol](protocol/README.md): seventeen accepted envelope, initialization, and tool request/list/call-result groups.
- [transport](transport/README.md): planned local transport and cleanup tests.
- [client](client/README.md): four request-registry groups and seven focused connection groups; both reviewers approved the connection scope and full verification passed. Concrete transport, revision-specific lifecycle, and Toolkit integration tests remain pending.

The current tests validate specification-derived wire boundaries and local request ownership. Connection tests use an in-memory borrowed port; they do not exercise a concrete transport, revision-specific handshake, or complete AgentScope wrapper parity.

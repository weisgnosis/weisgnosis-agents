# mcp: test package index

- [protocol](protocol/README.md): seventeen accepted envelope, initialization, and tool request/list/call-result groups.
- [transport](transport/README.md): four framing and three real-process groups pass with independent final approvals and full repository verification.
- [client](client/README.md): four request-registry, seven connection and four initialized-client groups; both reviewers approved the scope and full verification passed. Toolkit integration tests remain pending.

The tests validate specification-derived wire boundaries, local request ownership and direct-process stdio cleanup. Connection unit tests use an in-memory borrowed port; separate stdio tests exercise real child processes. A real peer verifies the selected initialize/initialized/list/call lifecycle. Complete AgentScope wrapper parity is not claimed.

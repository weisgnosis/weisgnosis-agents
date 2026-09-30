# mcp: test package index

- [protocol](protocol/README.md): seventeen accepted envelope, initialization, and tool request/list/call-result groups.
- [transport](transport/README.md): eight stdio/HTTP framing, three real-process and four HTTP transport groups pass with independent final approvals and full repository verification.
- [client](client/README.md): twenty-three groups covering request registry, connection, initialized client and scoped manager ownership have independent approval and full verification; manager coverage includes the reproduced compound-cause regression; cancellation coverage checks atomic abandonment, eligibility and independent bounded delivery.

- [tool](tool/README.md): three binding integration groups covering authority/routing/content, argument validation/error distinctions and joined cancellation.

The tests validate specification-derived wire boundaries, local request ownership and direct-process stdio cleanup. Connection unit tests use an in-memory borrowed port; separate stdio tests exercise real child processes. A real peer verifies the selected initialize/initialized/list/call lifecycle. Complete AgentScope wrapper parity is not claimed.

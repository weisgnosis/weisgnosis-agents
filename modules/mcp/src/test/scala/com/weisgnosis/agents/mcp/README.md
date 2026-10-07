# mcp: test package index

The [resource client contract](../../../../../../main/scala/com/weisgnosis/agents/mcp/client/README.md#resource-client-contract) records the current eleven-file manifest, FP/ZIO and invariant approvals and 29 focused passing groups for resource requests/results, capability/pagination/subscription behavior, real SDK resource calls and terminal/observer lifecycle regressions. Full verification, package preparation and the fresh artifact consumer passed; final combined documentation approval passed against 1441a97.

- [protocol](protocol/README.md): checked envelope, initialization, tool and resource request/list/read/result contracts.
- [transport](transport/README.md): nine stdio/HTTP framing, three real-process, eighteen HTTP transport and two real-network interoperability groups (SDK session replacement and Netty POST-body recovery) pass with independent final approvals and full repository verification.
- [client](client/README.md): suites covering request registry, connection, initialized client and scoped manager ownership have independent approval and full verification; manager coverage includes controlled-peer fresh registration after typed expiry, coherent grouped installation, real ReAct activation and the reproduced compound-cause regression; cancellation coverage checks atomic abandonment, eligibility and independent bounded delivery.

- [tool](tool/README.md): three binding, four metadata/argument integration and two pure argument-policy groups cover authority/routing/content, approved-input/default precedence, schema exclusion, metadata identity/omission, invalid input and joined cancellation; package acceptance records final identities and evidence.

The tests validate specification-derived wire boundaries, local request ownership and direct-process stdio cleanup. Connection unit tests use an in-memory borrowed port; separate stdio tests exercise real child processes. A real peer verifies the selected initialize/initialized/list/call lifecycle. Complete AgentScope wrapper parity is not claimed.

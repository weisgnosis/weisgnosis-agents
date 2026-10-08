# mcp: test package index

The [revision agreement contract](../../../../../../main/scala/com/weisgnosis/agents/mcp/transport/README.md#supported-server-revision-agreement) is exercised through scoped negotiation, correlated initialization and framing/header transitions. The [resource client contract](../../../../../../main/scala/com/weisgnosis/agents/mcp/client/README.md#resource-client-contract) is exercised through resource codecs, capability checks, pagination/subscriptions, actual local SDK resource calls and terminal/observer cleanup. These checks do not establish complete protocol or upstream-wrapper parity.

- [protocol](protocol/README.md): checked envelope, initialization, tool and resource request/list/read/result contracts.
- [transport](transport/README.md): bounded raw/envelope framing, selected-version real stdio processes, legacy and Streamable HTTP, SDK legacy/session replacement and Netty POST-body recovery.
- [client](client/README.md): request registry, connection, initialized client and scoped manager ownership; manager coverage includes controlled-peer fresh registration after typed expiry, coherent grouped installation, real ReAct activation and the reproduced compound-cause regression; cancellation coverage checks atomic abandonment, eligibility and independent bounded delivery.

- [tool](tool/README.md): binding and metadata/argument integration groups cover authority/routing/content, approved-input/default precedence, schema exclusion, metadata identity/omission, invalid input and joined cancellation; package contracts describe exact API and migration limits.

The tests validate specification-derived wire boundaries, local request ownership and direct-process stdio cleanup. Connection unit tests use an in-memory borrowed port; separate stdio tests exercise real child processes. A real peer verifies the selected initialize/initialized/list/call lifecycle. Complete AgentScope wrapper parity is not claimed.

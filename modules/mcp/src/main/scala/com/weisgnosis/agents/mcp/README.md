# mcp: source package index

[Supported server revision agreement](transport/README.md#supported-server-revision-agreement) provides opt-in checked revision membership with one scoped negotiation owner and correlated initialization. Exact-version defaults remain; legacy POST carries the selected revision header. [Resource access](client/README.md#resource-client-contract) includes capability-checked requests, bounded discovery and explicit subscriptions. These are selected contracts, not complete conformance to every MCP revision.

- [protocol](protocol/README.md): checked envelopes and explicit selected-lifecycle initialization; accepted tool request/list/call-result projections.
- [transport](transport/README.md): borrowed duplex port, bounded stdio and HTTP JSON/SSE framing with response-local event-ID snapshots, scoped direct-process stdio and legacy endpoint-event HTTP/SSE; reviewed within those contracts. The scoped HTTP POST and explicit GET adapter adds JSON/SSE responses, session headers, opt-in bounded same-send continuation, bounded listener resumption and explicit session termination; host replacement after typed expiry is explicit; broader interoperability and durable integration remain separate.
- [client](client/README.md): scoped bounded request correlation and borrowed-port RPC ownership; explicit lifecycle selection, capability checks, bounded discovery, invocation, joined deadlines and bounded optional outbound cancellation; independently reviewed scoped manager ownership and coherent host-selected Toolkit/group publication.

- [tool](tool/README.md): borrowed-client binding, host authority, checked shallow defaults/root input-schema exclusion, opt-in metadata projection and typed observations; scoped acceptance is recorded in the package contract.

Package READMEs describe APIs, ownership and migration. Accepted selected-version behavior does not imply complete MCP support or support for newer protocol revisions.

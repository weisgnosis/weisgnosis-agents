# mcp: source package index

- [protocol](protocol/README.md): accepted envelope/2025-06-18 initialization helpers; accepted tool request/list/call-result projections.
- [transport](transport/README.md): borrowed duplex port, bounded stdio and HTTP JSON/SSE framing, scoped direct-process stdio; reviewed within those contracts. The scoped HTTP POST and explicit GET adapter adds JSON/SSE responses and session headers; broader transport recovery remains pending.
- [client](client/README.md): scoped bounded request correlation and borrowed-port RPC ownership; selected 2025-06-18 initialization, capability checks, bounded discovery, invocation, joined deadlines and bounded optional outbound cancellation; independently reviewed scoped manager ownership and immutable host-selected Toolkit publication.

- [tool](tool/README.md): manual borrowed-client binding, explicit host authority and typed observation projection.

Package READMEs record contracts and acceptance. Accepted selected-version behavior does not imply complete MCP support or support for newer protocol revisions.

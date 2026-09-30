# com.weisgnosis.agents.mcp.transport: planned tests

No concrete transport is implemented. The borrowed [transport port](../../../../../../../main/scala/com/weisgnosis/agents/mcp/transport/README.md) is reviewed with the [connection suite](../client/README.md), which uses controlled message ports rather than network or process fixtures.

- [ ] Local stdio fixtures for framing, partial input, message limits, and process termination.
- [ ] Local HTTP fixtures for the explicitly selected transport and protocol revision.
- [x] Controlled-port connection tests cover correlation, server requests, ignored notifications, cancellation and terminal failures; concrete wire integration remains pending.
- [x] Controlled-port tests establish joined concurrent shutdown/cancellation and borrowed-resource ordering; repeat these contracts at each concrete transport boundary.
- [ ] Independent review and final verification of each implemented transport.

The current pure envelope tests make no network, server-handshake, or process-lifecycle claim.

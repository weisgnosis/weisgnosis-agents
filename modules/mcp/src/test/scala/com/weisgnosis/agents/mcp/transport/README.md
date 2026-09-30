# com.weisgnosis.agents.mcp.transport: planned tests

No transport tests or implementation exist yet. The [transport contract](../../../../../../../main/scala/com/weisgnosis/agents/mcp/transport/README.md) remains a roadmap.

- [ ] Local stdio fixtures for framing, partial input, message limits, and process termination.
- [ ] Local HTTP fixtures for the explicitly selected transport and protocol revision.
- [ ] Correlation, inbound server requests/notifications, cancellation, and terminal failures.
- [ ] Concurrent shutdown and cancellation that join resource cleanup.
- [ ] Independent review and final verification of each implemented transport.

The current pure envelope tests make no network, server-handshake, or process-lifecycle claim.

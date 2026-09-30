# com.weisgnosis.agents.mcp.client: planned tests

No client tests or implementation exist yet. The [client contract](../../../../../../../main/scala/com/weisgnosis/agents/mcp/client/README.md) remains a roadmap.

- [ ] Version-specific method/lifecycle contracts; MCP 2026-07-28 does not use `initialize`. Initialization ordering, repetition, and negotiation tests require explicit legacy support.
- [ ] Capability/tool discovery and invocation under the selected revision's lifecycle.
- [ ] Distinct protocol errors, tool-result errors, transport failures, and cancellation.
- [ ] Toolkit/ReAct integration, tool naming, and metadata trust boundaries.
- [ ] Connection cleanup and independent review of final implementation.

AgentScope Java wrapper tests provide future lifecycle mappings. Current envelope tests do not cover these contracts.

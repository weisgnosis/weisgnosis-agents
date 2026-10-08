# Typed error verification

[AgentErrorSpec](AgentErrorSpec.scala) verifies selected individual codecs and diagnostics for text transformation, memory/plan storage, descendant depth, acting-stop diagnostics and remote MCP errors, plus the token-free McpSessionExpired empty-object leaf codec and fixed diagnostic. One table-driven codec group preserves absent, explicit-null and object error data. Narrow host callback suites cover reasoning, history preparation, input projection and completion-observation error provenance. Their runtime integrations verify that host failures do not become provider fallback signals.

[McpRequestsSpec](../../../../../../../../../mcp/src/test/scala/com/weisgnosis/agents/mcp/client/McpRequestsSpec.scala) exercises the additive McpError boundary through capacity rejection, scoped abandonment and closure. It checks complete domain causes and the mapping of foreign typed request-scope failures without losing defects or interruption.

These error contracts and suites cover the listed boundaries; passing them does not establish exhaustive upstream parity.

Acceptance for unchanged host callback, storage and lineage behavior remains in the relevant [source package contracts](../../../../../../../main/scala/com/weisgnosis/agents/core/error/README.md). Current full-build evidence is recorded in the repository tracker; Git preserves prior review history.

The existing error table includes the additive StorageError leaf. Versioned backend runtime failures belong to the separate [storage contract](../../../../../../../main/scala/com/weisgnosis/agents/core/error/README.md); leaf codec checks alone do not establish database correctness.

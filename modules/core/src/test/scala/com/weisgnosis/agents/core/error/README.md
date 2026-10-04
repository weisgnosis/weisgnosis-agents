# Typed error verification

[AgentErrorSpec](AgentErrorSpec.scala) verifies selected individual codecs and diagnostics for text transformation, memory/plan storage, descendant depth and remote MCP errors, plus the token-free McpSessionExpired empty-object leaf codec and fixed diagnostic. One table-driven codec group preserves absent, explicit-null and object error data. Narrow host callback suites cover reasoning, history preparation, input projection and completion-observation error provenance. Their runtime integrations verify that host failures do not become provider fallback signals.

[McpRequestsSpec](../../../../../../../../../mcp/src/test/scala/com/weisgnosis/agents/mcp/client/McpRequestsSpec.scala) exercises the additive McpError boundary through capacity rejection, scoped abandonment and closure. It checks complete domain causes and the mapping of foreign typed request-scope failures without losing defects or interruption.

- [x] `registry_contract` and `current_docs` approved typed HTTP session expiry and host recovery integration. The source contract records exact current identities and full `mcp-session-expiry-verify.log` verification.
- [ ] Complete exhaustive error-hierarchy and applicable upstream scenario mapping; the current suites do not claim it.

Acceptance for unchanged host callback, storage and lineage behavior remains in the relevant [source package contracts](../../../../../../../main/scala/com/weisgnosis/agents/core/error/README.md). Current full-build evidence is recorded in the repository tracker; Git preserves prior review history.

# Typed error verification

[AgentErrorSpec](AgentErrorSpec.scala) verifies selected individual codecs and diagnostics for text transformation, memory/plan storage and descendant depth. Narrow host callback suites cover reasoning, history preparation, input projection and completion-observation error provenance. Their runtime integrations verify that host failures do not become provider fallback signals.

[McpRequestsSpec](../../../../../../../../../mcp/src/test/scala/com/weisgnosis/agents/mcp/client/McpRequestsSpec.scala) exercises the additive McpError boundary through capacity rejection, scoped abandonment and closure. It checks complete domain causes and the mapping of foreign typed request-scope failures without losing defects or interruption.

- [x] `path_review` and `current_docs` approved the additive MCP error/runtime scope with no findings. Exact identities and verification are in the [source error contract](../../../../../../../main/scala/com/weisgnosis/agents/core/error/README.md#current-review-acceptance) and linked MCP client acceptance.
- [ ] Complete exhaustive error-hierarchy and applicable upstream scenario mapping; the current suites do not claim it.

Acceptance for unchanged host callback, storage and lineage behavior remains in the relevant [source package contracts](../../../../../../../main/scala/com/weisgnosis/agents/core/error/README.md). Current full-build evidence is recorded in the repository tracker; Git preserves prior review history.

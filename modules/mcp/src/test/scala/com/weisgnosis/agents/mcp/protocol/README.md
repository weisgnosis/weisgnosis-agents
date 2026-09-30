# com.weisgnosis.agents.mcp.protocol: tests

[McpProtocolSpec](McpProtocolSpec.scala) tests the [checked envelope contract](../../../../../../../main/scala/com/weisgnosis/agents/mcp/protocol/README.md). The five grouped tests, including the reproduced integer-boundary regression, pass and are accepted within the linked envelope scope.

| Test | Coverage |
| --- | --- |
| Integer bounds | Oversized exponent notation and constructed IDs/error codes are rejected on inbound/outbound paths; valid boundary values and large-exponent zero remain accepted |
| Request identifiers | Empty/numeric text, negative and large integers, integral exponent notation, fixed protocol marker, and retained params extensions |
| Notifications | No identity field; omitted versus empty object params |
| Results and remote errors | Object results, present/absent correlation, large error codes, omitted data versus JSON null, and structured error data |
| Checked boundaries | Invalid versions and shapes, fractional numbers, mixed variants, malformed optional fields, unknown envelope/error fields, duplicate keys including nested payloads, trailing input, and outbound AST validation |

These are specification-derived tests, not claimed one-to-one mappings to AgentScope Java wrapper methods. The pinned reference uses MCP Java SDK 0.17.2 for wire types and serialization. Its wrapper tests mock SDK objects; in particular, `CallToolResult.isError` is a method result flag, not a JSON-RPC error envelope.

## Acceptance and next tests

- [x] Five grouped envelope tests pass with positive and negative cases.
- [x] Independent reviews and full repository verification are complete. The [protocol acceptance](../../../../../../../main/scala/com/weisgnosis/agents/mcp/protocol/README.md#verification-and-acceptance) records exact scope, reviewers, reviewed identities, and verification evidence.
- [ ] Next: version-specific method/capability and tool-list/call payload codec tests. MCP 2026-07-28 has no `initialize` method; older initialization/negotiation tests belong only to explicitly supported legacy revisions.

No transport, correlation state machine, server handshake, method schema, or whole-SDK compatibility is exercised here.

Fail-before-fix evidence: `mcp-integer-bound-red.log` records five failing rejection assertions for `1e100` IDs/error codes and oversized constructed IDs/error codes before the guard was added. The corrected boundary regression now passes in the accepted verification run.

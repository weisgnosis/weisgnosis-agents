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

## Initialization payload tests

[McpInitializationSpec](McpInitializationSpec.scala) adds four grouped tests for the explicitly selected 2025-06-18 revision. This payload-only scope is accepted with the review and verification evidence linked below.

| Test | Coverage |
| --- | --- |
| Request and notification | Exact revision, empty client capabilities, optional implementation title, supplied ID validation, and identity-free initialized notification |
| Capability projection | Absent versus present tools, absent/false/true listChanged, retained extension fields, instructions, and object `_meta` |
| Checked result boundary | Required fields, malformed/null known optionals, and recursive duplicate rejection |
| Exact revision | Reject other revisions and malformed version fields without fallback |

## Acceptance and next tests

- [x] Five grouped envelope tests pass with positive and negative cases.
- [x] Independent reviews and full repository verification are complete. The [protocol acceptance](../../../../../../../main/scala/com/weisgnosis/agents/mcp/protocol/README.md#verification-and-acceptance) records exact scope, reviewers, reviewed identities, and verification evidence.
- [x] Four initialization payload groups pass, for nine MCP groups in total; `path_review` and `current_docs` approved the exact source/test identities recorded in the linked protocol acceptance.
- [ ] Next: tool-list/call payload tests for 2025-06-18, then scoped client/transport integration. MCP 2026-07-28 initialization is outside this explicitly selected older contract.

No transport, correlation state machine, server handshake, runtime ordering, or whole-SDK compatibility is exercised here.

Fail-before-fix evidence: `mcp-integer-bound-red.log` records five failing rejection assertions for `1e100` IDs/error codes and oversized constructed IDs/error codes before the guard was added. The corrected boundary regression now passes in the accepted verification run.

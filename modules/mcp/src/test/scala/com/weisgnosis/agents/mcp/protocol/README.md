# com.weisgnosis.agents.mcp.protocol: tests

The [supported server revision agreement](../../../../../../../main/scala/com/weisgnosis/agents/mcp/transport/README.md#supported-server-revision-agreement) adds opt-in checked revision membership, one scoped negotiation owner, atomic correlated initialization and immediate framing/header transitions. Exact-version defaults remain; legacy POST additionally carries the revision header. This does not establish complete original-plan parity.

[McpPacketSpec](McpPacketSpec.scala) uses three grouped contracts for invalid request positions and response-error avoidance, selected revision/member limits, and exact explicit-null invalid-request replies. Singular-envelope boundaries are checked separately.

[McpProtocolVersionSpec](McpProtocolVersionSpec.scala) checks explicit selected handshakes across 2024-11-05, 2025-03-26 and 2025-06-18, preserving the default and rejecting unrecognized server versions.

[McpProtocolSpec](McpProtocolSpec.scala) tests the [checked envelope contract](../../../../../../../main/scala/com/weisgnosis/agents/mcp/protocol/README.md). Grouped tests cover integer boundaries and exact numeric rendering.

| Test | Coverage |
| --- | --- |
| Content variants | Ordered text, image, audio, resource links, and embedded text/blob/both bodies |
| Outcome and metadata | Tool errors remain results; absent/false isError normalization, structuredContent, annotations, and distinct inner/outer metadata and extensions |
| Known-field validation | Malformed/null fields, stricter validation of every provided embedded body member, audience/priority bounds, and compact integral sizes including negative values |
| Recursive boundaries | Duplicate keys fail even in otherwise opaque extensions |

These are specification-derived tests, not claimed one-to-one mappings to AgentScope Java wrapper methods. The pinned reference uses MCP Java SDK 0.17.2 for wire types and serialization. Its wrapper tests mock SDK objects; in particular, `CallToolResult.isError` is a method result flag, not a JSON-RPC error envelope.

## Initialization payload tests

[McpInitializationSpec](McpInitializationSpec.scala) checks the explicitly selected 2025-06-18 revision, including the independent resource capability matrix. Resource capability and notification behavior follow the linked resource contract.

| Test | Coverage |
| --- | --- |
| Request and notification | Exact revision, empty client capabilities, optional implementation title, supplied ID validation, and identity-free initialized notification |
| Capability projection | Absent versus present tools, absent/false/true listChanged, retained extension fields, instructions, and object `_meta` |
| Checked result boundary | Required fields, malformed/null known optionals, and recursive duplicate rejection |
| Exact revision | Reject other revisions and malformed version fields without fallback |

## Resource protocol tests

[McpResourcesSpec](McpResourcesSpec.scala) groups independent request-shape, retained resource/template/read payload, and malformed-boundary contracts. [McpInitializationSpec](McpInitializationSpec.scala) adds a table of independent absent/false/true resource capability flags and malformed flag rejection.

The original HLD promises resource servers, while the pinned AgentScope Java client wrapper only exposes tool discovery/invocation. These resource method expectations come from the MCP resource specification; existing upstream embedded-resource conversion cases map separately to McpCallResultSpec and do not substitute for resource discovery/read tests.

## Tool request and list-result tests

[McpToolsSpec](McpToolsSpec.scala) checks 2025-06-18.

| Test | Coverage |
| --- | --- |
| Request builders | List cursor/metadata, omitted params, supplied ID limits, and absent versus empty call arguments |
| List projection | Ordered duplicate names, complete input/output schema ASTs, optional tool descriptions, untrusted hints, metadata, and extension fields |
| Known-field validation | Missing/null/malformed fields, limited schema shape checks, and retained external references without resolution |
| Recursive boundaries | Duplicate keys inside schemas and outgoing arguments/metadata, with valid nearby cases |

## Call-result tests

[McpCallResultSpec](McpCallResultSpec.scala) covers the 2025-06-18 result projection.

| Test | Coverage |
| --- | --- |
| Content variants | Ordered text, image, audio, resource links, and embedded text/blob/both bodies |
| Outcome and metadata | Tool errors remain results; absent/false isError normalization, structuredContent, annotations, and distinct inner/outer metadata and extensions |
| Known-field validation | Malformed/null fields, stricter validation of every provided embedded body member, audience/priority bounds, and compact integral sizes including negative values |
| Recursive boundaries | Duplicate keys fail even in otherwise opaque extensions |

URI, base64, and timestamp strings are structural data; no format validation, network access, core content conversion, or output-schema evaluation is exercised.

## Acceptance and next tests

Run `sbt "mcp / test"` from the repository root for offline and owned local-process/loopback contracts. `sbt verify` also checks formatting and fatal-warning compilation. These tests do not establish interoperability with every server, complete protocol conformance or durable delivery.

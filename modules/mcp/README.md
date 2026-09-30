# weisgnosis-agents-mcp

The implemented scope is a pure MCP JSON-RPC envelope boundary in [McpMessage](src/main/scala/com/weisgnosis/agents/mcp/protocol/McpMessage.scala). It models requests, notifications, successful responses, and remote protocol errors. The bounded envelope scope is independently reviewed and accepted. Pure [2025-06-18 initialization helpers](src/main/scala/com/weisgnosis/agents/mcp/protocol/McpInitialization.scala) are independently reviewed and accepted within their payload-only scope; runtime integration remains pending.

The checked codecs validate one complete JSON document, preserve integer identity within the signed 256-bit parser-compatible range without floating-point conversion, and reject duplicate keys and unknown envelope/error fields. Method-specific payload extensions are retained. This foundation does not implement an MCP connection, handshake, negotiated protocol version, or tool client.

| Package | Current scope |
| --- | --- |
| [protocol](src/main/scala/com/weisgnosis/agents/mcp/protocol/README.md) | Accepted envelope/initialization helpers and tool request/list/call-result projections |
| [transport](src/main/scala/com/weisgnosis/agents/mcp/transport/README.md) | Planned transport and connection ownership |
| [client](src/main/scala/com/weisgnosis/agents/mcp/client/README.md) | Planned version-specific lifecycle, discovery, calls, and Toolkit bridge |

[McpTools](src/main/scala/com/weisgnosis/agents/mcp/protocol/McpTools.scala) now implements 2025-06-18 list/call request builders and one-page tool-list parsing; the bounded request/list-result scope is independently reviewed and accepted. Declarations preserve order and duplicate names, schemas remain unevaluated ASTs, and remote annotation hints confer no local authority. [McpCallResult](src/main/scala/com/weisgnosis/agents/mcp/protocol/McpCallResult.scala) adds the accepted five content variants, typed success/error outcome, structuredContent, annotations, and metadata. Embedded text and blob are both retained when present; every provided body field must be a string. URI/base64/timestamp strings remain opaque data. Runtime invocation remains pending.

## Verification and acceptance

- [x] Five grouped [McpProtocolSpec](src/test/scala/com/weisgnosis/agents/mcp/protocol/McpProtocolSpec.scala) tests pass, including the reproduced integer-boundary regression.
- [x] Independent FP/ZIO and protocol-invariant reviews approved the bounded envelope scope with no unresolved findings.
- [x] Formatting and full repository verification passed with zero compiler warnings or failed tests. Exact scope, reviewers, reviewed source identities, and verification evidence are recorded in the [protocol acceptance](src/main/scala/com/weisgnosis/agents/mcp/protocol/README.md#verification-and-acceptance).

These tests are specification-derived envelope coverage. The pinned AgentScope Java reference delegates wire schemas and transport serialization to MCP Java SDK 0.17.2; its wrapper tests do not establish envelope codec parity.

- [x] Four additional [McpInitializationSpec](src/test/scala/com/weisgnosis/agents/mcp/protocol/McpInitializationSpec.scala) groups cover initialization payloads, covering the accepted initialization scope.
- [x] `path_review` and `current_docs` approved the exact initialization payload scope with no unresolved findings; the linked protocol acceptance records its reviewed identities and final verification.

- [x] Four [McpToolsSpec](src/test/scala/com/weisgnosis/agents/mcp/protocol/McpToolsSpec.scala) groups pass.
- [x] Both independent reviewers approved the exact tool payload scope with no unresolved findings; the linked protocol acceptance records reviewed identities and full verification.

- [x] Four [McpCallResultSpec](src/test/scala/com/weisgnosis/agents/mcp/protocol/McpCallResultSpec.scala) groups pass, bringing the MCP suite to seventeen groups. Both independent reviewers approved the bounded result projection; the linked protocol acceptance records the final identities and full verification.

## Remaining work

- [ ] Next: scoped client/transport lifecycle, request correlation, cancellation, and connection shutdown for the explicitly selected revision.
- [ ] Revision-specific client lifecycle. MCP 2026-07-28 does not use `initialize`; older initialization/negotiation behavior requires explicitly selected legacy support.
- [ ] Tool discovery, runtime invocation, core content conversion, output-schema evaluation, and Toolkit integration.

No complete support claim is made for MCP 2024-11-05 or any later revision. The newer [2026-07-28 specification](https://modelcontextprotocol.io/specification/2026-07-28/basic) contains method/version requirements outside this envelope slice.

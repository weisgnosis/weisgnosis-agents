# weisgnosis-agents-mcp

This module supplies checked MCP messages, selected 2025-06-18 payload helpers, bounded scoped request correlation and accepted connection ownership over an injected port. A direct-process stdio adapter, initialized client, bounded optional outbound cancellation, bounded discovery, manual Toolkit binding and bounded scoped manager are independently reviewed and verified. Bounded HTTP JSON/SSE body framing and the scoped POST-only HTTP adapter are also reviewed; broader HTTP listening, resumption and recovery remain unfinished.

| Package | Current scope |
| --- | --- |
| [protocol](src/main/scala/com/weisgnosis/agents/mcp/protocol/README.md) | Accepted checked envelopes, initialization helpers, tool requests, discovery pages and call results |
| [client](src/main/scala/com/weisgnosis/agents/mcp/client/README.md) | Request correlation, connection ownership, initialized 2025-06-18 client and bounded scoped manager with host-selected immutable Toolkit publication |
| [tool](src/main/scala/com/weisgnosis/agents/mcp/tool/README.md) | Explicit borrowed-client binding, host definitions and typed content/error projection |
| [transport](src/main/scala/com/weisgnosis/agents/mcp/transport/README.md) | Borrowed message port, strict stdio and HTTP JSON/SSE framing, scoped direct-process stdio and POST-only HTTP; independently reviewed and verified |

The protocol boundary rejects ambiguous variants, duplicate keys and malformed known fields. Numeric envelope identities use a signed 256-bit parser-compatible range. Payload extensions remain data; tool annotations confer no execution authority. Schemas, URI/base64 strings and timestamps are retained without evaluation or resource fetching.

The request registry allocates monotonic numeric IDs before sending, atomically settles replies and retains complete terminal causes. Each request requires its own short-lived scope. The connection composes that registry with bounded operations, a reader and a separate bounded server-response writer. Its transport belongs to the host and must outlive connection cleanup. Optional cancellation notifications use that writer, drop on a full queue and have no delivery acknowledgement. HTTP eligibility begins after validated response headers; legacy ports and stdio signal after successful send. Pre-header HTTP cancellation is deliberately ineligible.

Stdio starts host-selected argv without an implicit shell and redirects stderr to discard or inherit. One incoming consumer and serialized frame writes preserve framing; interrupted admitted writes terminate the transport, while cancellation waiting for write admission is harmless. Cleanup shares one outcome and terminates/reaps the direct child before closing streams. Environment and working directory are inherited without configuration overrides. Process-tree containment, graceful shutdown deadlines and reconnects are not implemented.

[McpClient.open](src/main/scala/com/weisgnosis/agents/mcp/client/McpClient.scala) sends initialize, validates the exact 2025-06-18 result, and sends notifications/initialized before publishing the client. The advertised tools capability is required for listToolsPage and callTool. A positive finite configured deadline (default 30 seconds) covers the complete initialization exchange and each list/call operation. Expiry or caller cancellation interrupts and joins owned cleanup; return can exceed the deadline while cleanup completes. Failed initialization closes the connection immediately. Remote protocol rejections retain code, message and explicit data presence in McpRemoteError; tool isError remains a valid call result. The client neither installs server instructions nor grants authority from annotations.

Explicit `discoverTools(maxPages, maxTools, meta)` traverses pages sequentially within positive limits, preserves ordered duplicates, detects cursor cycles and returns no partial catalogue on failure. Each page retains its joined deadline; page metadata is omitted, and the result is not an atomic server snapshot.

[McpTool.bind](src/main/scala/com/weisgnosis/agents/mcp/tool/McpTool.scala) borrows one initialized client and fixes its remote name. The host supplies ToolDef naming/schema/read-only/concurrency declarations. All five content variants and nested metadata are preserved; tool isError retains its payload, while Toolkit normalizes RPC failures to textual Error observations without code/data. This neither fetches resources nor grants authority from annotations.

POST-only HTTP borrows a host HTTP client, owns each response scope, handles JSON/SSE replies and session headers, and reserves independent request/control admission. Session expiry terminates the old transport without replay. The [transport contract](src/main/scala/com/weisgnosis/agents/mcp/transport/README.md#post-only-http-contract) defines exact limits, required host client behavior and excluded features. This additive API changes no existing client or stdio signature.

## Verification and acceptance

- [x] Current `scalafmtAll scalafmtSbt verify` passed in `mcp-cancellation-verify.log`, with zero compiler warnings or failures; the [tracker](../../docs/architecture/tracker.md#current-acceptance) owns current totals. `path_review` and `current_docs` approved the exact [cancellation/client](src/main/scala/com/weisgnosis/agents/mcp/client/README.md#outbound-cancellation-contract) and [transport](src/main/scala/com/weisgnosis/agents/mcp/transport/README.md#cancellation-publication-boundary) scope and identities.
- [x] Both independent reviewers approved bounded HTTP body framing and the scoped POST-only HTTP adapter, and full verification passed. The [transport acceptance](src/main/scala/com/weisgnosis/agents/mcp/transport/README.md#verification-and-acceptance) records exact identities and records the bounded POST-only HTTP acceptance and its remaining transport features.
- [x] `path_review` and `current_docs` approved scoped manager acquisition, atomic registration, immutable Toolkit snapshots and joined removal/closure. The [client contract](src/main/scala/com/weisgnosis/agents/mcp/client/README.md#scoped-manager-contract) owns exact identities and regression evidence.

- [x] `path_review` and `current_docs` approved manual binding with no findings, and full verification passed; the [tool acceptance](src/main/scala/com/weisgnosis/agents/mcp/tool/README.md#verification-and-acceptance) owns its scoped identities and evidence.

- [x] The [protocol acceptance](src/main/scala/com/weisgnosis/agents/mcp/protocol/README.md#verification-and-acceptance) records independent FP/ZIO and invariant approvals for the exact envelope and payload scope.
- [x] The [client acceptance](src/main/scala/com/weisgnosis/agents/mcp/client/README.md#verification-and-acceptance) records independent approvals for scoped request correlation.
- [x] Both reviewers approved the connection/port/test identities with no findings; the linked client acceptance records the exact scope and successful full verification.
- [x] Four framing and three real-process transport groups pass, covering byte boundaries, malformed input, scoped reaping, reader ownership and cancellation.
- [x] Independent framing/stdio reviews approved the bounded transport scope; the [transport acceptance](src/main/scala/com/weisgnosis/agents/mcp/transport/README.md) records exact identities and evidence.
- [x] `path_review` and `current_docs` approved the final client scope with no findings. Six client groups cover ordered initialization, capability checks, remote failures, bounded discovery and joined deadlines/cancellation; the real-process fixture also initializes, lists and invokes a tool.

These are specification-derived protocol and ZIO ownership tests. AgentScope Java delegates its wire machinery to MCP Java SDK 0.17.2. Passing these tests does not close whole upstream client-wrapper methods or establish network interoperability.

## Remaining work

- [x] Implement scoped POST-only HTTP for the selected 2025-06-18 contract.
- [ ] Complete separately specified GET/resumption, session DELETE/reinitialization and external-server interoperability.
- [x] Enqueue bounded optional outbound cancellation for interrupted issued requests, excluding initialize and reply/terminal winners.
- [ ] Next: scoped opt-in HTTP GET listening; resumption and session recovery remain separate.
- [ ] Define presets, tool groups, ambient metadata propagation and schema transformation separately; manager selection retains explicit host authority.
- [ ] Implement provider multimodal projection; core typed conversion is available, while caching, notification invalidation and reconnect remain separate.
- [ ] Evaluate output schemas where required by the selected client contract.

The initialized client and its payload helpers explicitly select 2025-06-18. They do not support the different [2026-07-28 lifecycle](https://modelcontextprotocol.io/specification/2026-07-28/basic), which has no initialize exchange. No complete MCP revision support is claimed.

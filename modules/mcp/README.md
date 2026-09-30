# weisgnosis-agents-mcp

This module supplies checked MCP messages, selected 2025-06-18 payload helpers, bounded scoped request correlation and accepted connection ownership over an injected port. A direct-process stdio adapter with bounded framing is independently reviewed and verified. HTTP transport, an initialized client and Toolkit integration remain unfinished.

| Package | Current scope |
| --- | --- |
| [protocol](src/main/scala/com/weisgnosis/agents/mcp/protocol/README.md) | Accepted checked envelopes, initialization helpers, tool requests, discovery pages and call results |
| [client](src/main/scala/com/weisgnosis/agents/mcp/client/README.md) | Accepted request correlation and connection ownership over an injected transport |
| [transport](src/main/scala/com/weisgnosis/agents/mcp/transport/README.md) | Borrowed message port, strict UTF-8/LF framing and scoped direct-process stdio; independently reviewed and verified |

The protocol boundary rejects ambiguous variants, duplicate keys and malformed known fields. Numeric envelope identities use a signed 256-bit parser-compatible range. Payload extensions remain data; tool annotations confer no execution authority. Schemas, URI/base64 strings and timestamps are retained without evaluation or resource fetching.

The request registry allocates monotonic numeric IDs before sending, atomically settles replies and retains complete terminal causes. Each request requires its own short-lived scope. The connection composes that registry with bounded operations, a reader and a separate bounded server-response writer. Its transport belongs to the host and must outlive connection cleanup.

Stdio starts host-selected argv without an implicit shell and redirects stderr to discard or inherit. One incoming consumer and serialized frame writes preserve framing; interrupted admitted writes terminate the transport, while cancellation waiting for write admission is harmless. Cleanup shares one outcome and terminates/reaps the direct child before closing streams. Environment and working directory are inherited without configuration overrides. Process-tree containment, graceful shutdown deadlines and reconnects are not implemented.

## Verification and acceptance

- [x] The [protocol acceptance](src/main/scala/com/weisgnosis/agents/mcp/protocol/README.md#verification-and-acceptance) records independent FP/ZIO and invariant approvals for the exact envelope and payload scope.
- [x] The [client acceptance](src/main/scala/com/weisgnosis/agents/mcp/client/README.md#verification-and-acceptance) records independent approvals for scoped request correlation.
- [x] Both reviewers approved the connection/port/test identities with no findings; the linked client acceptance records the exact scope and successful full verification.
- [x] Four framing and three real-process transport groups pass, covering byte boundaries, malformed input, scoped reaping, reader ownership and cancellation.
- [x] Independent framing/stdio reviews and full verification passed: 561 core / 651 harness / 35 MCP tests (1,247 total), zero compiler warnings or failures, in `mcp-stdio-verify.log`. The [transport acceptance](src/main/scala/com/weisgnosis/agents/mcp/transport/README.md) records current evidence.

These are specification-derived protocol and ZIO ownership tests. AgentScope Java delegates its wire machinery to MCP Java SDK 0.17.2. Passing these tests does not close whole upstream client-wrapper methods or establish network interoperability.

## Remaining work

- [ ] Next: implement explicitly selected 2025-06-18 initialization, capability handling and discovery over the connection.
- [ ] Define deadlines, cancellation notifications and transport-specific failure recovery.
- [ ] Implement HTTP transport separately with an explicit revision contract.
- [ ] Implement discovery pagination, invocation and core content conversion, followed by Toolkit integration with explicit naming and authority policy.
- [ ] Evaluate output schemas where required by the selected client contract.

Initialization helpers explicitly select 2025-06-18. They do not support the different [2026-07-28 lifecycle](https://modelcontextprotocol.io/specification/2026-07-28/basic), which has no initialize exchange. No complete MCP revision support is claimed.

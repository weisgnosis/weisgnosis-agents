# com.weisgnosis.agents.mcp.client

[McpRequests](McpRequests.scala) implements scoped, bounded request correlation. It owns no transport, send callback, or fibers. The [protocol package](../protocol/README.md) supplies checked envelopes and selected 2025-06-18 payload helpers; there is still no operational MCP connection, handshake, discovery client, or Toolkit bridge.

## Request ownership contract

- `make(capacity = 64)` acquires an owner-scoped registry. Capacity must be positive. It bounds pending entries, not retained payload bytes or finalizers in caller scopes.
- `register` requires a fresh request scope around registration, checked request construction, sending, and awaiting. It returns a `PendingRequest` with `id` and `await`; the ID is allocated before any send. Numeric IDs increase from 1 through `Long.MaxValue`, are never reused, and fail on exhaustion rather than wrapping. Capacity, closed admission, and exhaustion produce local `AgentError.McpError` failures.
- `complete(reply)` expects an already validated `Success | Failure` from the caller's protocol boundary. Removal and promise completion are one STM transaction. Remote JSON-RPC failures remain reply data. `Delivered` means that completion won; `UnknownId` covers unmatched, late, duplicate, and abandoned identities without tombstones. An error without an ID yields `MissingId` while open. Closed admission takes precedence and yields `Closed`, including for a missing ID.
- Request-scope closure abandons an unfinished entry and settles its waiters. A normal lease close yields a local `McpError`; a failed lease preserves domain failures and the complete defect/interruption/composition structure, mapping only foreign typed failure leaves to `McpError`. Interrupting `await` atomically abandons the slot and propagates interruption. An already completed reply survives later lease closure.
- `failAll(cause)` atomically closes admission and settles all remaining requests. Nonempty causes are preserved in full; an empty cause becomes a local registry-closed `McpError`. First completion or terminal failure wins. `close`, including automatic owner-scope cleanup, uses that local closed error and does not forward the owner scope's exit.

The host must keep each request lease short-lived. This primitive neither sends cancellation notifications nor supplies deadlines, retries, transport cleanup, or method/version lifecycle enforcement. Authentication and server trust remain explicit host responsibilities.

## Verification and acceptance

Accepted scope: bounded scoped request correlation, monotonic identity allocation, atomic completion/abandonment, cancellation and terminal cause preservation, and the additive `AgentError.McpError` boundary described above. Transport, sending, deadlines, method/version lifecycle, and Toolkit integration remain excluded.

- [x] Four grouped [McpRequestsSpec](../../../../../../../test/scala/com/weisgnosis/agents/mcp/client/McpRequestsSpec.scala) tests pass for correlation, bounded leases, interruption, and terminal ownership/cause preservation.
- [x] `path_review` independently approved FP/ZIO correctness and `current_docs` independently approved invariants for the final identities below, including expanded cause-preservation tests, with no unresolved findings.
- [x] `scalafmtAll`, `scalafmtSbt`, and `verify` passed with zero compiler warnings or failed tests: core 561, harness 651, MCP 21; total 1,233. Evidence: `mcp-requests-verify.log`.

| Reviewed file | Git blob identity |
| --- | --- |
| [McpRequests.scala](McpRequests.scala) | `70c693b3b10b6c3b1bde45e8809725eca73e26a7` |
| [McpRequestsSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/mcp/client/McpRequestsSpec.scala) | `9d2a51529a8cf151637a05346083e7b35bf8eb0b` |
| [AgentError.scala](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/error/AgentError.scala) | `bc673debd766d15bca237f7b56bd085c2dfefa80` |

## Remaining work

- [ ] Next: integrate actual scoped client/transport ownership, request sending, cancellation, and shutdown for an explicitly selected revision.
- [ ] Implement capability discovery and tool invocation under that revision's lifecycle. MCP 2026-07-28 does not use `initialize`; older initialization and negotiation require explicit legacy support.
- [ ] Bridge discovered tools into Toolkit with explicit metadata, naming, and authority policy.
- [ ] Add transport/lifecycle and ReAct integration tests, then independently review those implementations.

These request-registry tests establish local concurrency and ownership contracts. AgentScope Java delegates wire/request machinery to MCP Java SDK 0.17.2; its wrapper `listTools`, `callTool`, and `close` methods remain future behavioral mappings. No complete MCP revision or whole-wrapper parity is claimed.

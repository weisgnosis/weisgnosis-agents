# com.weisgnosis.agents.mcp.client

[McpRequests](McpRequests.scala) implements scoped, bounded request correlation. It owns no transport, send callback, or fibers. [McpConnection](McpConnection.scala) composes it with scoped RPC operations over an injected [McpTransport](../transport/README.md). The [protocol package](../protocol/README.md) supplies checked envelopes and selected 2025-06-18 payload helpers. A [stdio adapter](../transport/McpStdioTransport.scala) is independently reviewed and verified; initialization, discovery and Toolkit integration remain unfinished.

## Request ownership contract

- `make(capacity = 64)` acquires an owner-scoped registry. Capacity must be positive. It bounds pending entries, not retained payload bytes or finalizers in caller scopes.
- `register` requires a fresh request scope around registration, checked request construction, sending, and awaiting. It returns a `PendingRequest` with `id` and `await`; the ID is allocated before any send. Numeric IDs increase from 1 through `Long.MaxValue`, are never reused, and fail on exhaustion rather than wrapping. Capacity, closed admission, and exhaustion produce local `AgentError.McpError` failures.
- `complete(reply)` expects an already validated `Success | Failure` from the caller's protocol boundary. Removal and promise completion are one STM transaction. Remote JSON-RPC failures remain reply data. `Delivered` means that completion won; `UnknownId` covers unmatched, late, duplicate, and abandoned identities without tombstones. An error without an ID yields `MissingId` while open. Closed admission takes precedence and yields `Closed`, including for a missing ID.
- Request-scope closure abandons an unfinished entry and settles its waiters. A normal lease close yields a local `McpError`; a failed lease preserves domain failures and the complete defect/interruption/composition structure, mapping only foreign typed failure leaves to `McpError`. Interrupting `await` atomically abandons the slot and propagates interruption. An already completed reply survives later lease closure.
- `failAll(cause)` atomically closes admission and settles all remaining requests. Nonempty causes are preserved in full; an empty cause becomes a local registry-closed `McpError`. First completion or terminal failure wins. `close`, including automatic owner-scope cleanup, uses that local closed error and does not forward the owner scope's exit.

The host must keep each request lease short-lived. This primitive neither sends cancellation notifications nor supplies deadlines, retries, transport cleanup, or method/version lifecycle enforcement. Authentication and server trust remain explicit host responsibilities.

## Connection ownership contract

- `make(transport, capacity = 64, controlCapacity = 16)` registers cleanup in the caller's scope before starting the reader and control writer. Both bounds must be positive. A closed acquiring scope starts no transport activity. The host acquires the borrowed transport first and releases it after connection cleanup.
- `request(method, params)` registers a runtime ID before checked sending and awaiting. `notify(notification)` validates and sends without a request ID. Both occupy bounded operation slots through send cleanup; an early reply cannot free a slot while sending is still running. Every operation owns a fresh scope and supervised action. Cancellation joins that action and retains failure/cleanup causes.
- Remote `Failure` replies remain data. Unknown, duplicate and late IDs are ignored. Local send failure ends that operation; the transport must terminate incoming after fatal or framing-ambiguous writes. No retry or partial-write recovery is implied.
- EOF, incoming failure, an ID-less remote error, control-write failure or control-queue overflow publishes the first terminal cause, closes admission and signals the reader/writer to stop. Owned operation workers observe termination independently of their callers and join send cleanup. First observed operation completion versus termination wins; independent losing failures survive, while a terminal cause already delivered by the registry is not duplicated.
- Server `ping` receives the same ID with an empty object result; other server requests receive `-32601`. A separate bounded nonblocking queue and one writer keep control sending out of reply correlation. Incoming notifications are ignored, including cancellation: server-originated request IDs cannot cancel client-originated requests merely by matching their values.
- `close` and owner-scope cleanup join operations, reader and writer before the borrowed transport can be released. Concurrent/repeated closers share one completed outcome. Close reports defects in retained operation/service exits, including operational reader defects already observed by a request; typed operational failures remain request failures. Sends, incoming stream code and their finalizers must not close their own connection, because closure joins them.

These bounds count operations and queued messages, not bytes. This is a raw RPC foundation: no initialization ordering, capability enforcement, deadlines, progress reporting, cancellation notifications, reconnects or subscriptions are provided. The separate stdio adapter supplies bounded wire framing and direct-process ownership.

## Verification and acceptance

Accepted registry scope: bounded correlation, monotonic IDs, atomic settlement/abandonment, complete causes and the additive AgentError.McpError boundary. Accepted connection scope: the borrowed port, bounded scoped RPC ownership, terminal dispatch and joined cleanup described above. Network transports and version-specific lifecycle remain excluded.

- [x] Four grouped [McpRequestsSpec](../../../../../../../test/scala/com/weisgnosis/agents/mcp/client/McpRequestsSpec.scala) tests establish registry contracts; `path_review` and `current_docs` approved their recorded identities with no findings.
- [x] Seven grouped [McpConnectionSpec](../../../../../../../test/scala/com/weisgnosis/agents/mcp/client/McpConnectionSpec.scala) tests pass for immediate/out-of-order replies, cancellation, complete causes, closed-parent startup, control dispatch, concurrent closure and terminal errors.
- [x] Regressions failed against unchanged connection blob `1217d4467307297381bed0e01804ca898a85c8d5`: terminal cause duplication and a ping sent after terminal failure. `mcp-terminal-dispatch-red.log` records the assertion failures. Closed-parent startup ordering was corrected by inspection and covered by a contract test; no reproduced startup failure is claimed.
- [x] `path_review` independently approved FP/ZIO correctness and `current_docs` independently approved invariants for the final connection/port/spec identities below, with no unresolved findings.
- [x] `scalafmtAll`, `scalafmtSbt`, and `verify` passed with zero compiler warnings or failed tests: core 561, harness 651, MCP 28; total 1,240. Evidence: `mcp-connection-verify.log`.

| Reviewed file | Git blob identity |
| --- | --- |
| [McpRequests.scala](McpRequests.scala) | `70c693b3b10b6c3b1bde45e8809725eca73e26a7` |
| [McpRequestsSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/mcp/client/McpRequestsSpec.scala) | `9d2a51529a8cf151637a05346083e7b35bf8eb0b` |
| [AgentError.scala](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/error/AgentError.scala) | `bc673debd766d15bca237f7b56bd085c2dfefa80` |
| [McpConnection.scala](McpConnection.scala) | `2ae27ca85a6b1bc5eaaee28d8825806d9c1e5470` |
| [McpTransport.scala](../transport/McpTransport.scala) | `acf9f4e3fc8d67fc7b66537b223fdacfffca0099` |
| [McpConnectionSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/mcp/client/McpConnectionSpec.scala) | `4e9616939a74fa38cd227f0bc700086492f79215` |

## Remaining work

- [ ] Next: explicitly selected 2025-06-18 initialization, capability handling and discovery over the connection.
- [ ] Define request deadlines; implement HTTP transport separately.
- [ ] Define cancellation notification and transport-specific recovery policy.
- [ ] Implement discovery pagination and tool invocation, then bridge into Toolkit with explicit metadata, naming and authority policy.
- [ ] Add revision-specific client/ReAct integration tests; the separate stdio contract records accepted real-process coverage.

These tests establish local concurrency and ownership contracts using controlled message ports. AgentScope Java delegates wire/request machinery to MCP Java SDK 0.17.2; wrapper listTools, callTool and close behaviors remain separate integration mappings. No complete MCP revision or whole-wrapper parity is claimed. MCP 2026-07-28 has a different lifecycle without initialize; it is not implemented by the selected older payload helpers.

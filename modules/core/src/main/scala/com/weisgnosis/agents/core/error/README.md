# Typed domain failures

[AgentError.scala](AgentError.scala) defines the sealed domain failure hierarchy. Effects expose these failures in the ZIO error channel. Tool execution failures become model observations at the toolkit boundary; cancellation and defects remain in `Cause`.

| Boundary | Contract and runtime evidence |
| --- | --- |
| Configuration and message validation | InvalidConfiguration and InvalidMessage reject invalid runtime configuration and dynamic role/content combinations; see the [model contract](../model/README.md). |
| Model and host callbacks | ModelError identifies provider failure. TextTransformationError, ReasoningHookError, HistoryPreparationError, ModelInputError and CompletionObservationError keep host failures separate from provider-native fallback; see the [client](../client/README.md) and [agent](../agent/README.md) contracts. |
| Acting-phase termination | ExecutionStopped(reason) reports a host processor stop from structured execution, before payload decoding; it is not a ModelError and cannot trigger native provider fallback. See [acting-stop decisions](../agent/README.md#acting-stop-decisions). |
| Storage | MemoryStorageError and PlanStorageError cover backend I/O, ownership and closed-handle failures. Path rejection remains PermissionDenied; finalizer failures remain observable defects. See [memory](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md) and [plan](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md). |
| Tools, permissions and runtime limits | ToolExecutionError, ToolNotFoundError, PermissionDenied, ContextLengthExceeded, MaxIterationsExceeded and TimeoutError retain their dedicated diagnostics. Interrupted is domain data, not a substitute for fiber interruption. |
| Descendant lineage | SpawnDepthExceeded rejects excessive depth before child construction or identity allocation; see the [subagent contract](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md). |
| MCP session expiry | McpSessionExpired() identifies HTTP 404 for a captured session. Its fixed diagnostic retains the previous text, contains no session token, and its individual derived codec writes an empty object `{}`. It is distinct from a remote JSON-RPC McpRemoteError and from a tool isError result. See the [HTTP contract](../../../../../../../../../mcp/src/main/scala/com/weisgnosis/agents/mcp/transport/README.md#http-contract). |
| MCP request ownership | McpError identifies local correlation capacity, identity exhaustion, closure and abandoned-request failures. It is distinct from malformed-wire DeserializationError/InvalidMessage and McpRemoteError, which preserves remote JSON-RPC code/message/data. Tagged McpErrorData distinguishes absent data from explicit JSON null through its domain codec. See the [MCP client contract](../../../../../../../../../mcp/src/main/scala/com/weisgnosis/agents/mcp/client/README.md). |

Each leaf exposes a message and its individual derived JSON codec. The hierarchy does not provide a single shared wire codec. Adding a leaf requires exhaustive AgentError matches to handle the new case. Migration: handle McpSessionExpired explicitly where recovery decisions previously inspected McpError text; the hierarchy still has no shared tagged wire codec. Bound tools may normalize this failure into an Error observation instead of exposing a typed leaf to the model-facing caller.

## Current review acceptance

- [x] `registry_contract` approved FP/ZIO correctness and `current_docs` approved invariants for McpSessionExpired and its codec/diagnostic integration, with no findings. Current changed error source/spec identities are recorded in [acting-stop decisions](../agent/README.md#acting-stop-decisions); expiry semantics retain their reviewed contract.
- [x] Scoped `mcp-session-expiry-verify.log` verification passed with zero compiler warnings or failures. Five focused core groups passed in 239 ms; the HTTP/manager focused run passed 24 groups in 3.846 seconds. The linked MCP contracts own their reviewed integration identities.
- [x] Existing failure boundaries retain their originating package acceptance and integration evidence; the links above describe current behavior instead of repeating closed review history.
- [ ] Complete exhaustive error-hierarchy and applicable upstream scenario mapping. Neither this additive review nor passing suites establishes exhaustive parity.

The [error spec](../../../../../../../test/scala/com/weisgnosis/agents/core/error/AgentErrorSpec.scala) checks selected codec/diagnostic contracts. Runtime suites verify callback provenance, storage ownership, tool normalization and MCP cause propagation. The current [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance) records required build verification.

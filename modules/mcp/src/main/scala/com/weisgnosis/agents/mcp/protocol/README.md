# com.weisgnosis.agents.mcp.protocol

[McpMessage](McpMessage.scala) implements the common JSON-RPC envelope shape used by MCP. The API is pure and has no transport, generated identifiers, method dispatch, or negotiated-version state. The bounded envelope scope is accepted; method contracts and runtime integration remain pending.

## Types and wire contract

| Type or case | Contract |
| --- | --- |
| `McpRequestId.Text` / `Number` | String or signed 256-bit integer in `[-2^255, 2^255 - 1]`; strings and numbers remain distinct. Empty strings and negative integers are accepted. Mathematically integral decimals/exponents normalize to `BigInt` within that range; fractions and out-of-range values are rejected. |
| `McpMessage.Request` | Required non-null ID and string method; optional object params. |
| `McpMessage.Notification` | String method and optional object params; no ID. |
| `McpMessage.Success` | Required non-null ID and object result. |
| `McpMessage.Failure` | Optional ID and required `McpRpcError`; omitted ID is allowed, explicit null ID is rejected. |
| `McpRpcError` | Signed 256-bit integer code, string message, and optional arbitrary JSON data. `None` omits data; `Some(Json.Null)` preserves explicit null. |

The numeric limit is an explicit implementation boundary matching the JSON parser, not a claim that MCP itself limits integers to this range. Incoming nonzero numbers are checked using decimal precision and scale before integer expansion, then checked against the exact signed range. Zero bypasses expansion. Outbound IDs and error codes are checked before decimal conversion, so checked encodings remain within the same domain. Payload sizes and method-level numeric requirements remain outside this limit.

Every encoded envelope has `jsonrpc` equal to `"2.0"`; decoding requires that exact value. Missing params and empty object params remain distinct. Explicit null or non-object params/results are rejected. Success and error are separate ADT cases, and mixed method/result/error envelopes are rejected. Arrays and batches are outside this boundary.

## Checked codec API

- `decode(String)` reads one complete JSON document and returns `Either[AgentError.DeserializationError, McpMessage]`.
- `fromJson(Json)` validates an existing AST and returns the same inbound error type.
- `encode(McpMessage)` returns `Either[AgentError.InvalidMessage, String]`.
- `toJson(McpMessage)` returns `Either[AgentError.InvalidMessage, Json.Obj]`.

These are explicit checked codecs; no implicit `JsonCodec` or infallible encoder is exposed. Manual AST conversion is required for the untagged envelope shape, constant protocol marker, strict field validation, and absent-versus-null error data. Constructors may contain unvalidated AST payloads, so outbound callers must use the checked conversions.

All four conversions reject duplicate object keys recursively, including inside payloads. `decode` also rejects trailing non-whitespace input. This implementation deliberately rejects unknown top-level envelope fields and unknown fields inside the error object. Extension fields inside params, result, and error data are preserved. Remote protocol errors remain data, separate from local parsing or validation failures.

The envelope does not validate method names, payload schemas, `_meta`, or revision-specific result requirements such as `resultType`. The [2025-11-25](https://modelcontextprotocol.io/specification/2025-11-25/basic) and [2026-07-28](https://modelcontextprotocol.io/specification/2026-07-28/basic) specifications provide the envelope references; no handshake or complete compatibility with either revision is claimed.

## Verification and acceptance

Accepted scope: the four message cases, checked wire/AST conversion, strict envelope/error fields, recursive duplicate rejection, complete-document decoding, optional null error data, and guarded signed 256-bit IDs/error codes. This acceptance excludes method/version contracts, transport, correlation, lifecycle, and tool adapters.

- [x] Five grouped [McpProtocolSpec](../../../../../../../test/scala/com/weisgnosis/agents/mcp/protocol/McpProtocolSpec.scala) tests pass for integer bounds, identities, notifications, responses, and strict boundaries.
- [x] `path_review` independently approved FP/ZIO correctness and `current_docs` independently approved protocol invariants for the final source/test identities below, with no unresolved findings.
- [x] `scalafmtAll`, `scalafmtSbt`, and `verify` passed with zero compiler warnings or failed tests: core 561, harness 651, MCP 5; total 1,217. Evidence: `mcp-envelope-verify.log`.

| Reviewed file | Git blob identity |
| --- | --- |
| [McpMessage.scala](McpMessage.scala) | `43387554aa5e8d88708180cde4b45e5fbf3b6858` |
| [McpProtocolSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/mcp/protocol/McpProtocolSpec.scala) | `22b9640fee4539891d6bfc6077b693a1623cd47c` |

## Remaining work

- [ ] Next: version-specific method and capability contracts. MCP 2026-07-28 does not use `initialize`; initialization and negotiation for older revisions require explicitly selected legacy support.
- [ ] Tool-listing and tool-call schemas, content, and result semantics.
- [ ] Method/version validation and integration with the lifecycle of each explicitly supported revision.

This is new specification-derived codec coverage. AgentScope Java uses SDK `McpSchema` objects and delegates serialization to MCP Java SDK 0.17.2; wrapper discovery, calls, cleanup, and explicitly supported legacy initialization remain separate future mappings.

# com.weisgnosis.agents.mcp.protocol

[McpMessage](McpMessage.scala) implements the common JSON-RPC envelope shape used by MCP. The API is pure and has no transport, generated identifiers, method dispatch, or negotiated-version state. The bounded envelope scope is accepted. [McpInitialization](McpInitialization.scala) adds pure 2025-06-18 initialization helpers accepted within the documented payload scope; selected runtime integration is accepted separately in the [client contract](../client/README.md).

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

## 2025-06-18 initialization helpers

[McpInitialization](McpInitialization.scala) implements payload construction and checked result projection for exactly `2025-06-18`, following that revision's [lifecycle specification](https://modelcontextprotocol.io/specification/2025-06-18/basic/lifecycle). This payload-only scope is independently reviewed and accepted as recorded below.

- `protocolVersion` is the fixed revision string. `request(id, clientInfo)` returns `Either[AgentError.InvalidMessage, McpMessage.Request]`, validates the supplied ID through the envelope boundary, and advertises empty client capabilities.
- `initialized` is the constant `notifications/initialized` notification with no identity or params.
- `readResult(Json.Obj)` returns `Either[AgentError.DeserializationError, McpInitializationResult]`. It accepts only the exact selected version, required implementation name/version strings, and a required capabilities object. Known optional fields reject explicit JSON null and malformed types; recursive duplicate keys are rejected before projection.
- `McpImplementation` has name/version and optional title, with a derived wire-data codec. The checked result reader validates optional title itself. The result has optional instructions and object `_meta`; these remain remote data, with no prompt or authority installation.
- Server `tools` and its `listChanged` flag preserve absence separately from a present object and explicit false. Unknown server/tool capability fields remain in `extensions`, excluding the separately typed `tools`/`listChanged` fields. Unknown outer result and serverInfo fields are ignored by this projection. No implicit result codec claims lossless round-trip behavior.

These helpers allocate no identities and implement no runtime, correlation, ordering enforcement, handshake, or negotiated-version state. MCP 2026-07-28 has no `initialize` method and is not accepted by this revision-specific result reader. Unsupported revisions fail without fallback.

## 2025-06-18 tool requests and list results

[McpTools](McpTools.scala) adds checked request builders and a one-page list-result parser for the same 2025-06-18 revision. The bounded request/list-result scope is independently reviewed and accepted as recorded below.

- `listRequest(id, cursor, meta)` constructs `tools/list`; it omits params when both options are absent. `callRequest(id, name, arguments, meta)` constructs `tools/call` and preserves absent arguments separately from an empty object. Both return `Either[AgentError.InvalidMessage, McpMessage.Request]` and reuse checked envelope validation for IDs and recursive duplicate keys.
- `readListResult(Json.Obj)` returns `Either[AgentError.DeserializationError, McpToolsPage]`. Required tools stay in server order, including duplicate names. Optional nextCursor and object `_meta` are retained; unknown page fields remain in extensions. There is no pagination loop, deduplication, or local registration.
- Each `McpToolDefinition` retains name, complete inputSchema, optional title/description/outputSchema/annotations/object `_meta`, and unknown extension fields. Input and output schemas require root `type: "object"`; optional properties must be an object and required must be an array of strings. These limited structural checks do not evaluate JSON Schema, resolve references, or validate tool arguments. Definitions, references, and other schema keywords remain in the original AST.
- `McpToolAnnotations` retains optional title and readOnlyHint/destructiveHint/idempotentHint/openWorldHint booleans plus unknown extensions. Absence is distinct from false. These are untrusted remote hints, never local ToolDef flags, permissions, or execution guarantees.

Known optional fields reject explicit null or malformed types. Duplicate keys are rejected throughout incoming schemas/extensions and outgoing arguments/metadata. Extension objects exclude the corresponding typed fields. No implicit codec bypasses checked parsing. Call-result projection is described below; the [initialized client](../client/README.md) executes capability-checked calls, and [manual tool binding](../tool/README.md) preserves host authority and typed observations.

## 2025-06-18 call results

[McpCallResult.read](McpCallResult.scala) returns `Either[AgentError.DeserializationError, McpCallResult]` for the selected revision. This pure checked projection rejects recursive duplicate keys before parsing and exposes no implicit result codec.

- Required content is an ordered array, which may be empty. `McpContentBlock` covers text, image, audio, resource links, and embedded resources; unknown content types fail. Outer blocks retain optional annotations, object `_meta`, and extensions excluding typed fields.
- Absent or false `isError` becomes `McpCallOutcome.Success`; true becomes `Error`. A tool failure remains a parsed result, separate from an envelope error or local parsing failure. Normalizing absent and false is deliberate, so this projection does not claim full wire round-trip behavior. Optional structuredContent and result `_meta` must be objects.
- Embedded resources retain URI, optional MIME type and object `_meta`, inner extensions, and text, blob, or both through `McpResourceBody.TextAndBlob`. Every provided body member must be a string. This is an explicit stricter local policy: valid text with a null blob is rejected even though the specification's `anyOf` can accept the text branch. Inner and outer metadata/extensions remain separate.
- Resource-link size must be mathematically integral, including negative values. It stays a compact `BigDecimal`; parsing does not expand exponent notation into `BigInt`. Annotation audience accepts ordered user/assistant values without deduplication, priority must be numeric within `[0, 1]`, and lastModified must be a string.

Known optional fields reject explicit null or malformed types. URI, base64 data, MIME type, and timestamp strings receive structural checks only: there is no format validation, decoding, fetching, or authority grant. Core content conversion, output-schema evaluation, execution, and transport remain outside this projection.

## Verification and acceptance

Accepted scope: the four message cases, checked wire/AST conversion, strict envelope/error fields, recursive duplicate rejection, complete-document decoding, optional null error data, and guarded signed 256-bit IDs/error codes. The accepted initialization scope is limited to the fixed 2025-06-18 request, initialized notification, implementation identity, and checked result/capability projection described above. The accepted tool scope adds 2025-06-18 list/call request construction, one-page ordered list parsing, limited schema-shape checks, and untrusted annotations/extensions. The accepted call-result scope adds the five content variants, embedded text/blob preservation, typed success/error outcome, structuredContent, annotations, metadata, and extensions described above. Other method/version contracts, format validation, core content conversion, output-schema evaluation, transport, correlation, runtime lifecycle/ordering, and tool adapters remain excluded.

- [x] Five grouped [McpProtocolSpec](../../../../../../../test/scala/com/weisgnosis/agents/mcp/protocol/McpProtocolSpec.scala) tests pass for integer bounds, identities, notifications, responses, and strict boundaries.
- [x] `path_review` independently approved FP/ZIO correctness and `current_docs` independently approved protocol invariants for the final source/test identities below, with no unresolved findings.
- [x] `scalafmtAll`, `scalafmtSbt`, and `verify` passed with zero compiler warnings or failed tests: core 561, harness 651, MCP 17; total 1,229. Evidence: `mcp-call-result-verify.log`.

| Reviewed file | Git blob identity |
| --- | --- |
| [McpMessage.scala](McpMessage.scala) | `43387554aa5e8d88708180cde4b45e5fbf3b6858` |
| [McpProtocolSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/mcp/protocol/McpProtocolSpec.scala) | `22b9640fee4539891d6bfc6077b693a1623cd47c` |
| [McpInitialization.scala](McpInitialization.scala) | `ccfe85505e6fce523e255bb0b9cc4619e4af477e` |
| [McpInitializationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/mcp/protocol/McpInitializationSpec.scala) | `a2937c98c7ddd00c94afca7f50925237f3d9fe6a` |
| [McpTools.scala](McpTools.scala) | `91819dd4870965c2f335e6f254ba240374beaa74` |
| [McpToolsSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/mcp/protocol/McpToolsSpec.scala) | `e4aca1b1c6beb9ace6ae91a7cd97d7e647f10d87` |
| [McpCallResult.scala](McpCallResult.scala) | `5a3765724d731816f0728845ba9a58edf855e175` |
| [McpCallResultSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/mcp/protocol/McpCallResultSpec.scala) | `a39d1d929ae233f45e2ebed661f451654f1b5db2` |

- [x] Four grouped [McpInitializationSpec](../../../../../../../test/scala/com/weisgnosis/agents/mcp/protocol/McpInitializationSpec.scala) tests pass for request/notification shape, capability projection, malformed fields, and exact revision handling.

- [x] Four grouped [McpToolsSpec](../../../../../../../test/scala/com/weisgnosis/agents/mcp/protocol/McpToolsSpec.scala) tests pass for request shape, retained definitions, malformed known fields, and recursive duplicate rejection.

- [x] Four grouped [McpCallResultSpec](../../../../../../../test/scala/com/weisgnosis/agents/mcp/protocol/McpCallResultSpec.scala) tests pass for content variants, outcome/metadata projection, known-field validation, and recursive duplicate rejection.

## Remaining work

- [x] Selected-revision client initialization, capability checks, list/call and joined deadlines are accepted in the [client contract](../client/README.md).
- [x] Bounded manager registration/removal has separate [client acceptance](../client/README.md#scoped-manager-contract); manual Toolkit binding retains its separate tool-package contract.
- [ ] Output-schema evaluation and provider multimodal projection remain separate from manual binding and core content conversion.
- [ ] Method/version validation and integration with the lifecycle of each explicitly supported revision.

This is new specification-derived codec coverage. AgentScope Java uses SDK `McpSchema` objects and delegates serialization to MCP Java SDK 0.17.2; wrapper discovery, calls, cleanup, and explicitly supported legacy initialization retain separate bounded mappings and exclusions in the parity audit.

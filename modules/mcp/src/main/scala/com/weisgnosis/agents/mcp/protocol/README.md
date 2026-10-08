# com.weisgnosis.agents.mcp.protocol

The current [supported server revision agreement](../transport/README.md#supported-server-revision-agreement) adds opt-in checked revision membership, one scoped negotiation owner, atomic correlated initialization and immediate framing/header transitions. Exact-version defaults remain; legacy POST additionally carries the revision header. This does not establish complete original-plan parity.

[McpMessage](McpMessage.scala) implements the common JSON-RPC envelope shape used by MCP. The API is pure and has no transport, generated identifiers, method dispatch, or negotiated-version state. [McpInitialization](McpInitialization.scala) supplies pure helpers with a default 2025-06-18 profile. Explicit older profiles are part of the [legacy/revision contract](../transport/README.md#legacy-http-sse-and-explicit-revision-selection). The current resource capability extension is governed by the [resource client contract](../client/README.md#resource-client-contract).

[McpPacket](McpPacket.scala) adds bounded selected-March batch frames without changing the four-case McpMessage envelope. The [batch contract](../transport/README.md#march-2025-batch-packets) defines grouped replies, invalid members, version restrictions and protocol boundaries.

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

Every encoded envelope has `jsonrpc` equal to `"2.0"`; decoding requires that exact value. Missing params and empty object params remain distinct. Explicit null or non-object params/results are rejected. Success and error are separate ADT cases, and mixed method/result/error envelopes are rejected. Arrays and batches remain outside McpMessage; the separate selected-March McpPacket boundary handles them.

## Checked codec API

- `decode(String)` reads one complete JSON document and returns `Either[AgentError.DeserializationError, McpMessage]`.
- `fromJson(Json)` validates an existing AST and returns the same inbound error type.
- `encode(McpMessage)` returns `Either[AgentError.InvalidMessage, String]`.
- `toJson(McpMessage)` returns `Either[AgentError.InvalidMessage, Json.Obj]`.

These are explicit checked codecs; no implicit `JsonCodec` or infallible encoder is exposed. Manual AST conversion is required for the untagged envelope shape, constant protocol marker, strict field validation, and absent-versus-null error data. Constructors may contain unvalidated AST payloads, so outbound callers must use the checked conversions.

All four conversions reject duplicate object keys recursively, including inside payloads. `decode` also rejects trailing non-whitespace input. This implementation deliberately rejects unknown top-level envelope fields and unknown fields inside the error object. Extension fields inside params, result, and error data are preserved. Remote protocol errors remain data, separate from local parsing or validation failures.

The envelope does not validate method names, payload schemas, `_meta`, or revision-specific result requirements such as `resultType`. The [2025-11-25](https://modelcontextprotocol.io/specification/2025-11-25/basic) and [2026-07-28](https://modelcontextprotocol.io/specification/2026-07-28/basic) specifications provide the envelope references; no handshake or complete compatibility with either revision is claimed.

## Exact envelope rendering

encode validates the envelope and renders all nested numeric values with the shared [exact JSON renderer](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/json/README.md). Long.MinValue IDs/error codes and scaled decimal payload values retain their values through both HTTP and stdio framing. toJson still returns an AST: callers who serialize that AST with the dependency's .toJson bypass this repair. This changes neither allowed IDs nor method/payload authority.

## Explicit initialization helpers

[McpInitialization](McpInitialization.scala) implements payload construction and checked result projection for an explicitly selected `McpProtocolVersion`, defaulting to `2025-06-18`, following the initialized [lifecycle specification](https://modelcontextprotocol.io/specification/2025-06-18/basic/lifecycle). Resource capabilities follow the [resource contract](../client/README.md#resource-client-contract).

- `protocolVersion` retains the compatibility default revision string. `request(id, clientInfo, version = V20250618)` returns `Either[AgentError.InvalidMessage, McpMessage.Request]`, validates the supplied ID through the envelope boundary, and advertises empty client capabilities.
- `initialized` is the constant `notifications/initialized` notification with no identity or params.
- `readResult(Json.Obj, selected = V20250618)` returns `Either[AgentError.DeserializationError, McpInitializationResult]`. It accepts only the exact selected version, required implementation name/version strings, and a required capabilities object. Known optional fields reject explicit JSON null and malformed types; recursive duplicate keys are rejected before projection.
- `McpImplementation` has name/version and optional title, with a derived wire-data codec. The checked result reader validates optional title itself. The result retains the selected protocol version and has optional instructions and object `_meta`; these remain remote data, with no prompt or authority installation.
- Server `tools`/`resources` and their optional flags preserve absence separately from a present object and explicit false. Unknown server/tool capability fields remain in `extensions`, excluding the separately typed capability fields. Unknown outer result and serverInfo fields are ignored by this projection. No implicit result codec claims lossless round-trip behavior.

These helpers allocate no identities and implement no runtime, correlation, ordering enforcement, handshake, or negotiated-version state. MCP 2026-07-28 has no `initialize` method and is not accepted by this revision-specific result reader. readResult retains exact-version validation. The public pure readSupportedResult(result, supported) projection uses the same complete validation with checked membership; the transport owns correlated atomic selection. [McpSupportedVersions](McpSupportedVersions.scala) is a checked nonempty immutable set, not an ordered preference list. See [supported server revision agreement](../transport/README.md#supported-server-revision-agreement) for ownership and migration. The paired [McpProtocolVersionSpec](../../../../../../../test/scala/com/weisgnosis/agents/mcp/protocol/McpProtocolVersionSpec.scala) covers all three selections and rejection of an unrecognized server revision.

## 2025-06-18 resource requests and results

[McpResources](McpResources.scala) adds checked list, template-list, read, subscribe and unsubscribe builders and resource notification projections.

Resource definitions retain URI/name, optional title/description/MIME type/nonnegative integer size, checked annotations, object `_meta` and unknown extensions. Templates retain `uriTemplate` and descriptive fields without expansion. Pages preserve ordered duplicates, opaque cursors, metadata and extensions. Reads reuse the existing text/blob resource body model, including both representations when supplied. Binary strings, URIs and timestamps are retained without local interpretation. Known malformed/null fields and duplicate keys anywhere in the AST fail before projection; no implicit codec bypasses these checked readers.

Initialization projects independent optional `resources.subscribe` and `resources.listChanged` flags, preserving absent/false/true and unknown resource capability extensions. The separately typed `resources` capability is removed from outer extensions. Tool capability behavior and exact revision validation remain unchanged.

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

Run `sbt "mcp / test"` from the repository root for offline and owned local-process/loopback contracts. `sbt verify` also checks formatting and fatal-warning compilation. These tests do not establish interoperability with every server, complete protocol conformance or durable delivery.

## Remaining work

Selected initialization, capability checks, bounded discovery/invocation and joined deadlines belong to the [client contract](../client/README.md). The protocol projections do not perform transport I/O, lifecycle enforcement, schema evaluation or URI/base64/timestamp interpretation.

- [ ] Extend optional method/revision validation and lifecycle integration as required by the [parity audit](../../../../../../../../../../docs/architecture/test-parity.md#mcp-protocol-and-connection-boundaries).

AgentScope Java delegates wire serialization to MCP Java SDK 0.17.2. These specification-derived codec contracts are separate from wrapper assertion mappings, provider multimodal support and output-schema evaluation.

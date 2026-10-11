# Explicit MCP tool binding

Host-selected [ToolDef schema strictness](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#provider-tool-schema-strictness) survives binding and fixed-argument copies. Remote annotations do not select it; it is provider generation metadata, not MCP permission authority. The linked contract describes host-selected strictness and compatibility.

[McpTool.bind](McpTool.scala) returns `Tool[Any, Json.Obj, McpCallResult]` for one initialized borrowed McpClient, a fixed remoteName and a host-supplied ToolDef. Binding is pure and performs no acquisition, discovery or close. The host retains the client lifetime and explicitly registers the result with Toolkit, whose existing registration rejects local-name collisions.

ToolDef supplies the local alias, description, schema, read-only declaration and concurrency declaration. Remote annotations never grant those permissions. Model arguments cannot choose another remote tool or client. Inputs must be JSON objects with recursively unique keys; direct execution also passes through the client's checked request builder. No JSON Schema evaluation occurs. Invocation sends an explicit arguments object, including when empty. Ambient metadata extraction is not implemented.

A host may explicitly retain, omit or replace a remote output schema in ToolDef.outputSchema. Binding and preset projection preserve the selected AST through Toolkit to custom model clients; the manager never adopts it automatically. It is descriptive only, and the current Chat Completions encoder omits it. See the [core contract and migration](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#descriptive-output-schemas).

## Request-only custom content projection

Install `McpModelInputTransform(existingTransform)` through ReActAgent.make's modelInputTransform argument or the builder slot when retained MCP Custom content must reach a text-oriented provider. Its default base is ModelInputTransform.identity; ReActAgent's default remains unchanged until this adapter is explicitly installed. This is explicit host composition; binding a tool does not silently replace a host request hook or alter the core encoder's default policy.

Every Custom tool-output block becomes literal text containing its exact kind and JSON payload. No tag, URI or remote annotation grants trust, and unknown or forged tags receive the same descriptive treatment. Recursive duplicate keys and malformed Unicode fail with ModelInputError; exact JSON numbers are preserved. Result identity, state, metadata and all other content remain unchanged. Projection affects request history only: canonical history keeps the original Custom block and lossless MCP data.

The selected-request path runs the prior hook inside the existing Scope, validates its output and retains its projected settings before describing custom content. The standalone apply path also projects the prior legacy output. Typed images/audio are not converted: images still need the explicit successful ImageParts endpoint profile, and unsupported media remains a typed error. Literal JSON can repeat a resource body or blob already represented by a visible companion; this adapter supplies lossless descriptions, not token-budget compression or URI fetching.

The actual regression executes an MCP embedded-text tool through Toolkit/ReAct, then serializes the next Chat Completions request. Its unchanged baseline fails on that second request; the corrected fixture requires two serialized requests, exact visible descriptions and unchanged canonical resource payload/metadata. The paired grouped table also checks image/binary descriptions, unrelated and forged Custom data, exact decimals, settings/hook cleanup, malformed content and unsupported audio.

## Embedded resource projection parity

The pinned AgentScope Java reference recorded in the [parity audit](../../../../../../../../../../docs/architecture/test-parity.md#mcp-protocol-and-connection-boundaries) has five relevant McpContentConverterTest methods: `testConvertContent_EmbeddedResource_TextResource` (line 311), `testConvertContent_EmbeddedResource_BlobResource_Image` (326), `testConvertContent_EmbeddedResource_BlobResource_NonImage` (339), `testConvertContent_EmbeddedResource_BlobResource_NullMimeType` (357), and `testBlobResource_ImageMimeTypeVariations` (470).

Current [McpTool](McpTool.scala) projects each embedded resource as one visible block followed by its existing `ContentBlock.Custom("mcp/resource", ...)` companion. Text bodies become Text. Blob bodies whose MIME type begins with the case-sensitive `image/` prefix become opaque base64 Image sources. Other blobs become descriptive Text containing their MIME type and URI; missing MIME is described as `unknown MIME type` rather than Java's string-concatenated null label. A TextAndBlob body uses visible text while preserving both bodies in the companion. No URI is fetched and no base64 is decoded.

The companion preserves URI, body, optional MIME, inner metadata and extensions. Outer content metadata remains indexed by original remote content: one resource contributes one metadata entry and two output blocks, so hosts must not zip the flattened output with that remote metadata array. Other content variants remain one output block each. This additive representation preserves payload retention while supplying the pinned visible conversion behavior. `ToolResult.outputText` includes text and binary descriptions. Images remain typed observations. Chat Completions defaults to `ToolResultFormat.TextOnly`; its explicit `ImageParts` endpoint extension accepts successful Text/Image output, but rejects unsupported Custom companions. Embedded-resource results retain those companions; install the [request-only projection](#request-only-custom-content-projection) before selecting the supported endpoint profile. This does not make unsupported media valid.

## Preset arguments and schema projection

[McpToolPresets](McpToolPresets.scala) is an immutable checked value. `make` validates the complete defaults before storing them; `merge` validates the complete supplied input before a shallow input-wins merge. Supplied JSON null and nested objects replace their defaults; nested objects are not merged. Invalid duplicate keys cannot disappear through overwriting. `empty` preserves arguments without adding defaults.

`project` checks the original host schema for recursive duplicate keys, an object root with `type: object`, optional object properties and an optional string-array required field. It removes defaulted names only from root properties/required, preserving other fields, ordering, absent fields, definitions and extensions. It neither evaluates schemas nor rewrites references, combinators or dependencies. Hosts remain responsible for the consistency of these retained constraints.

`McpToolBinding.withPresets(definition, rawPresets, metadata)` returns a checked binding with the projected definition. `McpTool.bind(client, remoteName, binding)` and manager registration consume that binding unchanged. Direct binding construction deliberately preserves the supplied host schema, even when validated presets are present. Local aliases, descriptions and read-only/concurrency declarations remain host authority.

Defaults are applied after authorization. For defaults-only bindings, the authorizer sees the supplied input and may replace it; the assistant history records the approved input. The remote arguments additionally contain missing defaults. Supplied input can override hidden preset names, so presets are neither permission constraints nor a way to enforce secret arguments. Host permission and concurrency declarations must remain valid for the defaults and permitted overrides. Request metadata remains separate.

Existing bind/register calls retain empty defaults. No new manager ownership or core authorization hook is introduced.

## Fixed arguments

`McpToolBinding.withFixedArguments(definition, rawFixed, metadata)` checks fixed values and projects their root names out of the supplied input schema. It installs the core [ToolFixedArguments policy](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#fixed-raw-arguments), with empty model-overridable defaults. Direct binding construction or a named copy can combine checked fixed arguments and defaults while retaining an explicitly supplied schema.

Manager registration preserves the whole selected binding. Raw decoding and Toolkit dispatch prepare fixed values; ReAct prepares them before authorization and rejects approved inputs that remove or change them. Accepted nonfixed edits remain intact in history and execution. Defaults still fill missing fields after authorization, so prepared fixed values win any overlap. Metadata propagation is independent. Trusted typed `execute`/`executeWithContext` calls bypass the fixed raw-input policy; fixed values are not secrets and can appear in history.

The factory retains the original schema in `inputValidationSchema` while projecting fixed names from the model-facing declaration. Toolkit validates the complete prepared/approved input against this original schema, including fixed-field types and constraints; a closed schema does not reject its own injected host fields. Raw model arguments and defaults remain separate from execution authority. The factory does not evaluate JSON Schema or rewrite dependent constraints during projection.

Direct construction may omit the optional validation override and retain validation against its supplied definition. A manually projected definition must explicitly supply the full execution validation schema. Positional patterns and Product arity now have five fields; consumers must rebuild.

## Explicit request metadata

[McpToolMetadata](McpToolMetadata.scala) is a fixed host binding policy: Omit (the default) or Propagate with an effectful `McpToolIdentity => IO[AgentError, Json.Obj]` resolver. The client also has a live Allow/Omit restriction. Each invocation reads that restriction once before resolving; either omission gate skips the resolver entirely and omits `_meta`. A later mode change does not revoke an already-started resolver, even before network send. An enabled empty resolver result remains an explicit empty object for Detached; Active adds its call-ID field.

The resolver receives only Detached or Active(replyId, requestId, callId, callOrdinal), never arguments, an event port or a context bag. Reply/request IDs are runtime correlation; callId originates in model output and may repeat. The ordinal identifies its occurrence. None is authentication or permission authority. Host-scoped trace data can be read through the resolver effect. Returned JSON must have recursively unique keys before dispatch. Active invocation overwrites the compatibility key `io.agentscope/toolCallId` without mutating host data; Detached keeps supplied host metadata and manufactures no call ID. Model `_meta` fields remain ordinary arguments and are never extracted.

Resolver failure becomes the existing typed direct-execution failure or Toolkit Error observation. Cancellation joins resolver cleanup and retains defects. The binding adds no resolver deadline or owner: the invoking host owns execution; the client's configured operation deadline begins with the subsequent client call. Explicit client `callTool(..., meta)` and `listToolsPage(..., meta)` bypass this bound-tool restriction intentionally.

`McpToolBinding(definition, metadata = Omit, presets = empty, fixedArguments = empty, inputValidationSchema = None)` pairs host authority, propagation policy and checked argument policies for [manager registerBindings](../client/README.md#scoped-manager-contract). Existing bind calls retain omission; no implicit opt-in or core invocation API migration is required.

## Ordered metadata merge

[McpMetadata.merge](McpMetadata.scala) is a pure checked helper taking Chunk[Json.Obj] and returning Either[AgentError.InvalidMessage, Json.Obj]. Sources are processed in supplied order; later top-level fields replace earlier whole values, including nested objects and literal JSON null. It is a shallow merge, not recursive patching. Empty sources produce an empty object. Every source undergoes recursive duplicate-field validation before it participates, including malformed values later overwritten by another source; invalid input fails the complete merge.

The helper retains original JSON AST values, including exact decimals and signed Long endpoints, without text serialization, mutation or effectful context acquisition. A host may explicitly compose defaults and live metadata inside the existing resolver. Existing Omit gates still skip the resolver entirely; runtime active-call overlay of io.agentscope/toolCallId runs last and remains authoritative. Detached metadata supplies no fabricated call ID. Merging correlation data grants no authentication, permission or ambient-context authority.

This is an additive public function; existing binding/resolver signatures and omission defaults are unchanged. The one new table-driven McpMetadataSpec covers ordered replacement, empty input, exact values and rejection of overwritten malformed sources; existing McpToolMetadataSpec integration composes defaults/live metadata without adding a group. The selected adaptation follows pinned McpMeta.merge (96); nullable Java inputs, mutable copying/accessors, ambient bags, caching and schema redesign are not adopted.

## Observation projection

Text, image and audio become the corresponding core ContentBlock variants in original order. Image/audio data stays opaque base64-source data; URL-looking values are not interpreted as URLs. Resource links become `Custom("mcp/resource_link", payload)` with uri, name and optional title, description, mimeType and size. Embedded resources contribute a visible Text/Image/description followed by `Custom("mcp/resource", payload)` with uri, body, optional mimeType/meta and extensions; the [projection contract](#embedded-resource-projection-parity) defines ordering and metadata indexing. The body object contains text, blob or both. Nothing fetches a URI, decodes base64 or evaluates a schema. Decimal size and priority remain Json.Num without integer expansion.

ToolResultPayload metadata contains exactly one top-level `mcp` object with this domain grouping:

```text
mcp:
  structuredContent: optional object
  meta: optional result object
  extensions: result extension object
  content: ordered array, one entry per original remote content block
    annotations: optional object
      audience: optional ordered user/assistant array
      priority: optional number
      lastModified: optional string
      extensions: annotation extension object
    meta: optional content object
    extensions: content extension object
```

The content array retains entries even without optional annotations or metadata. Extension objects remain nested, including empty objects; they are never merged into typed fields or runtime metadata. Embedded resource meta/extensions belong inside its Custom payload, independently of the outer content entry. This is a domain observation representation, not an MCP wire serializer or full round-trip codec.

A successful protocol reply with isError=true projects to ToolResultState.Error while retaining all typed content and metadata; other valid call results project to Success. Remote JSON-RPC failures remain typed McpRemoteError through direct execute, but Toolkit normalizes them into textual Error observations and does not preserve their code/data there. Cancellation and joined cleanup retain the existing client/Toolkit contract. encodeOutput is only the typed result's text projection; non-text content remains available through encodeResult. ReAct protects call identity and control metadata. Its Chat Completions request codec defaults to TextOnly. The explicit ImageParts extension supports successful Text/Image output. The [request-only projection](#request-only-custom-content-projection) describes retained Custom companions without changing the canonical observation or relaxing media validation.

## Verification and acceptance

Run `sbt "mcp / test"` from the repository root for offline and owned local-process/loopback contracts. `sbt verify` also checks formatting and fatal-warning compilation. These tests do not establish interoperability with every server, complete protocol conformance or durable delivery.

## Upstream scope and remaining work

The bounded adaptation of pinned `McpTool.callAsync` is fixed remote invocation and conversion of its result. `McpContentConverter.convertCallToolResult` maps to typed outcome/content projection. This implementation deliberately retains error content, audio, resources, structured content and metadata instead of upstream text-only error extraction and placeholders. It does not infer local read-only authority from remote hints as the upstream Java McpClientManager registration does.

Explicit effectful metadata projection, live bound-tool omission and host-selected descriptive output schemas are implemented; output schemas are not evaluated. The [method mappings](../../../../../../../../../../docs/architecture/test-parity.md#mcp-protocol-and-connection-boundaries) describe context/default adaptations. Fixed arguments and model-overridable defaults remain distinct. Ambient context bags, broader schema evaluation and provider media support are separate concerns. The borrowed client supplies [bounded optional outbound cancellation](../client/README.md#outbound-cancellation-contract); binding adds no delivery guarantee or complete MCP revision support.

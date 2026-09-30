# Explicit MCP tool binding

[McpTool.bind](McpTool.scala) returns `Tool[Any, Json.Obj, McpCallResult]` for one initialized borrowed McpClient, a fixed remoteName and a host-supplied ToolDef. Binding is pure and performs no acquisition, discovery or close. The host retains the client lifetime and explicitly registers the result with Toolkit, whose existing registration rejects local-name collisions.

ToolDef supplies the local alias, description, schema, read-only declaration and concurrency declaration. Remote annotations never grant those permissions. Model arguments cannot choose another remote tool or client. Inputs must be JSON objects with recursively unique keys; direct execution also passes through the client's checked request builder. No JSON Schema evaluation occurs. Invocation sends Some(input), including an explicit empty object. Preset arguments and ambient metadata extraction are not implemented.

## Explicit request metadata

[McpToolMetadata](McpToolMetadata.scala) is a fixed host binding policy: Omit (the default) or Propagate with an effectful `McpToolIdentity => IO[AgentError, Json.Obj]` resolver. The client also has a live Allow/Omit restriction. Each invocation reads that restriction once before resolving; either omission gate skips the resolver entirely and omits `_meta`. A later mode change does not revoke an already-started resolver, even before network send. An enabled empty resolver result remains an explicit empty object for Detached; Active adds its call-ID field.

The resolver receives only Detached or Active(replyId, requestId, callId, callOrdinal), never arguments, an event port or a context bag. Reply/request IDs are runtime correlation; callId originates in model output and may repeat. The ordinal identifies its occurrence. None is authentication or permission authority. Host-scoped trace data can be read through the resolver effect. Returned JSON must have recursively unique keys before dispatch. Active invocation overwrites the compatibility key `io.agentscope/toolCallId` without mutating host data; Detached keeps supplied host metadata and manufactures no call ID. Model `_meta` fields remain ordinary arguments and are never extracted.

Resolver failure becomes the existing typed direct-execution failure or Toolkit Error observation. Cancellation joins resolver cleanup and retains defects. The binding adds no resolver deadline or owner: the invoking host owns execution; the client's configured operation deadline begins with the subsequent client call. Explicit client `callTool(..., meta)` and `listToolsPage(..., meta)` bypass this bound-tool restriction intentionally.

`McpToolBinding(definition, metadata = Omit)` pairs host authority and propagation policy for [manager registerBindings](../client/README.md#scoped-manager-contract). Existing bind calls retain omission; no implicit opt-in or core invocation API migration is required.

## Observation projection

Text, image and audio become the corresponding core ContentBlock variants in original order. Image/audio data stays opaque base64-source data; URL-looking values are not interpreted as URLs. Resource links become `Custom("mcp/resource_link", payload)` with uri, name and optional title, description, mimeType and size. Embedded resources become `Custom("mcp/resource", payload)` with uri, body, optional mimeType/meta, and extensions. The body object contains text, blob or both. Nothing fetches a URI, decodes base64 or evaluates a schema. Decimal size and priority remain Json.Num without integer expansion.

ToolResultPayload metadata contains exactly one top-level `mcp` object with this domain grouping:

```text
mcp:
  structuredContent: optional object
  meta: optional result object
  extensions: result extension object
  content: ordered array, one entry per content block
    annotations: optional object
      audience: optional ordered user/assistant array
      priority: optional number
      lastModified: optional string
      extensions: annotation extension object
    meta: optional content object
    extensions: content extension object
```

The content array retains entries even without optional annotations or metadata. Extension objects remain nested, including empty objects; they are never merged into typed fields or runtime metadata. Embedded resource meta/extensions belong inside its Custom payload, independently of the outer content entry. This is a domain observation representation, not an MCP wire serializer or full round-trip codec.

A successful protocol reply with isError=true projects to ToolResultState.Error while retaining all typed content and metadata; other valid call results project to Success. Remote JSON-RPC failures remain typed McpRemoteError through direct execute, but Toolkit normalizes them into textual Error observations and does not preserve their code/data there. Cancellation and joined cleanup retain the existing client/Toolkit contract. encodeOutput is only the typed result's text projection; non-text content remains available through encodeResult. ReAct protects call identity and control metadata. Its current ChatCompletions request codec still rejects non-text tool output.

## Verification and acceptance

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for the current explicit metadata scope, with no findings at the identities below and in the [client acceptance](../client/README.md#verification-and-acceptance). Three [McpToolMetadataSpec](../../../../../../../test/scala/com/weisgnosis/agents/mcp/tool/McpToolMetadataSpec.scala) groups and the existing binding scenarios pass.
- [x] Formatting and full `verify` passed in `mcp-tool-metadata-verify.log`: 573 core / 673 harness / 75 MCP tests (1,321 total), zero compiler warnings or failures.

| Source/test | Reviewed Git blob |
| --- | --- |
| [McpTool.scala](McpTool.scala) | 0af39b7e0ae5ee81076a15b5ded7e82a92781f53 |
| [McpToolMetadata.scala](McpToolMetadata.scala) | b08198366e5ec303ef3cbb30b7db458a195311db |
| [McpToolMetadataSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/mcp/tool/McpToolMetadataSpec.scala) | b1379da19a74c176f14dfdf2d00b6eb76e603adb |
| [McpToolSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/mcp/tool/McpToolSpec.scala) | 2be53b2fdc30deea625a87e94057100b6d91858c |

## Upstream scope and remaining work

The bounded adaptation of pinned `McpTool.callAsync` is fixed remote invocation and conversion of its result. `McpContentConverter.convertCallToolResult` maps to typed outcome/content projection. This implementation deliberately retains error content, audio, resources, structured content and metadata instead of upstream text-only error extraction and placeholders. It does not infer local read-only authority from remote hints as the upstream Java McpClientManager registration does.

- [x] Bounded scoped registration/removal and host-selected filtering/names have separate [manager acceptance](../client/README.md#scoped-manager-contract).
- [x] Explicit effectful metadata projection and live bound-tool omission have final independent approval; [method mappings](../../../../../../../../../../docs/architecture/test-parity.md#mcp-protocol-and-connection-boundaries) record default and context adaptations.
- [ ] Next: implement host-owned preset arguments with matching exclusion from the advertised input schema. Groups and ambient context bags remain separate.
- [ ] Implement provider multimodal projection separately; typed history/events alone do not supply it.
The borrowed client supplies [bounded optional outbound cancellation](../client/README.md#outbound-cancellation-contract); binding adds no delivery guarantee.

- [ ] Keep caching, notification invalidation, reconnect and output-schema evaluation as separate work.

The manual binding does not close whole McpClientManager methods or establish complete MCP revision support.

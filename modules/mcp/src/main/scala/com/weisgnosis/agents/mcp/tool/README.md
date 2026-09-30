# Explicit MCP tool binding

[McpTool.bind](McpTool.scala) returns `Tool[Any, Json.Obj, McpCallResult]` for one initialized borrowed McpClient, a fixed remoteName and a host-supplied ToolDef. Binding is pure and performs no acquisition, discovery or close. The host retains the client lifetime and explicitly registers the result with Toolkit, whose existing registration rejects local-name collisions.

ToolDef supplies the local alias, description, schema, read-only declaration and concurrency declaration. Remote annotations never grant those permissions. Model arguments cannot choose another remote tool or client. Inputs must be JSON objects with recursively unique keys; direct execution also passes through the client's checked request builder. No JSON Schema evaluation occurs. Invocation sends an explicit arguments object, including when empty. Ambient metadata extraction is not implemented.

A host may explicitly retain, omit or replace a remote output schema in ToolDef.outputSchema. Binding and preset projection preserve the selected AST through Toolkit to custom model clients; the manager never adopts it automatically. It is descriptive only, and the current Chat Completions encoder omits it. See the [core contract and migration](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#descriptive-output-schemas).

## Preset arguments and schema projection

[McpToolPresets](McpToolPresets.scala) is an immutable checked value. `make` validates the complete defaults before storing them; `merge` validates the complete supplied input before a shallow input-wins merge. Supplied JSON null and nested objects replace their defaults; nested objects are not merged. Invalid duplicate keys cannot disappear through overwriting. `empty` preserves arguments without adding defaults.

`project` checks the original host schema for recursive duplicate keys, an object root with `type: object`, optional object properties and an optional string-array required field. It removes defaulted names only from root properties/required, preserving other fields, ordering, absent fields, definitions and extensions. It neither evaluates schemas nor rewrites references, combinators or dependencies. Hosts remain responsible for the consistency of these retained constraints.

`McpToolBinding.withPresets(definition, rawPresets, metadata)` returns a checked binding with the projected definition. `McpTool.bind(client, remoteName, binding)` and manager registration consume that binding unchanged. Direct binding construction deliberately preserves the supplied host schema, even when validated presets are present. Local aliases, descriptions and read-only/concurrency declarations remain host authority.

Defaults are applied after authorization. The authorizer sees the supplied input and may replace it; the assistant history records the approved input. The remote arguments additionally contain missing defaults. Supplied input can override hidden preset names, so presets are neither permission constraints nor a way to enforce secret arguments. Host permission and concurrency declarations must remain valid for the defaults and permitted overrides. Request metadata remains separate.

Migration: existing bind/register calls and two-argument McpToolBinding construction retain empty defaults. McpToolBinding now has three fields; positional pattern matches must include presets. No new manager ownership or core authorization hook is introduced.

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for the preset/binding scope with no findings; exact identities and verification are below.

## Explicit request metadata

[McpToolMetadata](McpToolMetadata.scala) is a fixed host binding policy: Omit (the default) or Propagate with an effectful `McpToolIdentity => IO[AgentError, Json.Obj]` resolver. The client also has a live Allow/Omit restriction. Each invocation reads that restriction once before resolving; either omission gate skips the resolver entirely and omits `_meta`. A later mode change does not revoke an already-started resolver, even before network send. An enabled empty resolver result remains an explicit empty object for Detached; Active adds its call-ID field.

The resolver receives only Detached or Active(replyId, requestId, callId, callOrdinal), never arguments, an event port or a context bag. Reply/request IDs are runtime correlation; callId originates in model output and may repeat. The ordinal identifies its occurrence. None is authentication or permission authority. Host-scoped trace data can be read through the resolver effect. Returned JSON must have recursively unique keys before dispatch. Active invocation overwrites the compatibility key `io.agentscope/toolCallId` without mutating host data; Detached keeps supplied host metadata and manufactures no call ID. Model `_meta` fields remain ordinary arguments and are never extracted.

Resolver failure becomes the existing typed direct-execution failure or Toolkit Error observation. Cancellation joins resolver cleanup and retains defects. The binding adds no resolver deadline or owner: the invoking host owns execution; the client's configured operation deadline begins with the subsequent client call. Explicit client `callTool(..., meta)` and `listToolsPage(..., meta)` bypass this bound-tool restriction intentionally.

`McpToolBinding(definition, metadata = Omit, presets = empty)` pairs host authority, propagation policy and checked defaults for [manager registerBindings](../client/README.md#scoped-manager-contract). Existing bind calls retain omission; no implicit opt-in or core invocation API migration is required.

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

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for the current preset/binding source and test identities below and in the [client acceptance](../client/README.md#verification-and-acceptance), with no findings. Two pure preset groups, actual authorization/metadata/output-schema integration and checked manager rollback pass alongside existing binding scenarios. The [core acceptance](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#descriptive-output-schemas) records the output-schema scope.
- [x] Formatting and full `verify` passed in `tool-output-schema-verify.log`: 574 core / 673 harness / 77 MCP tests (1,324 total), zero compiler warnings or failures.

| Source/test | Reviewed Git blob |
| --- | --- |
| [McpToolPresets.scala](McpToolPresets.scala) | 325bfc0091fe387b10c8a7a34d778defb9e1c242 |
| [McpToolPresetsSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/mcp/tool/McpToolPresetsSpec.scala) | 53dec7e9f096a82ec8b663bd619b9b029e36a15b |
| [McpTool.scala](McpTool.scala) | f7afaec0525d876666ddba031dc9495b5aa2fa44 |
| [McpToolMetadata.scala](McpToolMetadata.scala) | 25bd3f5d6843fc9e0b01ea04bc8e01641b205cfa |
| [McpToolMetadataSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/mcp/tool/McpToolMetadataSpec.scala) | aea7102a3970ca7be863ea69bdf219e7df7651e5 |
| [McpToolSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/mcp/tool/McpToolSpec.scala) | 2be53b2fdc30deea625a87e94057100b6d91858c |

## Upstream scope and remaining work

The bounded adaptation of pinned `McpTool.callAsync` is fixed remote invocation and conversion of its result. `McpContentConverter.convertCallToolResult` maps to typed outcome/content projection. This implementation deliberately retains error content, audio, resources, structured content and metadata instead of upstream text-only error extraction and placeholders. It does not infer local read-only authority from remote hints as the upstream Java McpClientManager registration does.

- [x] Bounded scoped registration/removal and host-selected filtering/names have separate [manager acceptance](../client/README.md#scoped-manager-contract).
- [x] Explicit effectful metadata projection and live bound-tool omission have final independent approval; [method mappings](../../../../../../../../../../docs/architecture/test-parity.md#mcp-protocol-and-connection-boundaries) record default and context adaptations.
- [x] Checked host presets and root input-schema exclusion have final independent approval; supplied/approved input overrides defaults after authorization.
- [x] Host-selected descriptive output schemas survive binding, preset projection and model-call definitions; host discard/override and provider omission are verified without result evaluation.
- [ ] Next: define the distinct framework-enforced registered-preset contract; current presets remain model-overridable defaults. Groups and ambient context bags remain separate.
- [ ] Implement provider multimodal projection separately; typed history/events alone do not supply it.
The borrowed client supplies [bounded optional outbound cancellation](../client/README.md#outbound-cancellation-contract); binding adds no delivery guarantee.

- [ ] Keep caching, notification invalidation, reconnect and output-schema evaluation as separate work.

The manual binding does not close whole McpClientManager methods or establish complete MCP revision support.

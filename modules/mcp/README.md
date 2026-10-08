# MCP clients and tool bindings

This module connects host-selected MCP servers to core tools through typed protocol messages, scoped clients, and explicit bindings. It provides stdio, Streamable HTTP, and legacy HTTP/SSE transports, resource access, and bounded client management.

## Start here

Follow the MCP application in [Getting started](../../docs/getting-started.md). [HostAssembly](../examples/src/main/scala/com/weisgnosis/agents/examples/HostAssembly.scala) demonstrates selected scoped registrations with explicit local definitions and authorization.

Choose a [transport](src/main/scala/com/weisgnosis/agents/mcp/transport/README.md), initialize it through [McpClient.open](src/main/scala/com/weisgnosis/agents/mcp/client/McpClient.scala), and bind selected tools through [McpTool](src/main/scala/com/weisgnosis/agents/mcp/tool/McpTool.scala) or the [client manager](src/main/scala/com/weisgnosis/agents/mcp/client/README.md). Remote annotations and schemas are data; the host supplies permission and installed tool identity.

| Package | Purpose |
| --- | --- |
| [protocol](src/main/scala/com/weisgnosis/agents/mcp/protocol/README.md) | Checked envelopes, initialization, tools, resources, and payload codecs |
| [transport](src/main/scala/com/weisgnosis/agents/mcp/transport/README.md) | Framing, process/HTTP ownership, revision selection, and bounded recovery policies |
| [client](src/main/scala/com/weisgnosis/agents/mcp/client/README.md) | Correlation, deadlines, discovery, resources, and selected registration |
| [tool](src/main/scala/com/weisgnosis/agents/mcp/tool/README.md) | Binding, presets, metadata, typed results, and request projection |

## Content and model requests

MCP results preserve text, media, structured data, resource companions, and metadata. Retained Custom blocks are not directly accepted by the default Chat Completions tool-result encoder. Install [McpModelInputTransform](src/main/scala/com/weisgnosis/agents/mcp/tool/McpModelInputTransform.scala) explicitly through the agent's modelInputTransform slot, passing the prior host transform when composing. It renders every custom kind/payload as literal JSON text for the request while leaving canonical history intact; tags grant no authority.

This projection does not adapt typed media. Images still need a compatible ImageParts endpoint profile; audio remains unsupported by that tool-result profile. Nothing fetches resource URIs or interprets opaque base64 data. See the [projection contract](src/main/scala/com/weisgnosis/agents/mcp/tool/README.md#request-only-custom-content-projection) and [core client profiles](../core/src/main/scala/com/weisgnosis/agents/core/client/README.md).

## Ownership and limits

Client and transport scopes must outlive their requests and joined cleanup. The host owns borrowed transports, credentials, process arguments, endpoints, and authorization. Each request has its own scope and checked deadline; returning after cancellation can take longer while cleanup joins. Stdio uses explicit argv without an implicit shell and owns the direct child, not a contained process tree.

Stdio and Streamable HTTP initialization default to 2025-06-18; legacy HTTP/SSE defaults to 2024-11-05. Explicit older profiles and checked server revision agreement follow the linked transport contract. These are selected contracts, not complete conformance to every MCP revision; the different 2026-07-28 lifecycle is unsupported. Protocol boundaries reject ambiguous envelopes and duplicate keys, with documented numeric and framing limits. Payload extensions remain data.

Recovery policies are bounded and explicit. Optional cancellation notifications are best effort, with no delivery acknowledgement. Expired sessions require explicit host replacement; automatic POST replay, general reconnect, durable orchestration, notification-driven definition invalidation, and broader schema evaluation are not supplied. Source/JVM and wire migration details remain in the package contracts; unfinished interoperability is in the [tracker](../../docs/architecture/tracker.md).

## Testing

From the repository root, run `sbt "mcp / test"`. Tests include scoped local processes and HTTP fixtures without requiring a public model endpoint. Run `sbt verify` for repository formatting checks, fatal-warning compilation, and tests. See [local artifact installation](../../project/README.md#local-artifact-packaging) for consuming the module outside this build.

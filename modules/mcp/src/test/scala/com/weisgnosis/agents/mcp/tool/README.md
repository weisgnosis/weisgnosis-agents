# MCP tool binding verification

[McpToolSpec](McpToolSpec.scala) contains three grouped integration scenarios using an initialized client and controlled borrowed transport:

- Fixed remote routing through an actual ReAct continuation preserves host ToolDef authority, all five content variants, structured data, independent metadata/extension levels and all resource bodies. Error state survives while runtime identity/control metadata remains protected.
- Non-object or duplicate arguments send nothing, including duplicate objects passed directly to execute through the checked client boundary. Valid success and remote RPC rejection become distinct Toolkit observations; an argument named name cannot reroute the binding.
- Invocation cancellation joins blocked cleanup and retains interruption and its cleanup defect. Existing client suites separately cover remote RPC code/data preservation before Toolkit's textual normalization.

These scenarios adapt only fixed invocation and content conversion from pinned McpTool.callAsync and McpContentConverter. They do not prove automatic manager registration/removal, presets, filtering, caching, schema evaluation or provider multimodal support. Existing client suites own transport/request/deadline coverage.

- [x] Both independent reviewers approved the final source/spec and full repository verification passed. Exact identities and evidence belong to the [source acceptance](../../../../../../../main/scala/com/weisgnosis/agents/mcp/tool/README.md#verification-and-acceptance).

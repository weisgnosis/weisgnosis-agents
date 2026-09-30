# MCP tool binding verification

[McpToolSpec](McpToolSpec.scala) contains three grouped integration scenarios using an initialized client and controlled borrowed transport:

- Fixed remote routing through an actual ReAct continuation preserves host ToolDef authority, all five content variants, structured data, independent metadata/extension levels and all resource bodies. Error state survives while runtime identity/control metadata remains protected.
- Non-object or duplicate arguments send nothing, including duplicate objects passed directly to execute through the checked client boundary. Valid success and remote RPC rejection become distinct Toolkit observations; an argument named name cannot reroute the binding.
- Invocation cancellation joins blocked cleanup and retains interruption and its cleanup defect. Existing client suites separately cover remote RPC code/data preservation before Toolkit's textual normalization.

[McpToolMetadataSpec](McpToolMetadataSpec.scala) adds three grouped contracts: actual registered ReAct calls receive restricted occurrence identity and effectful host metadata outside model arguments; omission skips resolver effects while invocation-start snapshots, enabled empty objects and explicit client metadata remain distinct; typed resolver failure and invalid nested duplicates send nothing, while cancellation joins held cleanup and preserves its defect. The manager suite separately checks source-compatible fallback and atomic selection rollback.

These scenarios adapt fixed invocation, content conversion and explicit metadata propagation from pinned McpTool.callAsync/buildMetaMap/extractMcpMeta. Omit is the local default; no generic runtime-context bag or mutable per-tool switch is introduced. Presets, groups, caching, schema evaluation and provider multimodal support remain outside this scope. Existing client suites own transport/request/deadline coverage; resolver lifetime and timeout remain the invoking host's responsibility.

- [x] Both independent reviewers approved the final source/spec and full repository verification passed. Exact identities and evidence belong to the [source acceptance](../../../../../../../main/scala/com/weisgnosis/agents/mcp/tool/README.md#verification-and-acceptance).

# MCP tool binding verification

[McpToolSpec](McpToolSpec.scala) contains three grouped integration scenarios using an initialized client and controlled borrowed transport:

- Fixed remote routing through an actual ReAct continuation preserves host ToolDef authority, all five content variants, structured data, independent metadata/extension levels and all resource bodies. Error state survives while runtime identity/control metadata remains protected.
- Non-object or duplicate arguments send nothing, including duplicate objects passed directly to execute through the checked client boundary. Valid success and remote RPC rejection become distinct Toolkit observations; an argument named name cannot reroute the binding.
- Invocation cancellation joins blocked cleanup and retains interruption and its cleanup defect. Existing client suites separately cover remote RPC code/data preservation before Toolkit's textual normalization.

[McpToolMetadataSpec](McpToolMetadataSpec.scala) adds three grouped contracts: actual registered ReAct calls preserve the authorizer's rewritten input in history, apply missing defaults only to remote arguments, and retain restricted occurrence identity and separate host metadata; omission skips resolver effects while invocation-start snapshots, enabled empty objects and explicit client metadata remain distinct; typed resolver failure and invalid nested duplicates send nothing, while cancellation joins held cleanup and preserves its defect. The manager suite separately checks the custom-manager fallback and atomic rollback after malformed preset selection.

[McpToolPresetsSpec](McpToolPresetsSpec.scala) contains two pure grouped contracts: shallow input-wins defaults, literal null/nested replacement and rejection of malformed original branches; root schema exclusion with preserved field order, absence, definitions, extensions and host flags. Empty defaults and direct unprojected host bindings remain valid. Schema evaluation and reference rewriting are not tested or implemented.

These scenarios adapt fixed invocation, content conversion, explicit metadata propagation, preset merging and root schema exclusion from the pinned upstream methods recorded in the [parity audit](../../../../../../../../../../docs/architecture/test-parity.md#mcp-protocol-and-connection-boundaries). Omit is the metadata default; no generic runtime-context bag or mutable per-tool switch is introduced. Groups, caching, schema evaluation and provider multimodal support remain outside this scope. Existing client suites own transport/request/deadline coverage; resolver lifetime and timeout remain the invoking host's responsibility.

- [x] Both independent reviewers approved the final source/spec and full repository verification passed. Exact identities and evidence belong to the [source acceptance](../../../../../../../main/scala/com/weisgnosis/agents/mcp/tool/README.md#verification-and-acceptance).

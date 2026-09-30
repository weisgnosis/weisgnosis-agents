# com.weisgnosis.agents.mcp.client: tests

[McpRequestsSpec](McpRequestsSpec.scala), [McpConnectionSpec](McpConnectionSpec.scala), [McpClientSpec](McpClientSpec.scala) and [McpClientManagerSpec](McpClientManagerSpec.scala) verify the [client contract](../../../../../../../main/scala/com/weisgnosis/agents/mcp/client/README.md) using scopes, STM, queues and explicit coordination promises. No test starts a network server or process.

| Suite / groups | Independent contracts |
| --- | --- |
| Request registry / five | Atomic abandonment observer, released capacity, reply/terminal suppression and exact observer-defect causes; out-of-order correlation and remote error data; capacity and scope release; waiting-fiber interruption versus completed replies; owner/terminal closure with complete causes |
| Connection / eight | Eligible cancellation versus initialize/unissued/completed requests, bounded dropping and connection-owned writer cleanup; immediate and concurrent replies, local send recovery and checked dispatch; cancellation joins cleanup and retains defects; terminal cause preservation during send and reply waiting; closed-parent startup; control writes and directional cancellation; concurrent close and defect replay before borrowed-resource release; EOF, uncorrelated errors and bounded control overflow |
| Initialized client / six | Publication after initialized send; exact-version/error cleanup and invalid-deadline rejection before reader startup; tools capability authority; initialization/call/later-page deadline and cancellation joining held cleanup; ordered bounded discovery through empty pages, opaque cursor cycles, exact/invalid/excess budgets, metadata forwarding and later remote failure without partial publication |
| Scoped manager / four | Atomic concurrent collision publication and explicit host definitions, including output-schema discard/override; metadata-aware selection rollback and custom-manager unsupported fallback without acquisition; failed/cancelled initialization and exact compound causes; retained Pending/Removing capacity and joined active-call cleanup; immutable snapshot routing and transferred client closure; parallel owner cleanup, shared defect replay and closed admission |

The terminal cause and post-terminal dispatch regressions failed against the unchanged draft before correction. They assert the public ownership contract, not internal Ref layouts. The already-closed-scope case is contract coverage; no failing-before-fix result is claimed for the inspected startup ordering hazard. Existing groups cover shared closure defects without adding getter tests or duplicating setup mechanically.

## Acceptance and next tests

- [x] `path_review` and `current_docs` approved the manager source/spec identities recorded in the linked client acceptance, with no findings. The compound-cause regression failed against unchanged source before correction; its final assertion checks the complete Cause and preserves independent resource defects exactly once.

- [x] Client-package suites pass in full `mcp-fixed-arguments-verify.log` verification, with zero compiler warnings or failures. Both reviewers approved the current manager/client metadata changes and exact identities in the [client acceptance](../../../../../../../main/scala/com/weisgnosis/agents/mcp/client/README.md#verification-and-acceptance). The [metadata/argument integration scenarios](../tool/McpToolMetadataSpec.scala) exercise actual registered invocation and live restriction behavior; current totals belong to the tracker.
- [x] Both reviewers approved the final connection and initialized-client scope and full verification passed; exact identities and evidence are in the [client acceptance](../../../../../../../main/scala/com/weisgnosis/agents/mcp/client/README.md#verification-and-acceptance).
- [x] The transport suite covers the selected HTTP POST and explicit GET contract.
- [ ] Complete remaining transport features and interoperability coverage. Bounded optional outbound cancellation is covered; notification delivery is not guaranteed. The tool package covers manual Toolkit/ReAct binding, and the transport suite covers actual stdio initialization, list/call and cleanup.
- [x] Verify explicit metadata projection, restricted invocation identity and omission boundaries in the tool package.
- [x] Checked fixed-argument selection failure rolls back the entire registration through the existing owner; actual invocation/schema exclusion is covered in the tool package.
- [ ] Next: adapt host-selected tool-group registration and activation; complete remaining method mappings and ambient context integration separately.

These controlled-port scenarios do not establish external-server interoperability, acknowledged remote cancellation or complete AgentScope wrapper parity.

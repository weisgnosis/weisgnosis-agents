# com.weisgnosis.agents.mcp.client: tests

[McpRequestsSpec](McpRequestsSpec.scala), [McpConnectionSpec](McpConnectionSpec.scala), [McpClientSpec](McpClientSpec.scala) and [McpClientManagerSpec](McpClientManagerSpec.scala) verify the [client contract](../../../../../../../main/scala/com/weisgnosis/agents/mcp/client/README.md) using scopes, STM, queues and explicit coordination promises. No test starts a network server or process.

| Suite / groups | Independent contracts |
| --- | --- |
| Request registry / four | Out-of-order correlation and remote error data; capacity and scope release; waiting-fiber interruption versus completed replies; owner/terminal closure with complete causes |
| Connection / seven | Immediate and concurrent replies, local send recovery and checked dispatch; cancellation joins cleanup and retains defects; terminal cause preservation during send and reply waiting; closed-parent startup; control writes and directional cancellation; concurrent close and defect replay before borrowed-resource release; EOF, uncorrelated errors and bounded control overflow |
| Initialized client / six | Publication after initialized send; exact-version/error cleanup and invalid-deadline rejection before reader startup; tools capability authority; initialization/call/later-page deadline and cancellation joining held cleanup; ordered bounded discovery through empty pages, opaque cursor cycles, exact/invalid/excess budgets, metadata forwarding and later remote failure without partial publication |
| Scoped manager / four | Atomic concurrent collision publication and explicit host definitions; failed/cancelled initialization and exact compound causes; retained Pending/Removing capacity and joined active-call cleanup; immutable snapshot routing and transferred client closure; parallel owner cleanup, shared defect replay and closed admission |

The terminal cause and post-terminal dispatch regressions failed against the unchanged draft before correction. They assert the public ownership contract, not internal Ref layouts. The already-closed-scope case is contract coverage; no failing-before-fix result is claimed for the inspected startup ordering hazard. Existing groups cover shared closure defects without adding getter tests or duplicating setup mechanically.

## Acceptance and next tests

- [x] `path_review` and `current_docs` approved the manager source/spec identities recorded in the linked client acceptance, with no findings. The compound-cause regression failed against unchanged source before correction; its final assertion checks the complete Cause and preserves independent resource defects exactly once.

- [x] All twenty-one client-package groups pass in full verification: 564 core / 651 harness / 48 MCP tests (1,263 total), zero compiler warnings or failures, recorded in `mcp-manager-verify.log`.
- [x] Both reviewers approved the final connection and initialized-client scope and full verification passed; exact identities and evidence are in the [client acceptance](../../../../../../../main/scala/com/weisgnosis/agents/mcp/client/README.md#verification-and-acceptance).
- [ ] Next: HTTP transport coverage for an explicitly selected revision. Outgoing cancellation notifications remain separate. The tool package covers manual Toolkit/ReAct binding, and the transport suite covers actual stdio initialization, list/call and cleanup.
- [ ] Complete applicable upstream method mappings and metadata trust boundaries.

These controlled-port scenarios do not establish external-server interoperability, cancellation notifications or complete AgentScope wrapper parity.

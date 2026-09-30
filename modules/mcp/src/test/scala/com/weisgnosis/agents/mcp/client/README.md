# com.weisgnosis.agents.mcp.client: tests

[McpRequestsSpec](McpRequestsSpec.scala), [McpConnectionSpec](McpConnectionSpec.scala) and [McpClientSpec](McpClientSpec.scala) verify the [client contract](../../../../../../../main/scala/com/weisgnosis/agents/mcp/client/README.md) using scopes, STM, queues and explicit coordination promises. No test starts a network server or process.

| Suite / groups | Independent contracts |
| --- | --- |
| Request registry / four | Out-of-order correlation and remote error data; capacity and scope release; waiting-fiber interruption versus completed replies; owner/terminal closure with complete causes |
| Connection / seven | Immediate and concurrent replies, local send recovery and checked dispatch; cancellation joins cleanup and retains defects; terminal cause preservation during send and reply waiting; closed-parent startup; control writes and directional cancellation; concurrent close and defect replay before borrowed-resource release; EOF, uncorrelated errors and bounded control overflow |
| Initialized client / six | Publication after initialized send; exact-version/error cleanup and invalid-deadline rejection before reader startup; tools capability authority; initialization/call/later-page deadline and cancellation joining held cleanup; ordered bounded discovery through empty pages, opaque cursor cycles, exact/invalid/excess budgets, metadata forwarding and later remote failure without partial publication |

The terminal cause and post-terminal dispatch regressions failed against the unchanged draft before correction. They assert the public ownership contract, not internal Ref layouts. The already-closed-scope case is contract coverage; no failing-before-fix result is claimed for the inspected startup ordering hazard. Existing groups cover shared closure defects without adding getter tests or duplicating setup mechanically.

## Acceptance and next tests

- [x] All seventeen client groups pass in focused verification; registry identities were independently accepted.
- [x] Both reviewers approved the final connection and initialized-client scope and full verification passed; exact identities and evidence are in the [client acceptance](../../../../../../../main/scala/com/weisgnosis/agents/mcp/client/README.md#verification-and-acceptance).
- [ ] Next: Toolkit/ReAct integration; outgoing cancellation notifications remain separate. The transport suite covers actual stdio initialization, list/call and cleanup.
- [ ] Complete applicable upstream method mappings and metadata trust boundaries.

These controlled-port scenarios do not establish external-server interoperability, cancellation notifications or complete AgentScope wrapper parity.

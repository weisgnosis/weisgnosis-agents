# com.weisgnosis.agents.mcp.client: tests

[McpRequestsSpec](McpRequestsSpec.scala) and [McpConnectionSpec](McpConnectionSpec.scala) verify the [client contract](../../../../../../../main/scala/com/weisgnosis/agents/mcp/client/README.md) using scopes, STM, queues and explicit coordination promises. No test starts a network server or process.

| Suite / groups | Independent contracts |
| --- | --- |
| Request registry / four | Out-of-order correlation and remote error data; capacity and scope release; waiting-fiber interruption versus completed replies; owner/terminal closure with complete causes |
| Connection / seven | Immediate and concurrent replies, local send recovery and checked dispatch; cancellation joins cleanup and retains defects; terminal cause preservation during send and reply waiting; closed-parent startup; control writes and directional cancellation; concurrent close and defect replay before borrowed-resource release; EOF, uncorrelated errors and bounded control overflow |

The terminal cause and post-terminal dispatch regressions failed against the unchanged draft before correction. They assert the public ownership contract, not internal Ref layouts. The already-closed-scope case is contract coverage; no failing-before-fix result is claimed for the inspected startup ordering hazard. Existing groups cover shared closure defects without adding getter tests or duplicating setup mechanically.

## Acceptance and next tests

- [x] All eleven client groups pass in focused verification; registry identities were independently accepted.
- [x] Both reviewers approved the final connection scope and full verification passed; exact identities and evidence are in the [client acceptance](../../../../../../../main/scala/com/weisgnosis/agents/mcp/client/README.md#verification-and-acceptance).
- [ ] Next: real transport framing/cleanup and selected-revision initialization, deadlines and cancellation notifications.
- [ ] Discovery/invocation followed by Toolkit/ReAct integration and metadata trust boundaries.

These controlled-port scenarios do not establish network interoperability, initialization ordering, deadlines, cancellation notifications or complete AgentScope wrapper parity.

# com.weisgnosis.agents.mcp.client: tests

[McpRequestsSpec](McpRequestsSpec.scala) contains four grouped tests for the scoped [request-correlation contract](../../../../../../../main/scala/com/weisgnosis/agents/mcp/client/README.md). These tests use local scopes and STM, without transport or send callbacks.

| Test | Coverage |
| --- | --- |
| Runtime correlation | Positive numeric IDs, out-of-order replies, and remote protocol failures preserved as reply data |
| Capacity and leases | Invalid/full admission, missing and mismatched IDs, late/duplicate replies, monotonic allocation, normal abandonment, compound domain causes, and foreign typed failure mapping without losing defects/interruption |
| Await interruption | Cancellation releases capacity and propagates interruption; completed replies survive subsequent failed scope closure |
| Terminal ownership | Owner cleanup, explicit terminal failure, all pending waiters settled, first failure retained, closed admission, and empty-cause fallback |

## Acceptance and next tests

- [x] Four grouped tests pass. The [client acceptance](../../../../../../../main/scala/com/weisgnosis/agents/mcp/client/README.md#verification-and-acceptance) records exact scope, source/test identities, and full verification evidence.
- [x] `path_review` and `current_docs` approved the final source/test identities, including expanded cause-preservation coverage, with no unresolved findings.
- [ ] Next: actual scoped client/transport lifecycle, send/cancel coordination, and connection cleanup.
- [ ] Revision-specific discovery and invocation, followed by Toolkit/ReAct integration and metadata trust boundaries.

No network connectivity, initialization ordering, deadlines, cancellation notifications, or whole-SDK compatibility is exercised. These are local ownership tests; AgentScope Java wrapper behavior remains a separate future mapping.

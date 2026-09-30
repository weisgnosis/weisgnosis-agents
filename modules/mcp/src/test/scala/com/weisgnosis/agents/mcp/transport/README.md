# com.weisgnosis.agents.mcp.transport: tests

The paired suites verify the [framing and stdio contracts](../../../../../../../main/scala/com/weisgnosis/agents/mcp/transport/README.md). They contain seven grouped scenarios, without accessor tests or artificial test-count targets.

| Suite | Coverage |
| --- | --- |
| [McpStdioFramingSpec](McpStdioFramingSpec.scala), four groups | Bytewise UTF-8 and multiple LF/CRLF frames; exact byte budgets and malformed/BOM/blank/EOF rejection; full upstream causes and actual cancellation cleanup; checked outbound bytes and strict surrogate rejection. Same-chunk early take proves later malformed data is not decoded and no extra pull occurs. |
| [McpStdioTransportSpec](McpStdioTransportSpec.scala), three groups | Real concurrent RPC frames and reaping after scope release; direct invalid-send recovery and exclusive reader; invalid configuration, malformed stdout and process EOF; blocked-read, queued-write and active partial-write cancellation with observed child liveness. |

[McpStdioPeer](McpStdioPeer.scala) is a test-only ZIOAppDefault launched with the current JDK and forked MCP test runtime classpath. Its protocol messages report readiness, PID and the first consumed byte of a deliberately blocked write. Tests coordinate with these signals and fiber status, using live clock only for test deadlines; they contain no fixed sleeps or external Python/shell dependency.

The complete-cause fixture uses a direct ZChannel and verifies the unframed baseline before framing. The local ZIO Streams 2.1.26 fromZIO-based failCause constructor reduced the original mixed fixture cause before it reached production code; this was a fixture correction, not a reproduced framing defect. Termination-failure cleanup sequencing was reviewed by inspection; the real-process fixtures do not induce OS permission failures or simulate process-tree containment.

## Acceptance and remaining tests

- [x] `path_review` and `child_factory` independently approved final framing source/test identities with no findings.
- [x] `path_review` and `registry_contract` independently approved final stdio source/spec/peer and build identities with no findings.
- [x] Seven focused groups pass against strict bytes and actual local JVM processes.
- [x] Full repository verification passed; exact identities and evidence are in the [transport acceptance](../../../../../../../main/scala/com/weisgnosis/agents/mcp/transport/README.md#verification-and-acceptance).
- [ ] Next: selected-revision initialization and capability handling, request deadlines and cancellation notifications.
- [ ] HTTP framing/lifecycle and external-server/platform interoperability require separate implementation and evidence.

These tests do not establish whole AgentScope wrapper parity, initialization ordering or complete protocol revision support.

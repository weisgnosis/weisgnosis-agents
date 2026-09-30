# com.weisgnosis.agents.mcp.transport: tests

The paired suites verify the [framing, stdio and HTTP contracts](../../../../../../../main/scala/com/weisgnosis/agents/mcp/transport/README.md). They contain nineteen grouped scenarios, without accessor tests or artificial test-count targets.

| Suite | Coverage |
| --- | --- |
| [McpStreamableHttpTransportSpec](McpStreamableHttpTransportSpec.scala), seven groups | Real loopback initialization SSE with session-bearing ping response under full request admission, JSON listing and calls; configuration/response/session/byte-limit failures without retries; matching completion and held cleanup on cancellation; controlled-client cancellation request-ID/session headers, exact cleanup defect and pre-header ineligibility; public client cancellation preserves an independent typed POST acquisition failure; bounded ingress, reader ownership and exact terminal causes. Duplicate initialization and queued cancellation preserve the admitted exchange. Explicit GET adds real loopback server traffic under occupied request admission, readiness through acknowledgement cleanup, nonterminal EOF/405, exclusive listening through joined cancellation, invalid-message rejection and independent equal-failure preservation. |
| [McpHttpFramingSpec](McpHttpFramingSpec.scala), five groups | Literal ID case/prefix checks, last-valid/inherited/reset/NUL/Unicode semantics, ID-only/comment/empty snapshots and unfinished-block discard; ID budgets and demand preservation; fragmented BOM/Unicode and CR/LF/CRLF events; exact body/content budgets including ignored fields; strict JSON and outbound validation; incomplete-event discard, complete causes, unread malformed suffixes and joined cancellation. |
| [McpStdioFramingSpec](McpStdioFramingSpec.scala), four groups | Bytewise UTF-8 and multiple LF/CRLF frames; exact byte budgets and malformed/BOM/blank/EOF rejection; full upstream causes and actual cancellation cleanup; checked outbound bytes and strict surrogate rejection. Same-chunk early take proves later malformed data is not decoded and no extra pull occurs. |
| [McpStdioTransportSpec](McpStdioTransportSpec.scala), three groups | Real concurrent RPC frames and initialized-client list/call with reaping after scope release; direct invalid-send recovery and exclusive reader; invalid configuration, malformed stdout and process EOF; blocked-read, queued-write and active partial-write cancellation with observed child liveness. |

[McpStdioPeer](McpStdioPeer.scala) is a test-only ZIOAppDefault launched with the current JDK and forked MCP test runtime classpath. Its protocol messages report readiness, PID and the first consumed byte of a deliberately blocked write. Tests coordinate with these signals and fiber status, using live clock only for test deadlines; they contain no fixed sleeps or external Python/shell dependency.

The complete-cause fixture uses a direct ZChannel and verifies the unframed baseline before framing. The local ZIO Streams 2.1.26 fromZIO-based failCause constructor reduced the original mixed fixture cause before it reached production code; this was a fixture correction, not a reproduced framing defect. Termination-failure cleanup sequencing was reviewed by inspection; the real-process fixtures do not induce OS permission failures or simulate process-tree containment.

## Acceptance and remaining tests

- [x] `path_review` and `current_docs` approved the final HTTP POST and explicit GET source/spec identities without remaining findings. The linked transport acceptance records exact hashes, full verification and the reproduced POST and GET regressions; inspected lifecycle races are distinguished from reproduced failures.
- [x] `path_review` and `current_docs` approved HTTP framing source/spec identities with no findings; the transport acceptance records their exact scope and full verification evidence.
- [x] `path_review` and `child_factory` independently approved final framing source/test identities with no findings.
- [x] The stdio ownership baseline and build configuration retain `path_review` and `registry_contract` approval; the issuance extension has renewed `path_review` and `current_docs` approval; `path_review` and `current_docs` approved final spec/peer identities with initialized-client coverage and no findings.
- [x] Nineteen groups pass against strict bytes, actual local JVM processes and a loopback HTTP server.
- [x] Both reviewers approved the current HTTP source/spec including POST acquisition cancellation and unchanged port/stdio issuance boundaries. Full `mcp-sse-events-verify.log` repository verification passed; exact identities and evidence are in the [transport acceptance](../../../../../../../main/scala/com/weisgnosis/agents/mcp/transport/README.md#verification-and-acceptance).
- [x] The real peer enforces initialize/initialized/ready ordering before discovery and invocation. Controlled-port client tests cover joined deadlines.
- [x] Manager integration has separate [client tests](../client/README.md); manual Toolkit binding retains its own tests.
- [ ] Resumption, session DELETE/reinitialization and external-server/platform interoperability require separate contracts and evidence.

These tests do not establish whole AgentScope wrapper parity or complete protocol revision support.

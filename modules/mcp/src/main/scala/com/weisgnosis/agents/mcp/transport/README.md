# com.weisgnosis.agents.mcp.transport

[McpTransport](McpTransport.scala) is a borrowed duplex message port. [McpStdioTransport](McpStdioTransport.scala) implements it for a host-selected direct child process; [McpStdioFraming](McpStdioFraming.scala) provides bounded UTF-8 newline framing. HTTP is not implemented.

## Port and lifetime

The host acquires the transport before [McpConnection](../client/README.md) and releases it after connection cleanup. Incoming emits checked [McpMessage](../protocol/McpMessage.scala) values; outbound envelopes are checked before dispatch. Concurrent sends must not interleave frames. Real-world operations and resource acquisition remain suspended in ZIO.

Connection-owned callers join their I/O cleanup before transport release. Direct port users must keep consuming fibers inside the transport scope; the adapter does not own arbitrary escaped consumer fibers. Consumer code and finalizers must not close their own connection, which joins them.

A generic port may recover from a local send failure only if framing remains intact. Fatal or ambiguous writes must terminate incoming. The stdio implementation enforces the stricter entered-write policy below.

## Stdio contract

- `open(command, maxFrameBytes = 1048576, stderr = Discard)` accepts a host-selected argument vector, rejects an empty/blank executable and nonpositive frame limit, and starts the process inside scoped blocking acquisition. It does not insert a shell. A shell explicitly supplied in the vector remains a host capability.
- `StderrPolicy.Discard | Inherit` redirects diagnostics separately from protocol stdout. No unread stderr pipe, log capture, or stdout merging is used. Environment and working directory are inherited; no overrides are exposed.
- Incoming is single-use. A second consumer is rejected without acquiring the first consumer's release action. EOF, failure, cancellation or early consumption ends that reader and terminates the direct process. IO failures retain an operation label and exception class/message as typed McpError diagnostics.
- Outbound validation/encoding happens before write admission. One semaphore holds the complete frame write and flush. Cancelling a waiter for that permit does not poison the channel. Failure/interruption once writing is admitted publishes the first terminal cause before aborting, so subsequent pipe EOF cannot mask it. Later sends reject the terminated channel.
- Cancellation of native pipe I/O uses an explicit process termination action rather than relying on thread interruption. Cleanup force-terminates and waits for the direct child, then closes all three streams. Stream closes are attempted even when termination fails; concurrent cleanup callers share its complete outcome. Scope release also performs this cleanup.

This is direct-process ownership, not a process-tree sandbox. Descendant processes and inherited handles remain host/server responsibilities. The adapter has no graceful-shutdown timeout, reconnect policy, custom environment/directory support, protocol initialization, request deadlines or cancellation notifications. A selected executable is trusted host configuration, not untrusted model output.

## Framing contract

The [selected stdio specification](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports) uses individual UTF-8 JSON-RPC messages separated by newlines. `decode(maxFrameBytes)` checks the retained raw bytes before appending a segment; completed frames use strict UTF-8 decoding and the checked envelope parser. LF is excluded from the bound; CR counts as a byte and is accepted as JSON whitespace in CRLF input. Empty/blank frames, BOM prefixes, malformed UTF-8/envelopes and nonempty unterminated EOF are rejected. Empty EOF is valid framing.

Split UTF-8 and multiple frames per chunk are supported. Completed messages are emitted individually; downstream early termination does not decode a malformed later frame or pull more input. Nonempty upstream causes pass through unchanged. Retained slices are copied so completed oversized input chunks are not pinned by their suffixes.

`encode(message, maxFrameBytes)` validates the envelope, produces compact JSON with a strict UTF-8 encoder, checks the exact byte length and appends one LF. Bounds count wire bytes, not total JVM memory or the size of an already constructed message/serialized string. The adapter reads bounded chunks of available data and waits for at least one byte rather than waiting for an entire frame-sized buffer.

## Verification and acceptance

- [x] `path_review` approved framing FP/ZIO correctness and `child_factory` approved framing invariants, with no findings at the recorded source/spec identities.
- [x] `path_review` and `registry_contract` approved the unchanged adapter and forked test configuration. `path_review` and `current_docs` approved the final process fixtures/spec, including initialized-client list/call and reaping, with no findings at the recorded identities.
- [x] Four framing groups and three real-process groups pass. They verify chunking, exact limits, strict Unicode, full upstream causes, early stop, direct validation, concurrent frames, one-reader admission, malformed/EOF handling and read/queued-write/active-write cancellation with direct-child reaping.
- [x] `scalafmtAll`, `scalafmtSbt` and `verify` passed with zero compiler warnings or failed tests: core 562, harness 651, MCP 41; total 1,254. Evidence: `mcp-discovery-verify.log`.

| Reviewed file | Git blob identity |
| --- | --- |
| [McpStdioFraming.scala](McpStdioFraming.scala) | `76545199289183041d814f27de01eae0a2c2d33c` |
| [McpStdioFramingSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/mcp/transport/McpStdioFramingSpec.scala) | `83db8decf53febdb299190ac7a9c1237e039ae85` |
| [McpStdioTransport.scala](McpStdioTransport.scala) | `bf37d238e1b6b661e6c684a5c68c85ad3a223609` |
| [McpStdioTransportSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/mcp/transport/McpStdioTransportSpec.scala) | `ffc6fd6b7c6ea614e12bf569b586050bd167344b` |
| [McpStdioPeer.scala](../../../../../../../test/scala/com/weisgnosis/agents/mcp/transport/McpStdioPeer.scala) | `c5bfdbb4902ecfe599efa82f742a3fdae9ec0c77` |
| [build.sbt](../../../../../../../../../../build.sbt) | `53c6ab513dab179a4940047c6eb86aedb768b36a` |

The port/connection acceptance is recorded separately in the [client contract](../client/README.md#verification-and-acceptance). Stdio tests exercise a local JVM peer; they do not establish complete MCP revision support or interoperability with every server/platform.

## Remaining work

- [x] Selected 2025-06-18 client initialization, capability checks, list/call and joined deadlines are implemented and reviewed in the [client contract](../client/README.md).
- [ ] Next: automatic manager registration/removal; manual Toolkit binding has a separate tool-package contract.
- [ ] Add an explicit cancellation-notification policy.
- [ ] Implement the selected HTTP transport with its own limits, cleanup and integration review.
- [ ] Validate additional platform/process configurations before extending the documented ownership claims.

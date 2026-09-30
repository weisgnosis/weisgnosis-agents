# com.weisgnosis.agents.mcp.transport

[McpTransport](McpTransport.scala) is a borrowed duplex message port. [McpStdioTransport](McpStdioTransport.scala) implements it for a host-selected direct child process; [McpStdioFraming](McpStdioFraming.scala) provides bounded UTF-8 newline framing. [McpHttpFraming](McpHttpFraming.scala) supplies bounded JSON/SSE bodies, and [McpStreamableHttpTransport](McpStreamableHttpTransport.scala) supplies the bounded HTTP POST and explicit GET contract below.

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

This is direct-process ownership, not a process-tree sandbox. Descendant processes and inherited handles remain host/server responsibilities. The adapter has no graceful-shutdown timeout, reconnect policy, custom environment/directory support, protocol initialization, request deadlines or self-originated cancellation notifications; it can carry the connection-owned notifications described below. A selected executable is trusted host configuration, not untrusted model output.

## Framing contract

The [selected stdio specification](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports) uses individual UTF-8 JSON-RPC messages separated by newlines. `decode(maxFrameBytes)` checks the retained raw bytes before appending a segment; completed frames use strict UTF-8 decoding and the checked envelope parser. LF is excluded from the bound; CR counts as a byte and is accepted as JSON whitespace in CRLF input. Empty/blank frames, BOM prefixes, malformed UTF-8/envelopes and nonempty unterminated EOF are rejected. Empty EOF is valid framing.

Split UTF-8 and multiple frames per chunk are supported. Completed messages are emitted individually; downstream early termination does not decode a malformed later frame or pull more input. Nonempty upstream causes pass through unchanged. Retained slices are copied so completed oversized input chunks are not pinned by their suffixes.

`encode(message, maxFrameBytes)` validates the envelope, produces compact JSON with a strict UTF-8 encoder, checks the exact byte length and appends one LF. Bounds count wire bytes, not total JVM memory or the size of an already constructed message/serialized string. The adapter reads bounded chunks of available data and waits for at least one byte rather than waiting for an entire frame-sized buffer.

## HTTP body framing

`McpHttpFraming.encode(message, maxBytes = 1048576)` produces checked compact UTF-8 JSON without a delimiter. `json(maxBytes = 1048576)` reads one complete checked envelope at EOF, rejecting empty, multiple-envelope, BOM-prefixed and malformed UTF-8 bodies. Positive limits are validated before input is pulled; the outbound limit is checked before and after UTF-8 encoding.

`sse(maxBodyBytes = 8L * 1024 * 1024, maxEventBytes = 1048576)` handles LF, CRLF and bare CR across chunk boundaries, strips one stream-leading BOM and combines `data` fields with newlines. Comments and other fields do not produce messages. A blank line dispatches a data event through the checked envelope decoder; `[DONE]` has no special meaning. Valid unfinished events are discarded at EOF, while an unfinished line must still contain valid UTF-8. Rejection of malformed UTF-8 is an explicit local policy, stricter than browser replacement decoding in the [SSE standard](https://html.spec.whatwg.org/multipage/server-sent-events.html#event-stream-interpretation).

The body limit counts every consumed byte. The event limit counts raw line content, including comments, unknown fields and BOM bytes, excluding CR/LF separators; it resets at each blank line. CR dispatches immediately without waiting for optional LF. Retained segments are bounded before copying. Emitting one event precedes inspecting later bytes in the same chunk, so early downstream completion does not decode an unread suffix. Both decoders preserve complete upstream failure causes and join owned stream cleanup on cancellation. These bounds do not cap an already allocated upstream chunk or total JVM memory.

This component performs no HTTP requests, session negotiation, media-type dispatch, retries or cancellation notifications. It is a transport prerequisite, not an HTTP client or a complete protocol implementation.

## HTTP contract

`McpStreamableHttpTransport.open(http, endpoint, headers = Headers.empty, limits = McpHttpLimits())` returns `ZIO[Scope, AgentError, McpStreamableHttpTransport]` and borrows the injected zio-http Client. The acquired `McpStreamableHttpTransport` extends `McpTransport` with an explicit `listen` operation. The absolute HTTP(S) endpoint cannot contain user information or a fragment. Header names must be ASCII tokens and values visible ASCII or spaces; transport-controlled headers cannot be overridden. Invalid configuration performs no network request.

Every message uses a separate POST with JSON content type, JSON/SSE Accept values and the selected `2025-06-18` protocol header. Requests accept one matching JSON reply or an SSE stream containing server requests/notifications before the matching reply. Matching completion closes the response scope without demanding an open tail. A premature SSE EOF or mismatched reply fails that send. Notifications and responses require exactly status 202 with an empty body. For JSON-RPC requests, response Content-Type must appear once and be JSON or SSE; only absent parameters or exact case-insensitive `charset=utf-8` / `charset="utf-8"` parameters are accepted. This parameter whitelist is a local compatibility constraint.

The first admitted initialize exchange can capture one nonempty visible-ASCII Mcp-Session-Id header before its SSE messages are delivered. Later POSTs carry that session ID; conflicting or malformed session headers terminate the transport. Duplicate initialize requests fail before sending, and rejected or queued-cancelled initializers cannot poison an existing session. Failure or cancellation after initialization admission terminates it, including response cleanup failures. Arbitrary stateless raw port requests remain possible; McpClient owns protocol initialization order, negotiated-version validation and capability checks.

A session-bearing 404 terminates the old transport. The host must acquire a fresh transport and initialized client; the failed operation is never replayed automatically. A 404 without a session is an ordinary HTTP rejection. Other request failures remain local unless they violate shared session state or overflow ingress. HTTP error bodies are not collected; status diagnostics remain explicit. Foreign network/body failures retain operation, exception class and message as McpError; existing AgentError values, full failure-cause structure and interruption are preserved.

Each send owns a fresh response scope and joins it before returning. Request admission has `maxConcurrentRequests` permits and control messages have a separate single permit, allowing a server ping response while ordinary requests are held open. The host HTTP pool must also permit this concurrency. Incoming is single-use; a rejected second consumer does not close the first. Bounded ingress uses immediate overflow failure rather than blocking producers. Terminal publication closes admission and discards queued messages, without joining its own producer. Owner closure and incoming completion perform local termination. The owning connection must join its send workers before transport release; escaped direct-caller fibers are not supervised by this adapter.

Default limits are 1,048,576 outbound JSON bytes, 1,048,576 JSON response bytes, 8,388,608 SSE response bytes, 1,048,576 SSE event-content bytes, 64 queued messages and eight concurrent requests. All must be positive. Framing bounds count consumed bytes; queue/admission bounds count entries. McpClient supplies joined operation deadlines; the raw port adds no timeout.

This is a bounded adaptation of the [2025-06-18 Streamable HTTP specification](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports). It does not implement event resumption, legacy HTTP+SSE fallback, session DELETE, automatic reinitialization, authentication discovery or token refresh. Owner cleanup is local only. The adapter does not add retries or redirects; the host must not inject an HTTP client configured to perform them. Full transport conformance and external-server interoperability remain unclaimed.

## Explicit GET listening

The host runs `listen: IO[AgentError, McpHttpListenResult]` inside the transport lifetime, normally after `McpClient.open`. It is never started automatically. Admission requires a matching initialize Success followed by a successful initialized notification POST and completion of that response's cleanup. This transport readiness check does not replace the client's version/result/capability validation. Early or duplicate listener attempts fail without terminating the active transport.

One listener remains admitted through joined response cleanup. GET uses the configured endpoint and authorization headers, selected protocol version, optional session ID and SSE Accept header, independently of POST request permits. `Last-Event-ID` is transport-controlled and rejected in host headers because resumption is unsupported. Only server requests and notifications enter bounded ingress; JSON-RPC responses on GET are rejected. Existing strict SSE decoding, body/event byte limits and immediate ingress-overflow failure apply.

Clean EOF returns `Ended`; HTTP 405 returns `Unsupported`. Both permit a new explicit GET after cleanup and leave POST usable. Caller interruption joins the GET action and cleanup before releasing listener admission; interruption alone is nonterminal. Independent acquisition/body/cleanup failures terminate the transport and preserve complete causes. A terminal signal interrupts the active GET action and joins it within the listening effect. The host must own and join that effect before releasing the transport; transport closure does not supervise arbitrarily escaped caller fibers. No automatic restart, delivery replay, session recovery or listening deadline is added.

Migration: existing callers accepting `McpTransport` can use the narrowed `open` return type unchanged. Hosts opting into GET retain the subtype and explicitly scope the listener. `McpHttpListenResult` distinguishes ordinary stream end from a server that does not support listening.

## Cancellation publication boundary

`send(message, onIssued)` adds a bounded local publication callback to the borrowed port. Its default calls the original send and signals only on success, preserving existing implementers. Stdio overrides signal after a full validated frame write/flush. HTTP overrides signal after accepted status and validated session headers, before consuming the body. Pre-header interruption stays ineligible; this deliberately omits some optional notifications rather than treating local admission as a sent request.

[McpConnection](../client/README.md#outbound-cancellation-contract) originates bounded best-effort notifications under the [selected cancellation specification](https://modelcontextprotocol.io/specification/2025-06-18/basic/utilities/cancellation), never for initialize. The transport does not originate notifications, and callback publication does not acknowledge peer delivery or stopping. HTTP cleanup can delay enqueue. The connection retains the notification writer independently of the cancelled request and joins it before transport release; no transport-wide guarantee orders notification delivery after all request finalizers.

## Verification and acceptance

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for the HTTP source/spec identities below, including explicit GET listening, with no remaining findings. Seven grouped tests cover POST behavior and cancellation publication plus real loopback GET/control routing under occupied request admission, readiness through acknowledgement cleanup, EOF/405 reuse, held cancellation cleanup and independent failure causes.
- [x] `mcp-get-listener-red.log` records six passing groups and one failing group against source `cf6841cf8030672c9ea3012ed7d215185482dda9` and the unchanged final spec below: two independent equal typed failures were expected, but only one survived. The correction separates GET-originated failures from terminal signalling and masks error translation around restored, interruptible acquisition. Removing value-based deduplication alone did not fix the interruption-time loss. The unchanged regression passes; an earlier fixture failure is not defect evidence.
- [x] `mcp-http-transport-red.log` records two failing groups against defective source blob `088d057c91673b87f90ea6d93b3b348627d55bc7`: incoming lost the defect branch of a compound terminal Cause, and rejected duplicate initialization poisoned a valid session. Both regressions pass after correction. Terminal publication and reader acquisition races were corrected by inspection; no reproduced red run is claimed for those races.
- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for HTTP framing at the exact source/spec identities below, with no findings. Four grouped tests cover line/Unicode boundaries, exact budgets, checked JSON, unread suffixes, complete causes and joined cancellation. Acceptance excludes HTTP requests and session lifecycle.
- [x] `path_review` approved framing FP/ZIO correctness and `child_factory` approved framing invariants, with no findings at the recorded source/spec identities.
- [x] The direct-process ownership baseline and forked test configuration retain `path_review` and `registry_contract` approval; the current issuance callback extension has separate approval below. `path_review` and `current_docs` approved the final process fixtures/spec, including initialized-client list/call and reaping, with no findings at the recorded identities.
- [x] Four framing groups and three real-process groups pass. They verify chunking, exact limits, strict Unicode, full upstream causes, early stop, direct validation, concurrent frames, one-reader admission, malformed/EOF handling and read/queued-write/active-write cancellation with direct-child reaping.
- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for the current port, stdio and HTTP issuance boundaries and updated HTTP spec, with no findings at the identities below and in the linked client table. Full `scalafmtAll scalafmtSbt verify` passed in `mcp-get-listener-verify.log`, with zero compiler warnings or failures; current totals belong to the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).

| Reviewed file | Git blob identity |
| --- | --- |
| [McpStreamableHttpTransport.scala](McpStreamableHttpTransport.scala) | `b4cc28dd46328e9542e1e2441111eccdeec046e9` |
| [McpStreamableHttpTransportSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/mcp/transport/McpStreamableHttpTransportSpec.scala) | `2881f525e93e4685414d1d9bf67edeef58f038e5` |
| [McpHttpFraming.scala](McpHttpFraming.scala) | `4c4a982ccd744e6cbf77bb7febab4253ae81cbf9` |
| [McpHttpFramingSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/mcp/transport/McpHttpFramingSpec.scala) | `2067fccfb8018c753b5a259e3cd40254f2cc2de9` |
| [McpStdioFraming.scala](McpStdioFraming.scala) | `76545199289183041d814f27de01eae0a2c2d33c` |
| [McpStdioFramingSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/mcp/transport/McpStdioFramingSpec.scala) | `83db8decf53febdb299190ac7a9c1237e039ae85` |
| [McpStdioTransport.scala](McpStdioTransport.scala) | `8b6aad5870714b280e6a6a693cb759cbf98c8c8a` |
| [McpStdioTransportSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/mcp/transport/McpStdioTransportSpec.scala) | `ffc6fd6b7c6ea614e12bf569b586050bd167344b` |
| [McpStdioPeer.scala](../../../../../../../test/scala/com/weisgnosis/agents/mcp/transport/McpStdioPeer.scala) | `c5bfdbb4902ecfe599efa82f742a3fdae9ec0c77` |
| [build.sbt](../../../../../../../../../../build.sbt) | `53c6ab513dab179a4940047c6eb86aedb768b36a` |

The port/connection acceptance is recorded separately in the [client contract](../client/README.md#verification-and-acceptance). Stdio tests exercise a local JVM peer; they do not establish complete MCP revision support or interoperability with every server/platform.

## Remaining work

- [x] Selected 2025-06-18 client initialization, capability checks, list/call and joined deadlines are implemented and reviewed in the [client contract](../client/README.md).
- [x] Bounded manager registration/removal and host selection have separate [client acceptance](../client/README.md#scoped-manager-contract); manual Toolkit binding retains its separate tool-package contract.
- [x] Support the connection-owned optional outbound cancellation policy, with conservative issuance signals and no delivery acknowledgement.
- [x] Implement and independently review the selected HTTP POST and explicit GET contract with bounded bodies, scoped cleanup and loopback integration.
- [ ] Next: test bounded POST acquisition cancellation and correct it only if a defect is confirmed; its error mapping remains inside restored acquisition.
- [ ] Complete separately specified event resumption, session DELETE/reinitialization and external-server interoperability.
- [ ] Validate additional platform/process configurations before extending the documented ownership claims.

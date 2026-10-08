# com.weisgnosis.agents.mcp.client

The current [supported server revision agreement](../transport/README.md#supported-server-revision-agreement) adds opt-in checked revision membership, one scoped negotiation owner, atomic correlated initialization and immediate framing/header transitions. Exact-version defaults remain; legacy POST additionally carries the revision header. This does not establish complete original-plan parity.

Toolkit construction and selection follow the [checked registry identity and migration contract](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#checked-registry-identity). The linked contract describes migration; existing lifecycle and permission contracts remain in force.

[McpClient](McpClient.scala) provides initialized tool discovery/invocation (2025-06-18 by default) and resource access over a borrowed transport. [McpRequests](McpRequests.scala) owns bounded request correlation; [McpConnection](McpConnection.scala) owns scoped RPC operations. [McpClientManager](McpClientManager.scala) owns bounded acquisition, host-selected registration and joined removal. The [protocol package](../protocol/README.md) checks envelopes and payloads, and the [stdio adapter](../transport/McpStdioTransport.scala) supplies direct-process connectivity. Manual Toolkit binding has a separate tool-package contract. HTTP POST and explicit GET connectivity has a separate [transport contract](../transport/README.md#http-contract).

The [legacy/revision contract](../transport/README.md#legacy-http-sse-and-explicit-revision-selection) supplies explicit initialization selection.

The connection now consumes bounded packets and retains server response groups for selected March transports. [Batch contracts](../transport/README.md#march-2025-batch-packets) cover that changed reader/writer behavior.

## Connection-local original-definition catalogue

`acquireToolCatalogue(maxPages, maxTools, meta)` explicitly acquires the first complete bounded tool discovery result. `open` still performs only initialization, and `discoverTools` remains a live request that does not update the retained catalogue. Every acquisition call requires positive limits before inspecting state or sending requests. Limits constrain the first successful traversal; a later call with smaller positive limits reuses that snapshot without filtering or revalidation. Metadata belongs to the first successful traversal, not to later reuse. Concurrent acquisitions serialize; failed or cancelled discovery publishes no partial snapshot and permits a subsequent attempt. Each page uses the existing joined request deadline; there is no additional total traversal deadline.

`cachedTools: UIO[Chunk[McpToolDefinition]]` reads ordered original definitions, including duplicate names. `cachedTool(name): UIO[Option[McpToolDefinition]]` returns the last occurrence of that name, matching upstream map insertion. Input/output schemas, title, description, annotations, metadata and extension ASTs remain unchanged. These effects perform no network access. Before acquisition and after closure the snapshot is empty and lookup returns None; an acquired empty catalogue is nevertheless a completed acquisition and is not rediscovered.

Explicit close atomically marks the catalogue closed before joined connection cleanup. Natural scope release also clears it before the existing connection finalizer. Close does not wait on the acquisition semaphore, and an in-flight traversal cannot republish after closure. The connection remains the single owner of natural RPC cleanup, preventing a second finalizer from replaying its cleanup defects. The host still owns the borrowed transport. Original remote definitions are descriptive data: local Toolkit selection, aliases, fixed arguments, permissions and duplicate-registration rejection remain separate authorities. There is no notification invalidation, automatic refresh, reconnect or background task.

The pinned AgentScope Java reference caches original definitions during sync/async wrapper initialization and leaves that cache unchanged during later listTools calls. Scala exposes explicit bounded acquisition after scoped initialization instead of changing the existing open contract. The following are behavioral adaptations, not claims of whole-wrapper assertion equivalence:

| Pinned behavior | Scala evidence |
| --- | --- |
| Sync/async testInitialize_Success and testGetCachedTool | Paginated original-definition table checks complete definitions, multiple and missing lookups, and last-name selection. Automatic discovery during open is intentionally replaced by explicit acquisition. |
| Sync/async testInitialize_AlreadyInitialized; wrapper testInitialize_Idempotent | Concurrent and repeated acquisition performs one successful traversal, including an empty result. Scoped open has no repeated handshake operation. |
| Wrapper testCachedTools_EmptyByDefault, testGetCachedTool_NonExistent, testGetCachedTool_Exists and testCachedTools_MultipleTools | Empty-before-acquisition and exact retained snapshot/lookups; mutable insertion and null are replaced by checked discovery and Option. |
| Sync/async testListTools_Success | Existing live discovery remains independent; a changed remote list does not refresh the retained definitions. |
| Sync/async testClose_Success; wrapper testClose and testClose_MultipleCallsSafe | Explicit and natural close clear the snapshot; repeated close is safe. Held send cleanup proves close waits and acquisition cannot resurrect state. Java fallback-close SDK mechanics are not reproduced. |
| Wrapper testConcurrentAccess_CachedTools | The Java test inserts and reads one entry without concurrent workers. Scala additionally checks two actual concurrent acquisition fibers; it does not reproduce ConcurrentHashMap implementation assertions. |

## Resource client contract

Resource access implements the original HLD's resource-server requirement for the selected revision, following the [MCP resource specification](https://modelcontextprotocol.io/specification/2025-06-18/server/resources).

`listResourcesPage(cursor, meta)` and `listResourceTemplatesPage(cursor, meta)` return checked `McpResourcePage` values. `readResource(uri, meta)` returns ordered text/binary contents with metadata and extension fields. These methods require an advertised `resources` capability; unknown experimental fields never grant it. URI selectors and templates remain opaque remote data, never local file access or implicit context installation. The host selects resources and owns permission checks before making requests.

`discoverResources(maxPages, maxResources, meta)` and `discoverResourceTemplates(maxPages, maxTemplates, meta)` require positive bounds, traverse sequentially, preserve ordered duplicates and opaque cursors (including empty cursors), reject repeated cursors and exceeded page/item bounds, and publish no partial result on failure. Each page/read/subscription operation uses the existing request deadline and joined cancellation. Remote errors retain code, message and absent-versus-present data through `McpRemoteError`.

`subscribeResource(uri, meta)` and `unsubscribeResource(uri, meta)` additionally require `resources.subscribe = true`. The host explicitly unsubscribes while the client remains open. Client lifetime owns local observation, not remote work cancellation or a durable subscription registry.

Acquire `observeResourceChanges` within a `Scope` before subscribing to register a local observer without a race. Its returned stream receives typed `Updated` and `ListChanged` hints; the convenience `resourceChanges` stream registers when consumed. Only notifications enabled by the advertised capability flags are delivered. There is no replay for late observers and no resource cache. Each observer has a bounded 64-event buffer. Overflow at any observer permanently fails all current and future resource observers for this client; ordinary RPC correlation remains usable. A host requiring new notification observation must acquire a fresh client. Malformed recognized resource notifications likewise fail observers. Connection termination preserves its cause in the observation stream. Explicit client closure fails current observation with the connection-closed cause; releasing an observer's own scope makes later use fail with an observer-closed error instead of waiting forever. Client/scope closure releases both the connection and observation resources. Host HTTP applications still run the transport's explicit GET listener to receive server-initiated traffic.

The connection's stack-safe child join removes only interruption leaves introduced by the exact owning fiber, retaining unrelated cancellation and independent failures/cleanup defects with their traces, annotations and log spans.

`McpServerCapabilities` adds a trailing defaulted `resources` field. Existing two-argument construction remains source-compatible; positional patterns and dependent binaries require rebuilding. Shared resource contents and annotation projection reuse the existing checked call-result helpers without adding an unchecked implicit codec.

Manager integration checks complete call-ID/result-state pairs rather than schema-admission arrival order; schema rejection precedes local execution.

Resource observation is separate from transport revision selection and tool-result projection, whose contracts are documented in their packages. It does not provide durable subscriptions or exhaustive wrapper parity.

## Scoped manager contract

[McpClientManager](McpClientManager.scala) exposes `make(maxClients = 16, maxTools = 256, groups = ToolGroups.empty): ZIO[Scope, AgentError.InvalidConfiguration, McpClientManager]` to acquire a bounded owner. `register(name, acquire, maxPages, select): IO[AgentError, Unit]` accepts a `ZIO[Scope, AgentError, McpClient]` acquisition and a `McpToolDefinition => Either[AgentError, Option[ToolDef]]` selector. It performs bounded discovery and publishes immutable Toolkit snapshots. The host chooses local names, schemas and read-only/concurrency declarations; server annotation hints do not grant authority. Prefixes and filtering can be expressed by the selector without changing the remote invocation name.

Registration publishes the complete client/tool set atomically after capacity and collision checks. `maxClients` counts Pending, Ready and Removing entries; published tool names and count remain reserved through removal. Discovery counts every returned definition against `maxTools`; remote duplicates are not deduplicated, and selected local names must be unique. `toolkit: UIO[Toolkit]` returns the current immutable snapshot and `names: UIO[Chunk[String]]` exposes Ready registrations only. `remove(name): UIO[McpClientRemoval]` returns Removed or Missing after joined cleanup; `close: UIO[Unit]` joins manager cleanup. Previously returned snapshots retain their original bindings, whose calls fail after removal rather than routing to a later registration with the same name.

`registerBindings(name, acquire, maxPages, select)` selects `Option[McpToolBinding]`, pairing ToolDef with an explicit metadata policy and checked argument defaults. It consumes the selected binding without changing its schema. `McpToolBinding.withPresets` supplies checked root schema exclusion; a configuration failure returned by the selector publishes no partial binding set and releases acquired resources through the existing ownership kernel. Selecting metadata runs no resolver: the effect runs only at tool invocation if the fixed binding and live client restriction allow it. See the [preset](../tool/README.md#preset-arguments-and-schema-projection) and [metadata contracts](../tool/README.md#explicit-request-metadata).

The acquisition must place owned transport resources in its supplied scope. The manager closes the returned client before releasing those resources. Registering an externally initialized client does not extend its original lifetime, and registration still authorizes the manager to close it. Copying a Toolkit snapshot never transfers ownership. Acquisitions, selectors and owned finalizers must not remove their own registration or close their manager, because closure joins that work.

Host selection explicitly retains, omits or overrides remote outputSchema in ToolDef; the manager does not adopt it automatically. The [core schema contract](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#descriptive-output-schemas) records codec semantics, current positional-pattern/Product migration and required binary rebuilding.

Selected bindings can also attach core fixed arguments, prepared before runtime authorization rather than merged as defaults afterward; the [fixed binding contract](../tool/README.md#fixed-arguments) defines the distinction and current acceptance.

This scope includes checked shallow defaults and root properties/required exclusion through selected bindings. Core/harness provide selected skill loading, child snapshots and opt-in Plan admission as separate host-installed capabilities. Remaining ambient metadata propagation, broader schema rewriting/evaluation and notification invalidation are unfinished; explicit original-definition acquisition is documented above. It does not make discovery an atomic server snapshot or broaden the selected 2025-06-18 protocol support. The [parity audit](../../../../../../../../../../docs/architecture/test-parity.md#mcp-protocol-and-connection-boundaries) maps bounded adaptations and intentional differences.

Failed or cancelled initialization joins acquired resources and preserves the original cause together with independent cleanup failures, without duplicating an already reported initialization cause.

## Explicit host recovery after session expiry

A direct client/transport failure containing AgentError.McpSessionExpired identifies HTTP 404 for a previously captured session, without exposing its token. It does not mean the failed remote operation was never executed; its outcome may be unknown. The transport terminates the old session path without replaying the failed call. Toolkit/ReAct may normalize the leaf into an Error observation, so hosts must not assume every model-facing tool caller receives a typed expiry value.

A host can await manager.remove(name), then register the name with a fresh transport/client acquisition. Removal joins the old client's owned work and resource cleanup. Fresh acquisition repeats initialization, bounded discovery and the host selector; remote definitions and selected aliases/schema/groups can change. Old Toolkit/installation snapshots retain their old clients and never retarget to the replacement. The host must obtain and explicitly install a new snapshot.

This sequence uses existing APIs and is not an atomic replace operation. Hosts serialize same-name maintenance; another registration can win between remove and register. Acquisition or selection failure leaves no replacement registration and does not restore the old one. There is no automatic failed-call replay, reconnect worker, in-place session mutation or durable recovery. Automatic cursorless reconnect and unowned subscriptions are intentional differences from the delegated SDK behavior, not pending replacement APIs. Hosts own serialization, fresh authentication/configuration and new snapshot installation.

## Grouped installation

`make` accepts an immutable `groups: ToolGroups = ToolGroups.empty` catalogue. Declarations must have empty membership sets: the manager derives its memberships from published registrations. `registerBindingsInGroup(name, acquire, maxPages, group, select)` requires a host-declared group before acquisition. Every selected local alias belongs to that group; remote tool names remain invocation targets and confer no group authority. Invalid aliases or failed selection publish no partial registration.

`installation: IO[AgentError.InvalidConfiguration, McpClientInstallation]` reads manager state once and derives both the unfiltered Toolkit and its ToolGroups from the same Ready registrations. Checked membership construction remains fallible rather than hiding an invariant behind an unchecked extraction. Pending, Removing and Closed registrations contribute neither tools nor memberships. Group declarations and their configured activation survive removal, including groups with no remaining tools.

The host passes both installation fields to ReActAgent. The existing `toolkit` view remains unfiltered for compatibility. Neither view refreshes an already constructed agent, restores durable activation, transfers ownership or retargets an old binding after re-registration. Multiple clients can share a group; removal affects only the removed client's aliases. The existing retained-name/capacity reservations and joined cleanup remain authoritative.

Custom managers inherit an ungrouped installation from their existing toolkit and a typed unsupported grouped-registration result that does not acquire a client. Existing registration calls remain ungrouped. The appended defaulted make parameter preserves ordinary source calls; dependent binaries require rebuilding.

Group activation is supplied explicitly by the host; installation does not restore durable activation or install ambient context propagation.

## Independent deadlines

`McpClient.Deadlines.make(initialization, request)` is a pure checked constructor: both durations must be positive and finite. The additive `open(transport, clientInfo, deadlines)` overload has no defaults. The existing Duration overload, its JVM signature, `30.seconds` default and uniform behavior remain unchanged; its invalid-configuration message is preserved. No configuration codec or case-class arity changes.

The initialization budget includes the initialize exchange, validated result and `notifications/initialized` send. The request budget applies separately to each list/call operation and each discovery page, not to the whole traversal. A timeout triggers cancellation; returning still joins owned cleanup, which may exceed either budget. Caller interruption and independent cleanup causes remain preserved. Bound-tool metadata resolution precedes the client request and remains outside that request budget. The host still owns the borrowed transport and can supply either overload through the existing manager acquisition effect. No retry, cache, reconnect or transport ownership change is introduced.

The existing McpClientSpec groups exercise independent initialization/request budgets, initialized-notification timing, later discovery pages and joined cleanup alongside the unchanged uniform overload. This is bounded adaptation of pinned McpClientBuilderTest.testTimeout (141) and testInitializationTimeout (149), whose Java assertions only check builder construction, not whole builder-suite parity.

The paired client tests cover separate deadlines and held cleanup; the catalogue does not change deadline semantics.

## Builder applicability inventory

The [finite builder inventory](../../../../../../../../../../docs/architecture/test-parity.md#finite-core-mcp-builder-applicability) accounts for every one of the 120 pinned McpClientBuilderTest declarations by exact name/line. It distinguishes available typed configuration and lifecycle behavior from Java mutable/fluent/sync/null/helper assertion shapes and selected-profile differences. Optional elicitation, arbitrary protocol negotiation, dynamic customization and broader cache/invalidation contracts remain unfinished; no whole-builder or whole-MCP parity is claimed.


## Initialized client contract

- `open(transport, clientInfo, deadline = 30.seconds)` creates an owned connection in the caller's scope. The host acquires the transport first. The deadline must be positive and finite; invalid values start no reader. Initialization advertises empty optional client capabilities, requires the exact selected revision, and publishes the client only after `notifications/initialized` finishes sending. Failed or interrupted initialization closes and joins the connection immediately.
- `initialization` exposes checked server information and capabilities. `listToolsPage(cursor, meta)` returns one ordered page, including its next cursor. `callTool(name, arguments, meta)` preserves checked content and metadata. Both require the actual advertised tools capability; uninterpreted extensions confer no authority.
- `setToolMetadataPropagation(Allow | Omit)` changes the live restriction for bound tools only; its initial value is Allow, while bindings default to Omit. Each invocation reads one snapshot before its effectful resolver. Changing mode cannot revoke a resolver already started, even before send. Explicit list/call metadata bypasses this restriction. Resolver lifetime/deadline belongs to the invocation host, not the subsequent client request deadline.
- Initialization uses its selected budget; each list/call uses the selected request budget. Expiration triggers cancellation and returns `TimeoutError` after owned cleanup finishes; cleanup can exceed the deadline. Caller interruption also joins cleanup and retains independent failures and defects. Eligible interrupted requests may enqueue the best-effort outbound notification described below; no delivery acknowledgement or retry is implied.
- Remote JSON-RPC failures become `McpRemoteError(code, message, data)`. `McpErrorData.Absent` and `Present(Json.Null)` remain distinct through the domain codec. A tool's `isError` result is valid `McpCallResult` data, not a protocol failure. `close` joins the connection while the transport remains host-owned.

`discoverTools(maxPages, maxTools, meta)` performs an explicitly bounded sequential traversal from the first page. Both limits must be positive before any discovery request. It preserves ordered duplicate definitions and counts every definition against the tool limit; an exact tool limit can still be followed by empty pages. The [selected pagination contract](https://modelcontextprotocol.io/specification/2025-06-18/server/utilities/pagination) treats cursors as opaque. Cursors, including empty strings, are forwarded unchanged. Repeated cursors, excess tools, or a continuation on the final permitted page fail before another request. Only terminal success publishes the complete catalogue; later errors and cancellation yield no partial value. Each page uses the selected request deadline with joined cleanup. Limits count pages and definitions, not bytes, and cleanup can exceed the deadlines.

The flattened catalogue omits page envelope metadata; use `listToolsPage` when that metadata is needed. Discovery is not an atomic server snapshot. This live operation does not update the separate explicitly acquired catalogue. There is no notification invalidation, schema evaluation, reconnect or Toolkit installation. The client does not implement the different 2026-07-28 lifecycle.

## API migration

`installation` and `registerBindingsInGroup` are concrete additions for custom managers: the default installation has empty groups, and grouped registration fails before acquisition unless overridden. Live `make` appends default-empty group declarations and rejects prepopulated memberships. Existing `register`, `registerBindings` and `toolkit` retain ungrouped/unfiltered behavior; the host explicitly supplies both installation fields to ReAct. Source calls using defaults remain valid; rebuild dependent binaries.

Existing `register` and `McpTool.bind` call sites retain metadata omission and empty presets. `registerBindings` is additive and concrete on the manager trait: custom managers compile unchanged and receive a typed unsupported failure without running acquisition or selection until they override it. McpToolBinding has four fields: definition, metadata, presets and fixedArguments. Existing construction/named copy calls can omit the empty argument policies; positional patterns/Product must handle four fields and dependent binaries require rebuilding. No core context bag is added. Fixed binding policy replaces a mutable per-tool switch.

Custom `McpConnection` implementations must implement the new checked `request(builder)` overload: allocate and register an ID before invoking the builder, reject a substituted ID before sending, and clean up rejected requests. The built-in connection suspends the builder. Exhaustive `AgentError` matches must handle the additive `McpRemoteError` leaf; its tagged data codec is a domain representation, not the JSON-RPC wire format.

## Request ownership contract

- `make(capacity = 64)` acquires an owner-scoped registry. Capacity must be positive. It bounds pending entries, not retained payload bytes or finalizers in caller scopes.
- `register` requires a fresh request scope around registration, checked request construction, sending, and awaiting. It returns a `PendingRequest` with `id` and `await`; the ID is allocated before any send. Numeric IDs increase from 1 through `Long.MaxValue`, are never reused, and fail on exhaustion rather than wrapping. Capacity, closed admission, and exhaustion produce local `AgentError.McpError` failures.
- `complete(reply)` expects an already validated `Success | Failure` from the caller's protocol boundary. Removal and promise completion are one STM transaction. Remote JSON-RPC failures remain reply data. `Delivered` means that completion won; `UnknownId` covers unmatched, late, duplicate, and abandoned identities without tombstones. An error without an ID yields `MissingId` while open. Closed admission takes precedence and yields `Closed`, including for a missing ID.
- Request-scope closure abandons an unfinished entry and settles its waiters. A normal lease close yields a local `McpError`; a failed lease preserves domain failures and the complete defect/interruption/composition structure, mapping only foreign typed failure leaves to `McpError`. Interrupting `await` atomically abandons the slot and propagates interruption. An already completed reply survives later lease closure.
- `failAll(cause)` atomically closes admission and settles all remaining requests. Nonempty causes are preserved in full; an empty cause becomes a local registry-closed `McpError`. First completion or terminal failure wins. `close`, including automatic owner-scope cleanup, uses that local closed error and does not forward the owner scope's exit.

The host must keep each request lease short-lived. This primitive neither sends cancellation notifications nor supplies deadlines, retries, transport cleanup, or method/version lifecycle enforcement. Authentication and server trust remain explicit host responsibilities.

## Connection ownership contract

- `make(transport, capacity = 64, controlCapacity = 16)` registers cleanup in the caller's scope before starting the reader and control writer. Both bounds must be positive. A closed acquiring scope starts no transport activity. The host acquires the borrowed transport first and releases it after connection cleanup.
- `request(method, params)` and `request(builder)` register a runtime ID before checked sending and awaiting; builders cannot replace that ID. `notify(notification)` validates and sends without a request ID. Both occupy bounded operation slots through send cleanup; an early reply cannot free a slot while sending is still running. Every operation owns a fresh scope and supervised action. Cancellation joins that action and retains failure/cleanup causes.
- Registry reservation shares the admission gate with terminal closure. An admitted worker either reserves before closure or waits interruptibly outside the gate for its supervisor to deliver termination. No builder, network operation, abandonment callback or join runs under that gate; a late reservation cannot introduce an unrelated registry-closed failure.
- Remote `Failure` replies remain data. Unknown, duplicate and late IDs are ignored. Local send failure ends that operation; the transport must terminate incoming after fatal or framing-ambiguous writes. No retry or partial-write recovery is implied.
- EOF, incoming failure, an ID-less remote error, control-write failure or server-response queue overflow publishes the first terminal cause, closes admission and signals the reader/writer to stop. Owned operation workers observe termination independently of their callers and join send cleanup. First observed operation completion versus termination wins; independent losing failures survive, while a terminal cause already delivered by the registry is not duplicated.
- Server `ping` receives the same ID with an empty object result; other server requests receive `-32601`. A separate bounded nonblocking queue and one writer keep control sending out of reply correlation. Incoming notifications are ignored, including cancellation: server-originated request IDs cannot cancel client-originated requests merely by matching their values.
- `close` and owner-scope cleanup join operations, reader and writer before the borrowed transport can be released. Concurrent/repeated closers share one completed outcome. Close reports defects in retained operation/service exits, including operational reader defects already observed by a request; typed operational failures remain request failures. Sends, incoming stream code and their finalizers must not close their own connection, because closure joins them.

These bounds count operations and queued messages, not bytes. This is a raw RPC foundation: no initialization ordering, capability enforcement, deadlines, progress reporting, reconnects or subscriptions are provided. The separate stdio adapter supplies bounded wire framing and direct-process ownership.

## Outbound cancellation contract

The [2025-06-18 cancellation specification](https://modelcontextprotocol.io/specification/2025-06-18/basic/utilities/cancellation) permits optional notifications only for previously issued requests in the same direction that are believed pending, and excludes client cancellation of initialize. The connection enqueues `notifications/cancelled` with the original `requestId` only when interruption wins atomic abandonment of an eligible outbound request. Reply or terminal completion winning first suppresses the observer; late replies are ignored. Incoming cancellation remains ignored and cannot cancel an outbound request with a matching ID.

Issuance eligibility is transport-specific: stdio signals after the complete frame flush, the source-compatible transport fallback signals after successful send, and HTTP signals after validated response headers but before body consumption. Pre-header HTTP interruption and interrupted legacy sends are ineligible. These conservative boundaries are not delivery or remote-stop acknowledgements. HTTP response cleanup may delay enqueue; no universal notification-after-all-cleanup ordering is promised for custom ports.

The abandonment observer runs outside STM after removal and full-cause settlement, at most once. A bounded offer under the open-admission gate drops notifications when the shared control queue is full; terminal admission suppresses new offers. The connection-owned writer performs network I/O independently of the cancelled request. Local cancellation still joins owned cleanup and preserves its causes without waiting for notification delivery. Connection shutdown joins the writer, and existing control-write failure remains terminal. No retry, durable notification or remote cancellation guarantee is added.

Migration: `McpRequests` adds abstract `register(onAbandon: Cause[AgentError] => UIO[Unit])`; existing custom implementations must implement or delegate this winner-only callback contract. This is source-incompatible for implementers, while no-argument callers retain the delegating API. Observers must avoid waiting on their own owner; observer defects are retained in caller cleanup rather than swallowed. `McpTransport.send(message, onIssued)` is additive with a successful-send fallback. Custom overrides must publish only a real issuance boundary within the send and keep the callback bounded and local; response-owning adapters need an override for in-flight eligibility.

The request, connection and HTTP suites cover callback-once settlement, held-observer capacity release, eligibility and suppression, bounded dropping, issuance/session identity and exact joined cleanup causes.

## Verification and acceptance

Run `sbt "mcp / test"` from the repository root for offline and owned local-process/loopback contracts. `sbt verify` also checks formatting and fatal-warning compilation. These tests do not establish interoperability with every server, complete protocol conformance or durable delivery.

## Remaining work

Explicit cancellation, descriptive output schemas and grouped installation are implemented within the contracts above. Manual binding and metadata integration have separate tool-package tests.

- [ ] Extend ambient metadata propagation, broader schema transformation or notification invalidation only through explicit host-selected contracts.
- [ ] Complete remaining applicable wrapper/transport mappings recorded in the [parity audit](../../../../../../../../../../docs/architecture/test-parity.md).

Controlled-port concurrency tests and real transport fixtures establish selected behavior, not whole-wrapper or complete revision parity. Automatic discovery during open, mutable map/null access and SDK fallback-close mechanics are intentional API differences. MCP 2026-07-28's lifecycle without initialize is not implemented by the selected older payload helpers.

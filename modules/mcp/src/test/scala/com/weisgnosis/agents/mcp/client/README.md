# com.weisgnosis.agents.mcp.client: tests

The [supported server revision agreement](../../../../../../../main/scala/com/weisgnosis/agents/mcp/transport/README.md#supported-server-revision-agreement) adds opt-in checked revision membership, one scoped negotiation owner, atomic correlated initialization and immediate framing/header transitions. Exact-version defaults remain; legacy POST additionally carries the revision header. This does not establish complete original-plan parity.

The [current March batch scope](../../../../../../../main/scala/com/weisgnosis/agents/mcp/transport/README.md#march-2025-batch-packets) changes connection packet ownership. Controlled transport tests exercise grouped server replies, invalid-member handling, atomic malformed-response rejection and held grouped-send cleanup; existing connection suites retain singular registry and cancellation coverage.

Resource/connection fixtures cover client/observer closure and exact terminal-cause provenance. Existing separate handshake/request deadline contracts remain in force.

[McpRequestsSpec](McpRequestsSpec.scala), [McpConnectionSpec](McpConnectionSpec.scala), [McpClientSpec](McpClientSpec.scala) and [McpClientManagerSpec](McpClientManagerSpec.scala) verify the [client contract](../../../../../../../main/scala/com/weisgnosis/agents/mcp/client/README.md) using scopes, STM, queues and explicit coordination promises. No test starts a network server or process.

| Suite | Independent contracts |
| --- | --- |
| Request registry | Atomic abandonment observer, released capacity, reply/terminal suppression and exact observer-defect causes; out-of-order correlation and remote error data; capacity and scope release; waiting-fiber interruption versus completed replies; owner/terminal closure with complete causes |
| Connection | Eligible cancellation versus initialize/unissued/completed requests, bounded dropping and connection-owned writer cleanup; immediate and concurrent replies, local send recovery and checked dispatch; cancellation joins cleanup and retains defects; terminal cause preservation during send and reply waiting; closed-parent startup; exact terminal rejection with no later send; control writes and directional cancellation; concurrent close and defect replay before borrowed-resource release; EOF, uncorrelated errors and bounded control overflow |
| Initialized client | Publication after initialized send; exact-version/error cleanup and invalid-deadline rejection before reader startup; tools capability authority; initialization/call/later-page deadline and cancellation joining held cleanup; ordered bounded discovery through empty pages, opaque cursor cycles, exact/invalid/excess budgets, metadata forwarding and later remote failure without partial publication |
| Scoped manager | Controlled-peer expiry followed by joined removal, fresh initialization/discovery/selection and stale-binding failure without replay; Ready-only toolkit/group snapshots, actual Meta activation with External isolation and retained fixed policy; grouped validation/rollback before publication; atomic concurrent collision publication and explicit host definitions, including output-schema discard/override; metadata-aware selection rollback and custom-manager unsupported fallback without acquisition; failed/cancelled initialization and exact compound causes; retained Pending/Removing capacity and joined active-call cleanup; immutable snapshot routing and transferred client closure; parallel owner cleanup, shared defect replay and closed admission |

Guarded reservations reject terminated connections before send, retaining the exact terminal cause. Closed-parent startup and shared closure defects retain contract coverage.

## Original-definition catalogue tests

McpClientSpec catalogue scenarios use the existing controlled Wire port. They check exact original schema/annotation/metadata/extension retention across pages and duplicate-name lookup; live discovery without refresh and invalid reuse bounds; later-page failure or cancellation followed by atomic retry; actual concurrent acquisition including a completed empty snapshot; explicit/natural close and held send cleanup without resurrection. The [catalogue contract](../../../../../../../main/scala/com/weisgnosis/agents/mcp/client/README.md#connection-local-original-definition-catalogue) explains explicit acquisition and pinned method adaptations.

## Resource client tests

McpClientSpec resource scenarios check coherent resource/template discovery, reads, subscriptions and typed updates with explicit metadata; denied capability/budget/cursor boundaries without partial results; and explicit notification overflow/malformed failure while correlated RPC remains usable. The existing held-finalizer deadline/cancellation table now includes resource reads and continues checking cleanup completion and retained defects. McpConnectionSpec adds one deterministic failing-send/terminal-race regression: it proves cleanup remains joined and requires the original exact terminal cause without the supervisor's own cancellation.

These controlled-port tests use no external server. Existing Java SDK interoperability groups separately add real loopback resource discovery/read/template routes and resource list-change delivery through an explicit HTTP GET listener. The [resource contract](../../../../../../../main/scala/com/weisgnosis/agents/mcp/client/README.md#resource-client-contract) describes capability and observer bounds.

## Acceptance and next tests

Run `sbt "mcp / test"` from the repository root for offline and owned local-process/loopback contracts. `sbt verify` also checks formatting and fatal-warning compilation. These tests do not establish interoperability with every server, complete protocol conformance or durable delivery.

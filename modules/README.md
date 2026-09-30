# Module index

Package READMEs describe available source or explicitly label planned designs. Consult the [tracker](../docs/architecture/tracker.md) and [parity audit](../docs/architecture/test-parity.md) for reviewed scope and remaining integration.

| Module | Current status |
| --- | --- |
| [core](core/README.md) | Immutable models, typed events/tools, canonical path checks, advisory command screening, scoped model clients, streaming and ReAct execution |
| [harness](harness/README.md) | Memory/transcript storage and maintenance, compaction/eviction, interactive permissions, reviewed plan sessions and controls, scoped task/resource ownership, configured child factories, bounded execution registries, host-held reusable sessions, bounded host session membership and opt-in spawn/send/task/cancel tools |
| [mcp](mcp/README.md) | Checked JSON-RPC envelopes; version-specific methods, client, transports and toolkit integration remain planned |

Harness adapters require explicit host installation and ownership. Child factories retain final static tool filtering and injected authorization. Runtime-owned lineage enforces depth through three; reusable sessions retain one child runtime and serialize turns. Model tools bind to a host-selected registry, session or session directory. `SubagentConstruction` supplies an opt-in factory path carrying static boundaries and live ancestor Deny/Plan checks into nested children while preserving their confirmation authority. Declaration-driven composition and event forwarding remain unfinished; durable recovery is a separate host contract.

Plan mode provides conservative inspection, live admission, exact-draft host review, request-only context and cooperative local draft storage. Stored drafts reopen unapproved. Strict enter/write/exit tools preserve review authority; configured/observed location metadata grants no filesystem or approval authority. Typed PlanDirectory selects an existing strict descendant of a host-controlled workspace without creating directories or discovering a workspace. A host-installed inherited gate restricts execution if either parent or child is in Plan; declared/general-purpose composition and remaining plan mappings stay pending.

Every change must update affected package documentation, source/test links and scenario mappings. Current reviewer identities, accepted revisions and verification evidence belong in the linked module/package contracts and tracker, not a milestone journal here.

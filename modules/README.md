# Module index

Package READMEs describe available source or explicitly label planned designs. Consult the [tracker](../docs/architecture/tracker.md) and [parity audit](../docs/architecture/test-parity.md) before interpreting a checklist as delivered coverage.

| Module | Current status |
| --- | --- |
| [core](core/README.md) | Models, events, tools, path screening, stream parsing, client port, ReAct |
| [harness](harness/README.md) | Compaction, eviction/storage, permission components; opt-in local/inline-result eviction adapter; opt-in interactive permission adapter; opt-in validated compaction adapter; static plan inspection, permission admission in-memory reviewed sessions, request projection and standalone draft storage |
| [mcp](mcp/README.md) | Design scaffolding only |

Every change must update affected package documentation, source/test links, and scenario mappings. Planned subagent and MCP documents are requirements, not implemented interfaces. Plan-mode documentation distinguishes implemented inspection/admission/session interfaces from pending child propagation; request-only projection is available.

Stored plan sessions now invalidate review before writing and reopen content only as unapproved Plan drafts. Opt-in model-facing controls are now available; descriptive location context is available; typed workspace-relative directories are available; durable approval restoration remains separate work.

PlanTools now adds strict opt-in enter/write/exit capabilities. Exact-ticket host review is mandatory inside exit even with ordinary Allow rules; publisher transport and authenticated decision routing remain host responsibilities.

PlanContext now associates one snapshot with configured/observed location metadata. Four additional manager/prompt scenarios are adapted; legacy slash rebasing remains pending and no location value grants filesystem or approval authority.

Typed PlanDirectory configuration now selects an existing strict descendant of an explicit host-controlled workspace. Native Path opening retains its semantics; no directory creation or implicit workspace discovery is provided. Plan parity now records eight of fourteen named adaptations; skills and supervised child propagation remain pending.

# Module index

Package READMEs describe available source or explicitly label planned designs. Consult the [tracker](../docs/architecture/tracker.md) and [parity audit](../docs/architecture/test-parity.md) before interpreting a checklist as delivered coverage.

| Module | Current status |
| --- | --- |
| [core](core/README.md) | Models, events, tools, path screening, stream parsing, client port, ReAct |
| [harness](harness/README.md) | Compaction, eviction/storage, permission components; opt-in local/inline-result eviction adapter; opt-in interactive permission adapter; opt-in validated compaction adapter; static plan inspection, permission admission and in-memory reviewed sessions |
| [mcp](mcp/README.md) | Design scaffolding only |

Every change must update affected package documentation, source/test links, and scenario mappings. Planned subagent and MCP documents are requirements, not implemented interfaces. Plan-mode documentation distinguishes implemented inspection/admission/session interfaces from pending files, control tools and child propagation; request-only projection is available.

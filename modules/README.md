# Module index

Package READMEs describe available source or explicitly label planned designs. Consult the [tracker](../docs/architecture/tracker.md) and [parity audit](../docs/architecture/test-parity.md) before interpreting a checklist as delivered coverage.

| Module | Current status |
| --- | --- |
| [core](core/README.md) | Models, events, tools, path screening, stream parsing, client port, ReAct |
| [harness](harness/README.md) | Compaction, eviction/storage, permission components; opt-in local-result eviction adapter; opt-in interactive permission adapter; opt-in validated compaction adapter |
| [mcp](mcp/README.md) | Design scaffolding only |

Every change must update affected package documentation, source/test links, and scenario mappings. Planned subagent, plan-mode, and MCP documents are requirements, not implemented interfaces.

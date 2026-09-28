# weisgnosis-agents-harness

Standalone context and permission components built on core.

| Package | Current status |
| --- | --- |
| [memory](src/main/scala/com/weisgnosis/agents/harness/memory/README.md) | Pair-safe compaction, summary/user provenance, token estimation; memory services pending |
| [eviction](src/main/scala/com/weisgnosis/agents/harness/eviction/README.md) | Above-80k eviction, 2k head/tail previews, explicit-trust local store |
| [permission](src/main/scala/com/weisgnosis/agents/harness/permission/README.md) | Immutable decisions, Promise coordination, serializable durable approval values |
| [subagent](src/main/scala/com/weisgnosis/agents/harness/subagent/README.md) | Planned supervision and task promotion |
| [plan](src/main/scala/com/weisgnosis/agents/harness/plan/README.md) | Planned mutation restrictions and shell inspection |

Agent middleware, durable workflow storage, MemoryFlushManager, and MemoryConsolidator are not implemented. Hosts supply summarization/flush/offload effects and authenticated approval routing.

Local storage requires WorkspaceTrust.HostControlled, protected filesystem ancestors, and hard-link support. It is not a sandbox for untrusted workspaces. See [implementation notes](../../docs/architecture/phase-4-implementation.md) and the [tracker](../../docs/architecture/tracker.md).

# Architecture contracts

The runtime owns conversation history, completion and cancellation. Models propose actions; tools and hosts provide observations under explicit authorization. Effects, resource acquisition and cleanup remain suspended in ZIO.

## Design and implementation

- [High-level design](high-level-design.md): module boundaries and host responsibilities.
- [Mid-level design](mid-level-design.md): component composition and planned integrations.
- [Low-level design](low-level-design.md): source-linked API contracts and migration notes.
- [Completion checklist and delivery plan](tracker.md): current capabilities, selected work packages, acceptance scenarios and unselected candidates.
- [Upstream parity reference](test-parity.md): behavior mappings, adaptations and open evidence.

## Component contracts

- [Agent runtime](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md): run ownership, checkpoints, authorization and interruption reconciliation.
- [Multi-agent collaboration](../../modules/core/src/main/scala/com/weisgnosis/agents/core/coordination/README.md): scoped MsgHub membership, passive peer data and explicit delivery outcomes.
- [Tracing](../../modules/otel/README.md): optional fiber-local span integration with host-owned SDK/export and bounded private attributes.
- [Model transport](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md): completion profiles, reasoning/media representations and stream boundaries.
- [Tools](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md): typed execution, schema admission and external observations.
- [Subagents](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md): scoped children, retained supervision and recovery limits.
- [Context and memory](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md): paired compaction, provenance, daily extraction and curated consolidation.
- [Tool output](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/eviction/README.md): bounded previews, checked persistence and raw-observation preservation.
- [Permissions](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md): independent gates, interactive confirmation and unavailable-channel denial.
- [Planning](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md): restricted execution, checked drafts and host review.
- [Transcripts](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/transcript/README.md): writer composition, conditional storage, catalogs and immutable segments.
- [Storage](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/storage/README.md): conditional publication, cancellation and ownership.
- [MCP](../../modules/mcp/README.md): tools/resources, negotiated revisions, transport lifetimes and session replacement.
- [PostgreSQL](../../modules/storage-postgresql/README.md): host-owned connections/schema and bounded conditional publication.

Hosts install harness adapters and own their scopes. Background promotion transfers ownership to a retained supervisor; it does not create a durable distributed workflow. Authentication, approval delivery, exclusive execution claims and idempotency belong to the host. Bounded storage adapters are library capabilities; backend deployment and recovery policy are host responsibilities.

Local persistence requires trusted filesystem ancestors. Canonical path validation is not an atomic sandbox. Saved plans, checkpoints and task records are data, not grants of authorization.

Design documents may include explicitly planned systems. Use the tracker and linked source contracts to distinguish implemented behavior from remaining work. Reviews apply to inspected changes and are recorded in the PR or task conversation, rather than repeated as documentation acceptance journals.

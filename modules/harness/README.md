# Host integration components

Harness builds on core with conversation compaction, memory and transcripts, result offloading, permissions, Plan admission, skills, and supervised subagents. Hosts choose and install these components explicitly; the module does not create a durable application host or scheduler by default.

## Start here

Use [Getting started](../../docs/getting-started.md) for compiled applications and [HostAssembly](../examples/src/main/scala/com/weisgnosis/agents/examples/HostAssembly.scala) for composition through existing core extension points. See the [build guide](../../project/README.md#local-artifact-packaging) for local artifacts.

| Package | Purpose |
| --- | --- |
| [memory](src/main/scala/com/weisgnosis/agents/harness/memory/README.md) | Pair-safe compaction, daily extraction, consolidation, and explicit maintenance |
| [transcript](src/main/scala/com/weisgnosis/agents/harness/transcript/README.md) | Observation recording, aggregate/segment storage, and selected discovery |
| [storage](src/main/scala/com/weisgnosis/agents/harness/storage/README.md) | Versioned JSON publication, local files, acting checkpoints, and cooperative workspace leases |
| [eviction](src/main/scala/com/weisgnosis/agents/harness/eviction/README.md) | Large-result offloading and bounded previews |
| [permission](src/main/scala/com/weisgnosis/agents/harness/permission/README.md) | Decision composition, Promise-based approval, and serializable confirmation data |
| [subagent](src/main/scala/com/weisgnosis/agents/harness/subagent/README.md) | Configured children, reusable host-held sessions, task tools, durable observations, and host-driven controls |
| [plan](src/main/scala/com/weisgnosis/agents/harness/plan/README.md) | Explicit Plan-mode tool admission, command screening, and draft storage |
| [skill](src/main/scala/com/weisgnosis/agents/harness/skill/README.md) | Bounded skill parsing and explicitly trusted local resources |

For subagents, construct trusted factories and retain the returned scopes/resources. [SubagentSession](src/main/scala/com/weisgnosis/agents/harness/subagent/SubagentSession.scala) reuses one runtime; catalog and directory adapters select host-installed capabilities. [SubagentTools](src/main/scala/com/weisgnosis/agents/harness/subagent/SubagentTools.scala) supplies optional model-facing tools. Durable storage and post-persistence consumption are separate explicit bindings; ordinary observational tools do not silently acknowledge results.

## Ownership and limits

The host supplies model clients, authorization, summarization, storage addresses, and lifetimes. TaskSupervisor owns promoted tasks and retained resources; session turns serialize without a FIFO guarantee. Closing an owner joins its work and finalizers. Callbacks must respect the documented nonclosure and self-join restrictions.

Durable task records preserve observations, metadata, cancellation intent, and acknowledgements. They do not restore execution, prove delivery, or create remote-running handles. Recovery requires an explicit host ownership claim; timestamps and CAS alone are not execution fences. AgentProtocol origin is persisted data, not a remote transport implementation. [Subagent contracts and migrations](src/main/scala/com/weisgnosis/agents/harness/subagent/README.md) describe exact APIs, supported schema versions, bounded receipt capacity, and remaining session/recovery work.

Local persistence requires WorkspaceTrust.HostControlled and screened ancestors. Cooperative leases are not a hostile-filesystem sandbox or stale-owner takeover mechanism. Local result publication requires hard-link support; atomic replacement backends require atomic-move support. Local task discovery reads the explicitly owned layout without taking competing writer leases; it is not an atomic global snapshot. See [storage ownership](src/main/scala/com/weisgnosis/agents/harness/storage/README.md).

Daily extraction writes daily memory; consolidation alone writes curated memory within its budget. Hosts provide offload readers and authenticated approval routing. Broader durable workflow, persistent named-session reuse, and backend/platform obligations remain in the [tracker](../../docs/architecture/tracker.md).

## Testing

From the repository root, run `sbt "harness / test"`. Run `sbt verify` for repository formatting checks, fatal-warning compilation, and tests. PostgreSQL-backed integration requires the separate [database fixture setup](../storage-postgresql/src/test/scala/com/weisgnosis/agents/storage/postgresql/README.md).

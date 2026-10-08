# Documentation index

The [registry terminal publication contract](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#registry-terminal-publication) adds explicitly owned automatic publication after joined child cleanup and effectful terminal observations through reopened `agent_task` and `task_list` tools. Stored text remains exact; model previews remain bounded. Per-task publication receipts survive registry closure, and hosts must inspect or flush them after shutdown before releasing storage. Independent FP/ZIO and invariant source reviews, focused PostgreSQL/ReAct checks, full verification, four RC1 packages and fresh external artifact consumption pass; review_fp and review_invariants also approve the final combined scope against `5f75a0a`: seven Scala files and eleven Markdown files, with no unresolved findings. Durable delivery acknowledgement, cancellation intent and execution/session restoration remain open in the original plan.

The [invocation and prepared-run contract](../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#invocation-identity-and-prepared-execution) separates fresh execution correlation from saved model-request identity. Ordinary prepared runs expose a lazy single-attempt handle; Auto attempts share one invocation and resumed executions receive a new one. Confirmation authority remains bound to the exact saved reply/request pair and call occurrence, independently of observational invocation identity. Both independent reviewers approve the fourteen-file source manifest against `db80dc0`; full verification, four RC1 packages, source-archive preparation and fresh external consumption pass; review_fp and review_invariants approve final combined documentation against `db80dc0` for fourteen unchanged Scala files and fourteen Markdown files.

Current implementation and future platform requirements are separate. The project is in development; passing tests do not imply production readiness or exhaustive upstream parity.

- [Getting started](getting-started.md): local installation, complete scripted/HTTP/tool/MCP examples and host boundaries.
- [API compatibility and migration](../project/README.md#compatibility-policy): current public contracts and rebuild requirements.
- [Verified host assembly](../modules/examples/README.md): explicit composition, logging and optional live validation.
- [Architecture](../ARCHITECTURE.md): runtime authority and delivery boundaries.
- [High-level design](architecture/high-level-design.md): modules and host responsibilities.
- [Mid-level design](architecture/mid-level-design.md): current behavior and planned integrations.
- [Low-level contracts](architecture/low-level-design.md): current APIs linked to source.
- [Implementation tracker](architecture/tracker.md): delivered components and remaining work.
- [Phase 4 notes](architecture/phase-4-implementation.md): storage, compaction, permission, and API contracts.
- [Parity audit](architecture/test-parity.md): mapped areas and outstanding exhaustive audit.
- [Module index](../modules/README.md): actual packages and planned scaffolding.

## Research references

[System architecture research](weisgnosis-os-and-agents-architecture.md) and the [porting guide](deep-research-and-porting-guide.md) preserve broader target architecture and upstream analysis. Their sketches are not current exported APIs.

## Current documentation acceptance

The publication guide and original-plan reconciliation have final independent acceptance within the [publication audit scope](architecture/tracker.md#publication-documentation-audit). This does not extend approval to unfinished transfers or external release gates.

The [tracker](architecture/tracker.md#current-acceptance) records the current verified scope and build evidence. The [core](../modules/core/README.md), [harness](../modules/harness/README.md) and [MCP](../modules/mcp/README.md) indexes lead to package contracts with exact reviewed source/test identities and reviewer approvals. The [parity audit](architecture/test-parity.md) uses pinned upstream `f98a685fdc4589f81e626e1686ac2c63d14a2304` and distinguishes verified adaptations, intentional exclusions and unfinished methods. Passing suites do not establish exhaustive parity.

Current runtime contracts include result preparation and commit/result/end ordering, native structured metadata, bounded Auto fallback, typed model-stream correlation and request-only context projection. General JSON Schema validation, exhaustive hook parity and richer event-source routing remain outside those contracts. See the [agent](../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md) and [event](../modules/core/src/main/scala/com/weisgnosis/agents/core/event/README.md) package documentation for precise limits.

## Updating this documentation

Update affected module/package READMEs, API and migration notes, the tracker and method mappings when behavior changes. Record final independent review scope, source identities and verification evidence before marking acceptance. Keep unfinished work unchecked and label roadmap sketches and host integration requirements explicitly. Draft source does not establish acceptance.

Replace superseded status and closed review narratives with current contracts; Git preserves their history. Check English prose and local links, preserve referenced anchors where practical, and follow [AGENTS.md](../AGENTS.md) and [CONTRIBUTING.md](../CONTRIBUTING.md).

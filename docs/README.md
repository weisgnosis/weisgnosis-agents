# Documentation index

Current implementation and future platform requirements are separate. The project is in development; passing tests do not imply production readiness or exhaustive upstream parity.

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

- [x] Independent reviewer `review_docs` approved the pinned inventory, 18-method structured-output, 119-method message, and 30-declaration event classifications, README acceptance scopes, and architecture contracts on 2026-09-29 after corrections.
- [x] Upstream `f98a685fdc4589f81e626e1686ac2c63d14a2304` was clean at inventory time. Mappings distinguish verified contracts, runtime adaptations, and pending behavior.
- [x] No unresolved findings in this documentation scope. English, local links, maintained-directory README coverage, whitespace, and the reviewed source identities were checked.
- [x] Repository verification: `sbt scalafmtAll scalafmtSbt verify`, 195 core and 40 harness tests passed with zero compiler warnings.

The agent READMEs describe engine-attempt lifecycle boundaries and private stream completion, including scoped publisher ownership and unbounded buffering. Structured-call completion and event wire parity remain separate pending tasks.

Implementation acceptance is scoped in the module/package READMEs. The [tracker](architecture/tracker.md) and [parity task list](architecture/test-parity.md) retain unfinished work as unchecked items. Closed review reports remain only in Git history.

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

- [x] Independent reviewer `review_docs` explicitly approved the README/review-closure and checkbox-tracking changes on 2026-09-29 after both requested corrections.
- [x] No unresolved findings in that documentation scope; English, local links, maintained-directory README coverage, and whitespace checks passed.
- [x] Repository verification for this documentation change: `sbt scalafmtAll scalafmtSbt verify`, 108 core and 40 harness tests passed with zero compiler warnings.

This approval covers the documentation protocol and current acceptance wording. Implementation acceptance remains scoped in the module/package READMEs; the [tracker](architecture/tracker.md) retains unchecked remaining work. Resolved review reports are retained only through Git history.

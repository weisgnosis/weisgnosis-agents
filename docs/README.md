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

- [x] Independent documentation reviews accepted the inventory and message/event/structured-output mappings through `e110321`; exact implementation acceptance belongs in the affected module/package READMEs.
- [x] Upstream `f98a685fdc4589f81e626e1686ac2c63d14a2304` was clean at inventory time. Mappings distinguish verified contracts, runtime adaptations, explicit exclusions, and pending behavior.
- [x] Executable baseline: `sbt scalafmtAll scalafmtSbt verify`, 326 core and 40 harness tests passed with zero compiler warnings.

The agent READMEs describe finalized result preparation, commit/result/end ordering, native metadata preservation, Auto fallback boundaries, and runtime-schema calls/streams. General JSON Schema validation and exhaustive model capability/hook parity are not claimed. The event READMEs explicitly exclude the deprecated coarse envelope while retaining actual typed model-stream correlation and richer source attribution as pending work.

The [tracker](architecture/tracker.md) and [parity task list](architecture/test-parity.md) keep incomplete work unchecked. Checked applicability decisions are labeled as exclusions, not passed tests. Current contracts replace closed review journals; Git preserves prior findings and acceptance records.

- [x] `review_docs` approved the eight-file deprecated-envelope classification against `e110321` on 2026-09-29: ten explicit exclusions, retained compatibility limitations, pending correlation/source requirements, English, links, and checkbox wording. No unresolved findings; no executable changes were made.

- [x] `review_docs` approved strict-delta documentation against `95a838f` on 2026-09-29: three source identities, typed API migration, boundary contracts, fifteen regressions, 326+40 verification, English, and links. HTTP integration, cross-frame validation, input bounds, and exhaustive extension parity remain pending.

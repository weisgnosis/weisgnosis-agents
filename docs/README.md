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
- [x] Executable baseline: `sbt scalafmtAll scalafmtSbt verify`, 392 core and 40 harness tests passed with zero compiler warnings.

The agent READMEs describe finalized result preparation, commit/result/end ordering, native metadata preservation, Auto fallback boundaries, and runtime-schema calls/streams. General JSON Schema validation and exhaustive model capability/hook parity are not claimed. The event READMEs explicitly exclude the deprecated coarse envelope while describing implemented typed model-stream correlation and retaining richer source attribution as pending work.

The [tracker](architecture/tracker.md) and [parity task list](architecture/test-parity.md) keep incomplete work unchecked. Checked applicability decisions are labeled as exclusions, not passed tests. Current contracts replace closed review journals; Git preserves prior findings and acceptance records.

Prior independent documentation acceptance through `73f66d1` remains recorded in Git. The current client README contains strict JSON/delta/assembly contracts, the validated text/function request surface, exact reviewed source identities, and remaining transport/provider limits.

- [x] `review_docs` approved the request-codec documentation against `73f66d1` on 2026-09-29: exact source identities, sixteen-test scope, pairing/state/reasoning contracts, 361+40 verification, English, and local links. No findings remain in this scope.

- [x] `review_docs` approved the transport documentation against `38c48db` on 2026-09-29: exact identities, fourteen-test scope, resource ownership, response bounds, finish/[DONE], 375+40 verification, English, and links. No findings remain within that earlier transport scope; client/event integration and full provider parity were excluded from its approval.

- [x] `review_docs` approved the final live client/event documentation against `60b3799` on 2026-09-29: sixteen final identities, eleven new tests, required client context, optional event context, live-order adaptation, 386+40 verification, English, and links. No findings remain in this scope.

- [x] `review_docs` approved the final completed-tool-result documentation against `84e4eb3` on 2026-09-29: five final identities, six new regressions, persistence/publication order, sixteen contextual variants, 392+40 verification, English, and links. No findings remain in this scope.

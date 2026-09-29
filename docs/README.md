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
- [x] Executable baseline: `sbt scalafmtAll scalafmtSbt verify`, 556 core and 549 harness tests passed with zero compiler warnings.

The agent READMEs describe finalized result preparation, commit/result/end ordering, native metadata preservation, Auto fallback boundaries, and runtime-schema calls/streams. General JSON Schema validation and exhaustive hook parity are not claimed. The event READMEs explicitly exclude the deprecated coarse envelope while describing implemented typed model-stream correlation and retaining richer source attribution as pending work.

The [tracker](architecture/tracker.md) and [parity task list](architecture/test-parity.md) keep incomplete work unchecked. Checked applicability decisions are labeled as exclusions, not passed tests. Current contracts replace closed review journals; Git preserves prior findings and acceptance records.

Prior documentation reviews are preserved in Git through `40f6f05`. Current package READMEs retain exact accepted implementation scopes and source identities.

- [x] Independent documentation review approved request-only model-input projection against `40f6f05`, including canonical-state isolation and all seven eviction mappings with explicit adaptations.

- [x] Independent documentation review approved compaction method accounting against `52da827`, including all 17 explicitly classified decisions, current source identities, and verification counts.

- [x] Independent documentation review approved daily-memory extraction against `738b216`, including injected-storage scope, source identities, two daily mappings, three pending transcript mappings, and append partial-effect boundaries.

- [x] Independent documentation review approved scoped local daily-memory storage against `d1a4cf2`, including current identities, cancellation and ownership semantics, atomic-move requirements, and explicit stale-lease/durability limits.

- [x] Independent documentation review approved atomic in-memory consolidation against `46c6174`, including current identities, capability separation, estimator limits, conflict handling, and five pending filesystem mappings.

- [x] `review_docs` approved recoverable local consolidation documentation against `e4bd6b0`: all nine identities, 34 new cases, migration, three local adaptations/two pending remote scenarios, and API-only recovery guarantees. Full verification passed 530 core / 209 harness tests; all 59 Markdown files passed link/script checks.

- [x] `review_docs` approved transcript observation documentation against `73458da`, including six identities, 22 new tests, first-observation semantics, ordering limits, and pending filesystem/segment assertions. Verification passed 530 core / 231 harness tests; all 61 Markdown files passed link/script checks.

- [x] `review_docs` approved local transcript persistence documentation against `e4dfc67`: 14 identities, 27 new tests, four local adaptations, strict decoding/ownership and Unicode limits. Full verification passed 530 core / 258 harness tests; all 63 Markdown files passed link/script checks.

- [x] `review_docs` approved completion-observer documentation against `0349b1a`, including all eight identities, 28 new cases, late cancellation, early-stream error visibility, explicit anonymous policy, compactor offload and remaining lifecycle work. Full verification passed 552 core / 264 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed.

- [x] `review_docs` approved the corrected memory-trigger documentation against `73cd376`: six identities, 23 tests, finite/monotonic admission, active-window cleanup, explicit owner sharing and conservative upstream applicability. Full verification passed 552 core / 287 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed.

- [x] `review_docs` approved the corrected scoped queue documentation against `ad12c7b`: five identities, twenty new tests, bounded coalescing, explicit runtime ticket outcomes, host-owned cleanup and pending real middleware integration. Full verification passed 552 core / 307 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed.

- [x] `review_docs` approved the corrected flush-scheduler documentation against `1c8e3ee`: four identities, seventeen new tests, immutable completed snapshots, explicit receipt handoff, four upstream runtime adaptations and pending maintenance work. Full verification passed 552 core / 324 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed.

- [x] `review_docs` approved the corrected consolidation/composition documentation against `3e817e3`: seven identities, 25 new cases, scoped composition, reporting limits, FIFO ordering and conservative parity classifications. Full verification passed 555 core / 346 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed.

- [x] `review_docs` approved the corrected daily-retention documentation against `b2ab23e`: seven identities, 24 new tests, archive ordering, migration and conservative partial-progress limits. Full verification passed 555 core / 370 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed. Automatic policy, session pruning and crash guarantees remain excluded, with no new named upstream test parity claimed.

- [x] `review_docs` approved the corrected maintenance documentation against `b3d2723`: eight identities, 21 new tests, policy and shared-window contracts, reporting limits and incomplete configuration mappings. Full verification passed 555 core / 391 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed.

- [x] `review_docs` approved transcript-pruning documentation against `9ddee3a`: three identities, thirteen tests, activity/cutoff rules, shared ownership, cancellation limits and generation retirement. Full verification passed 555 core / 404 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed. Automatic policy/discovery, remote pruning and crash guarantees remain excluded.

- [x] `review_docs` approved transcript-policy documentation against `f1a005e`: five identities, eight tests, fixed-day arithmetic, KeepAll bypass, additive API behavior and partial configuration mappings. Full verification passed 555 core / 412 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed. Batch, discovery, scheduling and remote integration remain excluded.

- [x] `review_docs` approved batch/coordinator documentation against `b86fd14`: eight identities, 22 new cases, explicit ordering, cutoff preparation and partial-progress contracts. Full verification passed 555 core / 434 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed. Configuration, discovery and remote integration remain visible as unfinished work.

- [x] `review_docs` approved corrected memory-configuration documentation against `005bacf`: ten identities, 17 new cases, preserved defaults, literal template semantics and all ten configuration-method classifications with explicit differences. Full verification passed 555 core / 451 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed. No findings remain in this scope.

- [x] `review_docs` approved corrected static-shell documentation against `2d4094b`: four identities, ten tests, exact grammar and fourteen pending upstream methods. Full verification passed 555 core / 461 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed. Runtime enforcement and executor/path/sandbox authority remain explicitly outside this scope.

- [x] `review_docs` approved corrected plan-admission documentation against `0ad16e5`, including the InvalidInput migration note and stale status wording in AGENTS.md. Eight identities, fourteen new tests, two upstream adaptations and twelve pending methods are verified. Full verification passed 555 core / 475 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed. No findings remain in this scope.

- [x] `review_docs` approved corrected plan-session documentation against `87b2d8b`, including current baseline architecture/module summaries. Five identities, eighteen new cases and bounded 2/14 parity status are verified. Full verification passed 555 core / 493 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed. Projection, persistence, control tools and child propagation remain explicitly pending.

- [x] `review_docs` approved corrected projection documentation against `132a81a`: five identities, thirteen new cases, truthful scope and 3/14 parity classification. Full verification passed 555 core / 506 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed. Recorded-path assertions, core equality validation behavior and live permission authority remain explicit. No findings remain in this scope.

- [x] `review_docs` approved corrected standalone plan-storage documentation against `083617b`: nine identities, fifteen new cases, 556 core / 520 harness tests and unchanged 3/14 plan parity. All 63 Markdown files passed local-link/script checks and git diff --check passed. Session/publication integration and persisted approval remain pending; no findings remain within this scope.

- [x] `review_docs` approved corrected stored-session documentation against `2a73621`: six identities, fourteen new cases, 556 core / 534 harness tests and unchanged 3/14 plan parity. Ownership, failure/cancellation limits and the widened error channel are explicit. All 63 Markdown files passed local-link/script checks and git diff --check passed. No findings remain in this scope.

- [x] `review_docs` approved corrected model-control documentation against `bfca341`: seven identities, fifteen new cases, 556 core / 549 harness tests and unchanged 3/14 plan parity. Exact-ticket review is distinguished from independent generic Ask policy; delivery and cancellation limits are explicit. All 63 Markdown files passed local-link/script checks and git diff --check passed. No findings remain within this scope.

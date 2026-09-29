# Plan-mode verification

- [PlanShellInspectionSpec](PlanShellInspectionSpec.scala): nine cases for strict command envelopes, exact argv, shell syntax rejection, unsupported binaries/wrappers, positive argument shapes, option/stdin rejection, bounded line counts, size/Unicode boundaries and whitespace normalization.
- [PosixReadCommandSpec](PosixReadCommandSpec.scala): two cases for fixed program identities and typed rejection codecs.

These tests execute no shell and establish no path/process isolation. The [current contract and current identity tables](../../../../../../../main/scala/com/weisgnosis/agents/harness/plan/README.md) define the narrow accepted scope.

- [x] `review_fp` and `review_harness` approved the final four-file static-shell increment against `2d4094b` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 461 harness tests, including ten new cases, with zero compiler warnings. No findings remain within bounded positive syntax inspection and immutable argv results. Runtime plan enforcement, process execution, path authorization and resource isolation are excluded.

- [x] Verify ten static syntax/type cases.
- [x] Verify permission integration, modified confirmation arguments and real runtime denials.
- [x] Adapt two upstream middleware methods through actual runtime admission.
- [x] Adapt fresh Build no-hint behavior through request-only projection.
- [ ] Adapt the other eleven upstream methods after their services exist.
- [ ] Verify child inheritance, safe plan persistence and authenticated exit approval.

## Admission verification

- [PlanModeSpec](PlanModeSpec.scala): one ADT/codec case for modes and trusted classifications.
- [PlanPermissionGateSpec](PlanPermissionGateSpec.scala): six cases for classification decisions, Build pass-through, lazy current-mode sampling, permission priority, strict input and cancellation cleanup.
- [PlanPermissionIntegrationSpec](PlanPermissionIntegrationSpec.scala): six real ReAct cases for persisted/streamed denials, inactive pass-through, confirmed replacement input, Plan activation during approval, mandatory execution-request confirmation without state transition, and already-admitted work followed by a denied next call.
- PlanShellInspectionSpec adds one exact-envelope case, bringing that suite to nine and the increment to fourteen new tests.

No test executes a process or proves filesystem isolation. In-memory plan management and host-approved transitions are implemented below; persistence and child propagation remain pending; request projection is implemented below. The [admission contract and eight current identities](../../../../../../../main/scala/com/weisgnosis/agents/harness/plan/README.md#dynamic-plan-admission) record acceptance.

- [x] `review_fp` and `review_harness` approved the final eight-file plan-admission increment against `0ad16e5` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 475 harness tests, including fourteen new cases, with zero compiler warnings. No findings remain within typed mode/classification, exact command input, dynamic permission admission and actual ReAct denial/confirmation behavior. Process isolation, plan lifecycle/storage/prompts and child propagation are excluded.

## Session and review verification

- [PlanSessionStateSpec](PlanSessionStateSpec.scala): four cases for exact nonblank drafts, strict unbounded revisions, valid snapshot shapes and review outcome codecs.
- [PlanSessionSpec](PlanSessionSpec.scala): seven cases for draft-free rejection, exact approval, decline, edit/re-entry invalidation, invalid edits and direct gate mode integration.
- [PlanSessionReviewSpec](PlanSessionReviewSpec.scala): seven cases for current/older scopes, equal-revision foreign sessions, concurrent consumers, both valid edit/approval race outcomes, interruption and cleanup after an approved generation.

All eighteen cases use in-memory STM authority. No filesystem/UI/model-tool behavior or durable restoration is inferred. The [session contract and unchanged prior identities](../../../../../../../main/scala/com/weisgnosis/agents/harness/plan/README.md#in-memory-plan-session-lifecycle) define the reviewed scope.

- [x] `review_fp` and `review_harness` approved the final five-file in-memory plan-session increment against `87b2d8b` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 493 harness tests, including eighteen new cases, with zero compiler warnings. No findings remain within typed snapshots, exact-ticket host review, atomic settlement, scoped cancellation and live gate integration. Paths/files, durable restoration, prompt projection and control-tool/authenticated-UI integration are excluded.

## Projection verification

- [PlanPromptSpec](PlanPromptSpec.scala): four cases for fresh Build, absent/unapproved drafts, exact pending revision/data and truthful approved context.
- [PlanInputTransformSpec](PlanInputTransformSpec.scala): six cases for lazy single sampling, final-user field preservation, complete pair preservation/new synthetic metadata, no accumulation, cancellation cleanup and defect propagation.
- [PlanInputIntegrationSpec](PlanInputIntegrationSpec.scala): three actual ReAct cases for pending-to-approved host review across model requests, unchanged canonical history, live gate denial after a reasoning-time mode change and rejection of incomplete history before the model runs.

The [projection contract and unchanged prior identities](../../../../../../../main/scala/com/weisgnosis/agents/harness/plan/README.md#request-only-plan-context) define the accepted scope. No filesystem/UI/child authority is supplied by prompt text.

- [x] `review_fp` and `review_harness` approved the final five-file plan-input increment against `132a81a` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 506 harness tests, including thirteen new cases, with zero compiler warnings. No findings remain within truthful snapshot rendering, request-only projection, final-user coalescing, preserved canonical/system/tool history and live gate authority. Files/restoration and automatic tool/UI/child integration are excluded.

## Standalone draft-store verification

[LocalPlanDraftStoreSpec](LocalPlanDraftStoreSpec.scala) covers missing payloads, exact Unicode/line endings, reopen/replacement, live competitors, directory isolation, closed handles, concurrent complete reads, stale tokens, corrupt/blank input and unsafe directory rejection. [PlanDraftFilesSpec](PlanDraftFilesSpec.scala) covers hardlink preservation, malformed UTF-16 rollback, nonregular destinations and missing-versus-blank reads. Shared WorkspaceLeaseSpec additionally checks plan-specific error provenance and masked cancellation; AgentErrorSpec checks the new leaf codec. Storage is separate from session approval and closes no additional named plan-manager method.

- [x] `review_fp` and `review_harness` approved the final nine-file standalone plan-storage increment against `083617b` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 556 core / 520 harness tests, including fifteen new cases, with zero compiler warnings. No findings remain within exact content/encoding, scoped cooperative ownership, atomic replacement, hardlink preservation, typed errors and cancellation ordering. Session/publication integration, persisted approval, hostile filesystem isolation, crash durability and new whole-method upstream parity are excluded.

## Stored-session verification

[PlanStoredSessionSpec](PlanStoredSessionSpec.scala) covers missing/failed/defective/cancelled opening, actual local publication and reopen, Build exclusion, explicit re-entry and ambiguous typed/defective writes. [PlanStoredPublicationSpec](PlanStoredPublicationSpec.scala) covers pre-I/O invalidation, live permission denial, stale-scope closure, serialized writers, interruptible queued edits/entry/review/resolution, early/late cancellation, successful recovery and composite write/cleanup causes. Fourteen new cases preserve the existing in-memory and request-projection suites. Approval restoration and saved-path method parity remain excluded.

- [x] `review_fp` and `review_harness` approved the final six-file stored-session increment against `2a73621` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 556 core / 534 harness tests, including fourteen new cases, with zero compiler warnings. No findings remain within unapproved initialization, pre-write review invalidation, serialized mutations, cause-preserving settlement, early/late cancellation and local reopen. Saved-path evidence, durable approval, external store mutation and automatic tool/UI integration are excluded.

Changed session/prompt identities and renewed acceptance are in the [current stored-session table](../../../../../../../main/scala/com/weisgnosis/agents/harness/plan/README.md#current-stored-session-identities).

## Model control verification

[PlanToolsSpec](PlanToolsSpec.scala) covers inert construction, strict envelopes, typed entry/write, exact approval, decline, blocked-publisher supersession, cancellation/cleanup, publication failure, failure after committed approval and stored write failure. [PlanReviewPublisherSpec](PlanReviewPublisherSpec.scala) covers suspended delivery, exact ticket identity and publisher scope failure. [PlanToolResultSpec](PlanToolResultSpec.scala) covers every codec outcome. [PlanToolsIntegrationSpec](PlanToolsIntegrationSpec.scala) verifies full local-store ReAct approve/decline flows under Allow rules and Parallel configuration; only the plan controls require sequential execution. Fifteen new cases cover this increment. No additional entire method in the fourteen-method manager/middleware/propagation inventory is closed.

- [x] `review_fp` and `review_harness` approved the final seven-file model-control increment against `bfca341` on 2026-09-29 after strengthening the concurrency fixture. Full `sbt scalafmtAll scalafmtSbt verify` passed 556 core / 549 harness tests, including fifteen new cases, with zero compiler warnings. No findings remain within strict inputs, suspended/scoped delivery, mandatory exact-ticket review, failure/cancellation handling and actual ReAct ordering under Allow/Parallel configuration. Authenticated UI transport, saved-path evidence, durable handoff and child propagation are excluded.

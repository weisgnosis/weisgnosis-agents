# Plan-mode verification

- [PlanShellInspectionSpec](PlanShellInspectionSpec.scala): nine cases for strict command envelopes, exact argv, shell syntax rejection, unsupported binaries/wrappers, positive argument shapes, option/stdin rejection, bounded line counts, size/Unicode boundaries and whitespace normalization.
- [PosixReadCommandSpec](PosixReadCommandSpec.scala): two cases for fixed program identities and typed rejection codecs.

These tests execute no shell and establish no path/process isolation. The [current contract and current identity tables](../../../../../../../main/scala/com/weisgnosis/agents/harness/plan/README.md) define the narrow accepted scope.

- [x] `review_fp` and `review_harness` approved the final four-file static-shell increment against `2d4094b` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 461 harness tests, including ten new cases, with zero compiler warnings. No findings remain within bounded positive syntax inspection and immutable argv results. Runtime plan enforcement, process execution, path authorization and resource isolation are excluded.

- [x] Verify ten static syntax/type cases.
- [x] Verify permission integration, modified confirmation arguments and real runtime denials.
- [x] Adapt two upstream middleware methods through actual runtime admission.
- [ ] Adapt the other twelve upstream methods after their services exist.
- [ ] Verify child inheritance, safe plan persistence and authenticated exit approval.

## Admission verification

- [PlanModeSpec](PlanModeSpec.scala): one ADT/codec case for modes and trusted classifications.
- [PlanPermissionGateSpec](PlanPermissionGateSpec.scala): six cases for classification decisions, Build pass-through, lazy current-mode sampling, permission priority, strict input and cancellation cleanup.
- [PlanPermissionIntegrationSpec](PlanPermissionIntegrationSpec.scala): six real ReAct cases for persisted/streamed denials, inactive pass-through, confirmed replacement input, Plan activation during approval, mandatory execution-request confirmation without state transition, and already-admitted work followed by a denied next call.
- PlanShellInspectionSpec adds one exact-envelope case, bringing that suite to nine and the increment to fourteen new tests.

No test executes a process or proves filesystem isolation. In-memory plan management and host-approved transitions are implemented below; persistence, prompt projection and child propagation remain pending. The [admission contract and eight current identities](../../../../../../../main/scala/com/weisgnosis/agents/harness/plan/README.md#dynamic-plan-admission) record acceptance.

- [x] `review_fp` and `review_harness` approved the final eight-file plan-admission increment against `0ad16e5` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 475 harness tests, including fourteen new cases, with zero compiler warnings. No findings remain within typed mode/classification, exact command input, dynamic permission admission and actual ReAct denial/confirmation behavior. Process isolation, plan lifecycle/storage/prompts and child propagation are excluded.

## Session and review verification

- [PlanSessionStateSpec](PlanSessionStateSpec.scala): four cases for exact nonblank drafts, strict unbounded revisions, valid snapshot shapes and review outcome codecs.
- [PlanSessionSpec](PlanSessionSpec.scala): seven cases for draft-free rejection, exact approval, decline, edit/re-entry invalidation, invalid edits and direct gate mode integration.
- [PlanSessionReviewSpec](PlanSessionReviewSpec.scala): seven cases for current/older scopes, equal-revision foreign sessions, concurrent consumers, both valid edit/approval race outcomes, interruption and cleanup after an approved generation.

All eighteen cases use in-memory STM authority. No filesystem/UI/model-tool behavior or durable restoration is inferred. The [session contract and five identities](../../../../../../../main/scala/com/weisgnosis/agents/harness/plan/README.md#in-memory-plan-session-lifecycle) define the reviewed scope.

- [x] `review_fp` and `review_harness` approved the final five-file in-memory plan-session increment against `87b2d8b` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 493 harness tests, including eighteen new cases, with zero compiler warnings. No findings remain within typed snapshots, exact-ticket host review, atomic settlement, scoped cancellation and live gate integration. Paths/files, durable restoration, prompt projection and control-tool/authenticated-UI integration are excluded.

# Plan-mode verification

Toolkit construction and selection follow the [checked registry identity and migration contract](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#checked-registry-identity). The linked source/spec manifest owns the current migration review scope and verification; existing lifecycle and permission contracts remain in force.

- [PlanShellInspectionSpec](PlanShellInspectionSpec.scala): nine cases for strict command envelopes, exact argv, shell syntax rejection, unsupported binaries/wrappers, positive argument shapes, option/stdin rejection, bounded line counts, size/Unicode boundaries and whitespace normalization.
- [PosixReadCommandSpec](PosixReadCommandSpec.scala): two cases for fixed program identities and typed rejection codecs.

These tests execute no shell and establish no path/process isolation. The [current contract and current identity tables](../../../../../../../main/scala/com/weisgnosis/agents/harness/plan/README.md) define the narrow accepted scope.

- [x] `review_fp` and `review_harness` approved the final four-file static-shell increment against `2d4094b` on 2026-09-29. No findings remain within bounded positive syntax inspection and immutable argv results. Runtime plan enforcement, process execution, path authorization and resource isolation are excluded.

- [x] Verify ten static syntax/type cases.
- [x] Verify permission integration, modified confirmation arguments and real runtime denials.
- [x] Adapt two upstream middleware methods through actual runtime admission.
- [x] Adapt fresh Build no-hint behavior through request-only projection.
- [x] Adapt four manager/prompt methods through location context and one directory method through validated workspace selection.
- [x] Adapt the five child/control methods through opt-in installation and actual retained-session sends; all fourteen inventory methods have bounded mappings.
- [x] Verify scoped local persistence and exact-ticket host exit approval.
- [x] Verify host-installed live parent/child admission with independent child controls and exact-review authority.
- [x] Verify opt-in child-local tool/context installation with retained live ancestor restrictions.
- [ ] Integrate authenticated host UI routing and durable approval restoration.

The [tracker](../../../../../../../../../../docs/architecture/tracker.md#immediate-next-work) records the next repository task.

## Admission verification

- [PlanModeSpec](PlanModeSpec.scala): one ADT/codec case for modes and trusted classifications.
- [PlanPermissionGateSpec](PlanPermissionGateSpec.scala): seven groups for explicit versus admitted-tool classification decisions, Build pass-through, lazy current-mode sampling, permission priority, strict input and cancellation cleanup.
- [PlanPermissionIntegrationSpec](PlanPermissionIntegrationSpec.scala): eight real ReAct groups including supported parent spawn/send/task/cancel with denied child mutation, actual skill loading under Plan with host/policy denial and spoof-resistant access, persisted/streamed denials, inactive pass-through, confirmed replacement input, Plan activation during approval, mandatory execution-request confirmation without state transition, and already-admitted work followed by a denied next call.
- [PlanInheritedPermissionSpec](PlanInheritedPermissionSpec.scala): three actual SubagentFactory-spawned child scenarios cover parent Plan/child Build restrictions with child-owned PlanControl, RequestExecution Ask and unknown-name Deny; parent Build/child Plan retention until the child's exact reviewer approves; and live parent activation with authoritative modified-input rechecks and permitted controls.
- PlanShellInspectionSpec verifies exact authoritative input envelopes alongside command grammar.

The skill scenario uses a Promise barrier to exercise valid out-of-order parallel completion and compares exact sorted call-ID/state pairs without dropping duplicates. Both reviewers approved the corrected expectation; reproduced evidence and the current hash belong to the [admission contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/plan/README.md#current-admission-identities).

No test executes a process or proves filesystem isolation. The inherited gate is explicitly installed in the child's authorizer and changes neither session; opt-in factory installation has separate actual child evidence. The [admission contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/plan/README.md#dynamic-plan-admission) and [current identities](../../../../../../../main/scala/com/weisgnosis/agents/harness/plan/README.md#current-admission-identities) define acceptance.

`path_review` approved FP/ZIO correctness and `current_docs` approved invariants for the gate and earlier admission baseline with no findings. Full `scalafmtAll scalafmtSbt verify` passed with zero compiler warnings or failures (`skill-reminders-final-verify.log`); the linked contract records exact identities. Existing confirmation scenarios now also verify retained admitted-access evidence with replacement input and live mode changes. Unchanged inherited admission retains its acceptance.

The pinned Java comparison is `f98a685fdc4589f81e626e1686ac2c63d14a2304`. Declared/general-purpose installation and retained named sends now have explicit bounded mappings in the parity inventory. Host dependencies and request-only context replace automatic Java manager/middleware/configuration wiring; live OR admission replaces copied child mode flags. Deterministic keys, shell bypass, literal Java APIs and durable physical-file approval are not claimed.

## Session and review verification

- [PlanSessionStateSpec](PlanSessionStateSpec.scala): four cases for exact nonblank drafts, strict unbounded revisions, valid snapshot shapes and review outcome codecs.
- [PlanSessionSpec](PlanSessionSpec.scala): seven cases for draft-free rejection, exact approval, decline, edit/re-entry invalidation, invalid edits and direct gate mode integration.
- [PlanSessionReviewSpec](PlanSessionReviewSpec.scala): seven cases for current/older scopes, equal-revision foreign sessions, concurrent consumers, both valid edit/approval race outcomes, interruption and cleanup after an approved generation.

All eighteen cases use in-memory STM authority. No filesystem/UI/model-tool behavior or durable restoration is inferred. The [session contract and unchanged prior identities](../../../../../../../main/scala/com/weisgnosis/agents/harness/plan/README.md#in-memory-plan-session-lifecycle) define the reviewed scope.

- [x] `review_fp` and `review_harness` approved the final five-file in-memory plan-session increment against `87b2d8b` on 2026-09-29. No findings remain within typed snapshots, exact-ticket host review, atomic settlement, scoped cancellation and live gate integration. Paths/files, durable restoration, prompt projection and control-tool/authenticated-UI integration are excluded.

## Projection verification

- [PlanPromptSpec](PlanPromptSpec.scala): four cases for fresh Build, absent/unapproved drafts, exact pending revision/data and truthful approved context.
- [PlanInputTransformSpec](PlanInputTransformSpec.scala): six cases for lazy single sampling, final-user field preservation, complete pair preservation/new synthetic metadata, no accumulation, cancellation cleanup and defect propagation.
- [PlanInputIntegrationSpec](PlanInputIntegrationSpec.scala): three actual ReAct cases for pending-to-approved host review across model requests, unchanged canonical history, live gate denial after a reasoning-time mode change and rejection of incomplete history before the model runs.

The [projection contract and unchanged prior identities](../../../../../../../main/scala/com/weisgnosis/agents/harness/plan/README.md#request-only-plan-context) define the accepted scope. No filesystem/UI/child authority is supplied by prompt text.

- [x] `review_fp` and `review_harness` approved the final five-file plan-input increment against `132a81a` on 2026-09-29. No findings remain within truthful snapshot rendering, request-only projection, final-user coalescing, preserved canonical/system/tool history and live gate authority. Files/restoration and automatic tool/UI/child integration are excluded.

## Standalone draft-store verification

[LocalPlanDraftStoreSpec](LocalPlanDraftStoreSpec.scala) covers missing payloads, exact Unicode/line endings, reopen/replacement, live competitors, directory isolation, closed handles, concurrent complete reads, stale tokens, corrupt/blank input and unsafe directory rejection. [PlanDraftFilesSpec](PlanDraftFilesSpec.scala) covers hardlink preservation, malformed UTF-16 rollback, nonregular destinations and missing-versus-blank reads. Shared WorkspaceLeaseSpec additionally checks plan-specific error provenance and masked cancellation; AgentErrorSpec checks the new leaf codec. Storage is separate from session approval and closes no additional named plan-manager method.

- [x] `review_fp` and `review_harness` approved the final nine-file standalone plan-storage increment against `083617b` on 2026-09-29. No findings remain within exact content/encoding, scoped cooperative ownership, atomic replacement, hardlink preservation, typed errors and cancellation ordering. Session/publication integration, persisted approval, hostile filesystem isolation, crash durability and new whole-method upstream parity are excluded.

## Stored-session verification

[PlanStoredSessionSpec](PlanStoredSessionSpec.scala) covers missing/failed/defective/cancelled opening, actual local publication and reopen, Build exclusion, explicit re-entry and ambiguous typed/defective writes. [PlanStoredPublicationSpec](PlanStoredPublicationSpec.scala) covers pre-I/O invalidation, live permission denial, stale-scope closure, serialized writers, interruptible queued edits/entry/review/resolution, early/late cancellation, successful recovery and composite write/cleanup causes. Fourteen new cases preserve the existing in-memory and request-projection suites. Approval restoration and saved-path method parity remain excluded.

- [x] `review_fp` and `review_harness` approved the final six-file stored-session increment against `2a73621` on 2026-09-29. No findings remain within unapproved initialization, pre-write review invalidation, serialized mutations, cause-preserving settlement, early/late cancellation and local reopen. Saved-path evidence, durable approval, external store mutation and automatic tool/UI integration are excluded.

Changed session/prompt production identities and renewed acceptance are in the [current location-context table](../../../../../../../main/scala/com/weisgnosis/agents/harness/plan/README.md#current-location-context-identities). Unchanged stored-session test identities remain in its earlier table.

## Model control verification

[PlanToolsSpec](PlanToolsSpec.scala) covers inert construction, strict envelopes, typed entry/write, exact approval, decline, blocked-publisher supersession, cancellation/cleanup, publication failure, failure after committed approval and stored write failure. [PlanReviewPublisherSpec](PlanReviewPublisherSpec.scala) covers suspended delivery, exact ticket identity and publisher scope failure. [PlanToolResultSpec](PlanToolResultSpec.scala) covers every codec outcome. [PlanToolsIntegrationSpec](PlanToolsIntegrationSpec.scala) verifies full local-store ReAct approve/decline flows under Allow rules and Parallel configuration; only the plan controls require sequential execution. Fifteen new cases cover this increment. No additional entire method in the fourteen-method manager/middleware/propagation inventory is closed.

- [x] `review_fp` and `review_harness` approved the final seven-file model-control increment against `bfca341` on 2026-09-29 after strengthening the concurrency fixture. No findings remain within strict inputs, suspended/scoped delivery, mandatory exact-ticket review, failure/cancellation handling and actual ReAct ordering under Allow/Parallel configuration. Authenticated UI transport, saved-path evidence, durable handoff and child propagation are excluded.

## Location and context verification

[PlanContextSpec](PlanContextSpec.scala) covers exact descriptive values, strict codecs, all snapshot forms, quoted rendering and a reproduced supplementary-Unicode formatting defect with malformed/valid surrogate regressions. [PlanLocationIntegrationSpec](PlanLocationIntegrationSpec.scala) covers missing configured targets, actual writes, retained locations across review/entry/reopen, explicit custom directories, blocked/failed publication provenance and legacy location-free stores. [PlanContextInputSpec](PlanContextInputSpec.scala) covers one lazy context sample, preserved legacy APIs and actual configured-to-approved model requests without canonical/System/tool-history contamination. Fourteen new cases cover this increment.

- [x] `review_fp` and `review_harness` approved the final ten-file location-context increment against `fd496a4` on 2026-09-29 after reproducing and fixing supplementary Unicode FORMAT validation. No findings remain within validated descriptive locations, canonical local metadata, single-snapshot context, provenance wording and four explicit manager/prompt adaptations. Continuous file revalidation, approval restoration, arbitrary third-party store truthfulness and legacy workspace-relative slash rebasing are excluded.

## Directory configuration verification

[PlanDirectorySpec](PlanDirectorySpec.scala) supplies six cases for normalization/defaults, codec validation, traversal/native syntax, device stems, visible Unicode and the reproduced trim boundary-control defect. [PlanWorkspaceStoreSpec](PlanWorkspaceStoreSpec.scala) supplies five real-store cases for normalized publication/location, default-directory shared ownership, missing/non-directory inputs, sensitive/untrusted rejection and closed capability behavior. [PlanDraftFilesSpec](PlanDraftFilesSpec.scala) adds one canonical path-component containment case rejecting root equality, ancestors and prefix-sharing siblings. These twelve cases do not claim privileged dynamic link or hostile mutation coverage.

- [x] `review_fp` and `review_harness` approved the final six-file directory increment against `f147a99` on 2026-09-29 after reproducing and correcting boundary control normalization. No findings remain within validated logical directory selection, canonical strict containment, shared ownership and the explicit manager-method adaptation. Directory creation, hostile mutation isolation, dynamic privileged link fixtures and child propagation are excluded.

Current identities are in the [directory table](../../../../../../../main/scala/com/weisgnosis/agents/harness/plan/README.md#current-directory-identities).

- [x] `registry_contract` and `current_docs` approved current PlanInputTransform and PlanPermissionIntegrationSpec identities, plus the three child-installation files. Two [SubagentPlanInstallationSpec](../subagent/SubagentPlanInstallationSpec.scala) groups verify actual declared/general-purpose controls, local context/review, typed boundary/collision guards and named-session reuse across mode changes. Full `child-plan-verify.log` passed; the [current Plan acceptance](../../../../../../../main/scala/com/weisgnosis/agents/harness/plan/README.md#child-installation-acceptance) owns exact scope, identities and verification. No test executes a shell or proves physical child plan persistence.

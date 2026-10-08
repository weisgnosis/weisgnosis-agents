# Plan-mode verification

The [shared initialization contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/storage/README.md#scoped-initialization) corrects failed/interrupted local opening: ownership is released before failure reaches a still-live host Scope; successful resources retain the host lifetime. The unchanged-production regression failed before correction; same-host repair/reopen and exact cleanup-cause tests now pass.

Toolkit construction and selection follow the [checked registry identity and migration contract](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#checked-registry-identity).

- [PlanShellInspectionSpec](PlanShellInspectionSpec.scala): coverage of strict command envelopes, exact argv, shell syntax rejection, unsupported binaries/wrappers, positive argument shapes, option/stdin rejection, bounded line counts, size/Unicode boundaries and whitespace normalization.

These tests execute no shell and establish no path/process isolation. The [current contract and current identity tables](../../../../../../../main/scala/com/weisgnosis/agents/harness/plan/README.md) define the narrow accepted scope.

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
- [PlanPermissionGateSpec](PlanPermissionGateSpec.scala): coverage of explicit versus admitted-tool classification decisions, Build pass-through, lazy current-mode sampling, permission priority, strict input and cancellation cleanup.
- [PlanPermissionIntegrationSpec](PlanPermissionIntegrationSpec.scala): eight real ReAct groups including supported parent spawn/send/task/cancel with denied child mutation, actual skill loading under Plan with host/policy denial and spoof-resistant access, persisted/streamed denials, inactive pass-through, confirmed replacement input, Plan activation during approval, mandatory execution-request confirmation without state transition, and already-admitted work followed by a denied next call.
- [PlanInheritedPermissionSpec](PlanInheritedPermissionSpec.scala): three actual SubagentFactory-spawned child scenarios cover parent Plan/child Build restrictions with child-owned PlanControl, RequestExecution Ask and unknown-name Deny; parent Build/child Plan retention until the child's exact reviewer approves; and live parent activation with authoritative modified-input rechecks and permitted controls.
- PlanShellInspectionSpec verifies exact authoritative input envelopes alongside command grammar.

The skill scenario uses a Promise barrier to exercise valid out-of-order parallel completion and compares exact sorted call-ID/state pairs without dropping duplicates.

No test executes a process or proves filesystem isolation. The inherited gate is explicitly installed in the child's authorizer and changes neither session; opt-in factory installation has separate actual child evidence.

Existing confirmation scenarios now also verify retained admitted-access evidence with replacement input and live mode changes. Unchanged inherited admission retains its acceptance.

The pinned Java comparison is `f98a685fdc4589f81e626e1686ac2c63d14a2304`. Declared/general-purpose installation and retained named sends now have explicit bounded mappings in the parity inventory. Host dependencies and request-only context replace automatic Java manager/middleware/configuration wiring; live OR admission replaces copied child mode flags. Deterministic keys, shell bypass, literal Java APIs and durable physical-file approval are not claimed.

## Session and review verification

- [PlanSessionStateSpec](PlanSessionStateSpec.scala): coverage of exact nonblank drafts, strict unbounded revisions, valid snapshot shapes and review outcome codecs.
- [PlanSessionSpec](PlanSessionSpec.scala): coverage of draft-free rejection, exact approval, decline, edit/re-entry invalidation, invalid edits and direct gate mode integration.
- [PlanSessionReviewSpec](PlanSessionReviewSpec.scala): coverage of current/older scopes, equal-revision foreign sessions, concurrent consumers, both valid edit/approval race outcomes, interruption and cleanup after an approved generation.

All eighteen cases use in-memory STM authority. No filesystem/UI/model-tool behavior or durable restoration is inferred. The [session contract and unchanged prior identities](../../../../../../../main/scala/com/weisgnosis/agents/harness/plan/README.md#in-memory-plan-session-lifecycle) define the reviewed scope.

## Projection verification

- [PlanPromptSpec](PlanPromptSpec.scala): coverage of fresh Build, absent/unapproved drafts, exact pending revision/data and truthful approved context.
- [PlanInputTransformSpec](PlanInputTransformSpec.scala): coverage of lazy single sampling, final-user field preservation, complete pair preservation/new synthetic metadata, no accumulation, cancellation cleanup and defect propagation.
- [PlanInputIntegrationSpec](PlanInputIntegrationSpec.scala): three actual ReAct cases for pending-to-approved host review across model requests, unchanged canonical history, live gate denial after a reasoning-time mode change and rejection of incomplete history before the model runs.

The [projection contract and unchanged prior identities](../../../../../../../main/scala/com/weisgnosis/agents/harness/plan/README.md#request-only-plan-context) define the accepted scope. No filesystem/UI/child authority is supplied by prompt text.

## Standalone draft-store verification

[LocalPlanDraftStoreSpec](LocalPlanDraftStoreSpec.scala) covers missing payloads, exact Unicode/line endings, reopen/replacement, live competitors, directory isolation, closed handles, concurrent complete reads, stale tokens, corrupt/blank input and unsafe directory rejection. [PlanDraftFilesSpec](PlanDraftFilesSpec.scala) covers hardlink preservation, malformed UTF-16 rollback, nonregular destinations and missing-versus-blank reads. Shared WorkspaceLeaseSpec additionally checks plan-specific error provenance and masked cancellation; AgentErrorSpec checks the new leaf codec. Storage is separate from session approval and closes no additional named plan-manager method.

## Stored-session verification

[PlanStoredSessionSpec](PlanStoredSessionSpec.scala) covers missing/failed/defective/cancelled opening, actual local publication and reopen, Build exclusion, explicit re-entry and ambiguous typed/defective writes. [PlanStoredPublicationSpec](PlanStoredPublicationSpec.scala) covers pre-I/O invalidation, live permission denial, stale-scope closure, serialized writers, interruptible queued edits/entry/review/resolution, early/late cancellation, successful recovery and composite write/cleanup causes. Fourteen new cases preserve the existing in-memory and request-projection suites. Approval restoration and saved-path method parity remain excluded.

## Model control verification

[PlanToolsSpec](PlanToolsSpec.scala) covers inert construction, strict envelopes, typed entry/write, exact approval, decline, blocked-publisher supersession, cancellation/cleanup, publication failure, failure after committed approval and stored write failure. [PlanReviewPublisherSpec](PlanReviewPublisherSpec.scala) covers suspended delivery, exact ticket identity and publisher scope failure. [PlanToolResultSpec](PlanToolResultSpec.scala) covers every codec outcome. [PlanToolsIntegrationSpec](PlanToolsIntegrationSpec.scala) verifies full local-store ReAct approve/decline flows under Allow rules and Parallel configuration; only the plan controls require sequential execution. Checks cover this increment. No additional entire method in the fourteen-method manager/middleware/propagation inventory is closed.

## Location and context verification

[PlanContextSpec](PlanContextSpec.scala) covers exact descriptive values, strict codecs, all snapshot forms, quoted rendering and a reproduced supplementary-Unicode formatting defect with malformed/valid surrogate regressions. [PlanLocationIntegrationSpec](PlanLocationIntegrationSpec.scala) covers missing configured targets, actual writes, retained locations across review/entry/reopen, explicit custom directories, blocked/failed publication provenance and legacy location-free stores. [PlanContextInputSpec](PlanContextInputSpec.scala) covers one lazy context sample, preserved legacy APIs and actual configured-to-approved model requests without canonical/System/tool-history contamination. Checks cover this increment.

## Directory configuration verification

[PlanDirectorySpec](PlanDirectorySpec.scala) supplies coverage of normalization/defaults, codec validation, traversal/native syntax, device stems, visible Unicode and the reproduced trim boundary-control defect. [PlanWorkspaceStoreSpec](PlanWorkspaceStoreSpec.scala) supplies five real-store cases for normalized publication/location, default-directory shared ownership, missing/non-directory inputs, sensitive/untrusted rejection and closed capability behavior. [PlanDraftFilesSpec](PlanDraftFilesSpec.scala) adds one canonical path-component containment case rejecting root equality, ancestors and prefix-sharing siblings. These twelve cases do not claim privileged dynamic link or hostile mutation coverage.

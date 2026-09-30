# Plan-mode admission foundations

[PlanShellInspection.inspect](PlanShellInspection.scala) is a pure, conservative POSIX command-shape inspector. It accepts at most 4,096 characters from ASCII letters, digits, spaces and `_./-`, then applies a command-specific positive grammar. All other characters, including tabs/newlines, quotes, escapes, redirects, pipes, substitutions, separators and glob syntax, fail with a typed ShellRejection.

| Program | Supported arguments |
| --- | --- |
| pwd | None |
| ls | No argument, one operand, one short flag group using only a/l/h/d (at most four letters), or that flag group followed by one operand |
| cat | One or more explicit operands |
| head / tail | One operand, or -n followed by one to five ASCII digits with numeric value 1 through 10,000 (leading zeroes allowed) and one operand |
| wc | -l and one operand |

An operand must not begin with a hyphen. Implicit stdin, unknown options, wrappers, path-qualified executables, interpreters, Git, find and sed are rejected. ASCII spacing normalizes to immutable argv. Program names are case-sensitive. Unsupported syntax is denied rather than partially parsed or silently rewritten.

[PosixReadCommand](PosixReadCommand.scala) carries a fixed program identity and immutable arguments; it has no public constructor or command codec. The host must bind that identity to its trusted implementation and consume the parsed argv without shell re-parsing. This is static syntax eligibility only, not execution approval or a sandbox. Operands can still identify sensitive paths, symlinks, devices or unbounded data. Canonical path authorization, regular-file/device policy, trusted executables/environment, output/time limits and scoped process ownership remain required at the executor boundary. Prefer dedicated read capabilities where available. This package does not execute any command.

## Current acceptance and identities

Current APIs provide typed plan admission, scoped draft/review authority, stored publication, model controls, request-only context, validated workspace-relative directories and a host-installed parent/child admission gate. The [subagent package](../subagent/README.md#reusable-child-sessions) retains installed live gates across host-held session turns. Automatic nested/reused-child installation and authenticated host routing remain separate integration work. The [parity inventory](../../../../../../../../../../docs/architecture/test-parity.md#plan-mode-inventory) retains nine bounded method adaptations and five unfinished methods; the inherited gate does not complete automatic factory or persistent spawn/send mappings. Unchanged accepted source identities are retained below, with the current gate identity in the admission table.

| Source | Git blob |
| --- | --- |
| [PosixReadCommandSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PosixReadCommandSpec.scala) | 5010e0c6d9f880f2c6a6a4db4f34297a0dcb6fc3 |

- [x] `review_fp` and `review_harness` approved the final four-file static-shell increment against `2d4094b` on 2026-09-29. No findings remain within bounded positive syntax inspection and immutable argv results. Runtime plan enforcement, process execution, path authorization and resource isolation are excluded.

## Remaining integration

- [x] Implement and independently review static command grammar and typed results.
- [x] Inventory all fourteen pinned plan-mode methods without claiming completed runtime parity.
- [x] Add typed mode values, explicit host mode effects and trusted tool classifications, default-denying unknown tools while planning.
- [x] Install an opt-in permission gate that abstains for eligible calls, denies unsafe calls and rechecks confirmed input.
- [x] Verify actual ReAct denial persistence/events and mode-change admission boundaries.
- [x] Implement in-memory draft/review lifecycle with separately held host approval authority.
- [x] Implement request-only prompt projection from one live session snapshot.
- [x] Independently verify standalone draft storage, ownership, encoding and atomic publication.
- [x] Integrate stored publication with session generations and failure/cancellation settlement.
- [x] Implement strict model-facing controls and exact-ticket confirmed exit through an explicit publisher.
- [x] Add descriptive configured/observed locations and request-context projection.
- [x] Implement validated workspace-relative directory selection and its explicit upstream adaptation.
- [ ] Complete remaining child installation/propagation method parity.
- [ ] Integrate authenticated host UI routing in the consuming application.
- [x] Verify a host-installed live parent/child gate through configured child execution without transferring approval authority.
- [ ] Install restrictions automatically for nested/reused children before granting plan-mode access to their spawn/send tools.

Next task: integrate session routing and automatic child installation with explicit live plan gates. Host-held reusable sessions, resource ownership and fixed-session send tools are accepted within the subagent package's recorded scope.

- [x] Retain `review_docs` acceptance of the unchanged static-shell contracts against `2d4094b`; current source identities and limitations are recorded in the associated sections.

## Dynamic plan admission

[PlanMode](PlanMode.scala) distinguishes Build and Plan. The host supplies a suspended UIO[PlanMode] and an immutable exact-name Map[String, PlanToolAccess] to [PlanPermissionGate.make](PlanPermissionGate.scala). Construction samples no state; each evaluation samples current mode once. PlanSession below provides in-memory mode authority; this package does not durably persist mode or automatically register control tools. Model metadata and arguments cannot grant a registry classification.

`PlanPermissionGate.withAdmittedTools(mode, access = Map.empty)` opts into the actual runtime tool snapshot. Explicit host classifications always win, including constrained shell/Plan controls. Otherwise ReadOnly abstains and Mutating or absent evidence denies in Plan. Build still abstains. ReAct derives AdmittedToolAccess from the resolved Tool.isReadOnly accessor inside the suspended authorization boundary, rather than from ToolDef flags or model JSON. This is trusted local evidence, not a serialized approval capability. The installed skill loader can therefore run under ordinary policy while newly activated mutating tools remain denied.

The same evidence survives gate composition and positive-confirmation rechecks; input and live mode/policy are read again. Existing make/inherited gates retain their explicit closed maps. Legacy wrappers ignore the new argument unless they opt into forwarding; current SubagentFactory wrapping does not carry it. Automatic child metadata propagation and full declared/reused-child Plan installation remain pending. See the [permission migration contract](../permission/README.md#gate-and-confirmation-semantics).

| Mode / classification | Gate decision |
| --- | --- |
| Build, any tool/input | Abstain |
| Plan + ReadOnly or PlanControl | Abstain |
| Plan + RequestExecution | Ask |
| Plan + PosixReadCommand | Abstain only after exact-envelope and command inspection; otherwise Deny |
| Plan + Mutation or unknown name | Deny |

Abstention preserves the ordinary permission engine's authority; it is never an implicit Allow. Deny still outranks Ask and configured Allow rules. PlanControl is a trusted host classification for constrained planning capabilities, not a name-based exception or unrestricted filesystem write. Hosts must implement those constraints. Spawn/send/task names receive no implicit exemption; child enforcement must exist before such capabilities are admitted. RequestExecution requires confirmation even against Allow rules, but does not itself change mode, approve another call or implement safe plan exit.

Install this gate as InteractivePermissionAuthorizer's interceptorGate, or as one of its explicitly composed host gates. The runtime then owns assistant persistence, denial observations/events and actual dispatch. The authorizer reevaluates gates after confirmed input replacement, so a mutated shell command, an extra execution field or activation of Plan during approval can still deny execution. Gates are checked at admission; changing mode does not atomically revoke a decision or cancel already admitted work. A later invocation sees the newer mode. Cancellation during mode evaluation preserves cleanup and yields no manufactured decision.

PlanShellInspection.inspectInput accepts exactly one JSON object field named command with a string value. Missing, duplicate, nonstring and extra fields all produce InvalidInput. The same boundary must be used by a host command executor, consuming the resulting program/argv without shell re-parsing. Raw cached tool content is not inspected instead of authoritative input. Migration: existing inspect(String) behavior is unchanged. ShellRejection now includes InvalidInput for the new envelope boundary; consumers with exhaustive matches or serialized-case handling must support that additional variant. Build mode leaves normal tool-schema/input validation to the host/runtime. Static eligibility still supplies no filesystem/process authority; all executor responsibilities above remain applicable.

The [test index](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/README.md) records actual runtime evidence. The admission increment adapts two upstream middleware methods, and projection adds a third and location context adds four more; directory configuration and skill-loader admission bring the inventory to nine adaptations; five still require child installation/propagation work.

### Host-installed inherited admission

`PlanPermissionGate.inherited(parentMode, childMode, access)` samples the two supplied mode effects lazily on each evaluation, parent first and child second, then delegates to the same capability inspection as `make`. Either Plan sample restricts the child; only two Build samples abstain. Reads change neither PlanSession and transfer no review ticket, approval or mode-management authority. Parent Build therefore cannot release a child that is still planning. Child PlanControl tools remain bound to the child's own control and exact host reviewer.

Install the returned gate explicitly in the configured child's InteractivePermissionAuthorizer. Positive confirmation rechecks both live modes and the authoritative modified input before dispatch. Mutation/unknown names remain Deny, RequestExecution remains Ask, and read/shell eligibility preserves underlying permission policy. The samples are not an atomic cross-session snapshot, and later mode changes do not revoke an already admitted execution.

This is a host adapter rather than automatic factory wiring. It does not register tools, copy parent plan-directory/prompt configuration, install nested gates, grant default exemptions to `agent_spawn`, `agent_task`, `agent_cancel` or `agent_send`, or implement persistent reuse. Hosts must retain the correct live mode sources. The reviewed [SubagentConstruction](../subagent/README.md) path installs live ancestor/child admission in derived factories and retained sessions; raw child paths still require explicit configuration.

## Current admission identities

`path_review` approved FP/ZIO correctness and `current_docs` approved invariants for the current gate and two changed admission suites, with no findings. The scope covers opt-in admitted-tool fallback, explicit host precedence, actual skill loading followed by mutation denial, model spoof rejection and confirmation rechecks retaining evidence while sampling live mode. Unchanged inherited admission retains its acceptance. Full `scalafmtAll scalafmtSbt verify` passed with zero compiler warnings or failed tests (`plan-admission-verify.log`); current totals belong to the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance). Automatic child metadata forwarding, full factory/reuse installation and post-admission revocation are excluded.

| Source | Git blob |
| --- | --- |
| [PlanMode.scala](PlanMode.scala) | 8ee5910af797c790f08ac61cfa0cf7f4cf03ab38 |
| [PlanPermissionGate.scala](PlanPermissionGate.scala) | b56772173cf6027f441a5adbc8b5cb352856f2d4 |
| [PlanInheritedPermissionSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanInheritedPermissionSpec.scala) | f3ea0134c152a784c2fec24835390f9e50af3e3c |
| [PlanShellInspection.scala](PlanShellInspection.scala) | 9bcbef0da418bba1707d018ee653ff5700045b61 |
| [PosixReadCommand.scala](PosixReadCommand.scala) | b1279b9d804743bc8e9df9fc46f0f4fd44a4bee9 |
| [PlanModeSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanModeSpec.scala) | 269fe9cba0e22e4bfbda8735ed9d4dc7b43c1e52 |
| [PlanPermissionGateSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanPermissionGateSpec.scala) | ae9f03e2d791c575d0703986401a3d1addc83cbe |
| [PlanPermissionIntegrationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanPermissionIntegrationSpec.scala) | aa81b48245b831a8bcf3a798547cb364933124e7 |
| [PlanShellInspectionSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanShellInspectionSpec.scala) | 06263d26f34e47b65a869298ac9d3db515d0fe7c |

- [x] Retain `review_fp`, `review_harness` and `review_docs` acceptance against `0ad16e5` for unchanged typed classifications, exact command input and ordinary admission integration.
- [x] Both reviewers approved the current opt-in gate and admission suites; unchanged configured-child integration retains its reviewed identity above.

## In-memory plan-session lifecycle

[PlanSession.make](PlanSession.scala) returns two capabilities. Give model-facing tools only control: snapshot, mode, enter, replaceDraft and scoped requestReview. Retain reviewer with the authenticated host; only reviewer.resolve can approve or decline. There is no unconditional exit or approval method on the control interface. The service itself authenticates no user and automatically registers no tools or UI routing; opt-in PlanTools are implemented below.

[PlanSessionSnapshot](PlanSessionState.scala) distinguishes Build(approved), Planning(optional draft) and AwaitingReview(draft, revision). PlanDraft is validated nonblank text and retains its exact contents. PlanRevision is a nonnegative unbounded integer with a strict numeric codec; it cannot wrap at a machine integer boundary. Snapshot/decision/outcome codecs represent data, not permission to restore state or manufacture an active review. PlanSession.make starts fresh in Build without an approved plan. The additive stored factory below starts in Plan with unapproved stored content.

Enter moves to Planning, retaining draft content from an earlier planning or approved state. Every explicit re-entry starts a new generation and invalidates any prior pending review/approval. Draft replacement requires Plan mode; malformed input and attempts to replace in Build leave state unchanged. RequestReview requires a concrete draft, captures that exact text and a fresh revision, and supersedes any older request. Merely entering does not imply that a plan exists, was written to disk or was approved.

One TRef and each request's TPromise share STM authority. Re-entry, edits and newer requests atomically settle older waiters with Superseded as they publish the new state. Host resolution matches the exact currently pending ticket identity, so foreign sessions, equal revision numbers, stale tickets and replay cannot consume approval. Approve atomically records the reviewed draft/revision in Build and settles Approved; Decline settles Declined and preserves the draft in Planning. Exactly one concurrent resolution can apply. This is once-only consumption of a live request, not durable or exactly-once external delivery.

Request registration uses acquireRelease. The owning Scope must remain alive while the host reviews: await review.await within that scope, or deliberately retain a host owner scope. Returning a ticket from a tool whose scope immediately closes cancels it; tickets are not durable handoff records. Scope closure or interruption settles Cancelled only if that exact ticket is still current. Closing an older scope cannot cancel a replacement, undo approval or alter a later generation. No lock is held while waiting for human input.

Pass session.control.mode directly to PlanPermissionGate to avoid mirrored flags. The existing admission-only rule remains: a mode transition affects later checks without revoking already admitted tools. For PlanSession.make, drafts and approvals are in memory only; the stored factory below persists drafts but still keeps approval in memory only. The in-memory factory claims no plan-file path, file existence, filesystem publication, restore protocol or model-facing control tool; request projection is implemented below. The upstream manager's live mode assertions are adapted with stricter review authority; its path recording/retention assertions are now adapted by location context below.

## Current session identities

Three unchanged identities from the five-file increment against 87b2d8b. Their earlier PlanSession and PlanSessionReviewSpec identities are preserved in 2a73621. The current PlanSession identity is in the location-context table; PlanSessionReviewSpec remains in the stored-session table.

| Source | Git blob |
| --- | --- |
| [PlanSessionState.scala](PlanSessionState.scala) | 09a0c147ce95c82376ee9f5931edacd8c8c56668 |
| [PlanSessionStateSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanSessionStateSpec.scala) | 26a3059f88ed81cb695054449a03b1e85b76156e |
| [PlanSessionSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanSessionSpec.scala) | e9ffabb278cc8bd420cf7a7721bb69f45f6a3261 |

- [x] `review_fp` and `review_harness` approved the final five-file in-memory plan-session increment against `87b2d8b` on 2026-09-29. No findings remain within typed snapshots, exact-ticket host review, atomic settlement, scoped cancellation and live gate integration. Paths/files, durable restoration, prompt projection and control-tool/authenticated-UI integration are excluded.

- [x] Retain `review_docs` acceptance of the unchanged plan-session contracts against `87b2d8b`; current source identities and limitations are recorded in the associated sections.

## Request-only plan context

[PlanPrompt.render](PlanPrompt.scala) describes one PlanSessionSnapshot. Fresh Build contributes no reminder. Planning without a reviewable draft truthfully reports that state; a concrete draft is identified as unapproved. AwaitingReview names the exact pending revision. Build with ApprovedPlan identifies the exact host-reviewed revision and draft without claiming execution has occurred or bypassing ordinary tool permissions. No file/path existence is inferred. Draft content is JSON-quoted and labeled as context data; quoting is presentation, not an instruction-injection security boundary.

[PlanInputTransform.make](PlanInputTransform.scala) accepts a suspended snapshot read and samples it exactly once for each model request. Install it through ReActAgent.make(modelInputTransform = ...), normally passing session.control.snapshot and using the same session.control.mode for PlanPermissionGate. A prompt is guidance; if mode changes during reasoning, the current gate still governs tool admission. The snapshot and later permission check are deliberately separate observations, not a lock spanning model work.

Build(None) returns the supplied history unchanged. Otherwise the transform appends a request-only User reminder. If the final message is already User, validated withContent appends one text block to that message, preserving its original identity, name, metadata, timestamp, usage and content prefix; the existing user message is not marked synthetic. For other final roles or empty history, a new User named system has agentscope_synthetic=true and agentscope_reminder_kind=plan_context. Only that newly created message is marked synthetic.

Ordered System messages and complete tool pairs remain unchanged. The core rewrite validator still rejects incomplete tool history when the projection changes it; fresh Build preserves the unchanged-history equality fast path. Canonical agent history is never replaced by the request view, so repeated model requests do not accumulate reminders or contaminate transcript/memory inputs. Snapshot cancellation and defects propagate without a permissive fallback. This adapter does not compose other transforms automatically; hosts installing multiple request projections remain responsible for explicit composition and the same validation contract.

Migration from upstream: request-only User context replaces System-prompt mutation. Fresh Build preserves the no-hint behavior. Legacy snapshot-only projection has no location metadata; the additive context projection below supplies configured/observed location data without presenting in-memory content as an existing approved file. The upstream Build-hint test checks recorded path text, not actual file existence. Opt-in controls are implemented below; authenticated host routing, durable restoration and child propagation remain separate work.

## Current projection identities

Two unchanged identities from the five-file increment against 132a81a; PlanInputTransform is now preserved in fd496a4 and replaced in the location table. Their earlier PlanPrompt and PlanPromptSpec identities are preserved in 2a73621. Current PlanPrompt is in the location-context table; PlanPromptSpec remains in the stored-session table.

| Source | Git blob |
| --- | --- |
| [PlanInputTransformSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanInputTransformSpec.scala) | a63dee4e6d8d1258c2f020fce788f07547a2c230 |
| [PlanInputIntegrationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanInputIntegrationSpec.scala) | aef817a3b44b424ce94894e4fb412d54b6753d03 |

- [x] `review_fp` and `review_harness` approved the final five-file plan-input increment against `132a81a` on 2026-09-29. No findings remain within truthful snapshot rendering, request-only projection, final-user coalescing, preserved canonical/system/tool history and live gate authority. Files/restoration and automatic tool/UI/child integration are excluded.

- [x] Retain `review_docs` acceptance of the unchanged projection contracts against `132a81a`; current source identities and limitations are recorded in the associated sections.

## Standalone draft storage

[PlanDraftStore](PlanDraftStore.scala) provides read: IO[AgentError, Option[PlanDraft]] and write(PlanDraft): IO[AgentError, Unit]. This runtime capability has no codec. [LocalPlanDraftStore.open](LocalPlanDraftStore.scala) acquires one scoped cooperative owner for a fixed PLAN.md in an existing explicitly selected HostControlled directory. It does not create directories, discover sessions, accept model-selected paths or restore session mode/approval. A persisted draft is content only.

The private [PlanDraftFiles](PlanDraftFiles.scala) validates raw traversal, sensitive paths, canonical directory location and non-symlink regular destinations. Reads and writes use the same .plan-writer.lease, independently of memory/transcript payload ownership. Opening validates existing content; a missing file is None, whereas blank text and malformed UTF-8 fail. Text is preserved exactly, including Unicode, whitespace and line endings. Malformed UTF-16 fails writing without replacement characters or overwriting the previous target.

Publication writes a scoped temporary file in the same directory and requires atomic replacement. There is no non-atomic fallback. Replacing a hard-linked target leaves its other name unchanged. Temporary cleanup is best effort with a warning on failure; it does not change a completed publication into a typed failure. Reads/writes revalidate their location and destination. These checks assume host-controlled filesystem mutation: they are not a race-free sandbox or a guarantee of file-object identity. Dynamic symlink/junction attacks, filesystem root aliases, fsync/power-loss durability, size limits and remote stores are outside this backend's contract.

Lease acquisition refuses live and stale owners without deleting their token. After admission, filesystem work is masked until it finishes; ordinary pending cancellation is delivered before successful return, so interruption can follow a completed publication. Scope release waits for admitted work. Closed handles reject further reads/writes. Slow I/O can delay cancellation/shutdown. Ownership verification or release failure remains observable as a scope-finalizer defect; it never silently deletes a changed token.

AgentError.PlanStorageError distinguishes plan I/O, invalid stored content and ownership/closed-handle failures. Path policy continues to use PermissionDenied. Existing memory/transcript owner errors remain MemoryStorageError. Migration: exhaustive matches on the sealed AgentError hierarchy must include PlanStorageError; its individual JSON codec is derived. This does not make finalizer defects ordinary typed failures.

The standalone backend itself changes no session state; the stored-session integration below connects publication to PlanSession. Opt-in model-facing controls are implemented below; location-aware prompts are implemented below; restoration of session mode/approval remains pending. No additional complete upstream method is claimed: the current inventory has nine adaptations and five pending methods; see the parity inventory.

## Current draft-storage identities

Three unchanged identities from the nine-file increment against 083617b. AgentError and AgentErrorSpec identities are preserved in 279b7a3 and replaced in the [lineage table](../subagent/README.md#current-lineage-identities). Earlier store/files identities are preserved in fd496a4; changed PlanDraftFilesSpec, LocalPlanDraftStore and PlanDraftFiles identities are preserved in f147a99 and replaced in the directory table. PlanDraftStore remains in the location table. The original storage migration's earlier AgentError, AgentErrorSpec and WorkspaceLease identities are preserved in 083617b.

| Source | Git blob |
| --- | --- |
| [WorkspaceLease.scala](../storage/WorkspaceLease.scala) | 409edda77d0fcf5b8846d1cc89e3050d190d2740 |
| [LocalPlanDraftStoreSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/LocalPlanDraftStoreSpec.scala) | 564df5e17f6c8c0998e060aea890adcf0bed5fc7 |
| [WorkspaceLeaseSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/storage/WorkspaceLeaseSpec.scala) | 631725c6b84598e9cc2b3641f0b33a7ec5b691bc |

- [x] `review_fp` and `review_harness` approved the final nine-file standalone plan-storage increment against `083617b` on 2026-09-29. No findings remain within exact content/encoding, scoped cooperative ownership, atomic replacement, hardlink preservation, typed errors and cancellation ordering. Session/publication integration, persisted approval, hostile filesystem isolation, crash durability and new whole-method upstream parity are excluded.

- [x] Retain `review_docs` acceptance of the unchanged standalone plan-storage contracts against `083617b`; current source identities and limitations are recorded in the associated sections.

## Stored-session publication and review

PlanSession.stored(store) reads the injected PlanDraftStore once and starts in Plan. Existing content becomes an unapproved draft; missing content starts without a reviewable draft. Failed, defective or interrupted reads yield no session and retain their causes. The factory creates no directory or store scope. The host must keep the supplied store alive for the session lifetime and assign one PlanSession as the sole publication/review authority for that logical store. Reusing a raw store through multiple sessions or direct writes bypasses this coordination. External edits are not automatically read, authenticated or reflected in cached session state.

The same STM state and exact-ticket review authority serve both factories. A per-session semaphore serializes entry, draft replacement, review registration and host resolution. It is acquired before the scoped request-registration mask, so waiting operations remain interruptible. The semaphore is never held while waiting for a human decision. Snapshot/mode reads remain independent and available during writes. Current-ticket scope finalizers use STM directly; a superseded scope cannot cancel publication or a newer request.

A stored replacement first rejects Build without touching the backend. In Plan, one transaction settles the previous review as Superseded, advances the generation and enters an internal Publishing state. That state exposes Planning(None): there is no current reviewable draft, even if old or newly published bytes exist on disk. This meaning also applies after ambiguous failure; the prompt no longer claims that no draft has ever been recorded.

The backend write runs with the caller's normal cancellation policy restored. After it succeeds, a short masked transaction publishes the exact new draft as unapproved Planning content. Typed failure, defect, interruption or cleanup failure clears the reviewable draft and propagates the complete cause. Neither old approval nor the previous review request is restored. A later successful replacement can establish a new reviewable generation. Re-entry after failure does not reload or resurrect the discarded draft.

Queued cancellation changes no state. Cancellation during writing can follow external publication and leaves no reviewable draft when observed as a write failure. If the write completes successfully despite pending cancellation, masked state publication may make the new unapproved draft visible before late interruption is delivered. No interrupted outcome proves that filesystem publication was rolled back. Explicit caller masking is preserved. Backend cleanup finishes before the mutation permit is released; slow or explicitly masked backend work can delay shutdown.

Storage callbacks must not await mutation methods or review resolution on the same session: those operations need the permit held during publication. Snapshot/mode reads are safe. The semaphore coordinates one session, not independent store clients, distributed hosts or remote transactions. The local backend's lease separately coordinates cooperative filesystem ownership.

Migration: PlanSessionControl.replaceDraft now returns IO[AgentError, Unit], widening its former InvalidMessage-only channel so stored failures retain their identity. Callers explicitly declaring the narrower channel must widen or handle storage errors. PlanSession.make retains initial Build behavior and atomic in-memory replacement. Snapshot and review DTO shapes are unchanged. Existing stored text initializes Plan rather than restoring Build or pending approval, and ticket identities remain session-local. The initial stored-session increment provided no location identity; descriptive location context is now implemented below. It still provides no durable approval record or automatic tool/UI registration; no additional whole upstream plan method is marked complete.

## Current stored-session identities

Four unchanged identities from the six-file increment against 2a73621; changed PlanSession and PlanPrompt identities are preserved in fd496a4 and replaced in the location table. Earlier changed identities remain in that revision.

| Source | Git blob |
| --- | --- |
| [PlanPromptSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanPromptSpec.scala) | 4b9f7d2127023a6f3d37645c3b281ccb1edaf3fd |
| [PlanSessionReviewSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanSessionReviewSpec.scala) | 1cafefc8ef32aea7f391991665b30eb175b01c58 |
| [PlanStoredPublicationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanStoredPublicationSpec.scala) | 3e7c47eb387816b6ece46d1224ab89fdd9a4b6ff |
| [PlanStoredSessionSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanStoredSessionSpec.scala) | 0fd9a44108f060d8383d65631c57cf7faff8e5e7 |

- [x] `review_fp` and `review_harness` approved the final six-file stored-session increment against `2a73621` on 2026-09-29. No findings remain within unapproved initialization, pre-write review invalidation, serialized mutations, cause-preserving settlement, early/late cancellation and local reopen. Saved-path evidence, durable approval, external store mutation and automatic tool/UI integration are excluded.

- [x] Retain `review_docs` acceptance of the unchanged stored-session contracts against `2a73621`; current source identities and limitations are recorded in the associated sections.

## Model-facing controls and host review delivery

[PlanTools.make](PlanTools.scala) creates an immutable Toolkit using only PlanSessionControl and [PlanReviewPublisher](PlanReviewPublisher.scala). Construction invokes neither capability. Merge it into an existing toolkit using the collision-checking registration/combination APIs. It installs no permission rules, publisher transport, UI or global mode. The host separately retains PlanReviewAuthority and authenticates decision delivery.

| Tool | Exact accepted input | Completed observation |
| --- | --- | --- |
| plan_enter | Empty JSON object | EnteredPlan |
| plan_write | Exactly one nonblank string content field | DraftReplaced |
| plan_exit | Empty object, or exactly one string summary field | ReviewSettled(outcome) |

Unknown fields, duplicates, explicit nulls, wrong types and blank drafts are rejected before execution. The public JSON schemas forbid extra properties; runtime validation independently enforces the exact envelope. A summary is optional untrusted display text, never the approved draft or a credential. The publisher receives the runtime PlanReviewRequest containing the exact ticket/draft/revision and summary. It must present that exact draft for authenticated host review; only the separately held reviewer can resolve the ticket.

All three definitions are mutating and not concurrency-safe. PlanTools.access classifies these exact implementations as PlanControl, including plan_exit. Its mandatory internal exact-ticket review supplies the execution handoff; classifying it RequestExecution would add a separate generic Ask before that review. Ordinary independent permission rules/gates still apply, and no implicit Allow is installed. A host selecting Allow for these controls cannot bypass plan_exit's internal review. Do not apply these trusted classifications to unrelated implementations merely because their names match.

Exit owns a Scope containing request registration, publisher resources and the subsequent review wait. Publication must finish before waiting continues. Supersession settles the ticket immediately but does not cancel a blocked publisher; the tool remains pending until publication finishes or is interrupted. Hosts should publish/deliver promptly rather than await a separate workflow inside that callback. No background delivery, timeout, durable approval handoff or UI routing is provided.

Publisher failure closes the scope and cancels only a still-current request. Interruption preserves cancellation and waits for scoped cleanup. Stale finalizers cannot undo another generation or an approval already committed by the host. In particular, failure/interruption after an authenticated approval may leave Build active: delivery failure does not roll back the decision. The result describes the settled review occurrence, not a promise that current mode remains unchanged after delivery.

[PlanToolResult](PlanToolResult.scala) has a derived codec and distinguishes entry, draft replacement and all review outcomes. Declined, Superseded and Cancelled are delivered control observations; when successfully encoded, Toolkit marks them Success, which does not mean approval. Only Approved carries the exact reviewed draft/revision. Typed/defective tool failures become Error observations through the existing Toolkit boundary; interruption remains cancellation. That generic boundary does not preserve all composite failure details in tool output. No output invents a saved file path or completed plan execution.

The same controls work with make and stored sessions. In-memory DraftReplaced does not claim disk persistence. Stored failures become Error observations and leave no reviewable draft according to the stored-session contract. Integration tests use a real local store, normal permission admission and Parallel agent configuration, with only the plan controls requiring sequential batch execution. They verify assistant persistence before actions, a denied mutation before review, exact host approve/decline, subsequent live gate behavior and ordered result history/events.

This is an explicit adaptation of the pinned PlanModeTools implementation: strict total input replaces coercion, separate exact-ticket review replaces an unversioned generic permission exit, and typed observations replace path claims. The control increment alone closes no additional named method; location context below adds four adaptations. The current inventory has nine adaptations and five pending methods for child installation and propagation.

## Current model-control identities

Final formatted seven-file increment against bfca341.

| Source | Git blob |
| --- | --- |
| [PlanReviewPublisher.scala](PlanReviewPublisher.scala) | ae1ef6ba98941228500602a494defe7cfa3ab92e |
| [PlanToolResult.scala](PlanToolResult.scala) | 5d816a3438c938b1df1e5e3afb2924f9dc742018 |
| [PlanTools.scala](PlanTools.scala) | b4319d9c0bef3e8a6861a8a22ffff30364b75398 |
| [PlanReviewPublisherSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanReviewPublisherSpec.scala) | 417dd96dfb195dd28a65fbdda7bc6ac2288ea058 |
| [PlanToolResultSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanToolResultSpec.scala) | c910dc8c1d439941a31bbbe448c864a05a3021ee |
| [PlanToolsIntegrationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanToolsIntegrationSpec.scala) | 6977f7ece6a49a632721a56b9b7268e23ac2a3c1 |
| [PlanToolsSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanToolsSpec.scala) | f0a2f26074b60baf5dbc30b07c1ac7330fd663cd |

- [x] `review_fp` and `review_harness` approved the final seven-file model-control increment against `bfca341` on 2026-09-29 after strengthening the concurrency fixture. No findings remain within strict inputs, suspended/scoped delivery, mandatory exact-ticket review, failure/cancellation handling and actual ReAct ordering under Allow/Parallel configuration. Authenticated UI transport, saved-path evidence, durable handoff and child propagation are excluded.

- [x] Retain `review_docs` acceptance of the unchanged model-control contracts against `bfca341`; current source identities and limitations are recorded in the associated sections.

## Descriptive locations and context provenance

[PlanLocation](PlanContext.scala) is validated display text, not a filesystem capability. It preserves exact text and has a strict string codec. Total code-point validation rejects blank text, ISO controls, Unicode FORMAT characters including supplementary tag characters, line/paragraph separators and malformed UTF-16; legitimate supplementary visible characters are preserved. It performs no path resolution or normalization. A relative-looking or remote description does not authorize traversal or any I/O.

PlanDraftStore.location defaults to None for existing/custom stores. LocalPlanDraftStore supplies the canonical configured directory's fixed PLAN.md destination after validation. A local destination whose display text is invalid fails opening with InvalidConfiguration before lease acquisition. Canonical path and ownership checks remain in the local backend; the metadata codec cannot manufacture those capabilities. The host must provide truthful immutable location metadata for custom stores.

PlanSession.stored captures optional location once, alongside its initial store read. PlanSessionControl.location defaults to None and the concrete stored session retains the captured value through entry, replacement, review, approval and failure. PlanSessionControl.context reads exactly one snapshot and associates it with that immutable location. PlanContext has a derived codec for data exchange, not restoration, path authorization or approval credentials. Reopening local content retains the descriptive destination but initializes an unapproved Plan draft.

PlanPrompt.fromContext and PlanInputTransform.fromContext are additive opt-in APIs. The existing render(snapshot) and make(UIO[PlanSessionSnapshot]) remain location-free. The new transform samples one context per model request and uses the same validated user coalescing and canonical-history isolation. System messages and tool pairs remain unchanged. Fresh Build without an approved draft still contributes no hint, even if a context has configured location metadata.

Planning(None), including active/failed publication, labels the location only as a configured draft destination. That state does not establish whether a file is missing, old or ambiguously replaced. A current draft associates the location with the session's last successful read/publication. Approved Build additionally states that approval applies to the captured draft and revision, not arbitrary current contents at the path. Location text is JSON-quoted as data; it is never interpolated into executable syntax. Quoting is presentation, not a complete prompt-injection defense.

This is recorded provenance, not continuous existence or content revalidation. External edits, independent sessions and raw store writes remain outside the session's exclusive-publication contract. No default workspace directory is created or discovered. Native open(Path) accepts an explicit existing relative or absolute directory and preserves native path meaning. The additive logical-directory adapter below supports workspace-relative slash normalization through a separate validated type.

## Current location-context identities

Eight unchanged identities from the ten-file increment against fd496a4, including the corrected code-point validator. Changed LocalPlanDraftStore and PlanDraftFiles identities are preserved in f147a99 and replaced in the directory table. Earlier identities remain in their recorded revisions.

| Source | Git blob |
| --- | --- |
| [PlanContext.scala](PlanContext.scala) | 37e323f303235e6bdce70c22da21aafd596266e1 |
| [PlanDraftStore.scala](PlanDraftStore.scala) | 478bd5c4de6c8eb39c6d4b857f09c171d3c2639b |
| [PlanInputTransform.scala](PlanInputTransform.scala) | 837dad25472ab01d561ec26781b33b5564cc3625 |
| [PlanPrompt.scala](PlanPrompt.scala) | ec2b932b615ef600235e6ece6da21191f662a7ec |
| [PlanSession.scala](PlanSession.scala) | 2bd0ebde5a2d1e95e0cb223f3f68f74ec321dabd |
| [PlanContextInputSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanContextInputSpec.scala) | 0662a99969dce13aadec27a8768c9de47ed088db |
| [PlanContextSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanContextSpec.scala) | 619b5e074a59bd73a70178e6e44010edaff1ed82 |
| [PlanLocationIntegrationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanLocationIntegrationSpec.scala) | f38b42608f683db7c0b18c5c8a383e58e990a387 |

- [x] `review_fp` and `review_harness` approved the final ten-file location-context increment against `fd496a4` on 2026-09-29 after reproducing and fixing supplementary Unicode FORMAT validation. No findings remain within validated descriptive locations, canonical local metadata, single-snapshot context, provenance wording and four explicit manager/prompt adaptations. Continuous file revalidation, approval restoration, arbitrary third-party store truthfulness and legacy workspace-relative slash rebasing are excluded.

- [x] Retain `review_docs` acceptance of the unchanged location-context contracts against `fd496a4`; current source identities and limitations are recorded in the associated sections.

## Workspace-relative plan directories

[PlanDirectory](PlanDirectory.scala) is a validated logical workspace-relative descriptor, separate from native Path. Blank or slash-only input selects plans. Unicode outer whitespace and leading/trailing forward slashes normalize away, so /docs/plans/ becomes docs/plans. Non-whitespace controls remain visible to validation: NUL/SOH are rejected, including during JSON decoding. Internal empty segments, dot/parent segments, hidden formatting, malformed UTF-16, leading/trailing segment whitespace, trailing dots, native path punctuation and reserved device stems are rejected. Valid visible Unicode and internal spaces are retained. The string codec validates and emits the normalized value; it grants no filesystem authority.

[LocalPlanDraftStore.inWorkspace](LocalPlanDraftStore.scala) accepts an explicit workspace Path, WorkspaceTrust and optional PlanDirectory (default plans). It canonicalizes the existing workspace and candidate through the same sensitive-path/trust checks as native open. The candidate must be a strict path-component descendant of the canonical workspace; equal paths, ancestors and prefix-sharing siblings are denied. Only after validation does the exact validated PlanDraftFiles acquire its scoped lease and perform the initial read. Native open(Path) keeps its previous relative/absolute semantics. No directory is created, and no workspace is discovered implicitly.

Both entry points share the same canonical directory lease, fixed PLAN.md publication and descriptive location. Competing opens are denied in either direction; use after scope closure fails. Hosts must create the selected directory and protect the workspace, candidate and their ancestors under HostControlled ownership. Canonical checks are not race-free isolation against hostile mutation; privileged dynamic symlink/junction fixtures remain separate platform validation. These changes restore no approval and grant no child capability.

The explicit customPlanDirNormalised adaptation now brings the named inventory to eight of fourteen. Directory normalization is stricter than the upstream string helper and requires existing directories. Skill integration and full declared/general-purpose Plan tool/prompt installation remain pending. Inline child configuration is available through SubagentDeclaration. Opt-in SubagentConstruction now wires admission without copying or mutating child review state.

## Current directory identities

Final formatted six-file increment against f147a99, including the reproduced boundary-control regression and strip correction.

| Source | Git blob |
| --- | --- |
| [PlanDirectory.scala](PlanDirectory.scala) | ea44ad54387a7740036cf13426552a8d2e3eafb3 |
| [LocalPlanDraftStore.scala](LocalPlanDraftStore.scala) | 956baf6fdda01f07eb67fe813b3c425cb0684f3d |
| [PlanDraftFiles.scala](PlanDraftFiles.scala) | 3f91a78cd27db273d672aaeb092bee8acf02faf1 |
| [PlanDirectorySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanDirectorySpec.scala) | 58baa98395e59ab3314e090cac54c9b6b8cc66ec |
| [PlanWorkspaceStoreSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanWorkspaceStoreSpec.scala) | fd907878d89368b10bcd75c9483941b8d964a27c |
| [PlanDraftFilesSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanDraftFilesSpec.scala) | 2123dde5a64c716a5e76df7a1d0cfaba208361a2 |

- [x] `review_fp` and `review_harness` approved the final six-file directory increment against `f147a99` on 2026-09-29 after reproducing and correcting boundary control normalization. No findings remain within validated logical directory selection, canonical strict containment, shared ownership and the explicit manager-method adaptation. Directory creation, hostile mutation isolation, dynamic privileged link fixtures and child propagation are excluded.

- [x] Retain `review_docs` acceptance of the unchanged directory contracts against `f147a99`; current source identities and limitations are recorded in the associated sections.

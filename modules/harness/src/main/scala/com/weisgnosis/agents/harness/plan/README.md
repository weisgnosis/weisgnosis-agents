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

The earlier placeholder PlanModeState/PlanModeInterceptor/PlanModeTools APIs were design sketches, not implementations. Typed mode and PermissionGate admission are implemented below. In-memory plan management and host-confirmed mode transitions are implemented below; stored-session publication is implemented below; control tools and child propagation remain pending; standalone draft storage is described below; request-only prompt projection is implemented below. The original syntax-only increment classified no complete upstream method; the later admission increment adapts two middleware methods. The [parity inventory](../../../../../../../../../../docs/architecture/test-parity.md#plan-mode-inventory) lists all fourteen named methods, with eleven still pending after the projection adaptation below. The single unchanged identity from the earlier four-file grammar scope remains below; the three changed identities are preserved in 0ad16e5 and replaced in the current admission table.

| Source | Git blob |
| --- | --- |
| [PosixReadCommandSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PosixReadCommandSpec.scala) | 5010e0c6d9f880f2c6a6a4db4f34297a0dcb6fc3 |

- [x] `review_fp` and `review_harness` approved the final four-file static-shell increment against `2d4094b` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 461 harness tests, including ten new cases, with zero compiler warnings. No findings remain within bounded positive syntax inspection and immutable argv results. Runtime plan enforcement, process execution, path authorization and resource isolation are excluded.

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
- [ ] Implement model-facing confirmed exit tools and authenticated host routing.
- [ ] Propagate restrictions to new and reused children before admitting spawn/send tools.

- [x] `review_docs` approved corrected static-shell documentation against `2d4094b`: four identities, ten tests, exact grammar and fourteen pending upstream methods. Full verification passed 555 core / 461 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed. Runtime enforcement and executor/path/sandbox authority remain explicitly outside this scope.

## Dynamic plan admission

[PlanMode](PlanMode.scala) distinguishes Build and Plan. The host supplies a suspended UIO[PlanMode] and an immutable exact-name Map[String, PlanToolAccess] to [PlanPermissionGate.make](PlanPermissionGate.scala). Construction samples no state; each evaluation samples current mode once. PlanSession below provides in-memory mode authority; this package does not durably persist mode or automatically register control tools. Model metadata and arguments cannot grant a registry classification.

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

The [test index](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/README.md) records actual runtime evidence. The admission increment adapts two upstream middleware methods, and the projection below adds a third; eleven still require path lifecycle, skill/subagent capabilities and propagation work.

## Current admission identities

Final formatted eight-file increment against 0ad16e5; prior changed grammar identities are preserved in that revision.

| Source | Git blob |
| --- | --- |
| [PlanMode.scala](PlanMode.scala) | 8ee5910af797c790f08ac61cfa0cf7f4cf03ab38 |
| [PlanPermissionGate.scala](PlanPermissionGate.scala) | 5d2345418158a239eca24822fbb88eb1db980017 |
| [PlanShellInspection.scala](PlanShellInspection.scala) | 9bcbef0da418bba1707d018ee653ff5700045b61 |
| [PosixReadCommand.scala](PosixReadCommand.scala) | b1279b9d804743bc8e9df9fc46f0f4fd44a4bee9 |
| [PlanModeSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanModeSpec.scala) | 269fe9cba0e22e4bfbda8735ed9d4dc7b43c1e52 |
| [PlanPermissionGateSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanPermissionGateSpec.scala) | 89d9dbadbe1a0b47e9a4c927dfd87ee437d53222 |
| [PlanPermissionIntegrationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanPermissionIntegrationSpec.scala) | 649c4aa0e3e07ad348e66f73ee81cad859429f3e |
| [PlanShellInspectionSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanShellInspectionSpec.scala) | 06263d26f34e47b65a869298ac9d3db515d0fe7c |

- [x] `review_fp` and `review_harness` approved the final eight-file plan-admission increment against `0ad16e5` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 475 harness tests, including fourteen new cases, with zero compiler warnings. No findings remain within typed mode/classification, exact command input, dynamic permission admission and actual ReAct denial/confirmation behavior. Process isolation, plan lifecycle/storage/prompts and child propagation are excluded.

- [x] `review_docs` approved corrected plan-admission documentation against `0ad16e5`, including the InvalidInput migration note and stale status wording in AGENTS.md. Eight identities, fourteen new tests, two upstream adaptations and twelve pending methods are verified. Full verification passed 555 core / 475 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed. No findings remain in this scope.

## In-memory plan-session lifecycle

[PlanSession.make](PlanSession.scala) returns two capabilities. Give model-facing tools only control: snapshot, mode, enter, replaceDraft and scoped requestReview. Retain reviewer with the authenticated host; only reviewer.resolve can approve or decline. There is no unconditional exit or approval method on the control interface. The service itself authenticates no user and automatically registers no tools or UI routing.

[PlanSessionSnapshot](PlanSessionState.scala) distinguishes Build(approved), Planning(optional draft) and AwaitingReview(draft, revision). PlanDraft is validated nonblank text and retains its exact contents. PlanRevision is a nonnegative unbounded integer with a strict numeric codec; it cannot wrap at a machine integer boundary. Snapshot/decision/outcome codecs represent data, not permission to restore state or manufacture an active review. PlanSession.make starts fresh in Build without an approved plan. The additive stored factory below starts in Plan with unapproved stored content.

Enter moves to Planning, retaining draft content from an earlier planning or approved state. Every explicit re-entry starts a new generation and invalidates any prior pending review/approval. Draft replacement requires Plan mode; malformed input and attempts to replace in Build leave state unchanged. RequestReview requires a concrete draft, captures that exact text and a fresh revision, and supersedes any older request. Merely entering does not imply that a plan exists, was written to disk or was approved.

One TRef and each request's TPromise share STM authority. Re-entry, edits and newer requests atomically settle older waiters with Superseded as they publish the new state. Host resolution matches the exact currently pending ticket identity, so foreign sessions, equal revision numbers, stale tickets and replay cannot consume approval. Approve atomically records the reviewed draft/revision in Build and settles Approved; Decline settles Declined and preserves the draft in Planning. Exactly one concurrent resolution can apply. This is once-only consumption of a live request, not durable or exactly-once external delivery.

Request registration uses acquireRelease. The owning Scope must remain alive while the host reviews: await review.await within that scope, or deliberately retain a host owner scope. Returning a ticket from a tool whose scope immediately closes cancels it; tickets are not durable handoff records. Scope closure or interruption settles Cancelled only if that exact ticket is still current. Closing an older scope cannot cancel a replacement, undo approval or alter a later generation. No lock is held while waiting for human input.

Pass session.control.mode directly to PlanPermissionGate to avoid mirrored flags. The existing admission-only rule remains: a mode transition affects later checks without revoking already admitted tools. For PlanSession.make, drafts and approvals are in memory only; the stored factory below persists drafts but still keeps approval in memory only. The in-memory factory claims no plan-file path, file existence, filesystem publication, restore protocol or model-facing control tool; request projection is implemented below. The upstream manager's live mode assertions are adapted with stricter review authority; its path recording/retention assertions remain pending.

## Current session identities

Three unchanged identities from the five-file increment against 87b2d8b. Changed PlanSession and PlanSessionReviewSpec identities are preserved in 2a73621 and replaced in the stored-session table below.

| Source | Git blob |
| --- | --- |
| [PlanSessionState.scala](PlanSessionState.scala) | 09a0c147ce95c82376ee9f5931edacd8c8c56668 |
| [PlanSessionStateSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanSessionStateSpec.scala) | 26a3059f88ed81cb695054449a03b1e85b76156e |
| [PlanSessionSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanSessionSpec.scala) | e9ffabb278cc8bd420cf7a7721bb69f45f6a3261 |

- [x] `review_fp` and `review_harness` approved the final five-file in-memory plan-session increment against `87b2d8b` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 493 harness tests, including eighteen new cases, with zero compiler warnings. No findings remain within typed snapshots, exact-ticket host review, atomic settlement, scoped cancellation and live gate integration. Paths/files, durable restoration, prompt projection and control-tool/authenticated-UI integration are excluded.

- [x] `review_docs` approved corrected plan-session documentation against `87b2d8b`, including current baseline architecture/module summaries. Five identities, eighteen new cases and bounded 2/14 parity status are verified. Full verification passed 555 core / 493 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed. Projection, persistence, control tools and child propagation remain explicitly pending.

## Request-only plan context

[PlanPrompt.render](PlanPrompt.scala) describes one PlanSessionSnapshot. Fresh Build contributes no reminder. Planning without a draft truthfully reports that absence; a concrete draft is identified as unapproved. AwaitingReview names the exact pending revision. Build with ApprovedPlan identifies the exact host-reviewed revision and draft without claiming execution has occurred or bypassing ordinary tool permissions. No file/path existence is inferred. Draft content is JSON-quoted and labeled as context data; quoting is presentation, not an instruction-injection security boundary.

[PlanInputTransform.make](PlanInputTransform.scala) accepts a suspended snapshot read and samples it exactly once for each model request. Install it through ReActAgent.make(modelInputTransform = ...), normally passing session.control.snapshot and using the same session.control.mode for PlanPermissionGate. A prompt is guidance; if mode changes during reasoning, the current gate still governs tool admission. The snapshot and later permission check are deliberately separate observations, not a lock spanning model work.

Build(None) returns the supplied history unchanged. Otherwise the transform appends a request-only User reminder. If the final message is already User, validated withContent appends one text block to that message, preserving its original identity, name, metadata, timestamp, usage and content prefix; the existing user message is not marked synthetic. For other final roles or empty history, a new User named system has agentscope_synthetic=true and agentscope_reminder_kind=plan_context. Only that newly created message is marked synthetic.

Ordered System messages and complete tool pairs remain unchanged. The core rewrite validator still rejects incomplete tool history when the projection changes it; fresh Build preserves the unchanged-history equality fast path. Canonical agent history is never replaced by the request view, so repeated model requests do not accumulate reminders or contaminate transcript/memory inputs. Snapshot cancellation and defects propagate without a permissive fallback. This adapter does not compose other transforms automatically; hosts installing multiple request projections remain responsible for explicit composition and the same validation contract.

Migration from upstream: request-only User context replaces System-prompt mutation. Fresh Build preserves the no-hint behavior. Other prompt methods' path assertions remain pending because the projection has no saved-location provenance; approved in-memory content is never presented as an existing approved file. The upstream Build-hint test checks recorded path text, not actual file existence. Model-facing controls, authenticated host routing, durable restoration and child propagation remain separate work.

## Current projection identities

Three unchanged identities from the five-file increment against 132a81a. Changed PlanPrompt and PlanPromptSpec identities are preserved in 2a73621 and replaced in the stored-session table below.

| Source | Git blob |
| --- | --- |
| [PlanInputTransform.scala](PlanInputTransform.scala) | e7d07aafaca556099df9c55b84dcf26f95035070 |
| [PlanInputTransformSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanInputTransformSpec.scala) | a63dee4e6d8d1258c2f020fce788f07547a2c230 |
| [PlanInputIntegrationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanInputIntegrationSpec.scala) | aef817a3b44b424ce94894e4fb412d54b6753d03 |

- [x] `review_fp` and `review_harness` approved the final five-file plan-input increment against `132a81a` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 506 harness tests, including thirteen new cases, with zero compiler warnings. No findings remain within truthful snapshot rendering, request-only projection, final-user coalescing, preserved canonical/system/tool history and live gate authority. Files/restoration and automatic tool/UI/child integration are excluded.

- [x] `review_docs` approved corrected projection documentation against `132a81a`: five identities, thirteen new cases, truthful scope and 3/14 parity classification. Full verification passed 555 core / 506 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed. Recorded-path assertions, core equality validation behavior and live permission authority remain explicit. No findings remain in this scope.

## Standalone draft storage

[PlanDraftStore](PlanDraftStore.scala) provides read: IO[AgentError, Option[PlanDraft]] and write(PlanDraft): IO[AgentError, Unit]. This runtime capability has no codec. [LocalPlanDraftStore.open](LocalPlanDraftStore.scala) acquires one scoped cooperative owner for a fixed PLAN.md in an existing explicitly selected HostControlled directory. It does not create directories, discover sessions, accept model-selected paths or restore session mode/approval. A persisted draft is content only.

The private [PlanDraftFiles](PlanDraftFiles.scala) validates raw traversal, sensitive paths, canonical directory location and non-symlink regular destinations. Reads and writes use the same .plan-writer.lease, independently of memory/transcript payload ownership. Opening validates existing content; a missing file is None, whereas blank text and malformed UTF-8 fail. Text is preserved exactly, including Unicode, whitespace and line endings. Malformed UTF-16 fails writing without replacement characters or overwriting the previous target.

Publication writes a scoped temporary file in the same directory and requires atomic replacement. There is no non-atomic fallback. Replacing a hard-linked target leaves its other name unchanged. Temporary cleanup is best effort with a warning on failure; it does not change a completed publication into a typed failure. Reads/writes revalidate their location and destination. These checks assume host-controlled filesystem mutation: they are not a race-free sandbox or a guarantee of file-object identity. Dynamic symlink/junction attacks, filesystem root aliases, fsync/power-loss durability, size limits and remote stores are outside this backend's contract.

Lease acquisition refuses live and stale owners without deleting their token. After admission, filesystem work is masked until it finishes; ordinary pending cancellation is delivered before successful return, so interruption can follow a completed publication. Scope release waits for admitted work. Closed handles reject further reads/writes. Slow I/O can delay cancellation/shutdown. Ownership verification or release failure remains observable as a scope-finalizer defect; it never silently deletes a changed token.

AgentError.PlanStorageError distinguishes plan I/O, invalid stored content and ownership/closed-handle failures. Path policy continues to use PermissionDenied. Existing memory/transcript owner errors remain MemoryStorageError. Migration: exhaustive matches on the sealed AgentError hierarchy must include PlanStorageError; its individual JSON codec is derived. This does not make finalizer defects ordinary typed failures.

The standalone backend itself changes no session state; the stored-session integration below connects publication to PlanSession. Model-facing control tools, saved-path prompts and restoration of session mode/approval remain pending. No additional complete upstream method is claimed: the inventory remains three adaptations and eleven pending methods.

## Current draft-storage identities

Final formatted nine-file increment against 083617b. Earlier changed AgentError, AgentErrorSpec and WorkspaceLease identities are preserved in that revision.

| Source | Git blob |
| --- | --- |
| [AgentError.scala](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/error/AgentError.scala) | ccc65bd076cc44a53cf85720dc035331030803e9 |
| [AgentErrorSpec.scala](../../../../../../../../../core/src/test/scala/com/weisgnosis/agents/core/error/AgentErrorSpec.scala) | f32ca3dd7cca41e95a1a0518ad84f9522ff5193a |
| [LocalPlanDraftStore.scala](LocalPlanDraftStore.scala) | 0a6f886340ad675b07adb85b3c5a2f19ca565a08 |
| [PlanDraftFiles.scala](PlanDraftFiles.scala) | 3e6907fa0d80603514a56477f23730e3328206ac |
| [PlanDraftStore.scala](PlanDraftStore.scala) | c997151bbbfa36dc996ebed970beb52ceaf473f2 |
| [WorkspaceLease.scala](../storage/WorkspaceLease.scala) | 409edda77d0fcf5b8846d1cc89e3050d190d2740 |
| [LocalPlanDraftStoreSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/LocalPlanDraftStoreSpec.scala) | 564df5e17f6c8c0998e060aea890adcf0bed5fc7 |
| [PlanDraftFilesSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanDraftFilesSpec.scala) | 6ec27d1d45c1259dfcc7bd7984c9be3fa515dd1c |
| [WorkspaceLeaseSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/storage/WorkspaceLeaseSpec.scala) | 631725c6b84598e9cc2b3641f0b33a7ec5b691bc |

- [x] `review_fp` and `review_harness` approved the final nine-file standalone plan-storage increment against `083617b` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 556 core / 520 harness tests, including fifteen new cases, with zero compiler warnings. No findings remain within exact content/encoding, scoped cooperative ownership, atomic replacement, hardlink preservation, typed errors and cancellation ordering. Session/publication integration, persisted approval, hostile filesystem isolation, crash durability and new whole-method upstream parity are excluded.

- [x] `review_docs` approved corrected standalone plan-storage documentation against `083617b`: nine identities, fifteen new cases, 556 core / 520 harness tests and unchanged 3/14 plan parity. All 63 Markdown files passed local-link/script checks and git diff --check passed. Session/publication integration and persisted approval remain pending; no findings remain within this scope.

## Stored-session publication and review

PlanSession.stored(store) reads the injected PlanDraftStore once and starts in Plan. Existing content becomes an unapproved draft; missing content starts without a reviewable draft. Failed, defective or interrupted reads yield no session and retain their causes. The factory creates no directory or store scope. The host must keep the supplied store alive for the session lifetime and assign one PlanSession as the sole publication/review authority for that logical store. Reusing a raw store through multiple sessions or direct writes bypasses this coordination. External edits are not automatically read, authenticated or reflected in cached session state.

The same STM state and exact-ticket review authority serve both factories. A per-session semaphore serializes entry, draft replacement, review registration and host resolution. It is acquired before the scoped request-registration mask, so waiting operations remain interruptible. The semaphore is never held while waiting for a human decision. Snapshot/mode reads remain independent and available during writes. Current-ticket scope finalizers use STM directly; a superseded scope cannot cancel publication or a newer request.

A stored replacement first rejects Build without touching the backend. In Plan, one transaction settles the previous review as Superseded, advances the generation and enters an internal Publishing state. That state exposes Planning(None): there is no current reviewable draft, even if old or newly published bytes exist on disk. This meaning also applies after ambiguous failure; the prompt no longer claims that no draft has ever been recorded.

The backend write runs with the caller's normal cancellation policy restored. After it succeeds, a short masked transaction publishes the exact new draft as unapproved Planning content. Typed failure, defect, interruption or cleanup failure clears the reviewable draft and propagates the complete cause. Neither old approval nor the previous review request is restored. A later successful replacement can establish a new reviewable generation. Re-entry after failure does not reload or resurrect the discarded draft.

Queued cancellation changes no state. Cancellation during writing can follow external publication and leaves no reviewable draft when observed as a write failure. If the write completes successfully despite pending cancellation, masked state publication may make the new unapproved draft visible before late interruption is delivered. No interrupted outcome proves that filesystem publication was rolled back. Explicit caller masking is preserved. Backend cleanup finishes before the mutation permit is released; slow or explicitly masked backend work can delay shutdown.

Storage callbacks must not await mutation methods or review resolution on the same session: those operations need the permit held during publication. Snapshot/mode reads are safe. The semaphore coordinates one session, not independent store clients, distributed hosts or remote transactions. The local backend's lease separately coordinates cooperative filesystem ownership.

Migration: PlanSessionControl.replaceDraft now returns IO[AgentError, Unit], widening its former InvalidMessage-only channel so stored failures retain their identity. Callers explicitly declaring the narrower channel must widen or handle storage errors. PlanSession.make retains initial Build behavior and atomic in-memory replacement. Snapshot and review DTO shapes are unchanged. Existing stored text initializes Plan rather than restoring Build or pending approval, and ticket identities remain session-local. This integration provides no saved-path identity, durable approval record or automatic tool/UI registration; no additional whole upstream plan method is marked complete.

## Current stored-session identities

Final formatted six-file increment against 2a73621. Earlier changed identities remain in that revision.

| Source | Git blob |
| --- | --- |
| [PlanPrompt.scala](PlanPrompt.scala) | ba4679b355faed6c695ef82e9dfd0d8fa086ed04 |
| [PlanSession.scala](PlanSession.scala) | 47d50c2131f284db31424fc4b1481d59555ce4a6 |
| [PlanPromptSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanPromptSpec.scala) | 4b9f7d2127023a6f3d37645c3b281ccb1edaf3fd |
| [PlanSessionReviewSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanSessionReviewSpec.scala) | 1cafefc8ef32aea7f391991665b30eb175b01c58 |
| [PlanStoredPublicationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanStoredPublicationSpec.scala) | 3e7c47eb387816b6ece46d1224ab89fdd9a4b6ff |
| [PlanStoredSessionSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanStoredSessionSpec.scala) | 0fd9a44108f060d8383d65631c57cf7faff8e5e7 |

- [x] `review_fp` and `review_harness` approved the final six-file stored-session increment against `2a73621` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 556 core / 534 harness tests, including fourteen new cases, with zero compiler warnings. No findings remain within unapproved initialization, pre-write review invalidation, serialized mutations, cause-preserving settlement, early/late cancellation and local reopen. Saved-path evidence, durable approval, external store mutation and automatic tool/UI integration are excluded.

- [x] `review_docs` approved corrected stored-session documentation against `2a73621`: six identities, fourteen new cases, 556 core / 534 harness tests and unchanged 3/14 plan parity. Ownership, failure/cancellation limits and the widened error channel are explicit. All 63 Markdown files passed local-link/script checks and git diff --check passed. No findings remain in this scope.

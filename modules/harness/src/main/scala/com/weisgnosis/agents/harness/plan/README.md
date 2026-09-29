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

The earlier placeholder PlanModeState/PlanModeInterceptor/PlanModeTools APIs were design sketches, not implementations. Typed mode and PermissionGate admission are implemented below. In-memory plan management and host-confirmed mode transitions are implemented below; persistence, prompt projection, safe plan-file storage and child propagation remain pending. The original syntax-only increment classified no complete upstream method; the later admission increment adapts two middleware methods. The [parity inventory](../../../../../../../../../../docs/architecture/test-parity.md#plan-mode-inventory) lists all fourteen named methods, with twelve still pending. The single unchanged identity from the earlier four-file grammar scope remains below; the three changed identities are preserved in 0ad16e5 and replaced in the current admission table.

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
- [ ] Implement prompt projection, safe plan storage and model-facing confirmed exit tools.
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

The [test index](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/README.md) records actual runtime evidence. Two upstream middleware methods are adapted; the remaining twelve require plan lifecycle, prompt projection, skill/subagent capabilities and propagation work.

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

[PlanSessionSnapshot](PlanSessionState.scala) distinguishes Build(approved), Planning(optional draft) and AwaitingReview(draft, revision). PlanDraft is validated nonblank text and retains its exact contents. PlanRevision is a nonnegative unbounded integer with a strict numeric codec; it cannot wrap at a machine integer boundary. Snapshot/decision/outcome codecs represent data, not permission to restore state or manufacture an active review. This service always starts fresh in Build without an approved plan.

Enter moves to Planning, retaining draft content from an earlier planning or approved state. Every explicit re-entry starts a new generation and invalidates any prior pending review/approval. Draft replacement requires Plan mode; malformed input and attempts to replace in Build leave state unchanged. RequestReview requires a concrete draft, captures that exact text and a fresh revision, and supersedes any older request. Merely entering does not imply that a plan exists, was written to disk or was approved.

One TRef and each request's TPromise share STM authority. Re-entry, edits and newer requests atomically settle older waiters with Superseded as they publish the new state. Host resolution matches the exact currently pending ticket identity, so foreign sessions, equal revision numbers, stale tickets and replay cannot consume approval. Approve atomically records the reviewed draft/revision in Build and settles Approved; Decline settles Declined and preserves the draft in Planning. Exactly one concurrent resolution can apply. This is once-only consumption of a live request, not durable or exactly-once external delivery.

Request registration uses acquireRelease. The owning Scope must remain alive while the host reviews: await review.await within that scope, or deliberately retain a host owner scope. Returning a ticket from a tool whose scope immediately closes cancels it; tickets are not durable handoff records. Scope closure or interruption settles Cancelled only if that exact ticket is still current. Closing an older scope cannot cancel a replacement, undo approval or alter a later generation. No lock is held while waiting for human input.

Pass session.control.mode directly to PlanPermissionGate to avoid mirrored flags. The existing admission-only rule remains: a mode transition affects later checks without revoking already admitted tools. Drafts and approvals here are in memory only. No plan-file path, file existence, filesystem publication, restore protocol, request projection or model-facing control tool is claimed. The upstream manager's live mode assertions are adapted with stricter review authority; its path recording/retention assertions remain pending.

## Current session identities

Final formatted five-file increment against 87b2d8b.

| Source | Git blob |
| --- | --- |
| [PlanSessionState.scala](PlanSessionState.scala) | 09a0c147ce95c82376ee9f5931edacd8c8c56668 |
| [PlanSession.scala](PlanSession.scala) | 1260abd21b03ca7981826a38a16b4d0ca2bf4dec |
| [PlanSessionStateSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanSessionStateSpec.scala) | 26a3059f88ed81cb695054449a03b1e85b76156e |
| [PlanSessionSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanSessionSpec.scala) | e9ffabb278cc8bd420cf7a7721bb69f45f6a3261 |
| [PlanSessionReviewSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanSessionReviewSpec.scala) | 012286158fd256e1a5cfae0afc8ac7741f09b117 |

- [x] `review_fp` and `review_harness` approved the final five-file in-memory plan-session increment against `87b2d8b` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 493 harness tests, including eighteen new cases, with zero compiler warnings. No findings remain within typed snapshots, exact-ticket host review, atomic settlement, scoped cancellation and live gate integration. Paths/files, durable restoration, prompt projection and control-tool/authenticated-UI integration are excluded.

- [x] `review_docs` approved corrected plan-session documentation against `87b2d8b`, including current baseline architecture/module summaries. Five identities, eighteen new cases and bounded 2/14 parity status are verified. Full verification passed 555 core / 493 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed. Projection, persistence, control tools and child propagation remain explicitly pending.

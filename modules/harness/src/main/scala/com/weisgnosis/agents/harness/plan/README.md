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

The earlier placeholder PlanModeState/PlanModeInterceptor/PlanModeTools APIs were design sketches, not implementations. Typed mode state, PermissionGate integration, prompt projection, safe plan-file storage, confirmed exit and child propagation are still pending. No entire upstream plan test method is classified as complete by this syntax-only increment; the [parity inventory](../../../../../../../../../../docs/architecture/test-parity.md#plan-mode-inventory) lists all fourteen named methods.

| Source | Git blob |
| --- | --- |
| [PosixReadCommand.scala](PosixReadCommand.scala) | 191ed957ad7f69f183bae57be227dbef6f742718 |
| [PlanShellInspection.scala](PlanShellInspection.scala) | e42f9a34617f4e709a6207f8156a648f1c964fd8 |
| [PosixReadCommandSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PosixReadCommandSpec.scala) | 5010e0c6d9f880f2c6a6a4db4f34297a0dcb6fc3 |
| [PlanShellInspectionSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/plan/PlanShellInspectionSpec.scala) | 2348bd2211ad6aa226569f081e96c5d81a4012cf |

- [x] `review_fp` and `review_harness` approved the final four-file static-shell increment against `2d4094b` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 461 harness tests, including ten new cases, with zero compiler warnings. No findings remain within bounded positive syntax inspection and immutable argv results. Runtime plan enforcement, process execution, path authorization and resource isolation are excluded.

## Remaining integration

- [x] Implement and independently review static command grammar and typed results.
- [x] Inventory all fourteen pinned plan-mode methods without claiming completed runtime parity.
- [ ] Add typed mode authority and host tool classifications, default-denying unknown tools while planning.
- [ ] Install a permission gate that abstains for eligible calls, denies unsafe calls and rechecks confirmed input.
- [ ] Verify actual ReAct denial persistence/events and mode-change admission boundaries.
- [ ] Implement prompt projection, safe plan storage and confirmed plan exit.
- [ ] Propagate restrictions to new and reused children before admitting spawn/send tools.

- [x] `review_docs` approved corrected static-shell documentation against `2d4094b`: four identities, ten tests, exact grammar and fourteen pending upstream methods. Full verification passed 555 core / 461 harness tests; all 63 Markdown files passed local-link/script checks and git diff --check passed. Runtime enforcement and executor/path/sandbox authority remain explicitly outside this scope.

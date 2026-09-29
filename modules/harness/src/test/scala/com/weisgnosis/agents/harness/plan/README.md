# Plan-mode verification

- [PlanShellInspectionSpec](PlanShellInspectionSpec.scala): eight cases for exact argv, shell syntax rejection, unsupported binaries/wrappers, positive argument shapes, option/stdin rejection, bounded line counts, size/Unicode boundaries and whitespace normalization.
- [PosixReadCommandSpec](PosixReadCommandSpec.scala): two cases for fixed program identities and typed rejection codecs.

These tests execute no shell and establish no path/process isolation. The [current contract and four-file identity table](../../../../../../../main/scala/com/weisgnosis/agents/harness/plan/README.md) define the narrow accepted scope.

- [x] `review_fp` and `review_harness` approved the final four-file static-shell increment against `2d4094b` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 461 harness tests, including ten new cases, with zero compiler warnings. No findings remain within bounded positive syntax inspection and immutable argv results. Runtime plan enforcement, process execution, path authorization and resource isolation are excluded.

- [x] Verify ten static syntax/type cases.
- [ ] Verify permission integration, modified confirmation arguments and real runtime denials.
- [ ] Adapt the fourteen upstream plan-mode methods after their corresponding services exist.
- [ ] Verify child inheritance, safe plan persistence and authenticated exit approval.

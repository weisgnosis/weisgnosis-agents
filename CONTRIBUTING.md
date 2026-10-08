# Contributing

Read [AGENTS.md](AGENTS.md) for the Pure FP, Scala 3/ZIO 2 and runtime invariant requirements. Use JDK 21 and the Scala/dependency/sbt versions pinned in the build.

## Deliver a working behavior

1. Select one concrete outcome from the [library checklist](docs/architecture/tracker.md). Define what a caller will observe and which failure/cancellation boundaries matter.
2. Implement the smallest coherent change using existing abstractions. Add proportionate tests for independent contracts; exercise the actual integration path instead of stopping at a converter or mock boundary.
3. For a confirmed defect, first reproduce the specific assertion failure against unchanged production. A compile error or invalid fixture does not establish a defect.
4. Obtain independent FP/ZIO and invariant-focused review for behavior changes. Correct findings and review the affected final changes. Record the concise outcome in the PR or task conversation, not package acceptance journals.
5. Run the relevant checks, update affected API/usage/migration documentation and the single checklist, then commit. Keep unfinished work visible; do not broaden scope silently.

Tests must preserve useful parser, state-machine, concurrency, failure and cancellation coverage. Prefer shared fixtures and tables to repeated setup. More tests or files are not an acceptance goal.

## Verification

For production/test changes:

```text
sbt scalafmtAll scalafmtSbt
sbt verify
```

`verify` checks formatting, compilation with fatal warnings and tests. Run one sbt process at a time on this host. The build serializes suite tasks within each module to bound integration-fixture contention; ZIO concurrency inside each test remains exercised. Aggregated modules may still overlap. Focused checks guide development; repeat broad checks only when a relevant change or unresolved failure requires it.

Changes to public APIs, dependencies, packaging or integration also require the applicable package/archive and fresh [external consumer](verification/artifact-consumer/README.md) checks. For a candidate, run `sbt verify packageArtifacts` and follow the [build contract](project/README.md#local-artifact-packaging). A packaged local JAR is not a published release or a binary-compatibility guarantee.

Actually execute affected database/process/HTTP tests with their documented prerequisites. Real-provider checks are opt-in; keep credentials in the process environment. Normal offline verification does not certify a live provider. Documentation-only edits need prose/API/link checks; compile examples if their code or dependencies change, without rerunning all runtime tests for prose alone.

## Review and documentation

Reviewers must inspect effects, scopes, interruption, typed failures, tool/runtime authority and the complete changed public path. Report concrete findings and the limits of the review. Never claim a review that did not run or treat one approval as certification of the whole library.

Keep README files useful to callers: purpose, installation, examples, contracts and limitations. Update only affected documentation. Do not add file hashes, test-count milestones or reviewer transcripts. Remove superseded review reports after preserving current contracts; Git retains history.

Write code, comments, docs, commits and PRs in English. Check local Markdown links. Unicode scans supplement manual prose review. Preserve compatibility and security notes; the [compatibility policy](project/README.md#compatibility-policy) describes current rebuild obligations.

Use focused Conventional Commit messages. PRs state the changed behavior, meaningful validation, review outcome and any remaining limitation. The [parity reference](docs/architecture/test-parity.md) explains upstream adaptations; the [tracker](docs/architecture/tracker.md) owns current completion scope.

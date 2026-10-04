# Contributing

Read [AGENTS.md](AGENTS.md) before changing production or test code. Use Scala 3.9.0, ZIO 2.1.26, JDK 21, and the sbt version in project/build.properties.

## Engineering practices

- Suspend external effects in ZIO, including constructor defaults, codecs, random IDs, clocks, filesystem screening, and adapter callbacks.
- Use immutable values, typed AgentError failures, explicit public result types, Scala 3 indentation, derived JSON codecs, and enums/ADTs for states.
- Avoid var, throw, null, return, procedural loops, mutable collections, Future, unsafe casts, partial access, and blocking sleeps.
- Register release with Scope. Give each run/request its own lifetime and test cleanup on success, failure, timeout, and cancellation.
- Normalize tool errors as observations; propagate cancellation. Races must preserve both successful and failed completion.
- Treat canonical paths and filesystem ownership as separate contracts. Check-then-use pathname validation is not a race-resistant sandbox.
- Keep code, comments, documentation, commits, and PRs in English. Use vendor-neutral interfaces and preserve attribution.

## Verification

```text
sbt scalafmtAll scalafmtSbt
sbt verify packageArtifacts
```

The verify alias runs scalafmtCheckAll, scalafmtSbtCheck, compile, and test. Fatal warnings use -Werror; deprecation, feature, unchecked, and unused-symbol warnings are enabled. The compiler also retains trees with -Yretain-trees. Tests use scripted clients, Promise barriers, TestClock, and scoped temporary resources. `packageArtifacts` generates module binary/source JARs and POMs without publishing; packaging changes also require the separate archive/dependency and isolated-consumer checks documented in the [build contract](project/README.md#local-artifact-packaging).

These checks do not prove semantic purity, exhaustive parity, English correctness, filesystem trust, or production readiness. Those remain review responsibilities.

## Independent review

Behavioral changes require independent FP/ZIO and invariant-focused reviews. Ask for reproducible findings with source/test locations, add regressions for confirmed defects, resolve findings, and have reviewers inspect the final diff. Record explicit final approval, accepted scope, reviewer identities, reviewed revision, and validation in the affected README.md files. Do not close acceptance with unresolved findings.

Never claim subagent review unless it actually ran. Never label a suite exhaustive 1:1 parity without method-level mapping and justified differences or exclusions.

## Documentation and compatibility

Update affected root/module/package READMEs, architecture contracts, tracker, test mappings, and migration notes. Prefer source links over duplicated implementations. Mark roadmap systems and host responsibilities explicitly.

Review changed prose and comments for English and neutral terminology. Check local Markdown links. Unicode screening is supplemental evidence, not proof of natural-language correctness.

Use focused Conventional Commit messages and the PR checklist. CI provisions the [official sbt runner](https://github.com/sbt/setup-sbt) before verification and artifact generation.

## Closing a review

Follow the mandatory sequence in [AGENTS.md](AGENTS.md): independent review, corrections, explicit re-approval, current README updates, verification, and commit. Each maintained working directory needs a README.md; generated output and empty structural ancestors are excluded.

Once all findings are resolved and reviewers approve, remove the temporary review report from the active tree and repair its links in the same commit. Historical findings remain available in Git. Preserve current API/security contracts and remaining roadmap work in their existing living documents. Do not create a replacement closed-review archive.

Acceptance statements must name the actual reviewed scope and revision. A later behavior change invalidates the affected approval until it is reviewed again. Planned systems and unmapped parity cases remain explicitly pending.

All trackers and roadmap/parity task lists use Markdown checkboxes. Check only verified completion; split partial phases into completed and pending tasks, and identify the next task. Update progress in the same commit as the work.

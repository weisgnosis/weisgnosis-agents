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
sbt verify
```

The verify alias runs scalafmtCheckAll, scalafmtSbtCheck, compile, and test. Fatal warnings use -Werror; deprecation, feature, unchecked, and unused-symbol warnings are enabled. The compiler also retains trees with -Yretain-trees. Tests use scripted clients, Promise barriers, TestClock, and scoped temporary resources.

These checks do not prove semantic purity, exhaustive parity, English correctness, filesystem trust, or production readiness. Those remain review responsibilities.

## Independent review

Behavioral changes require independent FP/ZIO and invariant-focused reviews. Ask for reproducible findings with source/test locations, add regressions for confirmed defects, resolve findings, and have reviewers inspect the final diff. Record scope, resolutions, validation, and remaining limitations in the PR or a repository review record.

Never claim subagent review unless it actually ran. Never label a suite exhaustive 1:1 parity without method-level mapping and justified differences or exclusions.

## Documentation and compatibility

Update affected root/module/package READMEs, architecture contracts, tracker, test mappings, and migration notes. Prefer source links over duplicated implementations. Mark roadmap systems and host responsibilities explicitly.

Review changed prose and comments for English and neutral terminology. Check local Markdown links. Unicode screening is supplemental evidence, not proof of natural-language correctness.

Use focused Conventional Commit messages and the PR checklist. CI provisions the [official sbt runner](https://github.com/sbt/setup-sbt) before verify.

# Typed tools and path validation: verification

These ZIO Test suites cover selected upstream scenarios and Scala runtime regressions. They do not certify exhaustive upstream parity.

- [PathSafetySpec](PathSafetySpec.scala)
- [ToolExecutionSpec](ToolExecutionSpec.scala)
- [ToolkitSpec](ToolkitSpec.scala)

Run `sbt "core / test"`, or `sbt verify` for repository-wide formatting, compilation, and tests. See the [parity audit](../../../../../../../../../../docs/architecture/test-parity.md) for gaps and intentional differences.

## Current review acceptance

- [x] Reviewed revision: `fb80428`; accepted files: PathSafety.scala and PathSafetySpec.scala.
- [x] Independent final approval: `review_harness`, reconfirmed for unchanged code on 2026-09-29 after corrections and cross-review.
- [x] No unresolved findings in the accepted scope: Parent traversal rejection before normalization and its deterministic regression.
- [x] Verification at the reviewed revision: `sbt verify`, 108 core and 40 harness tests, no failed tests or compiler warnings.

This is the accepted reference behavior for the scope above. Actual symlink/junction behavior was not dynamically verified; this acceptance does not cover all neighboring tool APIs. Changes to this behavior require renewed independent review and an updated acceptance entry.

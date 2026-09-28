# Large tool result offloading: verification

These ZIO Test suites cover selected upstream scenarios and Scala runtime regressions. They do not certify exhaustive upstream parity.

- [ToolResultEvictorSpec](ToolResultEvictorSpec.scala)
- [ToolResultStoreSpec](ToolResultStoreSpec.scala)

Run `sbt "harness / test"`, or `sbt verify` for repository-wide formatting, compilation, and tests. See the [parity audit](../../../../../../../../../../docs/architecture/test-parity.md) for gaps and intentional differences.

## Current review acceptance

- [x] Reviewed revision: `fb80428`; accepted files: ToolResultStore.scala and ToolResultStoreSpec.scala.
- [x] Independent final approval: `review_fp`, reconfirmed for unchanged code on 2026-09-29 after corrections and cross-review.
- [x] No unresolved findings in the accepted scope: Atomic completed-file publication, explicit workspace trust, and cleanup failure handling.
- [x] Verification at the reviewed revision: `sbt verify`, 108 core and 40 harness tests, no failed tests or compiler warnings.

This is the accepted reference behavior for the scope above. HostControlled ownership and hard-link support are required. Hostile concurrent filesystem mutation, crash durability, and exhaustive upstream parity are outside this acceptance. Changes to this behavior require renewed independent review and an updated acceptance entry.

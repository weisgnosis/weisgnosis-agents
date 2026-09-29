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

## Result processor verification

[ToolResultProcessorSpec](ToolResultProcessorSpec.scala) adds three scenarios for payload codecs, control-key protection, and suspended ordered composition. Runtime ownership and eviction are covered by the agent/harness integration suites.

- [x] `review_fp` and `review_harness` approved the final eight-file local-result processing/eviction increment against `8850a60` on 2026-09-29. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 466 core and 45 harness tests. See the [tool package table](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md) for retained processor/eviction identities; commit `a34201f` preserves all eight reviewed identities.

## Authorization verification

[ToolAuthorizerSpec](ToolAuthorizerSpec.scala) verifies request/decision codecs, lazy default-preserving authorization, and compile-time exclusion of provider errors from the permission channel. Runtime evidence is in [ReActAgentToolAuthorizerSpec](../agent/ReActAgentToolAuthorizerSpec.scala).

- [x] `review_fp` and `review_harness` approved the final five-file core authorization increment against `a34201f` on 2026-09-29. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 481 core and 45 harness tests, including fifteen added authorization tests. Interactive and durable policy adapters are excluded from this acceptance. See the [authorization table](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md) for exact identities.

# Typed tools and path validation

Tool keeps its input/output codecs and effectful handler together. Toolkit registration and merging return Either[AgentError, Toolkit], including duplicate-name failures. Execution normalizes tool failures and defects into Error observations; interruption propagates. PathSafety rejects parent traversal before canonicalization and resolves existing ancestors through toRealPath. Canonical checks do not provide an atomic sandbox against hostile filesystem mutation.

## Implementation

- [PathSafety](PathSafety.scala)
- [Tool](Tool.scala)
- [ToolDef](ToolDef.scala)
- [ToolExecutionResult](ToolExecutionResult.scala)
- [Toolkit](Toolkit.scala)

See the [repository architecture](../../../../../../../../../../ARCHITECTURE.md) for integration boundaries and invariant definitions.

## Current review acceptance

- [x] Reviewed revision: `fb80428`; accepted files: PathSafety.scala and PathSafetySpec.scala.
- [x] Independent final approval: `review_harness`, reconfirmed for unchanged code on 2026-09-29 after corrections and cross-review.
- [x] No unresolved findings in the accepted scope: Parent traversal rejection before normalization and its deterministic regression.
- [x] Verification at the reviewed revision: `sbt verify`, 108 core and 40 harness tests, no failed tests or compiler warnings.

This is the accepted reference behavior for the scope above. Actual symlink/junction behavior was not dynamically verified; this acceptance does not cover all neighboring tool APIs. Changes to this behavior require renewed independent review and an updated acceptance entry.

# Conversation compaction: verification

These ZIO Test suites cover selected upstream scenarios and Scala runtime regressions. They do not certify exhaustive upstream parity.

- [ConversationCompactorSpec](ConversationCompactorSpec.scala)
- [TokenCounterSpec](TokenCounterSpec.scala)

Run `sbt "harness / test"`, or `sbt verify` for repository-wide formatting, compilation, and tests. See the [parity audit](../../../../../../../../../../docs/architecture/test-parity.md) for gaps and intentional differences.

## Current review acceptance

- [x] Reviewed revision: `fb80428`; accepted files: ConversationCompactor.scala and ConversationCompactorSpec.scala.
- [x] Independent final approval: `review_fp`, reconfirmed for unchanged code on 2026-09-29 after corrections and cross-review.
- [x] No unresolved findings in the accepted scope: Repeated-compaction user provenance and unchanged below-threshold history.
- [x] Verification at the reviewed revision: `sbt verify`, 108 core and 40 harness tests, no failed tests or compiler warnings.

This is the accepted reference behavior for the scope above. Memory services and exhaustive upstream parity remain pending. Changes to this behavior require renewed independent review and an updated acceptance entry.

# Conversation compaction: verification

These ZIO Test suites cover selected upstream scenarios and Scala runtime regressions. They do not certify exhaustive upstream parity.

- [ConversationCompactorSpec](ConversationCompactorSpec.scala)
- [TokenCounterSpec](TokenCounterSpec.scala)

Run `sbt "harness / test"`, or `sbt verify` for repository-wide formatting, compilation, and tests. See the [parity audit](../../../../../../../../../../docs/architecture/test-parity.md) for gaps and intentional differences.

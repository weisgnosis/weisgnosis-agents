# Typed agent events: verification

These ZIO Test suites cover selected upstream scenarios and Scala runtime regressions. They do not certify exhaustive upstream parity.

- [AgentEventSerdeSpec](AgentEventSerdeSpec.scala)
- [AgentEventStreamingSpec](AgentEventStreamingSpec.scala)

Run `sbt "core / test"`, or `sbt verify` for repository-wide formatting, compilation, and tests. See the [parity audit](../../../../../../../../../../docs/architecture/test-parity.md) for gaps and intentional differences.

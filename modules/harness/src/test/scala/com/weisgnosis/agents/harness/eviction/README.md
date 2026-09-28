# Large tool result offloading: verification

These ZIO Test suites cover selected upstream scenarios and Scala runtime regressions. They do not certify exhaustive upstream parity.

- [ToolResultEvictorSpec](ToolResultEvictorSpec.scala)
- [ToolResultStoreSpec](ToolResultStoreSpec.scala)

Run `sbt "harness / test"`, or `sbt verify` for repository-wide formatting, compilation, and tests. See the [parity audit](../../../../../../../../../../docs/architecture/test-parity.md) for gaps and intentional differences.

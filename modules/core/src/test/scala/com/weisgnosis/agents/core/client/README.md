# Model client port and stream assembly: verification

These ZIO Test suites cover selected upstream scenarios and Scala runtime regressions. They do not certify exhaustive upstream parity.

- [LlmClientSpec](LlmClientSpec.scala)
- [ReasoningAdapterSpec](ReasoningAdapterSpec.scala)
- [SseParserSpec](SseParserSpec.scala)
- [StreamAccumulatorSpec](StreamAccumulatorSpec.scala)

Run `sbt "core / test"`, or `sbt verify` for repository-wide formatting, compilation, and tests. See the [parity audit](../../../../../../../../../../docs/architecture/test-parity.md) for gaps and intentional differences.

## Message API migration acceptance

- [x] `review_fp` and `review_harness` approved this package's changed Scala files against `9c47bf5` on 2026-09-29, with no unresolved findings in the message-boundary migration.
- [x] Repository verification passed 242 core and 40 harness tests with zero compiler warnings.

See the [validated message API and exact reviewed blobs](../../../../../../../main/scala/com/weisgnosis/agents/core/model/README.md) for source compatibility changes, scope, and evidence. Core callers preserve roles and optional fields; reasoning/structured-output filtering preserves message identity and metadata. Harness transformations preserve tool pairing, retained-user provenance, eviction fallback, and cancellation. This acceptance covers the changed files only; broader package and upstream parity gaps remain tracked.

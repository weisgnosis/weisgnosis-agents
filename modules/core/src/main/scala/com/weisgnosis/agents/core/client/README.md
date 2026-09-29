# Model client port and stream assembly

LlmClient.streamTurn requires Scope and returns TurnOutput. Implementations must acquire request resources in that scope. The package provides scripted mock clients, LlmConfig, SSE parsing, reasoning adaptation, and StreamAccumulator. Parallel tool deltas are assembled by streamIndex; incomplete interrupted JSON is sanitized. A live HTTP client and provider integration tests are pending.

## Implementation

- [LlmChunk](LlmChunk.scala)
- [LlmClient](LlmClient.scala)
- [ReasoningModelAdapter](ReasoningModelAdapter.scala)
- [SseParser](SseParser.scala)
- [StreamAccumulator](StreamAccumulator.scala)
- [TurnOutput](TurnOutput.scala)

See the [repository architecture](../../../../../../../../../../ARCHITECTURE.md) for integration boundaries and invariant definitions.

## Message API migration acceptance

- [x] `review_fp` and `review_harness` approved this package's changed Scala files against `9c47bf5` on 2026-09-29, with no unresolved findings in the message-boundary migration.
- [x] Repository verification passed 242 core and 40 harness tests with zero compiler warnings.

See the [validated message API and exact reviewed blobs](../model/README.md) for source compatibility changes, scope, and evidence. Core callers preserve roles and optional fields; reasoning/structured-output filtering preserves message identity and metadata. Harness transformations preserve tool pairing, retained-user provenance, eviction fallback, and cancellation. This acceptance covers the changed files only; broader package and upstream parity gaps remain tracked.

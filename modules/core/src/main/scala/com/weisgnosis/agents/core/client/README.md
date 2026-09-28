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

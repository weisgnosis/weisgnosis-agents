# Typed domain failures

[AgentError.scala](AgentError.scala) defines the sealed domain failure hierarchy, including configuration, serialization, permission, model, timeout, and tool errors. Domain effects expose these failures in the ZIO error channel. Tool execution errors become model observations at the toolkit boundary; cancellation remains cancellation.

The dedicated exhaustive upstream error-scenario mapping remains tracked with the wider core parity audit. Current scoped acceptance is recorded below.

## Message API migration acceptance

- [x] `review_fp` and `review_harness` approved this package's changed Scala files against `9c47bf5` on 2026-09-29, with no unresolved findings in the message-boundary migration.
- [x] Repository verification passed 242 core and 40 harness tests with zero compiler warnings.

The accepted migration is recorded in commit `a701612`. See the [validated message API](../model/README.md) for source compatibility changes, scope, and evidence. Core callers preserve roles and optional fields; reasoning/structured-output filtering preserves message identity and metadata. Harness transformations preserve tool pairing, retained-user provenance, eviction fallback, and cancellation. This acceptance covers the changed files only; broader package and upstream parity gaps remain tracked.

AgentError.InvalidMessage represents invalid role/content combinations from dynamic construction and updates. MsgConstructionSpec verifies its typed channel and JSON round trip. An exhaustive error-hierarchy scenario suite remains pending.

## Text transformation failure boundary

TextTransformationError is the sole typed error accepted by ModelTextTransform. External failures must be mapped explicitly to it; this distinguishes host transformation failures from ModelError provider rejections eligible for native structured fallback. Runtime propagation retains the typed error, and cancellation remains interruption. Adding a sealed-hierarchy leaf requires exhaustive AgentError matches to handle it.

- [x] `review_fp` and `review_harness` approved the final seven-file text-transformation increment against `02b4654` on 2026-09-29 after the fallback-provenance correction. No findings remain within this scope. Full formatting, fatal-warning compilation, and verification passed 434 core and 40 harness tests.

The [error spec](../../../../../../../test/scala/com/weisgnosis/agents/core/error/AgentErrorSpec.scala) covers the leaf codec/diagnostic contract. Client tests additionally verify compile-time separation and no fallback after a mapped provider-like host error. Broader hierarchy parity remains pending.

The [client transformation table](../client/README.md) records unchanged text-transformation identities; commit `2a400e0` preserves all seven identities from that increment.

## Reasoning policy failure boundary

ReasoningHookError is the sole typed callback failure for PostReasoningHook and also reports invalid replacement roles. It cannot be confused with a ModelError native-format rejection. PostReasoningHookSpec verifies its derived codec and the compile-time channel boundary; runtime tests verify no fallback after policy failure. Exhaustive error matches must handle this additional sealed-hierarchy leaf.

- [x] `review_fp` and `review_harness` approved the final six-file post-reasoning increment against `2a400e0` on 2026-09-29 after the legacy-usage correction. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 456 core and 40 harness tests.

The [agent table](../agent/README.md) records unchanged post-reasoning identities; commit `8850a60` preserves all six identities from that increment.

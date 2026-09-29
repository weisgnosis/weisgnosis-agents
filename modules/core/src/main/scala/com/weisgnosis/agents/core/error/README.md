# Typed domain failures

[AgentError.scala](AgentError.scala) defines the sealed domain failure hierarchy, including configuration, serialization, permission, model, timeout, and tool errors. Domain effects expose these failures in the ZIO error channel. Tool execution errors become model observations at the toolkit boundary; cancellation remains cancellation.

The dedicated exhaustive upstream error-scenario mapping remains tracked with the wider core parity audit. No new independent error-package acceptance is claimed by a documentation-only update.

## Message API migration acceptance

- [x] `review_fp` and `review_harness` approved this package's changed Scala files against `9c47bf5` on 2026-09-29, with no unresolved findings in the message-boundary migration.
- [x] Repository verification passed 242 core and 40 harness tests with zero compiler warnings.

The accepted migration is recorded in commit `a701612`. See the [validated message API](../model/README.md) for source compatibility changes, scope, and evidence. Core callers preserve roles and optional fields; reasoning/structured-output filtering preserves message identity and metadata. Harness transformations preserve tool pairing, retained-user provenance, eviction fallback, and cancellation. This acceptance covers the changed files only; broader package and upstream parity gaps remain tracked.

AgentError.InvalidMessage represents invalid role/content combinations from dynamic construction and updates. MsgConstructionSpec verifies its typed channel and JSON round trip. An exhaustive error-hierarchy scenario suite remains pending.

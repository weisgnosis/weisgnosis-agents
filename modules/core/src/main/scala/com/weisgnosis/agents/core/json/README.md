# Shared JSON boundaries

[JsonBoundary](JsonBoundary.scala) is library-internal (private[agents]) and pure. decode parses one complete JSON document and rejects duplicate object keys throughout the tree. validate checks an immutable AST without changing its representation. Both return Either[String, Json]; neither evaluates JSON Schema or imposes a size limit.

render emits ordered compact JSON using an immutable, tail-recursive token traversal. Numbers use java.math.BigDecimal.toString, preserving their exact value and scale without the dependency's minimum-Long unscaled-value overflow. Object keys and strings use the existing JSON string encoder; arrays, empty containers, booleans and null retain their ordinary representations. Rendering is not validation and does not discard duplicate members: callers own the checked boundary.

ChatCompletions uses render for complete HTTP bodies and historical tool-argument strings. [MCP envelope encoding](../../../../../../../../../mcp/src/main/scala/com/weisgnosis/agents/mcp/protocol/README.md#exact-envelope-rendering) integrates the same helper separately. Arbitrary external .toJson calls and derived codecs are not globally replaced. Known exposed owned paths include structured closing text and transcript/provenance serialization; host-serialized checkpoints/events containing Json remain outside this repair.

## Verification and acceptance

- [x] Existing strict-document, escaping, duplicate and direct-AST tests remain in place. One exact-rendering group adds independent minimum-Long/scaled literals, nested escaping and a 20,000-level array traversal. `exact-json-red.log` recorded six passing groups and one failure in 237 ms against delegating helper `79e4b127b3059793fc5f129d01d3dbe6b57404f1` and regression `f1cc6538c853ef10281378e2d2b5e10a2c7907e3`: -9223372036854775808 became -9223372036854775808E-16, with corresponding nested/scaled corruption.
- [x] The corrected core focused run passed 34 tests in 988 ms in the core section of `exact-json-focused-red.log`. Current source/spec identities and invariant approval are in the [seed/rendering contract](../client/README.md#optional-seed-and-exact-request-rendering).
- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved core invariants. Full formatting and verification passed in `exact-json-verify.log`; current aggregate evidence belongs to the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).
- [ ] Correct and independently verify structured closing text and transcript/provenance numeric integrity. External derived-codec serialization needs a separate explicit boundary; no global JSON repair is claimed.

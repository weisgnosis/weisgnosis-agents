# Shared JSON boundaries

[JsonBoundary](JsonBoundary.scala) is library-internal (private[agents]) and pure. decode parses one complete JSON document and rejects duplicate object keys throughout the tree. validate checks an immutable AST without changing its representation. Both return Either[String, Json]; neither evaluates JSON Schema or imposes a size limit.

render emits ordered compact JSON using an immutable, tail-recursive token traversal. Numbers use java.math.BigDecimal.toString, preserving their exact value and scale without the dependency's minimum-Long unscaled-value overflow. Object keys and strings use the existing JSON string encoder; arrays, empty containers, booleans and null retain their ordinary representations. Rendering is not validation and does not discard duplicate members: callers own the checked boundary.

ChatCompletions uses render for complete HTTP bodies and historical tool-argument strings. [MCP envelope encoding](../../../../../../../../../mcp/src/main/scala/com/weisgnosis/agents/mcp/protocol/README.md#exact-envelope-rendering) integrates the same helper separately. Arbitrary external .toJson calls and derived codecs are not globally replaced. [Structured closing text and local transcript output](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/transcript/README.md#exact-owned-numeric-output) also integrate exact rendering. Compactor provenance, generic tool-output codecs and host-serialized checkpoints/events containing Json remain outside these repairs.

## Verification and acceptance

- [x] Existing strict-document, escaping, duplicate and direct-AST tests remain in place. One exact-rendering group adds independent minimum-Long/scaled literals, nested escaping and a 20,000-level array traversal. `exact-json-red.log` recorded six passing groups and one failure in 237 ms against delegating helper `79e4b127b3059793fc5f129d01d3dbe6b57404f1` and regression `f1cc6538c853ef10281378e2d2b5e10a2c7907e3`: -9223372036854775808 became -9223372036854775808E-16, with corresponding nested/scaled corruption.
- [x] The corrected core focused run passed 34 tests in 988 ms in the core section of `exact-json-focused-red.log`. Current source/spec identities and invariant approval are in the [seed/rendering contract](../client/README.md#optional-seed-and-exact-request-rendering).
- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved core invariants. Full formatting and verification passed in `exact-json-verify.log`; current aggregate evidence belongs to the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).
- [x] Independently review and verify exact structured closing text, transcript rows and numeric previews; the linked acceptance records the eight-file scope.
- [ ] Correct compactor retained-user provenance and numeric previews. External derived-codec serialization needs a separate explicit boundary; no global JSON repair is claimed.

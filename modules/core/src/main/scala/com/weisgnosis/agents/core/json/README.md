# Shared JSON boundaries

Shared pure validation and rendering preserve exact JSON values at explicitly integrated boundaries.

[JsonBoundary](JsonBoundary.scala) is library-internal (private[agents]) and pure. decode parses one complete JSON document and rejects duplicate object keys throughout the tree. validate checks an immutable AST without changing its representation. Both return Either[String, Json]; neither evaluates JSON Schema or imposes a size limit.

render emits ordered compact JSON using an immutable, tail-recursive token traversal. Numbers use java.math.BigDecimal.toString, preserving their exact value and scale without the dependency's minimum-Long unscaled-value overflow. Object keys and strings use the existing JSON string encoder; arrays, empty containers, booleans and null retain their ordinary representations. Rendering is not validation and does not discard duplicate members: callers own the checked boundary.

ChatCompletions uses render for complete HTTP bodies and historical tool-argument strings. [MCP envelope encoding](../../../../../../../../../mcp/src/main/scala/com/weisgnosis/agents/mcp/protocol/README.md#exact-envelope-rendering) integrates the same helper separately. Arbitrary external .toJson calls and derived codecs are not globally replaced. [Structured closing text and local transcript output](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/transcript/README.md#exact-owned-numeric-output) also integrate exact rendering. The explicit domain/tool boundary below also covers retained-user provenance and memory previews. Arbitrary dependency codecs remain outside these integrations.

The restricted sameRepresentation helper compares canonical DTO representations without object-order or numeric-spelling sensitivity. validUnicode separately checks strings and object keys; decode/validate retain their existing duplicate-field behavior. Neither helper grants persistence or execution authority.

## Exact domain and tool output

[ExactJson.encode](ExactJson.scala) is a public pure `Either[AgentError.InvalidMessage, String]` boundary. It obtains the encoder's JSON AST, rejects recursive duplicate members, then renders exact numeric values. The explicit round-trip scope covers suspended AgentResult checkpoints, AgentResultEvent payloads and ToolResultStart/Delta/End/DataDelta metadata containing JSON. Unrelated event variants are not claimed newly audited. A custom encoder must supply a faithful toJsonAST: this helper cannot recover values already changed by that encoder. It does not add schema validation, storage, authentication or checkpoint authority.

Message and Thinking/ToolUse/ToolResult metadata decoders retain JSON object values directly, avoiding the dependency Map decoder's AST-to-text conversion. Missing/null metadata still becomes empty; malformed nonobjects still fail; duplicate map keys retain the existing last-wins behavior. This decoding change does not introduce duplicate rejection or new metadata authority.

Toolkit invokes the additive Tool.encodeResultChecked only after successful stream completion and joined cleanup. Its default delegates to the existing custom encodeResult projection. Tool.fromFunction overrides it with ExactJson and fixed-argument wrappers forward that override. Typed encoding failures and thrown custom-encoder defects follow existing Error-observation normalization; interruption remains cancellation. Direct legacy Tool.encodeOutput/encodeResult calls are unchanged and do not acquire the factory's checked runtime guarantee. Existing implementations need no new abstract method; dependent binaries should be rebuilt.

Compactor retained-user strings and unsigned argument previews, and daily-memory argument previews, use exact rendering. Provenance AST conversion fails with HistoryPreparationError rather than silently losing user content; retained numeric metadata decodes without a text round-trip. Existing history protection, preview limits, scoped hooks and persistence ownership remain unchanged.

## Verification and acceptance

[JsonBoundarySpec](../../../../../../../test/scala/com/weisgnosis/agents/core/json/JsonBoundarySpec.scala) checks escaping, complete documents and deep-tree rendering; [ExactJsonSpec](../../../../../../../test/scala/com/weisgnosis/agents/core/json/ExactJsonSpec.scala) checks numeric values through domain, checkpoint and event boundaries. External `.toJson`, legacy direct tool encoding and custom encoders with lossy AST conversion are not globally repaired.

The internal prefix/suffix helpers retain UTF-16 budgets while backing off a cut through a surrogate pair. They preserve valid input during preview truncation; they do not validate or repair malformed source strings. Eviction metadata records the actual retained lengths.

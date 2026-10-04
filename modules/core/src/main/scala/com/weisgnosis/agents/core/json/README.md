# Shared JSON boundaries

WIP pause: The reviewed twelve-file baseline below passed full verification. ExactJsonSpec was subsequently extended to four tool-result event metadata variants; that regression failed, and the corresponding latest AgentEvent correction is unverified. Final combined documentation closure remains pending.

[JsonBoundary](JsonBoundary.scala) is library-internal (private[agents]) and pure. decode parses one complete JSON document and rejects duplicate object keys throughout the tree. validate checks an immutable AST without changing its representation. Both return Either[String, Json]; neither evaluates JSON Schema or imposes a size limit.

render emits ordered compact JSON using an immutable, tail-recursive token traversal. Numbers use java.math.BigDecimal.toString, preserving their exact value and scale without the dependency's minimum-Long unscaled-value overflow. Object keys and strings use the existing JSON string encoder; arrays, empty containers, booleans and null retain their ordinary representations. Rendering is not validation and does not discard duplicate members: callers own the checked boundary.

ChatCompletions uses render for complete HTTP bodies and historical tool-argument strings. [MCP envelope encoding](../../../../../../../../../mcp/src/main/scala/com/weisgnosis/agents/mcp/protocol/README.md#exact-envelope-rendering) integrates the same helper separately. Arbitrary external .toJson calls and derived codecs are not globally replaced. [Structured closing text and local transcript output](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/transcript/README.md#exact-owned-numeric-output) also integrate exact rendering. The explicit domain/tool boundary below also covers retained-user provenance and memory previews. Arbitrary dependency codecs remain outside these integrations.

## Exact domain and tool output

[ExactJson.encode](ExactJson.scala) is a public pure `Either[AgentError.InvalidMessage, String]` boundary. It obtains the encoder's JSON AST, rejects recursive duplicate members, then renders exact numeric values. Reviewed round-trips cover suspended AgentResult checkpoints and AgentResultEvent payloads containing JSON. Other event metadata decoders are under investigation; no all-event metadata round-trip guarantee is established. A custom encoder must supply a faithful toJsonAST: this helper cannot recover values already changed by that encoder. It does not add schema validation, storage, authentication or checkpoint authority.

Message and Thinking/ToolUse/ToolResult metadata decoders retain JSON object values directly, avoiding the dependency Map decoder's AST-to-text conversion. Missing/null metadata still becomes empty; malformed nonobjects still fail; duplicate map keys retain the existing last-wins behavior. This decoding change does not introduce duplicate rejection or new metadata authority.

Toolkit invokes the additive Tool.encodeResultChecked only after successful stream completion and joined cleanup. Its default delegates to the existing custom encodeResult projection. Tool.fromFunction overrides it with ExactJson and fixed-argument wrappers forward that override. Typed encoding failures and thrown custom-encoder defects follow existing Error-observation normalization; interruption remains cancellation. Direct legacy Tool.encodeOutput/encodeResult calls are unchanged and do not acquire the factory's checked runtime guarantee. Existing implementations need no new abstract method; dependent binaries should be rebuilt.

Compactor retained-user strings and unsigned argument previews, and daily-memory argument previews, use exact rendering. Provenance AST conversion fails with HistoryPreparationError rather than silently losing user content; retained numeric metadata decodes without a text round-trip. Existing history protection, preview limits, scoped hooks and persistence ownership remain unchanged.

## Verification and acceptance

- [x] path_review approved FP/ZIO correctness and current_docs approved invariants for the twelve identities below, including direct metadata extraction and the unchanged memory regression. No findings remain in this scope.
- [x] Focused exact-output-final-focused.log passed 42 core tests in 576 ms and 41 harness tests in 383 ms. Two ExactJson groups cover exact checkpoint/AgentResultEvent/block round-trips, duplicate rejection and failed AST conversion; existing tool and memory groups add numeric regressions without new lifecycle fixtures.
- [x] Formatting, verify and packageArtifacts passed in exact-output-verify.log. Archive/POM checks and an isolated packaged consumer executing ExactJson also passed (artifact-inspection-green.log and exact-output-artifact-consumer.log). Current repository totals belong to the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).

The pre-fix memory-numeric-red.log recorded 38 passes/3 failures in provenance, pruning and extraction previews; tool-numeric-red.log recorded 6 passes/1 failure in factory output. metadata-numeric-red.log recorded 1 pass/1 failure in checkpoint/block metadata after exact rendering alone: the dependency Map decoder reserialized numeric AST values. Direct object extraction closes that additional loss. Expected numeric literals are independent of the writer under test. These are Scala numeric-integrity regressions, not additional whole-method upstream parity claims.

| Reviewed file | Git blob |
| --- | --- |
| [ExactJson.scala](ExactJson.scala) | `f9150a264f59c9c20590878b6195fda9b350c033` |
| [ExactJsonSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/json/ExactJsonSpec.scala) | `666b84a020d3b5ae3af98609e07a04153d4c5d42` |
| [JsonFields.scala](../model/JsonFields.scala) | `44095275fbccc029af1ec29ee9ee387a388d4422` |
| [Msg.scala](../model/Msg.scala) | `2f12a84f6f668f652dff6311088b842af6c495c7` |
| [ContentBlock.scala](../model/ContentBlock.scala) | `d4b186534f6874b67bab02e2d0d37dec097ecf09` |
| [Tool.scala](../tool/Tool.scala) | `e460c1179d8ed7ce1b21be6fa2915a577d39ec1c` |
| [Toolkit.scala](../tool/Toolkit.scala) | `7a6789a12bd958ae507b2d6d12eefa4583d29797` |
| [ToolExecutionSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/tool/ToolExecutionSpec.scala) | `084ece699eb700ab7d7fce9fae3bd83651933e6f` |
| [ConversationCompactor.scala](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/memory/ConversationCompactor.scala) | `e926e44021ee976bfaf0b3cc2da107fc23eb6b8e` |
| [MemoryFlushManager.scala](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/memory/MemoryFlushManager.scala) | `fb233e5ec1822773c25c717132f7aca79b34cecb` |
| [ConversationCompactorSpec.scala](../../../../../../../../../harness/src/test/scala/com/weisgnosis/agents/harness/memory/ConversationCompactorSpec.scala) | `52fb3f9cd12dae969cae67d13a809830ac5a0f32` |
| [MemoryFlushManagerSpec.scala](../../../../../../../../../harness/src/test/scala/com/weisgnosis/agents/harness/memory/MemoryFlushManagerSpec.scala) | `4609dd2a85af9351f5b46309f615aeee1d085317` |

JsonBoundary rendering and its independent escaping/deep-tree evidence remain accepted under the [request-rendering contract](../client/README.md#optional-seed-and-exact-request-rendering). External `.toJson`, legacy direct tool encoding and custom encoders with lossy AST conversion are not globally repaired.

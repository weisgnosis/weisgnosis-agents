# Shared JSON boundaries

JsonBoundary is core-internal and pure. It has no dependency on model, agent, or transport packages. decode parses one complete document with JSON whitespace, validates container/string/primitive boundaries, and rejects duplicate object keys throughout the tree. validate checks an existing immutable AST directly, preserving its representation. Both operations return Either[String, Json].

The implementation moved from client.ProtocolJson so Msg, structured output, request encoding, delta decoding, and tool accumulation can share the same authority boundary. Existing AST validation avoids re-encoding and parsing. This is JSON syntax/ambiguity validation, not JSON Schema validation or a request-size limit.

- [JsonBoundary](JsonBoundary.scala)

## Verification and acceptance

- [x] Six JsonBoundarySpec tests cover complete documents, primitive/container syntax, escaping, duplicate fields, truncated input, and direct AST validation.
- [x] Six StructuredJsonBoundarySpec regressions and five StructuredOutputHttpSpec loopback tests verify extraction and actual HTTP paths. Four initial regressions failed before strict extraction; the additional duplicate-wrapper regression failed before validation was moved ahead of extraction.
- [x] `review_fp` and `review_harness` approved the final ten-file increment against `9c165a7` on 2026-09-29 after the wrapper correction. No findings remain within strict JSON boundaries and structured HTTP completion. Full verification passed 404 core and 40 harness tests.
- [ ] Complete hook scenarios and exhaustive provider compatibility; no general JSON Schema validation is claimed.

The following identities supersede earlier acceptance records for changed files. The former client ProtocolJson source/spec paths were moved to this package; incoming links are updated.

| File | Reviewed Git blob |
| --- | --- |
| [ChatCompletionsRequest.scala](../client/ChatCompletionsRequest.scala) | ed7f2f592fe5ce0205f35521955dabed171a614a |
| [ReasoningModelAdapter.scala](../client/ReasoningModelAdapter.scala) | 72ae618a2309d972887169d140863711fffe3123 |
| [StreamAccumulator.scala](../client/StreamAccumulator.scala) | 223c6aeb22a15ee9d6fa45945b5b914d48c10a57 |
| [JsonBoundary.scala](JsonBoundary.scala) | d7d83434ff76b2a89943edb60b2b2878742fc458 |
| [Msg.scala](../model/Msg.scala) | ca34a6225c004dbb6f39e0d502ccd7886e9cc2dd |
| [StructuredJsonBoundarySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/StructuredJsonBoundarySpec.scala) | d967396a6c6e6cd9842c0fe69eb5ba14def41685 |
| [StructuredOutputHttpSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/StructuredOutputHttpSpec.scala) | 93610a93773fb649034e575803c6fc49d1d15ca2 |
| [JsonBoundarySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/json/JsonBoundarySpec.scala) | c08295405b54cd3684e23f21344fe0fa2fbe35a3 |

The ReActAgent identity from this increment is preserved in `06bd2e1`; its subsequent capability routing is covered by the current client/agent acceptance.

StructuredOutput from the JSON increment is preserved in `06bd2e1`; its subsequent single-transfer usage correction is covered by current agent acceptance.

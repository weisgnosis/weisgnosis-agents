# com.weisgnosis.agents.core.client (Tests)

ZIO Test parity suite for `com.weisgnosis.agents.core.client`.

---

## 🧪 Implemented Test Specs (Passing)

1. **`SseParserSpec.scala`** `[x]` (corresponds to `OpenAISseParserTest.java`):
   - Streaming SSE decoding and multi-line data buffering.
   - Discarding comments (`: keep-alive`).
   - Clean handling of the standard termination token `data: [DONE]`.

2. **`StreamAccumulatorSpec.scala`** `[x]` (INV-12):
   - Chunk index assembly across parallel interleaved tool calls.
   - Interruption sanitization of incomplete tool arguments to `{}`.
   - Pure text and thinking-only state transitions.

3. **`ReasoningAdapterSpec.scala`** `[x]`:
   - Preserving reasoning content for active tool execution segments.
   - Stripping reasoning content on older dialogue turns to minimize prompt bloat.
   - Validating reasoning token counts in usage metrics.

4. **`LlmClientSpec.scala`** `[x]` (corresponds to `MockServerLlmClientTest.java`):
   - Scoped invocation against mock client with sequential turn dispatch.

# com.weisgnosis.agents.core.client (Tests)

ZIO Test parity suite for `com.weisgnosis.agents.core.client`.

---

## 🧪 Planned Test Specs

1. **`SseParserSpec.scala`** (corresponds to `OpenAISseParserTest.java`):
   - Streaming SSE decoding across irregular network chunk boundaries.
   - Filtering heartbeat and ping frames (`: keep-alive`).
   - Clean handling of the standard termination token `data: [DONE]`.

2. **`ReasoningAdapterSpec.scala`**:
   - Preserving reasoning content for active tool execution segments.
   - Stripping reasoning content on older dialogue turns to minimize prompt bloat.
   - Validating reasoning token counts in usage metrics.

3. **`LlmClientSpec.scala`** (corresponds to `MockServerLlmClientTest.java`):
   - End-to-end HTTP request formatting against mock servers using `zio-http`.
   - Parsing text streaming chunks and simultaneous multiple tool call deltas.

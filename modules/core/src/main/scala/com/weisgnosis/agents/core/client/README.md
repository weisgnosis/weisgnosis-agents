# com.weisgnosis.agents.core.client

Streaming HTTP/SSE client package for model interactions built on `zio-http`.

---

## 🎯 Purpose and Types

1. **`LlmClient`**:
   - `def stream(messages: Chunk[Msg], tools: Chunk[ToolDef], config: LlmConfig): ZStream[Any, AgentError, LlmChunk]`
2. **`LlmChunk`** (sealed trait):
   - `TextDelta(delta: String)`
   - `ThinkingDelta(delta: String, signature: Option[String] = None)`
   - `ToolCallStart(callId: String, name: String)`
   - `ToolCallDelta(callId: String, argumentsDelta: String)`
   - `Usage(promptTokens: Int, completionTokens: Int, totalTokens: Int)`
3. **`SseParser`**:
   - Transforms raw byte streams (`ZStream[Any, Throwable, Byte]`) into structured SSE events `(event, data)`.
   - Discards comment frames (`: keep-alive`), seamlessly buffers multi-line `data:` payloads, and detects end-of-stream indicators.
4. **`ReasoningModelAdapter`**:
   - Handles `reasoning_content` and thinking token fields from extended reasoning models.
   - Context hygiene policy: preserves reasoning blocks immediately preceding tool execution turns; prunes obsolete reasoning from older dialogue turns to conserve context.

---

## 🔍 References in AgentScope Java

* `agentscope-core/src/main/java/io/agentscope/core/model/`
* `agentscope-extensions/.../OpenAISseParser.java`
* `agentscope-core/.../ReActAgentServerToolTest.java` (provider-hosted server tools)

---

## 🧪 Test Parity Map

| Upstream AgentScope Java Test | Target ZIO Spec | Verification Focus |
| :--- | :--- | :--- |
| `OpenAISseParserTest.java` | `SseParserSpec.scala` | Stream chunk parsing, incomplete line buffering, `[DONE]` termination |
| Upstream reasoning test | `ReasoningAdapterSpec.scala` | Reasoning content retention before tool calls, pruning on prior turns |
| `MockServerLlmClientTest.java` | `LlmClientSpec.scala` | Mock HTTP server integration via `zio-http`, parallel tool_call parsing |

---

## ⚠️ Critical Invariants
* `preserveReasoningOnlyForToolSegments`: Obsolete reasoning blocks are omitted in subsequent dialogue rounds to conserve token capacity.
* Accumulation of partial `argumentsDelta` chunks across parallel tool calls correctly keyed by `callId` or index.
* Graceful network error handling yielding typed `AgentError.LlmStreamError`.

---

- [x] ADT `LlmChunk`, `TurnOutput`, `LlmConfig`
- [x] Streaming `SseParser` via `zio.stream.ZPipeline`
- [x] Functional `StreamAccumulator` with INV-12 parallel tool assembly
- [x] Vendor-neutral `ReasoningModelAdapter` with context hygiene
- [x] Trait `LlmClient` with mock testing capabilities
- [x] Unit tests: `SseParserSpec.scala`
- [x] Unit tests: `StreamAccumulatorSpec.scala`
- [x] Unit tests: `ReasoningAdapterSpec.scala`
- [x] Integration tests: `LlmClientSpec.scala`

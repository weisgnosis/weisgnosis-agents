# weisgnosis.agents.core.client

Пакет потоковых клиентов для работы с LLM через протокол Server-Sent Events (SSE) на базе `zio-http`.

---

## 🎯 Назначение и типы

1. **`LlmClient`**:
   - `def stream(messages: Chunk[Msg], tools: Chunk[ToolDef], config: LlmConfig): ZStream[Any, AgentError, LlmChunk]`
2. **`LlmChunk`** (sealed trait):
   - `TextDelta(delta: String)`
   - `ThinkingDelta(delta: String, signature: Option[String] = None)`
   - `ToolCallStart(callId: String, name: String)`
   - `ToolCallDelta(callId: String, argumentsDelta: String)`
   - `Usage(promptTokens: Int, completionTokens: Int, totalTokens: Int)`
3. **`SseParser`**:
   - Превращает байтовый стрим `ZStream[Any, Throwable, Byte]` в структурированные SSE-события `(event, data)`.
   - Игнорирует комментарии (`: keep-alive`), корректно склеивает многострочные `data:`.
4. **`DeepSeekReasoningAdapter`**:
   - Обрабатывает поле `reasoning_content` у рассуждающих моделей (DeepSeek-R1 / V3).
   - Правило очистки: сохраняет рассуждения перед вызовом тулов, удаляет из старых текстовых шагов.

---

## 🔍 Референсы в AgentScope Java

* `agentscope-core/src/main/java/io/agentscope/core/model/`
* `agentscope-extensions/agentscope-extensions-model-openai/.../OpenAISseParser.java`
* `agentscope-extensions/agentscope-extensions-model-openai/.../DeepSeekFormatterTest.java`
* `agentscope-core/.../ReActAgentServerToolTest.java` (серверные инструменты провайдера)

---

## 🧪 Карта паритета тестов

| Тест в AgentScope Java | Целевой ZIO Spec | Что проверяет |
| :--- | :--- | :--- |
| `OpenAISseParserTest.java` | `SseParserSpec.scala` | Парсинг стрима чанков, буферизация неполных строк, обработка `[DONE]` |
| `DeepSeekFormatterTest.java` | `DeepSeekAdapterSpec.scala` | Сохранение `reasoning_content` перед вызовом тулов, вырезание из старых ходов |
| `MockServerLlmClientTest.java` | `LlmClientSpec.scala` | Интеграция с мок-сервером через `zio-http`, разбор tool_calls |

---

## ⚠️ Критические инварианты
* `preserveReasoningOnlyForToolSegments`: не слать старые рассуждения в последующих текстовых раундах (экономия контекста).
* Корректный разбор накопительных `argumentsDelta` для параллельных вызовов инструментов (различение `callId` и `index`).
* Graceful handling сетевых обрывов с типизированной ошибкой `AgentError.LlmStreamError`.

---

## 📋 Статус реализации
- [ ] ADT `LlmChunk`, `LlmConfig`
- [ ] Потоковый `SseParser` на `zio.stream.ZPipeline`
- [ ] Чистый OpenAI/DeepSeek адаптер
- [ ] Unit-тесты парсера `SseParserSpec`
- [ ] Тесты специфики DeepSeek `DeepSeekAdapterSpec`

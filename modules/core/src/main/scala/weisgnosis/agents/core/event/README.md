# weisgnosis.agents.core.event

Пакет типизированных событий агентного цикла для трансляции в реальном времени через Server-Sent Events (SSE) в UI или downstream consumer.

---

## 🎯 Назначение и типы

Полная система из **31 типизированного события** (`AgentEvent`), объединенная в sealed trait:

1. **Жизненный цикл агента:**
   - `AgentStartEvent(sessionId: String, replyId: String, agentId: String, source: Option[String] = None)`
   - `AgentEndEvent(replyId: String, source: Option[String] = None)`
   - `AgentProgressEvent(message: String, currentStep: Int, totalSteps: Option[Int] = None)`
2. **Текстовые дельты (стриминг токенов):**
   - `TextBlockStartEvent(blockIndex: Int)`
   - `TextBlockDeltaEvent(delta: String, blockIndex: Int)`
   - `TextBlockEndEvent(blockIndex: Int, fullText: String)`
3. **Рассуждения модели (Thinking Mode):**
   - `ThinkingBlockStartEvent(blockIndex: Int)`
   - `ThinkingBlockDeltaEvent(delta: String, blockIndex: Int)`
   - `ThinkingBlockEndEvent(blockIndex: Int, fullThought: String)`
4. **Вызовы инструментов (Tool Calls):**
   - `ToolCallStartEvent(callId: String, name: String, blockIndex: Int)`
   - `ToolCallDeltaEvent(callId: String, delta: String, blockIndex: Int)`
   - `ToolCallEndEvent(callId: String, name: String, arguments: zio.json.ast.Json, blockIndex: Int)`
5. **Результаты инструментов (Tool Results):**
   - `ToolResultStartEvent(callId: String, name: String)`
   - `ToolResultDeltaEvent(callId: String, delta: String)`
   - `ToolResultEndEvent(callId: String, name: String, output: String, state: ToolResultState)`
6. **Вызовы модели (Raw LLM calls):**
   - `ModelRequestStartEvent(model: String, promptTokens: Int)`
   - `ModelResponseChunkEvent(chunk: String)`
   - `ModelResponseEndEvent(model: String, completionTokens: Int, totalTokens: Int)`
7. **Субагенты (Subagent lifecycle):**
   - `SubagentSpawnEvent(parentSessionId: String, childSessionId: String, agentId: String, label: Option[String])`
   - `SubagentStartEvent(childSessionId: String, agentId: String)`
   - `SubagentEndEvent(childSessionId: String, agentId: String, status: String)`
   - `SubagentExposedEvent(subagentId: String, agentId: String, sessionId: String, label: Option[String])`
8. **Безопасность и Human-in-the-Loop:**
   - `PermissionAskEvent(requestId: String, toolName: String, input: zio.json.ast.Json)`
   - `PermissionDecisionEvent(requestId: String, allowed: Boolean, reason: Option[String])`
9. **Контекст и память:**
   - `CompactionStartEvent(triggerTokens: Int, messageCount: Int)`
   - `CompactionEndEvent(evictedTokens: Int, remainingMessages: Int)`
   - `HintBlockEvent(hint: String)`
10. **Ошибки и кастомные события:**
    - `ErrorEvent(message: String, code: Option[String] = None)`
    - `CustomEvent(kind: String, payload: zio.json.ast.Json)`

Каждое событие имеет поле `source: Option[String] = None` и метод `withSource(s: String): AgentEvent`. При маршрутизации событий от субагента источник тегируется: `source = parentSessionId/childAgentId`.

---

## 🔍 Референсы в AgentScope Java

* `agentscope-core/src/main/java/io/agentscope/core/hook/AgentStartEvent.java`
* `agentscope-core/src/main/java/io/agentscope/core/hook/TextBlockDeltaEvent.java`
* `agentscope-core/src/main/java/io/agentscope/core/hook/ToolCallStartEvent.java`
* `agentscope-core/src/main/java/io/agentscope/core/hook/ToolResultEndEvent.java`
* `agentscope-harness/src/main/java/io/agentscope/harness/agent/subagent/AgentEventEmitter.java`

---

## 🧪 Карта паритета тестов

| Тест в AgentScope Java | Целевой ZIO Spec | Что проверяет |
| :--- | :--- | :--- |
| `AgentEventTest.java` | `AgentEventSerdeSpec.scala` | Сериализация 31 события с JSON-дискриминатором `type` |
| `AgentSpawnToolCancelEndEventTest.java` | `AgentEventStreamSpec.scala` | Гарантия эмита `AgentEndEvent` при прерывании фибера |
| `RemoteEventCodecTest.java` | `RemoteEventCodecSpec.scala` | Преобразование wire-формата SSE в типизированные события |

---

## 📋 Статус реализации
- [ ] ADT `AgentEvent` (31 кейс-класс)
- [ ] `JsonCodec[AgentEvent]` с плоским дискриминатором `type`
- [ ] Метод `withSource` и обогащение метаданными
- [ ] Unit & SerDe тесты `AgentEventSerdeSpec`

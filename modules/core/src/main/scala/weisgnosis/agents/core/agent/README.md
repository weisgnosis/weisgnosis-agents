# weisgnosis.agents.core.agent

Пакет агентного цикла рассуждения и действий (`ReActAgent`) и управления жизненным циклом одного шага или локальной цепочки ходов.

---

## 🎯 Назначение и типы

1. **`ReActAgent`**:
   - `def run(prompt: String, history: Chunk[Msg] = Chunk.empty): ZIO[Any, AgentError, AgentResult]`
   - `def streamEvents(prompt: String, history: Chunk[Msg] = Chunk.empty): ZStream[Any, AgentError, AgentEvent]`
2. **`AgentConfig`**:
   - `name: String`
   - `systemPrompt: String`
   - `maxIterations: Int = 20`
   - `timeout: Duration = 300.seconds`
   - `emptyThinkingRetryLimit: Int = 2`
3. **`AgentResult`**:
   - `reply: Msg`
   - `history: Chunk[Msg]`
   - `iterationsCount: Int`
   - `usage: TokenUsage`
4. **Хвостовой цикл рассуждений (Tail-Recursive Loop):**
   - Шаг 1: Подготовка истории + вызов `LlmClient.stream`.
   - Шаг 2: Накопление дельт, параллельный эмит `AgentEvent` в поток.
   - Шаг 3: Если модель вернула только текст (без `ToolUse`) -> завершение.
   - Шаг 4: Если модель вернула рассуждения, но нет текста и тулов -> loopback retry с подсказкой.
   - Шаг 5: Если модель вызвала тулы -> параллельное/последовательное исполнение через `Toolkit`.
   - Шаг 6: Запись результатов `ToolResultBlock` в историю -> переход на шаг 1.

---

## 🔍 Референсы в AgentScope Java

* `agentscope-core/src/main/java/io/agentscope/core/agent/ReActAgent.java` (линии 350–700: цикл, линии 1070–1250: стриминг)
* `agentscope-core/.../ReActAgentInterruptToolCallTest.java` (Issue #2409: висячие тулы при прерывании)
* `agentscope-core/.../ReActAgentEmptyResponseRetryTest.java` (защита от пустого thinking)
* `agentscope-core/.../ReActAgentStructuredOutputTest.java` (двойной движок structured output)

---

## 🧪 Карта паритета тестов

| Тест в AgentScope Java | Целевой ZIO Spec | Что проверяет |
| :--- | :--- | :--- |
| `ReActAgentTest.java` | `ReActAgentSpec.scala` | Базовый диалог, вызов тула и ответ модели |
| `ReActAgentInterruptToolCallTest.java` | `ReActAgentInterruptSpec.scala` | Синтез `ToolResultBlock(INTERRUPTED)` при отмене фибера в момент вызова тула |
| `ReActAgentEmptyResponseRetryTest.java` | `ReActAgentThinkingRetrySpec.scala` | Шаг-напоминание, если модель выдала рассуждения, но не дала ответ |
| `ReActAgentStructuredOutputTest.java` | `StructuredOutputSpec.scala` | Инжекция синтетического инструмента `generate_response` при активных тулах |

---

## ⚠️ Критические инварианты
* **Dangling Tool Reconciliation:** В обработчике `ZIO.ensuring` проверяется последнее сообщение ассистента: незакрытые вызовы инструментов обязаны получить `ToolResultBlock(INTERRUPTED)`.
* **Zero Fiber Leaks:** При остановке агента все активные тулы и стримы отменяются через ZIO Structured Concurrency.

---

## 📋 Статус реализации
- [ ] Конфиг `AgentConfig` и результат `AgentResult`
- [ ] Хвостовой рекурсивный цикл ReAct на `ZIO`
- [ ] Потоковый интерфейс `streamEvents` на `ZStream`
- [ ] Механизм Dangling Tool Reconciliation в `ensuring`
- [ ] Тесты `ReActAgentSpec`, `ReActAgentInterruptSpec`, `ReActAgentThinkingRetrySpec`

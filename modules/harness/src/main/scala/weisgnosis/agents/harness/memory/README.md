# weisgnosis.agents.harness.memory

Пакет компактинга истории диалога, оценки токенов и управления долговременным контекстом.

---

## 🎯 Назначение и типы

1. **`CompactorConfig`**:
   - `triggerTokens: Int = 120000` (порог срабатывания сжатия)
   - `targetTokens: Int = 40000` (целевой размер после сжатия)
   - `preserveTailMessages: Int = 6` (нетронутые последние ходы)
2. **`ConversationCompactor`**:
   - `def compact(history: Chunk[Msg], client: LlmClient): ZIO[Any, AgentError, Chunk[Msg]]`
   - Алгоритм поиска безопасной точки отсечения `calculateCutoffIndex`: сдвигает срез влево, чтобы не разорвать пару `Assistant(tool_calls)` и `Tool(tool_results)`.
   - Дедупликация старых саммари: извлекает существующие блоки `__compaction_summary__` и объединяет их с новым срезом в одно плоское резюме.
3. **`TokenCounter`**:
   - Быстрая оценка количества токенов по эвристикам символов / BPE для экономии вызовов к внешним токенизаторам.

---

## 🔍 Референсы в AgentScope Java

* `agentscope-harness/src/main/java/io/agentscope/harness/agent/memory/compaction/ConversationCompactor.java` (линии 60–184)
* `agentscope-harness/src/main/java/io/agentscope/harness/agent/memory/compaction/TokenCounterUtil.java`
* `agentscope-harness/src/main/java/io/agentscope/harness/agent/memory/compaction/CompactionConfig.java`

---

## 🧪 Карта паритета тестов

| Тест в AgentScope Java | Целевой ZIO Spec | Что проверяет |
| :--- | :--- | :--- |
| `ConversationCompactorTest.java` | `ConversationCompactorSpec.scala` | Сжатие истории, соблюдение атомарности пар тулов, объединение старых саммари |
| `TokenCounterTest.java` | `TokenCounterSpec.scala` | Оценка токенов для текста, кода и структурированного JSON |

---

## ⚠️ Критические инварианты
* Разрыв пары вызов/ответ тула превращает диалог в невалидный JSON для LLM и вызывает ошибку API. Срез обязан гарантировать целостность пар.
* Сообщения саммари помечаются метатегом `__compaction_summary__ = true`.

---

## 📋 Статус реализации
- [ ] Конфиг `CompactorConfig` и эвристический `TokenCounter`
- [ ] Чистый алгоритм `ConversationCompactor.calculateCutoff`
- [ ] Логика генерации саммари через LLM с дедупликацией
- [ ] Тесты `ConversationCompactorSpec`

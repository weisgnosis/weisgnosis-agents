# weisgnosis.agents.harness.eviction

Пакет вытеснения тяжелых результатов работы инструментов (Tool Result Eviction) из контекста диалога в офф-контекстное хранилище.

---

## 🎯 Назначение и типы

1. **`EvictionConfig`**:
   - `maxChars: Int = 2000` (порог, выше которого тело ответа инструмента отсекается)
   - `previewHeadChars: Int = 800`
   - `previewTailChars: Int = 400`
2. **`ToolResultEvictor`**:
   - `def evict(block: ContentBlock.ToolResult): ZIO[ToolResultStore, Nothing, ContentBlock.ToolResult]`
   - Если размер `output` > `maxChars`, полный результат сохраняется в `ToolResultStore`, а в блок подставляется:
     ```text
     <first 800 chars>
     ... [Full output truncated: 1420 lines (48200 chars). Offloaded to cache: cache_id_123] ...
     <last 400 chars>
     ```
3. **`ToolResultStore`**:
   - Трейт хранилища для оффлоада: `InMemory`, `LocalFile` или `S3/Blob`.

---

## 🔍 Референсы в AgentScope Java

* `agentscope-harness/src/main/java/io/agentscope/harness/agent/middleware/ToolResultEvictionMiddleware.java` (линии 40–130)
* `agentscope-harness/.../ToolResultEvictionConfig.java`

---

## 🧪 Карта паритета тестов

| Тест в AgentScope Java | Целевой ZIO Spec | Что проверяет |
| :--- | :--- | :--- |
| `ToolResultEvictionMiddlewareTest.java` | `ToolResultEvictorSpec.scala` | Вытеснение результатов превышающих лимит, сохранение превью, извлечение оригинала по ключу |

---

## ⚠️ Критические инварианты
* Сохранение превью начала и конца (head/tail) критически важно для LLM, чтобы модель видела статус выполнения и последние строки вывода (например, `BUILD SUCCESS` или стек-трейс ошибки).
* Маленькие результаты (до 2000 символов) не затрагиваются и передаются без накладных расходов.

---

## 📋 Статус реализации
- [ ] Конфиг `EvictionConfig`
- [ ] Интерфейс хранилища `ToolResultStore` + `InMemoryToolResultStore`
- [ ] Логика вытеснения `ToolResultEvictor.evict`
- [ ] Unit-тесты `ToolResultEvictorSpec`

# weisgnosis-agents-harness

Инженерная обвязка агентного контекста на Scala 3.9 LTS и ZIO 2: управление памятью, вытеснение больших результатов инструментов, трехуровневая безопасность (Human-in-the-Loop), субагенты на фиберах и режим планирования.

---

## 📦 Структура пакетов

| Пакет | Назначение | Исходный модуль AgentScope Java | Оценка SLOC (Main / Test) |
| :--- | :--- | :--- | :---: |
| [`memory`](src/main/scala/weisgnosis/agents/harness/memory/README.md) | Компактинг диалога, дедупликация саммари, оценка токенов | `agentscope-harness/.../memory/compaction/` | ~200 / ~300 |
| [`eviction`](src/main/scala/weisgnosis/agents/harness/eviction/README.md) | Вытеснение результатов тулов >2k символов в офф-контекстный кэш | `agentscope-harness/.../middleware/ToolResultEvictionMiddleware.java` | ~150 / ~250 |
| [`permission`](src/main/scala/weisgnosis/agents/harness/permission/README.md) | Human-in-the-Loop гейтинг (Allow, Deny, Ask через `zio.Promise`) | `agentscope-core/.../permission/` | ~180 / ~300 |
| [`subagent`](src/main/scala/weisgnosis/agents/harness/subagent/README.md) | Дочерние агенты на фиберах (`forkScoped`), мультиплексирование потоков | `agentscope-harness/.../subagent/` | ~250 / ~400 |
| [`plan`](src/main/scala/weisgnosis/agents/harness/plan/README.md) | Режим планирования (Plan Mode), блокировка мутаций, обязательный HITL | `agentscope-harness/.../workspace/plan/` | ~150 / ~200 |

---

## 🎯 Ключевые архитектурные инварианты
1. **Atomic Turn Pairing:** При сжатии истории компактор никогда не разрезает пару вызов тула / результат тула.
2. **Summary Deduplication:** Повторный компактинг не создает «саммари от саммари», а агрегирует факты в плоский текст.
3. **Promise-Based HITL:** Запрос подтверждения оператора приостанавливает выполнение в чистом `zio.Promise`, не блокируя системные потоки.
4. **Subagent Scoping:** Лимит глубины спавна (`MAX_SPAWN_DEPTH = 3`). Отмена родительского агента каскадно гасит всех потомков.

# Модули библиотеки `weisgnosis-agents`

Центральный навигационный индекс и реестр пакетов библиотеки. В каждом каталоге находится детальный мини-вики трекер со ссылками на исходный код AgentScope Java и картой тестов.

---

## 🗺️ Карта модулей и пакетов

### 1. `weisgnosis-agents-core` (Ядро агентного цикла)
* [**README модуля**](core/README.md)
* ├── [`model`](core/src/main/scala/weisgnosis/agents/core/model/README.md) — Доменные сущности `Msg`, `Role`, `ContentBlock` *(Тесты: [model/README.md](core/src/test/scala/weisgnosis/agents/core/model/README.md))*
* ├── [`event`](core/src/main/scala/weisgnosis/agents/core/event/README.md) — 31 типизированное событие `AgentEvent` *(Тесты: [event/README.md](core/src/test/scala/weisgnosis/agents/core/event/README.md))*
* ├── [`tool`](core/src/main/scala/weisgnosis/agents/core/tool/README.md) — Типобезопасные инструменты `Tool` и `Toolkit` *(Тесты: [tool/README.md](core/src/test/scala/weisgnosis/agents/core/tool/README.md))*
* ├── [`client`](core/src/main/scala/weisgnosis/agents/core/client/README.md) — Потоковый SSE-клиент и адаптер DeepSeek *(Тесты: [client/README.md](core/src/test/scala/weisgnosis/agents/core/client/README.md))*
* └── [`agent`](core/src/main/scala/weisgnosis/agents/core/agent/README.md) — Хвостовой цикл `ReActAgent` *(Тесты: [agent/README.md](core/src/test/scala/weisgnosis/agents/core/agent/README.md))*

### 2. `weisgnosis-agents-harness` (Инженерная обвязка контекста)
* [**README модуля**](harness/README.md)
* ├── [`memory`](harness/src/main/scala/weisgnosis/agents/harness/memory/README.md) — Компактинг истории `ConversationCompactor` *(Тесты: [memory/README.md](harness/src/test/scala/weisgnosis/agents/harness/memory/README.md))*
* ├── [`eviction`](harness/src/main/scala/weisgnosis/agents/harness/eviction/README.md) — Вытеснение тяжелых аутпутов `ToolResultEvictor` *(Тесты: [eviction/README.md](harness/src/test/scala/weisgnosis/agents/harness/eviction/README.md))*
* ├── [`permission`](harness/src/main/scala/weisgnosis/agents/harness/permission/README.md) — Human-in-the-Loop гейтинг на `Promise` *(Тесты: [permission/README.md](harness/src/test/scala/weisgnosis/agents/harness/permission/README.md))*
* ├── [`subagent`](harness/src/main/scala/weisgnosis/agents/harness/subagent/README.md) — Координация субагентов на ZIO Fibers *(Тесты: [subagent/README.md](harness/src/test/scala/weisgnosis/agents/harness/subagent/README.md))*
* └── [`plan`](harness/src/main/scala/weisgnosis/agents/harness/plan/README.md) — Режим планирования `PlanMode` *(Тесты: [plan/README.md](harness/src/test/scala/weisgnosis/agents/harness/plan/README.md))*

### 3. `weisgnosis-agents-mcp` (Model Context Protocol)
* [**README модуля**](mcp/README.md)
* ├── [`protocol`](mcp/src/main/scala/weisgnosis/agents/mcp/protocol/README.md) — JSON-RPC 2.0 схемы MCP *(Тесты: [protocol/README.md](mcp/src/test/scala/weisgnosis/agents/mcp/protocol/README.md))*
* ├── [`transport`](mcp/src/main/scala/weisgnosis/agents/mcp/transport/README.md) — Транспорты Stdio (`zio.process`) и SSE (`zio-http`) *(Тесты: [transport/README.md](mcp/src/test/scala/weisgnosis/agents/mcp/transport/README.md))*
* └── [`client`](mcp/src/main/scala/weisgnosis/agents/mcp/client/README.md) — Клиент `McpClient` и мост к `Toolkit` *(Тесты: [client/README.md](mcp/src/test/scala/weisgnosis/agents/mcp/client/README.md))*

---

## 🎯 Правила ведения трекеров
1. Каждый мини-вики трекер обновляется по мере реализации классов и добавления тестов.
2. Никаких пустых заглушек (stubs) без тестов: каждый доменный компонент сопровождается парным ZIO Test спеком.
3. Прогресс отслеживается через чекбоксы `[ ]` $\to$ `[x]`.

# weisgnosis-agents-mcp

Нативный ZIO-клиент для **Model Context Protocol (MCP)**, позволяющий подключать любые внешние MCP-серверы (файловые системы, GitHub, базы данных, браузеры) и автоматически преобразовывать их инструменты в типизированный `Toolkit` агента.

---

## 📦 Структура пакетов

| Пакет | Назначение | Оценка SLOC (Main / Test) |
| :--- | :--- | :---: |
| [`protocol`](src/main/scala/weisgnosis/agents/mcp/protocol/README.md) | JSON-RPC 2.0 схемы сообщений MCP, структуры инструментов и ресурсов | ~150 / ~150 |
| [`transport`](src/main/scala/weisgnosis/agents/mcp/transport/README.md) | Транспорты: Stdio (`zio.process`) и SSE (`zio-http`) | ~200 / ~200 |
| [`client`](src/main/scala/weisgnosis/agents/mcp/client/README.md) | Высокоуровневый `McpClient`, discovery тулов, мост к `Toolkit` | ~150 / ~150 |

---

## 🎯 Ключевые архитектурные инварианты
1. **Строгое соответствие спецификации:** Полная поддержка MCP 2024-11-05+ (JSON-RPC 2.0).
2. **Resource Safety:** Процессы локальных серверов (Stdio) изолируются и гарантированно завершаются при выходе из скоупа ZIO (`Scope`).
3. **Seamless Toolkit Integration:** Инструменты MCP-сервера регистрируются в `Toolkit` агента в 1 строчку кода:
   ```scala
   val agentToolkit = toolkit ++ mcpClient.asToolkit
   ```

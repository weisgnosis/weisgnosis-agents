# weisgnosis.agents.mcp.client

Высокоуровневый клиент Model Context Protocol, обнаружение инструментов и интеграция в агентный цикл.

---

## 🎯 Назначение и типы

1. **`McpClient`**:
   - `def initialize: ZIO[Any, Throwable, InitializeResult]`
   - `def listTools: ZIO[Any, Throwable, Chunk[McpTool]]`
   - `def callTool(name: String, args: zio.json.ast.Json): ZIO[Any, Throwable, CallToolResult]`
   - `def asToolkit: ZIO[Any, Throwable, Toolkit]` (автоматический мост в нативный `Toolkit` агента)
2. **Мост инструментов `McpToolBridge`**:
   - Преобразует каждый обнаруженный `McpTool` в нативный `Tool[Any, Json, String]`.
   - Проксирует вызовы в `mcpClient.callTool`.

---

## 🧪 Карта паритета тестов

| Название теста | Целевой ZIO Spec | Что проверяет |
| :--- | :--- | :--- |
| MCP Client Lifecycle | `McpClientSpec.scala` | Инициализация, hand-shake, получение списка инструментов |
| Toolkit Bridge Test | `McpToolkitBridgeSpec.scala` | Регистрация MCP-инструментов в агенте, успешное выполнение вызова модели |

---

## 📋 Статус реализации
- [ ] Класс `McpClient` с методами жизненного цикла
- [ ] Адаптер `asToolkit`
- [ ] Unit & Mock тесты `McpClientSpec`

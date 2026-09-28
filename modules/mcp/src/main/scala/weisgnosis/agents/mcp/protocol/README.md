# weisgnosis.agents.mcp.protocol

Пакет протокольных структур Model Context Protocol на базе спецификации JSON-RPC 2.0.

---

## 🎯 Назначение и типы

1. **JSON-RPC 2.0 базовые типы:**
   - `JsonRpcRequest(jsonrpc: String = "2.0", id: RequestId, method: String, params: Option[zio.json.ast.Json])`
   - `JsonRpcResponse(jsonrpc: String = "2.0", id: RequestId, result: Option[zio.json.ast.Json], error: Option[JsonRpcError])`
   - `JsonRpcNotification(jsonrpc: String = "2.0", method: String, params: Option[zio.json.ast.Json])`
   - `JsonRpcError(code: Int, message: String, data: Option[zio.json.ast.Json])`
2. **MCP Специфика:**
   - `InitializeParams`, `InitializeResult`, `ServerCapabilities`
   - `ListToolsResult`, `McpTool(name: String, description: Option[String], inputSchema: zio.json.ast.Json)`
   - `CallToolParams(name: String, arguments: Option[zio.json.ast.Json])`
   - `CallToolResult(content: Chunk[McpContent], isError: Boolean = false)`

---

## 🧪 Карта паритета тестов

| Название теста | Целевой ZIO Spec | Что проверяет |
| :--- | :--- | :--- |
| MCP Protocol Spec | `McpProtocolSpec.scala` | Сериализация запросов/ответов, валидация полей `jsonrpc: "2.0"` |
| Tool Schema Parsing | `McpToolCodecSpec.scala` | Корректный разбор схем аргументов инструментов и ошибок |

---

## 📋 Статус реализации
- [ ] Базовые типы JSON-RPC 2.0 + кодеки
- [ ] MCP типы инициализации, списков инструментов и вызовов
- [ ] Unit-тесты SerDe `McpProtocolSpec`

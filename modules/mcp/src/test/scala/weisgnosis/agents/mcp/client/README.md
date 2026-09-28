# Test Suite: weisgnosis.agents.mcp.client

Карта тестов высокоуровневого клиента MCP и моста инструментов:

| ZIO Spec | Описание проверок |
| :--- | :--- |
| `McpClientSpec.scala` | Инициализация клиента, negotiation capabilities, получение списка доступных тулов |
| `McpToolkitBridgeSpec.scala` | Преобразование `McpTool` в нативный `Tool` агента и выполнение вызова через мост |

# Test Suite: weisgnosis.agents.mcp.transport

Карта тестов транспортов Stdio и SSE:

| ZIO Spec | Описание проверок |
| :--- | :--- |
| `StdioTransportSpec.scala` | Запуск локального процесса, обмен JSON-RPC, корректная передача сигналов закрытия |
| `SseTransportSpec.scala` | Чтение потока событий с эндпоинта `/sse` и отправка POST-запросов |

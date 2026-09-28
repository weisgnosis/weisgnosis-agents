# weisgnosis.agents.mcp.transport

Пакет транспортов для двустороннего обмена сообщениями с MCP-серверами (Stdio и SSE).

---

## 🎯 Назначение и типы

1. **`McpTransport`**:
   - `def send(request: JsonRpcRequest): ZIO[Any, Throwable, JsonRpcResponse]`
   - `def notify(notification: JsonRpcNotification): ZIO[Any, Throwable, Unit]`
   - `def incoming: ZStream[Any, Throwable, JsonRpcNotification]`
2. **`StdioTransport`**:
   - Запуск дочернего процесса через `zio.process.Command(command, args*)`.
   - Построчная передача сообщений JSON-RPC через stdin/stdout.
   - Автоматическое завершение процесса при выходе из `Scope`.
3. **`SseTransport`**:
   - Подключение к удаленному MCP-серверу через HTTP SSE (`zio-http`).
   - Чтение потока событий с endpoint'а `/sse` и отправка POST-запросов на указанный `endpoint`.

---

## 🧪 Карта паритета тестов

| Название теста | Целевой ZIO Spec | Что проверяет |
| :--- | :--- | :--- |
| Stdio Echo Test | `StdioTransportSpec.scala` | Запуск локального процесса, обмен request/response, корректное закрытие stdin |
| Sse Transport Test | `SseTransportSpec.scala` | Подключение к HTTP mock-серверу, разбор SSE сообщений |

---

## ⚠️ Критические инварианты
* Stdio транспорт обязан корректно обрабатывать буферизацию строк и не зависать при частичной записи чанков.
* В случае падения процесса дочернего сервера транспорт возвращает типизированную ошибку с кодом выхода.

---

## 📋 Статус реализации
- [ ] Трейт `McpTransport`
- [ ] Имплементация `StdioTransport` на `zio.process`
- [ ] Имплементация `SseTransport` на `zio-http`
- [ ] Интеграционные тесты транспортов

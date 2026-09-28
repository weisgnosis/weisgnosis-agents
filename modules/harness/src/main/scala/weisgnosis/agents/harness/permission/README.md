# weisgnosis.agents.harness.permission

Пакет управления правами и Human-in-the-Loop (HITL) гейтинга вызовов инструментов.

---

## 🎯 Назначение и типы

1. **`PermissionBehavior`** (enum):
   - `Allow`: Выполнить немедленно.
   - `Deny`: Заблокировать вызов и вернуть ошибку доступа модели.
   - `Ask`: Запросить подтверждение у оператора / человека.
2. **`PermissionDecision`** (sealed trait):
   - `Approved(updatedInput: Option[zio.json.ast.Json] = None)`
   - `Rejected(reason: String)`
3. **`PermissionRule`**:
   - `toolName: String`
   - `pattern: Option[String] = None` (например, регулярное выражение или префикс аргументов)
   - `behavior: PermissionBehavior`
4. **`PermissionGate`**:
   - `def check(toolName: String, input: zio.json.ast.Json): ZIO[Any, Nothing, PermissionBehavior]`
   - `def requestApproval(toolName: String, input: zio.json.ast.Json): ZIO[Any, Nothing, PermissionDecision]`
   - В ZIO пауза реализуется через создание `Promise[Nothing, PermissionDecision]`. Поток событий получает `PermissionAskEvent(requestId, toolName, input)`, а внешний клиент вызывает `gate.resolve(requestId, decision)`.

---

## 🔍 Референсы в AgentScope Java

* `agentscope-core/src/main/java/io/agentscope/core/permission/PermissionEngine.java`
* `agentscope-core/src/main/java/io/agentscope/core/permission/PermissionRule.java`
* `agentscope-harness/src/main/java/io/agentscope/harness/agent/middleware/PermissionMiddleware.java`

---

## 🧪 Карта паритета тестов

| Тест в AgentScope Java | Целевой ZIO Spec | Что проверяет |
| :--- | :--- | :--- |
| `PermissionEngineTest.java` | `PermissionGateSpec.scala` | Сопоставление правил, приоритет Deny > Ask > Allow |
| `InteractivePermissionTest.java` | `PermissionPromiseSpec.scala` | Асинхронная пауза через `Promise`, подтверждение и возобновление без блокировки тредов |

---

## ⚠️ Критические инварианты
* При отклонении запроса (`Rejected`) инструмент не выполняется, а в историю пишется `ToolResultBlock(state = ToolResultState.Denied, output = reason)`.
* При синхронном ожидании ответа удаленного агента без интерактивного канала запрос автоматически отклоняется во избежание взаимной блокировки.

---

## 📋 Статус реализации
- [ ] Модели `PermissionBehavior`, `PermissionRule`, `PermissionDecision`
- [ ] Движок правил `PermissionGate`
- [ ] Асинхронный координатор подтверждений на `zio.Promise`
- [ ] Unit-тесты `PermissionGateSpec`

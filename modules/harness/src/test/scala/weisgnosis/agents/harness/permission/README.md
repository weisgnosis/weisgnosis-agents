# Test Suite: weisgnosis.agents.harness.permission

Карта тестов гейтинга прав и подтверждений человека (Human-in-the-Loop):

| ZIO Spec | Исходный тест AgentScope Java | Описание проверок |
| :--- | :--- | :--- |
| `PermissionGateSpec.scala` | `PermissionEngineTest.java` | Таблица правил: превосходство Deny над Allow, точное сопоставление аргументов |
| `PermissionPromiseSpec.scala` | `InteractivePermissionTest.java` | Пауза на `Promise`, возобновление при `resolve`, отмена при `reject` |

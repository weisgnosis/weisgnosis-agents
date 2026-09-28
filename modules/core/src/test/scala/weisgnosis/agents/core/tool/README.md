# Test Suite: weisgnosis.agents.core.tool

Карта тестов движка инструментов и безопасности:

| ZIO Spec | Исходный тест AgentScope Java | Описание проверок |
| :--- | :--- | :--- |
| `ToolkitSpec.scala` | `ToolkitTest.java` | Регистрация, поиск инструмента, исключение дубликатов, генерация JSON Schema |
| `ToolExecutionSpec.scala` | `ToolExecutionTest.java` | Десериализация параметров из JSON, вызов функции, преобразование ошибок в `ToolResultState.Error` |
| `PathSafetySpec.scala` | `DangerousPathBypassTest.java` | Защита от опасных путей (`.ssh`, `.env`), разрешение симлинков `toRealPath()` |

# Test Suite: weisgnosis.agents.core.agent

Карта тестов агентного цикла ReAct и машины состояний:

| ZIO Spec | Исходный тест AgentScope Java | Описание проверок |
| :--- | :--- | :--- |
| `ReActAgentSpec.scala` | `ReActAgentTest.java` | Полный цикл: вопрос -> рассуждение -> вызов тула -> ответ пользователю |
| `ReActAgentInterruptSpec.scala` | `ReActAgentInterruptToolCallTest.java` | Issue #2409: Синтез `ToolResultBlock(INTERRUPTED)` при отмене фибера |
| `ReActAgentThinkingRetrySpec.scala` | `ReActAgentEmptyResponseRetryTest.java` | Защита от пустых ответов thinking-моделей (loopback retry) |
| `StructuredOutputSpec.scala` | `ReActAgentStructuredOutputTest.java` | Двойной движок: нативный response_format vs терминальный инструмент |

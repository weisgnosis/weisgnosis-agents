# Test Suite: weisgnosis.agents.harness.eviction

Карта тестов вытеснения тяжелых результатов тулов:

| ZIO Spec | Исходный тест AgentScope Java | Описание проверок |
| :--- | :--- | :--- |
| `ToolResultEvictorSpec.scala` | `ToolResultEvictionMiddlewareTest.java` | Отсечение тела >2000 символов, сохранение превью head/tail, целостность кэша |

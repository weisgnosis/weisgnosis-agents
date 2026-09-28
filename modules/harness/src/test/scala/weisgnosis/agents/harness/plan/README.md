# Test Suite: weisgnosis.agents.harness.plan

Карта тестов режима планирования:

| ZIO Spec | Исходный тест AgentScope Java | Описание проверок |
| :--- | :--- | :--- |
| `PlanModeInterceptorSpec.scala` | `PlanModeMiddlewareTest.java` | Запрет вызова мутирующих инструментов в режиме планирования |
| `PlanModeExitSpec.scala` | `PlanModeToolsTest.java` | Обязательный запрос подтверждения оператора (`Ask`) при вызове `plan_exit` |

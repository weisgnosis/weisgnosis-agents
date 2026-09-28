# Test Suite: weisgnosis.agents.harness.subagent

Карта тестов субагентов и координации:

| ZIO Spec | Исходный тест AgentScope Java | Описание проверок |
| :--- | :--- | :--- |
| `SubagentSpawnSpec.scala` | `AgentSpawnToolTest.java` | Спавн субагента, передача контекста, ограничение `MAX_SPAWN_DEPTH = 3` |
| `SubagentCancelSpec.scala` | `AgentSpawnToolCancelEndEventTest.java` | Закрытие стрима с `AgentEndEvent` и каскадная отмена дочерних фиберов |
| `SubagentPlanModePropagationSpec.scala` | `AgentSpawnToolPlanModeTest.java` | Сквозная передача флага `planActive` и правил DENY дочерним агентам |

# weisgnosis.agents.harness.plan

Пакет управления режимом планирования (Plan Mode) и безопасным переходом от проектирования к выполнению.

---

## 🎯 Назначение и типы

1. **`PlanModeState`**:
   - `active: Boolean` (активен ли режим планирования)
   - `planFile: String = "plans/PLAN.md"`
2. **`PlanModeInterceptor`**:
   - Фильтрует доступные инструменты:
     - Разрешены: чтение файлов, grep, `plan_write`, `todo_write`, `agent_spawn`.
     - Заблокированы: `write_file`, `edit_file`, мутирующие команды bash.
   - Заблокированные тулы получают `ToolResultState.Denied` с сообщением: *"File mutation is blocked in PLAN mode. Call plan_exit to request execution approval."*
3. **`PlanModeTools`**:
   - `plan_enter`: активирует режим планирования.
   - `plan_write(content: String)`: записывает черновик плана в `plans/PLAN.md`.
   - `plan_exit`: запрашивает подтверждение оператора (`PermissionDecision.ask`) на переход в фазу исполнения (BUILD mode).

---

## 🔍 Референсы в AgentScope Java

* `agentscope-harness/src/main/java/io/agentscope/harness/agent/workspace/plan/PlanModeManager.java`
* `agentscope-harness/src/main/java/io/agentscope/harness/agent/tool/PlanModeTools.java`
* `agentscope-harness/src/main/java/io/agentscope/harness/agent/middleware/PlanModeMiddleware.java`

---

## 🧪 Карта паритета тестов

| Тест в AgentScope Java | Целевой ZIO Spec | Что проверяет |
| :--- | :--- | :--- |
| `PlanModeMiddlewareTest.java` | `PlanModeInterceptorSpec.scala` | Блокировка мутаций, разрешение чтения и `plan_write` |
| `PlanModeSubagentPropagationTest.java` | `PlanModePropagationSpec.scala` | Наследование режима планирования порожденными субагентами |

---

## ⚠️ Критические инварианты
* Выход из Plan Mode (`plan_exit`) **обязан** требовать подтверждения человека (HITL Gate). Агент не может самостоятельно разрешить себе мутации.
* Дочерние агенты не могут обойти ограничения родителя.

---

## 📋 Статус реализации
- [ ] Состояние `PlanModeState`
- [ ] Набор инструментов `PlanModeTools`
- [ ] Фильтр вызовов `PlanModeInterceptor`
- [ ] Unit-тесты `PlanModeInterceptorSpec`

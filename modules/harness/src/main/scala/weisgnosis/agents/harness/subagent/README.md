# weisgnosis.agents.harness.subagent

Пакет координации и запуска субагентов, иерархического делегирования и слияния потоков событий на ZIO Fibers.

---

## 🎯 Назначение и типы

1. **`SubagentDeclaration`**:
   - `id: String` (уникальный тип субагента)
   - `description: String`
   - `systemPrompt: String`
   - `allowedTools: Set[String]` (фильтр доступных инструментов)
   - `persistSession: Boolean = false`
2. **`SubagentCoordinator`**:
   - `spawn(agentId: String, task: String, options: SpawnOptions): ZIO[Scope, AgentError, SubagentHandle]`
   - `send(handle: SubagentHandle, message: String): ZIO[Any, AgentError, Msg]`
3. **Мультиплексирование потоков событий:**
   - Потоки событий родителя и ребенка объединяются через `ZStream.merge`.
   - События ребенка тегируются методом `withSource(s"$parentSessionId/$childAgentId")`.
   - Гарантируется эмит `AgentStartEvent` и `AgentEndEvent` для каждого ребенка даже при отмене фибера.
4. **Защиты:**
   - Лимит глубины спавна: `MAX_SPAWN_DEPTH = 3`.
   - Timeout Promotion: если синхронный вызов превысил таймаут, задача не сбрасывается, а переводится в фоновый фибер.

---

## 🔍 Референсы в AgentScope Java

* `agentscope-harness/src/main/java/io/agentscope/harness/agent/subagent/AgentSpawnTool.java` (линии 300–700)
* `agentscope-harness/src/main/java/io/agentscope/harness/agent/subagent/AgentEventEmitter.java`
* `agentscope-harness/src/main/java/io/agentscope/harness/agent/middleware/SubagentsMiddleware.java`

---

## 🧪 Карта паритета тестов

| Тест в AgentScope Java | Целевой ZIO Spec | Что проверяет |
| :--- | :--- | :--- |
| `AgentSpawnToolTest.java` | `SubagentSpawnSpec.scala` | Создание субагента, передача контекста, лимит глубины 3 |
| `AgentSpawnToolCancelEndEventTest.java` | `SubagentCancelSpec.scala` | Гарантия завершения потока событий и каскадная отмена фибера ребенка |
| `AgentSpawnToolPlanModeTest.java` | `SubagentPlanModePropagationSpec.scala` | Сквозная передача режима Plan Mode дочерним агентам |

---

## ⚠️ Критические инварианты
* При отмене фибера родителя (`fiber.interrupt`) все порожденные субагенты обязаны быть немедленно прерваны (`forkScoped`).
* События от субагентов не должны перемешиваться без явного тега `source`.

---

## 📋 Статус реализации
- [ ] Модели `SubagentDeclaration`, `SpawnOptions`, `SubagentHandle`
- [ ] Инструмент `AgentSpawnTool` (`agent_spawn`, `agent_send`)
- [ ] Мультиплексор потоков `ZStream.merge` с тегированием
- [ ] Тесты `SubagentSpawnSpec`, `SubagentCancelSpec`

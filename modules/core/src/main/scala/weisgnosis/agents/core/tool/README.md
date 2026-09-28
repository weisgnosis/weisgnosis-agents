# weisgnosis.agents.core.tool

Пакет типизированных инструментов, регистрации и диспетчеризации вызовов LLM.

---

## 🎯 Назначение и типы

1. **`ToolDef`**:
   - `name: String`
   - `description: String`
   - `parametersSchema: zio.json.ast.Json` (JSON Schema объекта аргументов)
2. **`Tool[R, In, Out]`**:
   - Абстракция инструмента.
   - `def definition: ToolDef`
   - `def execute(input: In): ZIO[R, Throwable, Out]`
   - Декодер аргументов из `zio.json.ast.Json`.
3. **`Toolkit`**:
   - Неизменяемая коллекция зарегистрированных инструментов.
   - `def register(tool: Tool[Any, ?, ?]): Toolkit`
   - `def get(name: String): Option[Tool[Any, ?, ?]]`
   - `def definitions: Chunk[ToolDef]`
   - `def executeRaw(name: String, args: zio.json.ast.Json): ZIO[Any, AgentError, String]`
   - Проверка коллизий имен при регистрации.
4. **Удобные фабрики:**
   - `Tool.fromFunction("add", "Add two numbers") { (a: Int, b: Int) => ZIO.succeed(a + b) }`
   - `Tool.make(definition) { (json: Json) => ... }`

---

## 🔍 Референсы в AgentScope Java

* `agentscope-core/src/main/java/io/agentscope/core/tool/Tool.java`
* `agentscope-core/src/main/java/io/agentscope/core/tool/Toolkit.java`
* `agentscope-core/src/main/java/io/agentscope/core/tool/ToolDefinition.java`
* `agentscope-core/src/main/java/io/agentscope/core/tool/ToolBase.java` (линии 245–285: защита опасных путей `isDangerousPath`)

---

## 🧪 Карта паритета тестов

| Тест в AgentScope Java | Целевой ZIO Spec | Что проверяет |
| :--- | :--- | :--- |
| `ToolkitTest.java` | `ToolkitSpec.scala` | Регистрация, поиск по имени, валидация дубликатов |
| `ToolExecutionTest.java` | `ToolExecutionSpec.scala` | Декодирование аргументов JSON, обработка ошибок, возврат типизированного `ToolResultState` |
| `DangerousPathBypassTest.java` | `PathSafetySpec.scala` | Защита от опасных путей (`.env`, `.ssh`), резолв `toRealPath()` |

---

## ⚠️ Критические инварианты
* Если аргументы вызова тула не парсятся (невалидный JSON от модели), тул не бросает исключение, а возвращает `ToolResultState.Error` со структурированным сообщением для модели, чтобы та могла исправить ошибку.
* Инструменты, выполняющие долгие операции, поддерживают прерывание ZIO фибера.

---

## 📋 Статус реализации
- [ ] Модели `ToolDef`, `ToolCallRequest`, `ToolCallResult`
- [ ] Трейт `Tool[R, In, Out]` и фабрики создания
- [ ] Коллекция `Toolkit` с валидацией схемы
- [ ] Unit-тесты `ToolkitSpec`
- [ ] Тесты безопасности путей `PathSafetySpec`

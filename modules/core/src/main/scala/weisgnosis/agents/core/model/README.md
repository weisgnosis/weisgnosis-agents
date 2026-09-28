# weisgnosis.agents.core.model

Пакет доменных моделей сообщений, блоков контента и ролей для диалога с LLM.

---

## 🎯 Назначение и типы

1. **`Role`** (enum):
   - `System`, `User`, `Assistant`, `Tool`.
   - Сериализуется в lowercase строку (`system`, `user`, `assistant`, `tool`).
2. **`ToolResultState`** (enum):
   - `Success`, `Error`, `Interrupted`, `Denied`.
3. **`ContentBlock`** (sealed trait / ADT):
   - `Text(text: String)`
   - `Thinking(thought: String, signature: Option[String] = None)`
   - `ToolUse(id: String, name: String, input: zio.json.ast.Json, isServerTool: Boolean = false)`
   - `ToolResult(toolUseId: String, name: String, output: String, state: ToolResultState = ToolResultState.Success)`
   - `Image(url: Option[String] = None, data: Option[String] = None, mediaType: String = "image/png")`
   - `Custom(kind: String, payload: zio.json.ast.Json)`
4. **`Msg`** (case class):
   - `id: String` (UUID)
   - `role: Role`
   - `content: Chunk[ContentBlock]`
   - `name: Option[String] = None`
   - `metadata: Map[String, String] = Map.empty`
   - `timestamp: Long = System.currentTimeMillis()`

---

## 🔍 Референсы в AgentScope Java

* `agentscope-core/src/main/java/io/agentscope/core/message/Msg.java` (линии 36–160)
* `agentscope-core/src/main/java/io/agentscope/core/message/ContentBlock.java`
* `agentscope-core/src/main/java/io/agentscope/core/message/TextBlock.java`
* `agentscope-core/src/main/java/io/agentscope/core/message/ToolUseBlock.java`
* `agentscope-core/src/main/java/io/agentscope/core/message/ToolResultBlock.java`
* `agentscope-core/src/main/java/io/agentscope/core/message/ThinkingBlock.java`

---

## 🧪 Карта паритета тестов

| Тест в AgentScope Java | Целевой ZIO Spec | Что проверяет |
| :--- | :--- | :--- |
| `MsgTest.java` | `MsgSpec.scala` | Создание сообщений, добавление блоков, удобные фабрики (`Msg.user`, `Msg.assistant`) |
| `MsgJacksonModuleTest.java` | `MsgSerdeSpec.scala` | 100% round-trip сериализация/десериализация в JSON (wire format) |
| `ContentBlockTest.java` | `ContentBlockSerdeSpec.scala` | Полиморфный парсинг блоков с дискриминатором `type` |

---

## ⚠️ Критические инварианты
* `ToolUse.isServerTool`: если `true`, инструмент не ищется в локальном тулките (Anthropic web search, Gemini code execution).
* Блоки `Thinking` поддерживают извлечение `signature` (Claude 3.7 / Anthropic thinking protocol).
* `Msg.text`: вспомогательный метод, извлекающий склеенный текст из всех `Text` блоков.

---

## 📋 Статус реализации
- [ ] ADT `Role`, `ToolResultState`
- [ ] ADT `ContentBlock` + `zio-json` Codecs
- [ ] `case class Msg` + кодеки
- [ ] Unit-тесты `MsgSpec`
- [ ] SerDe-тесты `MsgSerdeSpec`

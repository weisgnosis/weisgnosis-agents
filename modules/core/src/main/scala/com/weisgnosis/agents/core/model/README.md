# com.weisgnosis.agents.core.model

Domain model package for conversation messages, content blocks, and agent roles.

---

## 🎯 Purpose and Types

1. **`Role`** (`enum`):
   - `System`, `User`, `Assistant`, `Tool`.
   - Serialized as lowercase strings (`system`, `user`, `assistant`, `tool`).
2. **`ToolResultState`** (`enum`):
   - `Success`, `Error`, `Interrupted`, `Denied`.
3. **`ContentBlock`** (sealed ADT):
   - `Text(text: String)`
   - `Thinking(thought: String, signature: Option[String] = None)`
   - `ToolUse(id: String, name: String, input: zio.json.ast.Json, isServerTool: Boolean = false)`
   - `ToolResult(toolUseId: String, name: String, output: String, state: ToolResultState = ToolResultState.Success)`
   - `Image(url: Option[String] = None, data: Option[String] = None, mediaType: String = "image/png")`
   - `Custom(kind: String, payload: zio.json.ast.Json)`
4. **`Msg`** (`case class`):
   - `id: String` (UUID)
   - `role: Role`
   - `content: Chunk[ContentBlock]`
   - `name: Option[String] = None`
   - `metadata: Map[String, String] = Map.empty`
   - `timestamp: Long = System.currentTimeMillis()`

---

## 🔍 References in AgentScope Java

* `agentscope-core/src/main/java/io/agentscope/core/message/Msg.java` (lines 36–160)
* `agentscope-core/src/main/java/io/agentscope/core/message/ContentBlock.java`
* `agentscope-core/src/main/java/io/agentscope/core/message/TextBlock.java`
* `agentscope-core/src/main/java/io/agentscope/core/message/ToolUseBlock.java`
* `agentscope-core/src/main/java/io/agentscope/core/message/ToolResultBlock.java`
* `agentscope-core/src/main/java/io/agentscope/core/message/ThinkingBlock.java`

---

## 🧪 Test Parity Map

| Upstream AgentScope Java Test | Target ZIO Spec | Verification Focus |
| :--- | :--- | :--- |
| `MsgTest.java` | `MsgSpec.scala` | Message creation, block concatenation, factories (`Msg.user`, `Msg.assistant`) |
| `MsgJacksonModuleTest.java` | `MsgSerdeSpec.scala` | 100% round-trip JSON serialization/deserialization (wire format) |
| `ContentBlockTest.java` | `ContentBlockSerdeSpec.scala` | Polymorphic block decoding with `type` discriminator |

---

## ⚠️ Critical Invariants
* `ToolUse.isServerTool`: When `true`, dispatch bypasses local tool lookup (used for hosted search, remote sandboxes).
* `Thinking` blocks support cryptographic reasoning `signature` verification where provided by provider APIs.
* `Msg.text`: Utility method extracting concatenated text from all `Text` blocks.

---

## 📋 Implementation Checklist
- [ ] ADTs `Role`, `ToolResultState`
- [ ] ADT `ContentBlock` + `zio-json` codecs
- [ ] `Msg` model + convenience builders
- [ ] `MsgSpec.scala` (1:1 with `MsgTest.java`)
- [ ] `MsgSerdeSpec.scala` (1:1 with `MsgJacksonModuleTest.java`)
- [ ] `ContentBlockSerdeSpec.scala` (1:1 with `ContentBlockTest.java`)

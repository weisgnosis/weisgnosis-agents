# com.weisgnosis.agents.core.tool

Type-safe tool execution, registration, and dispatch package for model interactions.

---

## 🎯 Purpose and Types

1. **`ToolDef`**:
   - `name: String`
   - `description: String`
   - `parametersSchema: zio.json.ast.Json` (JSON Schema for argument validation)
2. **`Tool[R, In, Out]`**:
   - Tool abstraction:
     - `def definition: ToolDef`
     - `def execute(input: In): ZIO[R, Throwable, Out]`
     - Argument decoder from `zio.json.ast.Json`.
3. **`Toolkit`**:
   - Immutable collection of registered tools:
     - `def register(tool: Tool[Any, ?, ?]): Toolkit`
     - `def get(name: String): Option[Tool[Any, ?, ?]]`
     - `def definitions: Chunk[ToolDef]`
     - `def executeRaw(name: String, args: zio.json.ast.Json): ZIO[Any, AgentError, String]`
     - Collision detection on duplicate registration.
4. **Convenience Builders:**
   - `Tool.fromFunction("add", "Add two numbers") { (a: Int, b: Int) => ZIO.succeed(a + b) }`
   - `Tool.make(definition) { (json: Json) => ... }`

---

## 🔍 References in AgentScope Java

* `agentscope-core/src/main/java/io/agentscope/core/tool/Tool.java`
* `agentscope-core/src/main/java/io/agentscope/core/tool/Toolkit.java`
* `agentscope-core/src/main/java/io/agentscope/core/tool/ToolDefinition.java`
* `agentscope-core/src/main/java/io/agentscope/core/tool/ToolBase.java` (lines 245–285: sensitive path filtering `isDangerousPath`)

---

## 🧪 Test Parity Map

| Upstream AgentScope Java Test | Target ZIO Spec | Verification Focus |
| :--- | :--- | :--- |
| `ToolkitTest.java` | `ToolkitSpec.scala` | Registration, lookup by name, duplicate rejection |
| `ToolExecutionTest.java` | `ToolExecutionSpec.scala` | JSON arg parsing, error recovery, typed `ToolResultState` |
| `DangerousPathBypassTest.java` | `PathSafetySpec.scala` | Sensitive path protection (`.env`, `.ssh`), path canonicalization |

---

## ⚠️ Critical Invariants
* If tool call arguments fail schema validation (malformed model JSON), the tool does not throw an exception; it returns a structured `ToolResultState.Error` payload informing the model so it can correct its call.
* Long-running tool executions are responsive to ZIO fiber interruption.

---

## 📋 Implementation Checklist
- [ ] Models `ToolDef`, `ToolCallRequest`, `ToolCallResult`
- [ ] Trait `Tool[R, In, Out]` and builder factories
- [ ] Immutable `Toolkit` collection with schema validation
- [ ] Unit tests: `ToolkitSpec.scala`
- [ ] Unit tests: `ToolExecutionSpec.scala`
- [ ] Unit tests: `PathSafetySpec.scala`

# com.weisgnosis.agents.harness.permission

Authorization policies and Human-in-the-Loop (HITL) execution gating package.

---

## 🎯 Purpose and Types

1. **`PermissionBehavior`** (`enum`):
   - `Allow`: Execute immediately.
   - `Deny`: Reject tool invocation and return access error to model.
   - `Ask`: Require interactive operator confirmation.
2. **`PermissionDecision`** (sealed trait):
   - `Approved(updatedInput: Option[zio.json.ast.Json] = None)`
   - `Rejected(reason: String)`
3. **`PermissionRule`**:
   - `toolName: String`
   - `pattern: Option[String] = None` (argument pattern / regex)
   - `behavior: PermissionBehavior`
4. **`PermissionGate`**:
   - `def check(toolName: String, input: zio.json.ast.Json): ZIO[Any, Nothing, PermissionBehavior]`
   - `def requestApproval(toolName: String, input: zio.json.ast.Json): ZIO[Any, Nothing, PermissionDecision]`
   - In ZIO, suspension is realized via pure `Promise[Nothing, PermissionDecision]`. The event stream emits `PermissionAskEvent(requestId, toolName, input)`, and an external consumer invokes `gate.resolve(requestId, decision)`.

---

## 🔍 References in AgentScope Java

* `agentscope-core/src/main/java/io/agentscope/core/permission/PermissionEngine.java`
* `agentscope-core/src/main/java/io/agentscope/core/permission/PermissionRule.java`
* `agentscope-harness/src/main/java/io/agentscope/harness/agent/middleware/PermissionMiddleware.java`

---

## 🧪 Test Parity Map

| Upstream AgentScope Java Test | Target ZIO Spec | Verification Focus |
| :--- | :--- | :--- |
| `PermissionEngineTest.java` | `PermissionGateSpec.scala` | Rule matching precedence: Deny > Ask > Allow |
| `InteractivePermissionTest.java` | `PermissionPromiseSpec.scala` | Async suspension via `Promise`, approval and resumption without thread blocking |

---

## ⚠️ Critical Invariants
* On rejection (`Rejected`), the tool is never executed; history receives `ToolResultBlock(state = ToolResultState.Denied, output = reason)`.
* In non-interactive environments where approval resolution is unavailable, `Ask` safely defaults to `Denied` to prevent deadlocks.

---

## 📋 Implementation Checklist
- [ ] Models `PermissionBehavior`, `PermissionRule`, `PermissionDecision`
- [ ] Policy engine `PermissionGate`
- [ ] Asynchronous approval coordinator backed by `zio.Promise`
- [ ] Unit tests: `PermissionGateSpec.scala`
- [ ] Async tests: `PermissionPromiseSpec.scala`

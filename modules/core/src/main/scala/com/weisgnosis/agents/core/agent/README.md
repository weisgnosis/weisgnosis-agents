# com.weisgnosis.agents.core.agent

ReAct agent loop implementation (`ReActAgent`) managing single-turn and multi-step reasoning cycles.

---

## 🎯 Purpose and Types

1. **`ReActAgent`**:
   - `def run(prompt: String, history: Chunk[Msg] = Chunk.empty): ZIO[Any, AgentError, AgentResult]`
   - `def streamEvents(prompt: String, history: Chunk[Msg] = Chunk.empty): ZStream[Any, AgentError, AgentEvent]`
2. **`AgentConfig`**:
   - `name: String`
   - `systemPrompt: String`
   - `maxIterations: Int = 20`
   - `timeout: Duration = 300.seconds`
   - `emptyThinkingRetryLimit: Int = 2`
3. **`AgentResult`**:
   - `reply: Msg`
   - `history: Chunk[Msg]`
   - `iterationsCount: Int`
   - `usage: TokenUsage`
4. **Tail-Recursive Reasoning Loop:**
   - Step 1: Format context history and invoke `LlmClient.stream`.
   - Step 2: Accumulate response deltas and emit typed `AgentEvent` values to stream.
   - Step 3: If model produces pure text (no `ToolUse`), finish loop and return response.
   - Step 4: If model produces thinking blocks but no text or tool calls, execute loopback retry with prompt hint.
   - Step 5: If model produces tool calls, dispatch executions via `Toolkit`.
   - Step 6: Append `ToolResult` blocks to dialogue history and recurse to Step 1.

---

## 🔍 References in AgentScope Java

* `agentscope-core/src/main/java/io/agentscope/core/agent/ReActAgent.java` (lines 350–700: execution loop, lines 1070–1250: streaming)
* `agentscope-core/.../ReActAgentInterruptToolCallTest.java` (Issue #2409: dangling tool calls on interruption)
* `agentscope-core/.../ReActAgentEmptyResponseRetryTest.java` (handling empty response after thinking)
* `agentscope-core/.../ReActAgentStructuredOutputTest.java` (dual-engine structured output)

---

## 🧪 Test Parity Map

| Upstream AgentScope Java Test | Target ZIO Spec | Verification Focus |
| :--- | :--- | :--- |
| `ReActAgentTest.java` | `ReActAgentSpec.scala` | Basic conversation, tool invocation turn, final assistant answer |
| `ReActAgentInterruptToolCallTest.java` | `ReActAgentInterruptSpec.scala` | Synthesizing `ToolResultBlock(INTERRUPTED)` when canceled during tool execution |
| `ReActAgentEmptyResponseRetryTest.java` | `ReActAgentThinkingRetrySpec.scala` | Automated loopback retry when reasoning models emit thinking without content |
| `ReActAgentStructuredOutputTest.java` | `StructuredOutputSpec.scala` | Synthetic `generate_response` tool injection when application tools are active |

---

## ⚠️ Critical Invariants
* **Dangling Tool Reconciliation:** In `ZIO.ensuring` (finalizer), any open assistant `ToolUse` blocks lacking matching results receive a synthetic `ToolResultBlock(INTERRUPTED)` to prevent subsequent 400 Bad Request errors.
* **Zero Fiber Leaks:** Agent cancellation propagates deterministically to all child fibers and in-flight tool tasks.

---

## 📋 Implementation Checklist
- [ ] Config `AgentConfig` and result `AgentResult`
- [ ] State machine for tail-recursive ReAct loop
- [ ] Cancellation finalizer with dangling tool reconciliation
- [ ] Unit tests: `ReActAgentSpec.scala`
- [ ] Interruption tests: `ReActAgentInterruptSpec.scala`
- [ ] Thinking-retry tests: `ReActAgentThinkingRetrySpec.scala`
- [ ] Structured output tests: `StructuredOutputSpec.scala`

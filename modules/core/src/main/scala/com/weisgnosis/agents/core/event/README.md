# com.weisgnosis.agents.core.event

Typed event package representing all state and delta transitions across an agent's reasoning loop for real-time Server-Sent Events (SSE) streaming.

---

## 🎯 Purpose and Types

A complete sealed hierarchy of **31 typed events** (`AgentEvent`):

1. **Agent Lifecycle:**
   - `AgentStartEvent(sessionId: String, replyId: String, agentId: String, source: Option[String] = None)`
   - `AgentEndEvent(replyId: String, source: Option[String] = None)`
   - `AgentProgressEvent(message: String, currentStep: Int, totalSteps: Option[Int] = None)`
2. **Text Streaming Deltas:**
   - `TextBlockStartEvent(blockIndex: Int)`
   - `TextBlockDeltaEvent(delta: String, blockIndex: Int)`
   - `TextBlockEndEvent(blockIndex: Int, fullText: String)`
3. **Model Reasoning & Thinking Mode:**
   - `ThinkingBlockStartEvent(blockIndex: Int)`
   - `ThinkingBlockDeltaEvent(delta: String, blockIndex: Int)`
   - `ThinkingBlockEndEvent(blockIndex: Int, fullThought: String)`
4. **Tool Calls:**
   - `ToolCallStartEvent(callId: String, name: String, blockIndex: Int)`
   - `ToolCallDeltaEvent(callId: String, delta: String, blockIndex: Int)`
   - `ToolCallEndEvent(callId: String, name: String, arguments: zio.json.ast.Json, blockIndex: Int)`
5. **Tool Results:**
   - `ToolResultStartEvent(callId: String, name: String)`
   - `ToolResultDeltaEvent(callId: String, delta: String)`
   - `ToolResultEndEvent(callId: String, name: String, output: String, state: ToolResultState)`
6. **Raw Model Invocations:**
   - `ModelRequestStartEvent(model: String, promptTokens: Int)`
   - `ModelResponseChunkEvent(chunk: String)`
   - `ModelResponseEndEvent(model: String, completionTokens: Int, totalTokens: Int)`
7. **Subagent Lifecycle:**
   - `SubagentSpawnEvent(parentSessionId: String, childSessionId: String, agentId: String, label: Option[String])`
   - `SubagentStartEvent(childSessionId: String, agentId: String)`
   - `SubagentEndEvent(childSessionId: String, agentId: String, status: String)`
   - `SubagentExposedEvent(subagentId: String, agentId: String, sessionId: String, label: Option[String])`
8. **Security & Human-in-the-Loop:**
   - `PermissionAskEvent(requestId: String, toolName: String, input: zio.json.ast.Json)`
   - `PermissionDecisionEvent(requestId: String, allowed: Boolean, reason: Option[String])`
9. **Context & Compaction:**
   - `CompactionStartEvent(triggerTokens: Int, messageCount: Int)`
   - `CompactionEndEvent(evictedTokens: Int, remainingMessages: Int)`
   - `HintBlockEvent(hint: String)`
10. **Errors & Custom Events:**
    - `ErrorEvent(message: String, code: Option[String] = None)`
    - `CustomEvent(kind: String, payload: zio.json.ast.Json)`

Every event carries `source: Option[String] = None` and provides `withSource(s: String): AgentEvent`. Subagent events are tagged hierarchically: `source = parentSessionId/childAgentId`.

---

## 🔍 References in AgentScope Java

* `agentscope-core/src/main/java/io/agentscope/core/hook/AgentStartEvent.java`
* `agentscope-core/src/main/java/io/agentscope/core/hook/TextBlockDeltaEvent.java`
* `agentscope-core/src/main/java/io/agentscope/core/hook/ToolCallStartEvent.java`
* `agentscope-core/src/main/java/io/agentscope/core/hook/ToolResultEndEvent.java`
* `agentscope-core/src/main/java/io/agentscope/core/hook/EventBus.java`

---

## 🧪 Test Parity Map

| Upstream AgentScope Java Test | Target ZIO Spec | Verification Focus |
| :--- | :--- | :--- |
| `EventBusTest.java` | `AgentEventSerdeSpec.scala` | JSON serialization/deserialization for all 31 events with discriminator |
| `HookTest.java` | `AgentEventStreamingSpec.scala` | Event stream ordering, source tagging, and subscriber isolation |

---

## ⚠️ Critical Invariants
* Deterministic JSON representation via `zio-json` tagged by discriminator field `"event"`.
* All events are strictly immutable case classes.

---

## 📋 Implementation Checklist
- [ ] Sealed trait `AgentEvent` with 31 cases
- [ ] `zio-json` codecs with discriminator
- [ ] `AgentEventSerdeSpec.scala`
- [ ] `AgentEventStreamingSpec.scala`

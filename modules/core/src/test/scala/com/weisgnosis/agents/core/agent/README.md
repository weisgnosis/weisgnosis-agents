# com.weisgnosis.agents.core.agent (Tests)

ZIO Test parity suite for `com.weisgnosis.agents.core.agent`.

---

## 🧪 Planned Test Specs

1. **`ReActAgentSpec.scala`** (corresponds to `ReActAgentTest.java`):
   - Multi-step reasoning loops with multiple tool invocations.
   - Reaching maximum iteration limit (`maxIterations`).
   - Timeout handling with typed error `AgentError.Timeout`.

2. **`ReActAgentInterruptSpec.scala`** (corresponds to `ReActAgentInterruptToolCallTest.java`):
   - Fiber interruption during active tool execution.
   - Verification that dialogue history receives synthetic `ToolResultBlock(INTERRUPTED)`.
   - Ensuring subsequent model calls succeed without invalid dialogue structure errors.

3. **`ReActAgentThinkingRetrySpec.scala`** (corresponds to `ReActAgentEmptyResponseRetryTest.java`):
   - Simulating reasoning model output with thinking blocks but empty text and tool calls.
   - Verifying automated loopback reminder injection up to retry limit.
   - Returning clean error when retry limit is exceeded.

4. **`StructuredOutputSpec.scala`** (corresponds to `ReActAgentStructuredOutputTest.java`):
   - Schema enforcement via native `response_format` when no tools are present.
   - Dynamic injection of synthetic `generate_response` tool when tools are active.

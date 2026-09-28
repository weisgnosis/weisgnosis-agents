# Agent Instructions for `weisgnosis-agents`

**Status:** Canonical instruction page for AI/code agents working in this repository.  
**License:** Apache-2.0  
**Stack:** Scala 3.8.4+, ZIO 2.1.26+, ZIO Schema, ZIO JSON, ZIO HTTP  

---

## 1. Core Engineering Principles

Every contribution and modification to this repository must follow strict **Pure Functional Programming (Pure FP)** and **ZIO 2** best practices:

### 🚫 FORBIDDEN IN ALL CODE (Production & Tests):
1. **NO `var`** — All state must be immutable. Use `Ref`, `STM`, or tail recursion.
2. **NO `throw`** — Never throw exceptions. Use typed errors in the ZIO error channel: `ZIO[R, E, A]`.
3. **NO `null`** — Never use or return `null`. Use `Option[A]`.
4. **NO `while` or procedural loops** — Use tail recursion (`@tailrec`), `ZIO.iterate`, `ZStream.unfoldZIO`, or combinators.
5. **NO `Thread.sleep()`** — Use `ZIO.sleep()` instead.
6. **NO `Future` or `runToFuture` / `unsafeRunSync`** — Stay completely inside the ZIO effect system.
7. **NO mutable builders or getters/setters** — Use Scala 3 `case class`, `copy`, and smart constructors.

### ✅ MANDATORY PRACTICES:
1. **Explicit Return Types:** All public methods, traits, and exported vals must have explicit return types.
2. **Structured Concurrency:** Use `ZIO.scoped`, `forkScoped`, and `ZStream` for background tasks so cancellation is 100% leak-free.
3. **ZIO Test First:** All tests must extend `ZIOSpecDefault`. Use `TestClock.adjust` for delays and retries instead of real-time sleeps.
4. **Typed Error Separation:** Separate recoverable domain errors (`AgentError`) from unexpected defects (`Die`).
5. **Clean ADTs:** Use Scala 3 `enum` with `derives JsonCodec` for all wire formats and domain models.

---

## 2. Behavioral Parity Safeguards (Lessons from AgentScope 2.0)

When implementing the agent runtime, the following critical edge-case protections **must never be bypassed or weakened**:

1. **Interrupted Tool Call Reconciliation (Issue #2409):**  
   If an agent execution fiber is interrupted while an assistant message contains a `ToolUseBlock` that has not yet been executed, the engine **MUST** append a synthetic `ToolResultBlock(state = ToolResultState.INTERRUPTED)` before terminating. This prevents downstream LLM APIs (OpenAI, Anthropic) from rejecting the next turn with HTTP 400.

2. **Empty Response / Thinking-Only Guard:**  
   If a reasoning model (DeepSeek-R1, o1, Claude Thinking) returns only `ThinkingBlock`s with no visible text and no tool calls, the ReAct loop **MUST NOT** terminate with an empty message. It must loop back (bounded by `maxIters`) with a synthetic reminder prompt prompting for the final user response.

3. **Server-Side Tool Distinctions:**  
   Tools executed on the provider's infrastructure (e.g. Anthropic Web Search, Gemini Code Execution) arrive pre-resolved in the response. They carry `isServerTool = true` and **MUST NOT** be dispatched to the local `Toolkit`.

4. **Context Compaction Deduplication:**  
   When the context compactor summarizes conversation history, any prior summary messages in the prefix must be consolidated into a single flat summary rather than creating nested summaries of summaries.

---

## 3. Module Boundaries and Dependencies

* **`weisgnosis-agents-core`**:
  * Depends ONLY on `zio`, `zio-streams`, `zio-json`, `zio-schema`, `zio-http`.
  * Must remain completely decoupled from databases, file paths, and external business models.
* **`weisgnosis-agents-harness`**:
  * Depends on `core`.
  * Contains `ConversationCompactor`, `ToolResultEvictor`, `PermissionGate`, and `FiberSubagents`.
* **`weisgnosis-agents-mcp`**:
  * Depends on `core`.
  * Implements Model Context Protocol client over Stdio (`ZProcess`) and SSE.

---

## 4. Verification Workflow

Before committing any changes:
1. Format code: `sbt scalafmtCheckAll`
2. Compile with fatal warnings: `sbt compile` (must compile with 0 warnings)
3. Run all tests: `sbt test` (must pass 100% deterministically)

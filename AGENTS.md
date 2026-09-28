# Engineering Standards & Agent Directives for `weisgnosis-agents`

> **Document Status:** Canonical Engineering Standard and AI Agent Operational Directive  
> **Repository:** `weisgnosis-agents`  
> **Organization:** `com.weisgnosis`  
> **License:** Apache-2.0  
> **Target Platform:** Scala 3.9.0 LTS, ZIO 2.1.26+, zio-json 0.9.1+, zio-http 3.11.3+  

---

## 🏛️ 1. The Core Manifesto: Uncompromising Pure FP & ZIO 2 Craftsmanship

`weisgnosis-agents` is designed to be an open-source reference library for the global Scala community. Mediocrity, lazy imperative shortcuts, and legacy Java habits are strictly prohibited.

Every line of code committed to this repository must exemplify **flawless, elegant, highly readable, and mathematically sound Pure Functional Programming (Pure FP)** powered by **Scala 3** and **ZIO 2**:

1. **Total Referentially Transparent Effects:**  
   Every computation that interacts with the real world (network, time, random numbers, process execution, console, disk) must be suspended as a pure, lazy value in `ZIO[R, E, A]`. No side-effect may ever execute outside the ZIO effect runtime.

2. **Elegance, Clarity, and Readability Over Obfuscation:**  
   We prioritize clean, descriptive, readable domain abstractions over cryptic one-liner cleverness. Code must read like a well-crafted specification: expressive naming, single-responsibility functions, and explicit intent.

3. **Type-Driven Domain Modeling:**  
   Make illegal states unrepresentable. Leverage Scala 3's modern type system—sealed traits, ADTs, enums, opaque types, union types, and typeclasses via `given`/`using`—to eliminate runtime bugs at compile time.

---

## 🚫 2. Absolute Prohibitions (Zero-Compromise Pure FP Rules)

The following constructs and idioms are **strictly forbidden** in all production and test code. Any pull request containing them will be rejected:

| Forbidden Idiom | Rationale & Pure FP Alternative |
| :--- | :--- |
| **NO `var`** | Mutable variables introduce race conditions and destroy referential transparency. Use immutable `val`, `zio.Ref`, `zio.stm.STM`, or tail recursion. |
| **NO `throw`** | Exceptions break the type system and escape control flow. All failures must be represented as typed values in the ZIO error channel: `ZIO[R, AgentError, A]`. |
| **NO `null`** | `null` is a billion-dollar mistake. Use `Option[A]`, `Chunk.empty`, or domain-specific ADT variants. |
| **NO `while` or procedural loops** | Procedural loops rely on local mutation. Use tail-recursive functions (`@annotation.tailrec`), `ZIO.iterate`, `ZStream.unfoldZIO`, or standard functional combinators (`map`, `foldLeft`, `traverse`). |
| **NO mutable collections** | Packages under `scala.collection.mutable.*` or Java collections (`java.util.*`) are prohibited. Use `zio.Chunk`, standard immutable `List`, `Vector`, `Map`, and `Set`. |
| **NO `Future`, `Await.result`, or `unsafeRunSync`** | Never break out of the ZIO effect boundary or block threads. Use ZIO structured concurrency (`ZIO.scoped`, `forkScoped`, `fiber.join`). |
| **NO `Thread.sleep()`** | Blocking OS threads starves the execution pool. Use non-blocking `ZIO.sleep()` and ZIO Test's `TestClock.adjust()`. |
| **NO partial functions (`.head`, `.get`, `asInstanceOf`)** | Calling `.get` on `Option`, `.head` on an empty collection, or unverified `asInstanceOf` causes runtime crashes. Use pattern matching, `headOption`, or total combinators. |
| **NO `zio-schema` reflection overhead** | Codecs and JSON schemas must use fast, compile-time `zio-json` and `zio.json.ast.Json` to maximize performance and minimize binary bloat. |

---

## 💎 3. Mandatory Engineering Best Practices

### 3.1. Idiomatic Scala 3 & ZIO 2 Style
- **Explicit Return Types:** All public and package-private defs, vals, and traits must declare explicit return types.
- **Pure Effect Boundaries:** Functions that return effects must be named clearly without `get...` or imperative prefixes (e.g. prefer `def resolve(id: String): ZIO[...]` over `def getAndResolve(...)`).
- **Resource Safety with `Scope`:** All acquire/release resources (child processes, sockets, streams) must be acquired via `ZIO.acquireRelease` or scoped combinators within `ZIO[Scope, E, A]`.
- **Structured Concurrency:** Subagent fibers, parallel tool executions, and stream readers must be managed via scoped fibers (`forkScoped`). Canceling a parent fiber must deterministically terminate all descendants without fiber leaks.
- **Modular Layers:** Components must declare their dependencies through constructor arguments or `ZLayer`, facilitating modular testing and substitution.

### 3.2. Error Handling & Normalization
- All domain errors must inherit from the sealed hierarchy `AgentError`:
  ```scala
  sealed trait AgentError extends Throwable:
    def message: String
    override def getMessage: String = message
  ```
- **Tool Error Normalization:** A tool execution failure (e.g. shell exit code non-zero, API error, syntax failure) is a valid *observation* for the cognitive model, **not** a fatal failure of the ReAct engine. Tool executions must return `ToolResultState.Error` with descriptive output for the model rather than failing the outer ZIO fiber.

---

## 🛡️ 4. Battle-Tested Production Invariants (Lessons from AgentScope)

When implementing agentic mechanics, the following 12 production invariants must be preserved:

1. **Dangling Tool Call Reconciliation (INV-2):**  
   Every assistant `ToolUse` block in conversation history must have a matching `ToolResult` block. On fiber interruption (`ZIO.uninterruptibleMask`), inspect turn progress in `Ref[Chunk[Msg]]`: synthesize `ToolResultState.Interrupted` *only for in-flight tool calls*, preserve completed tool results, and append a conversational recovery turn.
2. **Thinking-Only Loopback Guard (INV-3):**  
   If a reasoning model produces thinking tokens without user-facing text or tool calls, inject a synthetic `<system-reminder>` message (`role = Role.User, name = Some("system")`) to prompt for the final answer, bounded by `emptyThinkingRetryLimit`.
3. **Atomic Turn Pairing in Compactor (INV-4):**  
   History compaction must never sever an assistant tool call from its subsequent tool result. The cutoff boundary must shift backward to preserve turn pairs, with a forward fallback for orphaned results.
4. **Consecutive User Message Coalescing:**  
   Prepending a summary message (`Role.User`) before a retained tail that begins with `Role.User` must be coalesced into a single unified `User` message to avoid provider turn-alternation rejections.
5. **Two-Stage Context Pruning (INV-7):**  
   Large tool outputs (>80,000 chars) are immediately offloaded to workspace files (`large_tool_results/`) with 2k head / 2k tail previews and `read_file` instructions. Older tool results (>2,000 chars) and large arguments are pruned during compaction before LLM summarization.
6. **Two-Layer Memory Isolation (INV-5):**  
   Daily scratch flushes write strictly to `memory/YYYY-MM-DD.md`. The memory consolidator is the sole writer to curated `MEMORY.md` within a 4,000-token budget.
7. **Dual-Mode Human-in-the-Loop (INV-6):**  
   Support both interactive in-memory suspension via `zio.Promise[Nothing, ConfirmResult]` and durable multi-turn suspension for workflow engines (Temporal Activities), resuming with `ConfirmResult` without re-invoking the model.
8. **Subagent Supervision & Task Promotion (INV-8):**  
   Enforce `MAX_SPAWN_DEPTH = 3`. Subagents promoted after synchronous timeouts (30s) must transition to a persistent `TaskSupervisor` scope so that completing the parent turn does not terminate the background task.
9. **Plan Mode Shell Hardening (INV-9):**  
   Plan Mode disallows mutating tools. If shell execution is permitted for exploration, enforce static command inspection to reject file redirection (`>`, `>>`) and mutating binaries (`rm`, `sed -i`, `mv`).
10. **Sensitive Path Canonicalization (INV-10):**  
    All path operations must resolve canonical paths via `toRealPath()` to prevent directory traversal and symlink escapes to sensitive files (`.ssh`, `.env`, `.git`).
11. **Structured Output Dual-Engine (INV-11):**  
    Provide `runStructured[A: JsonCodec]`. Toggle between native `response_format` and synthetic `generate_response` with root `$defs` hoisting and context compression.
12. **Streaming Chunk Index Assembly (INV-12):**  
    Parallel tool call argument fragments must be keyed and grouped by `streamIndex` in `StreamAccumulator`, and sanitized to `{}` if interrupted mid-stream.

---

## 🌍 5. Global Repository Standards

1. **100% English Only:**  
   All code, docstrings, identifiers, comments, documentation, commit messages, and PR descriptions must be written strictly in professional English. No other languages are permitted in repository files.
2. **Vendor-Neutral Terminology:**  
   Do not couple interfaces or docs to specific model brands or commercial entities. Use generic, protocol-level abstractions: *reasoning models*, *thinking tokens*, *provider-hosted server tools*, *OpenAI-compatible SSE endpoints*.
3. **Repository Namespace:**  
   All packages belong strictly under `com.weisgnosis.agents.*`.
4. **Test Parity First:**  
   Every domain component, parser, and state machine must have a 1:1 paired test suite in `src/test/scala` extending `ZIOSpecDefault`, maintaining parity with upstream test coverage.

---

## ⚙️ 6. Pre-Commit Verification Workflow

Before committing code or opening a PR, ensure the following commands pass with zero warnings and zero failures:

```bash
# 1. Format code according to .scalafmt.conf
sbt scalafmtAll scalafmtSbt

# 2. Compile with fatal warnings (-Werror, -Xfatal-warnings enabled)
sbt compile

# 3. Run all unit and integration test specs deterministically
sbt test
```

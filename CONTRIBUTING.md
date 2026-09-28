# Contributing to `weisgnosis-agents`

We welcome contributions from the community! This project is licensed under **Apache-2.0**.

---

## 🏛️ Engineering Standard: Uncompromising Pure FP & ZIO 2

`weisgnosis-agents` is designed to be an open-source reference library for modern Scala 3 and ZIO 2. All contributions must adhere strictly to the engineering rules detailed in **[AGENTS.md](AGENTS.md)**:

- **Strict Pure FP:** Zero `var`, `throw`, `null`, `while`, or mutable collections. All effects must be suspended in `ZIO[R, E, A]`.
- **ZIO 2 Concurrency:** Non-blocking structured concurrency (`ZIO.scoped`, `forkScoped`). No `Thread.sleep` or `Future`.
- **Explicit Return Types:** Required on all public methods and traits.
- **Fast Compile-Time JSON:** Use `zio-json` exclusively (no reflection or `zio-schema` overhead).
- **100% English Only:** All code, identifiers, comments, documentation, and commit messages must be in English.
- **Vendor-Neutral Abstractions:** Protocol-level abstractions for model clients and reasoning streams.

---

## Development Workflow

1. **Prerequisites:**
   - JDK 21
   - SBT 1.10+
   - Git

2. **Branching & Commits:**
   - Create a feature branch: `git checkout -b feature/my-feature`
   - Keep commits atomic and scoped.
   - Use conventional commit messages: `feat:`, `fix:`, `docs:`, `test:`, `refactor:`.

3. **Code Style & Formatting:**
   - Format all code with scalafmt before opening a pull request:
     ```bash
     sbt scalafmtAll scalafmtSbt
     ```
   - Compile with fatal compiler warnings enabled:
     ```bash
     sbt compile
     ```

4. **Testing:**
   - Write deterministic tests extending `ZIOSpecDefault`.
   - Never use real-time sleeps; use `TestClock.adjust`.
   - Ensure 1:1 test parity with upstream AgentScope test suites.
   - Run tests:
     ```bash
     sbt test
     ```

5. **Pull Requests:**
   - Open a PR against `main`.
   - Verify CI passes with 0 compiler warnings (`-Xfatal-warnings` enabled).

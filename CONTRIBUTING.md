# Contributing to `weisgnosis-agents`

We welcome contributions from the community! This project is licensed under Apache-2.0.

## Development Workflow

1. **Prerequisites:**
   - JDK 17 or higher (JDK 21 recommended)
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
   - All code must adhere to the rules in [agents.md](agents.md):
     - Pure FP: no `var`, no `throw`, no `while`, no `null`.
     - Explicit return types on public APIs.
     - Strict typed error handling (`ZIO[R, E, A]`).

4. **Testing:**
   - Write deterministic tests extending `ZIOSpecDefault`.
   - Never use real-time sleeps; use `TestClock.adjust`.
   - Run tests:
     ```bash
     sbt test
     ```

5. **Pull Requests:**
   - Open a PR against `main`.
   - Ensure CI passes with 0 compiler warnings (`-Xfatal-warnings` enabled).

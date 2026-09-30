# Engineering standards and agent directives

Repository: weisgnosis-agents. Namespace: com.weisgnosis.agents. License: Apache-2.0. Target: Scala 3.9.0, ZIO 2.1.26+, zio-json 0.9.1+, and zio-http 3.11.3+.

This is the canonical engineering standard. Pure functional correctness, readability, and disciplined ZIO lifecycle design take priority over shortcuts.

## 1. Pure functional boundaries

All interaction with the real world must be suspended in ZIO: network, time, randomness, processes, console, filesystem, and resource acquisition/release. Constructors, codecs, defaults, and pure-looking accessors must never execute these effects eagerly.

Use descriptive domain abstractions and single-responsibility functions. Model illegal states out of existence with Scala 3 enums, sealed ADTs, opaque types, and typed errors. Runtime state and completion remain deterministic authorities; model output is untrusted input.

## 2. Prohibited constructs

The following rules apply to production and test code.

| Prohibited | Required alternative |
| --- | --- |
| var | Immutable val, Ref, STM, or tail recursion |
| throw or throwing validation | Typed AgentError failures |
| null | Option, empty Chunk, or explicit ADT case |
| return, while, procedural loops | Total functional combinators or tail recursion |
| Mutable Scala or Java collections | Chunk or immutable List, Vector, Map, Set |
| Future, Await.result, unsafeRunSync | ZIO structured concurrency |
| Thread.sleep | ZIO.sleep and deterministic TestClock |
| Partial Option.get, collection.head, asInstanceOf | Pattern matching, headOption, and total combinators |
| zio-schema reflection | Compile-time zio-json codecs and JSON AST |

Ref.get and Map.get(key) are total operations and are not the unsafe Option.get prohibition. Do not replace unsafe operations with hidden defects or unchecked assumptions.

## 3. Mandatory practices

- Declare explicit result types on all public/package-private defs and vals; keep public contracts explicit.
- Use Scala 3 significant indentation and avoid unnecessary braces.
- Prefer derives JsonCodec; manual codecs require wire validation, compatibility, or AST conversion reasons.
- Model execution modes and mutually exclusive states with ADTs rather than independent Boolean flags.
- Use descriptive effect names, avoiding misleading get-style pure accessors for real-world work.
- Use idiomatic ZIO duration literals such as 5.minutes.
- Declare dependencies through constructor arguments or ZLayer.
- Acquire resources with acquireRelease or scoped combinators. Each run and model request owns a bounded scope.
- Manage asynchronous children through structured concurrency and scoped fibers such as forkScoped. Cancellation must finish owned resource/child cleanup before returning. Promoted background tasks require explicit supervisor ownership.
- Never swallow cancellation as a successful observation.
- Keep transformations total and preserve existential tool input/output types without casts.

Domain failures inherit from sealed AgentError, which extends Throwable and exposes a message. External adapters may receive Throwable failures at their boundary; translate them to domain failures or tool observations.

A tool failure is a valid model observation, not a fatal ReAct error. Normalize execution failures and defects as descriptive ToolResultState.Error observations while preserving interruption.

## 4. Canonical invariants

These IDs follow the Phase 3/4 handover. Requirements for planned systems do not imply implemented behavior; the tracker records evidence and remaining work.

| ID | Required contract |
| --- | --- |
| INV-1 | Persist assistant tool calls before local execution starts. |
| INV-2 | Reconcile only unfinished local calls with Error observations; preserve completed results and append one recovery assistant for the interrupted run: "I noticed that you have interrupted me. What can I do for you?" with agentscope_generate_reason=interrupted. Inline provider results do not need separate Tool messages. |
| INV-3 | Thinking-only output with blank visible content and no calls triggers a bounded synthetic user reminder named system containing `<system-reminder>`, with agentscope_synthetic=true and agentscope_reminder_kind=empty_response. |
| INV-4 | Compaction preserves call/result pairs, including repeated IDs by occurrence; shift the cutoff backward to preserve pairs, with a forward fallback for orphan results that cannot begin the retained tail. |
| INV-5 | Replace prior summaries with one merged summary. Retained raw user provenance survives coalescing for future flushing. |
| INV-6 | Offload tool text above 80,000 characters with 2,000-character head/tail previews. Persistence failure preserves the original observation. Historical pruning has a separate 2,000-character threshold. |
| INV-7 | Independent permission gates combine as Deny > Ask > Passthrough > Allow. Unknown requests default to Ask. |
| INV-8 | Planned subagents enforce depth <=3; promotion after a 30-second synchronous timeout transfers ownership to a persistent TaskSupervisor. |
| INV-9 | Plan mode rejects mutating tools and statically inspects permitted exploration commands, rejecting redirection (>, >>) and mutations such as rm, mv, and sed -i. |
| INV-10 | Reject parent traversal before normalization; canonicalize existing ancestors through toRealPath and screen sensitive paths, including .env variants, shell startup files, .ssh, and .git. Canonical checks are not an atomic filesystem sandbox. Local persistence requires explicit ownership/trust. |
| INV-11 | Support native formatting and synthetic generate_response; preserve root definition namespaces and compress only synthetic context. |
| INV-12 | Group streaming arguments by streamIndex; sanitize incomplete interrupted JSON arguments to {}. |

Additional requirements:

- Coalesce adjacent user turns without losing provenance.
- Daily memory flushes write memory/YYYY-MM-DD.md only. Only the consolidator writes curated MEMORY.md within 4,000 tokens.
- Interactive HITL uses Promise. Durable hosts persist pending state and resume from ConfirmResult without another model request; authentication, checkpoints, and idempotency belong to the host.
- Server-hosted tools with inline results bypass local dispatch.
- Interrupted recovery preserves the agentscope_generate_reason=interrupted compatibility metadata.

## 5. Repository standards

All code, identifiers, comments, documentation, commit messages, and PR descriptions must be professional English. Use vendor-neutral protocol abstractions; preserve upstream names only for attribution, wire compatibility, and traceability.

Production/test packages belong under com.weisgnosis.agents. Every domain component, parser, and state machine requires a paired ZIOSpecDefault suite. The target is coverage of every applicable upstream behavior; record the method-level mapping, intentional differences, and justified exclusions. Do not claim exhaustive parity merely because suites pass.

Keep tests proportional to independent contracts and reproduced defects. Prefer concise table-driven cases and shared fixtures over repeated setup, accessor checks, or mechanically duplicated scenarios. Preserve meaningful integration and cancellation coverage; raw test counts and line counts are not acceptance targets.

## 6. Verification

Before committing or opening a PR:

```text
sbt scalafmtAll scalafmtSbt
sbt compile
sbt test
```

The sbt verify alias checks scalafmtCheckAll, scalafmtSbtCheck, compile, and test. Compilation uses -Werror with deprecation, feature, unchecked, and unused warnings. All required checks must pass with zero compiler warnings and zero failed tests.

Automated checks do not prove referential transparency, lifecycle safety, English prose, or exhaustive parity.

For a confirmed defect, derive regression expectations from the public contract, not from the implementation's current output. Run the regression against the unchanged defective implementation and record the specific assertion failure before fixing it. For command classifiers, pair rejection cases with permitted near-misses and an actual forbidden executable so that always-allow and always-deny implementations both fail. Keep fixtures as data; never execute destructive commands to test a pure command classifier. A user-requested failing-test checkpoint remains explicitly unaccepted and uncommitted until the fix, independent review, and required verification pass.

## 7. Independent review and documentation gates

Behavioral changes require independent FP/ZIO and invariant-focused reviews. Reviewers provide concrete source/test evidence. Resolve findings, add meaningful regressions, and review the final diff. Keep findings open until they are corrected and independent reviewers explicitly approve the final changes. Record the current accepted scope, reviewer identities, reviewed revision, and verification in the affected README.md files. Do not accept unfinished work or waive findings to close a review.

Before declaring completion, update affected root/module/package documentation, API contracts, tracker, test mappings, and migration notes. Explicitly label roadmap designs and host integration requirements. Prefer source links over copied implementations.

Inspect changed comments and prose for English. A Unicode script scan is supplemental evidence, not natural-language proof. Check local Markdown links.

See [CONTRIBUTING.md](CONTRIBUTING.md), the PR template, the [tracker](docs/architecture/tracker.md), and the [parity audit](docs/architecture/test-parity.md).

## 8. Living README and review closure protocol

Every maintained module, source/test package, documentation directory, and build/configuration directory must contain an English README.md. Keep it current whenever its contents or contracts change. Generated directories and empty structural ancestors do not need placeholder documentation.

The completion sequence is mandatory:

1. Implement the defined scope and update its tests and contracts.
2. Obtain independent subagent reviews of FP/ZIO correctness and relevant invariants.
3. Correct every finding, add meaningful regression coverage, and obtain explicit approval of the final diff. Repeat until no findings remain in the accepted scope.
4. Update affected README.md files with current behavior, exact accepted scope, reviewer identities, reviewed revision, and verification evidence. Approval applies only to that scope and revision; changed behavior requires renewed review.
5. Keep outstanding roadmap or parity work visible in the tracker and relevant README. Never label planned or unreviewed work as accepted reference implementation.
6. Delete resolved temporary review reports and repair their incoming links. Git history preserves past findings; the active tree contains current contracts and acceptance status, not closed review journals.
7. Run the required verification, check documentation links and English prose, and commit the code, README updates, and report removal. No step is complete until its evidence exists.

A reference implementation is accepted only within the explicitly reviewed scope, with no unresolved findings. Acceptance is not a claim that every future feature or exhaustive upstream parity is already implemented. Prefer updating an existing current document over adding another status report; remove superseded Markdown documents after preserving their still-applicable contracts.

Documentation describes current APIs, limitations, next work, and current review evidence. Replace outdated descriptions and acceptance entries instead of appending milestone narratives or historical test totals; Git retains that history.

## 9. Visible progress tracking

Every tracker, roadmap checklist, and parity task list must use Markdown task checkboxes: `- [x]` for verified completion and `- [ ]` for unfinished work. A prose paragraph or status table alone is not a progress tracker. Split partially completed work into concrete completed and remaining items; never check an entire phase because only some components exist. State the next task explicitly and update affected checkboxes with every delivery. Review acceptance checkboxes require actual final reviewer approval for the named scope.

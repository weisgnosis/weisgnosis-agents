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

## 5. Scope and tests

All code, identifiers, comments, documentation, commit messages and PR descriptions must be professional English. Production and test packages belong under com.weisgnosis.agents. Preserve upstream names for attribution, wire compatibility and traceability only.

The unit of delivery is a promised observable behavior, demonstrated through the public API. Before implementation, identify its expected result, relevant invariants and a proportionate way to verify the complete path. Reuse existing components rather than creating another abstraction for the same responsibility.

Cover independent contracts and reproduced defects. Use concise table-driven cases and shared fixtures; a new class does not automatically require another test file. Preserve meaningful parser, state-machine, integration, failure and cancellation coverage. Do not delete useful tests merely to reduce their count. Raw test totals, source lines and upstream declaration counts are not progress or acceptance targets.

Map promised upstream behavior to executable scenarios and explain intentional API adaptations. An unclassified Java declaration is not by itself a missing Scala feature. Neither a passing suite nor a documented difference proves exhaustive parity or authorizes removing a promised capability.

## 6. Verification proportional to the change

For production or test changes, run focused checks while developing, then before commit:

```text
sbt scalafmtAll scalafmtSbt
sbt verify
```

The verify alias checks formatting, compilation and tests with fatal deprecation, feature, unchecked and unused warnings. All required checks must pass with zero compiler warnings and zero failed tests. Run one sbt process at a time on this host.

Run package/archive and fresh external-consumer checks when public APIs, dependencies, build configuration or integration behavior change, and for the release candidate. Actually run affected PostgreSQL, subprocess or HTTP scenarios when those boundaries change; skipped tests are not successful integration evidence. Real-provider checks are explicitly enabled and use process-local credentials, never source-code secrets.

Documentation-only changes require English/API/example accuracy and local-link checks. Recompile an executable example if its code or dependencies change. Do not rerun the entire runtime suite solely to approve prose, checkmarks or a correction to review status. Repeat checks only after relevant changes, failures or unresolved concerns.

For a confirmed defect, derive the regression from the public contract and run it against unchanged defective production before fixing it. Record the command and specific assertion failure in the review/PR or task conversation; compilation failures and invalid fixtures are not behavioral RED evidence. Separate new capability work from reproduced defects. Command-classifier cases must include forbidden commands and permitted near-misses; fixtures are data, never destructive commands to execute.

Automated checks do not prove referential transparency, lifecycle safety, exhaustive parity or production readiness.

## 7. Independent substantive review

Behavioral changes require independent FP/ZIO and invariant-focused review of the final code and relevant tests. Reviewers look for concrete counterexamples, hidden effects, ownership/cancellation failures, incorrect authority and gaps between components. They must inspect the actual public integration path where the change crosses a boundary.

Resolve findings and have the reviewers recheck affected corrections. Approval applies to the inspected change, not the whole repository. Never invent a review or close an unresolved behavioral finding. The coordinator remains responsible for scope, integration and the delivered result.

Keep a concise review outcome in the PR or task conversation: scope, actual reviewers, findings resolved, verification and remaining limits. Do not copy review transcripts, file-blob hashes, manifest checksums or approval ceremonies into READMEs. A factual status update does not require another review round unless it changes a contract or exposes a substantive issue. Documentation/formatting-only changes need accuracy review; changes to engineering rules or completion criteria receive independent review.

## 8. Documentation for users

The root README explains purpose, installation, a small working example and current limitations. Module/package documentation explains APIs, ownership, usage and migrations where useful. Update documents whose contracts actually change; do not require a placeholder README in every directory or a paired documentation edit for every source file.

Keep documentation current. Replace superseded descriptions, remove resolved temporary review reports and repair incoming links. Git/PR history and executable regressions retain past findings. Do not move closed review journals into a new documentation archive. Preserve still-applicable API, compatibility and security contracts when removing history. Existing review journals are legacy cleanup work, not a template for new deliveries.

Prefer links to source and runnable examples over copied implementations. State prerequisites, exact commands and observable outcomes. Distinguish compiled examples, offline integration, actual live runs and unsupported behavior. Keep credentials out of deliverables.

## 9. One finite completion checklist

The [tracker](docs/architecture/tracker.md) is the sole current completion checklist for this library. Use Markdown task checkboxes for completed and remaining observable outcomes, with source/spec/example references. Keep the detailed [parity reference](docs/architecture/test-parity.md) for traceability; its legacy checkboxes are not a second release backlog and cannot silently expand or waive scope. Reconcile any newly identified substantive obligation with the tracker.

Preserve the original promised library behavior. WeisGnosis OS deployment and Temporal product verification are separate tasks. New work must trace to a promised outcome, a reproduced defect or an explicit user request; do not add optional platforms or Java-only class shapes as implicit completion gates.

Keep one explicit next task. Split partial completion honestly and leave missing behavior unchecked. Check a scenario only when its expected result and required review/verification exist; remove completed delivery narratives rather than append milestones. Do not claim whole-project percentages without a fixed meaningful denominator.

Report completed working behavior, verification, remaining outcomes and concrete blockers briefly. Before spending another work cycle without a working result, identify the cause and reduce or revise the next step. Bound delegation to concrete source/review questions; avoid repeated broad audits and idle status polling. Report resource usage only from available measurements, never inferred subscription percentages or invented token totals.

See [CONTRIBUTING.md](CONTRIBUTING.md) and the PR template for the delivery workflow.

# Architecture implementation tracker

Last synchronized: 2026-09-30. Implementation is active. This document records current capabilities and remaining work; review history belongs in Git. Available components do not imply complete host integration or exhaustive upstream parity.

## Current acceptance

- [x] Current reviewed scope includes PathSafety screening, configured factory/static filtering and bounded registry/polling and model-facing task tools (`path_review`), plus live parent-denial inheritance and inherited plan admission (`current_docs`). The reviewers approved FP/ZIO correctness and relevant invariants at the exact source/test identities in the [tool contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#current-review-acceptance), [subagent contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#verification-and-acceptance) and [plan contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md).
- [x] Full `scalafmtAll scalafmtSbt verify` passed on 2026-09-30: 561 core / 626 harness tests (1,187 total), zero compiler warnings or failures. Local evidence: `subagent-tools-plan-final-verify.log`.
- [x] Pin upstream reference to `f98a685fdc4589f81e626e1686ac2c63d14a2304`; maintain method mappings and explicit exclusions in the [parity audit](test-parity.md).
- [x] Close the reproduced PathSafety recursive-command and argument-substring defects with lexical warning semantics and bounded regressions; this helper is not shell parsing or execution authorization.
- [x] Verify configured child construction, monotone final tool filtering, preserved authorizers and scoped resource cleanup; map the two ManagedSubagentBoundary methods as bounded static adaptations.
- [x] Verify bounded registry admission, full-Exit inspection, retention, private caller leases and joined shutdown, plus live parent-denial checks around child authorization without copying approval authority.

## Available capabilities

### Core

- [x] Immutable messages, typed errors, compile-time JSON codecs, 39 event variants, typed tools and immutable registration.
- [x] ReAct execution with persisted local calls, occurrence-aware results, interruption recovery, bounded thinking-only retries, native/synthetic structured output and scoped authorization.
- [x] Scripted clients and scoped text/function HTTP completion, strict SSE parsing, indexed argument assembly and correlated incremental events; dispatch requires validated stream completion.
- [x] Scoped result processing, history preparation, request-only projection, completion observers and typed generation reasons.
- [x] Parent-traversal rejection and canonical path screening. Portable filesystem storage requires explicit host ownership; advisory lexical command screening does not authorize execution.

### Harness

- [x] Pair-safe compaction, merged summaries, retained user provenance, token estimation and opt-in local/inline result eviction with 80,000-character offloading and 2,000-character previews.
- [x] Immutable permission priorities, interactive Promise-based confirmation, current-policy/input rechecks and serializable durable approval data.
- [x] Daily-memory extraction, in-memory and cooperative local stores, budgeted consolidation and recoverable local publication. Daily writers target `memory/YYYY-MM-DD.md`; only the consolidator writes curated `MEMORY.md` within 4,000 configured-counter tokens.
- [x] Typed transcripts, opt-in completion recording, shared monotonic triggers, bounded scoped task queues, pending conversation coalescing and completion-triggered flush.
- [x] Configured maintenance with acknowledged daily archival, explicit ordered transcript pruning, calendar retention, immutable budgets and literal prompt templates.
- [x] Conservative static plan inspection and opt-in live admission; exact-draft in-memory review, request-only context, scoped stored publication, strict model-facing plan controls and validated workspace-relative directories. Reopened drafts do not restore approval.
- [x] Scoped task supervision, cancellation leases and same-execution promotion after a 30-second wait per `awaitOrPromote` invocation. Runtime-owned lineage admits descendants through depth three and rejects depth four before identity allocation or callbacks.

- [x] Configured factories build fresh children with final static filtering after inherited and automatic registration, deterministic assembly diagnostics, preserved authorizers and supervisor-owned execution.

- [x] Bounded task registry with lookup, waiting/promotion, cancellation and completed-record removal; registry closure cancels and joins promoted work. Capacity bounds records, not retained result bytes.
- [x] Optional live parent-denial inheritance preserves child policy and rereads parent rules against approved input; external parent gates and atomic post-decision revocation are excluded.

- [x] Opt-in model-facing `agent_spawn`, `agent_task` and `agent_cancel`, bound to one configured registry, with strict inputs, bounded result projection and joined cancellation.
- [x] Host-installed inherited plan gate: either parent or child Plan mode restricts execution, including confirmation rechecks, without copying approval state.

The host installs these capabilities explicitly. It owns background supervisor scopes, trusted filesystem ancestors, authenticated approval delivery, provider settings and operational monitoring. Durable approval values are not a durable resume implementation. Root distribution and retained raw spawner capabilities remain trusted-host responsibilities.

## Immediate next work

- [ ] Complete supervisor-owned persistent resource leases with final independent review and verification. This prerequisite is in progress and outside current acceptance.
- [ ] Build child sessions, persistent reuse and send on that ownership contract, preserving narrowed factories and current parent denial/plan restrictions for every invocation.
- [ ] Install inherited policy automatically for nested/reused children, then add correlated event routing with explicit lifetime and retention contracts.

The accepted factory, registry, task tools and plan adapter use host-selected process-local capabilities. They do not add declaration parsing, durable restart recovery or automatic inheritance through raw spawner callbacks. Hosts control capability distribution and completed-record retention.

## Remaining integration and parity

- [ ] Complete applicable core/harness method mappings and remaining structured-output, lifecycle and event-ordering gaps, including native structured/incremental local tool results, provider signature/tool metadata, event adapters, phase-stop integration and richer subagent attribution.
- [ ] Complete remaining plan adaptations, including skills and automatic declared/general-purpose/reused-child propagation; the host-installed inherited gate does not close those whole methods. Current plan inventory is eight adapted / six pending methods.
- [ ] Implement durable host persistence and authenticated, idempotent resumption from `ConfirmResult` without another model request.
- [ ] Complete model-selection configuration, remote memory/transcript adapters, timestamp-watermark migration, session discovery and scheduling.
- [ ] Validate platform-specific symlink/junction behavior and provide a secure storage backend if untrusted workspace support is required. Canonical checks are not an atomic filesystem sandbox; crash/fsync durability and hostile mutation isolation are not claimed.
- [ ] Implement MCP codecs, transports, discovery and tool bridging; the module currently contains scaffolding only.
- [ ] Validate live end-to-end cancellation, resource release and operational controls, confirm remote CI, and complete release/API compatibility and production acceptance.

## Verification and contracts

Run `sbt scalafmtAll scalafmtSbt` and `sbt verify` with JDK 21. Verification requires zero compiler warnings and failed tests. Each behavioral increment also requires independent FP/ZIO and invariant review, current package contracts and test mappings, English prose and valid local links under [AGENTS.md](../../AGENTS.md).

On the current Windows host, use one sbt process at a time. If normal JVM startup fails under commit pressure, the previously successful process-local settings were `JAVA_TOOL_OPTIONS=-Xms128m -Xmx768m -Xss2m -XX:ActiveProcessorCount=4` and sbt `--mem 768`; unavailable proxy settings previously required `-Dhttp.nonProxyHosts=*` and `-Dhttps.nonProxyHosts=*`. These are environment workarounds, not repository defaults.

TaskSupervisor lifecycle changes must preserve its parallel outer owner scope, sequential worker scopes and explicit worker-owner completion closure; direct `forkIn` into the parallel owner previously caused self-join on immediate completion. Tests cover full Exit observation and cancellation cleanup.

- [Core module and tests](../../modules/core/README.md)
- [Harness module and tests](../../modules/harness/README.md)
- [Subagent contracts](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md)
- [Plan contracts](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md)
- [MCP target module](../../modules/mcp/README.md)
- [Phase 4 contracts](phase-4-implementation.md)
- [Test parity audit](test-parity.md)
- [High-level design](high-level-design.md), [mid-level design](mid-level-design.md), [low-level design](low-level-design.md)

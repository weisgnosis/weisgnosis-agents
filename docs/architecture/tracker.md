# Architecture implementation tracker

Last synchronized: 2026-09-30. Implementation is active. This document records current capabilities and remaining work; review history belongs in Git. Available components do not imply complete host integration or exhaustive upstream parity.

## Current acceptance

- [x] `path_review` approved advisory PathSafety, configured factories, static filtering, registry/polling and task tools; `current_docs` approved live denial and plan inheritance. Both approved resource ownership, host-held sessions, bounded host session membership, fixed-session tools, directory-routed tools and policy-aware child construction, with no findings. Exact reviewed scope and identities are in the [tool](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#current-review-acceptance), [subagent](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#verification-and-acceptance) and [plan](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md) contracts.
- [x] `path_review` and `current_docs` approved explicit gate composition and authorizer views; exact identities and scope are in the [permission contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#current-review-acceptance). SubagentConstruction supplies the reviewed opt-in installation path.
- [x] `path_review` and `current_docs` approved the checked MCP envelope, including reproduced numeric-boundary fixes, with no findings. Exact identities and scope are in the [protocol contract](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/protocol/README.md#verification-and-acceptance).
- [x] `path_review` and `current_docs` approved borrowed-port RPC ownership and its final connection tests with no findings; exact scope and identities are in the [client contract](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/client/README.md#verification-and-acceptance).
- [x] `path_review` and `child_factory` approved bounded stdio framing; `path_review` and `registry_contract` approved direct-process stdio and real-process tests. Exact identities and scope are in the [transport contract](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/transport/README.md).
- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for initialized McpClient, checked request builders, typed remote errors and updated stdio integration, with no findings. The [client contract](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/client/README.md#verification-and-acceptance) owns exact identities.
- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for typed tool observations and compound cancellation-cause preservation, with no findings; the [tool acceptance](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#current-review-acceptance) owns exact scope and identities.
- [x] Full `scalafmtAll scalafmtSbt verify` passed: 564 core / 651 harness / 41 MCP tests (1,256 total), zero compiler warnings or failures. Evidence: `typed-tool-verify.log`.
- [x] Pin upstream reference to `f98a685fdc4589f81e626e1686ac2c63d14a2304`; the [parity audit](test-parity.md) records bounded method mappings, reproduced defects and explicit exclusions.

## Available capabilities

### Core

- [x] Immutable messages, typed errors, compile-time JSON codecs, 39 event variants, typed tools and immutable registration.
- [x] Accept typed Success/Error result payloads, runtime-owned identity/control metadata and cancellation-cause preservation after final independent review and verification. Provider multimodal serialization remains separate.
- [x] ReAct execution with persisted local calls, occurrence-aware results, interruption recovery, bounded thinking-only retries, native/synthetic structured output and scoped authorization.
- [x] Scripted clients and scoped text/function HTTP completion, strict SSE parsing, indexed argument assembly and correlated incremental events; dispatch requires validated stream completion.
- [x] Scoped result processing, history preparation, request-only projection, completion observers and typed generation reasons.
- [x] Parent-traversal rejection and canonical path screening. Portable filesystem storage requires explicit host ownership; advisory lexical command screening does not authorize execution.

### Harness

- [x] Pair-safe compaction, merged summaries, retained user provenance, token estimation and opt-in local/inline result eviction with 80,000-character offloading and 2,000-character previews.
- [x] Immutable permission priorities, interactive Promise-based confirmation, current-policy/input rechecks and serializable durable approval data.
- [x] Explicit permission-gate composition preserves priority, separate sequential resource scopes and failure/cancellation causes; immutable authorizer views retain shared policy/confirmation authority and rerun added gates against confirmed input.
- [x] Daily-memory extraction, in-memory and cooperative local stores, budgeted consolidation and recoverable local publication. Daily writers target `memory/YYYY-MM-DD.md`; only the consolidator writes curated `MEMORY.md` within 4,000 configured-counter tokens.
- [x] Typed transcripts, opt-in completion recording, shared monotonic triggers, bounded scoped task queues, pending conversation coalescing and completion-triggered flush.
- [x] Configured maintenance with acknowledged daily archival, explicit ordered transcript pruning, calendar retention, immutable budgets and literal prompt templates.
- [x] Conservative static plan inspection and opt-in live admission; exact-draft in-memory review, request-only context, scoped stored publication, strict model-facing plan controls and validated workspace-relative directories. Reopened drafts do not restore approval.
- [x] Scoped task supervision, cancellation leases and same-execution promotion after a 30-second wait per `awaitOrPromote` invocation. Runtime-owned lineage admits descendants through depth three and rejects depth four before identity allocation or callbacks.

- [x] Supervisor-owned typed resources outlive initialization and completed turns. Caller/supervisor closure joins initialization and nested running, queued and promoted workers before releasing resources; competing closers share completion and cleanup defects.

- [x] Configured factories build fresh children with final static filtering after inherited and automatic registration, deterministic assembly diagnostics, preserved authorizers and supervisor-owned execution.

- [x] Host-held child sessions retain one configured runtime/history, filtered toolkit and lineage across serialized sends with separate execution IDs. Queued cancellation leaves history unchanged; live injected denial/plan gates remain effective, and session closure joins promoted descendants before resource release.

- [x] Bounded host session directory registers acquired resources without transferring their original lifetime. Removal hides lookup and retains capacity until cleanup completes; explicit and host closure join all owned cleanup and share its outcome. Rejection preserves caller ownership.

- [x] Bounded task registry with lookup, waiting/promotion, cancellation and completed-record removal; registry closure cancels and joins promoted work. Capacity bounds records, not retained result bytes.
- [x] Optional live parent-denial inheritance preserves child policy and rereads parent rules against approved input; external parent gates and atomic post-decision revocation are excluded.

- [x] Opt-in model-facing controls: make supplies `agent_spawn`/`agent_task`/`agent_cancel` for a fresh-child registry; forSession supplies `agent_send`/`agent_task`/`agent_cancel` for one host-held session. Strict prompt-only invocation, isolated task lookup, bounded result projection and scoped wait/promotion/cancellation preserve the fixed capability.
- [x] Directory-bound send/task/cancel accepts strict session/task identifiers, preserves isolated histories and selected IDs in success/error observations, and joins cancellation during removal. Models cannot construct, register or remove sessions; membership lookup is not atomic revocation.
- [x] Opt-in SubagentConstruction initializes scoped child permissions and automatically carries effective static boundaries, live ancestor denials and ancestor/child Plan into descendant factories. Factory derivatives propagate; retained sessions keep live policy without copied approvals. Raw host callbacks remain explicit.
- [x] Host-installed inherited plan gate: either parent or child Plan mode restricts execution, including confirmation rechecks, without copying approval state.

The host installs these capabilities explicitly. It owns background supervisor scopes, trusted filesystem ancestors, authenticated approval delivery, provider settings and operational monitoring. Durable approval values are not a durable resume implementation. Root distribution and retained raw spawner capabilities remain trusted-host responsibilities.

## Immediate next work

- [ ] Implement MCP Toolkit integration next. The initialized client explicitly selects 2025-06-18 without claiming complete revision support; HTTP remains a separate transport task.
- [ ] Complete declared/general-purpose composition, including explicit tool/prompt/configuration installation; retain the accepted construction boundary.
- [ ] Define durable host reuse/recovery and correlated event routing separately, with explicit lifetime and retention contracts.

The accepted resource primitive, child session, factory, registry, task tools and plan adapter use host-selected process-local capabilities. They do not add declaration parsing, durable restart recovery or automatic inheritance through raw spawner callbacks. Hosts control capability distribution and completed-record retention.

## Remaining integration and parity

- [ ] Complete applicable core/harness method mappings and remaining structured-output, lifecycle and event-ordering gaps, including incremental local tool results and provider multimodal projection, provider signature/tool metadata, event adapters, phase-stop integration and richer subagent attribution.
- [ ] Complete remaining plan adaptations, including skills and declared/general-purpose configuration and legacy reused-child behavior; the opt-in construction path does not close those whole methods. Current plan inventory is eight adapted / six pending methods.
- [ ] Implement durable host persistence and authenticated, idempotent resumption from `ConfirmResult` without another model request.
- [ ] Complete model-selection configuration, remote memory/transcript adapters, timestamp-watermark migration, session discovery and scheduling.
- [ ] Validate platform-specific symlink/junction behavior and provide a secure storage backend if untrusted workspace support is required. Canonical checks are not an atomic filesystem sandbox; crash/fsync durability and hostile mutation isolation are not claimed.
- [x] Implement checked common MCP JSON-RPC envelopes with exclusive message variants, strict fields, recursive duplicate rejection and bounded lossless numeric identities.
- [x] Implement bounded scoped MCP request correlation with monotonic IDs, transactional settlement, cancellation cleanup and typed McpError lifecycle failures. Both reviewers approved the [client scope and identities](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/client/README.md#verification-and-acceptance).
- [x] Implement checked 2025-06-18 call-result projection for all content variants, embedded text/blob overlap, typed outcomes and nested metadata. Both reviewers approved the recorded protocol identities; transport and local observation conversion remain excluded.
- [x] Implement checked 2025-06-18 discovery requests/pages and tool-call requests, preserving schema/extension data and untrusted annotations. Both independent reviewers approved the exact [protocol scope and identities](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/protocol/README.md#verification-and-acceptance).
- [x] Implement pure 2025-06-18 initialization request/result helpers, truthful empty client capabilities, exact-version validation and typed tools capability projection. `path_review` and `current_docs` approved the exact [protocol identities](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/protocol/README.md#verification-and-acceptance) with no findings.
- [x] Implement borrowed-port scoped RPC ownership with checked sends, bounded operation/control admission, direction-safe server dispatch, owner-operated terminal cleanup and shared joined closure. Seven connection tests and full verification pass; both reviewers approved the final scope.
- [x] Implement reviewed bounded UTF-8/LF framing and scoped direct-process stdio with single-use incoming, serialized writes, terminal write poisoning and shared process cleanup. Four framing and three real-process groups pass.
- [x] Implement exact 2025-06-18 initialization, capability-checked one-page listing/invocation, joined configured deadlines and typed remote errors, including real-process stdio integration. Both reviewers approved the final scope and full verification passed.
- [x] Implement bounded sequential discovery with explicit page/tool limits, exact opaque cursor handling, cycle rejection and no partial catalogue on failure. Both reviewers approved the final client/spec identities.
- [ ] Implement Toolkit integration, then separately scoped HTTP support.
- [ ] Validate live end-to-end cancellation, resource release and operational controls, confirm remote CI, and complete release/API compatibility and production acceptance.

## Verification and contracts

Run `sbt scalafmtAll scalafmtSbt` and `sbt verify` with JDK 21. Verification requires zero compiler warnings and failed tests. Each behavioral increment also requires independent FP/ZIO and invariant review, current package contracts and test mappings, English prose and valid local links under [AGENTS.md](../../AGENTS.md).

On the current Windows host, use one sbt process at a time. If normal JVM startup fails under commit pressure, the previously successful process-local settings were `JAVA_TOOL_OPTIONS=-Xms128m -Xmx768m -Xss2m -XX:ActiveProcessorCount=4` and sbt `--mem 768`; unavailable proxy settings previously required `-Dhttp.nonProxyHosts=*` and `-Dhttps.nonProxyHosts=*`. These are environment workarounds, not repository defaults.

TaskSupervisor lifecycle changes must preserve its parallel outer owner scope, sequential worker scopes and explicit worker-owner completion closure; direct `forkIn` into the parallel owner previously caused self-join on immediate completion. Tests cover full Exit observation and cancellation cleanup. Resource closure must retain the first closer's Exit for finalizers and share its complete cleanup outcome with concurrent/repeated closers. Custom TaskSupervisor implementations must implement the new abstract own method; delegating only attach is insufficient.

- [Core module and tests](../../modules/core/README.md)
- [Harness module and tests](../../modules/harness/README.md)
- [Subagent contracts](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md)
- [Plan contracts](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md)
- [MCP module and remaining integration](../../modules/mcp/README.md)
- [Phase 4 contracts](phase-4-implementation.md)
- [Test parity audit](test-parity.md)
- [High-level design](high-level-design.md), [mid-level design](mid-level-design.md), [low-level design](low-level-design.md)

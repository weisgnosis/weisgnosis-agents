# Architecture implementation tracker

Last synchronized: 2026-10-07. Implementation is active. This document records current capabilities and remaining work; review history belongs in Git. Available components do not imply complete host integration or exhaustive upstream parity.

## Current delivery gates

Acceptance is scoped to the identities, contracts and evidence in linked package READMEs. These capabilities do not establish exhaustive upstream parity, a frozen release ABI or completion of host integration. Previous delivery narratives and test totals belong in Git.

- [x] [Exact domain/event/tool JSON](../../modules/core/src/main/scala/com/weisgnosis/agents/core/json/README.md#exact-domain-and-tool-output) preserves accepted numeric boundaries and metadata decoding.
- [x] [Provider request controls](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md) include typed parallel-call steering, cache usage, stop sequences, reasoning effort and per-tool strictness within their separate reviewed scopes.
- [x] [Completion profiles](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#completion-envelope-profiles), SSE framing, scalar aliases and explicit ordinary JSON completion retain validated runtime completion authority.
- [x] [User Video URL projection](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#user-video-url-projection) supports explicitly compatible endpoints; [summary text aliases](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#summary-text-alias) preserve strict selected-detail replay.
- [x] [Checked JSON publication/PostgreSQL](../../modules/storage-postgresql/src/main/scala/com/weisgnosis/agents/storage/postgresql/README.md#verification-and-acceptance) provides bounded per-address CAS with explicit schema, Unicode and cancellation contracts.
- [x] [Versioned memory](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md#versioned-remote-memory), [transcript](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/transcript/README.md#versioned-remote-transcript) and [acting checkpoints](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/storage/README.md#versioned-acting-checkpoints) have final scoped review/verification; host authentication, execution claims and replay remain separate.
- [x] [Ordered MCP metadata merge](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/tool/README.md#ordered-metadata-merge) preserves exact AST values, checked precedence and existing omission/runtime identity controls.

- [x] [Persistent memory admission](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md#versioned-memory-trigger-gate) has final scoped review/verification with explicit trusted-clock/policy/address and uncertain-acknowledgement limits.

- [x] [Immutable transcript publication](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/transcript/README.md#immutable-transcript-segments) provides bounded complete-chain restoration with host-owned manifest/subtree and explicit orphan/uncertain-acknowledgement limits.

- [x] [Explicit transcript catalog](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/transcript/README.md#explicit-transcript-session-catalog) registers checked descriptors at one known host address; global discovery and automatic writer installation remain separate.

- [x] [Checked unsigned candidate preparation](../../verification/artifact-consumer/README.md#candidate-preparation-acceptance) verifies explicit RC coordinates, exact source archive admission and actual selected-version consumer hashes. Remote workflows, tags/signing/publication and frozen ABI remain unverified.
- [x] Assess [remote retention/ownership limits](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/transcript/README.md#remote-retention-and-ownership-boundary) against 1f4581b with independent invariant evidence: current ports cannot safely reclaim physical objects.
- [ ] Define all-reader/writer fencing or quiescence, namespace generation/tombstone ABA protection, enumeration/deletion and uncertain-delete recovery before physical remote pruning.

- [x] [Registered transcript assembly](../../modules/examples/README.md#registered-transcript-assembly) resolves one explicit descriptor before MCP in the unpublished example; catalog membership grants no authorization, lease or history validity.

- [x] [Remote memory middleware throttle integration](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md#remote-middleware-throttle-integration) verifies a bounded real-PostgreSQL matrix with shared local oracle; broader remote guarantees remain separate.

- [x] [Custom-client turn trust boundary](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#model-turn-trust-boundary) validates assistant role/classification before runtime truth and persistence, with reproduced malformed-output regressions.

- [x] [Audited public model call](../../modules/examples/README.md#audited-public-model-call) demonstrates one delegated request and post-cleanup observation with explicit observer/failover limits.

- [x] [Checked usage arithmetic/runtime admission](../../modules/core/src/main/scala/com/weisgnosis/agents/core/model/README.md#checked-usage-arithmetic) rejects invalid/overflowing telemetry without replacing accepted usage; [lease cancellation](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/storage/README.md#admitted-work-failure-and-cancellation) preserves admitted failure plus pending interruption. Public arithmetic callers follow the recorded migration.

- [x] Reconcile living architecture documentation against c1c95be with independent review; current package boundaries and every unfinished blocker remain explicit.

- [x] [Checked thinking-budget endpoint preference](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#optional-thinking-budget) has final scoped acceptance; compatible endpoints remain host-selected and no local token limit is supplied.

- [x] [Explicit tool-image projection](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#tool-image-result-projection) has final scoped acceptance; default TextOnly and compatible-endpoint limits remain explicit.

- [x] [Thinking-only retry contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#thinking-only-retry-contract) has final test/documentation acceptance with independent retry/iteration bounds and no extra summarizing request.

- [x] [Interactive batch preflight](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#interactive-batch-preflight) has final scoped acceptance for stable initial policy denials/preparation errors before Ask; normal authorization remains fresh and dynamic gates remain execution-time decisions.

- [x] [Opaque signature details](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#opaque-signature-details) has final scoped acceptance with strict profile isolation and literal all-historical replay.

- [x] [Inline video projection](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#inline-video-projection) has final scoped acceptance for explicit video/webm and video/mp4; broader MIME types, Data inference and live interoperability remain open.

- [x] [Hook-error gate reuse](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#hook-error-gate-reuse) has final scoped test/documentation acceptance for a second successful same-agent invocation after typed hook failure.

Current delivery scope against 23a3bec: correct [interrupted transformed-progress history loss](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#interrupted-transformed-progress), preserving validated partial text without treating cancellation as completion.

- [x] transformed-partial-red.log reproduces published transformed text missing from canonical history on unchanged production: eight tests pass, one fails.
- [x] Finish producer/runtime progress and reviewed five-row cancellation/first-later typed-failure assertions; transformed-partial-green.log passes 14 tests with zero failures/ignored tests in 3.669 seconds, without new groups.
- [x] review_fp and review_invariants approved the exact final five-file source/spec scope against 23a3bec, with no unresolved source findings.
- [x] transformed-partial-verify.log passes 1,522 tests (676 core / 730 harness / 86 MCP / 22 real PostgreSQL / 8 examples), zero compiler warnings/failures/ignored tests and four RC1 packages; transformed-partial-preparation.log and transformed-partial-consumer.log pass current exact archives and fresh selected-API consumption.
- [x] review_fp and review_invariants approved the final eighteen-file combined scope against 23a3bec with no unresolved findings; English/migration/diff and 78-document/2,958-local-reference checks pass.
- [ ] Assess remaining provider/remote middleware/release gaps separately; full-plan completion is not claimed.

## Current acceptance

Acceptance is limited to each package's reviewed identities, behavior and evidence. Current source/spec manifests and reviewer names belong to the [core runtime](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md), [tools](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md), [skills](../../modules/core/src/main/scala/com/weisgnosis/agents/core/skill/README.md), [memory](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md), [transcripts](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/transcript/README.md), [permissions](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md), [subagents](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md), [plans](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md) and [MCP packages](../../modules/mcp/README.md). These links replace historical delivery narratives; they do not widen scoped approval to unfinished mappings.

## Available capabilities

### Core

- [x] Immutable messages, typed errors, compile-time JSON codecs, 39 event variants, typed tools and immutable registration.
- [x] Accept typed Success/Error result payloads, runtime-owned identity/control metadata and cancellation-cause preservation after final independent review and verification. Provider multimodal serialization remains separate.
- [x] Both reviewers approved [incremental local execution](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#incremental-execution): provisional events stay out of history; one final value requires EOF and cleanup; final-delta suppression is occurrence-local, and cancellation retains full causes. Exact six-file identities and reproduced draft-consumer evidence are recorded in the linked contract.
- [x] ReAct execution with persisted local calls, occurrence-aware results, interruption recovery, bounded thinking-only retries, native/synthetic structured output and scoped authorization.
- [x] Scripted clients and scoped text/user-media/function HTTP completion, strict SSE parsing, indexed argument assembly and correlated incremental events; dispatch requires validated stream completion.
- [x] Scoped result processing, history preparation, request-only projection, completion observers and typed generation reasons.
- [x] Parent-traversal rejection and canonical path screening. Portable filesystem storage requires explicit host ownership; advisory lexical command screening does not authorize execution.

### Harness

- [x] Bounded inline SKILL.md parsing with required string name/description, exact body preservation, ordered inert JSON metadata and positive collection-depth limits; typed resources and activation bindings remain host-owned.
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
- [x] Both reviewers approved the [host execution policy](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#host-execution-policy): checked positive waits through ten minutes cancel and join on timeout without promotion, while default 30-second promotion and original overloads remain. The contract records four current identities, full verification and the reproduced cancellation-handoff correction.
- [x] Directory-bound send/task/cancel accepts strict session/task identifiers, preserves isolated histories and selected IDs in success/error observations, and joins cancellation during removal. Models cannot construct, register or remove sessions; membership lookup is not atomic revocation.
- [x] Opt-in SubagentConstruction initializes scoped child permissions and automatically carries effective static boundaries, live ancestor denials and ancestor/child Plan into descendant factories. Factory derivatives propagate; retained sessions keep live policy without copied approvals. Raw host callbacks remain explicit.
- [x] Host-installed inherited plan gate: either parent or child Plan mode restricts execution, including confirmation rechecks, without copying approval state.

The host installs these capabilities explicitly. It owns background supervisor scopes, trusted filesystem ancestors, authenticated approval delivery, provider settings and operational monitoring. Durable approval values and conditional checkpoint data storage do not supply authenticated host delivery or an exclusive resume claim. Root distribution and retained raw spawner capabilities remain trusted-host responsibilities.

## Immediate next work

The transformed partial-history correction has final scoped acceptance above. Next, classify the finite pinned Plan propagation methods against existing child-installation/session tests, then continue the remaining applicable [method-level parity inventory](test-parity.md); do not infer new features or broader host authority from Java API differences.

- [ ] Complete finite pinned Plan propagation classification against current scoped child/session behavior, then select the next applicable inventory gap.
- [ ] Freeze the release compatibility baseline only against exact release sources/coordinates; the API candidate and selected consumer coverage do not establish backward binary compatibility.
- [ ] Define broader/global discovery, pruning and ownership/recovery contracts separately. Ambient-context/cache/schema redesign and other provider/parity, platform and release validation remain open.
- [ ] Complete other reasoning-detail formats and live-provider interoperability; selected profiles do not establish full formatter parity.
- [ ] Define durable host reuse/recovery and post-promotion event delivery separately, with explicit lifetime and retention contracts.

MCP recovery intentionally excludes automatic cursorless reconnect, unowned subscriptions, in-place session mutation and POST replay. Captured-session 404 alone produces typed expiry; SDK 400-expiry behavior is not adopted. Hosts serialize joined removal and fresh initialization/discovery/selection. These differences do not imply a missing automatic recovery API or full upstream parity.

The explicit known-address transcript catalog and unpublished registered assembly example are accepted; broader/global discovery, automatic registration/selection, physical reclamation, compaction/pruning/deletion and layout migration remain unfinished. Bounded remote memory/checkpoint data ports do not establish authenticated delivery, execution ownership or host restart recovery.

## Remaining integration and parity

- [x] The accepted MCP protocol/client/tool/transport packages provide checked 2025-06-18 envelopes, bounded discovery/RPC ownership, direct-process stdio, HTTP JSON/SSE, explicit host replacement and scoped manager installation within their linked contracts. Remaining interoperability and broader protocol behavior are not inferred from that scope.
- [x] Plan inventory methods have bounded adaptations, including opt-in child installation and retained named-session restrictions; Java middleware/API shapes and automatic flags remain deliberate differences.
- [x] MemoryExtractor supports explicit primary or dedicated host clients; this is not a string-ID registry or new upstream test mapping.
- [ ] Complete applicable core/harness method mappings and remaining structured-output, lifecycle and event-ordering gaps, including broader provider multimodal projection, other provider signature/tool metadata, event adapters, broader HITL delivery and richer subagent attribution.
- [ ] Implement durable host persistence, authenticated delivery and idempotent checkpoint claims around the accepted complete-batch ConfirmResult adapter; partial confirmation has final independent acceptance.
- [ ] Define string-ID model registry selection only under a separate host contract; broader/global transcript discovery, remote pruning/layout migration, checkpoint runtime/host installation, broader/global session discovery and automatic host scheduling remain unfinished; known-address catalog selection is accepted only under its explicit contract.
- [ ] Validate platform-specific symlink/junction behavior and provide a secure storage backend if untrusted workspace support is required. Canonical checks are not an atomic filesystem sandbox; crash/fsync durability and hostile mutation isolation are not claimed.
- [ ] Define remaining ambient metadata propagation, broader schema transformation and discovery caching separately; bounded manager acceptance does not close these upstream contracts.
- [ ] Validate live end-to-end cancellation, resource release and operational controls, confirm remote CI, and complete release/API compatibility and production acceptance.

## Release-candidate readiness assessment

These readiness checks supplement the remaining implementation/parity plan; they do not remove remote-adapter work or establish a release date or readiness percentage. Existing local SDK/Tomcat and Netty interoperability tests exercise real independent servers; public-provider validation remains separate.

- [x] Assess scoped cross-module assembly using the existing builder and verify the [host example](../../modules/examples/README.md); no additional library facade is justified for its accepted scope.
- [x] Assess logging/tracing through the independently reviewed [lifecycle logging example](../../modules/examples/README.md#lifecycle-logging): runtime correlation, static payload-free records, EOF consumption and joined cleanup. Host logger privacy/export and OpenTelemetry bridge remain separate; no mandatory dependency is introduced.
- [x] Add an [explicitly enabled live-provider smoke path](../../modules/examples/README.md) with bounded requests and tool calling; default verification remains offline and credential-free.
- [ ] Validate the smoke against an explicitly selected live endpoint; the executable alone does not prove provider interoperability.
- [x] Define the [development compatibility policy](../../project/README.md#compatibility-policy), retain package migration notes and verify selected public contracts from an independently built external artifact consumer with final review.
- [x] Record the current [public API candidate inventory](../../project/README.md#first-rc-api-candidate), restricted implementation boundaries and extension obligations after the signature assessment and Toolkit correction.
- [ ] Freeze the actual RC release baseline; current source consumption is not a published binary compatibility baseline.
- [ ] Validate remote CI and the release/publication pipeline, versioning, licensing and host/migration documentation before tagging or publishing an RC.

No Git remote is configured, and WEISGNOSIS_LIVE_SMOKE, WEISGNOSIS_LIVE_ENDPOINT, WEISGNOSIS_LIVE_MODEL and WEISGNOSIS_LIVE_API_KEY are unset; no remote workflow or public-provider validation was performed. Local RC validation does not close the unchecked release gates.

Durable workflow replay, authentication, UI, distributed execution and idempotent checkpoint claims belong to hosts. The selected versioned memory adapter is implemented above; broader/global transcript discovery, remote pruning/layout migration, checkpoint runtime/host installation and their remaining ownership/recovery contracts remain unfinished; host responsibility is not a reason to mark those adapters complete.

## Verification and contracts

Run `sbt scalafmtAll scalafmtSbt` and `sbt verify packageArtifacts` with JDK 21. Verification requires zero compiler warnings and failed tests. Each behavioral increment also requires independent FP/ZIO and invariant review, current package contracts and test mappings, English prose and valid local links under [AGENTS.md](../../AGENTS.md).

On the current Windows host, use one sbt process at a time. Current verification uses process-local `JAVA_TOOL_OPTIONS=-Xms64m -Xmx1024m -Xss16m -XX:ActiveProcessorCount=2` and sbt `--mem 1024`. These are host settings, not repository defaults.

TaskSupervisor lifecycle changes must preserve its parallel outer owner scope, sequential worker scopes and explicit worker-owner completion closure; direct `forkIn` into the parallel owner previously caused self-join on immediate completion. Tests cover full Exit observation and cancellation cleanup. Resource closure must retain the first closer's Exit for finalizers and share its complete cleanup outcome with concurrent/repeated closers. Custom TaskSupervisor implementations must implement both `own` and `ownRetained` with their distinct caller-lease contracts; delegating only `attach` is insufficient.

- [Core module and tests](../../modules/core/README.md)
- [Harness module and tests](../../modules/harness/README.md)
- [Subagent contracts](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md)
- [Plan contracts](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md)
- [MCP module and remaining integration](../../modules/mcp/README.md)
- [Phase 4 contracts](phase-4-implementation.md)
- [Test parity audit](test-parity.md)
- [High-level design](high-level-design.md), [mid-level design](mid-level-design.md), [low-level design](low-level-design.md)

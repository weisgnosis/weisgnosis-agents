# Library completion checklist

This is the single current checklist for completing weisgnosis-agents. The original promised library behavior remains the scope; WeisGnosis OS deployment and Temporal product tests are separate. Java API shapes, raw test counts and optional host platforms do not define completion.

The [parity reference](test-parity.md) retains upstream assertions and intentional differences. Its historical checklists are reference material, not a second delivery backlog. Reconcile substantive missing promised behavior with an outcome below; do not silently waive it or add an indefinite audit target.

## Current delivery gates

These implemented capabilities have existing public API/spec evidence. Checked availability does not certify every provider, platform or upstream method.

- [x] Execute typed tools, persist assistant calls before execution and reconcile interrupted local calls. See the [runtime contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md).
- [x] Suspend and restore a checked acting checkpoint under current host authorization without replaying supplied observations. See [checkpoint restoration](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#before-acting-suspension-and-restoration).
- [x] Compact paired history, merge summaries and offload oversized tool output without losing observations on persistence failure. See [memory](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md) and [eviction](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/eviction/README.md).
- [x] Run scoped children and retain task outcomes with checked observation, cancellation intent, acknowledgement and local recovery. See [subagents](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md).
- [x] Call installed MCP tools/resources over stdio or HTTP; project retained resource data into the next request without changing canonical history. See [request projection](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/tool/README.md#request-only-custom-content-projection).
- [x] Persist bounded data through conditional JSON storage, including actual PostgreSQL with host-owned DataSource/schema. See [storage](../../modules/storage-postgresql/README.md).
- [x] Run the opt-in live-provider smoke with streamed text, one tool execution and authoritative completion. This proves one selected run, not all providers or cancellation profiles; see [live verification](../../modules/examples/README.md#live-endpoint-verification).
- [x] Upload/read exact task-file bytes through an explicitly borrowed backend, with checked owner/agent/session isolation and no fallback. This is a typed route adaptation, not a general composite filesystem. See [shared task files](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#shared-task-files).
- [x] Deliver pending durable observations oldest dated first, with undated records last, stable publication ties and unchanged raw records/ACK stamps. See [delivery ordering](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#durable-terminal-acknowledgement).
- [x] Build local artifacts and consume selected APIs from a separate project. See the [artifact consumer](../../verification/artifact-consumer/README.md); remote publication remains open.

- [x] Reopen persistent named sessions by exact parent/agent/label through current factories and host-owned exclusive claims. Preserve idle history or checked suspension without replay; serialize snapshot/CAS save and reject further execution after uncertain persistence. See [persistent named sessions](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#persistent-named-sessions).

- [x] Run a selected real model through host-supplied write/read tools against a disposable file, checking exact physical bytes, next-request observations, Completed state and joined cleanup. LiveFileTask passed the selected endpoint on 2026-10-08; see [runnable task](../../modules/examples/README.md#model-driven-file-task). This is not a bundled unrestricted OS toolkit or provider-wide certification.

- [x] Explicitly compact a bounded immutable transcript chain to one visible segment without changing entries or overwriting concurrent appends. Preserve uncertainty/cancellation and retain old segments; see [compaction](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/transcript/README.md#immutable-transcript-segments). Safe reclamation, pruning and migration remain separate.

- [x] Parse typed subagent mode/visibility, carry policy into checked entries, reject PRIMARY before child allocation and keep Hidden explicitly selectable while suppressing advertisement. See [declaration eligibility](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#declaration-eligibility-and-visibility).

- [x] Control one prepared invocation through lazy ordinary/stream admission, status, replayable joined termination and owned cancel/cooperative interrupt. Preserve masked queue isolation, stale-handle safety and complete failure causes; see [controls](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#invocation-identity-and-prepared-execution). Broader session-routing, retry and structured/resume preparation remain separate dispositions.

- [x] Verify native Windows directory junction screening for existing and missing children, sensitive targets and lexical traversal, using an owned fixture whose borrowing JVM is joined before cleanup. See [native verification](../../verification/README.md#native-directory-aliases). POSIX execution, Windows file symlinks and hostile-mutation sandboxing are not implied.

- [x] Prepare separate prior history/new input once per admitted fresh invocation, with ordered scoped validation, exact failure/cancellation causes, retained cooperative interruption and one preparation across structured fallback. Restore/resume bypass this boundary; see [preparation](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#fresh-invocation-preparation).

- [x] Verify selected real-provider native and synthetic structured JSON, and joined cancellation of a model-issued local tool. Native passed on a catalog-selected small instruction model; Synthetic/Cancellation passed on the selected reasoning model. Native/All on the latter failed honestly; see [profile evidence](../../modules/examples/README.md#selected-structured-output-and-cancellation-profiles). No universal provider or in-flight HTTP cancellation claim follows.

- [x] Verify explicit independent runtime sessions, preparation-only retry under one reserved invocation and nested child cooperative-signal isolation. See [invocation mapping](test-parity.md#thinking-and-invocation-observations); ambient Java session routing and whole-execution replay are not introduced.

- [x] Deny unresolved approval when the host explicitly has no confirmation channel, including standard child construction; preserve live independent gates, Plan restrictions and interactive/durable defaults. See [unavailable-channel authorization](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#unavailable-approval-channel).

- [x] Observe an already admitted AgentProtocol task through an exact trusted transport binding without resubmission; preserve generation-bound approval claims through uncertainty, explicit cancellation intent and terminal ACK. See [remote observation](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#explicit-remote-task-observation). No concrete HTTP client or distributed fence is implied.

- [x] Publish opt-in subagent Spawn/Start/End through checked host metadata and actual registered named-session identity. Start follows turn admission; End follows the Spawn attempt and joined worker cleanup. Disabled defaults and closed-port limits remain explicit; see [milestones](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#subagent-milestones).

- [x] Register exact transcript bindings after nonempty recording through explicit writer composition. Both layouts support fresh-reader discovery and explicit stable-snapshot index repair; failure/cancellation preserves committed history without automatic replay or rollback. See [registered recording](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/transcript/README.md#registered-transcript-writer).

- [x] Provide the opt-in pinned-Java event DTO envelope and nested message/content/usage/confirmation values against 58 actual Jackson fixtures, with explicit identity/time and unchanged canonical Scala JSON. See [Java wire DTOs](../../modules/core/src/main/scala/com/weisgnosis/agents/core/event/javawire/README.md).

- [x] Retain immutable provider thinking metadata with array concatenation/latest-value replacement, isolated checked reasoning channels and validated omission from next-request replay. See [metadata](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#provider-thinking-metadata).

- [x] Apply five typed host permission modes through actual tool admission and confirmed checkpoint resume; retain explicit independent safety votes and parent-rule-only inheritance. See [modes](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#host-selected-permission-modes).

## Remaining library completion work

Check an outcome only after its expected result, relevant review and verification exist. New scope requires a promised behavior, reproduced defect or explicit user request. No percentage or calendar forecast is inferred from the item count.

- [ ] **Pinned extension and policy behavior:** finish the existing pinned hook/middleware and remaining permission-rule suggestion/applicability dispositions with named executable mappings or explicit justified API differences. Post-pin classification and milestone delivery do not close these obligations. See [hook inventory](test-parity.md#post-reasoning-decisions-and-accounting), [provider profiles](test-parity.md#selected-reasoning-details) and [permission inventory](test-parity.md#interactive-permission-adaptation).
- [ ] **Event production boundaries:** distinguish automatically emitted events from codec-only informational payloads, and reconcile any promised producer with actual runtime integration. Spawn/Start/End has the bounded implementation above; codecs alone do not establish other emission.
- [ ] **Fetched upstream changes:** finish the finite behavioral disposition of the 33 commits beyond the pin, including the core/harness portion of the session redesign and its changed assertions. Preserve the pin; new provider dialects, Java configuration frameworks and unrelated services do not become original-plan requirements merely by appearing in the diff. See [upstream delta](test-parity.md#upstream-delta-checked-on-2026-10-08).
- [ ] **Bounded workspace discovery:** resolve enumeration across explicitly supplied local and remote sources with identity deduplication. Known-address catalogs and local discovery are implemented; neither implies automatic global backend enumeration.
- [ ] **Remote transcript retention:** provide owner-authorized retirement from the discoverable catalog with explicit concurrency and failure semantics. Preserve immutable history; physical reclamation requires separate deletion/fencing guarantees and must not be inferred from CAS.
- [ ] **Local/remote recovery handoff:** reconcile the pinned mirror cold-recovery/union behavior with current explicit-store reopening; retain local-only data and define authority before implementing any import bridge. No generic layout converter or segmented-memory requirement has been established by the original plan.
- [ ] **Platform and live evidence:** reconcile advertised support with executed native-directory and selected real-provider profiles; state unsupported cases and prerequisites. Optional platforms and universal provider certification are not added completion gates; path screening remains distinct from an atomic sandbox.
- [ ] **Release usability:** finish user-facing documentation cleanup, run documented applications and reconcile compatibility/migrations against the final source. Preserve API/security contracts while removing legacy review journals; do not archive copied closed reports.
- [ ] **Release delivery:** finalize coordinates/API policy, build the candidate and prepare/verify CI and the intended distribution/signing workflow. Remote destination, credentials and actual publication are external setup/actions; a local JAR does not close them. See [workflows](../../.github/workflows/README.md) and [packaging](../../project/README.md#local-artifact-packaging).

## Immediate next work

Current task: complete bounded discovery across explicitly supplied local/catalog transcript sources, preserving exact binding identity and borrowed readers. In parallel, implement the remaining pinned phase-observation/order/Hint hooks and finish the finite upstream delta dispositions. Accepted wire DTOs, metadata merge and permission modes do not close these other obligations.

## Verification

Use JDK 21 and pinned sbt. For source/test changes run focused checks, then `sbt scalafmtAll scalafmtSbt` and `sbt verify`. Run one sbt process at a time. API/build/integration changes and the candidate also need applicable packaging and fresh-consumer checks. Prose changes need accuracy/link checks, not another full runtime run.

Actual PostgreSQL checks require a disposable database and WEISGNOSIS_POSTGRES_URL, WEISGNOSIS_POSTGRES_USER and WEISGNOSIS_POSTGRES_PASSWORD; see [fixture instructions](../../modules/storage-postgresql/src/test/scala/com/weisgnosis/agents/storage/postgresql/README.md). Skipped fixtures are not backend verification.

For the real-provider smoke, set WEISGNOSIS_LIVE_SMOKE=enabled, WEISGNOSIS_LIVE_ENDPOINT, WEISGNOSIS_LIVE_MODEL and, when needed, WEISGNOSIS_LIVE_API_KEY in the process environment. Run `sbt "examples / runMain com.weisgnosis.agents.examples.LiveProviderSmoke"`. Expected result: one smoke_sum execution, consumed streamed text and a checked Completed reply. Keep credentials out of source/logs. See [examples](../../modules/examples/README.md) for limits.

Review outcomes belong in the PR/task conversation. Documentation explains usage and boundaries; Git and regressions retain history. Follow [AGENTS.md](../../AGENTS.md) and [CONTRIBUTING.md](../../CONTRIBUTING.md).

## Host boundaries

Hosts supply authentication, approval delivery, exclusive durable execution claims/fencing, idempotency and native filesystem trust. Promise state does not survive restart. Stored records grant no execution authority. Sandbox/container/archive policies apply only to installed adapters. OS/Temporal product cutover is outside this checklist.

## Current acceptance

Implemented capabilities are listed above; remaining outcomes are not accepted merely because component tests pass. Active implementation and review proceed against the remaining outcomes. No public distribution or frozen binary API is claimed. Report each delivery's actual review/verification in its PR/task conversation without a package-hash journal.

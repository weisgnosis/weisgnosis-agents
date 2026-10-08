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

## Remaining library completion work

Check an outcome only after its expected result, relevant review and verification exist. New scope requires a promised behavior, reproduced defect or explicit user request. No percentage or calendar forecast is inferred from the item count.

- [ ] **Remaining runtime/provider obligations:** finish the finite disposition of promised event/hook, permission/declaration, metadata, reasoning/media and selected upstream-delta behavior. Link executable evidence, implement actual gaps or justify API/host adaptations. See [original scope](test-parity.md#original-plan-disposition), [subagents](test-parity.md#subagent-supervision-inventory) and [upstream delta](test-parity.md#upstream-delta-checked-on-2026-10-08). The latest upstream check found 33 commits beyond the pin, including six newly fetched commits and a substantial Harness/session redesign whose relevant behavior still needs classification. Java declaration totals are not a criterion.
- [ ] **Remaining remote adapters:** resolve retained memory/transcript/catalog/filesystem discovery, writer integration, pruning and layout migration against concrete ports. Implement promised library behavior and identify conditional host responsibilities. CAS alone does not prove safe reclamation or executable recovery. See [storage obligations](test-parity.md#versioned-json-publication-and-postgresql-subset).
- [ ] **Platform and live boundaries:** verify supported symlink/junction/path ownership and selected live cancellation/resource-release and structured-output profiles. State unsupported cases and prerequisites; path screening is not an atomic sandbox and offline mocks do not certify a live endpoint.
- [ ] **Release usability:** finish user-facing documentation cleanup, run documented applications, reconcile compatibility/migrations and verify a fresh consumer. Preserve API/security contracts while removing legacy review journals; do not archive copied closed reports.
- [ ] **Release delivery:** finalize coordinates/API policy, build the candidate and prepare/verify CI and the intended distribution/signing workflow. Remote destination, credentials and actual publication are external setup/actions; a local JAR does not close them. See [workflows](../../.github/workflows/README.md) and [packaging](../../project/README.md#local-artifact-packaging).

## Immediate next work

Current implementation task: once-per-admitted-invocation preparation of prior history and new input before model execution, with ordered scoped callbacks, validated state changes and one preparation across structured fallback. Prepared invocation lifecycle/controls and native Windows directory-junction screening are verified within their documented scopes. Declaration mode/visibility, persistent named-session restoration, transcript compaction and the model-driven file task are verified within their documented scopes. Other runtime/provider dispositions, remote adapters, platform/live profiles and release usability/delivery remain open.

## Verification

Use JDK 21 and pinned sbt. For source/test changes run focused checks, then `sbt scalafmtAll scalafmtSbt` and `sbt verify`. Run one sbt process at a time. API/build/integration changes and the candidate also need applicable packaging and fresh-consumer checks. Prose changes need accuracy/link checks, not another full runtime run.

Actual PostgreSQL checks require a disposable database and WEISGNOSIS_POSTGRES_URL, WEISGNOSIS_POSTGRES_USER and WEISGNOSIS_POSTGRES_PASSWORD; see [fixture instructions](../../modules/storage-postgresql/src/test/scala/com/weisgnosis/agents/storage/postgresql/README.md). Skipped fixtures are not backend verification.

For the real-provider smoke, set WEISGNOSIS_LIVE_SMOKE=enabled, WEISGNOSIS_LIVE_ENDPOINT, WEISGNOSIS_LIVE_MODEL and, when needed, WEISGNOSIS_LIVE_API_KEY in the process environment. Run `sbt "examples / runMain com.weisgnosis.agents.examples.LiveProviderSmoke"`. Expected result: one smoke_sum execution, consumed streamed text and a checked Completed reply. Keep credentials out of source/logs. See [examples](../../modules/examples/README.md) for limits.

Review outcomes belong in the PR/task conversation. Documentation explains usage and boundaries; Git and regressions retain history. Follow [AGENTS.md](../../AGENTS.md) and [CONTRIBUTING.md](../../CONTRIBUTING.md).

## Host boundaries

Hosts supply authentication, approval delivery, exclusive durable execution claims/fencing, idempotency and native filesystem trust. Promise state does not survive restart. Stored records grant no execution authority. Sandbox/container/archive policies apply only to installed adapters. OS/Temporal product cutover is outside this checklist.

## Current acceptance

Implemented capabilities are listed above; remaining outcomes are not accepted merely because component tests pass. Active implementation and review proceed against the remaining outcomes. No public distribution or frozen binary API is claimed. Report each delivery's actual review/verification in its PR/task conversation without a package-hash journal.

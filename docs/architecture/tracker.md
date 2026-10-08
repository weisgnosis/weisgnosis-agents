# Architecture implementation tracker

Last synchronized: 2026-10-08. The original six-phase plan is the completion contract. The plans at `6fe93a5` and audited `c9adcb4` are the reference for promised behavior. The selected release-candidate checklist is a verification subset, never a substitute for that plan. No promised capability is removed or waived by describing a narrower current API.

## Controlling original plan

- [x] Phase 0: architecture, engineering rules and build foundation exist and are enforced through verification and independent review.
- [x] Phase 1 implementation: immutable messages/content, typed errors and event hierarchy exist with paired codecs/specifications and recorded finite mappings.
- [ ] Phase 1 parity closure: complete remaining event method classification and any resulting applicable behavior gaps; component availability does not close the whole phase.
- [ ] Phase 2: complete tool/client behavior and applicable upstream parity. Declared Draft 4, 6, 7, 2019-09 and 2020-12 schema admission is implemented; remaining method and provider/transport applicability stay open.
- [ ] Phase 3: close remaining ReAct lifecycle/interruption/hook method mappings. Existing runtime behavior has scoped acceptance; whole-phase parity has not been established.
- [ ] Phase 4: complete remaining harness/permission/memory/transcript applicability and integration obligations. Existing compaction, eviction, memory, permission and Plan implementations are accepted only within their linked contracts.
- [ ] Phase 5: finish protocol preference/method applicability, durable workspace/task restoration and remaining subagent/MCP behavior mappings. Embedded-resource projection, bounded March 2025 batch messages, selected legacy SSE and model-facing task listing are implemented and verified below; they do not close the whole phase.
- [ ] Phase 6: perform the originally planned WeisGnosis OS cutover, including WeisGnosisAgentProvider, replacement of legacy host glue and Temporal Activity end-to-end verification. These are host/product changes; library ports and examples do not complete this phase.

The repository does not currently support a defensible whole-plan completion percentage. The pinned inventory contains 2,473 annotated Java core test declarations, with only named finite inventories classified. Test totals, source lines and available module counts cannot supply the missing denominator. The [parity audit](test-parity.md) owns detailed method evidence.

## Immediate next work

Execute the following sequence without changing the original scope. Component contracts retain exact source identities, reproduced defect evidence and scoped reviewer decisions; this checklist describes current work rather than a delivery journal.

- [x] Accept scoped invocation identity and lazy prepared execution in `c99596b`; the [agent contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#invocation-identity-and-prepared-execution) owns identity, confirmation binding and migration evidence.
- [x] Accept bounded durable terminal publication and reopened model-facing output/list/filter in `1925481`; the [registry contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#registry-terminal-publication) owns joined cleanup, receipt barriers, preview/ordering limits and corrected draft defects.
- [x] Accept strict schema migration, immutable first acknowledgement and observation-based inbox APIs in `bad689e`; the [acknowledgement contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#durable-terminal-acknowledgement) owns the corrected per-ID race and finite eight-method mapping.
- [x] Implement explicit ten-record host delivery and obtain final independent FP/ZIO and invariant source approvals for three Scala files against `bad689e`; focused verification passes eleven harness and two actual PostgreSQL groups. Callback cleanup precedes acknowledgement; final test-fixture cancellation ownership is corrected.
- [x] Pass full verification, four RC1 packages, archive preparation and fresh external consumption for the explicit delivery scope.
- [x] Update current contracts and obtain both final combined documentation approvals for the exact delivery scope: three Scala and eleven Markdown files against `bad689e`.
- [ ] Integrate automatic per-reasoning reminder delivery and tool consumption acknowledgement. The explicit host seam does not supply these original integrations.
- [ ] Implement durable admitted/running task identity and current-owner cancellation intent before claiming cross-process cancellation; preserve truthful terminal publication after joined cleanup.
- [ ] Complete durable reusable-session restoration and cross-process owner recovery with explicit host ownership/fencing.
- [ ] Complete remaining method-applicability inventories and resulting original library behavior gaps; classify retained obligations before claiming total completion.
- [ ] Execute original WeisGnosis OS cutover and Temporal Activity end-to-end verification under Phase 6.

The explicit delivery increment is accepted within its exact three-Scala/eleven-Markdown manifest against `bad689e`: both final combined reviews, full verification and artifact checks pass. No whole-plan completion or publication is claimed.

## Remaining effort

These are provisional engineering effort ranges, including implementation, proportionate verification, independent review/corrections and documentation. They are not elapsed wall-clock forecasts, token estimates or a quote for the entire original plan. Concurrent agents can overlap independent work; integration and final review remain sequential gates.

| Confirmed missing original library obligation | Remaining engineering effort | Principal uncertainty |
| --- | --- | --- |
| Supported MCP server-selected alternative revisions | Implementation, source/documentation reviews and verification complete | Both final combined reviews complete; no ordered preference, probing or replay claim. |
| Per-chunk accumulated thinking observations | Implementation/source/full checks complete; all delivery reviews/checks complete | Event-stream adaptation preserves incremental data; cumulative payload growth and migration are explicit. |
| Invocation identity and prepared-run exposure | Scoped delivery complete and committed as `c99596b` | Stable Auto identity, fresh resume identity, lazy single use and owned cancellation have finite evidence. Broader AgentRun lifecycle remains a separate finite classification. |
| Bounded durable registry publication and model-facing terminal read/list/filter | Implementation, both final combined reviews and full/artifact verification complete | Exact whole workspace-method metadata, running/session restoration, cancellation intent and automatic success-only consumption/push remain separate original obligations; the acknowledgement ledger is implemented below. |
| Durable acknowledgement ledger | Implementation, both final combined reviews and full/artifact verification complete | Automatic success-only consumption, completion ordering and notification integration remain separate. No distributed exactly-once promise. |
| Durable cancellation intent with current-owner handling | 12-24 hours after terminal storage | Persist intent, join owned cancellation and preserve terminal truth before notification. Cross-process takeover/fencing remains separate. |
| Remaining method applicability and resulting behavior | Not yet estimated | The finite inventories do not classify all 2,473 pinned core declarations. An evaluator dependency or passing suites cannot close unknown obligations. |

- [ ] Complete the remaining method-applicability inventory and estimate the resulting concrete implementation backlog. No whole-plan total is asserted before this work identifies what remains.
- [ ] Estimate and execute the original product cutover against its host sources and deployment contract; no product implementation or cutover is claimed here.
- [ ] Estimate external CI/distribution work after the repository, destination and operational setup exist; these are not assumed zero-cost tasks.

These estimates share persistence/verification work and must not be summed into a project forecast. Durable reusable-session restoration and cross-process owner recovery remain unestimated until their finite contracts are decomposed. The bounded original-definition catalogue now has source approvals and passing focused/full/artifact checks; final combined documentation approval passed against 63776a7 for two unchanged Scala identities and fourteen Markdown files. The [27-commit delta](test-parity.md#upstream-delta-checked-on-2026-10-08) also requires applicability decisions for search, provider replay/formatting, middleware lifecycle, tracing and persistence behavior. Classification does not implement or waive them.

## Current delivery gates

- [x] Immutable domain/events, checked exact JSON/usage, typed tool bindings and scoped ReAct execution have accepted source/spec scopes.
- [x] Reasoning/chunk assembly, interruption reconciliation, thinking-only loopback and native/synthetic structured output have accepted selected contracts.
- [x] Compaction preserves occurrence-aware call/result pairs, summary deduplication and raw-user provenance. [Overflow-safe estimates](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md#overflow-safe-token-estimates) preserve trigger/cutoff authority.
- [x] Eviction, daily/curated memory, local/remote transcript adapters, interactive/checkpoint permissions and Plan governance have accepted explicit ownership boundaries.
- [x] Scoped subagents, promotion, retained sessions, task registry/inbox, bounded model-facing enumeration and explicitly installed child policies have reviewed source scopes and full verification.
- [x] MCP tool/resource discovery, stdio, legacy HTTP/SSE, Streamable HTTP and transport-compatible explicit revision profiles have reviewed selected contracts; automatic preference negotiation and broader revision methods remain applicability work.
- [x] Optional PostgreSQL CAS and composed storage adapters pass actual database verification. [Cold-schema fixture provisioning](../../modules/storage-postgresql/src/test/scala/com/weisgnosis/agents/storage/postgresql/README.md#cold-schema-provisioning) is serialized separately from concurrent DML; production schema provisioning remains host-owned.
- [x] Four RC1 module artifacts, exact source-archive admission and an independent external consumer pass local verification.

## Current acceptance

The [explicit terminal delivery manifest](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#explicit-terminal-batch-delivery) owns three exact Scala identities and the current review scope against `bad689e`.

- [x] Implement explicit ten-record delivery with scoped callback cleanup before sequential exact-ID acknowledgement, typed receipts, duplicate rejection and no uncertain-write replay.
- [x] review_fp and review_invariants independently approve source manifest `048f7c44889cfd2eb0c2f3cc9bac8dc0290a904c48495a3f33c17e07d122098a`, with no unresolved source findings. Draft test-fixture cancellation ownership and the exact cleanup-cause oracle are corrected; no baseline production defect is claimed for that fixture correction.
- [x] `durable-delivery-final-focused.log` passes eleven harness groups and two actual PostgreSQL groups, zero failed/ignored.
- [x] `durable-delivery-final-verify.log` passes formatting, fatal-warning compilation and all 1,597 tests (689 core, 746 harness, 130 MCP, 24 actual PostgreSQL, eight examples), zero failed/ignored/compiler warnings; four RC1 packages are built using a fresh PostgreSQL fixture.
- [x] `durable-delivery-final-preparation.log` and `durable-delivery-final-consumer.log` pass source-archive admission, three restored negative checks and fresh standalone compilation/execution; the unchanged consumer probe does not independently exercise the new delivery API.
- [x] review_fp and review_invariants approve final combined documentation against `bad689e` for three unchanged Scala files and eleven Markdown files, no unresolved findings. English prose and 79-document/3,346-local-reference checks pass. Automatic per-reasoning reminders, tool consumption, completion ordering, cancellation intent, running metadata and session restoration remain unchecked.

The immutable process launcher remains in force and its current modified source identities redirect to the negotiation manifest. Its production dependency eviction diagnostics remain visible: no suppression or universal binary compatibility claim. SLF4J/dependency/runtime notices are distinct from compiler warnings.

The compiled publication guide retains its separate accepted evidence: publication-guide.log compiles all three complete applications with fatal warnings and executes ScriptedAgent successfully. This is not a new live-provider or whole-plan parity claim.

The separately retained earlier PostgreSQL connection-acquisition failure still has no established cause. The diagnosed cold-schema catalog race and its successful correction do not explain that older failure. Keep correlation of sanitized SQLSTATE, operation stage and server evidence open on recurrence.

Current exact source identities and reviewer evidence belong to [core](../../modules/core/README.md), [harness](../../modules/harness/README.md), [MCP](../../modules/mcp/README.md), [PostgreSQL](../../modules/storage-postgresql/README.md) and their package READMEs. Acceptance never extends to unfinished behavior automatically.

## Available capabilities

### Core

Immutable model/events/errors, exact JSON, typed local/external tools, fixed arguments, correlated streaming, ordinary JSON completion, checked usage, explicit provider profiles, bounded failover, structured output and scoped ReAct/checkpoint execution are available. Five declared standard schema drafts are implemented under the linked scoped review; broader method/profile obligations remain open above.

### Harness

Pair-safe compaction/pruning, offload, bounded memory/transcript publication, known-address catalog discovery, trigger admission, interactive/deferred permissions, Plan tools, skills and owned subagent/session/task capabilities are available. Filesystem ancestors, background supervisor scopes, authenticated approval delivery and exclusive durable execution remain explicitly host-owned. Their presence does not prove original product integration.

## Remaining integration and parity

- [x] Implement and independently source-review bounded connection-local discovered-tool catalogue acquisition/lookup/close semantics; full and artifact verification pass, with final combined documentation approval passed against 63776a7. Pinned sync/async wrapper initialization and cached-lookup tests require original remote definitions; immutable registered Toolkit snapshots already cover the separate discovery bridge.
- [x] Implement a server-selected alternative from explicitly configured supported MCP revisions, with both independent source approvals and full verification. Final combined delivery gates remain visible above; remaining builder methods are not automatically accepted.
- [x] Persist bounded terminal observations with automatic registry-owned publication and reopened model-facing output/list/filter, including failed results and owner isolation; source and full/artifact checks pass.
- [ ] Finish exact whole-method metadata, running records and session semantics for pinned WorkspaceTaskRepositoryTest methods103/136/178/317/360/388/444. The bounded terminal subset does not classify or complete all assertions in these methods, including original submission/completion ordering metadata.
- [ ] Implement automatic success-only consumption/push, publication-before-notification and cancellation-intent semantics. Pinned WorkspaceTaskRepositoryTest methods263/882/923 require durable admission/control identity and persisted terminal state before notification; the implemented acknowledgement ledger alone does not close these integration obligations.
- [ ] Decompose and implement durable reusable-session restoration and cross-process owner recovery with explicit host ownership/fencing. A stable-name/hash helper or persisted result does not restore execution authority.

- [ ] Complete the applicable upstream method mappings for clients/providers, ReAct lifecycle/hooks/events, permission delivery and richer subagent behavior; retain every original semantic obligation until implemented and independently accepted.
- [ ] Complete original host persistence, authenticated delivery, exclusive checkpoint claims and idempotency integration; serializable data is insufficient by itself.
- [ ] Complete remaining selected remote-adapter discovery/pruning/layout-migration contracts. Physical deletion requires all-reader/writer fencing or quiescence, generation/tombstone ABA protection, enumeration and uncertain-delete recovery; current CAS alone cannot safely provide them.
- [ ] Complete string-ID model selection, remaining metadata applicability and broader schema/provider transformations where applicable to the retained upstream scope; Pure FP is not a reason to exclude their behavior.
- [ ] Verify platform-specific symlink/junction behavior and the actual supported trust model. Canonical path checks are not an atomic filesystem sandbox; hostile mutation and crash/fsync guarantees are not established.
- [ ] Verify live cancellation/resource release and additional required completion/media/structured-output profiles. The successful bounded smoke below does not cover those paths.

## Release-candidate readiness assessment

This checklist records release checks within the controlling original plan. It cannot redefine completion or waive absent functionality.

- [x] Verify builder-based host composition, lifecycle logging and selected public APIs through the examples and external artifact consumer.
- [x] Provide explicitly enabled live-provider smoke execution while keeping normal verification offline.
- [x] On 2026-10-08, discover a loaded chat model from the authorized server catalog and run LiveProviderSmoke successfully with qwen3.8-27b: one smoke_sum(2,3) execution, three visible text deltas, fully consumed stream and authoritative Completed reply 5. live-provider-first.log and process exit zero are evidence for this exact run only.
- [ ] Verify additional required live contracts, including cancellation, structured output and actual host chains, without using model text as the completion oracle.
- [x] Verify current local packaging/licensing/archive admission and selected API consumption; current sources are not a frozen published binary compatibility baseline.
- [ ] Freeze actual release coordinates/API compatibility only after the original library obligations are closed.
- [ ] Configure and confirm actual remote CI and the manual candidate workflow; no Git remote is currently configured.
- [ ] Define and verify the actual distribution destination, credentials/signing and publication pipeline before tagging/publishing.

Credentials were supplied only to the authorized test process and are absent from repository deliverables. The server catalog selects the tested model; no live model name is introduced as a production default. Embeddings, reranking, decision and image endpoints are separate APIs and are not covered by this Chat Completions run.

## Verification and contracts

Run `sbt scalafmtAll scalafmtSbt` and `sbt verify packageArtifacts` with JDK 21. One sbt process runs at a time on this host. Current verification uses process-local JAVA_TOOL_OPTIONS=-Xms64m -Xmx1024m -Xss16m -XX:ActiveProcessorCount=2 and sbt --mem 1024; these are host settings.

Behavior changes require concrete contract evidence, RED-before-fix for reproduced defects, proportional tests, two independent final reviews, current English contracts/READMEs, valid local links and zero warnings/failures. Cancellation must join owned cleanup and preserve full causes. Supervisor ownership/promotion and host durable execution remain distinct contracts. Follow [AGENTS.md](../../AGENTS.md), [CONTRIBUTING.md](../../CONTRIBUTING.md) and the [parity audit](test-parity.md).

## Publication documentation audit

The [getting-started guide](../getting-started.md), simplified root navigation, corrected architecture examples and original-plan/upstream reconciliation have bounded acceptance at 2f2754a. All three complete applications compile against fresh artifacts; HTTP/MCP examples were compiled without starting external processes/requests. The later authorized live run above is separate evidence.

- [x] review_fp and review_invariants accepted the publication correction scope and its exact source identities, compiled examples and local artifact evidence.
- [x] Inspect maintained directories for README coverage and changed prose for English/UTF-8; current public guide and contracts have concrete entry routes.
- [x] Check 79 Markdown files and 3,010 local references with zero issues at the accepted publication baseline.
- [x] review_fp and review_invariants approved the final three-document controlling-plan/live-evidence/effort scope against 2f2754a, with unchanged source and no unresolved finding. No approval waives original obligations or establishes a whole-plan completion percentage.
- [ ] Complete the original implementation, parity, host and external release obligations above. Documentation clarity is not implementation completion.

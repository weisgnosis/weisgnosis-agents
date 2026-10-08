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

Execute the following sequence without changing the original scope:

- [x] Implement bounded exact terminal observations with immutable first publication, host-addressed owner partitions and strict persisted schema; both independent reviewers approve five Scala identities against `c99596b`.
- [x] Verify four focused harness groups and one actual PostgreSQL group for joined cleanup, exact reopened text, failed task observations, isolation, concurrent idempotence, bounds, definite conflicts and lost acknowledgements.
- [x] Complete full verification, four RC1 packages and fresh archive/consumer checks for the terminal observation store.
- [x] Both independent reviewers approve the final sixteen-file terminal observation scope against `c99596b`, with no unresolved findings; proceed to commit after final link/diff checks.
- [ ] Integrate durable terminal truth with model-facing task output/listing and explicit registry-owned publication. The store and caller-owned `publishJoined` do not implement automatic completion delivery, execution restoration or durable cancellation.

- [x] Reproduce the invocation draft integration failure before correction: `target/invocation-context-full-verify.log` records 688 core passes/one failure and 725 harness passes/nine failures against source manifest `038a769923e2d7869b6df17101d22988b23457a2d11cc032545c980b7f05d204`. Restored confirmation incorrectly compared the fresh execution identity with the saved proposal identity; event expectations also require the new execution context.
- [x] Correct confirmation binding to the exact saved reply/request pair while retaining ordinal, call identity and current policy checks; invocation-only acceptance and independently mismatched reply/request rejection pass the existing identity group.
- [x] Renew both independent source reviews for the corrected expanded core/harness scope and pass focused/full verification, artifact preparation and fresh consumption. Earlier eleven-file focused approval does not establish acceptance of the corrected fourteen-file scope.
- [x] Both independent reviewers approve final combined documentation for the corrected invocation increment against `db80dc0` (fourteen Scala files and fourteen Markdown files). Commit the approved delivery after final link/diff checks.
- [x] Resume the isolated terminal implementation and integrate its five formatted files into main after `c99596b`; focused harness and actual PostgreSQL checks pass. The separate worktree is a retained draft copy, not an independent accepted branch. Store implementation alone does not close model-facing reopened task lookup/listing or durable cancellation/delivery.

- [x] Implement server-selected MCP revision agreement among a checked explicit supported set, preserving exact negotiation defaults, legacy June compatibility and one scoped connection owner. Both independent source reviews approve the eighteen-file manifest.
- [x] Verify same-chunk framing, coherent HTTP request/continuation/listener/deletion headers, rejected concurrent ownership and joined cancellation. Full verification passes 1,581 tests with no compiler warnings, failed or ignored tests; four RC1 packages are built.
- [x] Close final combined documentation acceptance for the [supported revision scope](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/transport/README.md#supported-server-revision-agreement). Do not extend scoped acceptance to remaining original-plan obligations.
- [x] Complete per-chunk accumulated thinking alongside exact incremental deltas. Both source reviews approve six Scala identities; full verification passes 1,581 tests with existing groups extended. Final combined documentation approval passed against 4cc1225 for the twenty-file scope.
- [x] Classify both pinned ReActAgentRunIdVisibilityTest methods: reply/request identity is insufficient; explicit per-invocation identity and prepared lazy single-execution exposure are implementation obligations. Same-agent session concurrency remains an explicit API difference.
- [x] Implement explicit stable invocation identity across Auto attempts, ordinary/structured execution, resumption, forwarding and applicable callbacks/completion. Allocate it effectfully once per public invocation, independently of persisted reply identity; both source reviewers approve the corrected fourteen-file scope.
- [x] Implement a lazy prepared-run handle exposing its invocation identity before single execution, preserving scoped ownership and isolation from unrelated active work. This ordinary-run API does not implement the entire Java AgentRun lifecycle.


- [x] Correct the literal mutable-collection standards finding in McpStdioTransport.open through the immutable zio-process API, preserving inherited environment, Windows key handling, direct execution, stderr policy and joined cleanup. Both source reviews and focused/full/archive/consumer verification pass under the unchanged engineering standard. Final combined documentation acceptance passed against 203401a for the seventeen-file scope below.

- [x] Recheck the accepted implementation independently: current full verification, packaged consumption, compiled guide applications and one authorized live tool-calling run have concrete evidence below.
- [x] Classify all 39 pinned ToolValidatorTest methods with explicit executable evidence and typed null/reflection/API adaptations. Both independent source/classification reviews and full delivery verification pass; final combined documentation acceptance passed against 55df42a.
- [ ] Complete the remaining original-obligation inventories at method level, separating actual missing behavior from API-shape adaptations and explicit host integration; classify every retained obligation before claiming total completion.
- [x] Implement and independently review runtime JSON Schema validation under the pinned upstream default Draft 2020-12 semantics, including optional-null normalization. Full verification and fresh packaged consumption pass. Enforce it before local execution and external suspension, preserve required nulls, resolve references safely and avoid unpromised coercion/default injection. ToolValidator.java and ToolExecutor.java are the behavioral references; typed decoding alone is insufficient.
- [x] Implement and independently review MCP resource capability negotiation, checked listing/templates/reading, bounded discovery and subscription/notification lifecycle. Full verification and packaged consumption pass. Tool results containing embedded resources do not implement resource-server APIs.
- [x] Implement and independently review the originally promised older MCP revision and legacy HTTP/SSE capability: endpoint-event POST routing, inbound message events, same-origin target checks and joined cancellation/cleanup. Explicit single-profile lifecycle selection supports 2024-11-05, 2025-03-26 and 2025-06-18 where transport-compatible; it does not implement preference-list negotiation. Full verification and fresh packaged consumption pass.
- [x] Implement and independently review model-facing task listing over one selected owned registry, with bounded status/ID pages and no global discovery, payload disclosure or new task authority. Actual ReAct discovery from a new parent history, isolation and terminal-status projection pass focused and full verification.
- [x] review_fp and review_invariants approved the final combined legacy/task-list scope against ce55421, closing all code/test/documentation verification gates for this delivery.
- [x] Verify result-eviction inspection through the host-installed read_file capability: the actual agent requests inspection from a committed preview, receives the original 80,001 characters and completes under host authorization. Both source reviews and full verification pass.
- [x] Obtain independent final FP/ZIO and invariant source approvals, proportionate contract/regression coverage and full verification for the current schema/resource increment.
- [x] Both reviewers approved final combined documentation for the verified current increment against 1441a97. Apply the same gates to every subsequent task.

Current source-approved increment adds bounded durable terminal observations and explicit caller-owned publication after supervised cleanup. Both reviewers approve five Scala identities against `c99596b`; focused harness and actual PostgreSQL verification pass. Full/artifact/consumer verification passes; both independent final combined documentation reviews approve the sixteen-file scope against `c99596b`. The accepted invocation increment is committed as `c99596b`. Next implementation: model-facing reopened task output/listing with explicit owned publication; cancellation, delivery and execution restoration remain separate original obligations. No whole-plan completion is claimed.

- [x] Implement declared Draft 4, 6, 7 and 2019-09 alongside default Draft 2020-12, preserving exact numeric/reference checks and draft-specific keyword behavior. The [dialect manifest](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#declared-standard-schema-dialects) records bounded evidence; remaining method inventory is open.
- [x] Implement visible embedded MCP resource text, typed images and binary descriptions alongside lossless Custom payloads. See the [projection manifest](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/tool/README.md#embedded-resource-projection-parity).
- [x] review_fp and review_invariants approved the final combined 20-file scope against 1e90db1, with five unchanged source/spec identities and fifteen Markdown files; no unresolved findings remain in this scope.
- [x] Implement and independently review bounded March 2025 inbound JSON-RPC batches, preserving grouped server replies, malformed-member policy, singleton initialization and joined cleanup. June/November retain singular framing. Full verification passes; [packet scope](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/transport/README.md#march-2025-batch-packets) owns exact identities and finite evidence.
- [x] Complete fresh packaged consumption with exact four-module hash checks and the unchanged external public API probe.
- [x] review_fp and review_invariants approved the final combined 35-file scope against 55df42a, with sixteen unchanged source/spec identities and nineteen Markdown files; no unresolved findings remain in this scope.

## Remaining effort

These are provisional engineering effort ranges, including implementation, proportionate verification, independent review/corrections and documentation. They are not elapsed wall-clock forecasts, token estimates or a quote for the entire original plan. Concurrent agents can overlap independent work; integration and final review remain sequential gates.

| Confirmed missing original library obligation | Remaining engineering effort | Principal uncertainty |
| --- | --- | --- |
| Supported MCP server-selected alternative revisions | Implementation, source/documentation reviews and verification complete | Both final combined reviews complete; no ordered preference, probing or replay claim. |
| Per-chunk accumulated thinking observations | Implementation/source/full checks complete; all delivery reviews/checks complete | Event-stream adaptation preserves incremental data; cumulative payload growth and migration are explicit. |
| Invocation identity and prepared-run exposure | Scoped delivery complete and committed as `c99596b` | Stable Auto identity, fresh resume identity, lazy single use and owned cancellation have finite evidence. Broader AgentRun lifecycle remains a separate finite classification. |
| Remaining durable registry/model-facing publication, output, listing and filtering | Earlier 12-24 hour estimate covered the whole terminal obligation; residual not yet re-estimated | Bounded CAS store and direct adapter reopen/read/list are now implemented and verified. Automatic owned publication and model-facing reopened truth remain; execution restoration, cancellation and delivery acknowledgement are separate. |
| Durable delivery publication/acknowledgement | 8-16 hours after terminal storage | Persist before acknowledgement/notification and preserve uncertain-write receipts. No distributed exactly-once promise. |
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

The [terminal observation manifest](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#durable-terminal-observations) owns five exact Scala identities and the current review scope against `c99596b`.

- [x] Implement exact serializable terminal observations, checked aggregate bounds and caller-owned joined publication; no automatic execution/publication bridge is claimed.
- [x] review_fp and review_invariants independently approve source manifest `765852ad4fcfbf401282ac6345ce299da92a37d2f38f2fceccde9af3dc0cbbca`, with no source findings.
- [x] `durable-terminal-focused.log` passes four harness groups (278 ms) and one actual PostgreSQL group (648 ms), zero failed/ignored.
- [x] `durable-terminal-full-verify.log` passes formatting, fatal-warning compilation and all 1,588 tests (689 core, 738 harness, 130 MCP, 23 actual PostgreSQL, eight examples), zero failed/ignored/compiler warnings; four RC1 packages are built using a fresh PostgreSQL fixture.
- [x] `durable-terminal-preparation.log` and `durable-terminal-consumer.log` pass source-archive admission, three restored negative checks, fresh standalone compilation/execution and four module hashes; the unchanged consumer probe does not independently exercise terminal-store APIs.
- [x] review_fp and review_invariants approve final combined documentation against `c99596b`: five unchanged Scala files and eleven Markdown files, no unresolved findings. English and 79-document/3,303-local-reference checks pass. Original model-facing reopen, durable delivery/cancellation and restoration obligations remain unchecked.

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
- [ ] Persist terminal task observations for reopened output/listing. Pinned WorkspaceTaskRepositoryTest methods103/136/178/317/360/388/444 require persistence, failed-result projection, reopen lookup, filtered listing and terminal truth; scoped in-memory retention is insufficient.
- [ ] Implement durable delivery/acknowledgement and cancellation-intent semantics separately. Pinned WorkspaceTaskRepositoryTest methods263/882/923 require persisted state before notification/delivery; terminal storage alone cannot close them.
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

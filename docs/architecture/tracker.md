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

Execute the original scope without substituting a narrower release checklist. Package contracts own exact source identities, independent acceptance and verification evidence; Git retains previous delivery sequences.

- [x] Implement independently accepted invocation/prepared execution, durable terminal publication/acknowledgement and accepted-model-response observation.
- [x] Implement independently accepted automatic reminders, bound ordinary/routed inspection and full-output consumption, local selected/current-running barriers and scoped inbox waits.
- [x] Implement independently accepted checked worker identity, explicit borrowed store bindings and pre-start durable admission without executable restoration.
- [x] Implement independently accepted original creation/check metadata and exact expanded-result acknowledgement.
- [x] Reproduce the stored-only visibility gap against unchanged 0bbae03: a persisted Pending/Running/Terminal fixture exposes only its Terminal record (target/stored-task-views-gap-red.log).
- [x] Implement/source-review truthful stored-only Pending/Running model views and unresolved-work status without constructing executable handles; preserve local publication barriers and custom-store read failures.
- [x] Obtain final independent FP/ZIO and invariant source approval and pass focused/full/artifact/archive/consumer verification for stored views.
- [x] Obtain final combined source/documentation acceptance and commit the defined stored-view scope.
- [ ] Current task: preserve typed task failure messages and direct causes through strict legacy/current storage and public output; neither metadata nor bounded consumption establishes whole TaskTool parity.
- [ ] Implement current-owner durable cancellation intent and recovery authority, retaining truthful terminal publication after joined cleanup.
- [ ] Complete durable reusable-session restoration and cross-process owner recovery with explicit ownership/fencing.
- [ ] Complete remaining method-applicability inventories and resulting original library behavior gaps before claiming total completion.
- [ ] Execute the original WeisGnosis OS cutover and Temporal Activity end-to-end verification under Phase 6.

## Remaining effort

These are provisional engineering effort ranges, including implementation, proportionate verification, independent review/corrections and documentation. They are not elapsed wall-clock forecasts, token estimates or a quote for the entire original plan. Concurrent agents can overlap independent work; integration and final review remain sequential gates.

| Confirmed missing original library obligation | Remaining engineering effort | Principal uncertainty |
| --- | --- | --- |
| Remaining task-detail failure/cause applicability | Not yet estimated | Classify the pinned formatter contract separately from full terminal outcome availability. |
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

The [stored-task view contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#stored-task-views) owns the twelve-Scala increment against 0bbae03, manifest 1ac1a1aaeb623167b2eed4d47950db289044245bd5451f1fa97e93d78735bf73. Persisted nonterminals are data-only observations; querying never constructs a local executor or infers remote liveness. Legacy custom per-ID read failures and local publication barriers retain their authority.

- [x] Implement mixed stored Pending/Running/Terminal listing/inspection, immediate NotLocallyWaitable output and truthful unresolved inbox status.
- [x] review_fp and review_invariants approve all twelve exact Scala identities, with no unresolved findings. target/stored-task-views-focused-green.log passes 25 harness and two actual PostgreSQL groups, zero failed/ignored/compiler warnings.
- [x] target/stored-task-views-final-verify.log passes formatting/fatal-warning compilation, 1,631 tests (695 core / 774 harness / 130 MCP / 24 actual PostgreSQL / eight examples) and four RC1 packages, zero failed/ignored/compiler warnings.
- [x] target/stored-task-views-final-preparation.log passes negative archive admission/restoration and revalidation.
- [x] target/stored-task-views-final-consumer.log passes fresh standalone formatting, compilation, execution and four artifact-hash checks. The unchanged consumer probe does not directly exercise stored nonterminals; harness and actual PostgreSQL specifications provide that evidence.
- [x] review_fp and review_invariants approve the final combined scope against 0bbae03: twelve unchanged Scala identities and fourteen Markdown files, with no unresolved findings. English/UTF-8/diff checks and local Markdown links pass.

Stored querying does not close the physical workspace-file assertion, remaining failure/cause projection, completion ordering, durable cancellation authority, executable session restoration, remaining original-plan applicability or the actual product cutover. No whole-plan completion or publication is claimed.

## Available capabilities

### Core

Immutable model/events/errors, exact JSON, typed local/external tools, fixed arguments, correlated streaming, ordinary JSON completion, checked usage, explicit provider profiles, bounded failover, structured output and scoped ReAct/checkpoint execution are available. Five declared standard schema drafts are implemented under the linked scoped review; broader method/profile obligations remain open above.

### Harness

Pair-safe compaction/pruning, offload, bounded memory/transcript publication, known-address catalog discovery, trigger admission, interactive/deferred permissions, Plan tools, skills and owned subagent/session/task capabilities are available. Filesystem ancestors, background supervisor scopes, authenticated approval delivery and exclusive durable execution remain explicitly host-owned. Their presence does not prove original product integration.

## Remaining integration and parity

- [x] Implement and independently source-review bounded connection-local discovered-tool catalogue acquisition/lookup/close semantics; full and artifact verification pass, with final combined documentation approval passed against 63776a7. Pinned sync/async wrapper initialization and cached-lookup tests require original remote definitions; immutable registered Toolkit snapshots already cover the separate discovery bridge.
- [x] Implement a server-selected alternative from explicitly configured supported MCP revisions, with both independent source approvals and full verification. Final combined delivery gates remain visible above; remaining builder methods are not automatically accepted.
- [x] Persist bounded terminal observations with automatic registry-owned publication and reopened model-facing output/list/filter, including failed results and owner isolation; source and full/artifact checks pass.
- [x] Classify all 25 pinned WorkspaceTaskRepositoryTest methods: eight bounded observable adaptations, one partial and sixteen open; the [method inventory](test-parity.md#workspace-task-repository-method-inventory) owns exact assertions and differences.
- [ ] Complete the seventeen partial/open task-repository methods and separately classified TaskRecord metadata. Method444 now has verified durable Running/result/mixed-count semantics; its physical workspace JSON assertion469 remains open. It does not assert metadata fields or timestamps.
- [ ] Complete publication-before-notification, wakeup suppression and current-owner cancellation-intent semantics. Bound local/routed inspection and wait consumption are implemented within their accepted scopes. Pinned WorkspaceTaskRepositoryTest methods263/882/923 require durable admission/control identity and persisted terminal state before notification; the implemented acknowledgement ledger alone does not close these integration obligations.
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

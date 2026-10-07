# Architecture implementation tracker

Last synchronized: 2026-10-08. The original six-phase plan is the completion contract. The plans at `6fe93a5` and audited `c9adcb4` are the reference for promised behavior. The selected release-candidate checklist is a verification subset, never a substitute for that plan. No promised capability is removed or waived by describing a narrower current API.

## Controlling original plan

- [x] Phase 0: architecture, engineering rules and build foundation exist and are enforced through verification and independent review.
- [x] Phase 1 implementation: immutable messages/content, typed errors and event hierarchy exist with paired codecs/specifications and recorded finite mappings.
- [ ] Phase 1 parity closure: complete remaining event method classification and any resulting applicable behavior gaps; component availability does not close the whole phase.
- [ ] Phase 2: complete tool/client behavior and applicable upstream parity. Declared JSON Schema constraints are not yet enforced by runtime tool admission; provider/transport applicability remains incomplete.
- [ ] Phase 3: close remaining ReAct lifecycle/interruption/hook method mappings. Existing runtime behavior has scoped acceptance; whole-phase parity has not been established.
- [ ] Phase 4: complete remaining harness/permission/memory/transcript applicability and integration obligations. Existing compaction, eviction, memory, permission and Plan implementations are accepted only within their linked contracts.
- [ ] Phase 5: finish MCP resources, older protocol/legacy SSE compatibility, model-facing background task listing and remaining subagent/MCP behavior mappings.
- [ ] Phase 6: perform the originally planned WeisGnosis OS cutover, including WeisGnosisAgentProvider, replacement of legacy host glue and Temporal Activity end-to-end verification. These are host/product changes; library ports and examples do not complete this phase.

The repository does not currently support a defensible whole-plan completion percentage. The pinned inventory contains 2,473 annotated Java core test declarations, with only named finite inventories classified. Test totals, source lines and available module counts cannot supply the missing denominator. The [parity audit](test-parity.md) owns detailed method evidence.

## Immediate next work

Execute the following sequence without changing the original scope:

- [x] Recheck the accepted implementation independently: current full verification, packaged consumption, compiled guide applications and one authorized live tool-calling run have concrete evidence below.
- [ ] Complete the original-obligation inventory at method level, separating actual missing behavior from API-shape adaptations and explicit host integration; classify every retained obligation before claiming total completion.
- [ ] Implement runtime JSON Schema validation using pinned upstream Draft 2020-12 semantics, including optional-null normalization. Enforce it before local execution and external suspension, preserve required nulls, resolve references safely and avoid unpromised coercion/default injection. ToolValidator.java and ToolExecutor.java are the behavioral references; typed decoding alone is insufficient.
- [ ] Implement MCP resource capability negotiation, checked listing/templates/reading, bounded discovery and the required subscription/notification lifecycle. Tool results containing embedded resources do not implement resource-server APIs.
- [ ] Implement the originally promised MCP 2024-11-05 compatibility and legacy HTTP/SSE transport: endpoint-event negotiation, advertised POST routing, inbound message events and scoped cancellation/cleanup. Current 2025-06-18 Streamable HTTP does not supply these semantics.
- [ ] Add the originally promised model-facing task listing over the selected owned registry, with bounded projection and no global discovery or new task authority. Existing inspect/cancel tools and host-only registry.list do not provide this model capability.
- [ ] Verify result-eviction inspection through the host-installed read_file capability, as required by the original integration contract.
- [ ] Obtain final independent FP/ZIO and invariant reviews, proportionate contract/regression coverage, current documentation and full verification for each increment before acceptance.

Three resource protocol drafts were preserved under ignored `target/paused-mcp-resource-draft` when work was redirected to this controlling-plan correction. They are not installed, compiled, accepted or counted as progress. Resume their source implementation only through the same review gates.

## Remaining effort

These are provisional engineering effort ranges, including implementation, proportionate verification, independent review/corrections and documentation. They are not elapsed wall-clock forecasts, token estimates or a quote for the entire original plan. Concurrent agents can overlap independent work; integration and final review remain sequential gates.

| Confirmed missing original library obligation | Remaining engineering effort | Principal uncertainty |
| --- | --- | --- |
| Runtime JSON Schema admission and optional-null normalization | 23-51 hours | Standards-backed evaluator selection/integration, local references/composition, exact numeric semantics and admission boundaries. Includes 3-6 hours of contract/evaluator assessment and 20-45 hours of implementation/verification/review. |
| MCP resource-server APIs and lifecycle | 8-14 hours | Notification/subscription ownership, delivery bounds and independent interoperability. |
| Older MCP revision and legacy HTTP/SSE | 14-28 hours | Separate endpoint negotiation/routing, authentication/URI safety, joined cancellation and independent legacy-server compatibility. |
| Model-facing background task listing | 2-4 hours | Selected-registry visibility, bounded output and ownership-safe integration. |
| **Subtotal: four identified original library gaps** | **47-97 hours** | Excludes unclassified applicable methods, host cutover and external release work. |

- [x] Obtain independent resource/legacy estimates and an invariant-focused risk assessment; these ranges retain integration uncertainty instead of treating drafts as completed features.
- [ ] Complete the remaining method-applicability inventory and estimate the resulting concrete implementation backlog. No whole-plan total is asserted before this work identifies what remains.
- [ ] Estimate and execute the original product cutover against its host sources and deployment contract; no product implementation or cutover is claimed here.
- [ ] Estimate external CI/distribution work after the repository, destination and operational setup exist; these are not assumed zero-cost tasks.

The newly fetched upstream leading-BOM parser compatibility change is an additional 2-4 engineering hours, separate from the four original gaps. The [27-commit delta](test-parity.md#upstream-delta-checked-on-2026-10-08) also requires applicability decisions for search, provider replay/formatting, middleware lifecycle, tracing and persistence behavior. Classification does not implement or waive them.

## Current delivery gates

- [x] Immutable domain/events, checked exact JSON/usage, typed tool bindings and scoped ReAct execution have accepted source/spec scopes.
- [x] Reasoning/chunk assembly, interruption reconciliation, thinking-only loopback and native/synthetic structured output have accepted selected contracts.
- [x] Compaction preserves occurrence-aware call/result pairs, summary deduplication and raw-user provenance. [Overflow-safe estimates](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md#overflow-safe-token-estimates) preserve trigger/cutoff authority.
- [x] Eviction, daily/curated memory, local/remote transcript adapters, interactive/checkpoint permissions and Plan governance have accepted explicit ownership boundaries.
- [x] Scoped subagents, promotion, retained sessions, task registry/inbox and explicitly installed child policies are implemented; model-facing enumeration remains open above.
- [x] MCP 2025-06-18 tool discovery/calls, stdio and Streamable HTTP have accepted selected transport/client/tool contracts.
- [x] Optional PostgreSQL CAS and composed storage adapters pass actual database verification. [Cold-schema fixture provisioning](../../modules/storage-postgresql/src/test/scala/com/weisgnosis/agents/storage/postgresql/README.md#cold-schema-provisioning) is serialized separately from concurrent DML; production schema provisioning remains host-owned.
- [x] Four RC1 module artifacts, exact source-archive admission and an independent external consumer pass local verification.

## Current acceptance

Accepted source baseline: `2f2754a5e59f180953f6de0073341211c9155054`. review_fp and review_invariants approved its 19-file increment: six exact Scala identities in the memory/PostgreSQL manifests and thirteen Markdown files. Both also verified the local unsigned ZIP identity; this establishes neither signing/publication nor whole-plan completion.

publication-cold-verify.log passes 1,527 tests: 680 core, 731 harness, 86 MCP, 22 actual PostgreSQL and 8 examples. There are zero failed/ignored tests or compiler warnings. Formatting, compilation and four RC1 packages pass on a newly created empty PostgreSQL 16 database. publication-preparation.log and publication-consumer.log pass source/POM/LICENSE admission, negative archive cases and fresh standalone selected-public-API consumption. publication-guide.log compiles all three complete guide applications with fatal warnings and executes ScriptedAgent successfully.

An independent subagent rerun in plan-control-verify.log also exits zero with the same 1,527 tests, zero warnings/failures/ignored and four RC1 packages on another newly created empty PostgreSQL 16 fixture; its owned container was stopped after verification.

The separately retained earlier PostgreSQL connection-acquisition failure still has no established cause. The diagnosed cold-schema catalog race and its successful correction do not explain that older failure. Keep correlation of sanitized SQLSTATE, operation stage and server evidence open on recurrence.

Current exact source identities and reviewer evidence belong to [core](../../modules/core/README.md), [harness](../../modules/harness/README.md), [MCP](../../modules/mcp/README.md), [PostgreSQL](../../modules/storage-postgresql/README.md) and their package READMEs. Acceptance never extends to unfinished behavior automatically.

## Available capabilities

### Core

Immutable model/events/errors, exact JSON, typed local/external tools, fixed arguments, correlated streaming, ordinary JSON completion, checked usage, explicit provider profiles, bounded failover, structured output and scoped ReAct/checkpoint execution are available. Missing schema admission and broader method/profile obligations remain open above.

### Harness

Pair-safe compaction/pruning, offload, bounded memory/transcript publication, known-address catalog discovery, trigger admission, interactive/deferred permissions, Plan tools, skills and owned subagent/session/task capabilities are available. Filesystem ancestors, background supervisor scopes, authenticated approval delivery and exclusive durable execution remain explicitly host-owned. Their presence does not prove original product integration.

## Remaining integration and parity

- [ ] Complete the applicable upstream method mappings for clients/providers, ReAct lifecycle/hooks/events, permission delivery and richer subagent behavior; retain every original semantic obligation until implemented and independently accepted.
- [ ] Complete original host persistence, authenticated delivery, exclusive checkpoint claims and idempotency integration; serializable data is insufficient by itself.
- [ ] Complete remaining selected remote-adapter discovery/pruning/layout-migration contracts. Physical deletion requires all-reader/writer fencing or quiescence, generation/tombstone ABA protection, enumeration and uncertain-delete recovery; current CAS alone cannot safely provide them.
- [ ] Complete string-ID model selection, ambient metadata propagation, discovery caching and broader schema/provider transformations where applicable to the retained upstream scope; Pure FP is not a reason to exclude their behavior.
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

# Architecture implementation tracker

Last synchronized: 2026-10-08. The original library deliverables are the completion contract. WeisGnosis OS production cutover and its Temporal end-to-end tests are a separate product task, explicitly excluded from completion of this repository by the user on 2026-10-08. The plans at `6fe93a5` and audited `c9adcb4` are the reference for promised behavior. The selected release-candidate checklist is a verification subset, never a substitute for that plan. No promised capability is removed or waived by describing a narrower current API.

## Controlling original plan

- [x] Phase 0: architecture, engineering rules and build foundation exist and are enforced through verification and independent review.
- [x] Phase 1 implementation: immutable messages/content, typed errors and event hierarchy exist with paired codecs/specifications and recorded finite mappings.
- [ ] Phase 1 parity closure: complete remaining event method classification and any resulting applicable behavior gaps; component availability does not close the whole phase.
- [ ] Phase 2: complete tool/client behavior and applicable upstream parity. Declared Draft 4, 6, 7, 2019-09 and 2020-12 schema admission is implemented; remaining method and provider/transport applicability stay open.
- [ ] Phase 3: close remaining ReAct lifecycle/interruption/hook method mappings. Existing runtime behavior has scoped acceptance; whole-phase parity has not been established.
- [ ] Phase 4: complete remaining harness/permission/memory/transcript applicability and integration obligations. Existing compaction, eviction, memory, permission and Plan implementations are accepted only within their linked contracts.
- [ ] Phase 5: finish remaining protocol methods, durable named-session/task restoration and remaining subagent/MCP behavior mappings. Embedded-resource projection, bounded March 2025 batch messages, selected legacy SSE and model-facing task listing are implemented and verified below; they do not close the whole phase.
Phase 6 in the historical plan describes the separate WeisGnosis OS product cutover. It is not a library completion gate. This repository must document its integration contracts; installing and verifying them in the product is separate work.

Completion is assessed against concrete promised library behaviors and their acceptance evidence. The 2,473 pinned Java test declarations are a source inventory, not 2,473 required Scala implementations or an automatic completion blocker. Remaining applicability work must produce a finite list of missing observable library contracts, with explicit justification for Java-only API adaptations. The [parity audit](test-parity.md) owns detailed method evidence.

## Immediate next work

Next source task: implement retained workspace task-file routing857 over a checked borrowed byte-store capability. Persistent named-session implementation is being prepared in an isolated worktree; it is not yet accepted.

Execute the original scope without substituting a narrower release checklist. Package contracts own exact source identities, independent acceptance and verification evidence; Git retains previous delivery sequences.

- [x] Implement accepted invocation/prepared execution, durable admission/publication/acknowledgement and accepted-model-response observation.
- [x] Implement accepted reminders, ordinary/routed full-output consumption, scoped inbox waits and local completion barriers.
- [x] Implement accepted worker identity, borrowed store bindings, stored nonterminal views and typed failures without executable restoration.
- [x] Implement accepted current-owner cancellation intent, completion notification/suppression and explicit heartbeat.
- [x] Reproduce failed-open ownership retention before correction in the JSON draft and all three unchanged Plan/Transcript/Memory factories.
- [x] Correct shared initialization ownership; verify failure/cancellation causes, joined cleanup, once-only finalizers and same-host repair/reopen.
- [x] Implement physical local workspace JSON and actual mixed-state/result/count/reopen behavior (WorkspaceTaskRepositoryTest 469); both reviewers approve eight exact Scala identities and focused/full/artifact/archive checks pass.
- [x] Pass fresh standalone consumption against all four verified artifacts.
- [x] review_fp and review_invariants approve the final combined scope against 73b0fb1: eight unchanged Scala identities and twenty-two Markdown files, with no unresolved findings.
- [x] Implement/source-review host-driven stale-owner reconciliation and its admission gate (578/614/665 and 716/734/761), with explicit recovery authority; both reviewers approve six Scala identities. Persisted-origin case687 is implemented under the current acceptance gates below.
- [x] Reproduce draft Infinity-policy admission and unnecessary host-authority acquisition for retained local tasks before correction; actual RED logs are linked by the package contract.
- [x] Pass final recovery focused contracts: 28 harness and two actual PostgreSQL groups, including renewed finite-default and reusable-gate boundary checks.
- [x] Pass recovery full verification, four packages, negative archive admission/restoration and fresh standalone consumption.
- [x] review_fp and review_invariants approve the final combined scope against b4e3b37: six unchanged Scala identities and fourteen Markdown files, with no unresolved findings.
- [x] review_fp and review_invariants approve the final combined scope against 7b59bc9: six unchanged Scala identities and sixteen Markdown files, with no unresolved findings; focused/full/artifact/archive/consumer checks pass for local discovery and the shared cancellation-provenance correction.
- [x] Implement and independently source-review explicit persisted execution origin and agent-protocol recovery exclusion687; final combined acceptance passes below. Endpoint execution/polling is separate.
- [ ] Complete durable reusable-session restoration and retained workspace-file routing with explicit host ownership. Distributed claims/fencing remain host integration requirements.
- [ ] Complete remaining method-applicability inventories and resulting original library behavior gaps before claiming total completion.

## Remaining library completion work

This finite backlog follows independent source inspection by review_fp and review_invariants on 2026-10-08. It covers promised observable library behavior, not Java API shape, test declaration totals or WeisGnosis OS deployment. Estimates below are provisional engineering effort including tests, review and documentation, not elapsed agent runtime or token forecasts. Existing implementations must be reused rather than recreated.

- [x] Accept persisted execution-origin/recovery exclusion687: both reviewers approve the final 23-file combined scope against a5da755; focused/full/database/artifact/consumer checks pass.
- [ ] Add persistent named-child selection and restoration: deterministic parent/agent/label identity, bounded saved conversation/suspended state, validated hydration into a fresh current host factory, and reuse through spawn/send. Reuse existing retained sessions, run locks, checkpoint import and versioned storage; never deserialize live resources or old fibers. Provisional incremental effort: 16–32 engineering hours.
- [x] Reproduce and implement MCP embedded-resource request projection, with both independent source approvals and five passing focused groups; exact literal payload descriptions preserve canonical resource history.
- [x] Complete the [request-projection delivery gates](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/tool/README.md#request-only-custom-content-projection): full/database/artifact/consumer checks and both final combined reviews pass. The core default is unchanged; host installation is explicit.
- [ ] Implement retained workspace task-file routing857 through a borrowed byte-file capability with checked agent/session namespace isolation. This is file routing, not a remote execution service or another CAS database. Provisional effort: 8–14 engineering hours.
- [ ] Resolve the selected pending-delivery timestamp-order assertion against publication ordering, including an explicit legacy-undated policy; correct applicable behavior with a regression rather than claim equivalent ordering. Provisional effort: 4–8 engineering hours.
- [ ] Finish the finite original-deliverable/research-lesson disposition, platform/live validation and publication documentation, then perform final independent review and required verification. Provisional audit/documentation effort: 4–8 engineering hours; live availability is external.

The estimates are source-based human engineering effort ranges, not a promise of several weeks of agent execution; parallel preparation overlaps work and automated checks have separate observed runtimes. The current origin increment is already implemented and source-approved, so its earlier implementation estimate is not counted again. Concrete newly reproduced contract failures may change this backlog; they must be named and evidenced rather than expanding an indefinite method-count target.

Host authentication, exclusive durable execution claims/fencing, remote execution endpoints and background scheduling remain explicit integration requirements. Sandbox/container snapshots, archive extraction and mount/permission policies apply when a host installs such backends; their absence does not mandate implementing an unused platform inside this library. Phase 6 WeisGnosis OS cutover and Temporal product tests are separate work. Remote CI/publication require a configured destination and are not claimed completed by local packaging.

## Current delivery gates

- [x] Immutable domain/events, checked exact JSON/usage, typed tool bindings and scoped ReAct execution have accepted source/spec scopes.
- [x] Reasoning/chunk assembly, interruption reconciliation, thinking-only loopback and native/synthetic structured output have accepted selected contracts.
- [x] Compaction preserves occurrence-aware call/result pairs, summary deduplication and raw-user provenance. [Overflow-safe estimates](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md#overflow-safe-token-estimates) preserve trigger/cutoff authority.
- [x] Eviction, daily/curated memory, local/remote transcript adapters, interactive/checkpoint permissions and Plan governance have accepted explicit ownership boundaries.
- [x] Scoped subagents, promotion, retained sessions, task registry/inbox, bounded model-facing enumeration and explicitly installed child policies have reviewed source scopes and full verification.
- [x] MCP tool/resource discovery, stdio, legacy HTTP/SSE, Streamable HTTP and transport-compatible explicit revision profiles have reviewed selected contracts; broader revision methods require finite applicability classification. Pinned SDK version selection proposes the last configured version and accepts supported server alternatives; it does not implement ranked probing or fallback.
- [x] Optional PostgreSQL CAS and composed storage adapters pass actual database verification. [Cold-schema fixture provisioning](../../modules/storage-postgresql/src/test/scala/com/weisgnosis/agents/storage/postgresql/README.md#cold-schema-provisioning) is serialized separately from concurrent DML; production schema provisioning remains host-owned.
- [x] Four RC1 module artifacts, exact source-archive admission and an independent external consumer pass local verification.

## Current acceptance

The [MCP request-only projection contract](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/tool/README.md#request-only-custom-content-projection) owns three exact Scala identities against 6477b3b, manifest 3acf9fb5ac1e382952eff9fc611f13acc3ff509702f718ef9f4d4314266587f0. Explicit installation describes Custom tool data literally for the next request, retains prior settings/hooks and preserves canonical resource history and media validation.

- [x] Reproduce the actual converter/tool/runtime/next-request failure against unchanged production before correction.
- [x] review_fp and review_invariants approve all three exact Scala identities, no unresolved source findings.
- [x] Focused five groups pass in 1.605 seconds; the separate full run passes 1,655 tests (695 core / 797 harness / 131 MCP / 24 actual PostgreSQL / eight examples), zero failed/ignored/compiler warnings, and four RC1 packages.
- [x] Negative archive admission/restoration and fresh standalone consumer verification pass. The unchanged consumer probe is not direct transform coverage.
- [x] review_fp and review_invariants approve the final eleven-file combined scope against 6477b3b: three unchanged Scala identities and eight Markdown files, no unresolved findings. English prose, local links and diff checks pass.

The preceding [origin contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#persisted-execution-origin) remains accepted: 24 bounded task-repository methods are closed and routing857 remains open. The [finite library checklist](#remaining-library-completion-work) separates remaining implementation from host/product work.

## Available capabilities

### Core

Immutable model/events/errors, exact JSON, typed local/external tools, fixed arguments, correlated streaming, ordinary JSON completion, checked usage, explicit provider profiles, bounded failover, structured output and scoped ReAct/checkpoint execution are available. Five declared standard schema drafts are implemented under the linked scoped review; broader method/profile obligations remain open above.

### Harness

Pair-safe compaction/pruning, offload, bounded memory/transcript publication, known-address catalog discovery, trigger admission, interactive/deferred permissions, Plan tools, skills and owned subagent/session/task capabilities are available. Filesystem ancestors, background supervisor scopes, authenticated approval delivery and exclusive durable execution remain explicitly host-owned. Their presence does not prove original product integration.

## Remaining integration and parity

- [x] Implement and independently source-review bounded connection-local discovered-tool catalogue acquisition/lookup/close semantics; full and artifact verification pass, with final combined documentation approval passed against 63776a7. Pinned sync/async wrapper initialization and cached-lookup tests require original remote definitions; immutable registered Toolkit snapshots already cover the separate discovery bridge.
- [x] Implement a server-selected alternative from explicitly configured supported MCP revisions, with both independent source approvals and full verification. Final combined delivery gates remain visible above; remaining builder methods are not automatically accepted.
- [x] Persist bounded terminal observations with automatic registry-owned publication and reopened model-facing output/list/filter, including failed results and owner isolation; source and full/artifact checks pass.
- [x] Classify all 25 pinned WorkspaceTaskRepositoryTest methods: twenty-four accepted bounded observable adaptations and open857; the [method inventory](test-parity.md#workspace-task-repository-method-inventory) owns exact assertions and differences.
- [ ] Complete task-file routing857 and classify separately selected TaskRecord metadata. Method444 has verified durable Running/result/mixed-count semantics and physical JSON assertion469 under the linked local adapter contract; it does not assert metadata fields or timestamps.
- [x] Implement/source-review the bounded [current-owner cancellation adaptation](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#durable-cancellation-intent) of method263; its final combined approval is recorded above.
- [x] Implement/source-review publication-before-notification and per-submission suppression for WorkspaceTaskRepositoryTest methods882/923; final combined documentation approval is recorded above. Remote durable wakeup replay remains separate.
- [ ] Complete durable reusable-session restoration through fresh trusted factories and validated saved state. Cross-process execution claims/fencing are supplied by the host; stored data does not grant that authority.

- [ ] Complete the applicable upstream method mappings for clients/providers, ReAct lifecycle/hooks/events, permission delivery and richer subagent behavior; retain every original semantic obligation until implemented and independently accepted.
- [x] Classify research requirements 15/26/28–33 against actual adapter boundaries in the [research catalogue](../deep-research-and-porting-guide.md#3-catalog-of-production-edge-cases-35-hard-lessons). Explicit addresses do not infer anonymous tenants; remote noninteractive approval and sandbox/archive/snapshot/container policies apply when those backends are installed. No absent subsystem is claimed implemented or added as an unused platform requirement.
Host integration requirement: authenticated delivery, exclusive checkpoint claims and idempotency remain host responsibilities. The library documents and exposes checked state/ownership ports; actual product installation is separate work.
- [ ] Complete remaining selected remote-adapter discovery/pruning/layout-migration contracts. Physical deletion requires all-reader/writer fencing or quiescence, generation/tombstone ABA protection, enumeration and uncertain-delete recovery; current CAS alone cannot safely provide them.
- [ ] Finish finite classification of remaining metadata/provider transformations against actual promised behavior. Injected clients already provide model selection; a Java string-ID registry or builder shape is not itself a missing library capability.
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

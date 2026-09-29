# Architecture implementation tracker

Last synchronized: 2026-09-29. Implemented components are distinguished from host integration and exhaustive upstream parity. Passing local tests does not establish either broader claim.

## Progress checklist

Checked items have verification evidence. An unchecked item is unfinished; completed components do not imply completion of their entire phase. Paused by explicit user request on 2026-09-29. On resumption, next task: implement configured child construction and task registry, then integrate inherited plan restrictions for new and reused children; session discovery, remote storage and host operational monitoring remain separate work. Local and inline provider result eviction are installed through an opt-in processor.

### Foundation and current core

- [x] Configure Scala 3.9.0, ZIO 2, formatter, fatal compiler warnings, and the `sbt verify` gate.
- [x] Implement immutable domain models, codecs, typed errors, and 31 event variants.
- [x] Implement typed tools, immutable registration, normalized observations, and path screening.
- [x] Implement the model client port, scripted clients, SSE parser, indexed accumulator, and reasoning adapter.
- [x] Implement ReAct execution, bounded thinking retries, inline server tools, and structured output.
- [x] Resolve lifecycle and path review findings; obtain final independent approval for the scope recorded in the [core README](../../modules/core/README.md).
- [x] Verify 118 core tests after structured-output parity corrections.
- [x] Pin upstream `f98a685fdc4589f81e626e1686ac2c63d14a2304` and inventory 2,473 annotated core test declarations.
- [x] Map all 18 methods in the three structured-output suites to local coverage, adaptations, or explicit gaps.
- [x] Correct structured thinking/usage/history preservation and interruption-after-capture behavior; receive independent approval.
- [x] Classify all 119 message methods; close reviewed usage, strict decoding, Base64, and role-matrix defects.
- [x] Verify 169 core and 40 harness tests after the model corrections.
- [x] Implement 12 typed generation reasons and nine upstream message-reason scenarios; receive independent approval and verify 207 core / 40 harness tests.
- [x] Close unchecked Msg construction/update paths and all 26 factory scenarios; verify 242 core / 40 harness tests.
- [x] Unify all ten concrete/base content codecs, remove silent encoding fallbacks, and cover all 17 ToolUse methods plus strict states and canonical server-tool pass-through; verify 272 core / 40 harness tests.
- [x] Complete all seven DataBlock adaptations and both media codec contracts; verify 284 core / 40 harness tests and close all 119 message-method mapping entries with explicit differences.
- [x] Correct lifecycle correlation, private stream completion, and ordered draining; receive independent approval and verify 177 core / 40 harness tests.
- [x] Correct all 31 concrete/base event codecs and existing tool-result metadata serialization; classify 30 upstream event declarations and verify 185 core / 40 harness tests.
- [x] Implement and independently review all 31 protocol event identifiers and 11 aliases; verify 195 core / 40 harness tests.
- [x] Add all eight missing event payloads and expand concrete/base codec coverage to 39 variants; verify 291 core / 40 harness tests.
- [x] Publish correlated iteration-limit events and ordered inline text/data observations with full metadata; fix duplicate call/result pairing and cancellation reconciliation, and verify 300 core / 40 harness tests.
- [x] Publish finalized runtime results after structured preparation/history commit and expose streamStructuredEvents; obtain independent FP/invariant approval and verify 309 core / 40 harness tests.
- [x] Verify both runtime-schema/arbitrary-JSON direct and streaming scenarios from StructuredOutputDynamicDefineTest; full verification passed 311 core / 40 harness tests.
- [x] Classify all ten deprecated coarse-event methods as explicit legacy API/wire exclusions, with shared domain evidence distinguished from parity.
- [x] Correlate actual model streams with runtime-assigned reply/request identities.
- [ ] Complete richer subagent attribution through the typed API.
- [ ] Complete native structured tool returns and incremental execution, explicit event adapters, phase-stop integration, and remaining event ordering.
- [ ] Map every remaining applicable core method to Scala coverage, a justified difference, or a gap.
- [ ] Close remaining structured-output and lifecycle parity gaps; see the [parity task list](test-parity.md).
- [x] Replace silent completion-delta parsing with typed errors, strict indexes/counts, required choices, duplicate-field rejection, and provider-error handling; verify 326 core / 40 harness tests.
- [x] Require successful stream completion and complete unambiguous tool JSON; preserve raw arguments, indexed content/signatures, and usage snapshots; verify 345 core / 40 harness tests.
- [x] Serialize validated text/function requests, enforce complete history pairing, and independently review sixteen regressions; verify 361 core / 40 harness tests.
- [x] Implement scoped HTTP completion with bounded responses and strict [DONE] termination; verify fourteen transport tests and 375 core / 40 harness tests.
- [x] Integrate live LlmClient and correlated incremental events; verify eleven new scenarios and 386 core / 40 harness tests.
- [x] Correlate completed local/inline tool results, persist local observations before deltas/end, and independently review six regressions; verify 392 core / 40 harness tests.
- [x] Verify native/synthetic structured HTTP paths and reject incomplete/ambiguous structured JSON through shared pure validation; independently review twelve added tests and verify 404 core / 40 harness tests.
- [x] Implement typed structured-output capabilities, complete two pending upstream routing/default mappings, and correct preset-format leakage; independently review sixteen tests and verify 420 core / 40 harness tests.
- [x] Implement scoped visible-text transformation with a distinct failure channel, close the mapped streamed structured-text replacement scenario, independently review fourteen tests, and verify 434 core / 40 harness tests.
- [x] Implement scoped post-reasoning decisions and invocation accounting, close the last structured-output method mapping, correct legacy usage duplication, independently review twenty-two tests, and verify 456 core / 40 harness tests.
- [x] Add the scoped core authorization port, persist approved arguments by occurrence, normalize denial safely, close reasoning-metadata rewrite gaps, independently review fifteen tests, and verify 481 core / 45 harness tests.
- [ ] Implement incremental/structured local tool-result output, provider signature/tool metadata decoding, and remaining protocol scenarios.
- [ ] Validate platform-specific symlink/junction behavior.

### Phase 4: harness

- [x] Implement pair-safe compaction, summary deduplication, token estimation, and pruning.
- [x] Implement large-result previews and content-addressed local storage with explicit workspace trust.
- [x] Implement permission priorities, interactive promises, and serializable approval/resume values.
- [x] Resolve compactor/store review findings; obtain final independent approval for the scope recorded in the [harness README](../../modules/harness/README.md).
- [x] Verify 40 harness tests at revision `fb80428`.
- [ ] Complete exhaustive harness upstream scenario mapping and close its gaps.
- [x] Integrate immediate local-result eviction through a scoped processor; independently review fifteen new tests, map five of seven eviction methods with explicit adaptations, and verify 466 core / 45 harness tests.
- [x] Connect harness permission policy and interactive requests to ToolAuthorizer, including fresh dual-gate checks, atomic rule updates, and cancellation cleanup; verify sixteen added tests and 481 core / 61 harness tests.
- [x] Harden standalone compaction for repeated call occurrences, inline/local isolation, stale argument caches, and reasoning metadata; reproduce eight failures, obtain independent approval, and verify 481 core / 69 harness tests.
- [x] Integrate scoped compaction into canonical history before model requests, share occurrence validation, replace stale offsets with attempt-local recovery progress, independently review twenty added tests, and verify 497 core / 73 harness tests.
- [x] Process matched inline provider results by exact occurrence; preserve cleanup defects through cooperative/fiber/timeout cancellation; independently review fourteen added regressions and verify 510 core / 74 harness tests.
- [x] Install supplied-history eviction, store-backed preview recognition, and validated preparation composition; independently review twenty-two added tests and verify 516 core / 90 harness tests.
- [x] Add existing absolute-directory selection with canonical revalidation, map the upstream trailing-separator scenario, independently review eight new tests, and verify 516 core / 98 harness tests.
- [x] Add scoped request-only model-input projection, retain canonical eviction/recovery authority, map all seven eviction methods with explicit adaptations, independently review sixteen new tests, and verify 529 core / 101 harness tests.
- [x] Account for all seven compactor and ten compaction-middleware methods, strengthen exact routing and cancellation/session evidence, record explicit divergences and exclusions, independently review both suites, and verify 529 core / 114 harness tests.
- [x] Implement daily-memory extraction/store capability and compactor integration; close the serialization-test review finding, obtain final independent approval, map both upstream daily methods, and verify 529 core / 136 harness tests.
- [x] Implement scoped cooperative filesystem daily-memory storage; reproduce and fix cancellation-after-write reporting, independently review nineteen new tests, and verify 530 core / 154 harness tests.
- [x] Implement MemoryConsolidator and atomic in-memory daily/curated authority, enforce a 1–4,000 configured-counter budget, independently review twenty-one new tests, and verify 530 core / 175 harness tests.
- [x] Implement recoverable local consolidation under shared ownership; independently review 34 new persistence tests, map three local upstream adaptations, and verify 530 core / 209 harness tests.
- [x] Implement typed transcript observation projection and atomic in-memory store; independently review 22 new cases and verify 530 core / 231 harness tests.
- [x] Implement scoped local transcript persistence and shared ownership; reproduce/fix Unicode preview splitting, independently review 27 new cases and four local method adaptations, and verify 530 core / 258 harness tests.
- [x] Integrate required transcript turn completion with explicit identity policy; independently review 28 new cases and verify 552 core / 264 harness tests.
- [x] Implement typed memory trigger policies and explicit shared monotonic admission; independently review 23 new cases, local method adaptations and cleanup hardening, and verify 552 core / 287 harness tests.
- [x] Implement the bounded host-owned task queue, per-owner serialization and pending conversation coalescing; reproduce/fix masked-worker shutdown, independently review twenty new cases and verify 552 core / 307 harness tests.
- [x] Integrate completion-triggered background flush with pure eligibility, immutable snapshots, worker-side gates and explicit host receipts; map four upstream async-flush methods, independently review 17 new cases and verify 552 core / 324 harness tests.
- [x] Integrate owned consolidation scheduling, separately scoped observer composition, asynchronous gate behavior and all eight local throttle integration combinations; independently review 25 added cases and verify 555 core / 346 harness tests.
- [x] Implement explicit acknowledged-daily archive authority for in-memory/local repositories, with typed identities and conservative partial progress; independently review 24 new regressions and verify 555 core / 370 harness tests.
- [x] Integrate explicit calendar cutoff policy and acknowledged archival into one owned maintenance job; independently review 21 added scenarios and verify 555 core / 391 harness tests, including the existing consolidation-only regression suites.
- [x] Implement explicit local transcript pruning with writer-lease exclusion, complete-log validation, observation-aware recency and fixed-payload deletion; independently review thirteen added cases and verify 555 core / 404 harness tests.
- [x] Add explicit KeepAll/180-day transcript policy with one validated cutoff before ownership; independently review eight new regressions and verify 555 core / 412 harness tests.
- [x] Add explicit ordered target batches and integrate pruning into one owned maintenance job; independently review 22 new cases and verify 555 core / 434 harness tests.
- [x] Complete immutable memory configuration, typed prompt rendering and six remaining MemoryConfig adaptations; independently review 17 new cases and verify 555 core / 451 harness tests.
- [ ] Complete model-selection configuration and remote memory adapters where required.
- [ ] Implement remote memory/transcript storage, timestamp-watermark migration, and scheduling.
- [ ] Verify durable host persistence and resumption without a second model request.
- [ ] Provide a secure storage backend if untrusted workspace support is required; the current portable backend requires host-controlled ancestors.

### Subsequent platform work

- [x] Inventory fourteen upstream plan-mode methods and implement conservative static shell inspection; independently review ten cases and verify 555 core / 461 harness tests.
- [x] Integrate typed plan admission with strict inputs, actual runtime denials and confirmation rechecks; independently review fourteen new cases and verify 555 core / 475 harness tests.
- [x] Implement typed in-memory plan-session lifecycle with exact-draft host review, atomic waiter settlement and scoped cancellation; independently review eighteen new cases and verify 555 core / 493 harness tests.
- [x] Implement truthful request-only plan projection with unchanged canonical/System/tool history; independently review thirteen new cases and verify 555 core / 506 harness tests.
- [x] Integrate stored publication with the plan session without reviving stale approval after failure/cancellation.
- [x] Implement strict model-facing controls with scoped exact-ticket host review and actual ReAct approve/decline flows.
- [x] Add configured/observed location context, fourteen safety cases and four manager/prompt adaptations; verify 556 core / 563 harness tests.
- [x] Implement typed workspace-relative plan directories with canonical strict containment; independently review twelve new cases and verify 556 core / 575 harness tests.
- [x] Implement scoped task supervision and promotion; correct parallel forkIn cleanup, independently review eighteen cases and verify 556 core / 593 harness tests.
- [ ] Implement supervised child propagation.
- [x] Implement runtime-owned descendant depth <=3; independently review nine cases and verify 557 core / 601 harness tests.
- [ ] Implement child factories/registry before exposing spawn/send.
- [ ] Implement MCP protocol codecs, transports, discovery, and tool bridging.
- [ ] Validate live end-to-end behavior, cancellation, resource release, and operational controls.
- [ ] Complete release/API compatibility and production cutover acceptance.

### Delivery discipline

- [x] Maintain English per-directory READMEs and current architectural contracts.
- [x] Establish independent review, correction, explicit re-approval, README update, and closed-report removal as mandatory steps.
- [x] Preserve unresolved roadmap and parity work with unchecked tasks.
- [ ] Confirm remote CI execution for the next published revision; local verification does not establish this.

## Source and verification indexes

Package indexes enumerate actual source and spec files. Planned package documents mark their APIs and tests as targets.

- [Core module](../../modules/core/README.md)
- [Harness module](../../modules/harness/README.md)
- [MCP target module](../../modules/mcp/README.md)
- [Core agent tests](../../modules/core/src/test/scala/com/weisgnosis/agents/core/agent/README.md)
- [Compaction tests](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/memory/README.md)
- [Eviction and storage tests](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/eviction/README.md)
- [Permission tests](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/permission/README.md)

## Invariant verification

[AGENTS.md](../../AGENTS.md) owns invariant numbering and engineering rules.

| Invariant | Current evidence and limits |
| --- | --- |
| INV-1: Pre-acting persistence | ReAct tool tests inspect history before execution |
| INV-2: Interruption reconciliation | Interruption/lifecycle regressions preserve completed results and recover unfinished local calls |
| INV-3: Thinking-only loopback | Thinking retry suite covers reminders and retry bounds |
| INV-4: Safe compaction cutoff | Compactor tests cover pairs, repeated call IDs, orphan results and protected tails |
| INV-5: Summary deduplication | Compactor tests cover repeated summaries and retained-user provenance |
| INV-6: Tool eviction | Standalone and runtime suites cover 80k/2k limits, persistence fallback, raw-result retention on cancellation, parallel slot isolation, and actual disk storage |
| INV-7: Permission priority | Gate tests and installed interactive adapter cover Deny > Ask > Passthrough > Allow, fresh modified-input checks, and latest-policy denial |
| INV-8: Subagent supervision | Scoped local execution/promotion reviewed; runtime-owned depth reviewed; registry and actual spawn/send integration pending |
| INV-9: Plan mode hardening | Static grammar and opt-in runtime permission admission reviewed; in-memory review lifecycle, request projection and standalone draft storage are available; stored-session publication is available; model controls are available; location context is available; typed workspace-relative directories are available; child propagation remains pending |
| INV-10: Path safety | Canonical paths and parent traversal are tested; privileged symlink creation was unavailable on this host. The portable store requires host-controlled ancestors |
| INV-11: Structured output | Schema hoisting, native/synthetic selection and capture tests; broader parity gaps remain |
| INV-12: Indexed stream assembly | Interleaved deltas and interrupted partial JSON tests |

## Related contracts

- [High-level design](high-level-design.md)
- [Mid-level design](mid-level-design.md)
- [Low-level design](low-level-design.md)
- [Phase 4 contracts](phase-4-implementation.md)
- [Test parity audit](test-parity.md)

Research references describe longer-term targets: [porting guide](../deep-research-and-porting-guide.md) and [system architecture research](../weisgnosis-os-and-agents-architecture.md).

- [x] Verify and independently approve standalone plan-draft storage, explicit scoped ownership and atomic publication; fifteen new cases, 556 core / 520 harness tests.
- [x] Bind stored draft publication to session generations without stale approval on failure/cancellation; fourteen new cases and independent technical approval.


## Paused handoff: 2026-09-29

The user explicitly requested a pause for several days. Do not resume implementation automatically. Resume only when the user asks. This is the current handoff, not a completed-review report; update or remove this section when work resumes.

- [x] Last accepted implementation: f5bc9a0d9c46ad6b162315ba760b98b1ce89276a on main, runtime-owned descendant lineage and depth.
- [x] Last full verification: scalafmtAll, scalafmtSbt and verify passed 557 core / 601 harness tests (1,158 total), with zero compiler warnings. Local evidence: subagent-lineage-final-verify.log (ignored build log).
- [x] FP, invariant and documentation reviewers approved that committed scope. Current accepted identities are in the affected package READMEs. All 63 Markdown files passed local-link/script checks.
- [x] No task-started sbt build remains running at the checkpoint; unrelated IDE processes are not stopped.
- [x] review_docs independently approved this documentation-only pause checkpoint, verified the draft backup and reproduced all source-volume measurements.
- [ ] Finish the two untracked draft sources below. They have NOT been formatted, compiled, tested or accepted by final source review. Preserve them locally; do not discard them with git clean. They are intentionally excluded from the accepted implementation commit and from code-volume measurements.
- [ ] Add paired tests, run full verification, obtain renewed FP/invariant/documentation reviews, update current contracts and commit the factory increment only after those gates pass.

| Untracked draft | Canonical Git blob identity at pause |
| --- | --- |
| [SubagentFactory.scala](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/SubagentFactory.scala) | 6d5d813dc0afcb28733e5afde1f60eb2cd1e55df |
| [SubagentToolBoundary.scala](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/SubagentToolBoundary.scala) | c2b97546952b24efe3adcfbf005d399790ba41ad |

The drafts remain untracked in the working tree and are also preserved in the local Git stash commit 45f2275f3d29cb825964913d9bb4f2b7db6186dd, named pause checkpoint: unreviewed child factory drafts after f5bc9a0. The backup contains only those two draft files; it is not an accepted implementation commit and has not been pushed. Keep this stash until the resumed scope is safely accepted/committed. If a draft is missing, inspect its backup with git show using that stash commit followed by ^3: and the repository-relative file path. Do not apply a stash over newer edits. The draft links require this local workspace; the files are not tracked on main. The draft implements a proposed final tool filter and a scoped child factory. SubagentToolSelection distinguishes AllAvailable from Only(empty). Boundary narrowing intersects selections and unions exact-name denials. Assembly validates Toolkit key/definition-name consistency, merges with collision rejection, then filters the complete inherited plus construction-registered toolkit. Factory build suspends registration/client callbacks, preserves the injected ToolAuthorizer and extends resources into the caller Scope. Factory spawn uses SubagentSpawner so build and execution share the supervised task Scope. No tests for these two drafts exist yet.

### Resume this exact next scope

- [ ] Read AGENTS.md, this checkpoint and the current subagent/plan contracts before editing. Inspect git status; preserve unrelated user changes.
- [ ] Re-read pinned upstream ManagedSubagentBoundaryTest at C:/workspace/agentscope-java, revision f98a685fdc4589f81e626e1686ac2c63d14a2304. Its two methods are declarationFiltersAutoRegisteredToolsAsWellAsInheritedTools and childCannotReenableParentDeniedTool.
- [ ] Verify final filtering AFTER all registrations and BEFORE ReActAgent.make. Parent allowlists/denials and child selection/denials constrain both advertised schemas and actual dispatch, with no plan/task/platform exemptions.
- [ ] Verify AllAvailable versus Only(empty), parent toolkit immutability, narrowing across factory derivatives, collisions even on excluded names, and key/name mismatches. Make diagnostic ordering deterministic; current Toolkit merge iteration may require attention.
- [ ] Verify an actual built child receives only the selected model tool definitions and cannot execute a hallucinated excluded tool, including a parent-denied tool selected by the child.
- [ ] Verify the supplied authorizer is preserved, registration/client resources close on cancellation/failure, assembly failure precedes client acquisition, and factory execution actually goes through SubagentSpawner.
- [ ] Decide whether inherited Toolkit consistency should also be validated at make; the current draft defers assembly checks to build. Do not close a resource Scope inside build and return a runtime whose dependencies have already closed.
- [ ] Record the two upstream methods as explicit adaptations only after actual factory tests and independent review. The primitive factory boundary does NOT automatically propagate through raw SubagentSpawner capabilities: trusted host callbacks can construct another factory/task. Future model-facing nested spawning must retain the narrowed factory capability.
- [ ] Keep dynamic argument-sensitive parent Deny inheritance, child Allow/Ask preservation, plan propagation, persistent reuse/send, registry and event forwarding as separate unfinished integration. Static exact-name filtering is not PermissionEngine inheritance.

Design feedback was received from review_fp and review_harness, but it is not final implementation approval. Reuse those agents if available, or request fresh independent reviewers with this scope and evidence.

### Environment and verified lifecycle constraints

Workspace: C:/workspace/weisgnosis-agents; branch main. Upstream reference: C:/workspace/agentscope-java. Build launcher: C:/tools/sbt/bin/sbt.bat. JDK: Microsoft OpenJDK 21.0.11. Scala 3.9.0 and ZIO 2.1.26 remain pinned. Follow AGENTS.md; all repository text is English and all progress tasks use checkboxes.

The configured proxy was unavailable during this session, and Windows commit pressure prevented JVM startup with the normal heap. The following process-local override completed full checks without changing repository JVM settings. Run one sbt process at a time:

```powershell
$env:JAVA_TOOL_OPTIONS='-Xms128m -Xmx768m -Xss2m -XX:ActiveProcessorCount=4'
& C:\tools\sbt\bin\sbt.bat --mem 768 '-Dhttp.nonProxyHosts=*' '-Dhttps.nonProxyHosts=*' scalafmtAll scalafmtSbt verify
```

TaskSupervisor already contains a reproduced and fixed ZIO 2.1.26 forkIn self-join issue. Keep the outer owner Scope parallel and each worker scope sequential, with explicit worker-owner completion closure. Directly forking into the parallel owner caused immediate-completion tasks to hang. Cancellation and full-Exit tests cover the corrected shape. Each awaitOrPromote invocation deliberately starts its own 30-second wait; this is not a global timer from attachment.

Current plan inventory remains eight adapted / six pending methods. Runtime depth is zero for host roots and one through three for descendants; depth four rejects before UUID allocation/callback invocation. Root distribution and retained spawner capabilities remain trusted-host responsibilities.

### Measured checkpoint

At accepted implementation f5bc9a0, tracked Scala contains 115 production files (11,245 physical lines) and 157 test files (28,299 physical lines). Excluding blank/comment-only lines with the Scala lexer gives 8,069 production and 25,235 test lines. There are 63 Markdown files. These counts exclude the two untracked drafts and build/generated outputs.

The original 2,300-3,300 production / 2,800-4,300 test SLOC projection was materially too optimistic. Current production is already about 2.4-3.5 times that estimate and tests about 5.9-9.0 times, before the remaining platform work. Detailed state machines, strict protocol/path validation, local recovery/ownership and meaningful cancellation/parity tests account for much of the added scope. No reliable calendar completion estimate is established by this checkpoint. See the corrected [measurement reference](../deep-research-and-porting-guide.md#1-measured-codebase-and-estimate-correction).

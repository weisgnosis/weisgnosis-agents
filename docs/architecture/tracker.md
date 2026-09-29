# Architecture implementation tracker

Last synchronized: 2026-09-29. Implemented components are distinguished from host integration and exhaustive upstream parity. Passing local tests does not establish either broader claim.

## Progress checklist

Checked items have verification evidence. An unchecked item is unfinished; completed components do not imply completion of their entire phase. Next task: integrate pair-safe compaction into canonical runtime history with cancellation-safe progress tracking, then extend eviction to supplied history/inline results and expand remaining lifecycle/hook parity against the pinned upstream revision. Local-result eviction is now installed through an opt-in processor.

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
- [ ] Integrate compaction into canonical agent state before model invocation.
- [ ] Extend eviction to supplied history, separate model-input views, and inline provider results; close the two remaining eviction method gaps.
- [ ] Implement MemoryFlushManager and MemoryConsolidator with the specified writer isolation and budgets.
- [ ] Verify durable host persistence and resumption without a second model request.
- [ ] Provide a secure storage backend if untrusted workspace support is required; the current portable backend requires host-controlled ancestors.

### Subsequent platform work

- [ ] Implement plan-mode restrictions, shell inspection, and propagation.
- [ ] Implement subagent depth bounds, scoped supervision, and timeout promotion.
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
| INV-8: Subagent supervision | Planned |
| INV-9: Plan mode hardening | Planned |
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

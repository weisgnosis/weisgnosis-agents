# Architecture implementation tracker

Last synchronized: 2026-09-29. Implemented components are distinguished from host integration and exhaustive upstream parity. Passing local tests does not establish either broader claim.

## Progress checklist

Checked items have verification evidence. An unchecked item is unfinished; completed components do not imply completion of their entire phase. Next task: pin the upstream revision and build the exhaustive core parity mapping.

### Foundation and current core

- [x] Configure Scala 3.9.0, ZIO 2, formatter, fatal compiler warnings, and the `sbt verify` gate.
- [x] Implement immutable domain models, codecs, typed errors, and 31 event variants.
- [x] Implement typed tools, immutable registration, normalized observations, and path screening.
- [x] Implement the model client port, scripted clients, SSE parser, indexed accumulator, and reasoning adapter.
- [x] Implement ReAct execution, bounded thinking retries, inline server tools, and structured output.
- [x] Resolve lifecycle and path review findings; obtain final independent approval for the scope recorded in the [core README](../../modules/core/README.md).
- [x] Verify 108 core tests at revision `fb80428`.
- [ ] Pin the upstream revision and map every applicable core test to Scala coverage, a justified difference, or a gap.
- [ ] Close remaining structured-output and lifecycle parity gaps; see the [parity task list](test-parity.md).
- [ ] Implement live HTTP transport and protocol integration tests.
- [ ] Validate platform-specific symlink/junction behavior.

### Phase 4: harness

- [x] Implement pair-safe compaction, summary deduplication, token estimation, and pruning.
- [x] Implement large-result previews and content-addressed local storage with explicit workspace trust.
- [x] Implement permission priorities, interactive promises, and serializable approval/resume values.
- [x] Resolve compactor/store review findings; obtain final independent approval for the scope recorded in the [harness README](../../modules/harness/README.md).
- [x] Verify 40 harness tests at revision `fb80428`.
- [ ] Complete exhaustive harness upstream scenario mapping and close its gaps.
- [ ] Integrate compaction, eviction, and permission gates into agent execution.
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
| INV-6: Tool eviction | Evictor tests cover the 80k threshold, 2k previews and persistence failure fallback |
| INV-7: Permission priority | Gate tests cover Deny > Ask > Passthrough > Allow |
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

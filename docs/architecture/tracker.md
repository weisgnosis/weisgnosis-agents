# Architecture implementation tracker

Last synchronized: 2026-09-29. Implemented components are distinguished from host integration and exhaustive upstream parity. Passing local tests does not establish either broader claim.

## Delivery status

| Area | Available now | Remaining work |
| --- | --- | --- |
| Foundation | Scala 3.9.0, ZIO 2, formatter, fatal warnings, CI verification | Semantic FP and English checks require review |
| Core domain | Immutable models, codecs, typed errors, 31 event variants | Exhaustive upstream scenario mapping |
| Tool execution | Typed tools, immutable registry, normalized observations, canonical path checks | Platform-specific hostile-workspace sandboxing |
| Model client | LlmClient port, scripted clients, SSE parser, indexed stream accumulator, reasoning adapter | Live HTTP transport and provider integration tests |
| ReAct runtime | Stateful history, sequential/parallel tools, cancellation reconciliation, bounded thinking retries, inline server tools, scoped events | Full upstream parity audit and harness middleware integration |
| Structured output | Native request format, synthetic capture, root definition hoisting, metadata decoding | Additional upstream edge cases listed in parity audit |
| Context compaction | Token counter, safe cutoff, pruning, summary deduplication, retained-user provenance | MemoryFlushManager, MemoryConsolidator, production summarizer |
| Result eviction | Large-output previews, content-addressed local store, explicit workspace trust | Secure backend for untrusted workspaces; host installation |
| Permission policy | Gate priority, interactive promises, serializable suspension/resume values | Durable persistence and agent/workflow integration |
| Subagents and plan mode | Target design only | Supervision, timeout promotion, depth bounds, propagation, shell inspection |
| MCP | Target design only | Protocol codecs, transports, client, tool bridge |
| Production cutover | Research and target architecture | Live end-to-end validation, deployment and operational controls |

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
- [Independent review record](../reviews/2026-09-29-phase-3-4.md)

Research references describe longer-term targets: [porting guide](../deep-research-and-porting-guide.md) and [system architecture research](../weisgnosis-os-and-agents-architecture.md).

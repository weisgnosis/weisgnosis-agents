# weisgnosis-agents-harness

Standalone context and permission components built on core.

| Package | Current status |
| --- | --- |
| [memory](src/main/scala/com/weisgnosis/agents/harness/memory/README.md) | Pair-safe compaction, summary/user provenance, token estimation and daily extraction/local storage and atomic in-memory and recoverable local consolidation |
| [transcript](src/main/scala/com/weisgnosis/agents/harness/transcript/README.md) | Typed observation projection, atomic in-memory linkage, and strict scoped local persistence; remote segments pending |
| [storage](src/main/scala/com/weisgnosis/agents/harness/storage/README.md) | Internal cooperative scoped ownership shared by memory and transcripts |
| [eviction](src/main/scala/com/weisgnosis/agents/harness/eviction/README.md) | Above-80k eviction, 2k head/tail previews, explicit-trust local store |
| [permission](src/main/scala/com/weisgnosis/agents/harness/permission/README.md) | Immutable decisions, Promise coordination, serializable durable approval values |
| [subagent](src/main/scala/com/weisgnosis/agents/harness/subagent/README.md) | Scoped execution/promotion, bounded lineage, configured factories, live parent-denial inheritance and bounded task registry; model-facing tools pending |
| [plan](src/main/scala/com/weisgnosis/agents/harness/plan/README.md) | Static command inspection, opt-in permission admission and in-memory reviewed sessions, request projection and standalone draft storage; typed workspace-relative directories available; child propagation pending |

MemoryFlushManager supplies daily extraction through an injected DailyMemoryStore and can be installed as the compactor flush callback. LocalDailyMemoryStore supplies a scoped backend for host-controlled workspaces with exclusive cooperative ownership and atomic daily replacement. MemoryConsolidator enforces its counter budget before an atomic content/progress commit; MemoryRepository.inMemory implements shared daily/consolidation authority. LocalMemoryRepository supplies the daily/consolidation ports with shared ownership and journal recovery before each operation. Transcript recording can be installed through TurnCompletionObserver with an explicit anonymous-message policy. Completion-triggered flush scheduling is available through MemoryFlushScheduler. MemoryConsolidationScheduler adds opt-in owner-coalesced background consolidation with explicit result reporting. Explicit acknowledged-daily archival is available through MemoryRepository.retention. Explicit selected-target transcript pruning and configured maintenance are available; session discovery, remote memory/transcript storage, general middleware and durable workflow storage remain pending. Hosts choose a memory store and supply summarization/offload effects and authenticated approval routing.

Local storage requires WorkspaceTrust.HostControlled and protected filesystem ancestors. Tool-result publication requires hard-link support; memory/transcript publication requires atomic-move support. It is not a sandbox for untrusted workspaces. See [implementation notes](../../docs/architecture/phase-4-implementation.md) and the [tracker](../../docs/architecture/tracker.md).

## Current review acceptance

- [x] `path_review` approved configured child construction, final static filtering and bounded registry/polling; `current_docs` approved live parent-denial inheritance for FP/ZIO correctness and relevant invariants. Exact source/test identities and scope are in the [subagent README](src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#verification-and-acceptance).
- [x] Full `scalafmtAll scalafmtSbt verify` passed on 2026-09-30: 561 core / 618 harness tests, zero compiler warnings or failures (`registry-policy-verify.log`).
- [x] Existing memory, transcript, storage, eviction, permission, plan and supervision contracts retain their scoped independent acceptance in the package READMEs above.
- [ ] Implement model-facing spawn/inspect/cancel tools through the retained factory capability, followed by live plan propagation and persistent child reuse.

Configured factories filter both inherited and automatically registered tools before client/runtime construction, preserve the supplied authorizer and retain resources in the caller or supervised worker Scope. The opt-in inherited-denial wrapper reads current parent policy before child authorization and against approved arguments, while preserving the child policy. The scoped registry bounds retained record count, keeps full Exit results, and joins attached/promoted task cleanup at registry closure. External parent gates, atomic approval revocation, live plan propagation and persistent reuse remain outside these contracts. Trusted hosts control retained spawner/factory capabilities.

Approval covers recorded revisions and named contracts. Remaining host integration and parity work stays visible in the [tracker](../../docs/architecture/tracker.md).

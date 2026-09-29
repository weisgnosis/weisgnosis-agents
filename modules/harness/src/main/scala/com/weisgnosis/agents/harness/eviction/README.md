# Large tool result offloading

ToolResultEvictor offloads text exceeding 80,000 characters with 2,000-character head and tail previews, a content-addressed key, and read_file instructions. Excluded tools bypass eviction. Failed persistence preserves the original observation; cancellation propagates. ToolResultStore.local(workspace, WorkspaceTrust.HostControlled) requires OS protection against untrusted mutation of the directory and its ancestors. WorkspaceTrust.Untrusted is rejected. A fully written temporary file is published using an atomic hard link, requiring filesystem hard-link support. Cleanup failures are logged without overriding persistence success. This backend is not a sandbox for attacker-writable workspaces.

## Implementation

- [EvictionConfig](EvictionConfig.scala)
- [ToolResultEvictor](ToolResultEvictor.scala)
- [ToolResultStore](ToolResultStore.scala)

See the [repository architecture](../../../../../../../../../../ARCHITECTURE.md) for integration boundaries and invariant definitions.

## Runtime integration

ToolResultEvictor.evictResult processes one immutable observation with the same thresholds, exclusions, and persistence fallback as evictOversizedToolResults. processor(config) adapts it to the core ToolResultProcessor port; pass that value to ReActAgent.make or its builder. Raw completed output is persisted in runtime history before the cancellable storage operation. Successful previews and cache metadata replace that observation before model continuation and result deltas/end. Cancellation preserves raw success without duplicate interruption observations.

The adapter covers new local tool completions and matched inline provider observations. Inline results remain within the assistant message, and returned replies/events use the committed preview. Standalone history eviction remains available, but automatic eviction of supplied history is not installed. Storage paths and cache metadata follow the Scala store contract: a content-addressed filename under large_tool_results, with eviction_cache_key/eviction_path. This differs from the upstream per-agent subdirectory and middleware metadata. The host must provide read_file access if the model is expected to retrieve stored output.

## Current acceptance

- [x] Unchanged local store safety/publication contracts retain earlier acceptance through `8850a60`; Git preserves prior source identities and reviews.
- [x] `review_fp` and `review_harness` approved the final eight-file local-result processing/eviction increment against `8850a60` on 2026-09-29. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 466 core and 45 harness tests.
- [ ] Integrate supplied history and separate model-input views.
- [ ] Complete remaining upstream mappings, including hook-modified reasoning input and configurable absolute eviction directories.

HostControlled ownership and hard-link support remain required. Hostile concurrent filesystem mutation and crash durability remain outside acceptance.

The [core tool table](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/tool/README.md) records unchanged processor/eviction identities; commit `a34201f` preserves the full eight-file scope.

- [x] `review_fp` and `review_harness` approved the final inline-processing and cancellation-cleanup scope against `2b99226` on 2026-09-29. Fourteen added regressions and the full formatting/fatal-warning verification passed: 510 core and 74 harness tests. No findings remain in this scope; supplied-history eviction and exhaustive upstream middleware parity remain pending. Exact current identities are in the [agent table](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md).

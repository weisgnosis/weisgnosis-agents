# Large tool result offloading

ToolResultEvictor offloads text exceeding 80,000 characters with 2,000-character head and tail previews, a content-addressed key, and read_file instructions. Excluded tools bypass eviction. Failed persistence preserves the original observation; cancellation propagates. ToolResultStore.local(workspace, WorkspaceTrust.HostControlled) requires OS protection against untrusted mutation of the directory and its ancestors. WorkspaceTrust.Untrusted is rejected. A fully written temporary file is published using an atomic hard link, requiring filesystem hard-link support. Cleanup failures are logged without overriding persistence success. This backend is not a sandbox for attacker-writable workspaces.

## Implementation

- [EvictionConfig](EvictionConfig.scala)
- [ToolResultEvictor](ToolResultEvictor.scala)
- [ToolResultStore](ToolResultStore.scala)

See the [repository architecture](../../../../../../../../../../ARCHITECTURE.md) for integration boundaries and invariant definitions.

## Runtime integration

ToolResultEvictor.evictResult processes one immutable observation with the same thresholds, exclusions, and persistence fallback as evictOversizedToolResults. processor(config) adapts it to the core ToolResultProcessor port; pass that value to ReActAgent.make or its builder. Raw completed output is persisted in runtime history before the cancellable storage operation. Successful previews and cache metadata replace that observation before model continuation and result deltas/end. Cancellation preserves raw success without duplicate interruption observations.

The adapter covers new local tool completions and matched inline provider observations. Inline results remain within the assistant message, and returned replies/events use the committed preview. ToolResultEvictor.preparer installs eviction of supplied and accumulated history before each model request. Storage paths and cache metadata follow the Scala store contract: a content-addressed filename under workspace/large_tool_results by default or directly inside the selected absolute directory, with eviction_cache_key/eviction_path. This differs from the upstream per-agent subdirectory and middleware metadata. The host must provide read_file access if the model is expected to retrieve stored output.

## Current acceptance

- [x] Unchanged local store safety/publication contracts retain earlier acceptance through `8850a60`; Git preserves prior source identities and reviews.
- [x] `review_fp` and `review_harness` approved the final eight-file local-result processing/eviction increment against `8850a60` on 2026-09-29. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 466 core and 45 harness tests.
- [x] Integrate validated request-only model-input views through ModelInputTransform.
- [x] Map hook-modified reasoning input while retaining canonical eviction; all seven eviction methods now have explicit adaptations.

HostControlled ownership and hard-link support remain required. Hostile concurrent filesystem mutation and crash durability remain outside acceptance.

The [core tool table](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/tool/README.md) records unchanged processor/eviction identities; commit `a34201f` preserves the full eight-file scope.

- [x] `review_fp` and `review_harness` approved the final inline-processing and cancellation-cleanup scope against `2b99226` on 2026-09-29. Fourteen added regressions and the full formatting/fatal-warning verification passed: 510 core and 74 harness tests. No findings remain in this scope. Supplied-history eviction is covered by a later scope; exhaustive upstream middleware parity remains pending. Exact current identities are in the [agent table](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md).

## Supplied history and preview integrity

Install `evictor.preparer(config)` through `ReActAgent.make(historyPreparer = ...)` or `Builder.historyPreparer`. It visits supplied and accumulated tool observations, including inline provider results, before every model request without replaying tool execution events. To save complete payloads before historical pruning, compose `evictor.preparer(config).andThen(compactor.preparer)`. Each changed stage preserves ordered system messages and complete occurrence pairing before the next stage runs. Stage resources share the request-owned scope; runtime state changes only after the full pipeline and cleanup succeed. Completed storage/summarization side effects are not rolled back if a later stage fails.

`ToolResultStore.resolve(key)` returns an optional `StoredToolResult(content, path)` from the trusted backend. The evictor first checks marker version, strict integer widths, the configured key prefix, and lowercase SHA-256 syntax. It then verifies the stored content hash, authoritative path, and exact preview text before recognizing an existing eviction. Metadata paths never select files. Numeric strings, fractional/overflowing widths, altered text, and malformed markers do not establish recognition.

Verified previews are rendered from the original stored content with the current normalized head/tail settings without another write, even when the preview exceeds a small eviction threshold. Raising the threshold does not rehydrate raw output. Newly excluded tools bypass all processing and retain the exact observation. State, nontext blocks, and unrelated metadata remain unchanged. New previews add eviction_preview_version=1, eviction_preview_head_chars, and eviction_preview_tail_chars alongside eviction_cache_key/eviction_path.

Recognition requires valid backing content and the configured sanitized/truncated agent/call key prefix. Changed prefixes, legacy previews without versioned hints, missing/corrupt backing content, and mismatched preview data become raw candidates and may be evicted again when oversized. Prefixes can collide; content hashes and rendering checks provide integrity, not unique identity isolation or authenticated provenance. Typed lookup/persistence failures retain the complete input. Interruption and defects propagate. Each recognition reads the original stored payload; no process-local receipt cache is maintained.

The local resolver validates the key before filesystem access, creates no directory, rejects nonregular/symbolic-link targets with NOFOLLOW_LINKS, and checks canonical parent paths. Only genuinely missing storage returns None; invalid layouts and operational errors remain typed failures. HostControlled still requires protected ancestors and excludes concurrent hostile mutation. Dynamic symbolic-link/junction validation is not claimed.

## API migration

Custom ToolResultStore implementations must now implement resolve as well as persist and return their authoritative path with the complete original content. There is no default missing lookup that would silently weaken idempotency. Existing ToolResultEvictor constructors remain pure and source-compatible. StoredToolResult has a derived JSON codec; serialized values alone do not establish trust. HistoryPreparer.andThen is additive and validates intermediate as well as final stage output.

- [x] `review_fp` and `review_harness` approved the final nine-file history-eviction scope against `cc8fa7b` on 2026-09-29. Twenty-two added regressions and full formatting/fatal-warning verification passed: 516 core and 90 harness tests. Two new regressions exposed and closed strict-number and Windows storage-layout gaps. No findings remain within store-backed integrity, explicit workspace trust, and validated composition; absolute storage is covered by a later scope; request-only input views are covered by a later scope.

## Current history-eviction identities

Unchanged identities from the scope against `cc8fa7b`; commit `3718e18` preserves all nine original identities. The changed ToolResultStore identity belongs to the location scope below; changed preparation/history-test identities belong to the agent model-input table linked at the end:

| File | Git blob |
| --- | --- |
| [HistoryPreparerSpec.scala](../../../../../../../../../core/src/test/scala/com/weisgnosis/agents/core/agent/HistoryPreparerSpec.scala) | 087459eab0ad56b15ee3c53fe81667505bff6a71 |
| [ToolResultEvictor.scala](ToolResultEvictor.scala) | 9821483cf3f617f55ffa3487df357c4967365f20 |
| [ToolResultEvictorIntegrationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/eviction/ToolResultEvictorIntegrationSpec.scala) | [Current ToolResultEvictorIntegrationSpec.scala identity](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#checked-registry-identity) |
| [ToolResultEvictorRecognitionSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/eviction/ToolResultEvictorRecognitionSpec.scala) | 4c81fae29456c429589d6d8f646ac8f6d2ce96c0 |
| [ToolResultEvictorSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/eviction/ToolResultEvictorSpec.scala) | c59677db41b87de222575f446a5d6212be87668a |
| [ToolResultStoreSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/eviction/ToolResultStoreSpec.scala) | 5b8bf8b5d152f9741dcc4339430a9eacf74983d2 |

## Storage location selection

`ToolResultStore.local(workspace, trust, location)` now accepts a ResultStorageLocation, defaulting to WorkspaceDefault. The existing two-argument call retains lazy creation of workspace/large_tool_results. AbsoluteDirectory(path) selects an already-existing dedicated directory on the current host platform and does not access an unused workspace. Path parsing is suspended in ZIO; trailing separators follow platform Path parsing without trimming meaningful spaces. No directory is created for the absolute mode.

Absolute locations reject relative/drive-relative/root-relative paths, parent traversal before normalization, sensitive lexical/canonical names, filesystem roots, regular files, and a symbolic link in the selected final component. A missing directory is a typed storage failure. Invalid syntax/nonabsolute configuration uses InvalidConfiguration; path-policy violations use PermissionDenied. Untrusted fails before path parsing or filesystem access.

The backend retains and revalidates the canonical directory before each read/write. This checks its canonical location and directory type, not filesystem object identity. Parent links and concurrent mutation still depend on HostControlled protection of the selected directory and every ancestor, including locations outside the workspace. Absolute writes use the same scoped temporary files, atomic hard-link publication, key validation, collision checks, and original-content lookup. Returned paths and previews use the store's canonical path. The host must authorize read_file access to the chosen external location.

Configuration ownership deliberately belongs to the storage backend rather than EvictionConfig. ResultStorageLocation uses a derived JSON codec; decoded configuration is validated when constructing the store. This additive parameter preserves existing callers. The absolute-directory behavior adapts the upstream trailing-separator scenario, with Scala's flat agent/call/hash filenames and no Java RuntimeContext identity assertion.

- [x] `review_fp` and `review_harness` approved the final three-file storage-location scope against `3718e18` on 2026-09-29. Eight added tests and full formatting/fatal-warning verification passed: 516 core and 98 harness tests. No findings remain within explicit host ownership and canonical-location validation. Dynamic symlink/junction checks, hostile mutation protection, and filesystem object identity are not claimed.

## Current storage-location identities

Final formatted scope against `3718e18`:

| File | Git blob |
| --- | --- |
| [ResultStorageLocation.scala](ResultStorageLocation.scala) | efbdf1b6a7065acf9a9d4e7fa5bf7814e744de07 |
| [ToolResultStore.scala](ToolResultStore.scala) | 03ca61724459ce839c3bb5ae62011bd99bb8f950 |
| [ResultStorageLocationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/eviction/ResultStorageLocationSpec.scala) | 5eeec2f1e5d2af688336047e4683582a96aec5ca |

Superseded model-input scope identities remain in `40f6f05`; changed identities are in the [agent model-input table](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md).

- [x] `review_fp` and `review_harness` approved the final seven-file model-input scope against `40f6f05` on 2026-09-29. Sixteen added regressions and full formatting/fatal-warning verification passed: 529 core and 101 harness tests. No findings remain within request-only projection, structural validation, canonical-state isolation, and eviction integration. Broader legacy hook/middleware parity remains pending. Exact identities and migration are in the [agent contract](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md).

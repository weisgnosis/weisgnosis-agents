# Large tool result offloading verification

From the repository root, run:

```text
sbt "harness / testOnly com.weisgnosis.agents.harness.eviction.*"
```

| Suite | Verified contracts |
| --- | --- |
| [ToolResultEvictorSpec](ToolResultEvictorSpec.scala) | Thresholds, exclusions, previews and metadata preservation. |
| [ToolResultEvictorIntegrationSpec](ToolResultEvictorIntegrationSpec.scala) | Immediate runtime history, model input and events; inline provider results without local dispatch; actual default-directory storage under explicit trust; persistence failure, cancellation and reused call IDs. |
| [ToolResultEvictorHistorySpec](ToolResultEvictorHistorySpec.scala) | Supplied local/inline observations, eviction before compaction, canonical/request-view isolation and persistence failure/cancellation before dispatch. |
| [ToolResultEvictorRecognitionSpec](ToolResultEvictorRecognitionSpec.scala) | Fresh-instance/JSON recognition, historical split-surrogate preview repair, changed windows, malformed/forged hints, missing/corrupt content and lookup failure/interruption. |
| [ToolResultStoreSpec](ToolResultStoreSpec.scala) | Canonical storage and lookup, atomic concurrent publication/collisions, path validation and explicit workspace trust. |
| [ResultStorageLocationSpec](ResultStorageLocationSpec.scala) | Pure location codecs, absolute/trailing-separator configuration, invalid or untrusted locations, concurrent publication, removed/replaced directories and lazy default storage. |

[EvictionTestSupport](EvictionTestSupport.scala) owns the scoped temporary-directory fixture. Toolkit construction follows the [checked registry identity contract](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#checked-registry-identity).

Strict hint validation includes numeric strings, fractional widths and integer overflow. Storage failure must preserve the original observation; cancellation must not authorize tool re-execution. These suites use scripted model responses and owned local files, not real provider requests or hostile filesystem mutation.

The [eviction API contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/eviction/README.md) describes supported paths, metadata, trust and runtime exclusions. The [tracker](../../../../../../../../../../docs/architecture/tracker.md) is the sole current completion checklist; passing these suites alone does not establish exhaustive upstream parity.

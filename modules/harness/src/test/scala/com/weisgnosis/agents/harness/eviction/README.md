# Large tool result offloading verification

- [ToolResultEvictorSpec](ToolResultEvictorSpec.scala)
- [ToolResultEvictorIntegrationSpec](ToolResultEvictorIntegrationSpec.scala)
- [ToolResultStoreSpec](ToolResultStoreSpec.scala)
- [EvictionTestSupport](EvictionTestSupport.scala)

Existing suites verify thresholds/exclusions, state/metadata preservation, reused IDs, placeholder-prefix handling, persistence failure/interruption, canonical storage, atomic publication, and explicit workspace trust. Five new integration tests verify runtime history/model input/events, actual default-directory disk content, persistence fallback, cancellation without re-execution, and separate cache entries for reused IDs. EvictionTestSupport extracts the unchanged scoped temporary-directory fixture from ToolResultStoreSpec.

- [x] `review_fp` and `review_harness` approved the final eight-file local-result processing/eviction increment against `8850a60` on 2026-09-29. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 466 core and 45 harness tests.
- [ ] Complete the remaining upstream and runtime integration gaps in the [parity audit](../../../../../../../../../../docs/architecture/test-parity.md).

Passing these suites does not establish middleware two-view equivalence or exhaustive harness parity. The [eviction contracts](../../../../../../../main/scala/com/weisgnosis/agents/harness/eviction/README.md) describe path/metadata adaptations and runtime exclusions.

The [core tool table](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/tool/README.md) records unchanged processor/eviction identities; commit `a34201f` preserves the full eight-file scope.

# Large tool result offloading verification

Toolkit construction and selection follow the [checked registry identity and migration contract](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#checked-registry-identity).

- [ToolResultEvictorSpec](ToolResultEvictorSpec.scala)
- [ToolResultEvictorIntegrationSpec](ToolResultEvictorIntegrationSpec.scala)
- [ToolResultStoreSpec](ToolResultStoreSpec.scala)
- [EvictionTestSupport](EvictionTestSupport.scala)

Existing suites verify thresholds/exclusions, state/metadata preservation, reused IDs, placeholder-prefix handling, persistence failure/interruption, canonical storage, atomic publication, and explicit workspace trust. Five new integration tests verify runtime history/model input/events, actual default-directory disk content, persistence fallback, cancellation without re-execution, and separate cache entries for reused IDs. EvictionTestSupport extracts the unchanged scoped temporary-directory fixture from ToolResultStoreSpec.

- [ ] Complete the remaining upstream and runtime integration gaps in the [parity audit](../../../../../../../../../../docs/architecture/test-parity.md).

Passing these suites does not establish middleware two-view equivalence or exhaustive harness parity. The [eviction contracts](../../../../../../../main/scala/com/weisgnosis/agents/harness/eviction/README.md) describe path/metadata adaptations and runtime exclusions.

The inline-provider integration regression verifies full 80,001-character storage, bounded head/tail preview, preserved server marker, processed canonical reply and event metadata, and zero local executions/Tool messages. It does not establish supplied-history eviction.

- [ToolResultEvictorRecognitionSpec](ToolResultEvictorRecognitionSpec.scala): nine scenarios for low-threshold repetition, fresh-instance/JSON reloads, changed windows, malformed/forged hints, missing/corrupt content, lookup failure/interruption, reused IDs/nontext blocks, and local canonical lookup.
- [ToolResultEvictorHistorySpec](ToolResultEvictorHistorySpec.scala): seven runtime scenarios for supplied local/inline observations, persistence fallback, cancellation before dispatch, eviction-before-compaction, and canonical/request-view isolation.
- [ToolResultStoreSpec](ToolResultStoreSpec.scala): three added read-only lookup/layout/concurrent publication scenarios; the existing write test now verifies canonical resolution.

The focused tests first failed on numeric-string decoding and Windows file-as-directory behavior. Final strict-number forgery cases include version strings, fractional widths, and integer overflow. Symlink/junction creation is outside the dynamically verified scope.

[ResultStorageLocationSpec](ResultStorageLocationSpec.scala) adds eight scenarios for configuration codecs, absolute/trailing-separator persistence, invalid paths, existing-directory enforcement, untrusted mode, concurrent publication/collisions, removed/replaced locations, and unchanged lazy default storage. The upstream-named scenario checks complete stored content, canonical preview paths, fresh-instance recognition, and absence of a workspace cache side effect.

ToolResultEvictorHistorySpec now contains seven scenarios. The upstream-named evictsCanonicalAgentStateWhenReasoningHookRemovedLargeResult verifies full local-disk content while the model receives exactly the projected value without removed pairs. Two additional scenarios verify repeated views after actual execution and independent request-only metadata/state. Java reference identity and middleware ordering are adapted, not replicated.

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

- [x] `review_fp` and `review_harness` approved the final inline-processing and cancellation-cleanup scope against `2b99226` on 2026-09-29. Fourteen added regressions and the full formatting/fatal-warning verification passed: 510 core and 74 harness tests. No findings remain in this scope. Supplied-history eviction is covered by a later scope; exhaustive upstream middleware parity remains pending. Exact current identities are in the [agent table](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md).

The inline-provider integration regression verifies full 80,001-character storage, bounded head/tail preview, preserved server marker, processed canonical reply and event metadata, and zero local executions/Tool messages. It does not establish supplied-history eviction.

- [x] `review_fp` and `review_harness` approved the final nine-file history-eviction scope against `cc8fa7b` on 2026-09-29. Twenty-two added regressions and full formatting/fatal-warning verification passed: 516 core and 90 harness tests. Two new regressions exposed and closed strict-number and Windows storage-layout gaps. No findings remain within store-backed integrity, explicit workspace trust, and validated composition; absolute storage is covered by a later scope; request-only input views are covered by a later scope. See the [eviction contract/table](../../../../../../../main/scala/com/weisgnosis/agents/harness/eviction/README.md).

- [ToolResultEvictorRecognitionSpec](ToolResultEvictorRecognitionSpec.scala): nine scenarios for low-threshold repetition, fresh-instance/JSON reloads, changed windows, malformed/forged hints, missing/corrupt content, lookup failure/interruption, reused IDs/nontext blocks, and local canonical lookup.
- [ToolResultEvictorHistorySpec](ToolResultEvictorHistorySpec.scala): seven runtime scenarios for supplied local/inline observations, persistence fallback, cancellation before dispatch, eviction-before-compaction, and canonical/request-view isolation.
- [ToolResultStoreSpec](ToolResultStoreSpec.scala): three added read-only lookup/layout/concurrent publication scenarios; the existing write test now verifies canonical resolution.

The focused tests first failed on numeric-string decoding and Windows file-as-directory behavior. Final strict-number forgery cases include version strings, fractional widths, and integer overflow. Symlink/junction creation is outside the dynamically verified scope.

- [x] `review_fp` and `review_harness` approved the final three-file storage-location scope against `3718e18` on 2026-09-29. Eight added tests and full formatting/fatal-warning verification passed: 516 core and 98 harness tests. No findings remain within explicit host ownership and canonical-location validation. Dynamic symlink/junction checks, hostile mutation protection, and filesystem object identity are not claimed. Exact identities and migration are in the [eviction contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/eviction/README.md).

[ResultStorageLocationSpec](ResultStorageLocationSpec.scala) adds eight scenarios for configuration codecs, absolute/trailing-separator persistence, invalid paths, existing-directory enforcement, untrusted mode, concurrent publication/collisions, removed/replaced locations, and unchanged lazy default storage. The upstream-named scenario checks complete stored content, canonical preview paths, fresh-instance recognition, and absence of a workspace cache side effect.

- [x] `review_fp` and `review_harness` approved the final seven-file model-input scope against `40f6f05` on 2026-09-29. Sixteen added regressions and full formatting/fatal-warning verification passed: 529 core and 101 harness tests. No findings remain within request-only projection, structural validation, canonical-state isolation, and eviction integration. Broader legacy hook/middleware parity remains pending. Exact identities and migration are in the [agent contract](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md).

ToolResultEvictorHistorySpec now contains seven scenarios. The upstream-named evictsCanonicalAgentStateWhenReasoningHookRemovedLargeResult verifies full local-disk content while the model receives exactly the projected value without removed pairs. Two additional scenarios verify repeated views after actual execution and independent request-only metadata/state. Java reference identity and middleware ordering are adapted, not replicated.

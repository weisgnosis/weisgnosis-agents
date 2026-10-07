# Workspace ownership verification

[WorkspaceLeaseSpec](WorkspaceLeaseSpec.scala) adds three cases for independent typed owner filenames, transcript contention/closed handles, and abandoned-token retention. Existing [DailyMemoryLeaseSpec](../memory/DailyMemoryLeaseSpec.scala) exercises the extracted machinery through the memory adapter, including interrupted waiting, admitted-work cancellation, caller masking, scope closure, and foreign-token cleanup.

These are independent instances in one JVM. Cross-process contention and dynamic path mutation remain platform-validation work. Exact accepted identities are recorded in the [source scope](../../../../../../../main/scala/com/weisgnosis/agents/harness/transcript/README.md).

- [x] `review_fp` and `review_harness` approved the final 14-file local transcript increment against `e4dfc67` on 2026-09-29. Twenty-seven new tests reproduced and fixed Unicode surrogate splitting; full `sbt scalafmtAll scalafmtSbt verify` passed 530 core / 258 harness tests with zero compiler warnings. No findings remain within shared cooperative ownership, strict complete-log validation, atomic batch replacement, reopen/deduplication, and Unicode-safe previews. Remote segments, Java layout/wire equivalence, hostile mutation, fsync/power-loss durability, and automatic turn scheduling remain excluded.

Plan ownership extends the fixed-name test and adds typed verification/release-defect and cancellation cases. The [plan storage contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/plan/README.md#standalone-draft-storage) records the new scope; prior memory/transcript behavior remains covered by the existing suites.

- [x] `review_fp` and `review_harness` approved the final nine-file standalone plan-storage increment against `083617b` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 556 core / 520 harness tests, including fifteen new cases, with zero compiler warnings. No findings remain within exact content/encoding, scoped cooperative ownership, atomic replacement, hardlink preservation, typed errors and cancellation ordering. Session/publication integration, persisted approval, hostile filesystem isolation, crash durability and new whole-method upstream parity are excluded.

## Checked versioned storage values

[VersionedJsonStoreSpec](VersionedJsonStoreSpec.scala) adds two proportional groups for exact namespace/key identity and Unicode/UTF-8 bounds, plus positive revision numeric codecs and malformed inputs. Backend atomicity and cancellation require the separate [real PostgreSQL suite](../../../../../../../../../storage-postgresql/src/test/scala/com/weisgnosis/agents/storage/postgresql/README.md); these pure tests do not prove database behavior.

- [x] review_fp and review_invariants approved the exact source/spec identities and full verification passes; evidence belongs to the [public port contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/storage/README.md#versioned-json-publication).

The [checkpoint/shared-helper scope](../../../../../../../main/scala/com/weisgnosis/agents/harness/storage/README.md#versioned-acting-checkpoints) extracts canonical comparison and separately invoked Unicode checks without changing general duplicate validation or transcript behavior. Final source approvals and focused/full verification pass; fresh artifact consumption passes; final documentation approval passed in the linked manifest. Real database claims rely on the separate PostgreSQL fixture.

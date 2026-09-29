# Workspace ownership verification

[WorkspaceLeaseSpec](WorkspaceLeaseSpec.scala) adds three cases for independent typed owner filenames, transcript contention/closed handles, and abandoned-token retention. Existing [DailyMemoryLeaseSpec](../memory/DailyMemoryLeaseSpec.scala) exercises the extracted machinery through the memory adapter, including interrupted waiting, admitted-work cancellation, caller masking, scope closure, and foreign-token cleanup.

These are independent instances in one JVM. Cross-process contention and dynamic path mutation remain platform-validation work. Exact accepted identities are recorded in the [source scope](../../../../../../../main/scala/com/weisgnosis/agents/harness/transcript/README.md).

- [x] `review_fp` and `review_harness` approved the final 14-file local transcript increment against `e4dfc67` on 2026-09-29. Twenty-seven new tests reproduced and fixed Unicode surrogate splitting; full `sbt scalafmtAll scalafmtSbt verify` passed 530 core / 258 harness tests with zero compiler warnings. No findings remain within shared cooperative ownership, strict complete-log validation, atomic batch replacement, reopen/deduplication, and Unicode-safe previews. Remote segments, Java layout/wire equivalence, hostile mutation, fsync/power-loss durability, and automatic turn scheduling remain excluded.

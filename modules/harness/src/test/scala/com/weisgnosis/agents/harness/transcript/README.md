# Session transcript verification

- [TranscriptEntrySpec](TranscriptEntrySpec.scala): two codec/structural-key cases.
- [TranscriptStoreSpec](TranscriptStoreSpec.scala): seven cases for duplicate gaps, first-observation retention, anonymous replay, store isolation, lazy recording time, deterministic UUID-collision batch rollback, concurrent overlapping batches, and empty input.
- [SessionTranscriptWriterSpec](SessionTranscriptWriterSpec.scala): thirteen cases for overlapping histories, structured tool payloads, image/empty placeholders, absent/blank IDs, summaries, thinking/context filtering, repeated tools/delimiters, exact 500/501 and 1,000/1,001 preview boundaries, non-text output, compactor ordering, typed errors, and cancellation cleanup.

Tests use Ref, Promise, scoped fibers, TestClock and deterministic TestRandom seeds. No timing sleeps or filesystem claims are involved. The three offload-delegate behaviors and three writer behaviors have local observation evidence; actual JSONL persistence assertions and the immutable segment method remain pending.

- [x] `review_fp` and `review_harness` approved the final six-file transcript increment against `73458da` on 2026-09-29. No findings remain within pure observation projection, atomic in-memory batch deduplication/linkage, first-observation retention, collision rollback, and compactor callback integration. Full `sbt scalafmtAll scalafmtSbt verify` passed 530 core / 231 harness tests, including 22 new cases, with zero compiler warnings. Filesystem/remote persistence, automatic turn-boundary recording, strict persisted-input validation, and Java wire compatibility remain outside this scope.

Exact identities and limits are in the [source contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/transcript/README.md).

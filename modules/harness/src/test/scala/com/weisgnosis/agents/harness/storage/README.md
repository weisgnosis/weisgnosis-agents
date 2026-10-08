# Workspace ownership verification

## Read-only snapshot verification

[LocalVersionedJsonStoreSpec](LocalVersionedJsonStoreSpec.scala) covers explicit file/address CAS, strict bounded corruption handling, portable paths, atomic replacement and joined cancellation/cleanup. WorkspaceLeaseSpec preserves the unchanged-production failed-open assertions for Plan/Transcript/Memory and verifies repair/reopen in the same host Scope, exact failure/interruption causes, held cleanup and once-only finalizers. The actual registry suite verifies the session JSON file and mixed state/results/counts after fresh-scope reopen without execution replay.

[WorkspaceLeaseSpec](WorkspaceLeaseSpec.scala) adds coverage of independent typed owner filenames, transcript contention/closed handles, and abandoned-token retention. Existing [DailyMemoryLeaseSpec](../memory/DailyMemoryLeaseSpec.scala) exercises the extracted machinery through the memory adapter, including interrupted waiting, admitted-work cancellation, caller masking, scope closure, and foreign-token cleanup.

These are independent instances in one JVM. Cross-process contention and dynamic path mutation remain platform-validation work.

Plan ownership extends the fixed-name test and adds typed verification/release-defect and cancellation cases. The [plan storage contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/plan/README.md#standalone-draft-storage) records the new scope; prior memory/transcript behavior remains covered by the existing suites.

## Checked versioned storage values

[VersionedJsonStoreSpec](VersionedJsonStoreSpec.scala) adds two proportional groups for exact namespace/key identity and Unicode/UTF-8 bounds, plus positive revision numeric codecs and malformed inputs. Backend atomicity and cancellation require the separate [real PostgreSQL suite](../../../../../../../../../storage-postgresql/src/test/scala/com/weisgnosis/agents/storage/postgresql/README.md); these pure tests do not prove database behavior.

The [checkpoint/shared-helper scope](../../../../../../../main/scala/com/weisgnosis/agents/harness/storage/README.md#versioned-acting-checkpoints) extracts canonical comparison and separately invoked Unicode checks without changing general duplicate validation or transcript behavior. Real database claims rely on the separate PostgreSQL fixture.

WorkspaceLeaseSpec adds a held admitted-work failure/cancellation regression that requires the typed failure and pending interruption after joined cleanup. It does not test hostile-filesystem isolation.

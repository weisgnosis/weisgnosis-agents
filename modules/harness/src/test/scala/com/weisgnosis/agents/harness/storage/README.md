# Workspace ownership verification

## Read-only snapshot verification

[LocalVersionedJsonStoreSpec](LocalVersionedJsonStoreSpec.scala) covers explicit file/address CAS, strict bounded corruption handling, portable paths, atomic replacement and joined cancellation/cleanup. WorkspaceLeaseSpec verifies failed opening and repair/reopen for Plan/Transcript/Memory in the same host Scope, exact failure/interruption causes, held cleanup and once-only finalizers. The actual registry suite verifies the session JSON file and mixed state/results/counts after fresh-scope reopen without execution replay.

[WorkspaceLeaseSpec](WorkspaceLeaseSpec.scala) covers independent typed owner filenames, transcript contention/closed handles, and abandoned-token retention. [DailyMemoryLeaseSpec](../memory/DailyMemoryLeaseSpec.scala) exercises shared ownership through the memory adapter, including interrupted waiting, admitted-work cancellation, caller masking, scope closure, and foreign-token cleanup.

These are independent instances in one JVM. Cross-process contention and dynamic path mutation remain platform-validation work.

Plan ownership checks cover fixed ownership names, typed verification/release defects and cancellation. The [plan storage contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/plan/README.md#standalone-draft-storage) describes its adapter-specific behavior.

## Checked versioned storage values

[VersionedJsonStoreSpec](VersionedJsonStoreSpec.scala) covers exact namespace/key identity and Unicode/UTF-8 bounds, plus positive revision numeric codecs and malformed inputs. Backend atomicity and cancellation require the separate [real PostgreSQL suite](../../../../../../../../../storage-postgresql/src/test/scala/com/weisgnosis/agents/storage/postgresql/README.md); these pure tests do not prove database behavior.

The [checkpoint storage contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/storage/README.md#versioned-acting-checkpoints) describes canonical comparison and Unicode validation. Real database claims rely on the separate PostgreSQL fixture.

WorkspaceLeaseSpec also requires the typed failure and pending interruption after joined cleanup of held admitted work. It does not test hostile-filesystem isolation.

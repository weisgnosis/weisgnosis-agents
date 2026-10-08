# Session transcript verification

Run `sbt "harness / testOnly com.weisgnosis.agents.harness.transcript.*"` from the repository root. Suites use scoped temporary directories, immutable fixtures, Ref/Promise coordination and deterministic clocks/randomness where appropriate. The [source contracts](../../../../../../../main/scala/com/weisgnosis/agents/harness/transcript/README.md) define ownership, data loss, compatibility and uncertainty boundaries.

[TranscriptEntrySpec](TranscriptEntrySpec.scala), [TranscriptStoreSpec](TranscriptStoreSpec.scala) and [SessionTranscriptWriterSpec](SessionTranscriptWriterSpec.scala) cover structural keys, whole-batch deduplication/linkage, first-observation retention, anonymous replay, collisions, concurrent appends, exact numeric/Unicode previews, lossy message projection and compactor delegation. Registered-writer tables exercise actual ReAct completion and fresh readers in both layouts, append-before-registration, explicit index repair, empty projection and joined cancellation.

The same writer suite covers versioned catalog retirement: strict migration, permanent reservations, capacity, definite-conflict retry within one authority grant, uncertain committed publication and joined authority cancellation. A fixture capability is not proof of a distributed writer fence. Independent PostgreSQL retirement/reopening is documented separately below.

## Local persistence and strict restoration

[LocalTranscriptStoreSpec](LocalTranscriptStoreSpec.scala) exercises physical reopening, unchanged duplicate bytes, isolation/ownership, escaped handles, parallel whole batches, corruption preservation, UUID collisions and Unicode previews. [TranscriptFilesSpec](TranscriptFilesSpec.scala) checks hard-link preservation and rejected replacement cleanup. [TranscriptLogSpec](TranscriptLogSpec.scala) validates framing, versions, canonical shapes, duplicate fields, Unicode, exact values and complete chains; recovery-document cases also enforce branch/entry/byte bounds and conflicting identity rejection. [TranscriptStateSpec](TranscriptStateSpec.scala) covers shared draft/key/payload/preview/parent validation.

[LocalTranscriptHandoffSpec](LocalTranscriptHandoffSpec.scala) uses actual files to verify cold and empty import, prefix updates, divergent branch preservation, ordinary-writer continuation after reopen, shorter later remote snapshots, same-ID refusal and archive-aware activity. Failure/cancellation holds remote cleanup and checks exact causes and released ownership. The oversized cold/prefix regression failed against unchanged production before the handoff-specific byte check; ordinary legacy large local writes remain an explicit positive control. Recovery is observational union, not canonical agent restoration.

## Runtime completion integration

[TranscriptCompletionSpec](TranscriptCompletionSpec.scala) records stable and anonymous snapshots through actual runtime completion, including reconciled cancellation, policy rejection, compactor offload, reopen and typed storage errors. Recording is explicitly installed; no automatic global writer or Java file-format compatibility follows.

## Explicit pruning verification

[LocalTranscriptRetentionSpec](LocalTranscriptRetentionSpec.scala) checks actual missing/empty payloads, cutoff equality, metadata and observation activity, future/nonmonotonic timestamps, corruption, live/stale leases, unrelated files, fresh generation, unsafe/nonregular paths and competing pruners. Handoff tests additionally ensure recent archived observations prevent deletion of an older selected continuation.

Cancellation-after-admission follows the shared WorkspaceLease contract. No deterministic mid-syscall interruption, hostile mutation or process-crash/power-loss guarantee is claimed. Pruning remains fixed-target and owner-authorized.

## Transcript policy verification

[TranscriptRetentionPolicySpec](TranscriptRetentionPolicySpec.scala) and [LocalTranscriptRetentionPolicySpec](LocalTranscriptRetentionPolicySpec.scala) cover checked 180-day defaults, codecs, safe Instant arithmetic, one cutoff sample, missing/equality outcomes and KeepAll bypass of both ownership and clock. Explicit policy does not install a timer or prove inactivity.

## Batch verification

[TranscriptRetentionTargetSpec](TranscriptRetentionTargetSpec.scala) checks exact IDs and deferred ownership. [TranscriptRetentionBatchSpec](TranscriptRetentionBatchSpec.scala) covers explicit/discovered selection, duplicate rejection, fresh snapshots, one cutoff, ordering, complete failure causes, joined cancellation, replay and enabled empty batches. Earlier committed deletions are not rolled back by a later failure.

## Bounded local discovery evidence

[LocalTranscriptDiscoverySpec](LocalTranscriptDiscoverySpec.scala) covers sorted trusted cold selection, bounds/traversal/sensitive/nonregular rejection before effects, and later writer/body failure preserving earlier progress. [TranscriptDiscoverySpec](TranscriptDiscoverySpec.scala) combines actual local and catalog sources, counts raw candidates before deduplication, rejects conflicting bindings/reserved subtrees, defers body reads and joins source cleanup. Borrowed readers grant no writer authority or atomic global snapshot. Native aliases have their separate verification fixture; these tests do not establish hostile-filesystem isolation.

## Versioned transcript verification

Actual PostgreSQL [aggregate](../../../../../../../../../storage-postgresql/src/test/scala/com/weisgnosis/agents/storage/postgresql/VersionedTranscriptStoreSpec.scala), [segment](../../../../../../../../../storage-postgresql/src/test/scala/com/weisgnosis/agents/storage/postgresql/SegmentedTranscriptStoreSpec.scala) and [catalog](../../../../../../../../../storage-postgresql/src/test/scala/com/weisgnosis/agents/storage/postgresql/VersionedTranscriptSessionCatalogSpec.scala) suites verify independently reopened adapters, strict state, conditional conflicts and uncertain publication. The catalog suite records through both registered layouts, retires exact membership and confirms history remains readable. The database environment must be enabled; skipped fixtures are not backend verification. Global enumeration, physical reclamation and Java mirror wire/layout equivalence are not claimed.

# Storage contracts

WorkspaceLease supplies cooperative ownership for local memory, transcript, plan-draft and explicitly bound JSON backends. Default domain filenames remain .daily-writer.lease, .transcript-writer.lease and .plan-writer.lease. A JSON target has a separate checked per-file lease; distinct targets can coexist. Callers must control and validate filesystem ancestors.

CREATE_NEW publishes an opaque ownership token. Competitors never delete or overwrite it. Waiting is interruptible; admitted work is masked and finishes before pending ordinary cancellation is delivered. Closing joins admitted work, marks the handle closed, verifies the token and deletes the lease. Token verification/release failures remain observable finalizer defects. Slow filesystem operations can delay cancellation. Abandoned leases require explicit host recovery.

This is cooperative infrastructure, not a hostile-filesystem sandbox, distributed execution lock, file-object identity guarantee or crash-durability mechanism. DailyMemoryLease retains its compatibility filename. Memory/Transcript failures retain MemoryStorageError, Plan failures retain PlanStorageError, and JSON failures use StorageError.

## Scoped initialization

Failed construction must release acquired ownership before returning its failure, even when the host catches open.either inside a still-live Scope. Successful construction stays attached to that host Scope. WorkspaceLease.initialize registers an owned child Scope before restoring interruptible initialization, closes it on the actual failed/interrupted Exit and preserves operation and cleanup causes. Later host closure must not repeat already-run finalizers or their defects.

- [x] Pass same-live-host-scope repair/reopen and initialization failure/cancellation/cleanup regressions.

## Admitted-work failure and cancellation

WorkspaceLease captures the complete Exit of admitted work, joins work and cleanup, and combines pending ordinary caller cancellation with failure. Typed operation errors remain present and explicit caller masking is respected. WorkspaceLeaseSpec retains the reproduced admitted-failure regression as well as initialization lifecycle coverage.

## Caller interruption provenance

ZIO 2.1.26 allowInterrupt synthesizes self-interruption at this explicit checkpoint.

The shared checkpoint retains actual pending descriptor interruptor IDs alongside the original operation/cleanup causes and preserves explicit caller masking. Public discovery already had an outer admitted-I/O checkpoint; the direct-kernel failure is not evidence that its public discovery tests failed.

No new test group is added for the lease correction.

## Read-only JSON snapshots

LocalVersionedJsonStore.readSnapshot shares the owned backend's strict path/envelope reader without acquiring a writer lease. SnapshotRead distinguishes Missing, Older(observedModifiedAt) and Found(snapshot). A cutoff excludes strictly older files before payload decoding; equality remains included. Found retains the exact VersionedJson, observed modification time and actual encoded-byte count.

Modification time and payload are observational samples, not one atomic filesystem generation. Admitted bounded reads and stream cleanup finish before pending ordinary cancellation is delivered; typed failures, cleanup causes and caller interruption remain observable. Native filesystem blocking can delay cancellation; byte/entry bounds are not wall-clock deadlines. Reading does not grant execution authority, acknowledge data, change revisions or perform abandoned-lease takeover. Host-controlled ancestors remain required.

## Local versioned JSON

[LocalVersionedJsonStore.open](LocalVersionedJsonStore.scala) binds one explicit StorageAddress to one explicit file beneath an existing host-controlled directory. Construction and all filesystem interaction are suspended in ZIO. There is no automatic partition/path derivation. Limits.default bounds a complete envelope to 2 MiB; checked custom limits leave room for an overflow byte. Target names are portable lowercase ASCII .json components, at most 128 UTF-8 bytes, with device aliases and unsafe path spellings rejected. Domain addresses and JSON values retain exact Unicode and numeric representation.

Opening validates trust, lexical traversal, sensitive lexical/canonical paths, canonical existing non-symlink parents and regular non-symlink targets. Each read reloads bounded strict UTF-8 data from disk. The private formatVersion=1 envelope validates its exact address, positive revision, recursive field uniqueness, Unicode and canonical codec representation. Corruption is a failure, never absence or permission to overwrite.

compareAndSet reloads under the per-target lease. A conflict changes no bytes; creation starts at revision 1 and updates advance the persisted revision. Exhausted Long.MaxValue expectations fail. A bounded candidate is written through a scoped same-directory temporary file and native atomic replacement, with no non-atomic fallback. Operation and release errors retain their causes. Cancellation waits for admitted replacement and cleanup; failure or cancellation does not prove the write did not commit, and uncertain writes are never retried automatically.

The host owns the lifetime and must keep it alive through borrowed adapters. Closing the backend rejects later use. External replacement/deletion invalidates revision assumptions. This adapter provides neither fencing, fsync/power-loss durability, abandoned-lease takeover, directory discovery nor executable checkpoint restoration.

The actual registry integration targets agents/parent/tasks/sess-rt.json and verifies mixed Pending/Running/Completed/Failed data, exact results/counts, persisted acknowledgement and fresh-scope reopen without model/execution replay. It reuses SubagentTerminalStore rather than introducing a second task schema. This is the physical assertion in pinned WorkspaceTaskRepositoryTest 469, not Java wire/API equivalence or whole task-repository completion.

Source and paired tests: [LocalMemoryRepository.scala](../memory/LocalMemoryRepository.scala), [LocalPlanDraftStore.scala](../plan/LocalPlanDraftStore.scala), [LocalVersionedJsonStore.scala](LocalVersionedJsonStore.scala), [WorkspaceLease.scala](WorkspaceLease.scala), [LocalTranscriptStore.scala](../transcript/LocalTranscriptStore.scala), [LocalVersionedJsonStoreSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/storage/LocalVersionedJsonStoreSpec.scala), [WorkspaceLeaseSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/storage/WorkspaceLeaseSpec.scala), [SubagentTaskRegistrySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentTaskRegistrySpec.scala).

## Versioned JSON publication

[VersionedJsonStore.scala](VersionedJsonStore.scala) adds public checked StorageAddress, opaque positive StorageRevision, ExpectedRevision, ConditionalWrite and VersionedJson data plus the effectful read/compareAndSet port. Address codecs reapply exact Unicode/nonblank/512-UTF-8-byte checks; namespace serialization uses a reserved U+001F separator. The revision codec accepts only positive signed-Long JSON numbers, rejecting string/fraction/overflow coercion. Conflict changes nothing; creation starts at 1 and exact-revision updates advance without reuse. Exhausted Long.MaxValue expectations fail without publication. Cancellation/lost acknowledgement does not establish that a write did not commit.

The optional [PostgreSQL backend](../../../../../../../../../storage-postgresql/README.md) is a separately packaged implementation. The separately composed memory/transcript adapters implement bounded aggregates. This prerequisite does not supply deletion/search, multi-address transactions or exclusive host execution claims. Existing WorkspaceLease remains private cooperative local infrastructure; this new public storage port does not turn local leases into distributed locks.

## Versioned acting checkpoints

[VersionedActingCheckpointStore.make](VersionedActingCheckpointStore.scala) is a pure factory returning ActingCheckpointStore over one injected VersionedJsonStore and StorageAddress. There are no retry/configuration knobs and construction performs no effects. read returns None for absence or VersionedActingCheckpoint(checkpoint, revision). The revision identifies persistent publication, not permission or execution ownership.

save(expected, checkpoint) validates a suspended AgentResult with PendingActing.validate, encodes/validates the candidate, then invokes exactly one compareAndSet with the host-supplied expectation. It performs no preliminary read and never retries Conflict, failures, cancellation or uncertain acknowledgements. A valid candidate may explicitly replace corrupt stored data when the expected revision matches. Non-suspended/domain-invalid candidates fail before publication. A failure or cancellation can follow an admitted committed write; hosts coordinate subsequent recovery instead of blindly repeating side effects.

The private version-1 envelope requires schemaVersion and checkpoint. Recursive duplicate-field rejection, separately invoked Unicode string/object-key validation, exact numeric rendering and canonical encode/decode comparison precede suspended-state restoration checks. Unknown/missing/noncanonical fields, unsupported versions, decoder coercions, corrupt occurrence ledgers and invalid pending state fail with StorageError on read. Save's initial domain validation retains InvalidConfiguration; underlying backend errors remain typed. Public AgentResult/DTO codecs and their wire shapes are unchanged, and public codecs alone are not strict persisted-state validators.

The shared restricted JsonBoundary.sameRepresentation and validUnicode helpers replace the prior transcript-local implementations. General JsonBoundary.validate remains duplicate-field validation with its existing Unicode policy; local JSONL and remote transcript behavior are preserved. Canonical comparison permits object ordering and numerically equivalent spelling, while optional fields retain each writer's representation. This extraction adds no global JSON Schema, size or authentication policy.

Persistent checkpoint data does not grant approval. ReActAgent.restore and host child import still rebuild current executable bindings, check iteration limits and validate pending occurrences; stored tool definitions and permission correlation IDs do not authorize execution. Hosts own authenticated delivery, exclusive claims, replay/idempotency and workflow orchestration. One address holds one whole checkpoint; backend payload limits and address binding are host responsibilities. No automatic runtime installation, checkpoint deletion/listing, transcript segments/discovery/pruning or cross-address transaction is supplied.

Migration: the capability/wrapper and factory are additive. Existing runtime restore and memory/transcript APIs remain unchanged. Remote version 1 is a separate private envelope; there is no automatic import from local logs or a blanket upstream checkpoint-format/parity claim.

Source and paired tests: [ActingCheckpointStore.scala](ActingCheckpointStore.scala), [VersionedActingCheckpointStore.scala](VersionedActingCheckpointStore.scala), [JsonBoundary.scala](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/json/JsonBoundary.scala), [JsonBoundarySpec.scala](../../../../../../../../../core/src/test/scala/com/weisgnosis/agents/core/json/JsonBoundarySpec.scala), [TranscriptLog.scala](../transcript/TranscriptLog.scala), [VersionedTranscriptStore.scala](../transcript/VersionedTranscriptStore.scala), [ActingCheckpointStoreSpec.scala](../../../../../../../../../storage-postgresql/src/test/scala/com/weisgnosis/agents/storage/postgresql/ActingCheckpointStoreSpec.scala).

## Remote object lifetime

The current publication capability supplies read and conditional replacement, without list/delete. ExpectedRevision is valid within an address lifetime; deleting and recreating the address can introduce ABA and invalidate that guarantee. CAS publication alone cannot fence an old immutable-chain reader or an in-flight writer preparing a later-visible segment. A catalog is a descriptor index, not quiescence or retention proof.

The [retention assessment](../transcript/README.md#remote-retention-and-ownership-boundary) requires explicit all-reader/writer fencing or quiescence, generation/tombstone lifetime protection, enumeration/deletion and uncertain-delete recovery before physical reclamation. Existing memory archival remains logical aggregate publication. These are unfinished host/backend contracts, not implemented storage operations or a distributed workflow within the library. The unpublished registered assembly example selects explicit transcript bindings without adding ownership, authorization or reclamation authority.

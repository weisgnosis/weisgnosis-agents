# Storage contracts

WorkspaceLease supplies cooperative ownership for local memory, transcript, plan-draft and explicitly bound JSON backends. Default domain filenames remain .daily-writer.lease, .transcript-writer.lease and .plan-writer.lease. A JSON target has a separate checked per-file lease; distinct targets can coexist. Callers must control and validate filesystem ancestors.

CREATE_NEW publishes an opaque ownership token. Competitors never delete or overwrite it. Waiting is interruptible; admitted work is masked and finishes before pending ordinary cancellation is delivered. Closing joins admitted work, marks the handle closed, verifies the token and deletes the lease. Token verification/release failures remain observable finalizer defects. Slow filesystem operations can delay cancellation. Abandoned leases require explicit host recovery.

This is cooperative infrastructure, not a hostile-filesystem sandbox, distributed execution lock, file-object identity guarantee or crash-durability mechanism. DailyMemoryLease retains its compatibility filename. Memory/Transcript failures retain MemoryStorageError, Plan failures retain PlanStorageError, and JSON failures use StorageError.

## Scoped initialization

Failed construction must release acquired ownership before returning its failure, even when the host catches open.either inside a still-live Scope. Successful construction stays attached to that host Scope. WorkspaceLease.initialize registers an owned child Scope before restoring interruptible initialization, closes it on the actual failed/interrupted Exit and preserves operation and cleanup causes. Later host closure must not repeat already-run finalizers or their defects.

- [x] Reproduce the defect before correction on the unchanged JSON draft: target/local-json-failed-open-red.log fails because .checked.json.versioned-writer.lease remains after rejected initialization.
- [x] Reproduce the same defect in unchanged accepted Plan/Transcript/Memory factories: target/local-backends-failed-open-red.log fails all three payload-only directory assertions because their writer leases remain.
- [x] Pass same-live-host-scope repair/reopen and initialization failure/cancellation/cleanup regressions.
- [x] Obtain renewed independent FP/ZIO and invariant approval of the final eight source identities; the earlier JSON draft approval was superseded.
- [x] Pass full verification, fresh artifacts/archive admission and standalone consumption.
- [x] review_fp and review_invariants approve the final combined scope against 73b0fb1: eight unchanged Scala identities and twenty-two Markdown files, with no unresolved findings.

## Admitted-work failure and cancellation

WorkspaceLease captures the complete Exit of admitted work, joins work and cleanup, and combines pending ordinary caller cancellation with failure. Typed operation errors remain present and explicit caller masking is respected. WorkspaceLeaseSpec retains the reproduced admitted-failure regression as well as initialization lifecycle coverage. The local JSON manifest below records the exact source-approved identities and completed verification and final combined approval by review_fp and review_invariants for eight unchanged Scala identities and twenty-two Markdown files against 73b0fb1.

## Local versioned JSON

[LocalVersionedJsonStore.open](LocalVersionedJsonStore.scala) binds one explicit StorageAddress to one explicit file beneath an existing host-controlled directory. Construction and all filesystem interaction are suspended in ZIO. There is no automatic partition/path derivation. Limits.default bounds a complete envelope to 2 MiB; checked custom limits leave room for an overflow byte. Target names are portable lowercase ASCII .json components, at most 128 UTF-8 bytes, with device aliases and unsafe path spellings rejected. Domain addresses and JSON values retain exact Unicode and numeric representation.

Opening validates trust, lexical traversal, sensitive lexical/canonical paths, canonical existing non-symlink parents and regular non-symlink targets. Each read reloads bounded strict UTF-8 data from disk. The private formatVersion=1 envelope validates its exact address, positive revision, recursive field uniqueness, Unicode and canonical codec representation. Corruption is a failure, never absence or permission to overwrite.

compareAndSet reloads under the per-target lease. A conflict changes no bytes; creation starts at revision 1 and updates advance the persisted revision. Exhausted Long.MaxValue expectations fail. A bounded candidate is written through a scoped same-directory temporary file and native atomic replacement, with no non-atomic fallback. Operation and release errors retain their causes. Cancellation waits for admitted replacement and cleanup; failure or cancellation does not prove the write did not commit, and uncertain writes are never retried automatically.

The host owns the lifetime and must keep it alive through borrowed adapters. Closing the backend rejects later use. External replacement/deletion invalidates revision assumptions. This adapter provides neither fencing, fsync/power-loss durability, abandoned-lease takeover, directory discovery nor executable session restoration.

The actual registry integration targets agents/parent/tasks/sess-rt.json and verifies mixed Pending/Running/Completed/Failed data, exact results/counts, persisted acknowledgement and fresh-scope reopen without model/execution replay. It reuses SubagentTerminalStore rather than introducing a second task schema. This is the physical assertion in pinned WorkspaceTaskRepositoryTest 469, not Java wire/API equivalence or whole task-repository completion.

- [x] review_fp and review_invariants approve eight exact Scala identities against 73b0fb1, manifest b450bd087a0b14af083c61326935250b7c5d90b8cffa627cc1c7b41f2ddf14c7, with no unresolved source findings.
- [x] target/local-json-focused-green.log passes 68 groups, zero failed/ignored/compiler warnings, in 1.612 seconds.
- [x] target/local-json-final-verify.log passes formatting/fatal-warning compilation, 1,648 tests (695 core / 791 harness / 130 MCP / 24 actual PostgreSQL / eight examples) and four RC1 packages, zero failed/ignored/compiler warnings.
- [x] target/local-json-final-preparation.log passes negative archive admission, original-byte restoration and revalidation.
- [x] target/local-json-final-consumer.log passes fresh standalone formatting/compilation/execution and four artifact hashes; the unchanged probe does not directly exercise this adapter.
- [x] review_fp and review_invariants approve the final combined scope against 73b0fb1: eight unchanged Scala identities and twenty-two Markdown files, with no unresolved findings. English prose, UTF-8 and local links are checked.

| Reviewed source against 73b0fb1 | Git blob |
| --- | --- |
| [LocalMemoryRepository.scala](../memory/LocalMemoryRepository.scala) | `4002a33fa1499c15ac795e8142325869444cb735` |
| [LocalPlanDraftStore.scala](../plan/LocalPlanDraftStore.scala) | `1f42fd5ae3237ad6f130fb5e702d443cc78b2a32` |
| [LocalVersionedJsonStore.scala](LocalVersionedJsonStore.scala) | `f633936f0edfb249faa5672371e57b31b43cebb0` |
| [WorkspaceLease.scala](WorkspaceLease.scala) | `58729f2d2ec9d90c7733250ca7997b383f399195` |
| [LocalTranscriptStore.scala](../transcript/LocalTranscriptStore.scala) | `2b0102c6aa48668976f64b002320bdb973ce4173` |
| [LocalVersionedJsonStoreSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/storage/LocalVersionedJsonStoreSpec.scala) | `2b8f2ea62b559a27e1069cfaa6d5753a70e3d268` |
| [WorkspaceLeaseSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/storage/WorkspaceLeaseSpec.scala) | `a5f8c250763bc39cfc5de1ec646f2dfb8b4910e3` |
| [SubagentTaskRegistrySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentTaskRegistrySpec.scala) | `2fbdb76e725bdd8e1fa061c6ac67d39a9cc45174` |

## Versioned JSON publication

[VersionedJsonStore.scala](VersionedJsonStore.scala) adds public checked StorageAddress, opaque positive StorageRevision, ExpectedRevision, ConditionalWrite and VersionedJson data plus the effectful read/compareAndSet port. Address codecs reapply exact Unicode/nonblank/512-UTF-8-byte checks; namespace serialization uses a reserved U+001F separator. The revision codec accepts only positive signed-Long JSON numbers, rejecting string/fraction/overflow coercion. Conflict changes nothing; creation starts at 1 and exact-revision updates advance without reuse. Exhausted Long.MaxValue expectations fail without publication. Cancellation/lost acknowledgement does not establish that a write did not commit.

The optional [PostgreSQL backend](../../../../../../../../../storage-postgresql/README.md) is a separately packaged implementation. The separately composed memory/transcript adapters implement bounded aggregates. The checkpoint adapter below has final source/full/artifact verification; final documentation approval passed. This prerequisite does not supply deletion/search, multi-address transactions or exclusive host execution claims. Existing WorkspaceLease remains private cooperative local infrastructure; this new public storage port does not turn local leases into distributed locks.

- [x] review_fp and review_invariants approved this additive public port and its named backend; full verification with real PostgreSQL passes. The [backend manifest](../../../../../../../../../storage-postgresql/src/main/scala/com/weisgnosis/agents/storage/postgresql/README.md#verification-and-acceptance) records exact identities, verification and bounded acceptance. Existing workspace ownership scopes remain unchanged.

## Versioned acting checkpoints

[VersionedActingCheckpointStore.make](VersionedActingCheckpointStore.scala) is a pure factory returning ActingCheckpointStore over one injected VersionedJsonStore and StorageAddress. There are no retry/configuration knobs and construction performs no effects. read returns None for absence or VersionedActingCheckpoint(checkpoint, revision). The revision identifies persistent publication, not permission or execution ownership.

save(expected, checkpoint) validates a suspended AgentResult with PendingActing.validate, encodes/validates the candidate, then invokes exactly one compareAndSet with the host-supplied expectation. It performs no preliminary read and never retries Conflict, failures, cancellation or uncertain acknowledgements. A valid candidate may explicitly replace corrupt stored data when the expected revision matches. Non-suspended/domain-invalid candidates fail before publication. A failure or cancellation can follow an admitted committed write; hosts coordinate subsequent recovery instead of blindly repeating side effects.

The private version-1 envelope requires schemaVersion and checkpoint. Recursive duplicate-field rejection, separately invoked Unicode string/object-key validation, exact numeric rendering and canonical encode/decode comparison precede suspended-state restoration checks. Unknown/missing/noncanonical fields, unsupported versions, decoder coercions, corrupt occurrence ledgers and invalid pending state fail with StorageError on read. Save's initial domain validation retains InvalidConfiguration; underlying backend errors remain typed. Public AgentResult/DTO codecs and their wire shapes are unchanged, and public codecs alone are not strict persisted-state validators.

The shared restricted JsonBoundary.sameRepresentation and validUnicode helpers replace the prior transcript-local implementations. General JsonBoundary.validate remains duplicate-field validation with its existing Unicode policy; local JSONL and remote transcript behavior are preserved. Canonical comparison permits object ordering and numerically equivalent spelling, while optional fields retain each writer's representation. This extraction adds no global JSON Schema, size or authentication policy.

Persistent checkpoint data does not grant approval. ReActAgent.restore and host child import still rebuild current executable bindings, check iteration limits and validate pending occurrences; stored tool definitions and permission correlation IDs do not authorize execution. Hosts own authenticated delivery, exclusive claims, replay/idempotency and workflow orchestration. One address holds one whole checkpoint; backend payload limits and address binding are host responsibilities. No automatic runtime installation, checkpoint deletion/listing, transcript segments/discovery/pruning or cross-address transaction is supplied.

Migration: the capability/wrapper and factory are additive. Existing runtime restore and memory/transcript APIs remain unchanged. Remote version 1 is a separate private envelope; there is no automatic import from local logs or a blanket upstream checkpoint-format/parity claim.

| Reviewed source/spec against b7488cc | Git blob |
| --- | --- |
| [ActingCheckpointStore.scala](ActingCheckpointStore.scala) | `5da51e857554bd120dcadf5642c9f2dfc34611ce` |
| [VersionedActingCheckpointStore.scala](VersionedActingCheckpointStore.scala) | `c984be08b7e25037aad625cbb01cd102cff50223` |
| [JsonBoundary.scala](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/json/JsonBoundary.scala) | `9d44f157ffe49ec2a0994a8562bec31e5ff04fd9` |
| [JsonBoundarySpec.scala](../../../../../../../../../core/src/test/scala/com/weisgnosis/agents/core/json/JsonBoundarySpec.scala) | `34e46cc9cd042634ba6b701851639391dc938738` |
| [TranscriptLog.scala](../transcript/TranscriptLog.scala) | `f0619827e1545be6413ee185dd80792b1b2163c8` |
| [VersionedTranscriptStore.scala](../transcript/VersionedTranscriptStore.scala) | `7a42b1271420bd9767da516b576d709dc4ca971c` |
| [ActingCheckpointStoreSpec.scala](../../../../../../../../../storage-postgresql/src/test/scala/com/weisgnosis/agents/storage/postgresql/ActingCheckpointStoreSpec.scala) | `4a19f4ece7664b13c957b8b8406bef50905aeeaa` |

- [x] review_fp and review_invariants approved these seven final identities against b7488cc, with no unresolved findings.
- [x] Focused JSON verification passes nine JsonBoundarySpec and six StructuredJsonBoundarySpec cases (15 total); existing transcript suites pass 18 groups. acting-checkpoint-final-green.log passes all 12 real PostgreSQL groups in 3.771 seconds, including three checkpoint groups.
- [x] acting-checkpoint-final-verify.log passes formatting, fatal-warning compilation, four packages and 1,497 tests: 669 core / 726 harness / 85 MCP / 12 PostgreSQL / 5 examples, with zero compiler warnings or failed tests. acting-checkpoint-artifact-preparation.log passes all four archive/POM checks.
- [x] acting-checkpoint-artifact-consumer.log passes standalone formatting, clean compilation and forked execution against four fresh module JAR hashes. Its existing selected API smoke does not construct the checkpoint adapter; real PostgreSQL/runtime integration verifies the new capability.
- [x] review_fp and review_invariants approved final combined source/documentation closure against b7488cc and these seven identities. English inspection passed; all 2,710 local references in 78 Markdown documents passed with zero issues.

## Remote object lifetime

The current publication capability supplies read and conditional replacement, without list/delete. ExpectedRevision is valid within an address lifetime; deleting and recreating the address can introduce ABA and invalidate that guarantee. CAS publication alone cannot fence an old immutable-chain reader or an in-flight writer preparing a later-visible segment. A catalog is a descriptor index, not quiescence or retention proof.

The [retention assessment](../transcript/README.md#remote-retention-and-ownership-boundary) requires explicit all-reader/writer fencing or quiescence, generation/tombstone lifetime protection, enumeration/deletion and uncertain-delete recovery before physical reclamation. Existing memory archival remains logical aggregate publication. These are unfinished host/backend contracts, not implemented storage operations or a distributed workflow within the library. The unpublished registered assembly example selects explicit transcript bindings without adding ownership, authorization or reclamation authority.

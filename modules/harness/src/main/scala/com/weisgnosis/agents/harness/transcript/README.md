# Session transcript observations

[SessionTranscriptWriter](SessionTranscriptWriter.scala) records typed observations through a session-bound [TranscriptStore](TranscriptStore.scala). `appendMessages` returns newly committed entries; `offload` is suitable for a conversation compactor. Install the completion observer as well to record terminal turns. Recording is independent of memory extraction: summaries and thinking are eligible, user messages containing `<session_context>` are skipped, and system messages remain eligible.

Local resources follow the [scoped initialization contract](../storage/README.md#scoped-initialization): failed or cancelled opening releases ownership before returning to a still-live host scope; successful resources retain that scope's lifetime.

## Discovery across explicit sources

[TranscriptDiscovery.discover](TranscriptDiscovery.scala) combines explicitly borrowed sources, with defaults of 16 sources and 256 raw targets. `Source.catalog` enumerates a supplied catalog; `Source.local` uses bounded local discovery and a host-supplied checked basename-to-session binding. Targets retain borrowed readers without reading transcript bodies during union. Local mapping is a trusted host declaration, not proof of physical ownership.

Sources run sequentially in bounded nested scopes. Raw candidates count before deduplication. Exact duplicate bindings keep the first reader; inconsistent identity, layout or overlapping reserved addresses fail. Failure and cancellation join source cleanup. Hosts own reader lifetimes and per-source bounds. Discovery provides neither global backend enumeration nor an atomic cross-source snapshot.

## Catalog retirement

[VersionedTranscriptSessionCatalog.retire](VersionedTranscriptSessionCatalog.scala) removes an exact active binding from discoverable membership. `TranscriptRetirementAuthority` must fence relevant writers for the exact catalog address and session throughout the acquired scope; CAS alone is not that fence. Missing and already-retired bindings are nonmutating outcomes. Mismatched identities fail.

Strict version-1 catalog values are validated and normalized on read without a migration write. Publications use version 2 with Active/Retired entries. Retired bindings permanently reserve their logical identity and physical namespace and still consume capacity. Only definite conditional-write conflicts permit bounded retries within one authority scope. Uncertain failures and cancellation propagate without replay; publication may already have committed.

Retirement preserves transcript history. It performs no physical deletion, tombstone eviction, scheduling or automatic writer fencing. Custom catalog implementations default to an explicit unsupported retirement failure.

## Registered transcript writer

[SessionTranscriptWriter.registered](SessionTranscriptWriter.scala) explicitly constructs the Aggregate or Segmented store named by a checked descriptor. `new SessionTranscriptWriter(store)` remains unregistered. Hosts own the borrowed backend and catalog and select nonoverlapping addresses/subtrees. Segment and entry limits apply to Segmented; Aggregate uses the publication-attempt limit.

For nonempty projected input, append completes before exact idempotent catalog registration. The append, offload and completion-observer paths share this ordering. Duplicate stable snapshots still attempt registration, allowing an explicit retry to repair indexing without duplicating history. Empty projection creates no registration.

Registration failure or cancellation preserves committed history and propagates the failure. There is no automatic replay, rollback or transaction across the two publications. Registration grants no execution, activity or retention authority. Use the [registered assembly example](../../../../../../../../../../modules/examples/README.md#registered-transcript-assembly); registration is available explicitly and is not a default runtime behavior.

## Projection and data contract

Projection combines nonblank text/thinking in block order, then emits tool uses and results in per-kind order. Media-only and empty messages receive placeholders. Tool-input JSON is complete through 500 UTF-16 characters; larger input carries a typed truncated preview and original character count. Joined tool output uses a 1,000-character limit. Boundaries never split surrogate pairs. Result state and output block types are retained; truncated arguments are never represented as complete JSON.

[TranscriptEntry](TranscriptEntry.scala) contains a storage-generated UUID, optional parent UUID, `recordedAt`, optional structural key and payload. Keys distinguish messages, uses, results and per-kind occurrences, including repeated call IDs. Missing or blank message IDs produce anonymous drafts that append on every invocation. Stable keys retain the first observation even if subsequent content changes. Separate session capabilities provide separate deduplication domains.

Observations are lossy and cannot restore canonical `Msg` history or an agent checkpoint. Metadata, media bytes and reasoning signatures are not copied. Joined output uses canonical `ToolResult.outputText`, including separators around empty text blocks. UUID identities and structural keys replace concatenated upstream IDs. Scala DTO codecs are not Java SessionEntry wire adapters or persisted-state validators.

## Atomic store authority

`TranscriptStore.inMemory` samples time and allocates candidate UUIDs in ZIO before a pure atomic state transition. Each accepted row extends the stable-key set and current parent chain. Duplicates change neither entries nor parent; a UUID collision rejects the entire proposed batch. Concurrent writers on one capability publish whole batches. Empty and all-duplicate batches commit nothing.

`recordedAt` is observational, not monotonic ordering authority; follow sequence and parent links. Failure or cancellation after commit can leave an uncertain acknowledgement. Anonymous retries are not idempotent. The in-memory implementation supplies no durability or scheduling.

## Exact owned numeric output

Transcript rows and bounded tool-input previews use exact decimal rendering. Preview lengths are measured on that text with the same UTF-16 and surrogate-pair boundaries. Complete numeric inputs retain their values through persistence and reopening. The [domain/tool numeric contract](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/json/README.md#exact-domain-and-tool-output) describes related structured-output and checkpoint/event boundaries; direct dependency codecs are outside those guarantees.

## Verification and acceptance

Run the focused package suites with `sbt "harness / testOnly com.weisgnosis.agents.harness.transcript.*"`. Repository verification uses `sbt scalafmtAll scalafmtSbt verify`. PostgreSQL integration requires its documented database setup.

The [test package](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/README.md), [method mapping](../../../../../../../../../../docs/architecture/test-parity.md#transcript-observation-applicability) and [tracker](../../../../../../../../../../docs/architecture/tracker.md) describe coverage and remaining obligations. Passing suites do not establish exhaustive upstream parity or hostile-filesystem safety.

## Versioned remote transcript

[VersionedTranscriptStore.make](VersionedTranscriptStore.scala) is a pure checked factory over one host-selected `VersionedJsonStore` and `StorageAddress`. `maxAttempts` defaults to eight and must be positive. Missing storage reads as empty; empty append creates no aggregate.

Nonempty append samples time and candidate UUIDs once. The shared state transition validates the batch and derives parents from the current persistent tail. Definite CAS conflicts allow bounded reread using those same candidates; backend failures, cancellation and uncertain acknowledgements propagate without replay. All-duplicate batches perform no write. Anonymous retries remain nonidempotent.

The private version-1 aggregate requires `schemaVersion` and `entries`. Strict decoding rejects duplicate fields, malformed Unicode, unsupported versions, ignored fields and coercions before validating the complete chain, stable keys, parents and previews. Encoding applies the same validation and exact numeric rendering. Every read validates the whole aggregate and every update replaces it; hosts bound growth and backend payload size.

This remote envelope does not import local logs automatically or reproduce upstream file layouts. It grants no authenticated checkpoint claim, execution authority or exactly-once delivery. Backend revision and uncertainty follow the [versioned publication contract](../storage/README.md#versioned-json-publication). Explicit catalog/discovery and segmented storage are separate capabilities; remote physical pruning still requires a host protocol.

## Immutable transcript segments

[SegmentedTranscriptStore.make](SegmentedTranscriptStore.scala) uses a host-selected manifest and the reserved namespace `manifest.namespace ++ Chunk(manifest.key, "segments")`, with UUID segment keys. Hosts exclusively own the manifest and subtree and must not mutate, delete, recreate or repurpose visible segments.

`TranscriptSegmentLimits` requires positive limits, defaulting to 1,024 segments, 100,000 visible entries and eight publication attempts. Reads capture a manifest head and validate the entire predecessor chain. Missing, cyclic, corrupt or oversized chains fail. These count bounds are not payload-byte limits or pagination.

Version-1 manifests and immutable segments use strict canonical decoding, exact segment identity and revision checks, and complete transcript validation. Each attempt prepares newly accepted entries in a fresh segment before CAS-publishing the head. Entry UUIDs/time are reused across definite conflicts; segment UUIDs are fresh per attempt. Address collisions fail. Empty/all-duplicate append performs no write even at capacity.

Preparation and head publication are not atomic together: conflicts or failures can leave orphan segments. There is no uncertain-write retry or automatic garbage collection. Explicit `compact` publishes one immutable block with the exact visible entries, preserving IDs, times, keys, payloads and order; empty/single-block chains are noops. Old blocks remain. Cancellation joins owned cleanup but may follow a committed publication. No automatic aggregate migration or safe reclamation is implied.

## Explicit transcript session catalog

[TranscriptSession](VersionedTranscriptSessionCatalog.scala) is a checked ID, manifest address and Aggregate/Segmented layout. [VersionedTranscriptSessionCatalog.make](VersionedTranscriptSessionCatalog.scala) is pure, with positive defaults of 256 bindings and eight publication attempts. Capacity includes active and retired bindings.

Strict original version-1 values are read-only migrations to active bindings; writers publish canonical version 2. Validation rejects malformed Unicode, duplicates, unsupported shapes, invalid ordering, conflicting logical identities and physical namespace overlap, including overlap with the catalog itself. `sessions` lists active descriptors from this supplied catalog only.

Registration is exact and idempotent for active bindings. Retired bindings cannot be resurrected or retargeted. Only definite CAS conflicts allow bounded retries; uncertain publication is not replayed. Membership proves neither content validity nor liveness, ownership or authorization. There is no global backend listing or cross-store transaction. Registered writers provide the explicit append-before-index integration described above.

## Remote retention and ownership boundary

`VersionedJsonStore` supplies no global listing or deletion. A changed head, invisible segment or retired catalog binding does not prove that readers and writers are quiescent: captured readers may need old segments and prepared writers may still publish them.

Physical remote reclamation requires explicit host fencing, bounded enumeration/deletion and uncertain-deletion recovery. Timestamps and catalog membership alone grant no such authority. Deleting and recreating addresses can also invalidate revision-lifetime assumptions. Local pruning does not implement this remote protocol.

## Scoped local transcript store

[LocalTranscriptStore.open](LocalTranscriptStore.scala) requires an existing directory and explicit `WorkspaceTrust.HostControlled`. The shared transcript writer lease protects complete inspection and publication. Active or stale leases fail without takeover. Canonical/sensitive-path and regular-file checks rely on host-controlled ancestors; they are not an atomic filesystem sandbox.

The fixed payload is `transcript.jsonl`. Legacy linear rows remain readable. A version-2 local recovery document preserves a selected linear continuation and retained branches; ordinary `entries` exposes only the selected chain, and append preserves the archives. Remote aggregate/segment formats are separate.

Publication uses a same-parent temporary file and native atomic replacement, with no non-atomic fallback or fsync/power-loss guarantee. Admitted filesystem work and cleanup join before ownership is released; slow blocking work can delay cancellation. Process death can leave temporary files. Failed opening promptly unwinds ownership through scoped initialization.

## Local and remote handoff

[LocalTranscriptHandoff.open](LocalTranscriptHandoff.scala) takes an existing local directory, explicit trust, exact `TranscriptSession` binding, borrowed scoped remote reader and `TranscriptHandoffAuthority`. Existing local data loads without contacting the remote reader or authority. Missing local data imports the exact validated remote chain; an empty remote chain creates an explicit empty payload, distinguishing cold initialization from absence.

Explicit `sync` contacts the remote source under host authority and the local lease. Authority receives the canonical destination and exact binding and must fence the relevant handoff while held. The reader receives no writes. Callbacks must not reenter this owner/store or close its scope; hosts bound callback lifetimes and prevent cross-owner cycles. No background mirror, execution restoration or inferred remote authority is supplied.

Cold import and prefix reconciliation preserve original entry IDs, times, parents, keys and payloads without allocating replacement identities or timestamps. Divergence is retained in one atomic version-2 recovery document: every branch remains a valid strict linear chain. `store` continues the selected local chain through ordinary append, including after reopening. `recoveredView` presents the latest remote chain first, then retained branches and local-only IDs, deduplicating exact entries. Conflicting data for the same ID fails without replacing either original. Shorter remote observations do not discard retained remote-only entries. Union order is an observation view, not fabricated parentage or an agent checkpoint.

Recovery admits at most 16 retained remote branches, 4,096 raw entries across the selected chain and branches, and 1 MiB of encoded recovery data. The encoded-byte limit also applies to linear cold imports and prefix publications through handoff. These are admission bounds, not streaming or total heap bounds; ordinary legacy linear append retains its existing behavior. No archive eviction occurs. Older binaries fail closed on the new document; legacy linear files remain readable.

Local discovery still selects the same fixed payload. Retention validates the recovery document and computes activity across the complete recovered union, so an archived recent entry protects the whole target. Failed or uncertain publication is not automatically replayed, and remote storage is never mutated.

## Terminal canonical snapshot recording

Install `writer.completionObserver(policy)` through `ReActAgent.make` or its builder. The policy is mandatory: `RequireStableIds` rejects eligible anonymous drafts before append; `RecordEachSnapshot` accepts repeated anonymous observations. Stable keys still deduplicate. Skipped session-context messages need no ID. The adapter never changes canonical message identities, including runtime-generated anonymous messages.

The observer records success, final failure, timeout and reconciled cancellation once per admitted invocation. Storage failures map to `CompletionObservationError` and propagate without uncertain replay. Keep the store alive around every invocation. Install compactor offload separately to preserve prefixes removed before a terminal snapshot; offload retains its own anonymous-append behavior.

Required observation finishes before successful result publication and cancellation return. Late cancellation can coexist with an already committed `AgentResultEvent`. Direct calls and drained streams expose observer errors; an early stream consumer cannot treat its own completion as persistence acknowledgement. Observers must not await operations needing the same held agent run lock. See the [core completion contract](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md).

## Explicit local transcript pruning

[LocalTranscriptRetention.prune](LocalTranscriptRetention.scala) acquires the writer lease for one existing host-selected directory and explicit exclusive `Instant` cutoff. Active or stale ownership refuses pruning without takeover. Missing payload reports Missing; missing directory fails; empty payload is valid. The complete strict document, including retained recovery branches, is validated before deletion. Corruption is preserved for diagnosis.

Activity is the maximum file modification time and every persisted `recordedAt` across the recovered union. Backward clocks make the final row insufficient. Strictly earlier activity is pruned; equality or future activity retains it. Results report activity and the removed union-entry count. Filesystem timestamp precision remains platform dependent.

Deletion removes only the validated fixed payload, with no recursion or sibling/directory removal. It may complete before pending cancellation is delivered, so failure is not proof of unchanged storage. No rollback, undelete, crash guarantee or hostile-path protection is supplied. Hosts must retire the logical session: reopening after deletion starts a new chain and can reuse prior stable keys. Explicit target selection grants no user authentication or inactivity proof.

## Current pruning identities

The pruning implementation is [LocalTranscriptRetention](LocalTranscriptRetention.scala), using the shared [TranscriptFiles](TranscriptFiles.scala) validation and lease boundary. [LocalTranscriptRetentionSpec](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/LocalTranscriptRetentionSpec.scala) covers its current contract. Source history retains prior revisions; remote physical reclamation remains a separate host integration.

## Explicit transcript retention policy

[TranscriptRetentionPolicy](TranscriptRetentionPolicy.scala) selects KeepAll or PruneAfter checked positive `RetentionDays`, with an explicit 180-day default. Days are exact 24-hour intervals, not zoned calendar subtraction. Cutoff calculation preserves nanoseconds and returns typed `InvalidConfiguration` on underflow.

`pruneWithPolicy` is additive. KeepAll bypasses Clock, path/trust validation and payload access. Enabled policy samples time once, validates the cutoff before acquiring ownership and delegates to the same pruner. Disabled differs from Evaluated(cutoff, Missing/Retained/Pruned). The older explicit-cutoff API does not silently apply the default. No timer, discovery or separate maintenance authority is installed.

## Current transcript-policy identities

Current policy contracts live in [TranscriptRetentionPolicy](TranscriptRetentionPolicy.scala) and the shared [memory retention policy](../memory/MemoryRetentionPolicy.scala). [Policy tests](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/TranscriptRetentionPolicySpec.scala) and [local policy integration](../../../../../../../test/scala/com/weisgnosis/agents/harness/transcript/LocalTranscriptRetentionPolicySpec.scala) cover validation, cutoff arithmetic and KeepAll bypass. Existing explicit-cutoff callers retain their behavior.

## Ordered explicit retention batches

[TranscriptRetentionTarget](TranscriptRetentionTarget.scala) pairs an exact checked correlation ID with a suspended prune capability. IDs are not paths or authorization. Different IDs can alias one directory; a later alias may report Missing after an earlier deletion. Custom capabilities own their resource contracts.

[TranscriptRetentionBatch](TranscriptRetentionBatch.scala) rejects duplicate IDs before invocation. KeepAll bypasses Clock, discovery and targets. Enabled preparation samples time once, checks cutoff and selection, then captures sequential traversal in supplied order without executing targets. Empty enabled batches return an empty evaluated result. Prepared execution is replayable, not a durable once-only plan.

Failure, defect or cancellation stops later targets and preserves the cause; earlier effects remain committed. Neither explicit selection nor cold discovery supplies inactivity proof or a multi-file transaction. See [selected transcript maintenance](../memory/README.md#selected-transcript-maintenance) for coordinator ordering and partial-progress handling.

## Bounded local transcript discovery

[LocalTranscriptDiscovery.discover](LocalTranscriptDiscovery.scala) scans one explicit HostControlled root without mutation. Checked defaults are 4,096 immediate entries and 256 targets; ignored entries count. Selected directories are sorted by filename, which becomes the target ID. Only a present regular `transcript.jsonl` selects a directory. Genuinely absent payloads are skipped; missing roots and other filesystem errors fail. No directories are created.

Raw traversal and sensitive/nonregular/symlink entries are rejected. Selected paths must retain canonical identity and direct parentage. Enumeration closes before validation. Host-controlled ancestors remain required; these checks do not establish a hostile-filesystem sandbox or cancellation deadline.

`TranscriptRetentionBatch.fromDiscovery` retains an injected cold selection effect. Each enabled preparation gets a fresh snapshot and validates IDs; KeepAll bypasses discovery and Clock. The memory coordinator finishes selection/cutoff validation before memory work. Discovery acquires no writer lease, parses no transcript body and proves no inactivity. Pruning later acquires ownership and validates the complete body, including recovery archives; later failure may follow earlier committed maintenance effects. Hosts own logical retirement and receipt handling.

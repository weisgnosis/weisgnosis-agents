# PostgreSQL integration verification

These suites use actual PostgreSQL connections through independently constructed adapters. Set `WEISGNOSIS_POSTGRES_URL`, `WEISGNOSIS_POSTGRES_USER` and `WEISGNOSIS_POSTGRES_PASSWORD` for a disposable database, then run `sbt "storagePostgresql / test"`. Without the URL the tests are skipped; skipping is not backend verification.

The fixture initializes the fixed public table and deletes only its generated test namespaces. Use an isolated database and host-selected credentials. Tests use live-clock bounded coordination and driver connection/socket/query limits. CI provisions PostgreSQL 16; successful local checks do not establish remote workflow execution. No model provider is contacted.

[PostgresVersionedJsonStoreSpec](PostgresVersionedJsonStoreSpec.scala) covers exact JSON and namespace identity, malformed Unicode, create/stale-revision races, masked admitted-write cancellation, joined cleanup, exhaustion and corrupt stored data. Its read-only transaction fixture requires SQLSTATE 25006 and bounded exception classification without exposing payload, URL, options or namespace. See the [backend contract](../../../../../../../main/scala/com/weisgnosis/agents/storage/postgresql/README.md).

## Subagent terminal observations

[PostgresSubagentTerminalStoreSpec](PostgresSubagentTerminalStoreSpec.scala) covers durable Pending/Running/terminal publication, worker/creation/check metadata, immutable acknowledgement, namespace isolation and publication races. Actual ReAct task tools observe mixed stored states without creating executions. Bound inspection/wait/catalog/output integrations acknowledge exact final persisted projections, including Unicode beyond preview length. Reminders and explicit delivery retain selected IDs and first acknowledgement stamps.

Remote-origin cases persist Awaiting and generation-bound ConfirmationClaimed records through fresh adapters, reject stale competing claims, preserve claims while Running and resolve them on terminal publication. Stored origin and approval data grant no remote transport or distributed execution authority. Local physical storage and host-driven execution recovery have separate contracts.

## Remote memory integration

[VersionedMemoryRepositorySpec](VersionedMemoryRepositorySpec.scala) uses independent repositories to verify competing appends, reopened active-day enumeration, namespace isolation, stale consolidation, exact acknowledged/archive content, malformed aggregate rejection, definite conflict retries and uncertain acknowledgement without replay. Wrappers delegate actual database operations. Discovery includes acknowledged active days and excludes archived days; it does not merge independent stores' content.

## Remote transcript integration

[VersionedTranscriptStoreSpec](VersionedTranscriptStoreSpec.scala) covers persistent parent linkage, stable-key first observations, repeatable anonymous appends, fixed candidate identities/time across definite conflicts, strict malformed state, bounded attempts and ambiguous acknowledgement. These aggregate tests do not establish global discovery, physical deletion, Java file formats or mirror synchronization.

## Acting checkpoint integration

[ActingCheckpointStoreSpec](ActingCheckpointStoreSpec.scala) verifies conditional checkpoint publication and restoration through current host runtime bindings. Invalid persisted state is rejected; uncertain publication is not replayed automatically. Authentication, exclusive execution and side-effect replay protection remain host responsibilities.

## Persistent memory admission

[VersionedMemoryTriggerGateSpec](VersionedMemoryTriggerGateSpec.scala) verifies persistent admission and bounded conditional publication with injected clock effects. It does not establish synchronized production clocks, exclusive execution, completion or exactly-once delivery.

## Immutable transcript publication

[SegmentedTranscriptStoreSpec](SegmentedTranscriptStoreSpec.scala) verifies immutable segments, strict predecessor chains and explicit compaction. Compaction preserves entries, collapses visible segments, retains concurrent appends and propagates uncertain publication/cancellation without blind retry or reclamation. Hosts own the backend and reserved subtree.

## Explicit transcript catalog

[VersionedTranscriptSessionCatalogSpec](VersionedTranscriptSessionCatalogSpec.scala) verifies independent registration/reopen/sorting/isolation, layout/subtree/capacity conflicts, strict schema and conditional-publication limits. Registered writers in both layouts append and index one stable observation. Exact retirement under an explicit fixture authority removes reopened membership, refuses resurrection and retains independently read transcript history. This is known-catalog membership, not global backend enumeration, physical deletion or proof of a distributed writer fence.

## Remote memory middleware integration

[MemoryLifecycleIsolationSpec](MemoryLifecycleIsolationSpec.scala) exercises four scope variants in both flush/maintenance orders using the harness oracle and independent reopened gate bindings. Extraction/report/curated/idle effects and reopened curated data are checked. Separate per-row addresses do not prove cross-scope collision behavior; host restart, distributed delivery and physical retention have separate ownership requirements.

## Cold-schema provisioning

Fixture users serialize schema creation through one advisory key on an explicitly scoped, nonpooled `PGSimpleDataSource` connection. The five-second lock acquisition query is masked and joined before release; result set, statement and physical session close after failure or interruption. Only provisioning is serialized. Namespace-isolated DML and contention tests remain concurrent; no retry hides their failures.

The original cold-schema regression identified concurrent `CREATE TABLE` and SQLSTATE 23505 in PostgreSQL's type catalog. That creation-stage diagnosis does not explain a separate historical connection-acquisition failure. Fixture serialization is not a production migration or distributed provisioning service.

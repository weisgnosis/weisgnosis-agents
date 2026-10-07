# PostgreSQL versioned JSON backend

[PostgresVersionedJsonStore](PostgresVersionedJsonStore.scala) implements the public harness [VersionedJsonStore port](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/storage/README.md#versioned-json-publication). Pure make validates a positive queryTimeoutSeconds (default 30); construction performs no I/O. Every initialize/read/compareAndSet operation suspends JDBC and borrows a fresh host DataSource connection, requiring autocommit. The host owns the pool, driver and credentials.

StorageAddress preserves exact nonblank namespace components and key spelling. Components reject malformed Unicode, NUL and U+001F; serialized namespaces use U+001F separators. Serialized namespace and key each fit 512 UTF-8 bytes, with no trimming, normalization or case folding. StorageRevision is a checked positive signed Long. ExpectedRevision.Absent inserts revision 1 only when absent; AtRevision replaces only the exact current revision and advances it by one. ConditionalWrite.Conflict changes nothing. Expected Long.MaxValue fails as exhausted even if it would otherwise conflict. Deletion or external replacement invalidates revision lifetime guarantees and lies outside this capability.

The fixed public.weisgnosis_versioned_json table stores namespace_path and item_key as TEXT COLLATE "C", exact JSON in value_json TEXT, and a positive BIGINT revision, with a composite primary key and 512-byte address checks. TEXT preserves arbitrary checked JSON, numeric precision and object order without JSONB coercion. Recursive duplicate members and malformed Unicode in object keys or nested string values fail before publication; stored decoding applies the same payload validation. Valid Unicode, including astral characters and escaped control characters, remains supported. This backend-local immutable traversal does not change generic JsonBoundary validation. There is no delete, search, list or unconditional write API.

initialize explicitly issues CREATE TABLE IF NOT EXISTS. It is idempotent DDL, not schema verification or migration; an existing table must already satisfy the documented contract. Ordinary reads/writes do not initialize the schema. Hosts own DDL permissions, deployment, recovery and any external modification policy.

Admitted JDBC operations and resource cleanup are masked and joined, then an interruptible acknowledgement checkpoint restores pending interruption. A failed operation preserves its original failure/cleanup Cause together with pending interruption. A canceled acknowledgement may follow a committed write; cancellation does not prove nonpublication. Statement query timeout is not a total operation/cancellation deadline. The host must bound connection acquisition, login and socket behavior. Connections, statements and result sets close within each owned Scope; release failures remain observable defects. Typed operational failures use AgentError.StorageError without exposing arbitrary driver diagnostics.

This is a per-address conditional publication prerequisite. Remote memory/transcript/checkpoint assembly, multi-address transactions, durable workflow replay, authentication and exclusive execution claims remain separate contracts.

## Sanitized diagnostics

Direct SQLException failures retain only their exception class and an optional SQLSTATE whose spelling is exactly five uppercase ASCII letters/digits. Missing or malformed states are omitted. SQLSTATE 22003 keeps the existing specific numeric-revision-limit message, with priority over generic classification. Non-SQL failures remain class-only. Driver messages, SQL text, URLs, credentials, cause chains and arbitrary diagnostics are not copied. This adds no retry, connection recovery or public error-case/signature change.

review_fp and review_invariants approved the two exact diagnostic source/spec identities below; full verification/artifact checks pass and final combined documentation acceptance is approved. It does not establish the cause of the earlier unexplained PostgreSQL acquisition failure; that investigation remains open.

## Verification and acceptance

The current bounded increment against 2598250 strengthens sanitized diagnostics and the existing real PostgreSQL backend-failure group. Existing address/CAS/Unicode/cancellation contracts above remain separately accepted; changed identities require renewed approval.

| Current implementation/regression | Git blob |
| --- | --- |
| [PostgresVersionedJsonStore.scala](PostgresVersionedJsonStore.scala) | `3fb019e16e7808427ef5d8792f7525e34547e0bc` |
| [PostgresVersionedJsonStoreSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/storage/postgresql/PostgresVersionedJsonStoreSpec.scala) | `e681d076c61fe42c2982b9eee0a7dc5876d032a5` |

The [shared fixture identity](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md#versioned-remote-memory) retains scoped JDBC ownership and live random namespace isolation. Driver/pool deployment remains host-owned; [candidate preparation](../../../../../../../../../../verification/artifact-consumer/README.md#candidate-preparation-acceptance) validates actual packages rather than establishing a published ABI.

- [x] postgres-diagnostics-red.log records two passes/one failed group against unchanged backend 6d1b55318fce559cba03a5ee8b90570610c43301: the real read-only transaction produced class-only StorageError without required SQLSTATE=25006. This is a newly selected diagnostic capability gap, not proof of the earlier connection failure's cause.
- [x] review_fp approved FP/ZIO correctness and review_invariants approved invariants for the two exact formatted source/spec identities against 2598250, with no unresolved source findings.
- [x] postgres-diagnostics-green.log passes all three real PostgreSQL groups with zero failed/ignored in 2.335 seconds, including safe SQLSTATE=25006 from the actual read-only transaction.
- [x] postgres-diagnostics-verify.log passes 1,526 tests (680 core / 730 harness / 86 MCP / 22 real PostgreSQL / 8 examples), zero compiler warnings/failures/ignored, and four RC1 packages.
- [x] postgres-diagnostics-preparation.log passes exact source/POM/LICENSE staging and reversible invalid-selector/extra/duplicate source-archive rejection; postgres-diagnostics-consumer.log passes standalone formatting, clean compilation and forked execution against four fresh actual RC1 module hashes. The unchanged selected consumer does not separately trigger the new database diagnostic.
- [x] review_fp and review_invariants approved the final nine-file combined scope against 2598250 with no unresolved findings; manual English inspection, changed-script/diff checks and 78-document/2,978-local-reference checks pass.
- [ ] Diagnose the separately retained unexplained connection acquisition failure; subsequent successful isolated/full runs do not identify its cause.

Regression expectations remain contract-derived: cancellation acknowledges interruption only after joined cleanup and preserves an independent timeout failure even when a write committed; malformed payload Unicode produces StorageError without publication, while valid astral keys/values and escaped controls remain intact. These behaviors are exercised in the existing three-group suite without treating cancellation as rollback or driver replacement encoding as valid storage.

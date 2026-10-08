# PostgreSQL versioned JSON backend

[PostgresVersionedJsonStore](PostgresVersionedJsonStore.scala) implements the public harness [VersionedJsonStore port](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/storage/README.md#versioned-json-publication). Pure make validates a positive queryTimeoutSeconds (default 30); construction performs no I/O. Every initialize/read/compareAndSet operation suspends JDBC and borrows a fresh host DataSource connection, requiring autocommit. The host owns the pool, driver and credentials.

StorageAddress preserves exact nonblank namespace components and key spelling. Components reject malformed Unicode, NUL and U+001F; serialized namespaces use U+001F separators. Serialized namespace and key each fit 512 UTF-8 bytes, with no trimming, normalization or case folding. StorageRevision is a checked positive signed Long. ExpectedRevision.Absent inserts revision 1 only when absent; AtRevision replaces only the exact current revision and advances it by one. ConditionalWrite.Conflict changes nothing. Expected Long.MaxValue fails as exhausted even if it would otherwise conflict. Deletion or external replacement invalidates revision lifetime guarantees and lies outside this capability.

The fixed public.weisgnosis_versioned_json table stores namespace_path and item_key as TEXT COLLATE "C", exact JSON in value_json TEXT, and a positive BIGINT revision, with a composite primary key and 512-byte address checks. TEXT preserves arbitrary checked JSON, numeric precision and object order without JSONB coercion. Recursive duplicate members and malformed Unicode in object keys or nested string values fail before publication; stored decoding applies the same payload validation. Valid Unicode, including astral characters and escaped control characters, remains supported. This backend-local immutable traversal does not change generic JsonBoundary validation. There is no delete, search, list or unconditional write API.

initialize explicitly issues CREATE TABLE IF NOT EXISTS. It is idempotent DDL, not schema verification or migration; an existing table must already satisfy the documented contract. Ordinary reads/writes do not initialize the schema. Hosts own DDL permissions, deployment, recovery and any external modification policy.

Admitted JDBC operations and resource cleanup are masked and joined, then an interruptible acknowledgement checkpoint restores pending interruption. A failed operation preserves its original failure/cleanup Cause together with pending interruption. A canceled acknowledgement may follow a committed write; cancellation does not prove nonpublication. Statement query timeout is not a total operation/cancellation deadline. The host must bound connection acquisition, login and socket behavior. Connections, statements and result sets close within each owned Scope; release failures remain observable defects. Typed operational failures use AgentError.StorageError without exposing arbitrary driver diagnostics.

This is a per-address conditional publication prerequisite. Remote memory/transcript/checkpoint assembly, multi-address transactions, durable workflow replay, authentication and exclusive execution claims remain separate contracts.

## Sanitized diagnostics

Direct SQLException failures retain only their exception class and an optional SQLSTATE whose spelling is exactly five uppercase ASCII letters/digits. Missing or malformed states are omitted. SQLSTATE 22003 keeps the existing specific numeric-revision-limit message, with priority over generic classification. Non-SQL failures remain class-only. Driver messages, SQL text, URLs, credentials, cause chains and arbitrary diagnostics are not copied. This adds no retry, connection recovery or public error-case/signature change.

## Verification and acceptance

The [backend suite](../../../../../../../test/scala/com/weisgnosis/agents/storage/postgresql/PostgresVersionedJsonStoreSpec.scala) exercises publication, exact JSON/Unicode, conditional conflicts, sanitized diagnostics and joined cancellation against a disposable PostgreSQL database. An actual read-only transaction verifies SQLSTATE=25006 without copying private driver text. Missing fixture configuration skips database scenarios and does not verify the backend.

The [shared fixture](../../../../../../../test/scala/com/weisgnosis/agents/storage/postgresql/README.md#cold-schema-provisioning) owns test connections and namespace isolation. Driver/pool deployment remains host-owned. The [artifact consumer](../../../../../../../../../../verification/artifact-consumer/README.md#candidate-preparation-acceptance) checks consumable packages; it does not establish a published binary API.

The older unexplained connection-acquisition failure has no established cause. Subsequent successful runs and sanitized diagnostic coverage do not resolve that investigation.

Regression expectations remain contract-derived: cancellation acknowledges interruption only after joined cleanup and preserves an independent timeout failure even when a write committed; malformed payload Unicode produces StorageError without publication, while valid astral keys/values and escaped controls remain intact. These behaviors are exercised in the existing three-group suite without treating cancellation as rollback or driver replacement encoding as valid storage.

## Schema provisioning

The host serializes initial schema provisioning. `initialize` uses repeatable `CREATE TABLE IF NOT EXISTS`, which neither verifies an existing schema nor guarantees concurrent creation is safe. Reads and writes do not provision or migrate tables. The shared disposable test fixture serializes only initialization using a scoped physical connection and a fixed session advisory lock; independent DML remains concurrent. This fixture mechanism is not installed by the production backend.

The [fixture contract](../../../../../../../test/scala/com/weisgnosis/agents/storage/postgresql/README.md#cold-schema-provisioning) describes serialization of the reproduced cold-schema catalog race. The separately retained older connection-acquisition failure still has no established cause.

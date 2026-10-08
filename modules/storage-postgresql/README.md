# Optional PostgreSQL storage

This module implements the harness VersionedJsonStore port using PostgreSQL. It depends on harness; core, harness, and MCP do not acquire a PostgreSQL dependency. The host supplies a DataSource and owns the pool, JDBC driver, database, and connection/login/socket deadlines. The JDBC driver in this repository is a test dependency.

## Use the backend

Create [PostgresVersionedJsonStore](src/main/scala/com/weisgnosis/agents/storage/postgresql/PostgresVersionedJsonStore.scala) through its make factory with the host DataSource. Provision its schema through initialize under serialized host control, then bind harness adapters to explicit checked StorageAddress values. Repeated DDL is not a concurrent migration mechanism.

The [backend contract](src/main/scala/com/weisgnosis/agents/storage/postgresql/README.md) describes schema ownership, address identity, revision exhaustion, strict JSON/Unicode handling, and sanitized SQLSTATE diagnostics. The [harness storage contract](../harness/src/main/scala/com/weisgnosis/agents/harness/storage/README.md) explains conditional publication and acting checkpoints. [Memory](../harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md) and [transcript](../harness/src/main/scala/com/weisgnosis/agents/harness/transcript/README.md) adapters provide bounded aggregates and selected segmented layouts over the same port.

## Ownership and limits

Compare-and-set publication advances a revision at one exact address. It does not grant execution ownership, authenticate a resume, or provide exactly-once delivery. A failed or cancelled write can have an uncertain outcome; callers must not infer rollback or retry side effects automatically. Adapters retry only the documented definite conflicts.

The host controls namespaces, table access, migration, deadlines, and backend lifetime. Persisted task observations and checkpoint data do not resurrect runtimes. Catalogs discover explicitly registered descriptors rather than arbitrary global backend contents. Segment reclamation, broader retention/layout migration, and durable host recovery remain separate work in the [tracker](../../docs/architecture/tracker.md).

## Testing

For actual backend tests, use a disposable database and set WEISGNOSIS_POSTGRES_URL, WEISGNOSIS_POSTGRES_USER, and WEISGNOSIS_POSTGRES_PASSWORD. From the repository root, run `sbt "storagePostgresql / test"`. The [fixture guide](src/test/scala/com/weisgnosis/agents/storage/postgresql/README.md) explains table initialization, generated test namespaces, and cleanup.

Without the database URL these suites are skipped; a skipped suite is not PostgreSQL verification. Run `sbt verify` with the same environment for repository checks including database integration. Local execution does not establish a remote CI result. See the [build guide](../../project/README.md#local-artifact-packaging) for installing the optional artifact.

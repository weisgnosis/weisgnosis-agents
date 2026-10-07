# PostgreSQL versioned JSON backend

[PostgresVersionedJsonStore](PostgresVersionedJsonStore.scala) implements the public harness [VersionedJsonStore port](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/storage/README.md#versioned-json-publication). Pure make validates a positive queryTimeoutSeconds (default 30); construction performs no I/O. Every initialize/read/compareAndSet operation suspends JDBC and borrows a fresh host DataSource connection, requiring autocommit. The host owns the pool, driver and credentials.

StorageAddress preserves exact nonblank namespace components and key spelling. Components reject malformed Unicode, NUL and U+001F; serialized namespaces use U+001F separators. Serialized namespace and key each fit 512 UTF-8 bytes, with no trimming, normalization or case folding. StorageRevision is a checked positive signed Long. ExpectedRevision.Absent inserts revision 1 only when absent; AtRevision replaces only the exact current revision and advances it by one. ConditionalWrite.Conflict changes nothing. Expected Long.MaxValue fails as exhausted even if it would otherwise conflict. Deletion or external replacement invalidates revision lifetime guarantees and lies outside this capability.

The fixed public.weisgnosis_versioned_json table stores namespace_path and item_key as TEXT COLLATE "C", exact JSON in value_json TEXT, and a positive BIGINT revision, with a composite primary key and 512-byte address checks. TEXT preserves arbitrary checked JSON, numeric precision and object order without JSONB coercion. Recursive duplicate members and malformed Unicode in object keys or nested string values fail before publication; stored decoding applies the same payload validation. Valid Unicode, including astral characters and escaped control characters, remains supported. This backend-local immutable traversal does not change generic JsonBoundary validation. There is no delete, search, list or unconditional write API.

initialize explicitly issues CREATE TABLE IF NOT EXISTS. It is idempotent DDL, not schema verification or migration; an existing table must already satisfy the documented contract. Ordinary reads/writes do not initialize the schema. Hosts own DDL permissions, deployment, recovery and any external modification policy.

Admitted JDBC operations and resource cleanup are masked and joined, then an interruptible acknowledgement checkpoint restores pending interruption. A failed operation preserves its original failure/cleanup Cause together with pending interruption. A canceled acknowledgement may follow a committed write; cancellation does not prove nonpublication. Statement query timeout is not a total operation/cancellation deadline. The host must bound connection acquisition, login and socket behavior. Connections, statements and result sets close within each owned Scope; release failures remain observable defects. Typed operational failures use AgentError.StorageError without exposing arbitrary driver diagnostics.

This is a per-address conditional publication prerequisite. Remote memory/transcript/checkpoint assembly, multi-address transactions, durable workflow replay, authentication and exclusive execution claims remain separate contracts.

## Verification and acceptance

| Reviewed source/build | Git blob |
| --- | --- |
| [ci.yml](../../../../../../../../../../.github/workflows/ci.yml) | [Current candidate preparation identity](../../../../../../../../../../verification/artifact-consumer/README.md#candidate-preparation-acceptance) |
| [build.sbt](../../../../../../../../../../build.sbt) | [Current candidate preparation identity](../../../../../../../../../../verification/artifact-consumer/README.md#candidate-preparation-acceptance) |
| [AgentError.scala](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/error/AgentError.scala) | `a27cfd268bc615a5c0211fd3c0388add55a13e1d` |
| [AgentErrorSpec.scala](../../../../../../../../../core/src/test/scala/com/weisgnosis/agents/core/error/AgentErrorSpec.scala) | `dcfd9032e358a9a2b87c197119ab24d5b093e84f` |
| [ArtifactSmoke.scala](../../../../../../../../../../verification/artifact-consumer/ArtifactSmoke.scala) | [Current candidate preparation identity](../../../../../../../../../../verification/artifact-consumer/README.md#candidate-preparation-acceptance) |
| [build.sbt](../../../../../../../../../../verification/artifact-consumer/build.sbt) | [Current candidate preparation identity](../../../../../../../../../../verification/artifact-consumer/README.md#candidate-preparation-acceptance) |
| [prepare.py](../../../../../../../../../../verification/artifact-consumer/prepare.py) | [Current candidate preparation identity](../../../../../../../../../../verification/artifact-consumer/README.md#candidate-preparation-acceptance) |
| [VersionedJsonStore.scala](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/storage/VersionedJsonStore.scala) | `5ae24e788910c42092ed764d7830e47e1d7fb27f` |
| [VersionedJsonStoreSpec.scala](../../../../../../../../../harness/src/test/scala/com/weisgnosis/agents/harness/storage/VersionedJsonStoreSpec.scala) | `c977075cf98640a121d9405ece4c19f097417843` |
| [PostgresVersionedJsonStore.scala](PostgresVersionedJsonStore.scala) | `6d1b55318fce559cba03a5ee8b90570610c43301` |
| [PostgresVersionedJsonStoreSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/storage/postgresql/PostgresVersionedJsonStoreSpec.scala) | [Current shared-fixture identity](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md#versioned-remote-memory) |


- [x] review_fp and review_invariants renewed approval of all eleven final source/spec/build identities, including the payload-Unicode correction, with no unresolved findings.
- [x] postgresql-unicode-green.log passes all three real PostgreSQL 16 groups in 2.179 seconds, including valid astral keys/values and rejection of malformed payload Unicode without publication. Existing groups were extended; no test groups were added.
- [x] postgresql-final-verify.log passes formatting, fatal-warning compilation, all four artifact packages and 1,486 tests: 667 core / 726 harness / 85 MCP / 3 PostgreSQL / 5 examples, with zero compiler warnings or failed tests.
- [x] postgresql-final-artifact-preparation.log validates eight archives/four POMs, exact sources and dependency scopes.
- [x] postgresql-final-artifact-consumer.log passes standalone formatting, clean compilation and forked execution against four fresh actual module JAR hashes, with no PostgreSQL JDBC fixture dependency on the runtime classpath.
- [x] review_fp and review_invariants approved final source/documentation closure; English prose and all 2,634 local references in 78 Markdown documents pass inspection.

The real PostgreSQL 16 regression postgresql-cancellation-audit-live.log recorded two passes/one failed group against backend blob 399693f80c86cb07898ff67c1e1e356e409e4972 and unchanged spec db7b3faca89b9e842c8ef0a366121eb024d71596. Cancellation incorrectly returned Applied(2), and canceled query timeout retained StorageError without interruption. The public contract requires interrupted acknowledgement after joined cleanup, preserving an independent timeout failure while allowing the committed revision to remain. postgresql-cancellation-green.log passes the same three groups against corrected backend 39bc9dbcf9f9895337714718863c0efb1947b6a2 and unchanged spec, in 2.120 seconds. The current final Unicode correction has the renewed acceptance gates above.

The payload Unicode regression postgresql-unicode-red.log recorded two passes/one failed group against backend 39bc9dbcf9f9895337714718863c0efb1947b6a2 and spec 122844ce4110c0812bb7d100e714c645cc6e2188: malformed string values/object keys were published instead of producing StorageError with the address remaining absent. postgresql-unicode-green.log passes that unchanged spec against final backend 6d1b55318fce559cba03a5ee8b90570610c43301. Expected rejection follows exact Unicode preservation, independently of the JDBC encoder's replacement behavior.

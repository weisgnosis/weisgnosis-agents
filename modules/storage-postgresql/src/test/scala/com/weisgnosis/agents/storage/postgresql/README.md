# PostgreSQL integration verification

[PostgresVersionedJsonStoreSpec](PostgresVersionedJsonStoreSpec.scala) has three grouped real-database contracts: exact JSON/namespace identity and valid/malformed payload Unicode across independent connections and adapters; create/stale-revision races with one winner; and masked admitted-write cancellation, joined connection cleanup, exhaustion and corrupt stored data. These tests exercise PostgreSQL rather than mocked JDBC.

Set WEISGNOSIS_POSTGRES_URL, WEISGNOSIS_POSTGRES_USER and WEISGNOSIS_POSTGRES_PASSWORD for a disposable fixture database, then run storagePostgresql / test. Without the URL the suite is skipped; skipping is not backend verification. The fixture explicitly initializes the fixed public table and deletes only its generated test namespaces. Use an isolated database and host-selected credentials. Tests use live-clock bounded coordination and driver connection/socket/query limits.

CI provisions PostgreSQL 16 and enables the suite without any LLM/provider request. The [backend contract](../../../../../../../main/scala/com/weisgnosis/agents/storage/postgresql/README.md#verification-and-acceptance) owns current reviewed identities, verification and limitations. Remote CI execution must still be confirmed independently.

- [x] postgresql-unicode-green.log passes all three real PostgreSQL 16 groups in 2.179 seconds against the unchanged Unicode RED spec.
- [x] Both reviewers renewed final source/spec approval; postgresql-final-verify.log passes all 1,486 tests, including this enabled real-database suite.
- [x] Fresh consumer verification passes through postgresql-final-artifact-consumer.log.
- [x] Both independent reviewers approved final documentation through the linked contract; English prose and local links pass inspection.

## Remote memory integration

[PostgresFixture](PostgresFixture.scala) shares scoped JDBC fixture helpers between the existing backend suite and [VersionedMemoryRepositorySpec](VersionedMemoryRepositorySpec.scala). The existing backend test bodies are unchanged; live random namespace generation prevents independent suites from sharing deterministic test namespaces. Three new grouped contracts exercise the actual PostgreSQL store through independently constructed repositories and validate malformed aggregate rejection, valid near-misses, genuine CAS conflict retries and lost acknowledgements. The wrappers delegate actual storage operations; they do not substitute in-memory persistence for remote evidence.

- [x] review_fp and review_invariants approved all six source/fixture identities; versioned-memory-verify.log passes 1,489 tests including six actual PostgreSQL groups. Evidence belongs to the [remote-memory manifest](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md#versioned-remote-memory).

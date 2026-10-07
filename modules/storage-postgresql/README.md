# Optional PostgreSQL storage

This fourth library artifact provides an explicit PostgreSQL backend for the harness VersionedJsonStore prerequisite. It depends on harness; core/harness/MCP do not acquire a PostgreSQL dependency. The host supplies javax.sql.DataSource and owns its pool, JDBC driver and connection/login/socket deadlines. PostgreSQL JDBC 42.7.11 is Test-only here.

See the [backend contract](src/main/scala/com/weisgnosis/agents/storage/postgresql/README.md) for exact namespace/revision semantics, schema ownership and cancellation limits, and the [real database fixture](src/test/scala/com/weisgnosis/agents/storage/postgresql/README.md) for opt-in verification. The separately composed [VersionedMemoryRepository](../harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md#versioned-remote-memory) has final source approval, full verification, fresh packaged-consumer verification and final documentation approval; this backend alone does not install domain persistence or grant exclusive workflow claim authority. Remote transcript segmentation/discovery/pruning/layout migration and checkpoint adapters remain unfinished.

- [x] review_fp and review_invariants renewed approval of all eleven final source/spec/build identities; the canonical manifest records the exact scope.
- [x] Real PostgreSQL Unicode regression passes all three unchanged groups; malformed values never publish and valid astral content survives.
- [x] postgresql-final-verify.log passes all 1,486 tests with real PostgreSQL enabled; eight-archive/four-POM preparation passes.
- [x] postgresql-final-artifact-consumer.log passes fresh four-JAR formatting, clean compilation and forked execution with no JDBC fixture dependency.
- [x] review_fp and review_invariants approved final documentation at the linked eleven source identities; English prose and local links pass inspection.

The additive [versioned transcript adapter](../harness/src/main/scala/com/weisgnosis/agents/harness/transcript/README.md#versioned-remote-transcript) has final independent source approval, full verification and fresh artifact consumption; final documentation approval passed. It binds append/read to one host-sized aggregate, preserving candidate identities/time across definite CAS retries and validating the complete parent chain. Remote checkpoint persistence, transcript segmentation/discovery/pruning and authenticated execution claims remain unfinished.

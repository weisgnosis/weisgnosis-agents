# Packaged public API consumer

This standalone, unpublished SBT fixture resolves core, harness, MCP and optional storage-postgresql through generated Maven POMs and packaged JARs, without a source-project dependency. Its neutral consumer package deliberately sits outside com.weisgnosis.agents and its package-private visibility boundary. It is external host verification infrastructure, not a published library component.

From the repository root, first run sbt scalafmtAll scalafmtSbt verify packageArtifacts, then python verification/artifact-consumer/prepare.py. Change into this directory and run sbt scalafmtCheckAll scalafmtSbtCheck compile run. On Windows, use the configured C:/tools/sbt/bin/sbt.bat runner. Initial formatting changes can be applied here with sbt scalafmtAll scalafmtSbt; the root formatter does not include this standalone build.

prepare.py validates the eight archive licenses, compiled classes/TASTy, exact current production source content, module POM coordinates, core dependency versions and test-dependency scopes. It copies all twelve artifacts into target/artifact-verification/repository and writes expected binary SHA-256 hashes to a manifest. Preparation removes the previous manifest before inspection, so a failed preparation cannot authorize stale verification.

The forked consumer hashes the four module JARs actually loaded by its classloader and compares them with the fresh manifest. A stale SNAPSHOT resolution fails instead of silently testing an old artifact. Runtime classpath inspection permits only the consumer's own classes directory and dependency JARs; known test/Java SDK/Jackson/Tomcat fixture dependencies fail admission. No model provider or database is contacted by this consumer; real PostgreSQL behavior is verified separately by the backend suite.

The executable checks public custom LlmClient and ToolAuthorizer implementations, builder-based tool execution, streamed final-answer filtering, typed provider steering, checked stop-sequence construction/configuration round-trip and actual stop-array/reasoning-effort serialization and per-tool strictness with unchanged parameters, the public completion-envelope enum codec, the additive ordinary JSON encoder with stream=false and no stream_options, exact numeric metadata codecs, permission precedence, MCP result projection and checked StorageAddress identity/codec, positive StorageRevision and resolution/hash verification of the backend companion. This is representative current-version source/packaging evidence, not exhaustive API coverage, backward binary compatibility, provider interoperability, or publication evidence.

The fixture pins the current development coordinates 0.1.0-SNAPSHOT and Scala 3.9.0. Change its build, preparation version, and executable expectation together when release coordinates change. No compatibility baseline is inferred from mutable SNAPSHOT artifacts.

- [x] review_fp approved FP/ZIO/build correctness and review_invariants approved the current four-artifact consumer/build/CI identities, with no unresolved findings. Changed identities link the eleven-file backend manifest below; unchanged standalone build settings retain their recorded scope.
- [x] postgresql-final-artifact-preparation.log and postgresql-final-artifact-consumer.log pass fresh four-module preparation, standalone formatting, clean compilation and forked execution against exact loaded JAR hashes, excluding the JDBC fixture driver. postgresql-final-verify.log passes all 1,486 repository tests with real PostgreSQL enabled.
- [ ] Confirm the remote workflow separately from local verification.

| Reviewed file | Git blob |
| --- | --- |
| [ArtifactSmoke.scala](ArtifactSmoke.scala) | [Current storage identity](../../modules/storage-postgresql/src/main/scala/com/weisgnosis/agents/storage/postgresql/README.md#verification-and-acceptance) |
| [prepare.py](prepare.py) | [Current storage identity](../../modules/storage-postgresql/src/main/scala/com/weisgnosis/agents/storage/postgresql/README.md#verification-and-acceptance) |
| [build.sbt](build.sbt) | [Current storage identity](../../modules/storage-postgresql/src/main/scala/com/weisgnosis/agents/storage/postgresql/README.md#verification-and-acceptance) |
| [build.properties](project/build.properties) | `dabdb159035d2210e30b7d74ef7a9ee7973a4add` |
| [plugins.sbt](project/plugins.sbt) | `7d517ef38d639b60dd174be213be9abc0667aa7f` |
| [ci.yml](../../.github/workflows/ci.yml) | [Current storage identity](../../modules/storage-postgresql/src/main/scala/com/weisgnosis/agents/storage/postgresql/README.md#verification-and-acceptance) |

review_fp and review_invariants approved unchanged four-artifact build/preparation and storage value/companion-resolution identities. The final backend payload-Unicode correction has renewed source approval and successful final artifact execution; final documentation closure remains pending under the [backend acceptance](../../modules/storage-postgresql/src/main/scala/com/weisgnosis/agents/storage/postgresql/README.md#verification-and-acceptance). PostgreSQL JDBC is a prohibited consumer fixture dependency; hosts choose their production driver separately.

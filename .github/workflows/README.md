# Continuous integration

[ci.yml](ci.yml) checks out the repository, provisions JDK 21 and sbt, and runs sbt verify packageArtifacts on pushes and pull requests. Verification checks formatting, fatal-warning compilation, and tests; packaging generates the four module binary/source JARs and POMs.

A PostgreSQL 16 service with fixture-only credentials enables the optional storagePostgresql integration suite through WEISGNOSIS_POSTGRES_URL/USER/PASSWORD. It exercises a real database, with no provider calls; absence of the local URL skips that suite rather than proving it.

Python 3.12 then runs the [tracked artifact inspection](../../verification/artifact-consumer/README.md), checking archive/POM boundaries and staging the freshly generated artifacts. A standalone external consumer separately checks its own formatting, compiles against generated Maven coordinates, and executes in a forked JVM. It verifies actual resolved module hashes, excludes source-project classes and fixture dependencies, and exercises representative public contracts without contacting a provider.

The workflow does not publish artifacts. Remote execution must be confirmed independently from local verification. Semantic FP correctness, final independent review approval, and source/binary/wire migration assessment remain separate acceptance gates. Current workflow identity and verification status belong to the [build contract](../../project/README.md#local-artifact-packaging).

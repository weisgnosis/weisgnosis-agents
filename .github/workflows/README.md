# Continuous integration

[ci.yml](ci.yml) checks out the repository, provisions JDK 21 and sbt, and runs sbt verify packageArtifacts on pushes and pull requests. Verification checks formatting, fatal-warning compilation, and tests; packaging generates the four module binary/source JARs and POMs.

A PostgreSQL 16 service with fixture-only credentials enables the optional storagePostgresql integration suite through WEISGNOSIS_POSTGRES_URL/USER/PASSWORD. It exercises a real database, with no provider calls; absence of the local URL skips that suite rather than proving it.

Python 3.12 then runs the [tracked artifact inspection](../../verification/artifact-consumer/README.md), checking archive/POM boundaries and staging the freshly generated artifacts. A standalone external consumer separately checks its own formatting, compiles against generated Maven coordinates, and executes in a forked JVM. It verifies actual resolved module hashes, excludes source-project classes and fixture dependencies, and exercises representative public contracts without contacting a provider.

The push/PR workflow also exercises reversible archive-admission regressions through check_preparation.py.

The manual [candidate.yml](candidate.yml) workflow accepts a checked canonical RC version, verifies/packages with a real PostgreSQL 16 fixture and runs exact source admission plus an isolated consumer against the selected version and actual loaded JAR hashes. Only successful preceding steps permit unsigned versioned artifact upload with source commit metadata. It neither tags nor signs/publishes artifacts; remote execution and release-baseline acceptance remain unchecked. Current source/config identities and local evidence are in the [candidate preparation manifest](../../verification/artifact-consumer/README.md#candidate-preparation-acceptance).

The [candidate preparation manifest](../../verification/artifact-consumer/README.md#candidate-preparation-acceptance) owns unchanged workflow/configuration acceptance. Latest production-source RC1 verification, reversible archive admission and the standalone actual-version/hash consumer passed under the [startup-default verification scope](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#shell-startup-defaults); the subsequent [empty-text stream scope](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#empty-text-stream-completion) changes tests/documentation only and passes full verification. No Git remote is configured, so these results do not establish a workflow run. Explicit live-provider smoke settings are unset and no provider call was made.

The workflows do not publish artifacts. Remote execution must be confirmed independently from local verification. Semantic FP correctness, final independent review approval, and source/binary/wire migration assessment remain separate acceptance gates. Current workflow identity and verification status belong to the [build contract](../../project/README.md#local-artifact-packaging).

- [x] review_fp and review_invariants approved the three-document RC-readiness clarification against 2a92a0b with no findings; English/diff and 78-document/3,020-local-reference checks pass. Workflow/source/test identities are unchanged.

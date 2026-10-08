# Packaged public API consumer

This standalone, unpublished SBT fixture resolves core, harness, MCP and optional storage-postgresql through generated Maven POMs and packaged JARs, without a source-project dependency. Its neutral consumer package deliberately sits outside com.weisgnosis.agents and its package-private visibility boundary. It is external host verification infrastructure, not a published library component.

The default coordinates remain 0.1.0-SNAPSHOT. For an unsigned local candidate, select a canonical ASCII major.minor.patch-RCpositive version without leading zeros, at most 64 characters, for example 0.1.0-RC1. The root build checks -Dweisgnosis.candidate.version; the standalone build checks -Dweisgnosis.artifact.version. Neither selection creates a tag, frozen ABI or published release.

From the repository root, run sbt verify packageArtifacts for development artifacts, or sbt "-Dweisgnosis.candidate.version=0.1.0-RC1" verify packageArtifacts for a candidate. Then run python verification/artifact-consumer/check_preparation.py --version 0.1.0-RC1 (omit --version for SNAPSHOT). In this fixture directory, run sbt "-Dweisgnosis.artifact.version=0.1.0-RC1" scalafmtCheckAll scalafmtSbtCheck clean compile run. On Windows use C:/tools/sbt/bin/sbt.bat. Root formatting does not format this standalone build.

prepare.py --version selects the same bounded version; --check-only validates the selector without filesystem mutation or artifact inspection. Ordinary preparation invalidates the old authorization manifest before selector/artifact failures. Inspection requires exact current Scala source entry names/counts and normalized source bytes, rejecting obsolete or duplicate Scala entries; it also checks all eight archive licenses, compiled class/TASTy presence, four POM coordinates/parent versions and fixture dependency scopes. Twelve artifacts are staged under target/artifact-verification/repository with selected version and expected binary SHA-256 hashes in the manifest.

check_preparation.py uses reversible target-only source-JAR fixtures: a rejected selector must invalidate authorization, and extra/duplicate Scala entries must fail. Original archive bytes are restored in finally and the valid preparation is revalidated. It does not modify repository source files. RED/GREEN evidence below records reproduced admission defects, not a published compatibility guarantee.

The forked consumer compares the selected version and hashes of four actually loaded module JARs against the fresh manifest. Runtime classpath inspection excludes source-project classes and known test/Java SDK/Tomcat/JDBC fixture dependencies. Jackson is now a legitimate production transitive dependency of the private schema evaluator; the consumer executes valid/invalid schema-admission neighbors to verify that packaged path. It contacts no model provider or database; real PostgreSQL verification belongs to the configured backend suite.

The consumer exercises public external-tool suspension/resume, schema rejection/acceptance with exactly one handler invocation, representative request/settings and exact JSON contracts, MCP deadlines, permission precedence, explicit unavailable-channel denial and storage address/revision projection. It compares all four loaded module hashes. This is current-version source/packaging evidence, not exhaustive API coverage, binary compatibility, provider interoperability or publication.

## Candidate preparation acceptance

Candidate preparation checks package contents before authorizing the standalone run. Reversible negative archive fixtures verify rejected selectors and unexpected/duplicate Scala source entries, restore original bytes and revalidate the valid artifacts. The consumer then compiles from a clean standalone build and compares all four loaded module hashes. These checks establish selected current source/packaging behavior, not a frozen binary API or a published release.

The manual [candidate workflow](../../.github/workflows/candidate.yml) validates its version, provisions PostgreSQL 16, verifies/packages four modules, runs archive admission and the standalone consumer, and uploads unsigned artifacts/source metadata only after preceding steps succeed. The [workflow contract](../../.github/workflows/README.md) owns its separately reviewed identities and operational boundaries.

- [ ] Confirm actual remote workflow execution, signing/publication and the release compatibility baseline.

No Git remote is configured; no remote workflow, tag, signing, publication or frozen ABI is claimed. The separately authorized [live smoke evidence](../../modules/examples/README.md#live-endpoint-verification) is bounded to the recorded endpoint run and is not executed by this consumer.

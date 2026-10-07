# Packaged public API consumer

This standalone, unpublished SBT fixture resolves core, harness, MCP and optional storage-postgresql through generated Maven POMs and packaged JARs, without a source-project dependency. Its neutral consumer package deliberately sits outside com.weisgnosis.agents and its package-private visibility boundary. It is external host verification infrastructure, not a published library component.

The default coordinates remain 0.1.0-SNAPSHOT. For an unsigned local candidate, select a canonical ASCII major.minor.patch-RCpositive version without leading zeros, at most 64 characters, for example 0.1.0-RC1. The root build checks -Dweisgnosis.candidate.version; the standalone build checks -Dweisgnosis.artifact.version. Neither selection creates a tag, frozen ABI or published release.

From the repository root, run sbt verify packageArtifacts for development artifacts, or sbt "-Dweisgnosis.candidate.version=0.1.0-RC1" verify packageArtifacts for a candidate. Then run python verification/artifact-consumer/check_preparation.py --version 0.1.0-RC1 (omit --version for SNAPSHOT). In this fixture directory, run sbt "-Dweisgnosis.artifact.version=0.1.0-RC1" scalafmtCheckAll scalafmtSbtCheck clean compile run. On Windows use C:/tools/sbt/bin/sbt.bat. Root formatting does not format this standalone build.

prepare.py --version selects the same bounded version; --check-only validates the selector without filesystem mutation or artifact inspection. Ordinary preparation invalidates the old authorization manifest before selector/artifact failures. Inspection requires exact current Scala source entry names/counts and normalized source bytes, rejecting obsolete or duplicate Scala entries; it also checks all eight archive licenses, compiled class/TASTy presence, four POM coordinates/parent versions and fixture dependency scopes. Twelve artifacts are staged under target/artifact-verification/repository with selected version and expected binary SHA-256 hashes in the manifest.

check_preparation.py uses reversible target-only source-JAR fixtures: a rejected selector must invalidate authorization, and extra/duplicate Scala entries must fail. Original archive bytes are restored in finally and the valid preparation is revalidated. It does not modify repository source files. RED/GREEN evidence below records reproduced admission defects, not a published compatibility guarantee.

The forked consumer compares the selected version and hashes of four actually loaded module JARs against the fresh manifest. Runtime classpath inspection excludes source-project classes and known test/Java SDK/Jackson/Tomcat/JDBC fixture dependencies. It contacts no model provider or database; real PostgreSQL verification belongs to the configured backend suite.

The consumer also exercises checked thinking-budget construction/codec and selected request encoding under the [renewed nine-file scope](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#optional-thinking-budget), with fresh verification passed in thinking-budget-consumer.log. Its changed source relies on this renewed evidence rather than earlier consumer execution.

The checked-usage consumer migration now handles combine/totalTokens/validated Either values, asserts a total of ten and rejects counter overflow. Its changed identity has renewed source approval in the [usage manifest](../../modules/core/src/main/scala/com/weisgnosis/agents/core/model/README.md#checked-usage-arithmetic); fresh packaged execution passes in source-sample-fixes-consumer.log; earlier candidate execution alone does not verify this changed source.

The executable checks public custom LlmClient and ToolAuthorizer implementations, builder-based tool execution, streamed final-answer filtering, typed provider steering, checked stop-sequence construction/configuration round-trip and actual stop-array/reasoning-effort serialization and per-tool strictness with unchanged parameters, the public completion-envelope enum codec, the additive ordinary JSON encoder with stream=false and no stream_options, exact numeric metadata codecs, permission precedence, MCP result projection and checked StorageAddress identity/codec, positive StorageRevision and resolution/hash verification of the backend companion. This is representative current-version source/packaging evidence, not exhaustive API coverage, backward binary compatibility, provider interoperability, or publication evidence.

## Candidate preparation acceptance

The manual [candidate workflow](../../.github/workflows/candidate.yml) validates its version before packaging, provisions PostgreSQL 16, verifies/packages all four modules, runs archive admission regressions and the standalone selected-version/hash consumer, then uploads unsigned versioned artifacts and source commit metadata only after preceding steps succeed. It does not tag, sign, publish, freeze the ABI or establish remote execution; the [workflow contract](../../.github/workflows/README.md) preserves those boundaries.

| Source/config approved by review_fp and review_invariants against 529aa4c | Git blob |
| --- | --- |
| [Root build.sbt](../../build.sbt) | [Current test-sharing build identity](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md#remote-middleware-throttle-integration); candidate version behavior unchanged. |
| [ci.yml](../../.github/workflows/ci.yml) | d865baa4dc36d52dc2c84c8e4c2c5ebab11d7b2e |
| [candidate.yml](../../.github/workflows/candidate.yml) | 63fa41a745e21ce1f894f0009af6753b4cc20bd3 |
| [ArtifactSmoke.scala](ArtifactSmoke.scala) | [Current thinking-budget identity](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#optional-thinking-budget); prior behavioral scope retained. |
| [build.sbt](build.sbt) | 166a1d10c8ba5b95088bbc070fb4f0fd3cd93b9f |
| [prepare.py](prepare.py) | 93e90864cda955a230900c08213806315f6057d5 |
| [check_preparation.py](check_preparation.py) | a42c275772974a31a7a5809adc4dd1a5e2cc923a |

- [x] review_fp and review_invariants approved all seven final identities against 529aa4c, with no unresolved findings.
- [x] source-archive-admission-red.log records extra/duplicate Scala entries incorrectly admitted (exit zero, stale manifest retained); source-archive-admission-green.log rejects both and restores/revalidates original bytes. candidate-selector-red.log records a rejected selector retaining old authority; candidate-selector-green.log rejects it with manifest invalidation and passes restored archive checks. Final selected-RC verification remains a separate gate.
- [x] candidate-rc-verify.log exited zero for 0.1.0-RC1: 1,507 tests (669 core / 726 harness / 86 MCP / 21 PostgreSQL / 5 examples), zero compiler warnings/failures and four packages. A build-reload notice from formatting build.sbt is an sbt diagnostic, not a compiler warning.
- [x] candidate-rc-preparation.log exited zero for actual RC artifacts, selector/source-entry rejection and restored exact source/POM/LICENSE staging.
- [x] candidate-rc-consumer.log passed actual 0.1.0-RC1 standalone formatting, clean compilation and forked execution; candidate-default-consumer.log passed default SNAPSHOT consumption. candidate-version-mismatch.log rejects a manifest declaring RC2 to an RC1 consumer with Unexpected artifact verification version; original manifest bytes were restored in finally, and candidate-rc-restored-consumer.log passed the valid RC1 consumer again.
- [x] Canonical valid/invalid --check-only selector cases preserve manifest bytes; both workflow YAML files parse. candidate-build-invalid-version.log independently rejects explicit RC0 in the root build before packaging. These local checks do not execute remote workflows.
- [x] review_fp and review_invariants approved final combined documentation against 529aa4c and the seven unchanged identities. Manual English inspection, changed-script/diff checks and 78-document/2,813-local-reference checks pass with zero issues.
- [ ] Confirm remote workflow execution, live-provider interoperability and signing/publication/release-baseline requirements separately.

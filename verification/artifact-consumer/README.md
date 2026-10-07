# Packaged public API consumer

This standalone, unpublished SBT fixture resolves core, harness, MCP and optional storage-postgresql through generated Maven POMs and packaged JARs, without a source-project dependency. Its neutral consumer package deliberately sits outside com.weisgnosis.agents and its package-private visibility boundary. It is external host verification infrastructure, not a published library component.

The default coordinates remain 0.1.0-SNAPSHOT. For an unsigned local candidate, select a canonical ASCII major.minor.patch-RCpositive version without leading zeros, at most 64 characters, for example 0.1.0-RC1. The root build checks -Dweisgnosis.candidate.version; the standalone build checks -Dweisgnosis.artifact.version. Neither selection creates a tag, frozen ABI or published release.

From the repository root, run sbt verify packageArtifacts for development artifacts, or sbt "-Dweisgnosis.candidate.version=0.1.0-RC1" verify packageArtifacts for a candidate. Then run python verification/artifact-consumer/check_preparation.py --version 0.1.0-RC1 (omit --version for SNAPSHOT). In this fixture directory, run sbt "-Dweisgnosis.artifact.version=0.1.0-RC1" scalafmtCheckAll scalafmtSbtCheck clean compile run. On Windows use C:/tools/sbt/bin/sbt.bat. Root formatting does not format this standalone build.

prepare.py --version selects the same bounded version; --check-only validates the selector without filesystem mutation or artifact inspection. Ordinary preparation invalidates the old authorization manifest before selector/artifact failures. Inspection requires exact current Scala source entry names/counts and normalized source bytes, rejecting obsolete or duplicate Scala entries; it also checks all eight archive licenses, compiled class/TASTy presence, four POM coordinates/parent versions and fixture dependency scopes. Twelve artifacts are staged under target/artifact-verification/repository with selected version and expected binary SHA-256 hashes in the manifest.

check_preparation.py uses reversible target-only source-JAR fixtures: a rejected selector must invalidate authorization, and extra/duplicate Scala entries must fail. Original archive bytes are restored in finally and the valid preparation is revalidated. It does not modify repository source files. RED/GREEN evidence below records reproduced admission defects, not a published compatibility guarantee.

The forked consumer compares the selected version and hashes of four actually loaded module JARs against the fresh manifest. Runtime classpath inspection excludes source-project classes and known test/Java SDK/Jackson/Tomcat/JDBC fixture dependencies. It contacts no model provider or database; real PostgreSQL verification belongs to the configured backend suite.

The unchanged consumer has renewed RC1 execution evidence for the [interactive preflight runtime](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#interactive-batch-preflight), including its public custom authorizer default during actual tool dispatch. Final combined documentation approval passed; consumer source identity is unchanged.

The current consumer additionally checks child-environment named-parameter source consumption and typed pre-launch rejection without starting a process; current identities/reviews and renewed RC1 evidence are in the [stdio environment contract](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/transport/README.md#child-environment-overrides). Both reviewers approved the final thirteen-file combined source/documentation scope against 0661e31.

The consumer additionally checks the explicit [tool-image selector](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#tool-image-result-projection); both independent source reviews and renewed RC1 verification/preparation/consumer execution pass; final combined documentation approval passed. Prior RC execution alone does not verify this change.

The consumer also exercises checked thinking-budget construction/codec and selected request encoding under the [renewed nine-file scope](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#optional-thinking-budget), with current RC1 verification passed in interactive-batch-consumer.log. Its changed source relies on this renewed evidence rather than earlier consumer execution.

The checked-usage consumer migration now handles combine/totalTokens/validated Either values, asserts a total of ten and rejects counter overflow. Its changed identity has renewed source approval in the [usage manifest](../../modules/core/src/main/scala/com/weisgnosis/agents/core/model/README.md#checked-usage-arithmetic); fresh current RC1 packaged execution passes in interactive-batch-consumer.log; earlier candidate execution alone does not verify this changed source.

The executable checks public custom LlmClient and ToolAuthorizer implementations, builder-based tool execution, streamed final-answer filtering, typed provider steering, checked stop-sequence construction/configuration round-trip and actual stop-array/reasoning-effort serialization and per-tool strictness with unchanged parameters, the public completion-envelope enum codec, the additive ordinary JSON encoder with stream=false and no stream_options, exact numeric metadata codecs, permission precedence, MCP result projection and checked StorageAddress identity/codec, positive StorageRevision and resolution/hash verification of the backend companion. This is representative current-version source/packaging evidence, not exhaustive API coverage, backward binary compatibility, provider interoperability, or publication evidence.

## Candidate preparation acceptance

The manual [candidate workflow](../../.github/workflows/candidate.yml) validates its version before packaging, provisions PostgreSQL 16, verifies/packages all four modules, runs archive admission regressions and the standalone selected-version/hash consumer, then uploads unsigned versioned artifacts and source commit metadata only after preceding steps succeed. It does not tag, sign, publish, freeze the ABI or establish remote execution; the [workflow contract](../../.github/workflows/README.md) preserves those boundaries.

| Source/config approved by review_fp and review_invariants against 529aa4c | Git blob |
| --- | --- |
| [Root build.sbt](../../build.sbt) | [Current test-sharing build identity](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md#remote-middleware-throttle-integration); candidate version behavior unchanged. |
| [ci.yml](../../.github/workflows/ci.yml) | d865baa4dc36d52dc2c84c8e4c2c5ebab11d7b2e |
| [candidate.yml](../../.github/workflows/candidate.yml) | 63fa41a745e21ce1f894f0009af6753b4cc20bd3 |
| [ArtifactSmoke.scala](ArtifactSmoke.scala) | [Current environment consumer identity](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/transport/README.md#child-environment-overrides); prior behavioral scope retained. |
| [build.sbt](build.sbt) | 166a1d10c8ba5b95088bbc070fb4f0fd3cd93b9f |
| [prepare.py](prepare.py) | 93e90864cda955a230900c08213806315f6057d5 |
| [check_preparation.py](check_preparation.py) | a42c275772974a31a7a5809adc4dd1a5e2cc923a |

- [x] review_fp and review_invariants approved all seven final identities against 529aa4c, with no unresolved findings.
- [x] source-archive-admission-red.log records extra/duplicate Scala entries incorrectly admitted (exit zero, stale manifest retained); source-archive-admission-green.log rejects both and restores/revalidates original bytes. candidate-selector-red.log records a rejected selector retaining old authority; candidate-selector-green.log rejects it with manifest invalidation and passes restored archive checks. Final selected-RC verification remains a separate gate.
- [x] interactive-batch-final-verify.log exited zero against source base c507dad and the final approved interactive-preflight source identities for 0.1.0-RC1: 1,520 tests (674 core / 730 harness / 86 MCP / 22 PostgreSQL / 8 examples), zero compiler warnings/failures and four packages.
- [x] interactive-batch-preparation.log passed actual RC1 source/POM/LICENSE staging, rejected invalid selector/extra/duplicate Scala entries, and restored/revalidated the original archives.
- [x] interactive-batch-consumer.log passed standalone formatting, clean compilation and forked selected-public-API execution against four fresh actual RC1 module hashes. The selected-version/hash checks establish current source consumption, not backward binary compatibility.
- [x] Canonical valid/invalid --check-only selector cases preserve manifest bytes; both workflow YAML files parse. candidate-build-invalid-version.log independently rejects explicit RC0 in the root build before packaging. These local checks do not execute remote workflows.
- [x] review_fp and review_invariants approved final combined documentation against 529aa4c and the seven unchanged identities. Manual English inspection, changed-script/diff checks and 78-document/2,813-local-reference checks pass with zero issues.
- [x] review_fp and review_invariants approved the final five-file current-source RC validation and bounded [remaining-work classification](../../docs/architecture/test-parity.md) against 609e75c. review_fp document manifest SHA-256: 00cd36458fbd526bafd7b9410f286cef663e47957ff8a89417c1204e00830356. Manual English inspection, diff checks and 78-document/2,853-local-reference validation pass with zero issues. Acceptance covers the recorded current RC1 verification/preparation/consumer evidence and classification, without closing unfinished source/parity or external release gates.
- [ ] Confirm remote workflow execution, live-provider interoperability and signing/publication/release-baseline requirements separately.

No Git remote is configured and all four explicit WEISGNOSIS_LIVE_SMOKE/ENDPOINT/MODEL/API_KEY variables are unset. No remote workflow or live-provider smoke was executed; no tag, signing, publication or frozen ABI is claimed.

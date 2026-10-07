# Packaged public API consumer

This standalone, unpublished SBT fixture resolves core, harness, and MCP through generated Maven POMs and packaged JARs, without a source-project dependency. Its neutral consumer package deliberately sits outside com.weisgnosis.agents and its package-private visibility boundary. It is external host verification infrastructure, not a published library component.

From the repository root, first run sbt scalafmtAll scalafmtSbt verify packageArtifacts, then python verification/artifact-consumer/prepare.py. Change into this directory and run sbt scalafmtCheckAll scalafmtSbtCheck compile run. On Windows, use the configured C:/tools/sbt/bin/sbt.bat runner. Initial formatting changes can be applied here with sbt scalafmtAll scalafmtSbt; the root formatter does not include this standalone build.

prepare.py validates the six archive licenses, compiled classes/TASTy, exact current production source content, module POM coordinates, core dependency versions and test-dependency scopes. It copies all nine artifacts into target/artifact-verification/repository and writes expected binary SHA-256 hashes to a manifest. Preparation removes the previous manifest before inspection, so a failed preparation cannot authorize stale verification.

The forked consumer hashes the three module JARs actually loaded by its classloader and compares them with the fresh manifest. A stale SNAPSHOT resolution fails instead of silently testing an old artifact. Runtime classpath inspection permits only the consumer's own classes directory and dependency JARs; known test/Java SDK/Jackson/Tomcat fixture dependencies fail admission. No model provider is contacted.

The executable checks public custom LlmClient and ToolAuthorizer implementations, builder-based tool execution, streamed final-answer filtering, typed provider steering, checked stop-sequence construction/configuration round-trip and actual stop-array/reasoning-effort serialization, exact numeric metadata codecs, permission precedence, and MCP result projection. This is representative current-version source/packaging evidence, not exhaustive API coverage, backward binary compatibility, provider interoperability, or publication evidence.

The fixture pins the current development coordinates 0.1.0-SNAPSHOT and Scala 3.9.0. Change its build, preparation version, and executable expectation together when release coordinates change. No compatibility baseline is inferred from mutable SNAPSHOT artifacts.

- [x] review_fp approved FP/ZIO/build correctness and review_invariants approved invariants for the six identities below, with no unresolved findings. Final documentation closure uses the same bounded scope.
- [x] reasoning-effort-artifact-consumer.log passes standalone formatting, clean fatal-warning compilation and forked execution; reasoning-effort-artifact-preparation.log passes all three archive/POM inspections. reasoning-effort-verify.log passes full repository formatting, fatal-warning compilation, 1,474 tests and packaging with zero compiler warnings or failed tests. artifact-consumer-mismatch.log rejects an intentionally incorrect core hash with exit 1; the original manifest was restored.
- [ ] Confirm the remote workflow separately from local verification.

| Reviewed file | Git blob |
| --- | --- |
| [ArtifactSmoke.scala](ArtifactSmoke.scala) | `94d3776ea518cd14ba1bb7d58e201a0a66d20e33` |
| [prepare.py](prepare.py) | `fb380de03b8f7cf78eec59d330e253b1a8874869` |
| [build.sbt](build.sbt) | `aab1480c04e6fb5f8be0f634f4c52220b456e1d8` |
| [build.properties](project/build.properties) | `dabdb159035d2210e30b7d74ef7a9ee7973a4add` |
| [plugins.sbt](project/plugins.sbt) | `7d517ef38d639b60dd174be213be9abc0667aa7f` |
| [ci.yml](../../.github/workflows/ci.yml) | `faf28bbb41c685c698c04e65e5e91b9345b1f104` |

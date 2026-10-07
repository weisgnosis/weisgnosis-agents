# Build definition support

[build.properties](build.properties) pins sbt; [plugins.sbt](plugins.sbt) declares build plugins, including scalafmt. Module dependencies and compiler options live in [build.sbt](../build.sbt). Run `sbt scalafmtAll scalafmtSbt` and `sbt verify packageArtifacts` after build or packaging changes.

The harness alone depends on SnakeYAML Engine 3.1.1 for bounded declaration event parsing; core and MCP do not gain a YAML dependency. Public parse events are read inside ZIO, without object loading. The [parser acceptance](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#inline-declaration-parsing) retains its parser/dependency approval; current build identity is recorded below.

MCP tests run in a forked JVM so the standard java.class.path contains their actual runtime dependencies. The stdio integration suite uses that classpath and the same JDK to start its test-only peer; production code has no test-launcher dependency. The [transport acceptance](../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/transport/README.md#verification-and-acceptance) records review of this build setting.

MCP interoperability tests pin Java SDK `mcp-core`/`mcp-json-jackson2` 0.17.2, embedded Tomcat 11.0.18 and `slf4j-jdk14` 2.0.16 in Test scope only. The forked test JVM opens `java.base/java.lang` and `java.rmi/sun.rmi.transport` for Tomcat leak checks; JDK logging keeps server diagnostics visible. Production dependencies are unchanged. The [interop acceptance](../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/transport/README.md#independent-server-interoperability) records the fixture and test-configuration scope; current build identity is recorded below.

## Local artifact packaging

`packageArtifacts` invokes nine standard sbt tasks: binary JAR, source JAR and Maven POM generation for each of core, harness and MCP. Outputs remain under each module's `target/scala-3.9.0`; the alias does not publish or install them. Both archive types include exactly one `META-INF/LICENSE` copied from the repository license. The root aggregate and the additional examples project are not published. Examples depends only on the three library Compile configurations and its own ZIO Test dependencies; it does not inherit MCP Java server fixtures. Root verify compiles and tests examples, while packageArtifacts remains limited to the three library distributions.

Coordinates are `com.weisgnosis:weisgnosis-agents-{core,harness,mcp}_3:0.1.0-SNAPSHOT`. Harness and MCP depend on the packaged core artifact; SnakeYAML Engine is a harness dependency only. Java SDK/Jackson, Tomcat, SLF4J and ZIO test dependencies retain Test scope and do not enter the consumer runtime. No repository remote is configured, and no SCM URL is invented in the POMs.

[CI](../.github/workflows/ci.yml) invokes `sbt verify packageArtifacts`. After packaging, it runs the [tracked artifact inspection and external consumer](../verification/artifact-consumer/README.md), including standalone formatting, representative public extension contracts, actual resolved module hashes and runtime classpath boundaries. No remote workflow execution or publication is established by local verification.

| Reviewed file | Git blob |
| --- | --- |
| [build.sbt](../build.sbt) | `0540b2b595f7d69e8f0836ddec9317d2c3492828` |
| [ci.yml](../.github/workflows/ci.yml) | `faf28bbb41c685c698c04e65e5e91b9345b1f104` |

- [x] `review_fp` approved FP/ZIO/build correctness and `review_invariants` approved the current build identity and unpublished example scope, with no findings. The packaged-consumer CI extension has separate [scoped acceptance](../verification/artifact-consumer/README.md). Full `scalafmtAll scalafmtSbt verify packageArtifacts` passed in `packaged-api-verify.log`, with zero compiler warnings or failures; current repository totals and environment are in the [tracker](../docs/architecture/tracker.md#current-acceptance). No existing library test groups were added by the build/consumer changes; examples contains five integration/admission/logging groups.
- [x] `artifact-license-red.log` reproduced missing LICENSE entries in all six binary/source archives against unchanged build `6c54a59788d5342f2ca7c4948bf0ad9352b6bd4c`. Separate `artifact-inspection-green.log` checks pass for exact license bytes, compiled classes/TASTy, all current core/harness/MCP production Scala sources, POM coordinates and dependency scopes.
- [x] `artifact-consumer-verify.log` verifies selected public APIs and custom host extension points from a neutral external package against the three fresh packaged artifacts. Actual loaded JAR hashes and classpath boundaries are checked; an intentionally incorrect hash fails with exit 1 in `artifact-consumer-mismatch.log`. This uses neither source-project dependencies nor publishLocal/remote publication.
- [ ] Confirm remote CI and release version/SCM/signing requirements, public API compatibility, documentation artifacts and actual publication before claiming release readiness.

## Compatibility policy

All three library distributions remain development SNAPSHOT artifacts. No previously published binary-compatibility baseline or blanket API freeze is established. Defaulted additions can preserve ordinary source construction while changing positional extractors, Product arity, constructor method values and JVM signatures; downstream code must follow package migration contracts and rebuild dependent binaries.

Current public contracts include the documented core models/runtime/extension ports, harness policies/storage adapters, and MCP protocol/client/transport APIs. Package-private implementation helpers remain outside that host surface. The examples project and verification fixture are unpublished integration aids. Naming a public API here records its current accessibility and contract, not a promise that every signature is frozen for an RC.

The [client migration](../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#optional-parallel-tool-call-steering) records the current thirteen-field LlmConfig shape. The [tool migration](../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#typed-execution-observations) distinguishes supported String construction from changed ToolExecutionResult payload/state JSON and generated extractors. The [Toolkit migration](../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#checked-registry-identity) removes unchecked Map/copy/Product reconstruction and preserves name-based checked registration. Persisted payload/checkpoint migration is distinct from recompiling host source; read the relevant package contract before upgrading.

- [x] Both independent reviewers approved the tracked external consumer and workflow extension at the [six recorded identities](../verification/artifact-consumer/README.md); standalone formatting, compilation and forked execution pass. This establishes selected current public API consumption, not exhaustive visibility or compatibility coverage.
- [ ] Confirm remote CI, agree release coordinates and an actual published compatibility baseline before release compatibility claims.

# Build definition support

[build.properties](build.properties) pins sbt; [plugins.sbt](plugins.sbt) declares build plugins, including scalafmt. Module dependencies and compiler options live in [build.sbt](../build.sbt). Run `sbt scalafmtAll scalafmtSbt` and `sbt verify packageArtifacts` after build or packaging changes.

The harness alone depends on SnakeYAML Engine 3.1.1 for bounded declaration event parsing; core and MCP do not gain a YAML dependency. Public parse events are read inside ZIO, without object loading. The [parser acceptance](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#inline-declaration-parsing) retains its parser/dependency approval; current build identity is recorded below.

MCP tests run in a forked JVM so the standard java.class.path contains their actual runtime dependencies. The stdio integration suite uses that classpath and the same JDK to start its test-only peer; production code has no test-launcher dependency. The [transport acceptance](../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/transport/README.md#verification-and-acceptance) records review of this build setting.

MCP interoperability tests pin Java SDK `mcp-core`/`mcp-json-jackson2` 0.17.2, embedded Tomcat 11.0.18 and `slf4j-jdk14` 2.0.16 in Test scope only. The forked test JVM opens `java.base/java.lang` and `java.rmi/sun.rmi.transport` for Tomcat leak checks; JDK logging keeps server diagnostics visible. Production dependencies are unchanged. The [interop acceptance](../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/transport/README.md#independent-server-interoperability) records the fixture and test-configuration scope; current build identity is recorded below.

## Local artifact packaging

`packageArtifacts` invokes nine standard sbt tasks: binary JAR, source JAR and Maven POM generation for each of core, harness and MCP. Outputs remain under each module's `target/scala-3.9.0`; the alias does not publish or install them. Both archive types include exactly one `META-INF/LICENSE` copied from the repository license. The root aggregate is not published.

Coordinates are `com.weisgnosis:weisgnosis-agents-{core,harness,mcp}_3:0.1.0-SNAPSHOT`. Harness and MCP depend on the packaged core artifact; SnakeYAML Engine is a harness dependency only. Java SDK/Jackson, Tomcat, SLF4J and ZIO test dependencies retain Test scope and do not enter the consumer runtime. No repository remote is configured, and no SCM URL is invented in the POMs.

[CI](../.github/workflows/ci.yml) invokes `sbt verify packageArtifacts`. This checks the repository and generates artifacts; it does not automatically run the separate archive/POM inspection or isolated consumer checks described below. No remote workflow execution or publication is established by local verification.

| Reviewed file | Git blob |
| --- | --- |
| [build.sbt](../build.sbt) | `4224b9121bcdd1426accac0f4c37a266199fe3c9` |
| [ci.yml](../.github/workflows/ci.yml) | `840a89cf8c55d13df2355a1450673f28691a1f15` |

- [x] `path_review` approved FP/ZIO/build correctness and `current_docs` approved invariants for these two identities, with no open findings. Full `scalafmtAll scalafmtSbt verify packageArtifacts` passed in `artifacts-verify.log`, with zero compiler warnings or failures; current repository totals and environment are in the [tracker](../docs/architecture/tracker.md#current-acceptance). No new runtime test groups were added.
- [x] `artifact-license-red.log` reproduced missing LICENSE entries in all six binary/source archives against unchanged build `6c54a59788d5342f2ca7c4948bf0ad9352b6bd4c`. Separate `artifact-inspection-green.log` checks pass for exact license bytes, compiled classes/TASTy, all 57/93/16 core/harness/MCP production Scala sources, POM coordinates and dependency scopes.
- [x] `artifact-consumer-final.log` verifies compilation and forked execution of public ReAct, PermissionEngine and McpCallResult APIs in an isolated consumer using a Maven file resolver over generated POMs/JARs. Its classpath contains the three packaged JARs, no original project class directories and no Test dependencies. This used neither source-project dependencies nor publishLocal/remote publication.
- [ ] Confirm remote CI and release version/SCM/signing requirements, public API compatibility, documentation artifacts and actual publication before claiming release readiness.

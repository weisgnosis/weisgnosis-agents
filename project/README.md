# Build definition support

[build.properties](build.properties) pins sbt; [plugins.sbt](plugins.sbt) declares build plugins, including scalafmt. Module dependencies and compiler options live in [build.sbt](../build.sbt). Run `sbt scalafmtAll scalafmtSbt` and `sbt verify` after build changes.

The harness alone depends on SnakeYAML Engine 3.1.1 for bounded declaration event parsing; core and MCP do not gain a YAML dependency. Public parse events are read inside ZIO, without object loading. The [parser acceptance](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#inline-declaration-parsing) records the exact reviewed build blob and verification.

MCP tests run in a forked JVM so the standard java.class.path contains their actual runtime dependencies. The stdio integration suite uses that classpath and the same JDK to start its test-only peer; production code has no test-launcher dependency. The [transport acceptance](../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/transport/README.md#verification-and-acceptance) records review of this build setting.

MCP interoperability tests pin Java SDK `mcp-core`/`mcp-json-jackson2` 0.17.2, embedded Tomcat 11.0.18 and `slf4j-jdk14` 2.0.16 in Test scope only. The forked test JVM opens `java.base/java.lang` and `java.rmi/sun.rmi.transport` for Tomcat leak checks; JDK logging keeps server diagnostics visible. Production dependencies are unchanged. The [interop acceptance](../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/transport/README.md#independent-server-interoperability) records both reviewers, exact fixture/build identities and verification.

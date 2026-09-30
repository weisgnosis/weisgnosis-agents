# Build definition support

[build.properties](build.properties) pins sbt; [plugins.sbt](plugins.sbt) declares build plugins, including scalafmt. Module dependencies and compiler options live in [build.sbt](../build.sbt). Run `sbt scalafmtAll scalafmtSbt` and `sbt verify` after build changes.

MCP tests run in a forked JVM so the standard java.class.path contains their actual runtime dependencies. The stdio integration suite uses that classpath and the same JDK to start its test-only peer; production code has no test-launcher dependency. The [transport acceptance](../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/transport/README.md#verification-and-acceptance) records review of this build setting.

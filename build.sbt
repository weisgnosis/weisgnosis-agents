val scala3Version                      = "3.9.0"
val zioVersion                         = "2.1.26"
val zioJsonVersion                     = "0.9.1"
val zioHttpVersion                     = "3.11.3"
val zioProcessVersion: String          = "0.7.2"
val jsonSchemaValidatorVersion: String = "2.0.4"
val snakeYamlEngineVersion: String     = "3.1.1"
val mcpJavaSdkVersion: String          = "0.17.2"
val tomcatVersion: String              = "11.0.18"
val slf4jVersion: String               = "2.0.16"
val zioOpenTelemetryVersion: String    = "3.1.20"
val openTelemetryVersion: String       = "1.66.0"

ThisBuild / scalaVersion := scala3Version
ThisBuild / version := {
  sys.props.get("weisgnosis.candidate.version").fold("0.1.0-SNAPSHOT") { candidate =>
    val pattern = "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)-RC[1-9][0-9]*"
    require(
      candidate.length <= 64 && candidate.matches(pattern),
      "Candidate version must be canonical ASCII major.minor.patch-RCpositive without leading zeros"
    )
    candidate
  }
}
ThisBuild / organization := "com.weisgnosis"
ThisBuild / homepage     := Some(url("https://weisgnosis.com"))
ThisBuild / licenses     := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / developers := List(
  Developer("weisgnosis", "WeisGnosis Team", "team@weisgnosis.com", url("https://weisgnosis.com"))
)

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Werror",
    "-Wunused:all",
    "-Yretain-trees"
  ),
  Test / parallelExecution := false,
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
  Compile / packageBin / mappings +=
    ((LocalRootProject / baseDirectory).value / "LICENSE" -> "META-INF/LICENSE"),
  Compile / packageSrc / mappings +=
    ((LocalRootProject / baseDirectory).value / "LICENSE" -> "META-INF/LICENSE"),
  Compile / packageDoc / mappings +=
    ((LocalRootProject / baseDirectory).value / "LICENSE" -> "META-INF/LICENSE")
)

lazy val core = (project in file("modules/core"))
  .settings(
    name        := "weisgnosis-agents-core",
    description := "Pure Scala 3 and ZIO agent runtime, model clients, tools and coordination.",
    commonSettings,
    libraryDependencies ++= Seq(
      "dev.zio"      %% "zio"                   % zioVersion,
      "dev.zio"      %% "zio-streams"           % zioVersion,
      "dev.zio"      %% "zio-json"              % zioJsonVersion,
      "dev.zio"      %% "zio-http"              % zioHttpVersion,
      "com.networknt" % "json-schema-validator" % jsonSchemaValidatorVersion,
      "org.slf4j"     % "slf4j-jdk14"           % slf4jVersion % Test,
      "dev.zio"      %% "zio-test"              % zioVersion   % Test,
      "dev.zio"      %% "zio-test-sbt"          % zioVersion   % Test
    )
  )

lazy val harness = (project in file("modules/harness"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(
    name        := "weisgnosis-agents-harness",
    description := "ZIO agent harness with scoped permissions, persistence, memory and subagents.",
    commonSettings,
    libraryDependencies ++= Seq(
      "org.snakeyaml" % "snakeyaml-engine" % snakeYamlEngineVersion,
      "dev.zio"      %% "zio"              % zioVersion,
      "dev.zio"      %% "zio-streams"      % zioVersion,
      "dev.zio"      %% "zio-json"         % zioJsonVersion,
      "dev.zio"      %% "zio-test"         % zioVersion % Test,
      "dev.zio"      %% "zio-test-sbt"     % zioVersion % Test
    )
  )

lazy val mcp = (project in file("modules/mcp"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(
    name        := "weisgnosis-agents-mcp",
    description := "Scoped Scala 3 and ZIO Model Context Protocol clients and transports.",
    commonSettings,
    Test / fork := true,
    Test / javaOptions ++= Seq(
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.rmi/sun.rmi.transport=ALL-UNNAMED"
    ),
    libraryDependencies ++= Seq(
      "io.modelcontextprotocol.sdk" % "mcp-core"          % mcpJavaSdkVersion % Test,
      "io.modelcontextprotocol.sdk" % "mcp-json-jackson2" % mcpJavaSdkVersion % Test,
      "org.apache.tomcat.embed"     % "tomcat-embed-core" % tomcatVersion     % Test,
      "org.slf4j"                   % "slf4j-jdk14"       % slf4jVersion      % Test,
      "dev.zio"                    %% "zio"               % zioVersion,
      "dev.zio"                    %% "zio-streams"       % zioVersion,
      "dev.zio"                    %% "zio-json"          % zioJsonVersion,
      "dev.zio"                    %% "zio-http"          % zioHttpVersion,
      "dev.zio"                    %% "zio-process"       % zioProcessVersion,
      "dev.zio"                    %% "zio-test"          % zioVersion        % Test,
      "dev.zio"                    %% "zio-test-sbt"      % zioVersion        % Test
    )
  )

lazy val storagePostgresql = (project in file("modules/storage-postgresql"))
  .dependsOn(harness % "compile->compile;test->test")
  .settings(
    name        := "weisgnosis-agents-storage-postgresql",
    description := "Host-owned PostgreSQL persistence adapters for the WeisGnosis agent harness.",
    commonSettings,
    libraryDependencies ++= Seq(
      "org.postgresql" % "postgresql"   % "42.7.11"  % Test,
      "dev.zio"       %% "zio-test"     % zioVersion % Test,
      "dev.zio"       %% "zio-test-sbt" % zioVersion % Test
    )
  )

lazy val otel = (project in file("modules/otel"))
  .dependsOn(core)
  .settings(
    name        := "weisgnosis-agents-otel",
    description := "Opt-in OpenTelemetry observation for the WeisGnosis agent runtime.",
    commonSettings,
    libraryDependencies ++= Seq(
      "dev.zio"         %% "zio-opentelemetry"           % zioOpenTelemetryVersion,
      "dev.zio"         %% "zio-test"                    % zioVersion           % Test,
      "dev.zio"         %% "zio-test-sbt"                % zioVersion           % Test,
      "io.opentelemetry" % "opentelemetry-sdk-testing"   % openTelemetryVersion % Test,
      "io.opentelemetry" % "opentelemetry-exporter-otlp" % openTelemetryVersion % Test,
      "org.slf4j"        % "slf4j-jdk14"                 % slf4jVersion         % Test
    )
  )

lazy val examples = (project in file("modules/examples"))
  .dependsOn(core, harness, mcp)
  .settings(
    name           := "weisgnosis-agents-examples",
    publish / skip := true,
    commonSettings,
    libraryDependencies ++= Seq(
      "org.slf4j" % "slf4j-jdk14"  % slf4jVersion % Runtime,
      "dev.zio"  %% "zio-test"     % zioVersion   % Test,
      "dev.zio"  %% "zio-test-sbt" % zioVersion   % Test
    )
  )

lazy val root = (project in file("."))
  .aggregate(core, harness, mcp, storagePostgresql, otel, examples)
  .settings(
    name           := "weisgnosis-agents",
    publish / skip := true
  )

addCommandAlias("verify", ";scalafmtCheckAll;scalafmtSbtCheck;compile;test")
addCommandAlias(
  "packageArtifacts",
  ";core / Compile / packageBin;core / Compile / packageSrc;core / Compile / packageDoc;core / makePom" +
    ";harness / Compile / packageBin;harness / Compile / packageSrc;harness / Compile / packageDoc;harness / makePom" +
    ";mcp / Compile / packageBin;mcp / Compile / packageSrc;mcp / Compile / packageDoc;mcp / makePom" +
    ";storagePostgresql / Compile / packageBin;storagePostgresql / Compile / packageSrc;storagePostgresql / Compile / packageDoc;storagePostgresql / makePom" +
    ";otel / Compile / packageBin;otel / Compile / packageSrc;otel / Compile / packageDoc;otel / makePom"
)

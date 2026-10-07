val scala3Version                  = "3.9.0"
val zioVersion                     = "2.1.26"
val zioJsonVersion                 = "0.9.1"
val zioHttpVersion                 = "3.11.3"
val snakeYamlEngineVersion: String = "3.1.1"
val mcpJavaSdkVersion: String      = "0.17.2"
val tomcatVersion: String          = "11.0.18"
val slf4jVersion: String           = "2.0.16"

ThisBuild / scalaVersion := scala3Version
ThisBuild / version      := "0.1.0-SNAPSHOT"
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
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
  Compile / packageBin / mappings +=
    ((LocalRootProject / baseDirectory).value / "LICENSE" -> "META-INF/LICENSE"),
  Compile / packageSrc / mappings +=
    ((LocalRootProject / baseDirectory).value / "LICENSE" -> "META-INF/LICENSE")
)

lazy val core = (project in file("modules/core"))
  .settings(
    name := "weisgnosis-agents-core",
    commonSettings,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"          % zioVersion,
      "dev.zio" %% "zio-streams"  % zioVersion,
      "dev.zio" %% "zio-json"     % zioJsonVersion,
      "dev.zio" %% "zio-http"     % zioHttpVersion,
      "dev.zio" %% "zio-test"     % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test
    )
  )

lazy val harness = (project in file("modules/harness"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(
    name := "weisgnosis-agents-harness",
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
    name := "weisgnosis-agents-mcp",
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
      "dev.zio"                    %% "zio-test"          % zioVersion        % Test,
      "dev.zio"                    %% "zio-test-sbt"      % zioVersion        % Test
    )
  )

lazy val storagePostgresql = (project in file("modules/storage-postgresql"))
  .dependsOn(harness)
  .settings(
    name := "weisgnosis-agents-storage-postgresql",
    commonSettings,
    libraryDependencies ++= Seq(
      "org.postgresql" % "postgresql"   % "42.7.11"  % Test,
      "dev.zio"       %% "zio-test"     % zioVersion % Test,
      "dev.zio"       %% "zio-test-sbt" % zioVersion % Test
    )
  )

lazy val examples = (project in file("modules/examples"))
  .dependsOn(core, harness, mcp)
  .settings(
    name           := "weisgnosis-agents-examples",
    publish / skip := true,
    commonSettings,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-test"     % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test
    )
  )

lazy val root = (project in file("."))
  .aggregate(core, harness, mcp, storagePostgresql, examples)
  .settings(
    name           := "weisgnosis-agents",
    publish / skip := true
  )

addCommandAlias("verify", ";scalafmtCheckAll;scalafmtSbtCheck;compile;test")
addCommandAlias(
  "packageArtifacts",
  ";core / Compile / packageBin;core / Compile / packageSrc;core / makePom" +
    ";harness / Compile / packageBin;harness / Compile / packageSrc;harness / makePom" +
    ";mcp / Compile / packageBin;mcp / Compile / packageSrc;mcp / makePom" +
    ";storagePostgresql / Compile / packageBin;storagePostgresql / Compile / packageSrc;storagePostgresql / makePom"
)

val scala3Version  = "3.9.0"
val zioVersion     = "2.1.26"
val zioJsonVersion = "0.9.1"
val zioHttpVersion = "3.11.3"

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
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
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
      "dev.zio" %% "zio"          % zioVersion,
      "dev.zio" %% "zio-streams"  % zioVersion,
      "dev.zio" %% "zio-json"     % zioJsonVersion,
      "dev.zio" %% "zio-test"     % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test
    )
  )

lazy val mcp = (project in file("modules/mcp"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(
    name := "weisgnosis-agents-mcp",
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

lazy val root = (project in file("."))
  .aggregate(core, harness, mcp)
  .settings(
    name           := "weisgnosis-agents",
    publish / skip := true
  )

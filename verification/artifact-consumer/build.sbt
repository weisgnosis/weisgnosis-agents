scalaVersion := "3.9.0"
Compile / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Werror",
  "-Wunused:all",
  "-Yretain-trees"
)
publish / skip             := true
Compile / unmanagedSources := Seq(baseDirectory.value / "ArtifactSmoke.scala")
scalafmtConfig             := baseDirectory.value / "../../.scalafmt.conf"
resolvers := Seq(
  "workspace-artifacts" at (baseDirectory.value / "../../target/artifact-verification/repository").toURI.toString,
  Resolver.mavenCentral
)
libraryDependencies ++= Seq("core", "harness", "mcp", "storage-postgresql").map(module =>
  "com.weisgnosis" %% s"weisgnosis-agents-$module" % "0.1.0-SNAPSHOT"
)
Compile / run / fork := true
Compile / run / javaOptions +=
  s"-Dweisgnosis.artifact.manifest=${(baseDirectory.value / "../../target/artifact-verification/manifest.json").getAbsolutePath}"

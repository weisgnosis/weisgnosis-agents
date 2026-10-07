lazy val artifactVersion: SettingKey[String] =
  settingKey[String]("Checked packaged artifact version")

artifactVersion := {
  val selected  = sys.props.getOrElse("weisgnosis.artifact.version", "0.1.0-SNAPSHOT")
  val candidate = "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)-RC[1-9][0-9]*"
  require(
    selected == "0.1.0-SNAPSHOT" || (selected.length <= 64 && selected.matches(candidate)),
    "Artifact version must be the default SNAPSHOT or a canonical ASCII RC version"
  )
  selected
}

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
  "com.weisgnosis" %% s"weisgnosis-agents-$module" % artifactVersion.value
)
Compile / run / fork := true
Compile / run / javaOptions +=
  s"-Dweisgnosis.artifact.manifest=${(baseDirectory.value / "../../target/artifact-verification/manifest.json").getAbsolutePath}"

Compile / run / javaOptions += s"-Dweisgnosis.artifact.version=${artifactVersion.value}"

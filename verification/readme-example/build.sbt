import java.util.regex.Pattern

val releaseVersion: String = "0.1.1-SNAPSHOT"

lazy val readmeFile: SettingKey[File] =
  settingKey[File]("README containing the first-agent example")
lazy val checkReadmeExtraction: TaskKey[Unit] =
  taskKey[Unit]("Reject missing or ambiguous example blocks")

def readExampleBlock(source: String, name: String): String = {
  val start: String = s"<!-- $name:start -->"
  val end: String   = s"<!-- $name:end -->"
  require(
    source.split(Pattern.quote(start), -1).length == 2 &&
      source.split(Pattern.quote(end), -1).length == 2,
    s"README must contain exactly one $name block"
  )
  val block =
    ("(?s)" + Pattern.quote(start) + "\\s*```scala\\r?\\n(.*?)\\r?\\n```\\s*" + Pattern.quote(
      end
    )).r
  block.findFirstMatchIn(source) match {
    case Some(found) => found.group(1)
    case None        => sys.error(s"README $name must contain one Scala code fence")
  }
}

scalaVersion                            := "3.9.0"
libraryDependencies += "com.weisgnosis" %% "weisgnosis-agents-core" % releaseVersion
scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Werror", "-Wunused:all")
publish / skip := true
resolvers := Seq(
  "workspace-artifacts" at (baseDirectory.value / "../../target/artifact-verification/repository").toURI.toString,
  Resolver.mavenCentral
)
externalResolvers          := resolvers.value
scalafmtConfig             := baseDirectory.value / "../../.scalafmt.conf"
readmeFile                 := baseDirectory.value / "../../README.md"
Compile / unmanagedSources := Nil

Compile / sourceGenerators += Def.task {
  val source: String = IO.read(readmeFile.value)
  val expectedBuild: String =
    s"""scalaVersion := "${scalaVersion.value}"
       |libraryDependencies += "com.weisgnosis" %% "weisgnosis-agents-core" % "$releaseVersion"""".stripMargin
  require(
    readExampleBlock(source, "first-agent-build").replace("\r\n", "\n").trim ==
      expectedBuild.replace("\r\n", "\n"),
    "README dependency block must match this consumer's Scala version and packaged dependency"
  )
  val output: File = (Compile / sourceManaged).value / "FirstAgent.scala"
  IO.write(output, readExampleBlock(source, "first-agent-source") + "\n")
  Seq(output)
}.taskValue

checkReadmeExtraction := {
  val valid: String = "<!-- sample:start -->\n```scala\nval answer = 42\n```\n<!-- sample:end -->"
  require(readExampleBlock(valid, "sample") == "val answer = 42")
  require(readExampleBlock(valid.replace("\n", "\r\n"), "sample") == "val answer = 42")
  Seq(
    "",
    valid + valid,
    valid.replace("```scala", "```text"),
    valid.replace("<!-- sample:end -->", "")
  )
    .foreach(source => require(scala.util.Try(readExampleBlock(source, "sample")).isFailure))
}

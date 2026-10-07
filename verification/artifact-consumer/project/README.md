# Consumer build support

build.properties pins the same sbt runner as the repository. plugins.sbt enables the same scalafmt plugin. The standalone build uses the root scalafmt configuration and fatal-warning Scala options, resolves only published-style module dependencies, and runs its executable in a forked JVM for actual classpath inspection.

It does not inherit root source projects or Test dependencies. Selected artifact coordinates are checked by the standalone build; default SNAPSHOT and explicit canonical RC consumption share the same forked hash/version verification. See the parent README for verification commands and the intentionally bounded compatibility claim.

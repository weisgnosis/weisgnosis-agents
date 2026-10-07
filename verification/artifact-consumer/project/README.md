# Consumer build support

build.properties pins the same sbt runner as the repository. plugins.sbt enables the same scalafmt plugin. The standalone build uses the root scalafmt configuration and fatal-warning Scala options, resolves only published-style module dependencies, and runs its executable in a forked JVM for actual classpath inspection.

It does not inherit root source projects or Test dependencies. See the parent README for verification commands and the intentionally bounded compatibility claim.

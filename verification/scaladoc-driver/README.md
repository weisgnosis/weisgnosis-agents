# Pinned documentation driver

This nonpublished, nonaggregated build tool generates the five library Scaladoc
archives with Scala 3.9.0. No driver, compiler or ZIO tool dependency enters a
library runtime or published POM. Production compilation keeps its existing
compiler and fatal-warning settings.

The upstream [classpath fix](https://github.com/scala/scala3/pull/27167) prevents
the TASTy inspector from reparsing an already configured classpath. The pinned
generator lacks that fix. This adapter extracts the normal options without the
classpath flag, then supplies the exact dependency classpath through the parsed
arguments. The inspector configures it once. No diagnostic is filtered or
suppressed. Generator errors or warnings fail the forked process; source
compilation remains independently governed by its existing fatal-warning flags.

The sbt task retains its individual TASTy inputs, output directory, documentation
options and external API mappings. The driver retains output replacement and
optional Inkuire generation; directory inputs are explicitly rejected. Rendering
and resource interaction are suspended in ZIO. The fork prevents tool lifecycle
and exit handling from affecting the sbt JVM.

This intentionally uses the pinned internal `Scaladoc.extract` and
`Scaladoc.run(Args)` APIs, with its source in the required upstream package.
The driver alone enables `-experimental` to access those upstream APIs, which
are marked experimental. Library source compilation does not enable that flag.
Remove the adapter and restore the standard sbt doc invocation when the selected
official Scaladoc release contains the upstream fix. Revalidate dependencies,
snippet compilation, licensed archives and a fresh consumer when changing that
tool version.

Run `sbt "otel / Compile / doc"` for a focused generation check, then
`sbt packageArtifacts` for all five archives. These commands do not publish.

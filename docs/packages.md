# Installing release packages

All five modules of `0.1.0-RC1` (Git tag `v0.1.0-RC1`) are published to
[Maven Central](https://central.sonatype.com/artifact/com.weisgnosis/weisgnosis-agents-core_3/0.1.0-RC1).
Use JDK 21, Scala 3.9.0 and sbt 1.12.11. Candidate APIs are not frozen;
rebuild consumers when upgrading.

## Maven Central

Maven Central is a default sbt resolver. No token or custom resolver is required.
All five modules were verified in a separate compiling and running consumer
with isolated dependency caches and Maven Central as its only resolver.
In your application's `build.sbt`, add:

```scala
scalaVersion := "3.9.0"

libraryDependencies +=
  "com.weisgnosis" %% "weisgnosis-agents-core" % "0.1.0-RC1"
```

Add only the modules you need:

| Artifact | Purpose |
| --- | --- |
| `weisgnosis-agents-core` | Agent execution, tools, model clients and coordination. |
| `weisgnosis-agents-harness` | Context, memory, approvals, skills and subagents. |
| `weisgnosis-agents-mcp` | MCP clients and tool/resource integration. |
| `weisgnosis-agents-storage-postgresql` | Host-owned PostgreSQL persistence. |
| `weisgnosis-agents-otel` | Host-owned OpenTelemetry tracing. |

All use organization `com.weisgnosis`, Scala `%%` coordinates and version
`0.1.0-RC1`. Transitive library modules resolve automatically from Maven Central.
Continue with the complete [quickstart](../README.md#quickstart).

Binary, source and Scaladoc JARs and POMs have detached `.asc` signatures in
Central. See the [public key and verification commands](../project/keys/README.md).
The Central artifacts preserve the bytes of the original GitHub RC1 release.

## Development snapshots

`0.1.1-SNAPSHOT` is available from the public Sonatype snapshot repository. It includes
`ToolSchema` and `Tool.derived`, which are not part of RC1. Add:

```scala
resolvers += "Sonatype snapshots" at "https://central.sonatype.com/repository/maven-snapshots/"
libraryDependencies += "com.weisgnosis" %% "weisgnosis-agents-core" % "0.1.1-SNAPSHOT"
```

All five modules use the same version. Binary, source and Scaladoc JARs and POMs have
detached signatures from the [release key](../project/keys/README.md). Downloads need
no credentials. A separate consumer verified the published module bytes against the
local package manifest and compiled and ran the public APIs from a fresh cache.
The [first-agent example](../README.md#first-agent) was also compiled against the
public snapshot repository.

Snapshots are mutable development builds. Sonatype retains them for a limited period;
see its [snapshot policy](https://central.sonatype.org/publish/publish-portal-snapshots/).
Use a release coordinate when you need an immutable published version.

## GitHub Packages

The same release is also available from
`https://maven.pkg.github.com/weisgnosis/weisgnosis-agents`.
GitHub requires authentication even when downloading public Maven packages.
Supply your GitHub username as `GITHUB_PACKAGES_USER` and a personal access token
(classic) with `read:packages` as `GITHUB_PACKAGES_TOKEN` in the sbt process
environment. Never put token values in source control. See GitHub's
[Maven authentication instructions](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry#authenticating-to-github-packages).

Add this resolver and credentials alongside the dependency above:

```scala
resolvers += "WeisGnosis GitHub Packages" at
  "https://maven.pkg.github.com/weisgnosis/weisgnosis-agents"

credentials += Credentials(
  "GitHub Package Registry",
  "maven.pkg.github.com",
  sys.env("GITHUB_PACKAGES_USER"),
  sys.env("GITHUB_PACKAGES_TOKEN")
)
```

## Downloadable Maven repository

For installation from a local archive, download
`weisgnosis-agents-0.1.0-RC1-maven.zip` and `SHA256SUMS` from the
[GitHub Release](https://github.com/weisgnosis/weisgnosis-agents/releases/tag/v0.1.0-RC1).
Verify the archive checksum before extracting it to a directory you own. Its root
contains `com/weisgnosis/`; select the directory immediately above `com` as the
Maven resolver:

```scala
resolvers += "WeisGnosis release archive" at
  file("/absolute/path/to/extracted-maven-repository").toURI.toString
```

Use the same RC dependency coordinates without the GitHub resolver or
`credentials` setting; this archive route needs no
GitHub token or package environment variables. Other dependencies still resolve from
Maven Central. The release also contains individual binary, source and Scaladoc
JARs and POMs, plus checksums for the downloadable package files. A checksum
detects altered bytes; it is not a publisher signature. This original GitHub
archive contains unsigned copies; detached signatures are available in Central.

## Rebuild a candidate from source

Check out the release tag and explicitly select the candidate version:

```text
sbt "-Dweisgnosis.candidate.version=0.1.0-RC1" verify packageArtifacts
sbt "artifactVerification / runMain com.weisgnosis.agents.verification.CheckPreparation --version 0.1.0-RC1"
```

The default source build remains `0.1.1-SNAPSHOT`. Local preparation does not
publish remotely. See the [packaged consumer](../verification/artifact-consumer/README.md)
for selected-version archive and consumer verification.

## Maintainer publication

Central publication promotes verified release artifacts without rebuilding their
JARs or POMs. Sign each binary, source and Scaladoc JAR and POM with the primary
release key, include Maven `.md5` and `.sha1` checksums, and preserve the Maven
directory layout in the deployment ZIP. Private signing material and Central
tokens stay outside the repository. Upload with `USER_MANAGED`, inspect
validation, then publish only a `VALIDATED` deployment. See the
[Publisher API](https://central.sonatype.org/publish/publish-portal-api/).
Verify public Central downloads and a fresh external consumer against the
original release manifest before announcing availability.

The manually dispatched [GitHub Packages publication workflow](../.github/workflows/publish-packages.yml)
accepts an exact source SHA and canonical candidate version. It verifies the
library with PostgreSQL, checks the archives and a local consumer, publishes five
modules using the repository's temporary package token, then resolves them through
GitHub Packages in a fresh consumer with isolated caches. The loaded module
hashes must match the verified manifest before release assets are admitted.

GitHub Packages publication is not atomic across five modules. If publication or remote
verification fails, inspect which versions exist; do not automatically delete,
overwrite or reuse a partially published version. Create the public GitHub
prerelease only after all checks and package uploads succeed. Tags, package
versions and release assets must identify the same verified source.

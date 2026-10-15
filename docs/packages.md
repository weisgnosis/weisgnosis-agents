# Installing release packages

This guide targets the published `0.1.0-RC1` (Git tag `v0.1.0-RC1`). The
[release page](https://github.com/weisgnosis/weisgnosis-agents/releases/tag/v0.1.0-RC1)
provides the Maven archive, individual artifacts and checksums. All five modules
are also available through GitHub Packages; installation from that registry was
verified in a separate consumer with fresh dependency caches.
Use JDK 21,
Scala 3.9.0 and sbt 1.12.11. Candidate APIs are not frozen; rebuild consumers
when upgrading. Artifacts are unsigned and are not published to Maven Central.

## GitHub Packages

The Maven repository is
`https://maven.pkg.github.com/weisgnosis/weisgnosis-agents`.
GitHub requires authentication even when downloading public Maven packages.
Supply your GitHub username as `GITHUB_PACKAGES_USER` and a personal access token
(classic) with `read:packages` as `GITHUB_PACKAGES_TOKEN` in the sbt process
environment. Never put token values in source control. See GitHub's
[Maven authentication instructions](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry#authenticating-to-github-packages).

In your application's `build.sbt`:

```scala
scalaVersion := "3.9.0"

resolvers += "WeisGnosis GitHub Packages" at
  "https://maven.pkg.github.com/weisgnosis/weisgnosis-agents"

credentials += Credentials(
  "GitHub Package Registry",
  "maven.pkg.github.com",
  sys.env("GITHUB_PACKAGES_USER"),
  sys.env("GITHUB_PACKAGES_TOKEN")
)

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
`0.1.0-RC1`. Transitive library modules resolve automatically from this repository.
Continue with the complete [quickstart](../README.md#quickstart).

## Downloadable Maven repository

For installation without GitHub Packages credentials, download
`weisgnosis-agents-0.1.0-RC1-maven.zip` and `SHA256SUMS` from the
[GitHub Release](https://github.com/weisgnosis/weisgnosis-agents/releases/tag/v0.1.0-RC1).
Verify the archive checksum before extracting it to a directory you own. Its root
contains `com/weisgnosis/`; select the directory immediately above `com` as the
Maven resolver:

```scala
resolvers += "WeisGnosis release archive" at
  file("/absolute/path/to/extracted-maven-repository").toURI.toString
```

Use the same RC dependency coordinates, but omit the GitHub resolver and
`credentials` setting from the first example; this archive route needs no
GitHub token or package environment variables. Other dependencies still resolve from
Maven Central. The release also contains individual binary, source and Scaladoc
JARs and POMs, plus checksums for the downloadable package files. A checksum
detects altered bytes; it is not a publisher signature.

## Rebuild a candidate from source

Check out the release tag and explicitly select the candidate version:

```text
sbt "-Dweisgnosis.candidate.version=0.1.0-RC1" verify packageArtifacts
sbt "artifactVerification / runMain com.weisgnosis.agents.verification.CheckPreparation --version 0.1.0-RC1"
```

The default source build remains `0.1.0-SNAPSHOT`. Local preparation does not
publish remotely. See the [packaged consumer](../verification/artifact-consumer/README.md)
for selected-version archive and consumer verification.

## Maintainer publication

The manually dispatched [publication workflow](../.github/workflows/publish-packages.yml)
accepts an exact source SHA and canonical candidate version. It verifies the
library with PostgreSQL, checks the archives and a local consumer, publishes five
modules using the repository's temporary package token, then resolves them through
GitHub Packages in a fresh consumer with isolated caches. The loaded module
hashes must match the verified manifest before release assets are admitted.

Registry publication is not atomic across five modules. If publication or remote
verification fails, inspect which versions exist; do not automatically delete,
overwrite or reuse a partially published version. Create the public GitHub
prerelease only after all checks and package uploads succeed. Tags, package
versions and release assets must identify the same verified source.

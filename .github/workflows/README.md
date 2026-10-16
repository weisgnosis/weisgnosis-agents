# Continuous integration

[ci.yml](ci.yml) provisions JDK 21 and sbt, then runs `sbt verify packageArtifacts` on pushes and pull requests. Verification checks formatting, fatal-warning compilation and tests; packaging creates binary/source JARs and POMs for five selected library modules.

A PostgreSQL 16 service enables actual storage integration scenarios through WEISGNOSIS_POSTGRES_URL/USER/PASSWORD. Missing local configuration skips those scenarios; it does not verify PostgreSQL.

The [artifact consumer checks](../../verification/artifact-consumer/README.md) inspect archive/POM boundaries, stage generated artifacts and compile/execute a separate consumer. Reversible archive-admission checks reject selected malformed packaging.

The [README consumer](../../verification/README.md#readme-first-agent-compilation) extracts and
compiles the first-agent example against freshly packaged core with fatal warnings.
It checks the declared dependency block and rejects missing or ambiguous example markers.
It does not execute provider requests or require model credentials.

The manual [candidate.yml](candidate.yml) accepts a checked RC version, verifies and packages with the database fixture, validates source admission and runs an isolated consumer against the selected artifacts. Successful steps upload unsigned versioned artifacts with source commit metadata. It does not tag, sign or publish them.

Local successful checks do not prove successful remote workflow execution. The CI and candidate workflows do not publish to Maven Central or GitHub Packages. The [completion checklist](../../docs/architecture/tracker.md#remaining-library-completion-work) tracks library behavior and local artifact integrity; remote CI and publication are not library completion gates, and the [build contract](../../project/README.md#local-artifact-packaging) explains packaging boundaries. CI does not contact a public model provider; live smoke is separately enabled.

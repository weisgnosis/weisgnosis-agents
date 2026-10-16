# Distribution verification

## README first-agent compilation

After `sbt packageArtifacts` and the artifact preparation below, from `verification/readme-example`, run `sbt scalafmtSbtCheck checkReadmeExtraction clean compile`.
This standalone consumer extracts the marked first-agent source directly from the root README
and checks that its dependency block matches the current SNAPSHOT dependency and Scala version.
Only the staged packaged repository and Maven Central participate in dependency resolution. Compilation uses fatal warnings and
needs only core; its transitive dependencies supply ZIO, zio-json and zio-http.
Missing, duplicate or malformed source markers fail the check. CI compiles the example without
running it, contacting a model provider or supplying API credentials.

## Runtime scenarios and historical comparisons

Run `sbt "examples / runMain com.weisgnosis.agents.examples.SyntheticRuntimeComparison"`
for offline lifecycle contracts through the public ReAct runtime. No credentials
or provider requests are used. The opt-in
[LiveRuntimeComparison](../modules/examples/src/main/scala/com/weisgnosis/agents/examples/LiveRuntimeComparison.scala)
exercises the actual Scala HTTP runtime against a selected provider; its
[guide](../docs/live-runtime-comparison.md) supplies configuration and scenarios.

The current tree contains Scala verification tools only. The
[asyncio comparison](../docs/synthetic-runtime-comparison.md),
[pinned AgentScope comparison](../docs/agentscope-python-comparison.md) and
[matched live comparison](../docs/live-runtime-comparison.md) retain bounded
historical results and immutable source references. Current Scala runs cannot
refresh the Python arms or establish new comparative results.

## Native directory aliases

Run the opt-in [PathSafetySpec](../modules/core/src/test/scala/com/weisgnosis/agents/core/tool/PathSafetySpec.scala)
against actual Windows directory junctions or POSIX directory symlinks:

First run `sbt "export artifactVerification / Runtime / fullClasspath"` and copy
the exported classpath. After that sbt process exits, run from the repository root:

```text
<jdk-21>/bin/java -cp "<exported-classpath>" com.weisgnosis.agents.verification.NativePaths --java-home "<jdk-21>" --sbt-launch "<sbt-launch.jar>"
```

On Windows select `bin/java.exe`. The
[Scala launcher](artifact-preparation/src/main/scala/com/weisgnosis/agents/verification/NativePaths.scala)
creates a disposable owned directory with ordinary and `.ssh`
targets and aliases, supplies `WEISGNOSIS_PATH_FIXTURE` to the focused test process, waits for that
process and removes only its known links, marker files and empty directories. It launches the JDK
directly with the project JVM options; the project selects its pinned sbt version. Core tests run
in that JVM for this invocation, so timeout/Ctrl-C joins the process borrowing the fixture.
Other modules retain their configured fork settings, including MCP subprocess fixture requirements. `JAVA_HOME` can replace `--java-home`. Add
`--full` to run `verify` while the same fixture remains available. Run only one sbt process at a time.

Windows uses PowerShell to create junctions; POSIX needs permission to create
directory symlinks. No administrator permissions or security settings are changed. The integration
group is explicitly ignored without the environment variable; a supplied invalid fixture fails.
An external host may instead supply the same fixture layout and own its lifetime through verification.
The group checks existing and missing-child canonicalization, sensitive-target rejection, permitted
ordinary aliases and lexical traversal rejection. This does not certify Windows file symlinks or an
atomic sandbox against concurrent hostile filesystem changes.

The launcher reads an optional local `.jvmopts`; without it, it selects a 4 GiB
heap limit and 16 MiB stack. Launch it directly with Java, not `sbt runMain`:
it owns a child sbt process, and this host permits only one sbt process at a time.

## Packaged external consumer

The [artifact consumer](artifact-consumer/README.md) inspects five modules' freshly generated binary/source/Scaladoc
archives and POMs, then compiles and runs a separate application against those artifacts. It checks
coordinates, licenses, dependency scopes, source admission, selected model/request/schema APIs and
the optional PostgreSQL and telemetry companions. It does not construct a DataSource or contact a database;
backend behavior requires the configured storage integration suite.

The [pinned documentation driver](scaladoc-driver/README.md) generates Scaladoc
without suppressing diagnostics; it is build-only and not distributed.

[Candidate preparation](artifact-consumer/README.md#candidate-preparation-acceptance) selects a checked
RC version and includes reversible target-only negative checks. See the consumer guide for commands.
CI runs verification and packaging before consumption. The manual candidate workflow uploads unsigned
artifacts and source metadata; local success does not establish remote execution, signing or publication.

The runnable [examples](../modules/examples/README.md) distinguish offline application checks from
explicit live-provider runs. Keep endpoint credentials in the process environment. The
[completion checklist](../docs/architecture/tracker.md) records remaining library outcomes.

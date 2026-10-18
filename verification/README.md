# Distribution verification

## README first-agent compilation

After `sbt packageArtifacts` and the artifact preparation below, from `verification/readme-example`, run `sbt scalafmtSbtCheck checkReadmeExtraction clean compile`.
This standalone consumer extracts the marked first-agent source directly from the root README
and checks that its dependency block matches the current SNAPSHOT dependency and Scala version.
Only the staged packaged repository and Maven Central participate in dependency resolution. Compilation uses fatal warnings and
needs only core; its transitive dependencies supply ZIO, zio-json and zio-http.
Missing, duplicate or malformed source markers fail the check. CI compiles the example without
running it, contacting a model provider or supplying API credentials.

To check the published snapshot instead, use a fresh `COURSIER_CACHE` and Ivy home,
override both `resolvers` and `externalResolvers` to Sonatype snapshots and Maven Central,
then run the same extraction and compilation tasks. This excludes staged and Ivy-local
library packages. The publication check additionally runs the full packaged consumer
and compares all five downloaded JAR hashes with the preparation manifest.

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


## Selected live native Responses profiles

[LiveNativeResponses](live-responses/LiveNativeResponses.scala) is an opt-in external consumer
in a neutral package. It uses packaged public APIs, with no source-project dependency or
private helpers. It performs two real-model profiles: one local arithmetic tool, then an
external-tool checkpoint serialized to JSON, restored in a fresh agent and resumed with a
checked host result. Both require a completed reply of `5`, exactly one successful call/result
pair, native replay metadata and positive output usage. The checkpoint profile also checks
the original proposal arguments and snapshot equality after decoding.

Prepare artifacts as above, then set process-local `WEISGNOSIS_LIVE_ENDPOINT` to a complete
Responses endpoint, `WEISGNOSIS_LIVE_MODEL` to a tool-capable model and
`WEISGNOSIS_LIVE_API_KEY` to its credential. Each agent invocation permits at most four
ReAct iterations and has a 180-second timeout. Each model request has a 4,096-output-token
limit. The runner omits temperature. Supply the variables through your own secret manager; do not place values in
source or shell history.

From `verification/artifact-consumer`, run:

```sh
sbt 'set Compile / unmanagedSources := Seq(file("../live-responses/LiveNativeResponses.scala"))' 'set Compile / run / mainClass := Some("consumer.live.LiveNativeResponses")' clean compile run
```

These settings affect that sbt session only; the ordinary packaged-consumer entry point is
unchanged. Successful output contains one JSON receipt per profile, with every relevant
assertion true and process exit zero. `checkpointRoundTrip` is false for the local-tool
profile because it does not perform restoration. This run does not prove encrypted reasoning
support, server-side inference cancellation, hosted tools or provider-wide compatibility. See the
[dated live results](../docs/live-e2e-results.md#native-responses-live-profiles-on-2026-10-18).


## Live Responses HTTP cancellation

[LiveResponsesHttpCancellation](live-responses/LiveResponsesHttpCancellation.scala) uses the same
staged artifacts and process-local endpoint/model/key configuration as the previous runner.
Select a model that streams nonempty reasoning text or summary deltas. From
`verification/artifact-consumer`, run:

```sh
sbt 'set Compile / unmanagedSources := Seq(file("../live-responses/LiveResponsesHttpCancellation.scala"))' 'set Compile / run / mainClass := Some("consumer.live.LiveResponsesHttpCancellation")' clean compile run
```

The borrowed driver delegates the actual network request and forwards real SSE frames until
the first reasoning delta; it then parks downstream while the upstream reader stays scoped.
The runtime's parsed-frame event triggers hard cancellation. A controlled finalizer holds
cleanup so the runner can assert that the consumer has not returned. After release, a marker
registered before HTTP acquisition checks that the driver's finalizers have finished.
No provider response is synthesized.

A successful `native-http-cancellation` receipt requires the original interruptor, joined cleanup,
no false completion, zero tool calls and one recovery message. The incomplete reasoning context
must be rejected before another HTTP driver call. A fresh agent on the same client must then
complete with `READY`; dispatch counts must be one before the fresh context and two overall.
Each invocation has a 180-second timeout; the entire scoped profile has a 420-second watchdog
and an emergency cleanup release. The interrupted request configures 1,024 output tokens;
the fresh-context request configures 4,096, with temperature omitted in both.

This is an instrumented live ownership check, not a measurement of unmodified stream timing,
physical socket closure or cancellation of inference on the server. The
[dated results](../docs/live-e2e-results.md#native-responses-live-profiles-on-2026-10-18)
record the two selected model runs.

# Distribution verification

## Synthetic runtime comparison

The [comparison guide](../docs/synthetic-runtime-comparison.md) runs matched
offline lifecycle contracts through the public ReAct runtime and a Python
TaskGroup reference. Run `python -W error verification/compare_asyncio.py` and
`sbt "examples / runMain com.weisgnosis.agents.examples.SyntheticRuntimeComparison"`.
No credentials or provider requests are used. Labelled Python negative controls
include an owned child process; they are separate from the matched contracts and
do not compare agent frameworks or performance.

## Native directory aliases

Run the opt-in [PathSafetySpec](../modules/core/src/test/scala/com/weisgnosis/agents/core/tool/PathSafetySpec.scala)
against actual Windows directory junctions or POSIX directory symlinks:

```text
python verification/native_paths.py --java-home <jdk-21> --sbt-launch <sbt-launch.jar>
```

The [launcher](native_paths.py) creates a disposable owned directory with ordinary and `.ssh`
targets and aliases, supplies `WEISGNOSIS_PATH_FIXTURE` to the focused test process, waits for that
process and removes only its known links, marker files and empty directories. It launches the JDK
directly with the project JVM options; the project selects its pinned sbt version. Core tests run
in that JVM for this invocation, so timeout/Ctrl-C joins the process borrowing the fixture.
Other modules retain their configured fork settings, including MCP subprocess fixture requirements. `JAVA_HOME` can replace `--java-home`. Add
`--full` to run `verify` while the same fixture remains available. Run only one sbt process at a time.

Windows needs a Python runtime exposing `_winapi.CreateJunction`; POSIX needs permission to create
directory symlinks. No administrator permissions or security settings are changed. The integration
group is explicitly ignored without the environment variable; a supplied invalid fixture fails.
An external host may instead supply the same fixture layout and own its lifetime through verification.
The group checks existing and missing-child canonicalization, sensitive-target rejection, permitted
ordinary aliases and lexical traversal rejection. This does not certify Windows file symlinks or an
atomic sandbox against concurrent hostile filesystem changes.

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

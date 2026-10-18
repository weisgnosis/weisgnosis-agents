# Selected real-model E2E results

The current packaged refresh ran on 2026-10-15 against a private compatible gateway using
library revision `b77e11d`. **All 20 selected profile outcomes passed in eight
sequential application stages.** Mixed-model rooms ran twice in each direction;
these are bounded attempts, not a reliability estimate or language benchmark.
The [current machine-readable receipts](live-e2e-current.json) retain every stage
and its actual assertion fields; this file is replaced on the next refresh.

The [scenario design](live-e2e-scenarios.md) and [tracker](architecture/tracker.md)
separate executed contracts from unverified model/provider profiles. Other
profiles below retain their explicit verification dates and failed outcomes.

## Execution conditions and reproduction

The authenticated catalog selected `qwen3.8-27b` and `lfm2.5-2.6b-mlx` by chat
capability, endpoint and availability. Qwen was loaded; LFM was cold-startable.
Requests used a private OpenAI-compatible Chat Completions gateway, process-local
credentials, 4,096 output tokens and a 300-second per-agent deadline. Cleanup can
extend the deadline. Each transport bounded response bytes to one MiB. Admitted
model turns and tools were not retried to manufacture passing results.

The actual Java entrypoints used the exported examples Runtime classpath with
core, harness and MCP class directories replaced by their freshly packaged
`0.1.0-SNAPSHOT` JARs. Examples remained compiled application classes; optional
storage-postgresql and otel were not part of this live classpath. These artifacts
also passed the separate archive-admission and clean packaged-consumer checks.
Credentials were supplied without echo and remained in process memory and child
JVM environments, outside source, logs and command history.

Use the [enabled live environment](../modules/examples/README.md#live-endpoint-verification)
with primary and peer selected from the current catalog. The four existing
entrypoints are:

```text
sbt "examples / runMain com.weisgnosis.agents.examples.LiveProviderSmoke"
sbt "examples / runMain com.weisgnosis.agents.examples.LiveParallelTools"
sbt "examples / runMain com.weisgnosis.agents.examples.LiveRuntimeComparison text parallel cancel cooperative-cancel"
sbt "examples / runMain com.weisgnosis.agents.examples.LiveCoordinationEvidence"
```

Run each with both model selections; coordination uses the other model as peer.
These SBT commands use current project classes. To reproduce packaged execution,
first run `sbt packageArtifacts "examples / Compile / compile" "export examples / Runtime / fullClasspath"`,
replace the three module class-directory entries with their corresponding binary
JARs under `modules/<module>/target/scala-3.9.0`, and invoke the same main classes
with JDK 21, `@.jvmopts` and that classpath. Keep model credentials process-local.
Require explicit smoke/tool pass markers and all JSON acceptance receipts;
process exit zero alone is insufficient.

## Single-agent profiles

Each cell is one selected attempt through the current packaged runtime.

| Profile | Qwen 27B | LFM reasoning 2.6B |
| --- | --- | --- |
| Streamed text, exactly one addition tool and Completed trimmed reply `5` | Passed | Passed |
| Sequential/parallel control: local tool peaks 1/2, exact sum `37` | Passed | Passed |
| Text-only Completed trimmed reply `READY` | Passed | Passed |
| Parallel tool turn: calls persisted before entry, exact observations and reply | Passed | Passed |
| Parent fiber interruption, joined cleanup and same-runtime continuation | Passed | Passed |
| Cooperative interruption, joined cleanup and same-runtime continuation | Passed | Passed |

Both parallel runtime receipts contain exactly the two expected tool names,
peakActive=2, callsPersistedBeforeEntry=true and historyComplete=true. Local
overlap is measured independently of model inference latency; no CPU throughput
or Python speed advantage is established.

## E3: Cooperative interruption and same-runtime continuation

Both selected models passed one parent-interruption and one cooperative attempt.
Every accepted cancellation receipt has completedResultPreserved,
unfinishedOnlyReconciled, cancellationJoinedCleanup, interruptionPreserved,
recoveryExactlyOnce, historyComplete and sameRuntimeContinuation all true.

The fixture waits for the completed-left observation and the held-right tool to
enter before cancellation. Parent interruption remains in the execution Exit;
cooperative interruption returns an Interrupted result. Cleanup joins before the
receipt, completed results survive, only the unfinished call receives Error,
and one recovery message is retained. Continuation completes on the same runtime
without tool replay or another recovery. A missing valid model batch would not
count as cancellation evidence; none occurred in these four attempts.

Use the runtime entrypoint above with `cancel` or `cooperative-cancel`. This
verifies local owned tool cleanup, not remote inference cancellation or restart
recovery.

## E4: Useful room and Pipeline evidence

| Analyst/reporter model | Checker model | Accepted room attempts |
| --- | --- | --- |
| Qwen | Qwen | 1/1 |
| LFM | LFM | 1/1 |
| Qwen | LFM | 2/2 |
| LFM | Qwen | 2/2 |

All six room receipts record five model requests, one evidence read and exactly
one accepted submission by each Analyst, Checker and Reporter. Every receipt
has queuedWithoutReasoning, flushedExactlyOnce, foreignToolsInert and
exactDecisions true. The task produces the exact blocked-release decision from
the three fixture records and preserves peer attribution without activating
foreign tool calls.

Qwen and LFM each passed one Pipeline profile with ten requests. Both retain
sequentialUseful, orderedBoundedFanout, cancellationJoined,
suspendedSuccessorNotStarted and interruptedSuccessorNotStarted true, with one
accepted SequentialSource and SequentialTarget submission each.

The host selects one phase-specific function with Required at temperature zero.
These outcomes establish useful host-guided coordination and bounded orchestration,
not autonomous planning or unrestricted tool selection. Exact counts are fixture
contracts, not a production request budget recommendation.

## E1: HTTP MCP evidence and restored authorization

Last verified on 2026-10-11; not rerun in the packaged refresh.

`LiveMcpEvidence` executed once on each selected model. LFM reasoning passed;
Qwen failed with the sanitized `domain_failure` category. The Qwen receipt does
not identify a narrower cause and is not accepted or replaced by the LFM result.

The accepted LFM receipt records two model requests, one MCP evidence read and
one authorized write. `askSuspendedWithoutWrite`, `deniedWithoutWrite`,
`planWithoutWrite`, `restoredWithoutReasoningOrReadReplay` and `exactReportBytes`
are all true. Actual MCP initialize/list/call traffic discovers both tool schemas;
installed declarations remove the host-selected namespace and logical report path.

The model derives the blocked-release report from BUILD-1, TEST-1 and SEC-1.
The same serialized Ask checkpoint is restored into fresh runtimes under current
Deny, actual PlanSession/PlanPermissionGate and confirmed Ask. Denied results
stop through the fixture's explicit host processor; approved Direct execution
requires no new model request or replayed read. The peer retains exactly one
canonical UTF-8 report with a final LF in a disposable Ref-backed byte ledger.
This establishes MCP effects and restoration, not filesystem/database persistence,
production authentication or durable single-use claims.

Reproduce with the enabled live environment and:

```text
sbt "examples / runMain com.weisgnosis.agents.examples.LiveMcpEvidence"
```

The exercise admits at most two model requests and uses one aggregate deadline.
E1 acceptance remains specific to the LFM attempt; the current smoke and room
profiles do not requalify the failed Qwen MCP task.

## E5: Oversized retrieval and explicit compaction

Last verified on 2026-10-11; not rerun in the packaged refresh.

`LiveContextEvidence` passed three independent attempts on each selected Qwen
and reasoning-LFM model, six successful attempts in total.
Each receipt records five model requests and one bounded retrieval, with
`storageVerified`, `previewVerified`, `summaryVerified`, `recallVerified`,
`pairingVerified`, `provenanceVerified` and `directoryRemoved` all true.

The fixture stores more than 80,000 characters as actual UTF-8 file bytes and
checks their content hash and exact retrieval key/path. A fresh middle fact is
absent from both previews and the immediate post-lookup model request; the model retrieves it
through the bounded `read_file` tool before completing with the exact fact.
Separate real summary and answer requests then compact seeded history to one
summary, preserving the recent occurrence of a repeated tool ID. Serialized
canonical history is expanded during a recording flush to check both retained
users' original IDs, metadata and order. Summary fidelity and exact recall are
separate checks. Joined cleanup removes the owned temporary directory.

Offline controls preserve the full observation after typed persistence failure
and reject an exact answer delivered as a truncated `Stopped` turn. The fixture
uses an example-owned single-file store; it does not measure long-term memory
scaling, general recall, remote storage or concurrent local-store publication.

Reproduce with the enabled live environment and:

```text
sbt "examples / runMain com.weisgnosis.agents.examples.LiveContextEvidence"
```

## E6: Active HTTP boundary attempts

Last verified on 2026-10-11; not rerun in the packaged refresh.

Both selected models passed three active-request cancellation attempts and the
separate definite-rejection failover. Each successful cancellation used one
request, a received parsed chunk, held joined cleanup, released request/boundary
scopes, zero local execution, Interrupted state and one recovery without a
completed-model event. Earlier unsuccessful attempts have no established narrow
cause and are not retroactively accepted.

All six accepted live frames contained no partial tool calls. Argument sanitizing
and reconciliation for incomplete calls therefore remain separately demonstrated
by the actual loopback HTTP fixture, not those vacuous live predicates. Local
cleanup does not establish that remote inference stopped. Reproduce through
[LiveRequestBoundary](../modules/examples/src/main/scala/com/weisgnosis/agents/examples/LiveRequestBoundary.scala)
with the enabled environment.

## Structured-output profiles not refreshed

The separate 2026-10-11 fifteen-cell campaign at `81c5d46` recorded 12 Passed
and 3 Failed. Native structured output failed on both selected models; synthetic
structured output failed on LFM. Those failures are not requalified by the current
stream/tool/coordination checks. The focused instruction-LFM native profile and
Qwen synthetic profile have their separate documented bounds in the
[profile guide](../modules/examples/README.md#selected-structured-output-and-cancellation-profiles).
Native/synthetic profile execution is not part of this fresh twenty-outcome set.

## Remaining evidence and claim limits

The packaged refresh verifies current streaming, selected local tools, scoped
coordination and tool interruption/continuation on two models. It does not measure
saturated diagnostic buffers, long-term memory, durable restart or all provider
features. Diagnostic overflow has separate public offline regression evidence.

The preceding code verification passed 1,805 executed tests with zero failures
and compiler warnings, plus five-module packaging and fresh external consumption.
Its detailed log contains 28 unconfigured skips: 27 PostgreSQL scenarios and one
native-directory-alias scenario. Those skips are not backend/platform evidence.
No source changed for this live refresh, so the runtime suite was not repeated
solely to record receipts or update prose.

The gateway uses the OpenAI-compatible protocol; native Anthropic/Gemini,
hosted tools and realtime WebSockets were not tested here. No Python AgentScope
comparison was rerun, and no language superiority or inference speedup is claimed.


## Native Responses live profiles on 2026-10-18

The published `0.1.1-SNAPSHOT` build 6 binaries from implementation commit `156d9a4`
were consumed by a separately compiled neutral-package host. The configured gateway exposed
an actual Responses SSE endpoint; endpoint and credential values are excluded from this report.
Catalog discovery confirmed both selected models were loaded before requests. No adapter or
runtime source changes were needed for the successful profiles.

| Model | Local tool and continuation | JSON checkpoint restore and resume |
| --- | --- | --- |
| `qwen3.8-27b` | Passed | Passed |
| `qwen3.8-27b-uncensored-mlx` | Passed | Passed |

All four receipts report Completed, one successful `live_sum(2, 3)` call/result pair,
a final reply of `5`, native replay metadata and positive output usage. In each checkpoint
profile the host checked the model's exact proposal before supplying the result; decoded
snapshot equality was true, and a new agent continued the saved external-tool occurrence.
Both processes exited zero. An independent source review checked these assertions and the
public API boundary. The [runner and exact command](../verification/README.md#selected-live-native-responses-profiles)
are retained for reproduction with host-supplied configuration.

This verifies the selected local-function and checkpoint-handoff paths against two actual models.
Native replay metadata does not establish that encrypted reasoning was supplied. In-flight
HTTP cancellation, native structured formatting, hosted tools, multimodal data and other
models were not tested by these profiles. No Python comparison or performance ranking is
inferred. Library source was unchanged; the host runner was compiled with fatal warnings.

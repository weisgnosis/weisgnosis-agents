# Real-model E2E scenarios and publication evidence

## Purpose and current status

Exercise useful agent tasks against `https://bots.weisgnosis.ru/api/v1` and measure
observable runtime guarantees. This is a scenario design, not a report of new live
runs. Existing executable coverage is identified below; proposed extensions are
not executable commands yet. Delivery status belongs in the
[single tracker](architecture/tracker.md), not a second checklist here.

The gateway uses one machine. Model loading and inference may serialize there.
Local tool concurrency, concurrent agent requests and simultaneous model inference
are different measurements. ZIO fibers do not establish CPU scaling or faster
inference by themselves.

## Execution protocol

1. Check `/healthz` and `/api/tunnel`, then fetch authenticated `/api/v1/models`.
   Select current chat-capable identifiers and record their advertised status.
   Never select embeddings, reranking, decisions or image generation as chat models.
2. Start with one loaded chat model. Add one catalog-selected instruction model
   and, separately, a reasoning model. Run mixed roles in both directions only
   when each model supports the required tool/format profile. Do not hardcode the
   previously observed identifiers as current gateway capabilities.
3. Use process-local credentials and the full Chat Completions URL. Use one sbt
   process and at most two simultaneous model requests. Sequentially schedule
   scenarios; parallel work is confined to the selected scenario.
4. Initially use three model iterations per agent, 4,096 output tokens per request,
   a five-minute agent budget and bounded response/tool bytes. Reasoning consumes
   that output budget too. Assign each new multi-agent scenario a finite aggregate
   deadline and request ceiling; joined cleanup may extend the deadline.
5. Retry safe discovery only within a fixed limit. Do not replay admitted model
   turns, writes or ambiguous tool operations. Record offline/unavailable outcomes;
   do not keep retrying until a failure disappears.

Use the existing environment contract after installing the key through the host's
secret mechanism. Do not paste the key into a command, source, report or recording:

```powershell
$env:WEISGNOSIS_LIVE_SMOKE = 'enabled'
$env:WEISGNOSIS_LIVE_ENDPOINT = 'https://bots.weisgnosis.ru/api/v1/chat/completions'
$env:WEISGNOSIS_LIVE_MODEL = '<primary identifier from the current catalog>'
$env:WEISGNOSIS_LIVE_PEER_MODEL = '<distinct peer identifier from the current catalog>'
$env:WEISGNOSIS_LIVE_MAX_TOKENS = '4096'
$env:WEISGNOSIS_LIVE_TIMEOUT_SECONDS = '300'
& 'C:\tools\sbt\bin\sbt.bat' 'examples / runMain com.weisgnosis.agents.examples.LiveMultiModel'
```

`WEISGNOSIS_LIVE_API_KEY` must already be present when bearer authentication is
required. These requests consume gateway capacity and may incur provider charges.

The existing [LiveMultiModel](../modules/examples/src/main/scala/com/weisgnosis/agents/examples/LiveMultiModel.scala)
emits per-scenario JSON with Passed, Failed, Unsupported, Unavailable or NotRun.
Its current discovery checks `/healthz` and the catalog, not `/api/tunnel`.
It runs selected scenarios sequentially and has no whole-matrix deadline.
Keep new orchestration limits explicit rather than attributing them to this runner.
An sbt exit code alone is insufficient: require the expected scenario receipt and
its assertions. Disabled execution, Unsupported and NotRun are not passes.

## Shared useful task

Produce a release-readiness report from a disposable, host-owned evidence service:

| Evidence | Fixed input | Authoritative expected result |
| --- | --- | --- |
| BUILD-1 | Build passed | Build accepted |
| TEST-1 | Tests passed | Tests accepted |
| SEC-1 | Security failed, one high-severity finding | Release blocked |

The model must read all three records, cite their identifiers and propose a report
with `releaseAllowed=false`, `blockingEvidence=["SEC-1"]` and ordered
`evidenceIds=["BUILD-1","TEST-1","SEC-1"]`. Accept exactly these three fields.
Validate their semantic values first; the host then serializes the accepted report
to the following compact UTF-8 JSON with one final LF and no BOM:

```json
{"releaseAllowed":false,"blockingEvidence":["SEC-1"],"evidenceIds":["BUILD-1","TEST-1","SEC-1"]}
```

The host computes the expected answer independently, validates the schema and
checks the actual stored report. A persuasive answer, a reviewer agent's opinion or the word "done" cannot
authorize publication. The report is a synthetic fixture, not a real release.

Expose only bounded evidence reads and a write to one owned report path. No real
repository, shell, service credentials or model-selected filesystem paths are
needed. The same dataset supports local tools and a real loopback HTTP MCP server;
model calls still go to bots. This isolates transport/runtime correctness from
third-party business services without claiming a deployed-service integration.

## Scenarios

### E0: Existing live baseline

**Executable now:** LiveMultiModel exercises streamed local-tool continuation,
exact disposable-file write/read, native and synthetic structured output, joined
single-tool cancellation, persistent same/mixed-model MsgHub, an independently
selected child and definite first-target HTTP rejection/fallback.

Use its existing oracles and report every cell. The documented earlier 11/14 result
is historical selected-model evidence, not this campaign's result. The existing
MsgHub task checks attribution and delivery; it does not validate a substantive
team decision. The existing cancellation holds a local tool, not an HTTP stream.

### E1: Useful MCP task and authorization

**Executable:** [LiveMcpEvidence](../modules/examples/src/main/scala/com/weisgnosis/agents/examples/LiveMcpEvidence.scala).
Real model → installed HTTP MCP evidence tools → runtime observations → checked
report proposal → host-authorized report write. The service retains exact bytes
in a disposable ledger; filesystem persistence is not part of this scenario.

Pass requires all expected evidence receipts, exact report fields/bytes and one
authorized write. Record the actual discovered tool schemas and narrowed host
selectors; fixture text suggesting "ignore the approval policy" grants no power.
Reject model attempts to replace the host's report path or evidence namespace.

Run the same proposal under Ask, Deny and Plan mode. While awaiting Ask, the write
ledger must remain empty. Restore the captured acting checkpoint under current
host authorization and supply ConfirmResult: execute the approved write once,
without another pre-acting model request or replaying completed reads. Deny and
Plan must leave the write ledger empty. Test host state, not whether the model
voluntarily obeys its prompt. Relevant contracts: INV-1, INV-7, INV-9 and INV-10.

### E2: Parallel tool execution

**Executable:** [LiveParallelTools](../modules/examples/src/main/scala/com/weisgnosis/agents/examples/LiveParallelTools.scala).
Ask the live model to issue independent evidence reads in
one assistant batch. Mark fixture reads concurrency-safe and select
ToolExecution.Parallel. Configuration alone does not grant concurrency safety.

Two read tools signal entry and await a host-controlled release. Before releasing
either, require both entry signals under a bounded watchdog, active count two and
the assistant calls already present in canonical history. Release both and require
one matching result per call occurrence, the exact `{"sum":37}` answer from the
fixed observations 17 and 20, and zero
active tools after joined completion. This proves overlap and INV-1 without
using a guessed time threshold.

Use the same live task in Sequential mode as a control, releasing each tool after
its own entry and requiring peak active count one. Do not reuse the two-entry
release barrier: it would deadlock sequential execution. If the model never emits
one multi-call batch, report that parallel execution was not exercised; do not
invent a speedup or label it a runtime scheduling failure. The selected profile
fails its required-batch task in that case; its concurrency oracle was not
exercised. This executable measures peak local activity, not latency or speedup.

### E3: Cancel a partially completed batch and reuse the agent

**Both interruption arms executed on selected live paths.**
[LiveRuntimeComparison](../modules/examples/src/main/scala/com/weisgnosis/agents/examples/LiveRuntimeComparison.scala)
passes the parent-fiber arm on Qwen in three actual attempts, including repeated
interruption and same-runtime continuation; the selected LFM task fails full
acceptance. [Receipts and limits](live-runtime-comparison.md) retain every attempt.
The separate `cooperative-cancel` arm passed once on Qwen and once on LFM
reasoning, with all lifecycle/continuation assertions true; see
[current receipts](live-e2e-results.md#e3-cooperative-interruption-and-same-runtime-continuation).
One tool completes with a known payload; another enters a host-owned wait. Wait until the first result
is committed to canonical history or its terminal event is observed, then cancel.
A tool body returning does not establish persisted completion.

Hold the second tool's finalizer and prove cancel/join is still pending. Release it;
then require zero active children, the first result unchanged, one Error result
only for the unfinished call occurrence, complete call/result pairs and exactly
one recovery message with the required interruption metadata. No successful write
may occur after joined cancellation. Relevant contracts: INV-1 and INV-2. This
does not exercise interruption during partial streamed argument assembly.

Run cooperative agent interruption and parent fiber interruption as separate
arms: explicitly signal `runtime.interrupt` and join the original run in the former,
which yields the runtime's interrupted outcome; the latter preserves
an interrupted Exit rather than a successful observation. Continue the same agent
after cleanup with a new user turn. Require completion without rerunning previous
tools or duplicating the recovery message. Provider refusal and a stuck runtime
execution permit are different failures.

### E4: Useful multi-agent coordination

**Executable:** `LiveCoordinationEvidence` reuses persistent agents and the existing coordination APIs.
An analyst reads evidence, a checker receives attributed peer findings, and a
reporter produces the exact blocked-release decision. Use two models with explicit
roles and also a same-model control. Machine-check task correctness separately
from MsgHub delivery and each agent's private history.
The current profile uses `submit_report` for each model's proposed decision;
schema, exact semantics and previously observed evidence must pass before
Direct completion. Earlier ordinary-text attempts remain separate results.
The current executable advertises one phase-selected function with `ToolChoice.Required`
and temperature zero. Report arguments remain model proposals subject to runtime
validation. This profile does not demonstrate autonomous operation selection.

Hold one recipient in a tool while another publishes: require a Queued receipt
with no extra recipient model call. Release/join its turn, explicitly flush between
rounds, then require the attributed observation once and an empty pending queue.
Install `ModelInputTransform.peerAware` on receiving agents. Peer tool calls/results
must appear as inert peer evidence in the receiving model request, not executable
foreign tool history or System authority.

Exercise Pipeline sequential handoff and fanout with parallelism two. Fanout must
bound active runs and retain stage order; interruption must join owned children.
Sequential processing must stop on a suspended/interrupted stage instead of
calling its successor. This is scoped coordination, not durable workflow replay.

### E5: Large evidence and bounded context

**Executable:** `LiveContextEvidence` reuses HostAssembly, result eviction and compaction. Return
more than 80,000 characters containing a known fact in the middle, outside the
2,000-character previews. Require exact stored bytes, a valid retrieval key/path
and the shortened observation in the next actual model request. The model must
use the bounded retrieval tool to recover the middle fact; a preview-only guessed
answer fails. Inject a persistence failure: retain the original observation.

Trigger compaction explicitly with a bounded prepared history, including a prior
summary and paired tool occurrences. Require one merged summary, unbroken
call/result occurrence pairs, retained user provenance and a correct answer to a
known earlier fact. Score summary/model recall separately from structural runtime
guarantees. Repeated prompting alone does not prove compaction. INV-4/5/6 apply.

### E6: Provider failure and request cancellation

**Executable:** the existing definite HTTP rejection/fallback scenario
uses an owned loopback first target and a real selected bots fallback. Require no
first-target tool effect and one successful continuation. It proves a specific
safe rejection path, not retry safety after an ambiguous response.

**Executable:** `LiveRequestBoundary` cancels an active response through an
instrumented borrowing HTTP driver. Synchronize on receipt of a real stream chunk,
not a timer. Verify local request-scope release, consumer/child termination and no
later tool dispatch. Remote generation may continue: client cleanup cannot prove
that the bots backend stopped inference. A malformed/truncated response must not
authorize incomplete tools; token exhaustion must remain an explicit Stopped turn.

## Model selection and measurements

Run E0 first. Select a working tool-calling model for E1–E6; use native structured
output only on an observed compatible profile, otherwise explicitly select the
supported synthetic format. Do not silently substitute models/formats after a
failed cell. Add a reasoning model with an adequate output budget, then reverse
the analyst/checker selections. The machine's limits prohibit a throughput stress
test disguised as compatibility testing.

Repeat each selected required scenario three times, preserving every result and
attempt. Three runs provide reproducibility checks, not a reliable p95 estimate.
Collect scenario/model identities, fixture version and source revision, terminal
status, assertion outcomes, model requests, tool entries/completions/cancellations,
peak active counts, event ordering, latency boundaries and joined-cleanup duration.
Record provider-reported usage/cache fields only when supplied; missing usage is
unknown, not zero. Never infer token totals from text length or subscription limits.

Keep receipts and bounded sanitized fixture artifacts under ignored `target/`.
Exclude credentials, authorization headers, raw reasoning and unrelated host data.
Failure receipts must distinguish runtime assertion failure, model task/profile
failure and endpoint unavailability. Timeouts are observations, not passes.

## Implementation order and acceptance

Reuse existing applications/fixtures rather than creating a new test framework:
first establish a fresh E0 matrix, then add the shared E1 task, E2/E3 concurrency
controls, E4 coordination and E5/E6 boundary checks. Each source slice needs focused
offline tests of its oracle, actual selected live execution, independent FP/ZIO and
invariant review, and the repository's required verification. No new dependency,
generic benchmark service or automatic release pipeline is implied.

For publication, publish only claims supported by saved successful scenarios.
Required safety assertions must pass in all selected repetitions; unsupported or
unavailable cells remain visible. If the model cannot solve the useful task, that
profile cannot support a usefulness claim despite passing lifecycle checks.

| Evidence | Defensible wording | Unsupported extrapolation |
| --- | --- | --- |
| E1 | Produces a checked report through installed MCP tools; host authorization controls writes | Every external service works; model output authorizes a release |
| E2 | Selected independent tools overlap under explicit concurrency policy | Faster inference or unlimited CPU/agent scaling |
| E3 | Cancellation joins owned cleanup and preserves valid tool history on the tested path | No possible leaks; remote inference is always cancelled |
| E4 | Scoped agents exchange attributed evidence and complete the selected task | Durable/distributed coordination or guaranteed better answers |
| E5 | Selected large results are retrievable and compaction preserves structural contracts | Unlimited memory or perfect semantic recall |
| E6 | Selected failure/cancellation paths preserve local execution boundaries | All failures can safely retry or every provider behaves identically |

A Python comparison is a separate matched experiment. Pin a named implementation,
versions and code; use the same models, prompts, fixtures, budgets and fault schedule
with equivalent cancellation/ownership controls. Run both on the same host and
report correctness, cleanup and overhead separately from inference time. Python
can implement structured cancellation too. Without an executed comparator, describe
the demonstrated Scala/ZIO contracts and do not claim another language generally
fails them or that this library is "mathematically flawless."

# Selected real-model E2E results

These are actual bots gateway runs on 2026-10-11. They exercise the current
library through its public streaming, tool, coordination and interruption APIs.
The [scenario design](live-e2e-scenarios.md) and [tracker](architecture/tracker.md)
separate executed contracts from unverified model/provider profiles.

## Execution conditions and reproduction

The authenticated catalog selected `qwen3.8-27b` and `lfm2.5-2.6b-mlx` by their
chat capability and availability. Requests used
`https://bots.weisgnosis.ru/api/v1/chat/completions`, process-local credentials,
4,096 output tokens. The original matrix used a 300-second per-agent budget
and one-MiB response limit; E4/E6 also use one MiB. E5 retains HostAssembly's
one-minute per-turn deadline, a 300-second aggregate budget and the default
eight-MiB transport response limit. Joined cleanup can extend a deadline. Scenarios ran sequentially;
admitted model turns and tools were not retried to obtain a passing receipt.

The fifteen-scenario matrix used
[LiveMultiModel](../modules/examples/src/main/scala/com/weisgnosis/agents/examples/LiveMultiModel.scala)
with the library baseline `81c5d46`. Select the primary and peer through
`WEISGNOSIS_LIVE_MODEL` and `WEISGNOSIS_LIVE_PEER_MODEL`, using the documented
[enabled live environment](../modules/examples/README.md#live-endpoint-verification):

```text
sbt "examples / runMain com.weisgnosis.agents.examples.LiveMultiModel"
```

Every scenario emitted a receipt: **12 Passed and 3 Failed**, with no Unsupported,
Unavailable or NotRun cells. The process returned zero even with failed profiles;
acceptance uses the individual assertions, never process exit alone.

## Single-agent profiles

Each cell below is one selected attempt, not a repeated reliability measurement.

| Profile | Qwen 27B | LFM reasoning 2.6B |
| --- | --- | --- |
| Streamed text and local-tool continuation | Passed | Passed |
| Exact disposable-file write/read and cleanup | Passed | Passed |
| Native structured `{"value":7}` | Failed | Failed |
| Synthetic structured `{"value":7}` | Passed | Failed |
| Joined local-tool cancellation and recovery | Passed | Passed |

Failed profiles remain Failed. Their sanitized receipts do not capture the
underlying cause, so they do not distinguish model instruction following, native
formatting, gateway behavior or runtime behavior. These native/synthetic
structured profiles are not accepted for the failed model selections. Synthetic
success does not establish native structured-format compatibility.

## Focused structured-profile diagnostic rerun

After adding fixed typed failure categories, each previously failed profile was
executed once again with the same selected models, 4,096-token output budget and
300-second run deadline. These are separate attempts; the original matrix above
remains 12 Passed/3 Failed.

| Selected profile | New attempt | Diagnostic |
| --- | --- | --- |
| Qwen native structured | Failed | deserialization |
| LFM reasoning native structured | Failed | deserialization |
| LFM reasoning synthetic structured | Passed | Public assertions passed |

Use the existing `LiveProviderProfiles` command with
`WEISGNOSIS_LIVE_PROFILE=native` or `synthetic`. Inspect its JSON status rather
than process exit. The category does not distinguish malformed provider output,
JSON/schema validation or wire decoding. No raw responses or error messages are
retained. The LFM synthetic outcome differs across selected attempts; one pass
does not establish repeatable reliability or explain the earlier failure.

## Collaboration and controlled failure

| Profile | Selection | Result |
| --- | --- | --- |
| Persistent same-model MsgHub | Qwen/Qwen | Passed |
| Persistent mixed-model MsgHub | Qwen/LFM reasoning | Passed |
| Owned parent/tool/child/parent chain | Qwen parent, LFM reasoning child | Passed |
| Definite HTTP rejection and fallback | Owned loopback HTTP 503, then Qwen | Passed |
| Paired Sequential/Parallel local evidence task | Qwen | Passed |

Hub assertions check sender attribution, private canonical histories and delivery
across successive turns. The child profile checks actual owned child dispatch and
its exact parent observation. The rejection fixture uses a local server before a
real model request; it does not induce a failure on the public gateway.

The paired task checks persisted calls, exact successful observations, complete
history, zero active tools and joined releases. Sequential execution requires
peak activity one; Parallel requires peak activity two and exact sum 37. This
establishes overlapping local tool execution, not faster model inference.

## E1: HTTP MCP evidence and restored authorization

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
The original fifteen-cell matrix is unchanged. Full E1 acceptance is specific to
the LFM attempt; it is not model-wide certification.

## E3: Cooperative interruption and same-runtime continuation

Run the current
[LiveRuntimeComparison](../modules/examples/src/main/scala/com/weisgnosis/agents/examples/LiveRuntimeComparison.scala)
with each explicitly selected model:

```text
sbt "examples / runMain com.weisgnosis.agents.examples.LiveRuntimeComparison cooperative-cancel"
```

Both selected attempts emitted `scenarioAccepted=true` and `state=Interrupted`.
They require all of these receipt fields to be true:
`callsPersistedBeforeEntry`, `completedResultPreserved`,
`unfinishedOnlyReconciled`, `cancellationJoinedCleanup`,
`interruptionPreserved`, `recoveryExactlyOnce`, `historyComplete` and
`sameRuntimeContinuation`. Each recorded peak activity two and exactly one
execution of each evidence tool. `expectedReply=false` is intentional: this
first turn is interrupted, not a completed sum answer.

| Selection | E3 result | Cancel/join ms | Continuation ms |
| --- | --- | ---: | ---: |
| Qwen 27B | Passed | 31.1084 | 4536.4528 |
| LFM reasoning 2.6B | Passed | 25.4736 | 11891.4648 |

The fixture waits for committed left result 17 and an active right tool before
signaling `runtime.interrupt`. While right cleanup is held, cancellation cannot
return. After release, the completed result survives, only the unfinished call
receives Error, and one exact recovery assistant is retained. The same agent
then completes a READY turn without changing prior tool results, recovery or
execution counts. Timing describes these controlled local waits and selected
provider runs; it is not a throughput or language benchmark.

Cooperative interruption requires successful stream delivery of the runtime's
Interrupted result. The separately verified parent-fiber scenario requires an
interrupted Exit and repeated cancellation; its
[existing framework comparison](live-runtime-comparison.md) remains distinct.

## E4: Useful room and Pipeline evidence

The final `LiveCoordinationEvidence` profile passed three separate executions
of each scenario, twelve successful receipts in total:

| Scenario | Roles | Successful attempts | Requests per attempt |
| --- | --- | ---: | ---: |
| Useful room | Qwen analyst/reporter, Qwen checker | 3 | 5 |
| Useful room | Qwen analyst/reporter, reasoning-LFM checker | 3 | 5 |
| Useful room | Reasoning-LFM analyst/reporter, Qwen checker | 3 | 5 |
| Pipeline controls | Qwen | 3 | 10 |

Each room reads evidence once and accepts one model-produced `submit_report`
proposal per analyst, checker and reporter. The exact report must identify the
failed security record and preserve all observed evidence IDs in order. A busy
checker queues attributed evidence without another reasoning request; explicit
flush delivers it exactly once. Captured receiver requests keep foreign tool
calls/results inert. Every accepted receipt requires all these assertions.

The Pipeline performs useful sequential evidence/report transfer, then verifies
fanout with at most two active stages and input-ordered results despite forced
out-of-order completion. Cancellation waits for held cleanup; suspended or
interrupted first stages never start their successor. Both sequential roles
submit one validated report. A failed useful task stops before lifecycle controls.

This is an explicitly host-selected tool profile: each request advertises only
the current phase's function, uses `ToolChoice.Required` and temperature zero.
A source reads once before submitting; report admission checks prior successful
read evidence. The checker wait phase uses exact host input, not quoted peer
text. The model still supplies the report values; schemas, prerequisites and
runtime authorization remain authoritative. This proves the selected workflow,
not autonomous tool selection or provider enforcement of `tool_choice`.

Earlier profiles remain distinct evidence:

| Earlier profile | Same Qwen room | Qwen analyst/LFM checker | LFM analyst/Qwen checker | Qwen Pipeline |
| --- | --- | --- | --- | --- |
| Ordinary text, first attempt | Passed | Failed assertion | Failed assertion | Failed assertion |
| Ordinary text, second attempt | Passed | Unavailable: HTTP 503 | Failed analyst decision | Failed useful sequential task |
| Unsteered typed report | Failed analyst decision | Passed | Failed analyst decision | Timeout, stage unknown |
| Host-selected `Specific` object | Failed: HTTP 400 | Failed: HTTP 400 | Failed: HTTP 400 | Failed: HTTP 400 |
| Single function with `Required`, before field semantics were clarified | Passed | Passed | Failed analyst report | Failed sequential reports |

The second ordinary-text Pipeline passed its four lifecycle controls but failed
useful collaboration. A separate instruction-LFM 1.2B selection failed both
rooms where it was analyst; Qwen analyst/instruction-LFM checker exceeded the
request budget. Its Pipeline timed out in Fanout with six client invocations and
zero accepted sequential submissions. It is not reasoning-LFM acceptance.

A process-private wire probe confirmed rejection of object-valued `tool_choice`;
`required` received HTTP 200 headers, without completing an agent turn. Final
instructions define `releaseAllowed` from all record states, `blockingEvidence`
from every failed record, and `evidenceIds` from every observed record, without
supplying the answer values. Prior failed attempts are not retroactively accepted.
Failed diagnostic invocation counts can include calls blocked before dispatch;
successful receipts require exact delegated requests.

Reproduce with the enabled live environment and:

```text
sbt "examples / runMain com.weisgnosis.agents.examples.LiveCoordinationEvidence"
```

## E5: Oversized retrieval and explicit compaction

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

The first Qwen attempt failed with an aggregate timeout; the phase causing that
timeout was not captured. The subsequent reasoning-LFM attempt failed with
HTTP 400. Neither verifies active-response cancellation. The successful-response
fixture deliberately withholds later SSE frames; non-success responses retain
their original status and body.

Both selections passed the separate definite HTTP 503-to-real-model failover
scenario. Offline malformed, missing-terminal-marker and token-exhausted
controls passed with one request, released request scope and no local tool
execution. Offline controls are not live provider failures.

After explicit empty-object tool schema and phase diagnostics were installed,
both Qwen and reasoning-LFM passed three separate active-boundary attempts. Each used
one request and reported parsed chunk receipt, pending consumer during held
cleanup, released request/boundary scopes, zero tool execution, Interrupted state,
one recovery message and no completed-model event. Both also passed the separate
definite-rejection failover again. This does not identify the earlier failures'
causes or retroactively accept them.

All six successful live frames contained zero partial tool calls. Their sanitizer
and reconciliation predicates are vacuous for that frame; incomplete arguments
are exercised separately through the actual loopback HTTP fixture. Local request
cleanup does not establish remote inference cancellation.

## Remaining evidence and claim limits

E4 has three successful attempts per selected room/Pipeline profile; E5 and E6
have three successful attempts per selected model. Formatting, full verification
(1,807 passed, zero failed, one separately excluded native alias test), actual
PostgreSQL, five-module packaging, archive admission and clean external packaged
consumption pass with zero compiler warnings.
Local request cancellation does not prove that remote inference stops.

The gateway is OpenAI-compatible. It does not verify native Anthropic or Gemini
wire protocols, hosted search/execution, realtime WebSockets or every model
profile. No Python comparison was rerun here; universal compatibility, language
superiority and inference speedup are not supported claims.

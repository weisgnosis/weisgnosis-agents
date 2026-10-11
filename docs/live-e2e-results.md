# Selected real-model E2E results

These are actual bots gateway runs on 2026-10-11. They exercise the current
library through its public streaming, tool, coordination and interruption APIs.
The [scenario design](live-e2e-scenarios.md) and [tracker](architecture/tracker.md)
separate executed contracts from remaining live extensions.

## Execution conditions and reproduction

The authenticated catalog selected `qwen3.8-27b` and `lfm2.5-2.6b-mlx` by their
chat capability and availability. Requests used
`https://bots.weisgnosis.ru/api/v1/chat/completions`, process-local credentials,
4,096 output tokens and a 300-second per-agent budget. Responses are bounded to
one MiB. Joined cleanup can extend a deadline. Scenarios ran sequentially;
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

## Remaining evidence and claim limits

Remaining live extensions are busy-recipient queue flushing, useful ordered
Pipeline collaboration, oversized-output retrieval/compaction and cancellation
during an active HTTP response. Those remain unchecked in the
tracker. Local tool cancellation does not prove that remote inference stops.

The gateway is OpenAI-compatible. It does not verify native Anthropic or Gemini
wire protocols, hosted search/execution, realtime WebSockets or every model
profile. No Python comparison was rerun here; universal compatibility, language
superiority and inference speedup are not supported claims.

# Selected real-model E2E results

These are actual gateway runs on 2026-10-11, not offline tests or a Python
benchmark. The [scenario design](live-e2e-scenarios.md) describes the broader
campaign; passing this baseline does not complete its proposed extensions.

## Execution conditions

The authenticated bots gateway catalog selected `qwen3.8-27b`,
`lfm2.5-1.2b-instruct-mlx` and `lfm2.5-2.6b-mlx`. All requests used
`https://bots.weisgnosis.ru/api/v1/chat/completions`, process-local credentials,
4,096 output tokens and a 180-second per-agent budget. Joined cleanup can extend
that deadline. The executable was
[LiveMultiModel](../modules/examples/src/main/scala/com/weisgnosis/agents/examples/LiveMultiModel.scala)
at source revision `6b8f395`. Scenarios ran sequentially, without retrying an
admitted model turn or tool.

Run with the documented enabled environment:

```text
sbt "examples / runMain com.weisgnosis.agents.examples.LiveMultiModel"
```

Every selected attempt emitted all fourteen scenario receipts. All four sbt
process invocations returned zero, including invocations containing failed
profiles. Acceptance therefore uses individual receipts, not process exit.

## Repeated baseline

Three repetitions selected Qwen as primary and the 1.2B instruction model as
peer. Each produced eleven Passed and three Failed receipts: **33 of 42 passed**.
Failures were retained; no selected cell was unavailable, unsupported or skipped.

| Model profile | Qwen 27B | LFM instruction 1.2B |
| --- | --- | --- |
| Streamed text and local-tool continuation | Passed 3/3 | Failed 3/3 |
| Exact disposable-file write/read and cleanup | Passed 3/3 | Failed 3/3 |
| Native structured `{"value":7}` | Failed 3/3 | Passed 3/3 |
| Synthetic structured `{"value":7}` | Passed 3/3 | Passed 3/3 |
| Joined local-tool cancellation and recovery | Passed 3/3 | Passed 3/3 |

| Collaboration/failure profile | Selection | Result |
| --- | --- | --- |
| Persistent same-model MsgHub | Qwen/Qwen | Passed 3/3 |
| Persistent mixed-model MsgHub | Qwen/LFM instruction | Passed 3/3 |
| Owned parent/tool/child/parent chain | Qwen parent, LFM instruction child | Passed 3/3 |
| Definite HTTP rejection and fallback | Owned loopback HTTP 503, then Qwen | Passed 3/3 |

## Exploratory reasoning-model selection

One additional matrix selected LFM reasoning 2.6B as primary and Qwen as peer:
**12 of 14 passed**. This is one attempt, not a repeated reliability measurement.

| Model profile | LFM reasoning 2.6B | Qwen 27B |
| --- | --- | --- |
| Streamed text and local-tool continuation | Passed | Passed |
| Exact disposable-file write/read and cleanup | Passed | Passed |
| Native structured `{"value":7}` | Failed | Failed |
| Synthetic structured `{"value":7}` | Passed | Passed |
| Joined local-tool cancellation and recovery | Passed | Passed |

Same-model LFM reasoning hub, mixed LFM reasoning/Qwen hub, LFM reasoning
parent/Qwen child and definite rejection followed by LFM reasoning all passed.
Across the four matrices, **45 of 56 scenario attempts passed and 11 failed**.
These heterogeneous scenario counts are not a project-completion percentage.

## Local-tool concurrency control

The separately executed
[LiveParallelTools](../modules/examples/src/main/scala/com/weisgnosis/agents/examples/LiveParallelTools.scala)
adds one paired Sequential/Parallel task after the frozen baseline. Each of three
selected models was attempted once with the same token/run budgets:

| Selection | Paired profile | Accepted measurements |
| --- | --- | --- |
| Qwen 27B | Passed | Sequential peak 1; Parallel peak 2; exact sum 37 |
| LFM reasoning 2.6B | Failed | No accepted paired result |
| LFM instruction 1.2B | Failed | No accepted paired result |

Qwen emitted the explicit success message after both modes checked persisted
calls, exact successful observations, complete history, zero active tools and
joined local releases. Parallel tools cannot be released until both have entered;
the sequential control releases each tool in model declaration order. This
establishes actual local overlap on the selected live path without timing guesses.
The LFM profiles did not satisfy all final assertions; these aggregate failures do
not identify which assertion failed and are not accepted concurrency evidence.
These three paired profiles are separate from the fourteen-cell baseline and
have not been repeated. Current LiveMultiModel adds the new profile as a
fifteenth receipt; the frozen baseline results above remain fourteen-cell runs.

## What the checks establish

- Selected real models requested tools, consumed observations and completed the
  checked task through the library's actual streaming and ReAct path.
- Cancellation profiles joined deliberately held local-tool cleanup, preserved
  complete call/result history and generated one recovery message.
- Hub profiles checked sender attribution, private canonical histories and
  delivery across successive turns. The child profile checked an actual owned
  child dispatch and its exact parent observation.
- Failover profiles rejected one owned local HTTP request before using the real
  selected model; the rejected request executed no tool.

Failed profiles remain Failed. Their sanitized receipt reports a scenario
failure without a diagnostic cause; it does not establish whether the cause was
model instruction following, native formatting, gateway behavior or runtime
behavior. Synthetic success is not evidence that native structured output works.

## Remaining evidence and claim limits

The baseline does not establish simultaneous tool entry; the separate Qwen
profile above supplies that evidence. Neither establishes partial-completion
cancellation with same-runtime continuation, busy-recipient queue flushing,
useful Pipeline collaboration, permission checkpoint restoration, large-output
retrieval/compaction or cancellation during an active HTTP response. Those
extensions remain unchecked in the [tracker](architecture/tracker.md).

No matched Python implementation or benchmark was run. Comparative superiority,
speedup and universal provider compatibility are not supported claims. Process
timings include sbt/JVM startup and model loading, so they are not inference
latency measurements. Token usage, cache hits and cost were not measured.
Gateway checks do not verify native Anthropic or Gemini endpoints. Credentials
and raw request/response logs are excluded from public documentation.

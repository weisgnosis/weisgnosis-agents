# Live AgentScope and WeisGnosis comparison

This campaign executes actual Python AgentScope 2.0.9 and the public WeisGnosis
runtime against the same bots gateway. It measures useful task completion and
selected cancellation contracts, as well as elapsed time. A quicker failed task
is not a successful speed result.

## Executed results

On 2026-10-11, two catalog-selected models, four scenarios, two frameworks and
three repetitions produced **48 receipts**. Framework order alternated Scala/
Python, Python/Scala, Scala/Python in successive rounds. Requests were serial;
the fixtures introduced concurrency only inside the selected tool task.
Every attempt is retained in [machine-readable results](live-runtime-comparison-results.jsonl).
All processes exited zero, but only **23 scenario contracts passed**: 14 Scala
and 9 Python. Process success does not certify a task or the whole library.

| Model/task | Scala accepted | Python accepted | Scala median successful turn, ms | Python median successful turn, ms |
| --- | --- | --- | --- | --- |
| Qwen 27B / text | 3/3 | 3/3 | 4,674 | 2,608 |
| Qwen 27B / sequential tools | 3/3 | 3/3 | 10,432 | 7,482 |
| Qwen 27B / parallel tools | 2/3 | 2/3 | 22,538 | 11,888 |
| Qwen 27B / repeated cancellation + continuation | 3/3 | 0/3 | 16,859 | No accepted task |
| LFM instruction 1.2B / text | 3/3 | 1/3 | 5,702 | 1,298 (one success) |
| LFM instruction 1.2B / sequential tools | 0/3 | 0/3 | No accepted task | No accepted task |
| LFM instruction 1.2B / parallel tools | 0/3 | 0/3 | No accepted task | No accepted task |
| LFM instruction 1.2B / repeated cancellation + continuation | 0/3 | 0/3 | No accepted task | No accepted task |

Medians use successful tasks only; failed attempts and their timing remain in
the data. Parallel Qwen success samples contain different repetitions: the
first Scala attempt failed after two tool entries, while the second Python
attempt failed with `APIConnectionError` before tool entry. Only repetition
three is a mutually successful parallel pair. These small samples do not
support significance or a general speed ratio.

**No Scala end-to-end speed advantage was demonstrated.** Python has lower
successful-turn medians in the ordinary sampled tasks. Qwen text times range
from 3,582 to 6,333 ms for Scala and 1,936 to 46,274 ms for Python, illustrating
the variability hidden by a single number. The cancellation continuation is
separate: Qwen returned `READY` in all three arms on each framework, with
medians 8,907 ms Scala and 16,021 ms Python. That observation is not a warmed
throughput claim; histories differ through their recovery policy.

The local scheduling measurement supports **parity**, rather than an exclusive
Scala advantage:

| Model/tool phase | Scala median, ms | Python median, ms |
| --- | --- | --- |
| Qwen sequential | 1,060 | 1,015 |
| Qwen parallel | 506 | 504 |
| LFM sequential | 1,049 | 1,019 |
| LFM parallel | 505 | 512 |

These phase medians include ordinary attempts with both actual tool entries,
even when the later model response failed the overall task. Both frameworks
show peak activity 1 sequentially and 2 in parallel. Two independent 500 ms
waits take about one second sequentially and half a second concurrently.

### What differs under repeated cancellation

Qwen exercised cancellation in every arm. Scala passed completed-result
preservation, unfinished-only reconciliation, interrupted completion, joined
cleanup, canonical recovery, paired history and continuation in **3/3** runs.
Python passed preservation, reconciliation, interruption, paired history and
continuation in **3/3**, but cleanup and canonical recovery in **0/3**.

Across both models, Scala completed cleanup in all **5 exercised** cancellation
attempts; one LFM attempt never admitted the required batch. Python cleanup
failed in all **6 exercised** attempts. As in the
[offline comparison](agentscope-python-comparison.md), repeated cancellation
aborts ordinary asynchronous finalization in the selected AgentScope path.
Each Python boundary reports an active-resource counter of 1 and **zero pending
tool tasks**. This does not demonstrate a live zombie, leaked OS process or a
failure of every possible Python cleanup implementation.

Scala stores exactly one recovery in all five exercised attempts. Python's
canonical recovery count remains zero: its delivered interruption response is
a different storage policy, not a demonstrated violation of its own contract.
A Python application can add protected cleanup and canonical recovery, as the
[correct asyncio reference](synthetic-runtime-comparison.md) already does.

The two admitted Scala LFM cancellation runs preserved the lifecycle contracts
but failed the aggregate continuation oracle. Their receipts do not isolate
whether the failure was the reply, completion state or history checks.
Python LFM continuation passed twice
and encountered `InternalServerError` once. This prevents claiming complete
usefulness for that selected LFM task on either framework.

### Failures and diagnostic limits

The receipts retain wrong final outputs, unexercised tasks, failed continuation
and provider failures. Besides the Python connection/internal-server errors,
one Scala Qwen parallel attempt and one Scala LFM sequential attempt report a
runtime failure after tool entry. The original series did not retain their
typed failure cause, so these records cannot establish whether the cause was
the adapter, provider response or task guard. The current runner distinguishes
selected typed failure categories for future execution; it cannot retroactively
classify those two attempts. No failed attempt was retried into a passing cell.

Three development preflight calls are outside this 48-attempt matrix: Python
Qwen text and parallel used an earlier inconsistent exact-whitespace matcher
and reported failure; after matching Scala's trim policy, a text preflight
passed. Those preflight
receipts are not framework acceptance evidence. A logging API setup error and
Scala compilation errors occurred before model execution and are not live
framework findings.

The supported advantage is **managed cleanup under repeated interruption and
runtime-owned canonical recovery in this public integration path**. The data
also confirms useful Qwen tasks and concurrent local tools. It does not support
universal superiority over Python, zero production defects or a release-ready
claim for every model profile.

## Reproduce a selected pair

Install the [pinned Python environment](agentscope-python-comparison.md#reproduce).
Supply `WEISGNOSIS_LIVE_API_KEY` through the host's secret mechanism into the
process environment. The following commands assume that credential is already
available; do not put it in source, logs or shell history. Set the other variables:

```powershell
$env:WEISGNOSIS_LIVE_SMOKE = 'enabled'
$env:WEISGNOSIS_LIVE_ENDPOINT = 'https://bots.weisgnosis.ru/api/v1/chat/completions'
$env:WEISGNOSIS_LIVE_MODEL = '<chat-model-from-authenticated-catalog>'
$env:WEISGNOSIS_LIVE_TIMEOUT_SECONDS = '300'
$env:WEISGNOSIS_COMPARISON_SCENARIO = 'parallel'
& target/agentscope-comparison-env/Scripts/python.exe -W error verification/live_agentscope_comparison.py parallel
sbt "examples / runMain com.weisgnosis.agents.examples.LiveRuntimeComparison parallel"
```

Select `text`, `sequential`, `parallel` or `cancel`. Check tunnel health and the
authenticated `/api/v1/models` catalog before selecting a model. Neither runner
retries a failed admitted task. Both emit sanitized JSON; acceptance uses
`scenarioAccepted`, not process exit or the presence of a final string.
Unexercised cancellation oracles are absent/null, not successful measurements.

## Matched task conditions

Both sides use temperature zero, streaming, `max_tokens=4096`, the same system
and user instructions and two read-only tools with empty arguments. The tools
return 17 and 20; an ordinary tool task must return exactly `37`, after trimming
outer whitespace, and preserve both successful observations. Each tool waits
500 milliseconds before returning. Text-only tasks require `READY`.

The cancellation task waits until the left result is persisted and the right
tool is running. Cancellation enters a held finalizer; another cancellation
arrives before its gate is released after 100 milliseconds. Measurements check
cleanup, completed-result preservation, unfinished-only reconciliation, canonical
recovery and complete history. A separate live turn on that same runtime must
return `READY` without another tool entry or changes to earlier tool observations.

AgentScope selects sequential scheduling by marking the tools not concurrency
safe; WeisGnosis uses `ToolExecution.Sequential`. AgentScope's public
`parallel_tool_calls=True` setting omits the provider field, as does Scala's
`None`. Its public `extra_body` supplies `max_tokens`, avoiding the adapter's
different `max_completion_tokens` parameter mapping. Native interrupted results
and terminal events are accepted as semantic interruption; Python need not use
Scala's compatibility metadata keys.

## Timing interpretation

`firstVisibleMillis` ends at the first nonempty visible text delta. It can include
an intermediate assistant preamble, rather than the final answer; thinking-only
tokens do not count. `totalMillis` measures the original agent turn from launch
through completion/cancellation, excluding process startup, client/agent
construction, later continuation and final harness/client teardown.
`continuationMillis` is separate. `toolPhaseMillis` measures first tool entry
through last cleanup for ordinary tool tasks, separating local scheduling from
inference. Cancellation uses `cancelJoinedMillis` and the cleanup oracle.

Recorded times include provider computation, HTTP/TLS, request formatting,
gateway load and cold first-turn runtime work. Matching instructions does not
make framework-generated wire contexts identical. Fresh Python/JVM processes
are used; this is not a warmed throughput or isolated runtime-overhead benchmark.
Python SDK and Scala HTTP clients differ; Scala limits a response to 1 MiB,
whereas this Python runner retains its SDK's response handling. Both original
turn budgets are 300 seconds. Model output remains probabilistic even at
temperature zero. These limits prevent attributing every timing difference to
the language or declaring universal performance superiority.

The catalog reported Qwen loaded and the instruction LFM model not loaded
before the campaign. A first request may therefore include backend model loading;
the receipts retain that attempt rather than silently dropping it as warm-up.

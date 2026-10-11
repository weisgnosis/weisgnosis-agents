# Synthetic lifecycle comparison with Python

The executed comparison does **not** support a claim that Python cannot provide
safe concurrency or interruption. On 2026-10-11, both the WeisGnosis production
ReAct runtime and a correctly owned Python asyncio reference passed the nine
selected lifecycle contracts. Separate negative controls reproduced unsafe
cancellation patterns. They are not measurements of a Python agent framework.

## Reproduce

Prerequisites: JDK 21, repository-pinned sbt and Python 3.11 or newer. The actual
run used Microsoft JDK 21.0.11, Scala 3.9.0, ZIO 2.1.26 and CPython 3.12.10 on
Windows. No credentials, model, network service or third-party Python package is
required. The Python process control starts and reaps one owned Python child.

```text
python -W error verification/compare_asyncio.py
sbt "examples / runMain com.weisgnosis.agents.examples.SyntheticRuntimeComparison"
```

The [Python executable](../verification/compare_asyncio.py) emits one JSON receipt
whose `matched` fields must all be true. Its `negativeControls` and
`processControl` fields report successful detection of the labelled behaviors,
not extra wins in the matched comparison. The
[Scala executable](../modules/examples/src/main/scala/com/weisgnosis/agents/examples/SyntheticRuntimeComparison.scala)
emits the same nine contract fields, all true. Both fail their assertions if an
expected result is absent; use the receipts as well as process exit. Python
assertions must remain enabled: do not use `-O` or `PYTHONOPTIMIZE`.

Focused offline verification:

```text
sbt "examples / testOnly com.weisgnosis.agents.examples.LiveMultiModelSpec"
```

These are synthetic executions. Actual gateway evidence remains in the separate
[live results](live-e2e-results.md).

## Matched task and actual result

The scripted task proposes a two-call batch: `completed-call` and `held-call`.
Both independent tools enter before the first can complete. The first returns
`completed-evidence`; its successful observation must be stored before the host
cancels the run. The second remains active with deliberately held cleanup.

Request cancellation twice while cleanup is held. Cancellation must remain
pending, then join after the host releases cleanup. Preserve the completed
observation, reconcile only the unfinished call, append exactly one recovery
message with the interrupted reason, preserve cancellation and continue using
the same runtime without repeating either tool.

| Checked contract | WeisGnosis ReAct | Python TaskGroup reference |
| --- | --- | --- |
| Two local tool bodies overlap | Passed | Passed |
| Calls stored before either tool enters | Passed | Passed |
| Completed sibling observation preserved | Passed | Passed |
| Only unfinished call reconciled as Error | Passed | Passed |
| Cancellation joins held cleanup, including repeated cancellation | Passed | Passed |
| Cancellation remains interruption, not successful completion | Passed | Passed |
| Exactly one recovery message and interrupted reason | Passed | Passed |
| Complete call/result pairing | Passed | Passed |
| Same runtime continues without executing tools again | Passed | Passed |

Scala exercises public `ReActAgent`, `Toolkit`, owned fibers and the persisted
local-result observer. Python uses `asyncio.TaskGroup`, with application-written
history, reconciliation and continuation. The task and contract expectations
match; the two implementations are not equivalent full agent libraries. This
comparison shows that the selected behavior is achievable in Python, while our
runtime already supplies the history/recovery policy. It does not quantify the
implementation or maintenance effort saved.

The cancellation drivers address the owning run twice. The Python negative
control below instead directly cancels the child twice; that deliberate ownership
change must not be presented as a failed matched TaskGroup case. Host gates and
scheduling yields establish state/order; there is no stopwatch-based oracle.

## Reproduced negative controls

1. `Task.cancel()` without joining returns while cleanup is unfinished. The
   control detects this pending state, then joins all tasks before exiting.
2. Directly cancelling a child again while its unprotected async `finally` awaits
   interrupts that cleanup. The correctly owned TaskGroup reference still passes
   the repeated cancellation test above; these are different patterns.
3. Cancelling a task awaiting `process.wait()` leaves the actual child process
   alive. Explicit owner termination and `wait()` then reap it. This is a
   Python-only diagnostic, without a matched Scala subprocess experiment.

These controls identify integration obligations. They are not leak-frequency
statistics, proofs about LangChain/AutoGen/CrewAI, or proof that ZIO cannot be used
incorrectly. No child process is intentionally left running after the test.

## Disposition of earlier comparative claims

| Earlier claim | What the evidence permits |
| --- | --- |
| Python cancellation inevitably leaks coroutines or skips cleanup | Not supported. The owned TaskGroup reference passes; the risky patterns are separately reproducible. |
| Python agent history necessarily becomes inconsistent on cancellation | Not supported as a language claim. The reference passes with explicit reconciliation; WeisGnosis supplies the checked policy in its runtime. |
| Python cannot overlap tools | Refuted for this I/O-style waiting task: both implementations reach two active tool bodies. |
| Cancelling a Python await automatically terminates its subprocess | Refuted by the actual Python child control; process ownership and termination must be explicit. No cross-runtime subprocess winner was measured. |
| Python threads cannot execute CPU-bound Python bytecode in parallel under the GIL | A constraint documented for conventional CPython, not measured here. It says nothing about I/O concurrency, process pools, native code releasing the GIL or other/free-threaded builds. |
| Python frameworks pollute peer histories with foreign tool IDs | Not compared: no pinned framework and shared MsgHub task were run. Our peer sanitization has separate library coverage. |
| Python cannot have typed state or immutable data | Unsupported generalization. The reference uses frozen entries and immutable history tuples; these runtime checks do not compare static type systems. |
| WeisGnosis is faster, safer under arbitrary load or universally superior | Not established. There is no matched throughput, CPU, memory, framework, crash-recovery or provider benchmark. |

[Python 3.12 task documentation](https://docs.python.org/3.12/library/asyncio-task.html)
describes joined TaskGroup ownership and cancellation propagation.
[Threading documentation](https://docs.python.org/3.12/library/threading.html)
distinguishes the GIL constraint from I/O concurrency.
[Subprocess documentation](https://docs.python.org/3.12/library/asyncio-subprocess.html)
provides separate waiting and termination operations. The subprocess behavior
above was executed locally, not inferred solely from those documents.

## Supported public statement

“WeisGnosis provides an independently reviewed ZIO ReAct runtime with tested
pre-execution persistence, unfinished-only cancellation recovery, joined local
cleanup and same-runtime continuation. Correct Python structured concurrency can
satisfy the same selected synthetic contracts; adopting this library supplies the
tested agent policy rather than requiring the host to write that policy.”

This statement describes selected verified behavior. It does not promise
exhaustive correctness or superiority over an untested framework.

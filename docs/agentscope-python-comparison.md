# Executed Python AgentScope comparison

This offline comparison runs actual Python AgentScope **2.0.9** on CPython
**3.12.10**, and the public WeisGnosis `ReActAgent`/`Toolkit` path on Scala/ZIO.
It uses scripted models and real concurrent tools. It does not compare live
provider quality, throughput, token cost, memory consumption or every framework
feature. The Python release is pinned from
[PyPI](https://pypi.org/project/agentscope/2.0.9/).

The separate [live comparison](live-runtime-comparison.md) uses real gateway
models for useful tasks and timing. Its results do not change the offline
scope of this experiment.

## Reproduce

From the repository root, use an isolated environment:

```powershell
python -m venv target/agentscope-comparison-env
& target/agentscope-comparison-env/Scripts/python.exe -m pip install agentscope==2.0.9
& target/agentscope-comparison-env/Scripts/python.exe -W error verification/compare_agentscope.py
sbt "examples / runMain com.weisgnosis.agents.examples.SyntheticRuntimeComparison"
```

No credentials or model requests are required. Installation downloads package
dependencies; the scenarios themselves are offline. The Python script rejects
another AgentScope version. Dependency resolution beyond that package is not
locked. Framework contract failures appear in the JSON receipt; fixture
exceptions fail the process. Read `oracleStatus`, rather than treating exit zero
as framework acceptance.

The [Python fixture](../verification/compare_agentscope.py) uses `Agent`,
`reply_stream`, `FunctionTool`, `Toolkit`, public state and a `ChatModelBase`
extension with an OpenAI formatter. It does not patch AgentScope internals or
repair its history. The [Scala fixture](../modules/examples/src/main/scala/com/weisgnosis/agents/examples/SyntheticRuntimeComparison.scala)
uses the actual runtime, including its local result persistence observer.

## Task and cancellation boundary

Both models request two independent tools. Both calls must be present in
canonical history before either tool enters. One tool completes and its result
must be stored before cancellation; the other enters a held asynchronous
finalizer. Cancellation must wait until cleanup is explicitly released.
Repeated cancellation happens while that cleanup is held. The runtime then
continues with another user input without repeating the completed tools.

AgentScope is configured to permit the same two read-only, concurrency-safe
tools. Its recovery text is set through `ReActConfig`, matching the Scala
fixture. Three Python arms distinguish default single cancellation, default
repeated cancellation and repeated cancellation with
`interruption_raise_cancelled_error=True`. Scala currently exercises repeated
interruption; the Python single arm is an additional control, not a separately
executed Scala single-interruption result.

Python state `interrupted` is accepted as the semantic equivalent of an
interrupted Error observation. An explicit interrupted terminal event counts
as preserved interruption even when the default Python policy consumes
`CancelledError`. Legacy Scala metadata keys are not required in Python.

## Observed results

| Contract | Scala repeated | AgentScope single/default | AgentScope repeated/default | AgentScope repeated/propagation |
| --- | --- | --- | --- | --- |
| Both tools overlap, peak active = 2 | Pass | Pass | Pass | Pass |
| Persist calls before tool entry | Pass | Pass | Pass | Pass |
| Preserve the completed result | Pass | Pass | Pass | Pass |
| Reconcile only the unfinished call | Pass | Pass | Pass | Pass |
| Cancellation joins completed cleanup | Pass | Pass | Fail | Fail |
| Preserve interruption semantics | Pass | Pass | Pass | Pass |
| Store exactly one recovery assistant in canonical history | Pass | Fail | Fail | Fail |
| Preserve complete call/result pairing | Pass | Pass | Pass | Pass |
| Continue the same runtime without tool replay | Pass | Pass | Pass | Pass |

These are selected contract observations, not whole-project quality scores.
Three Python executions produced the same results in every arm; a fresh Scala
execution passed all nine repeated-interruption contracts.
AgentScope demonstrates working parallel calls, persistence, unfinished-call
reconciliation and continuation. Describing Python agents as universally
incapable of those behaviors would contradict this execution.

Two differences remain in this task:

- **Repeated cancellation interrupts held cleanup in AgentScope.** The first
  cancellation enters the tool's asynchronous `finally`; a second parent
  cancellation aborts it before the release gate. At return, cleanup is false
  and the fixture's active-resource counter is 1. There are **zero pending tool
  tasks**: this is incomplete cleanup, not evidence of a still-running zombie
  or an actual leaked OS process. Scala's managed finalizer finishes and its
  interruption callers wait for it. A Python tool can implement its own
  protected cleanup; this arm measures the framework path with ordinary
  asynchronous cleanup, without adding that repair.
- **AgentScope delivers recovery without storing it in canonical history.**
  Each arm emits one recovery message and an interrupted reply-end event,
  while its canonical recovery count is zero. This differs from WeisGnosis
  INV-2. It is a storage-policy difference, not proof that AgentScope violates
  its own advertised contract. Continuation succeeds in all three arms.

Successful continuation checks the next reply and absence of tool replay; it
does not imply that previously interrupted resource cleanup has been repaired.

The configured propagation arm ends with task cancellation; both default arms
end with an explicit interrupted event instead. Neither policy is presented
as intrinsically incorrect.

## Limits on public claims

The defensible comparative claim is that **this selected WeisGnosis runtime
path protects cleanup under repeated interruption and persists recovery in
its canonical history**, with the versioned AgentScope behavior above as the
control. Scripted models deliberately remove probabilistic generation from
the lifecycle experiment. This does not close the live E3 scenario.

The separate [asyncio reference](synthetic-runtime-comparison.md) passes all
nine contracts when the application explicitly implements the corresponding
ownership and recovery policy. The [Scala/ZIO architectural catalogue](scala-zio-positioning.md)
explains fifty engineering arguments without presenting them as fifty measured
framework victories.

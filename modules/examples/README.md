# Host integration examples

This nonpublished SBT project compiles against core, harness, and MCP. It demonstrates composition using the existing immutable ReActAgent builder; it does not introduce a library facade.

HostAssembly accepts a model, explicit authorization, summarization, storage, and selected scoped MCP acquisitions. Its owner Scope bounds the MCP registrations and every agent run uses the runtime's own scope. Local tools must include read_file capable of retrieving paths returned by the supplied result store. No persistence or interactive approval policy is guessed.

The example installs result eviction before context compaction, offloads compacted history to the lossy transcript, and records terminal snapshots with an explicit anonymous-message policy. The fixed run budget is three model iterations and one minute; finalizer completion may exceed that deadline. It creates no background scheduler or durable host.

LiveProviderSmoke is an explicitly opt-in runnable program for a host-selected Chat Completions endpoint. Ordinary tests and verification remain offline. Before running it, set WEISGNOSIS_LIVE_SMOKE to exactly enabled, WEISGNOSIS_LIVE_ENDPOINT to the full endpoint URL, and WEISGNOSIS_LIVE_MODEL to the explicit model identifier. WEISGNOSIS_LIVE_API_KEY is required when the endpoint requires bearer authentication and may be omitted for an endpoint that does not require it; no ambient provider credential is read. Keep credentials in the process environment, never in tracked files or command history.

Run `sbt "examples / runMain com.weisgnosis.agents.examples.LiveProviderSmoke"` from the repository root. An enabled run sends provider requests and may incur charges. It permits three model iterations, 1,024 output tokens per request, a one-minute run deadline, and a one-MiB response bound. The check requires one successful addition-tool execution, streamed visible text, and the exact final reply 5. Logs report only aggregate validation counts. No external endpoint has been validated by the offline suite.

## Scoped host usage

Use supplied settings, dependencies and selected registrations within their owning scope:

```scala
ZIO.scoped:
  HostAssembly.open(settings, dependencies, registrations)
    .flatMap(_.agent.run(input))
```

The example keeps policy, model and storage choices explicit. The existing builder and ports suffice for this bounded assembly; an additional supported library facade is not justified by this example. Subagent installation, maintenance scheduling and durable host recovery keep their separate contracts.

## Lifecycle logging

[LifecycleLogging](src/main/scala/com/weisgnosis/agents/examples/LifecycleLogging.scala) consumes result-bearing events through EOF and uses existing ZIO structured logging. Wrap the assembled agent with `LifecycleLogging.run(agent, input)` inside its owner scope. It returns the complete AgentResult and preserves failure causes, cancellation, and joined cleanup. It does not retry executed turns.

The allowlist emits static agent/model/tool lifecycle messages. Runtime agent/reply/request IDs correlate events; a fresh effectful invocation_id correlates the whole wrapper invocation and terminal status. Per-event annotations do not leak a previous request ID. AgentEnd records closure, not success; terminal state is reported separately without rendering suspension data or stop reasons. Raw tool IDs/names, model names, prompts, reasoning, deltas, outputs, metadata and error causes are excluded from this adapter's records.

observe accepts runtime-origin events. It is not a sanitizer for caller-forged IDs, inherited host annotations, independent runtime/third-party logs or exporter behavior. ZIO may independently log failed-fiber causes; the host configures its logger privacy boundary, levels, export, retention and access. A slow logger delays consumption and existing runtime event buffers are unbounded. Logger defects propagate with owned cleanup, so the adapter does not promise failure-free diagnostics.

ZIO.logSpan supplies logging timing context, not an OpenTelemetry span or distributed tracing. Hosts can install zio-logging or a tracing bridge through their runtime/layers. This example adds no mandatory dependency, exporter, background subscriber or distributed tracing implementation.

## Verification and acceptance

- [x] review_fp approved FP/ZIO correctness and review_invariants approved relevant invariants for the five identities below, including the following model request receiving the retained eviction preview. No findings remain in this scope.
- [x] `host-assembly-verify.log` passes formatting, fatal-warning compilation, 658 core / 724 harness / 85 MCP / 3 examples tests (1,470 total), and library packaging. The three example groups cover integrated tool execution, joined failed/cancelled acquisition cleanup, and offline smoke admission.
- [ ] Run the explicitly enabled smoke against a selected compatible live endpoint; offline verification does not establish live-provider interoperability.

| Reviewed file | Git blob |
| --- | --- |
| [build.sbt](../../build.sbt) | [Current candidate preparation identity](../../verification/artifact-consumer/README.md#candidate-preparation-acceptance) |
| [HostAssembly.scala](src/main/scala/com/weisgnosis/agents/examples/HostAssembly.scala) | `b520fc9338abb3531067139409d71b0bb6f2dc05` |
| [HostAssemblySpec.scala](src/test/scala/com/weisgnosis/agents/examples/HostAssemblySpec.scala) | `1091488cbb76edc3a7f687ea8920613ec9498145` |
| [LiveProviderSmoke.scala](src/main/scala/com/weisgnosis/agents/examples/LiveProviderSmoke.scala) | `61bffc3da29040c9ff16270b8bd9387cb845337d` |
| [LiveProviderSmokeSpec.scala](src/test/scala/com/weisgnosis/agents/examples/LiveProviderSmokeSpec.scala) | `60092ce956893432a4415609ce95b75c05ab983c` |

## Logging acceptance

- [x] review_fp approved FP/ZIO correctness and review_invariants approved relevant invariants for the two logging identities below; no findings remain in this scope.
- [x] lifecycle-logging-verify.log passes formatting, fatal-warning compilation, 658 core / 724 harness / 85 MCP / 5 examples tests (1,472 total), and library packaging. Markdown link and English prose checks pass.

| Reviewed file | Git blob |
| --- | --- |
| [LifecycleLogging.scala](src/main/scala/com/weisgnosis/agents/examples/LifecycleLogging.scala) | `6760a231a4ebb2663ce4768536db84da2e71c3c8` |
| [LifecycleLoggingSpec.scala](src/test/scala/com/weisgnosis/agents/examples/LifecycleLoggingSpec.scala) | `3338fa0be9ec09beafca7458309316dd05afcae0` |

The paired LifecycleLoggingSpec has two contract groups: static allowlist/redaction/correlation with an actual completed run, and failure preservation plus cancellation blocked on model cleanup. These are new Scala integration checks, not additional upstream parity.

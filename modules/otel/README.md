# OpenTelemetry adapter

`weisgnosis-agents-otel` is an optional artifact. Core has no telemetry dependency. This module
uses `zio-opentelemetry` 3.1.20 with OpenTelemetry API 1.66.0; its SDK dependencies are test-only.

The host supplies a `Tracer` and fiber-local `ContextStorage` from
`zio.telemetry.opentelemetry.OpenTelemetry.contextZIO`. Create the adapter in the invocation
host's scope and attach all three observers:

```scala
import com.weisgnosis.agents.core.agent.ReActAgent
import com.weisgnosis.agents.otel.AgentTelemetry
import zio.*
import zio.telemetry.opentelemetry.context.ContextStorage

// tracer, model and toolkit are borrowed host capabilities.
val program = ZIO.scoped {
  for
    contexts <- ZIO.service[ContextStorage]
    telemetry <- AgentTelemetry.make(tracer, contexts)
    agent <- ReActAgent.builder()
      .model(model)
      .toolkit(toolkit.withDispatchObserver(telemetry.dispatchObserver))
      .phaseObserver(telemetry.phaseObserver)
      .completionObserver(telemetry.completionObserver)
      .build
    result <- agent.run(input)
  yield result
}.provideSomeLayer[Any](zio.telemetry.opentelemetry.OpenTelemetry.contextZIO)
```

The observers can be composed with existing observers using `andThen`; `withDispatchObserver`
replaces the toolkit observer, so compose before attaching it. Host observer order and failures
still apply. Keep the adapter's scope alive through its agent invocations and child fibers.
Use fiber-local context storage, including for parallel tools; native thread-local storage
does not provide the required ZIO fiber context semantics. Thread-local `Span.current()` is not
the context API for this adapter. Hosts propagate remote context explicitly before invocation.

`agent.invocation`, `agent.reasoning`, `agent.model` and `agent.tool` spans follow actual phase
and local-dispatch ownership. Model fallback creates separate model spans. Tool spans observe
completed local dispatch normalization, including decoding/execution errors; they are not
proof that a handler ran. Unknown tools, denied/prepared synthetic observations, external
execution and server-hosted inline results are outside local dispatch. Spans close after
owned work cleanup. The adapter never ends the borrowed parent span.

Successful invocation outcomes carry `weisgnosis.run.state` as `completed`, `suspended`,
`interrupted` or `stopped`. Failure/pure interruption is `failed`/`cancelled`. A mixed failure plus interruption remains `failed` with ERROR status; `weisgnosis.interruption=present` preserves the interruption signal without inferring its source. These states
do not acknowledge delivery or approval. Tool state is separately recorded; a normalized
tool error marks that tool span as an error while the agent may successfully continue.

Attributes have fixed keys and bounded values: outcome, run state, tool state, interruption presence, reasoning
iteration and tool occurrence ordinal. Prompts, messages, arguments, results, model/tool
names, error text, stack traces, user IDs and runtime correlation IDs are omitted. Span
parentage supplies trace correlation. The adapter does not install global telemetry,
automatic logging, HTTP instrumentation, baggage or distributed propagation. Host processors
may independently add data and must follow the host's privacy policy.

Telemetry operation failures and defects are isolated and counted by `diagnostics`; no
unbounded diagnostic log or per-request records are retained. Caller interruption is preserved,
including a compound cause containing interruption and a telemetry failure. Runtime failures
and cleanup causes remain authoritative and are not converted into telemetry retries.

The host owns the SDK, processors, exporters, sampling and exporter shutdown/flush. The adapter
does not export or flush directly. Use a bounded asynchronous processor and nonblocking span
completion for production. A supplied synchronous processor can export inside `span.end()`
and extend cleanup latency; the adapter cannot make arbitrary host SDK callbacks nonblocking.
No daemon, exporter availability guarantee or exactly-once delivery is implied.

[AgentTelemetrySpec](src/test/scala/com/weisgnosis/agents/otel/AgentTelemetrySpec.scala) exercises
the public runtime with an in-memory SDK. [ArtifactSmoke](../../verification/artifact-consumer/ArtifactSmoke.scala)
contains a fresh-consumer integration using the API's no-op tracer.

The failed-exporter scenario verifies unchanged runtime execution when the SDK reports export failure. It does not inject every synchronous SDK or sealed ContextStorage defect; those isolation branches additionally rely on source review.

## Host-owned OTLP HTTP export

An application that exports traces adds its own SDK and exporter dependencies, independently
of the optional agent adapter:

```scala
"io.opentelemetry" % "opentelemetry-sdk" % "1.66.0"
"io.opentelemetry" % "opentelemetry-exporter-otlp" % "1.66.0"
```

Inside suspended, scoped acquisition, construct `OtlpHttpSpanExporter.builder()` with an
explicit `/v1/traces` endpoint and request timeout, then a `BatchSpanProcessor` with explicit
queue capacity, batch size, exporter timeout and schedule delay. During acquisition, close the exporter if processor construction fails, and close the processor
(which owns the exporter) if provider construction fails. After success, register only the
provider finalizer; acquisition must be masked through registration. `SdkTracerProvider.builder().addSpanProcessor(processor).build()`
supplies the tracer via `provider.get("your-instrumentation-name")`; pass that borrowed tracer
and `OpenTelemetry.contextZIO` storage to `AgentTelemetry.make`. Do not register a global SDK.

The host scope must outlive all traced invocations. After joining them, the host can await
`provider.forceFlush()` and finally `provider.shutdown()` using their bounded
`CompletableResultCode.join(timeout, TimeUnit.SECONDS)` operation in `ZIO.attemptBlocking`.
Check `isSuccess` explicitly: timeout or rejection is not successful export. Provider shutdown
owns processor/exporter shutdown, so do not register duplicate successful-lifetime releases. Exporter credentials, sampling, trusted destinations and shutdown budgets
are application policy. An asynchronous bounded queue can drop telemetry under overload;
export failures must not trigger replay of an agent invocation.

The local OTLP scenario in `AgentTelemetrySpec` sends an actual SDK-generated HTTP/protobuf
batch to a scoped loopback `/v1/traces` handler, checks the content type, bounded nonempty
payload and span names, and requires successful SDK flush. In-memory scenarios check the
span parent relationships. This is not evidence of a deployed Collector, backend retention
or remote exporter availability. See the [official Java SDK guidance](https://opentelemetry.io/docs/languages/java/sdk/).

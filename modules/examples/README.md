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

## Registered transcript assembly

HostAssembly.Dependencies.transcript now accepts TranscriptSource.Provided(existingStore) or Registered(backend, catalog, id, explicitLimits). Unpublished example callers supplying a store wrap it in Provided; library factory signatures/defaults are unchanged. Registered resolves exactly one matching catalog descriptor before MCP acquisition, chooses VersionedTranscriptStore with maxAttempts for Aggregate or SegmentedTranscriptStore with all checked limits for Segmented, then installs the existing offload/completion observer.

Lookup failure or cancellation joins its owned cleanup before returning and acquires no MCP client. The supplied backend/catalog retain their host-defined lifetimes. Registration, history preflight, authorization, leases, automatic selection and global discovery are not installed. A descriptor may point to corrupt history that fails completion observation after model/tool work; no rollback or safe automatic replay is promised.

The paired actual-ReAct table checks both layouts through shared Ref-backed conditional publication, including exact selected history/returned records. Missing/duplicate matching IDs fail assembly. The existing failure/cancellation table covers catalog and MCP acquisition stages without adding another group. This is offline composition evidence, not PostgreSQL/provider integration or upstream method parity. This composition selects no new provider profile. Legacy type=signature reasoning-details objects require the explicitly selected [opaque signature details profile](../core/src/main/scala/com/weisgnosis/agents/core/client/README.md#opaque-signature-details); other provider and per-tool signature wire contracts remain separate.

| Source/spec under review against 1f4581b | Git blob |
| --- | --- |
| [HostAssembly.scala](src/main/scala/com/weisgnosis/agents/examples/HostAssembly.scala) | 8f01c4db654983e4d47461d366e7452eabd130c2 |
| [HostAssemblySpec.scala](src/test/scala/com/weisgnosis/agents/examples/HostAssemblySpec.scala) | fc212b7e36c386278a3c36437df1f706e145e94c |

- [x] review_fp approved the final two source/spec identities against 1f4581b.
- [x] review_invariants approved the same final two identities, with no unresolved findings.
- [x] registered-transcript-final-focused.log passed three assembly groups in 662 milliseconds.
- [x] registered-transcript-verify.log exited zero: 1,508 tests (669 core / 726 harness / 86 MCP / 21 PostgreSQL / 6 examples), zero compiler warnings/failures and four packages.
- [x] registered-transcript-preparation.log passed source/archive admission regressions and restored archive/POM staging.
- [x] registered-transcript-consumer.log passed fresh standalone formatting, clean compilation and forked selected-API consumption; the smoke does not select Registered.
- [x] review_fp and review_invariants approved final combined documentation against 1f4581b and the two unchanged identities; manual English inspection, changed-script/diff checks and 78-document/2,827-local-reference checks pass with zero issues.

- [x] review_fp and review_invariants approved the three-document RC-readiness clarification against 2a92a0b with no findings; English/diff and 78-document/3,020-local-reference checks pass. Source/test identities and existing verification are unchanged.

## Audited public model call

[AuditedLlmClient.wrap](src/main/scala/com/weisgnosis/agents/examples/AuditedLlmClient.scala) is a pure unpublished example of public-call decoration. One suspended streamTurn delegation receives the exact history/tools/config/event hub/context inside a fresh request scope. Suspended Clock.nanoTime measurement includes owned request cleanup; the observer sees the complete exit afterward. The observation contains only ModelStreamContext, elapsed duration and Returned/Failed/Cancelled, with no copied content, configuration or causes. Cancelled means interruption-only failure; mixed failure/interruption remains Failed. Returned includes cooperative Interrupted and is not validated runtime completion.

Ordinary output/cause is preserved. The UIO observer runs as an uninterruptible onExit finalizer and must terminate: slow observation delays cancellation. Observer defects replace success or compose with existing failure/cancellation. There is no durable audit-delivery guarantee. A public wrapper cannot forward private attemptTurn/BeforeResponseRejection capability-based failover proof or implicitly acquire the live client's private request-progress channel; hosts must account for this limitation when decorating compatible clients. No retries, cache, raw event rewriting or general middleware facade is supplied, and library APIs are unchanged.

The two paired test groups verify nonempty exact forwarding, one lazy delegation, client failures/observer defects/cooperative return, and held request cleanup followed by one timed cancellation observation. Pinned ModelCallMiddlewareExample.AuditingMiddleware.onModelCall (149-185) provides delegation/timing/outcome source intent only. Global counters, raw logging, Java chains and advertised client substitution are excluded; no whole middleware-method/suite parity is claimed.

| Source/spec approved by review_fp and review_invariants against 15047a8 | Git blob |
| --- | --- |
| [AuditedLlmClient.scala](src/main/scala/com/weisgnosis/agents/examples/AuditedLlmClient.scala) | 2229c8466e49409cd181e1437f4a70d3c209df6a |
| [AuditedLlmClientSpec.scala](src/test/scala/com/weisgnosis/agents/examples/AuditedLlmClientSpec.scala) | 72d0ee0cbadfc5883ec20922c88ed34e4e77eef6 |

- [x] Both independent reviewers approved these final identities, with no unresolved findings.
- [x] Focused verification passed two groups in 165 milliseconds.
- [x] audited-client-verify.log exited zero: 1,512 tests (670 core / 726 harness / 86 MCP / 22 PostgreSQL / 8 examples), zero compiler warnings/failures and four packages.
- [x] audited-client-preparation.log passed archive/source admission regressions and restored archive/POM staging.
- [x] audited-client-consumer.log passed fresh standalone formatting, clean compilation and forked selected public API execution; the smoke does not exercise unpublished AuditedLlmClient.
- [x] review_fp and review_invariants approved final combined documentation against 15047a8 and the two unchanged identities; manual English inspection, diff checks and 78-document/2,857-local-reference checks pass with zero issues.

## Lifecycle logging

[LifecycleLogging](src/main/scala/com/weisgnosis/agents/examples/LifecycleLogging.scala) consumes result-bearing events through EOF and uses existing ZIO structured logging. Wrap the assembled agent with `LifecycleLogging.run(agent, input)` inside its owner scope. It returns the complete AgentResult and preserves failure causes, cancellation, and joined cleanup. It does not retry executed turns.

The allowlist emits static agent/model/tool lifecycle messages. Runtime agent/reply/request IDs correlate events; a fresh effectful invocation_id correlates the whole wrapper invocation and terminal status. Per-event annotations do not leak a previous request ID. AgentEnd records closure, not success; terminal state is reported separately without rendering suspension data or stop reasons. Raw tool IDs/names, model names, prompts, reasoning, deltas, outputs, metadata and error causes are excluded from this adapter's records.

observe accepts runtime-origin events. It is not a sanitizer for caller-forged IDs, inherited host annotations, independent runtime/third-party logs or exporter behavior. ZIO may independently log failed-fiber causes; the host configures its logger privacy boundary, levels, export, retention and access. A slow logger delays consumption and existing runtime event buffers are unbounded. Logger defects propagate with owned cleanup, so the adapter does not promise failure-free diagnostics.

ZIO.logSpan supplies logging timing context, not an OpenTelemetry span or distributed tracing. Hosts can install zio-logging or a tracing bridge through their runtime/layers. This example adds no mandatory dependency, exporter, background subscriber or distributed tracing implementation.

## Verification and acceptance

- [x] review_fp approved FP/ZIO correctness and review_invariants approved relevant invariants for the five identities below, including the following model request receiving the retained eviction preview. No findings remain in this scope.
- [x] Current formatting, fatal-warning compilation, full tests and packaging are recorded in the [audited-client verification](#audited-public-model-call). Existing integration/offline-smoke contracts retain their scoped review acceptance.
- [ ] Run the explicitly enabled smoke against a selected compatible live endpoint; offline verification does not establish live-provider interoperability.

| Reviewed file | Git blob |
| --- | --- |
| [build.sbt](../../build.sbt) | [Current candidate preparation identity](../../verification/artifact-consumer/README.md#candidate-preparation-acceptance) |
| [HostAssembly.scala](src/main/scala/com/weisgnosis/agents/examples/HostAssembly.scala) | [Current registered transcript scope](#registered-transcript-assembly) |
| [HostAssemblySpec.scala](src/test/scala/com/weisgnosis/agents/examples/HostAssemblySpec.scala) | [Current registered transcript scope](#registered-transcript-assembly) |
| [LiveProviderSmoke.scala](src/main/scala/com/weisgnosis/agents/examples/LiveProviderSmoke.scala) | `61bffc3da29040c9ff16270b8bd9387cb845337d` |
| [LiveProviderSmokeSpec.scala](src/test/scala/com/weisgnosis/agents/examples/LiveProviderSmokeSpec.scala) | `60092ce956893432a4415609ce95b75c05ab983c` |

## Logging acceptance

- [x] review_fp approved FP/ZIO correctness and review_invariants approved relevant invariants for the two logging identities below; no findings remain in this scope.
- [x] Current full verification is recorded in the [audited-client contract](#audited-public-model-call); the unchanged logging identities retain their accepted redaction/correlation/lifecycle scope.

| Reviewed file | Git blob |
| --- | --- |
| [LifecycleLogging.scala](src/main/scala/com/weisgnosis/agents/examples/LifecycleLogging.scala) | `6760a231a4ebb2663ce4768536db84da2e71c3c8` |
| [LifecycleLoggingSpec.scala](src/test/scala/com/weisgnosis/agents/examples/LifecycleLoggingSpec.scala) | `3338fa0be9ec09beafca7458309316dd05afcae0` |

The paired LifecycleLoggingSpec has two contract groups: static allowlist/redaction/correlation with an actual completed run, and failure preservation plus cancellation blocked on model cleanup. These are new Scala integration checks, not additional upstream parity.

# Host integration examples

This nonpublished SBT project compiles against core, harness, and MCP. It demonstrates existing library APIs rather than adding a supported facade. Start with the complete applications in [Getting started](../../docs/getting-started.md). Use JDK 21 and the repository-pinned sbt version, and run the commands below from the repository root.

The [real-model E2E scenario design](../../docs/live-e2e-scenarios.md) specifies a useful evidence/report task, concurrency and cancellation measurements, and publication limits. Proposed extensions there are not implemented live profiles; the runnable coverage and actual earlier observations below remain separate.

`LiveFileTask`, `LiveProviderSmoke`, `LiveProviderProfiles` and `MsgHubConversation` are runnable applications. HostAssembly, AuditedLlmClient and LifecycleLogging are composition examples used by a host application; they have no standalone `runMain` entrypoint.

## Persistent-agent conversation

[MsgHubConversation](src/main/scala/com/weisgnosis/agents/examples/MsgHubConversation.scala) is an offline application using two persistent agents and scoped MsgHub subscriptions. It makes no network requests. Run `sbt "examples / runMain com.weisgnosis.agents.examples.MsgHubConversation"`; its expected output is `Bob: Alice recommends typed outcomes and scoped resources.` The offline application compiled and produced this output on 2026-10-09; live collaboration is a separate check. See [membership, delivery and ownership](../core/src/main/scala/com/weisgnosis/agents/core/coordination/README.md).

## Model-driven file task

[LiveFileTask](src/main/scala/com/weisgnosis/agents/examples/LiveFileTask.scala) demonstrates a useful model → host file tool → model flow with one disposable recipe file. It supplies only `fixture_write(text)` and `fixture_read()`: the model cannot choose paths, run commands or access other files. The host requires exact recipe bytes, one successful write and read in separate tool turns, an authoritative Completed result and joined fixture removal. The final model's verbal confirmation alone cannot satisfy verification.

Run `sbt "examples / runMain com.weisgnosis.agents.examples.LiveFileTask"` using the existing `WEISGNOSIS_LIVE_SMOKE=enabled`, `WEISGNOSIS_LIVE_ENDPOINT`, `WEISGNOSIS_LIVE_MODEL` and optional `WEISGNOSIS_LIVE_API_KEY` configuration described below. Model and endpoint are explicit host choices; no default identifier or ambient credential is selected. Enabled execution makes external requests and may incur charges. Limits remain three model iterations, 4,096 output tokens per request by default and a five-minute run deadline and one-MiB provider responses. File input is fixed and bounded; this is not a general filesystem toolkit or hostile-filesystem sandbox.

The directory is acquired into an owned scope, native file operations are suspended and joined, and cleanup errors remain typed failures rather than successful verification. Cleanup may extend beyond the model deadline. [LiveFileTaskSpec](src/test/scala/com/weisgnosis/agents/examples/LiveFileTaskSpec.scala) drives the actual ReAct/file path offline and checks the next model request's read observation, exact bytes, cancellation cleanup and typed failure. Offline tests send no provider requests. The actual selected-endpoint run on 2026-10-08 completed with one write, one read, exact expected file bytes and removed owned fixture, using qwen3.8-27b selected from the authenticated model catalog. This is bounded evidence for that run, not all providers, models or filesystem environments.

## Scoped host usage

[HostAssembly](src/main/scala/com/weisgnosis/agents/examples/HostAssembly.scala) composes a model, explicit authorization, result storage, summarization, transcript storage, and selected scoped MCP registrations. Keep the returned assembly inside its owning Scope; each agent run owns its own run/request scopes. The host must install a read_file tool capable of retrieving paths returned by the supplied result store.

Assembly installs result eviction before context compaction and records terminal snapshots with an explicit anonymous-message policy. Its run budget is three model iterations and one minute; joined cleanup may exceed that deadline. It creates no durable host, background scheduler, or automatic approval policy. [HostAssemblySpec](src/test/scala/com/weisgnosis/agents/examples/HostAssemblySpec.scala) exercises actual offloaded-result inspection through the following model request.

## Registered transcript assembly

HostAssembly.TranscriptSource selects either a provided store or one descriptor from an explicit registered catalog. Registered selection occurs before MCP acquisition and installs the existing aggregate or segmented writer with checked limits. The supplied catalog and backend remain host-owned.

Selection adds no registration, global discovery, history preflight, authentication, or execution lease. Corrupt history can fail a completion observer after work has occurred; there is no rollback or safe automatic replay promise. The source and its paired integration suite show the supported composition.

## Audited public model call

[AuditedLlmClient.wrap](src/main/scala/com/weisgnosis/agents/examples/AuditedLlmClient.scala) decorates the public model call with scoped timing and Returned/Failed/Cancelled observations. It preserves ordinary output and causes without copying prompts, configuration, or raw failures into the observation. Returned includes cooperative interruption; mixed interruption/failure is classified Failed.

The observer is an uninterruptible finalizer and must terminate. Its defects propagate, and it supplies no durable audit-delivery guarantee. A public wrapper cannot preserve private failover proof or implicitly acquire a wrapped client's private progress channel. See [AuditedLlmClientSpec](src/test/scala/com/weisgnosis/agents/examples/AuditedLlmClientSpec.scala) for these boundaries.

## Lifecycle logging

[LifecycleLogging](src/main/scala/com/weisgnosis/agents/examples/LifecycleLogging.scala) consumes result-bearing events through EOF using ZIO structured logging. Use LifecycleLogging.run within the agent's owner scope. It preserves the complete result, failure cause, cancellation, and joined cleanup without replaying turns.

Its static allowlist excludes prompts, reasoning, outputs, metadata, raw tool/model names, and error causes. Runtime correlation IDs are observational. AgentEnd means closure, not success. A slow or failing logger affects consumption, and existing event buffers are unbounded. Hosts own inherited annotations, third-party logs, exporter privacy, retention, and access. ZIO log spans are not a distributed tracing implementation.

[LifecycleLoggingSpec](src/test/scala/com/weisgnosis/agents/examples/LifecycleLoggingSpec.scala) covers correlation, redaction, failure preservation, and cleanup during cancellation; it does not establish a privacy boundary for unrelated host logging.

## Live endpoint verification

[LiveProviderSmoke](src/main/scala/com/weisgnosis/agents/examples/LiveProviderSmoke.scala) is opt-in and sends real requests that may incur charges. Set WEISGNOSIS_LIVE_SMOKE to enabled, WEISGNOSIS_LIVE_ENDPOINT to the full endpoint URL, and WEISGNOSIS_LIVE_MODEL to the selected model identifier. Supply WEISGNOSIS_LIVE_API_KEY only if bearer authentication is required. Keep credentials in the process environment, outside tracked files and command history.

Run `sbt "examples / runMain com.weisgnosis.agents.examples.LiveProviderSmoke"` from the repository root. The program bounds model iterations, output tokens, runtime, and response bytes; it checks addition-tool execution, streamed text, and the exact completed reply. Its logs expose aggregate validation counts. Require the explicit `Live provider smoke passed:` message; a disabled run or a logged application failure is not successful endpoint verification.

Unset `WEISGNOSIS_LIVE_SMOKE` disables external execution; other values besides `enabled` fail configuration. The endpoint must be the complete Chat Completions URL, including `/chat/completions`. The smoke is a narrow endpoint check, not evidence for cancellation, ordinary JSON completion, structured output, media, failover, subagent/MCP chains, or durable recovery. Offline verification does not contact or validate that endpoint.

## Selected structured-output and cancellation profiles

[LiveProviderProfiles](src/main/scala/com/weisgnosis/agents/examples/LiveProviderProfiles.scala)
uses the same explicitly enabled environment as LiveProviderSmoke. Run
`sbt "examples / runMain com.weisgnosis.agents.examples.LiveProviderProfiles"`.
By default (`WEISGNOSIS_LIVE_PROFILE=all`, or unset) it sequentially requires native structured
output, synthetic generate_response output and a model-issued
`profile_hold` tool call that the host cancels. Both structured results must be Completed and preserve
exactly `{"value":7}`, with no additional fields. Native rejection fails the check rather than silently
switching formats. Set WEISGNOSIS_LIVE_PROFILE to native, synthetic or cancellation to run
one selected profile independently. A selected success does not establish success of the other profiles.

The cancellation profile holds local-tool cleanup, checks that cancellation has not returned early,
then releases it and verifies interruption, one Error observation, complete call/result history and
one recovery message. It rejects hidden typed failures or defects. This verifies a live model-to-tool
chain and local resource cleanup; it does not prove cancellation during an active HTTP response.

Each profile uses the shared live run/token budgets below, at most three model iterations and
one-MiB HTTP responses. The three-profile application has no single aggregate deadline;
joined cleanup can extend the elapsed time. Credentials/model selection remain process-local host
inputs. [Offline tests](src/test/scala/com/weisgnosis/agents/examples/LiveProviderProfilesSpec.scala)
exercise actual ReAct execution and reject native failure, wrong values, extra object fields and a
missing hold call. Offline success alone does not verify an endpoint. Require the explicit
`Selected provider profile passed: <Profile>` message as well as successful execution; sbt may return
zero after a ZIOAppDefault application logs a failure. The initial All run on the selected qwen3.8-27b
endpoint on 2026-10-08 failed structured decoding. A direct native-format diagnostic returned stop,
empty visible content and reasoning-only data; Native/All compatibility on that model is not established.
Actual selected runs on the same gateway on 2026-10-08 passed Synthetic and Cancellation with
qwen3.8-27b, and Native with catalog-selected lfm2.5-1.2b-instruct-mlx. Each emitted its explicit
success message after its checks. This is evidence for those selected runs, not All on one model,
universal JSON Schema conformance or HTTP-response cancellation.

## Catalog-driven multi-model verification

`LiveMultiModel` reuses the enabled live environment and adds `WEISGNOSIS_LIVE_PEER_MODEL` for a distinct second chat model. Fetch the authenticated `/api/v1/models` catalog before choosing both identifiers; the application validates them against current chat capability, endpoint and status, and checks `/healthz`. It accepts loaded and cold-startable models, rejects malformed/duplicate catalog entries, and bounds each discovery GET to at most two safe attempts. Credentials remain process-local. Run:

```text
sbt "examples / runMain com.weisgnosis.agents.examples.LiveMultiModel"
```

The runner uses the token/deadline budgets below and schedules scenarios sequentially. It never automatically retries an admitted model turn or tool. Each scenario immediately prints a JSON result: Passed, Failed, Unsupported, Unavailable or NotRun. Require the individual assertions and receipts; process exit alone is not acceptance. Missing peer selection leaves mixed-model/independent-child scenarios NotRun. Cancellation and defects propagate instead of becoming successful reports.

The current actual run on 2026-10-09 selected loaded `qwen3.8-27b` and catalog instruction model `lfm2.5-1.2b-instruct-mlx`, with 4,096 output tokens, a 180-second per-agent budget and one-MiB profile responses. Eleven of fourteen scenarios passed:

| Profile | qwen3.8-27b | lfm2.5-1.2b-instruct-mlx |
| --- | --- | --- |
| Streamed text plus one local-tool continuation | Passed | Failed |
| Exact disposable-file write/read effects and cleanup | Passed | Failed |
| Native structured `{"value":7}` | Failed | Passed |
| Synthetic structured `{"value":7}` | Passed | Passed |
| Joined local-tool cancellation and recovery | Passed | Passed |

| Collaboration/failure scenario | Actual selection | Result |
| --- | --- | --- |
| Three turns through one persistent same-model MsgHub | Qwen/Qwen | Passed |
| Three turns through persistent mixed-model MsgHub | Qwen/LFM | Passed |
| Factory-owned parent/tool/child/parent chain | Qwen parent, LFM child | Passed |
| Definite first-target rejection and explicit ModelTarget fallback | Owned loopback HTTP 503, then Qwen | Passed |

The hub checks exact sender attribution, private canonical histories, delivery receipts and authoritative completion. The child scenario checks one actual dispatch and its exact observation; failover checks one rejected request and no tool replay. Failed profile cells remain failures, not unsupported declarations or successful runs. This matrix does not diagnose their model-level cause or certify every gateway model/native dialect. The paired offline suite exercises the same helpers and deterministic failure/cancellation controls without credentials.

## Verification and acceptance

From the repository root, run `sbt "examples / test"` for offline example contracts and `sbt "examples / compile"` to compile the applications. Run `sbt verify` for repository formatting checks, fatal-warning compilation, and tests. These commands make no live-provider requests. Actual PostgreSQL verification and native alias fixtures have separate [verification prerequisites](../../verification/README.md); skipped fixture scenarios are not backend or platform verification. These examples are not published library artifacts. [Packaged consumer and candidate preparation](../../verification/README.md#packaged-external-consumer) are separate from running an example and do not sign or publish a release. Remaining library and host integration work is tracked in the [repository tracker](../../docs/architecture/tracker.md).

## Live execution budgets

The shared live configuration accepts `WEISGNOSIS_LIVE_MAX_TOKENS` (64–32,768; default 4,096) and `WEISGNOSIS_LIVE_TIMEOUT_SECONDS` (10–1,800; default 300). Invalid values fail before the selected run. Cleanup is joined and may extend beyond the run deadline. Structured/cancellation profiles preserve an explicitly supplied `LlmConfig.maxTokens`; they no longer replace the host's reasoning allowance with 1,024 tokens. Ordinary offline profile callers without a token limit retain the bounded 1,024 fallback. These limits do not guarantee that a particular model follows the requested mechanism.

## Native hosted-provider checks

`NativeProviderSmoke` is disabled unless `WEISGNOSIS_NATIVE_PROVIDER=anthropic` or `gemini` is selected. It also requires the shared `WEISGNOSIS_LIVE_SMOKE=enabled`, explicit native `WEISGNOSIS_LIVE_ENDPOINT`, `WEISGNOSIS_LIVE_MODEL` and process-local `WEISGNOSIS_LIVE_API_KEY`. Anthropic uses the full Messages endpoint; Gemini uses the full selected-model streamGenerateContent endpoint. Run `sbt "examples / runMain com.weisgnosis.agents.examples.NativeProviderSmoke"`.

The selected check explicitly requests provider-hosted search or Python execution and requires authoritative Completed state, an inline successful outcome and no local Tool messages. These requests can incur provider charges. No native credentials or real native-provider run are claimed here; compiled source and loopback suites are separate evidence. See [native contracts and limitations](../core/src/main/scala/com/weisgnosis/agents/core/client/README.md#native-provider-selection).

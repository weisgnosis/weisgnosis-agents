# Host integration examples

This nonpublished SBT project compiles against core, harness, and MCP. It demonstrates existing library APIs rather than adding a supported facade. Start with the complete applications in [Getting started](../../docs/getting-started.md). Use JDK 21 and the repository-pinned sbt version, and run the commands below from the repository root.

The [real-model E2E scenario design](../../docs/live-e2e-scenarios.md) specifies a useful evidence/report task, concurrency and cancellation measurements, and publication limits. It distinguishes executed profiles from unverified model/provider behavior; [current live results](../../docs/live-e2e-results.md) record actual selected attempts.

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

Its static allowlist excludes prompts, reasoning, outputs, metadata, raw tool/model names, and error causes. Runtime correlation IDs are observational. AgentEnd means closure, not success. A slow logger can exhaust the agent's diagnostic budget; the stream then reports delivery failure after owned execution joins. A failing logger abandons the reader and cancels attached execution. See the [stream contract](../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#result-bearing-streams). Hosts own inherited annotations, third-party logs, exporter privacy, retention, and access. ZIO log spans are not a distributed tracing implementation.

[LifecycleLoggingSpec](src/test/scala/com/weisgnosis/agents/examples/LifecycleLoggingSpec.scala) covers correlation, redaction, failure preservation, and cleanup during cancellation; it does not establish a privacy boundary for unrelated host logging.

## Live endpoint verification

[LiveProviderSmoke](src/main/scala/com/weisgnosis/agents/examples/LiveProviderSmoke.scala) is opt-in and sends real requests that may incur charges. Set WEISGNOSIS_LIVE_SMOKE to enabled, WEISGNOSIS_LIVE_ENDPOINT to the full endpoint URL, and WEISGNOSIS_LIVE_MODEL to the selected model identifier. Supply WEISGNOSIS_LIVE_API_KEY only if bearer authentication is required. Keep credentials in the process environment, outside tracked files and command history.

Run `sbt "examples / runMain com.weisgnosis.agents.examples.LiveProviderSmoke"` from the repository root. The program bounds model iterations, output tokens, runtime, and response bytes; it checks addition-tool execution, streamed text, and the exact completed reply. Its logs expose aggregate validation counts. Require the explicit `Live provider smoke passed:` message; a disabled run or a logged application failure is not successful endpoint verification.

Unset `WEISGNOSIS_LIVE_SMOKE` disables external execution; other values besides `enabled` fail configuration. The endpoint must be the complete Chat Completions URL, including `/chat/completions`. The smoke is a narrow endpoint check, not evidence for cancellation, ordinary JSON completion, structured output, media, failover, subagent/MCP chains, or durable recovery. Offline verification does not contact or validate that endpoint.

## HTTP MCP evidence and restored authorization

[LiveMcpEvidence](src/main/scala/com/weisgnosis/agents/examples/LiveMcpEvidence.scala)
uses the enabled live environment and the selected catalog chat model:

```text
sbt "examples / runMain com.weisgnosis.agents.examples.LiveMcpEvidence"
```

A scoped loopback HTTP MCP service supplies BUILD-1, TEST-1 and SEC-1. The model
reads them once and proposes a release report. The host pins the namespace and
logical report path, rejects altered selectors and validates the exact report.
An untrusted instruction in evidence cannot grant write permission.

Ask suspends before any write. The serialized checkpoint is restored into fresh
agents under Deny, actual Plan mode and confirmed Ask. The first two produce
Denied observations and stop through an explicit host result processor; they do
not request another model answer. Approved Direct execution writes exactly one
canonical UTF-8 report with a final LF, without another model request or read replay.
The service stores bytes in a disposable in-memory ledger, not on disk or in a
real repository. Hosts still own authentication, checkpoint persistence and
single-use execution claims.

Acceptance requires `scenarioAccepted=true` and a Passed scenario receipt.
The run admits at most two model requests and has one aggregate live deadline;
joined cleanup can extend that deadline. Discovery and installation schemas are
included in the receipt. Disabled execution and process exit zero are not acceptance.

## Useful coordination and Pipeline controls

With the enabled live environment, run:

```text
sbt "examples / runMain com.weisgnosis.agents.examples.LiveCoordinationEvidence"
```

The runner validates the configured primary model against the current catalog.
Set `WEISGNOSIS_LIVE_PEER_MODEL` for mixed roles in both directions, in addition
to the same-model control. Each room task permits five model requests: an analyst
reads evidence, a checker queues it while busy in a tool, and a reporter produces
the checked blocked-release decision. Explicit flushing must deliver each
attributed observation once; captured receiver requests must keep foreign tool
calls/results inert. Each reporting role proposes the closed `submit_report`
schema. The tool validates the exact decision and checks previously observed
evidence before returning that proposal directly; a guessed same-batch report
cannot complete the task. Exactly one accepted submission per role is required.
Correct decisions and delivery assertions are separate.

The current profile sets temperature zero and explicitly selects the required
tool by advertising one phase-selected function with `ToolChoice.Required`.
Sources may read once, then submit only after
an observed successful read; a missing read fails without retry. The initial
checker wait is selected only from the exact current host instruction. This is
provider request steering, not execution authority or proof of autonomous tool
selection. Ignored steering, premature submissions and incorrect values still fail.

The Pipeline arm permits ten requests. It checks useful sequential handoff,
fanout with at most two active stages and input-ordered results despite a forced
different completion order, joined cancellation, and suspended/interrupted stages
leaving their successors idle. A failed useful sequential decision stops the arm
before the additional lifecycle requests. Each arm has its own aggregate live deadline.
This is process-local coordination, not durable workflow replay. Inspect JSON
receipts and statuses; a zero process exit does not turn a failed receipt into acceptance.

## Oversized evidence and explicit compaction

```text
sbt "examples / runMain com.weisgnosis.agents.examples.LiveContextEvidence"
```

The enabled runner uses at most five model requests under one aggregate deadline.
A fresh fact lies in the middle of an oversized result, outside both previews.
An example-owned single-file store persists actual UTF-8 bytes in a disposable
directory; the model must retrieve the bounded middle slice through `read_file`
using the exact saved path and answer correctly. No general filesystem access
is granted. The fixture checks physical bytes, content hash and the next request's
shortened observation.

The same host assembly explicitly compacts seeded history containing a prior
summary and repeated tool IDs. A real tool-free summary request and subsequent
answer are checked separately from occurrence pairing and retained provenance.
The existing assembly retains its one-minute per-turn limit; the aggregate live
deadline also includes summarization and joined fixture cleanup. Offline controls
cover storage failure and serialized provenance. This does not measure unlimited
memory, general semantic recall or the local store's concurrent publication contract.

## Active HTTP request cancellation

```text
sbt "examples / runMain com.weisgnosis.agents.examples.LiveRequestBoundary"
```

The enabled runner forwards one complete real SSE frame through a borrowed HTTP
client and withholds later frames. Cancellation starts only after a parsed runtime
chunk arrives. A held request finalizer proves the consumer remains pending until
cleanup is released and joined; local tool dispatch must remain zero. The fixture
also checks recovery and any incomplete received call arguments. SSE framing is
normalized, so this is instrumented protocol forwarding rather than a byte-transparent proxy.

Separate receipts label malformed/truncated response and token-exhaustion controls
as `offline-fixture`. A definite loopback rejection followed by the selected real
model reuses the existing failover scenario. Active-request cancellation permits
one request under an aggregate deadline; these checks do not prove that the remote
backend stops inference or authorize replay after an ambiguous failure.

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
Typed scenario failures emit a Failed/Unavailable JSON receipt with a fixed diagnostic category
and, for model failures, HTTP status; messages and response bodies are omitted. Inspect the
receipt status: normal process completion is not acceptance. Configuration, acquisition,
interruption and defects still fail the process.

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

The [current live results](../../docs/live-e2e-results.md) distinguish the 2026-10-15 packaged refresh from other dated profiles. All twenty selected stream/tool/runtime/room/Pipeline outcomes passed on Qwen and reasoning-LFM, including both interruption modes and same-runtime continuation. Mixed rooms ran twice per direction; other cells ran once. Prior native/synthetic failures remain explicit and were not rerun. These checks do not certify every model profile or superiority over another language.

## Verification and acceptance

[SyntheticRuntimeComparison](src/main/scala/com/weisgnosis/agents/examples/SyntheticRuntimeComparison.scala)
uses a scripted model through the public ReAct runtime to verify partial batch
cancellation, repeated interruption, joined cleanup and same-runtime continuation.
Run `sbt "examples / runMain com.weisgnosis.agents.examples.SyntheticRuntimeComparison"`
without live configuration. Require all nine JSON contract fields to be true.
The [matched Python comparison](../../docs/synthetic-runtime-comparison.md) explains
the corresponding TaskGroup reference and actual result; this synthetic fixture
does not itself close live E3. The actual
[LiveRuntimeComparison](src/main/scala/com/weisgnosis/agents/examples/LiveRuntimeComparison.scala)
now compares useful tasks and repeated parent interruption against real Python
AgentScope on a common gateway. Run
`sbt "examples / runMain com.weisgnosis.agents.examples.LiveRuntimeComparison parallel"`
with the enabled environment, or select `text`, `sequential` or `cancel`.
[Results and reproduction](../../docs/live-runtime-comparison.md) distinguish
ordinary turn timing from local scheduling, cancellation and continuation.
Run `sbt "examples / runMain com.weisgnosis.agents.examples.LiveRuntimeComparison cooperative-cancel"`
with the enabled environment to check E3 separately. It signals `runtime.interrupt`
after one local result is committed while its sibling is still active, holds cleanup,
then requires an Interrupted result and successful same-runtime continuation with
unchanged prior observations and execution counts. This arm passed once on each
selected Qwen/reasoning-LFM model; [current receipts](../../docs/live-e2e-results.md#e3-cooperative-interruption-and-same-runtime-continuation)
state the exact assertions. It is a Scala runtime check, not a new Python comparison.
Parent `cancel` still checks an interrupted Exit with repeated fiber interruption;
`interruptionPreserved` validates the selected interruption contract in each receipt.

[LiveParallelTools](src/main/scala/com/weisgnosis/agents/examples/LiveParallelTools.scala)
runs the same real-model two-read task in Sequential and Parallel modes. With the
enabled live environment, run
`sbt "examples / runMain com.weisgnosis.agents.examples.LiveParallelTools"`.
Require `Live parallel tools passed: sequential peak=1, parallel peak=2; exact sum=37`.
The host checks persisted calls, actual overlapping local entry, exact tool
observations, joined cleanup and the completed sum. The entry watchdog is ten
seconds after batch admission; each agent retains the shared run budget. A model
that does not issue the instructed single two-call batch fails the profile without
exercising its concurrency oracle. This demonstrates local overlap, not inference
speedup. LiveMultiModel appends this primary-model profile as a fifteenth receipt.

From the repository root, run `sbt "examples / test"` for offline example contracts and `sbt "examples / compile"` to compile the applications. Run `sbt verify` for repository formatting checks, fatal-warning compilation, and tests. These commands make no live-provider requests. Actual PostgreSQL verification and native alias fixtures have separate [verification prerequisites](../../verification/README.md); skipped fixture scenarios are not backend or platform verification. These examples are not published library artifacts. [Packaged consumer and candidate preparation](../../verification/README.md#packaged-external-consumer) are separate from running an example and do not sign or publish a release. Remaining library and host integration work is tracked in the [repository tracker](../../docs/architecture/tracker.md).

## Live execution budgets

The shared live configuration accepts `WEISGNOSIS_LIVE_MAX_TOKENS` (64–32,768; default 4,096) and `WEISGNOSIS_LIVE_TIMEOUT_SECONDS` (10–1,800; default 300). Invalid values fail before the selected run. Cleanup is joined and may extend beyond the run deadline. Structured/cancellation profiles preserve an explicitly supplied `LlmConfig.maxTokens`; they no longer replace the host's reasoning allowance with 1,024 tokens. Ordinary offline profile callers without a token limit retain the bounded 1,024 fallback. These limits do not guarantee that a particular model follows the requested mechanism.

## Native hosted-provider checks

`NativeProviderSmoke` is disabled unless `WEISGNOSIS_NATIVE_PROVIDER=anthropic` or `gemini` is selected. It also requires the shared `WEISGNOSIS_LIVE_SMOKE=enabled`, explicit native `WEISGNOSIS_LIVE_ENDPOINT`, `WEISGNOSIS_LIVE_MODEL` and process-local `WEISGNOSIS_LIVE_API_KEY`. Anthropic uses the full Messages endpoint; Gemini uses the full selected-model streamGenerateContent endpoint. Run `sbt "examples / runMain com.weisgnosis.agents.examples.NativeProviderSmoke"`.

The selected check explicitly requests provider-hosted search or Python execution and requires authoritative Completed state, an inline successful outcome and no local Tool messages. These requests can incur provider charges. No native credentials or real native-provider run are claimed here; compiled source and loopback suites are separate evidence. See [native contracts and limitations](../core/src/main/scala/com/weisgnosis/agents/core/client/README.md#native-provider-selection).

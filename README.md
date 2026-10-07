# weisgnosis-agents

Compatible streamed cache counters now preserve direct cache-hit precedence and cache-write usage through the [reviewed decoder contract](modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#cache-usage-decoding). The [current delivery gates](docs/architecture/tracker.md#current-delivery-gates) distinguish accepted scope from unfinished work.

Ordinary before-acting suspension, explicit resume and resumed event streams with checked supplied results and data-only checkpoint restoration are implemented and independently reviewed; see the [runtime contract](modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#before-acting-suspension-and-restoration). Complete/partial checkpoint confirmations and nonblocking policy admission are accepted; generic checkpoint event correlation is accepted. Host checkpoint authentication, storage and exclusive claims remain external.

Pure functional agent runtime components for Scala 3.9.0 and ZIO 2.1.26. The project ports selected agent mechanics from [AgentScope Java](https://github.com/agentscope-ai/agentscope-java) under Apache-2.0.

This repository is in development. Current SNAPSHOT artifacts require host recompilation according to package migration contracts; no backward binary-compatibility baseline or blanket RC API freeze is claimed. The [public API and migration boundary](project/README.md#compatibility-policy) and [external packaged consumer](verification/artifact-consumer/README.md) distinguish current consumability from release compatibility.

The [implementation tracker](docs/architecture/tracker.md) distinguishes available components from pending integration; this is not a completed production distribution.

Implementation is active. Core provides scoped ReAct execution, checked optional topP/frequency/presence/seed settings and host tool-choice and typed parallel-call steering, tool policies with opt-in ordinary return-direct completion, provisional tool events with authoritative final results, optional final-answer presentation filtering and complete-batch denial observations, run-owned groups, preloaded skills and exact deferred-resource capabilities. The harness adds bounded local loading, reusable child sessions, a retained terminal-outcome inbox, ordered child events and opt-in child Plan tools/context with independent review. MCP provides owned stdio/HTTP clients, bounded cursor recovery and explicit host replacement after typed expiry. The [tracker](docs/architecture/tracker.md#current-acceptance) and linked package contracts record exact acceptance and remaining limits. Standard tool messages remain text-only; the tracker records the next provider integration work. Provider-owned calls without inline results continue reasoning without local dispatch or recovery; the [runtime contract](modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#provider-owned-pending-calls) defines the bounded support.

## Implemented components

- **core:** immutable messages, 39 typed event variants, JSON codecs, typed tools with completed observation payloads, path screening, SSE parsing and chunk assembly, and ReAct execution with interruption recovery, bounded plaintext/opaque thinking retries, explicit encrypted/text/summary reasoning replay, structured output and result-bearing event streams with ordered single-consumer delivery and joined cleanup.
- **harness:** daily-memory extraction, scoped local storage, atomic in-memory consolidation, and recoverable local consolidation with explicit conservative legacy-watermark migration, typed transcript observations with in-memory and scoped local stores, opt-in completion recording and bounded host-controlled discovery for maintenance, typed memory triggers with shared monotonic admission, a scoped task queue with pending conversation coalescing and completion-triggered memory flush, conversation compaction, opt-in runtime result eviction and local storage, immutable permission decisions, interactive confirmation requests, and serializable durable approval data.
- **mcp:** checked 2025-06-18 payloads, bounded request correlation, scoped RPC ownership, direct-process stdio and bounded HTTP JSON/SSE body framing. The initialized client adds capability-checked listing/calls, bounded discovery and joined deadlines. Manual tool binding and the scoped manager preserve host authority, typed observations and resource ownership. A scoped HTTP POST and explicit GET adapter supports JSON/SSE responses, session headers, bounded opt-in continuation after clean SSE EOF or narrowly classified connection loss, bounded listener resumption and explicit session termination; typed expiry supports explicit host-owned fresh registration; automatic POST replay and cursorless reconnect are intentionally excluded.

LlmClient has scripted test clients and a scoped text/user-media/function HTTP implementation through ChatCompletionsClient, including explicit inline image/audio Data blocks and image Data URLs with exact MIME hints. An explicit host format couples Google tool-signature decoding and historical replay. It publishes correlated incremental model events and requires validated stream completion before tool dispatch. Explicit bounded HTTP-status retries preserve one logical model invocation and close every attempt before retrying. Runtime-owned selection of one compatible fallback model preserves the active request plan and assigns fresh target identity. Selected-model request projection can change transient history and checked temperature/maxTokens/topP/frequencyPenalty/presencePenalty and exact optional seed while preserving tool and client authority; both independent reviews and required verification passed. Harness components are not automatically installed into ReActAgent. Durable approval resumption, broader middleware integration, remote memory/transcript storage and durable child checkpoint storage/claims remain separate work. Host-held sessions support process-local child reuse and [explicit resume](modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#host-only-session-resume) of their current checkpoint. [Host checkpoint import](modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#host-checkpoint-import) can acquire a fresh child from supplied suspended data under current factory authority, with independent approval and full verification.

## Build and verification

Use JDK 21 and the sbt version pinned in project/build.properties.

```text
sbt scalafmtAll scalafmtSbt
sbt verify packageArtifacts
```

The verify alias checks formatting, compiles with fatal warnings, and runs all tests. See [CONTRIBUTING.md](CONTRIBUTING.md) for semantic review and documentation requirements.

Toolkit host construction uses a [private canonical registry and checked map migration](modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#checked-registry-identity); unchecked map/copy/Product construction is removed.

## Host integration

The nonpublished [examples project](modules/examples/README.md) verifies scoped core/harness/MCP assembly using the existing builder and explicit host dependencies. It also supplies a bounded, explicitly enabled live-provider smoke program and payload-free lifecycle logging through existing ZIO APIs. Hosts configure logging/export and independent log privacy; default verification makes no external provider requests.

## Minimal local example

This example uses a scripted client and performs no provider request. Build from this repository; [local artifact packaging](project/README.md#local-artifact-packaging) is independently reviewed, including separate archive and isolated-consumer validation. Snapshot artifacts have not been externally published. The [first-RC API candidate](project/README.md#first-rc-api-candidate) distinguishes the inspected public contracts from internal helpers and unfinished release validation.

```scala
package com.weisgnosis.agents.examples

import com.weisgnosis.agents.core.agent.ReActAgent
import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.model.Msg
import zio.*

object SimpleAgentApp extends ZIOAppDefault:
  def run: ZIO[Any, Throwable, Unit] =
    ZIO.scoped:
      for
        client <- LlmClient.mock(Chunk(TurnOutput.FinalText(Msg.assistant("Hello"))))
        agent <- ReActAgent.make(client = client)
        result <- agent.run(Msg.user("Hello"))
        _ <- Console.printLine(result.reply.text)
      yield ()
```

For HTTP completion, obtain a zio-http Client from the host layer, pass it to ChatCompletionsClient.make, and supply that client plus LlmConfig to ReActAgent.make. The host selects endpoint, credentials, connection settings, and model compatibility; see the [client contracts](modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md).

Model transports implement LlmClient.streamTurn with an explicit ModelStreamContext. The agent owns request lifetime and conversation state. Filesystem stores and approval workflows must honor their documented trust and persistence contracts.

## Documentation

- [Architecture and delivery boundary](ARCHITECTURE.md)
- [Documentation index](docs/README.md)
- [Module index](modules/README.md)
- [Core API and harness implementation notes](docs/architecture/phase-4-implementation.md)
- [Engineering standards](AGENTS.md)

All repository documentation, comments, identifiers, commit messages, and PR descriptions must be English. Use protocol-level interfaces; retain upstream names for attribution and traceability.

## License

[Apache-2.0](LICENSE).

ChatCompletions/MCP, structured closing text and local transcript outputs use exact numeric rendering. Compactor provenance/previews and checked factory tool outputs also preserve numeric values. Hosts can opt into ExactJson.encode for checkpoint/event serialization; external dependency codecs and legacy direct tool encoding are not globally replaced. The [JSON contract](modules/core/src/main/scala/com/weisgnosis/agents/core/json/README.md) records the scope.

## Current acceptance

- [x] `path_review` and `current_docs` approved [run-owned model-facing group activation](modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#run-owned-tool-group-activation), with fresh invocation state, snapshot-consistent descriptions/reset notes and shared structured fallback. Exact source/spec identities and full verification are recorded in the linked contract.

- [x] Both independent reviewers approved the [preloaded skill catalogue](modules/core/src/main/scala/com/weisgnosis/agents/core/skill/README.md) and [runtime loader](modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#preloaded-skills); scoped identities, limitations and verification are recorded there.
- [x] Both reviewers approved [host acting-stop decisions](modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#acting-stop-decisions); full verification passed. HITL and durable phase resumption remain separate.
- [x] Both independent reviewers approved [MCP grouped installation](modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/client/README.md#grouped-installation), including coherent snapshots, rollback/removal and actual ReAct activation. The linked contract records current identities and verification.
- [x] `path_review` and `current_docs` approved [immutable host-selected tool groups](modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#host-selected-tool-groups), including pure scoped notes and actual ReAct snapshot visibility and dispatch, with no findings. Exact two-file identities and full verification are recorded in the linked contract; run-owned activation and MCP grouped installation have separate scoped acceptance.

- [x] `path_review` and `current_docs` approved [core fixed arguments and runtime authorization](modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#fixed-raw-arguments), including the reproduced interrupted-authorizer cleanup correction. Scoped identities and full verification are recorded in the linked contract; MCP installation has its own [binding contract](modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/tool/README.md#fixed-arguments).

- [x] `path_review` and `current_docs` approved [bounded MCP request correlation](modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/client/README.md#verification-and-acceptance), including cancellation, scoped registration and full-cause settlement. The connection borrows its host-owned transport.

- [x] `path_review` and `current_docs` approved the [checked MCP envelope](modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/protocol/README.md#verification-and-acceptance), and 2025-06-18 initialization/discovery helpers and call requests, including bounded numeric conversion and malformed-wire rejection. Call-result projection is also reviewed; broader revision support remains pending; manual Toolkit binding has its own scoped acceptance.

- [x] `path_review` approved advisory PathSafety, configured factories, static filtering, registry/polling and task tools; `current_docs` approved live denial and plan inheritance. Both approved retained resource ownership, host-held sessions, bounded host session membership, fixed-session tools, directory-routed tools and policy-aware child construction, with no findings. Exact scope and reviewed identities are in the [tool](modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#current-review-acceptance), [subagent](modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#verification-and-acceptance) and [plan](modules/harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md) contracts.
- [x] `path_review` and `current_docs` approved [scoped permission composition and shared authorizer views](modules/harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#current-review-acceptance), including confirmed-input rechecks. The opt-in SubagentConstruction path installs inherited policy; raw factories remain host-controlled.
- [x] `path_review` and `current_docs` approved [borrowed-port RPC ownership](modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/client/README.md#verification-and-acceptance) with no findings; seven focused connection groups pass.
- [x] Both independent reviewers approved typed tool observations and compound cancellation causes; full repository verification passed and the [tracker](docs/architecture/tracker.md#current-acceptance) records current evidence.
- [x] Independent reviewers approved bounded framing and direct-process stdio. Seven framing/process groups pass; the [transport contract](modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/transport/README.md) records exact identities and scope.
- [x] `path_review` and `current_docs` approved the initialized 2025-06-18 client, checked request-builder overload and typed remote errors with no findings. See the [client acceptance](modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/client/README.md#verification-and-acceptance).
- [x] Independently review inline declarations, bounded Markdown/YAML parsing, the general-purpose preset and scoped named factory routing; the [subagent acceptance](modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#verification-and-acceptance) records reviewer identities, exact revisions and verification.
- [x] Load bounded local declarations from an explicit host-controlled directory; see the [loader contract](modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#local-declaration-loading).
- [x] Install finite named reusable sessions with one runtime/history per entry and joined aggregate ownership; see the [session catalog contract](modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#named-session-catalog).
- [x] Route send/task/cancel to exact host-installed session names with retained history and invocation-scoped forwarding; see the [named tool contract](modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#named-session-tools).
- [x] Independently review bounded optional MCP outbound cancellation; [client acceptance](modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/client/README.md#outbound-cancellation-contract) records exact scope and evidence.
- [ ] Complete durable host recovery.

Approval applies to the recorded scope and revisions, not exhaustive upstream parity. Other accepted components retain their package-level evidence.

- [x] `path_review` and `current_docs` approved [MCP metadata propagation, checked presets and descriptive output schemas](modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/tool/README.md#verification-and-acceptance); current identities and full `mcp-fixed-arguments-verify.log` verification are recorded in the package contracts.
- [x] Both independent reviewers approved and full verification passed for [manual MCP tool binding](modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/tool/README.md#verification-and-acceptance).
- [x] Both independent reviewers approved the bounded scoped [MCP manager](modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/client/README.md#scoped-manager-contract); registration uses explicit host definitions and owns acquired resources. Required verification passed; the client acceptance records exact identities and evidence.
- [x] Both independent reviewers approved bounded [HTTP body framing](modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/transport/README.md#http-body-framing), and full verification passed. The separately reviewed HTTP POST and explicit GET adapter adds scoped requests and session-header handling; its transport contract records the bounded acceptance.

## Integration boundaries

Tool.encodeResultChecked preserves custom typed projections and gives Tool.fromFunction exact JSON runtime output after joined cleanup. Runtime call identity and control metadata remain protected. ToolExecutionResult constructor/copy/unapply/JSON now use payload/state; its String constructors and output text projection remain. See the [tool migration](modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#typed-execution-observations). Media survives history/events, but the current ChatCompletions request codec still rejects non-text tool output.

Local execution supports an optional scoped ToolAuthorizer with runtime-owned call identity, approved-input persistence and fail-closed denial observations. The default allows calls; InteractivePermissionAuthorizer installs harness policy and in-memory confirmation. CheckpointPermissionAuthorizer maps policy Ask/Passthrough to deferred checkpoint work without a waiting Promise. Durable resumption requires host persistence, authentication, checkpoints and idempotency.

[Memory maintenance](modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md) supports validated configuration, scoped consolidation, acknowledged daily archival and explicitly selected transcript pruning under one owned job. Remote storage and remote session discovery remain pending; bounded local discovery is available; local backends require host-controlled filesystem ancestors.

[Plan mode](modules/harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md) provides conservative static command inspection, opt-in runtime admission, exact-draft review, request-only context, cooperative local draft storage and strict enter/write/exit tools. Reopened text remains unapproved. Exact-ticket host review is mandatory for exit; location metadata grants no filesystem or approval authority. Typed directories select existing strict workspace descendants. A host-installed inherited gate applies Plan restrictions when either parent or child is in Plan, without copying approval state. Opt-in SubagentConstruction installation supplies child-local controls and inherited request context across fresh and reused children, with independent child review and normal tool-group visibility. Fourteen Plan methods have bounded adaptations; raw host factories and durable approval restoration remain outside that scope.

[Task supervision](modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md) owns scoped tasks, parent cancellation leases and same-execution promotion after a 30-second wait per invocation. The host retains the owner Scope for background work. Runtime-owned lineage enforces descendant depth <=3. Configured factories build fresh children with scoped resources and final filtering of inherited and registered tools; the injected authorizer remains effective. A bounded scoped registry retains full results and supports inspection, waiting, promotion, cancellation and completed-record removal. Opt-in parent-denial inheritance rereads current policy before child authorization and against approved arguments without copying child approval authority. Opt-in `agent_spawn`, `agent_task` and `agent_cancel` expose this fixed capability with strict inputs and bounded observations. A checked host policy can instead require synchronous waiting, with joined timeout cancellation and no promotion; the default remains unchanged. `TaskSupervisor.own` retains typed resources across worker turns and joins nested workers before releasing them; caller and supervisor scopes bound the lifetime. `SubagentSession.open` retains one configured child runtime and lineage; host `send` and `resume` calls serialize turns with separate execution IDs and preserve live injected authorization. `SubagentTools.forSession` exposes `agent_send`, `agent_task` and `agent_cancel` for one host-selected session, with no model-selected session ID. `SubagentSessionDirectory` supplies bounded host registration and lookup of acquired sessions, with joined removal/shutdown and unchanged original lifetime bounds. `SubagentTools.forDirectory` routes send/task/cancel through registered session IDs while preserving the same bounded observations and scoped execution. `SubagentConstruction` supplies an opt-in factory path carrying static boundaries and live ancestor Deny/Plan checks into nested children while preserving their confirmation authority. Inline declarations and the general-purpose preset compose through this path. A scoped host factory catalog adds named fresh-child spawn/task/cancel routing with independent retained-task limits. Spawn/send forwards nested child events with runtime execution IDs through the current invocation lease; promotion closes publication while supervised execution continues. Read-only local declaration loading validates data without installing capabilities. A finite host-selected session catalog eagerly creates reusable named runtimes under one resource owner; opt-in send/task/cancel tools route to these exact names. Durable reuse remains unfinished.

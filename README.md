# weisgnosis-agents

Pure functional agent runtime components for Scala 3.9.0 and ZIO 2.1.26. The project ports selected agent mechanics from [AgentScope Java](https://github.com/agentscope-ai/agentscope-java) under Apache-2.0.

This repository is in development. The [implementation tracker](docs/architecture/tracker.md) distinguishes available components from pending integration; this is not a completed production distribution.

Implementation is active. Current MCP scope includes owned clients and registration, HTTP POST/GET, explicit metadata propagation and one independent SDK interoperability check. Scoped subagent composition, reusable sessions and child event forwarding are also available. The [tracker](docs/architecture/tracker.md#current-acceptance) and linked package contracts record exact acceptance and limitations. Checked host presets, input-schema exclusion and host-selected descriptive output schemas are available. Next is the distinct framework-enforced registered-preset contract; broader interoperability and durable host recovery remain unfinished.

## Implemented components

- **core:** immutable messages, 39 typed event variants, JSON codecs, typed tools with completed observation payloads, path screening, SSE parsing and chunk assembly, and ReAct execution with interruption recovery, bounded thinking retries, structured output and result-bearing event streams.
- **harness:** daily-memory extraction, scoped local storage, atomic in-memory consolidation, and recoverable local consolidation, typed transcript observations with in-memory and scoped local stores and opt-in completion recording, typed memory triggers with shared monotonic admission, a scoped task queue with pending conversation coalescing and completion-triggered memory flush, conversation compaction, opt-in runtime result eviction and local storage, immutable permission decisions, interactive confirmation requests, and serializable durable approval data.
- **mcp:** checked 2025-06-18 payloads, bounded request correlation, scoped RPC ownership, direct-process stdio and bounded HTTP JSON/SSE body framing. The initialized client adds capability-checked listing/calls, bounded discovery and joined deadlines. Manual tool binding and the scoped manager preserve host authority, typed observations and resource ownership. A scoped HTTP POST and explicit GET adapter supports JSON/SSE responses, session headers, bounded opt-in continuation after clean SSE EOF or narrowly classified connection loss, bounded listener resumption and explicit session termination; general I/O recovery and automatic recovery remain separate.

LlmClient has scripted test clients and a scoped text/function HTTP implementation through ChatCompletionsClient. It publishes correlated incremental model events and requires validated stream completion before tool dispatch. Harness components are not automatically installed into ReActAgent. Durable approval resumption, broader middleware integration, remote memory/transcript storage and durable child restoration remain separate work. Host-held sessions support process-local child reuse.

## Build and verification

Use JDK 21 and the sbt version pinned in project/build.properties.

```text
sbt scalafmtAll scalafmtSbt
sbt verify
```

The verify alias checks formatting, compiles with fatal warnings, and runs all tests. See [CONTRIBUTING.md](CONTRIBUTING.md) for semantic review and documentation requirements.

## Minimal local example

This example uses a scripted client and performs no provider request. Build from this repository; publication of snapshot artifacts is not guaranteed.

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

## Current acceptance

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

- [x] `path_review` and `current_docs` approved [MCP metadata propagation, checked presets and descriptive output schemas](modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/tool/README.md#verification-and-acceptance); current identities and full `tool-output-schema-verify.log` verification are recorded in the package contracts.
- [x] Both independent reviewers approved and full verification passed for [manual MCP tool binding](modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/tool/README.md#verification-and-acceptance).
- [x] Both independent reviewers approved the bounded scoped [MCP manager](modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/client/README.md#scoped-manager-contract); registration uses explicit host definitions and owns acquired resources. Required verification passed; the client acceptance records exact identities and evidence.
- [x] Both independent reviewers approved bounded [HTTP body framing](modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/transport/README.md#http-body-framing), and full verification passed. The separately reviewed HTTP POST and explicit GET adapter adds scoped requests and session-header handling; its transport contract records the bounded acceptance.

## Integration boundaries

Tool.encodeResult supports typed completed Success/Error observations while retaining existing String tools. Runtime call identity and control metadata remain protected. ToolExecutionResult constructor/copy/unapply/JSON now use payload/state; its String constructors and output text projection remain. See the [tool migration](modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#typed-execution-observations). Media survives history/events, but the current ChatCompletions request codec still rejects non-text tool output.

Local execution supports an optional scoped ToolAuthorizer with runtime-owned call identity, approved-input persistence and fail-closed denial observations. The default allows calls; InteractivePermissionAuthorizer installs harness policy and in-memory confirmation. Durable resumption requires host persistence, authentication, checkpoints and idempotency.

[Memory maintenance](modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md) supports validated configuration, scoped consolidation, acknowledged daily archival and explicitly selected transcript pruning under one owned job. Remote storage and session discovery remain pending; local backends require host-controlled filesystem ancestors.

[Plan mode](modules/harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md) provides conservative static command inspection, opt-in runtime admission, exact-draft review, request-only context, cooperative local draft storage and strict enter/write/exit tools. Reopened text remains unapproved. Exact-ticket host review is mandatory for exit; location metadata grants no filesystem or approval authority. Typed directories select existing strict workspace descendants. A host-installed inherited gate applies Plan restrictions when either parent or child is in Plan, without copying approval state. Automatic nested/reused-child installation and remaining plan adaptations are pending.

[Task supervision](modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md) owns scoped tasks, parent cancellation leases and same-execution promotion after a 30-second wait per invocation. The host retains the owner Scope for background work. Runtime-owned lineage enforces descendant depth <=3. Configured factories build fresh children with scoped resources and final filtering of inherited and registered tools; the injected authorizer remains effective. A bounded scoped registry retains full results and supports inspection, waiting, promotion, cancellation and completed-record removal. Opt-in parent-denial inheritance rereads current policy before child authorization and against approved arguments without copying child approval authority. Opt-in `agent_spawn`, `agent_task` and `agent_cancel` expose this fixed capability with strict inputs and bounded observations. `TaskSupervisor.own` retains typed resources across worker turns and joins nested workers before releasing them; caller and supervisor scopes bound the lifetime. `SubagentSession.open` retains one configured child runtime and lineage; host `send` calls serialize turns with separate execution IDs and preserve live injected authorization. `SubagentTools.forSession` exposes `agent_send`, `agent_task` and `agent_cancel` for one host-selected session, with no model-selected session ID. `SubagentSessionDirectory` supplies bounded host registration and lookup of acquired sessions, with joined removal/shutdown and unchanged original lifetime bounds. `SubagentTools.forDirectory` routes send/task/cancel through registered session IDs while preserving the same bounded observations and scoped execution. `SubagentConstruction` supplies an opt-in factory path carrying static boundaries and live ancestor Deny/Plan checks into nested children while preserving their confirmation authority. Inline declarations and the general-purpose preset compose through this path. A scoped host factory catalog adds named fresh-child spawn/task/cancel routing with independent retained-task limits. Spawn/send forwards nested child events with runtime execution IDs through the current invocation lease; promotion closes publication while supervised execution continues. Read-only local declaration loading validates data without installing capabilities. A finite host-selected session catalog eagerly creates reusable named runtimes under one resource owner; opt-in send/task/cancel tools route to these exact names. Durable reuse remains unfinished.

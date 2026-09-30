# weisgnosis-agents

Pure functional agent runtime components for Scala 3.9.0 and ZIO 2.1.26. The project ports selected agent mechanics from [AgentScope Java](https://github.com/agentscope-ai/agentscope-java) under Apache-2.0.

This repository is in development. The [implementation tracker](docs/architecture/tracker.md) distinguishes available components from pending integration; this is not a completed production distribution.

Implementation is active. The [immediate next work](docs/architecture/tracker.md#immediate-next-work) is child sessions, reuse and send, built on the accepted supervisor-owned resource primitive.

## Implemented components

- **core:** immutable messages, 39 typed event variants, JSON codecs, typed tools, path screening, SSE parsing and chunk assembly, and ReAct execution with interruption recovery, bounded thinking retries, and structured output.
- **harness:** daily-memory extraction, scoped local storage, atomic in-memory consolidation, and recoverable local consolidation, typed transcript observations with in-memory and scoped local stores and opt-in completion recording, typed memory triggers with shared monotonic admission, a scoped task queue with pending conversation coalescing and completion-triggered memory flush, conversation compaction, opt-in runtime result eviction and local storage, immutable permission decisions, interactive confirmation requests, and serializable durable approval data.
- **mcp:** architecture and package scaffolding only.

LlmClient has scripted test clients and a scoped text/function HTTP implementation through ChatCompletionsClient. It publishes correlated incremental model events and requires validated stream completion before tool dispatch. Harness components are not automatically installed into ReActAgent. Durable approval resumption, broader middleware integration, remote memory/transcript storage, automatic nested/reused-child policy installation and persistent child reuse remain separate work.

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

- [x] Advisory PathSafety screening, configured child construction, bounded task registry/polling and model-facing task tools are independently approved by `path_review`; live parent-denial inheritance and the inherited plan gate are approved by `current_docs`, covering FP/ZIO correctness and relevant invariants. Exact reviewed source/test identities and limits are in the [tool contract](modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#current-review-acceptance), [subagent contract](modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#verification-and-acceptance) and [plan contract](modules/harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md).
- [x] `scalafmtAll scalafmtSbt verify` passed on 2026-09-30: 561 core / 631 harness tests (1,192 total), zero compiler warnings or failures. Local evidence: `resource-owner-verify.log`.
- [x] `path_review` and `current_docs` independently approved supervisor-owned resource lifetimes, initialization failure exits, joined concurrent closure and nested cleanup ordering, with no findings at the exact four source/test identities in the subagent acceptance record.
- [ ] Complete child-session reuse/send and automatic nested policy installation; the [tracker](docs/architecture/tracker.md) records remaining integration.

Approval covers the named source/test revisions and documented contracts. Existing accepted components retain their package-level evidence; passing verification does not establish exhaustive upstream parity.

## Integration boundaries

Local execution supports an optional scoped ToolAuthorizer with runtime-owned call identity, approved-input persistence and fail-closed denial observations. The default allows calls; InteractivePermissionAuthorizer installs harness policy and in-memory confirmation. Durable resumption requires host persistence, authentication, checkpoints and idempotency.

[Memory maintenance](modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md) supports validated configuration, scoped consolidation, acknowledged daily archival and explicitly selected transcript pruning under one owned job. Remote storage and session discovery remain pending; local backends require host-controlled filesystem ancestors.

[Plan mode](modules/harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md) provides conservative static command inspection, opt-in runtime admission, exact-draft review, request-only context, cooperative local draft storage and strict enter/write/exit tools. Reopened text remains unapproved. Exact-ticket host review is mandatory for exit; location metadata grants no filesystem or approval authority. Typed directories select existing strict workspace descendants. A host-installed inherited gate applies Plan restrictions when either parent or child is in Plan, without copying approval state. Automatic nested/reused-child installation and remaining plan adaptations are pending.

[Task supervision](modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md) owns scoped tasks, parent cancellation leases and same-execution promotion after a 30-second wait per invocation. The host retains the owner Scope for background work. Runtime-owned lineage enforces descendant depth <=3. Configured factories build fresh children with scoped resources and final filtering of inherited and registered tools; the injected authorizer remains effective. A bounded scoped registry retains full results and supports inspection, waiting, promotion, cancellation and completed-record removal. Opt-in parent-denial inheritance rereads current policy before child authorization and against approved arguments without copying child approval authority. Opt-in `agent_spawn`, `agent_task` and `agent_cancel` expose this fixed capability with strict inputs and bounded observations. `TaskSupervisor.own` retains typed resources across worker turns and joins nested workers before releasing them; caller and supervisor scopes bound the lifetime. Event forwarding, child sessions, reuse and send remain unfinished.

# weisgnosis-agents-core

Scala 3/ZIO 2 agent components with immutable models and effectful lifecycle management.

| Package | Available |
| --- | --- |
| [model](src/main/scala/com/weisgnosis/agents/core/model/README.md) | Msg, content, roles, usage, codecs |
| [event](src/main/scala/com/weisgnosis/agents/core/event/README.md) | 31 typed variants and source tagging; not all emitters are implemented |
| [tool](src/main/scala/com/weisgnosis/agents/core/tool/README.md) | Typed tools, immutable registry, normalized observations, path screening |
| [client](src/main/scala/com/weisgnosis/agents/core/client/README.md) | Client port, scripted clients, SSE framing and indexed assembly |
| [agent](src/main/scala/com/weisgnosis/agents/core/agent/README.md) | ReAct lifecycle, interruption, thinking retries, structured output |

Typed failures are defined in [AgentError.scala](src/main/scala/com/weisgnosis/agents/core/error/AgentError.scala). Live HTTP transport, automatic failover, and exhaustive upstream parity remain pending.

ReActAgent retains in-memory history and serializes runs. Resources are scoped per run/request; cancellation reconciles unfinished calls without overwriting completed observations. See [architecture](../../ARCHITECTURE.md) and the [parity audit](../../docs/architecture/test-parity.md).

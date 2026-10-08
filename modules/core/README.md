# Agent runtime

Core provides immutable messages and tool observations, typed tools and authorization ports, scoped model clients, and the ReAct runtime. It has no dependency on harness, MCP, or PostgreSQL.

## Start here

Follow the runnable applications in [Getting started](../../docs/getting-started.md). The [host assembly example](../examples/src/main/scala/com/weisgnosis/agents/examples/HostAssembly.scala) shows how to compose the existing builder with harness and MCP dependencies. Development artifact installation is described in the [build guide](../../project/README.md#local-artifact-packaging).

Use [ReActAgent](src/main/scala/com/weisgnosis/agents/core/agent/ReActAgent.scala) with an explicit client, toolkit, and authorization policy. Its [runtime contract](src/main/scala/com/weisgnosis/agents/core/agent/README.md) explains streaming, interruption, structured output, suspension, and checked restoration. The host installs optional history preparation, result processing, request projection, and completion observers.

## Components

| Package | Purpose |
| --- | --- |
| [model](src/main/scala/com/weisgnosis/agents/core/model/README.md) | Messages, content blocks, usage, and codecs |
| [tool](src/main/scala/com/weisgnosis/agents/core/tool/README.md) | Checked bindings, schema admission, authorization, and typed execution observations |
| [client](src/main/scala/com/weisgnosis/agents/core/client/README.md) | Model ports, Chat Completions requests, HTTP/SSE ownership, selected provider profiles, retries, and failover boundaries |
| [agent](src/main/scala/com/weisgnosis/agents/core/agent/README.md) | Serialized ReAct runs and explicit extension points |
| [event](src/main/scala/com/weisgnosis/agents/core/event/README.md) | Typed model, tool, and agent lifecycle events |
| [skill](src/main/scala/com/weisgnosis/agents/core/skill/README.md) | Host-selected skill catalogs and deferred resource capabilities |
| [json](src/main/scala/com/weisgnosis/agents/core/json/README.md) | Strict JSON boundaries and exact numeric rendering |

## Ownership and limits

An agent retains in-memory canonical history and serializes its runs. [Prepared invocations](src/main/scala/com/weisgnosis/agents/core/agent/README.md#invocation-identity-and-prepared-execution) reserve identity for single-use execution or streaming, expose joined termination, and control only their own invocation. Run and model-request scopes own their resources; cancellation joins cleanup and reconciles unfinished local calls while preserving completed observations. Streaming progress is provisional, and terminal events are not a substitute for the returned result and cause.

Atomic AgentSnapshot values distinguish idle history from a suspended acting checkpoint; checked restoration loads data into an empty runtime using current host bindings. Core does not supply durable execution ownership, authentication, exactly-once replay, or a persistent session service. Model content never grants tool authority. Required observers can fail a run after work has already occurred; hosts must not blindly replay that work.

Provider capabilities are explicit. Chat Completions uses text tool results by default; successful image results require the selected ImageParts profile, and other retained content needs an explicit request adapter. Audio/video, signatures, reasoning, and schema behavior depend on the documented selected profile, not merely on accepting a typed content block. See [client limits and migrations](src/main/scala/com/weisgnosis/agents/core/client/README.md), [tool API migrations](src/main/scala/com/weisgnosis/agents/core/tool/README.md), and the [parity audit](../../docs/architecture/test-parity.md).

## Testing

From the repository root, run `sbt "core / test"` for this module. Run `sbt verify` for repository formatting checks, fatal-warning compilation, and tests. Broader provider and host integration work is tracked in the [repository tracker](../../docs/architecture/tracker.md); passing offline tests does not establish live provider interoperability.

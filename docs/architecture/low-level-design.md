# Low-level contracts

Current source declarations are authoritative. This document records API contracts rather than maintaining a second implementation.

## Domain models

| Type | Contract | Declaration |
| --- | --- | --- |
| Msg | Optional identity, role/content, optional name/timestamp/usage, metadata; identified allocates identity in ZIO | [Msg.scala](../../modules/core/src/main/scala/com/weisgnosis/agents/core/model/Msg.scala) |
| ContentBlock | Ten variants with shared tagged concrete/base codecs and nested discriminator validation; ToolUse has JSON input and protocol state; ToolResult carries a Chunk of blocks | [ContentBlock.scala](../../modules/core/src/main/scala/com/weisgnosis/agents/core/model/ContentBlock.scala) |
| ToolCallState | Pending, Asking, Allowed, Submitted, Finished; lowercase codec; input metadata does not grant runtime execution authority | [ToolCallState.scala](../../modules/core/src/main/scala/com/weisgnosis/agents/core/model/ToolCallState.scala) |
| GenerateReason | 12 reasons; explicit uppercase/lowercase input, lowercase output; Msg.generationReason defaults without altering raw metadata or runtime completion | [GenerateReason.scala](../../modules/core/src/main/scala/com/weisgnosis/agents/core/model/GenerateReason.scala) |
| ChatUsage | Token metrics with additive combine | [ChatUsage.scala](../../modules/core/src/main/scala/com/weisgnosis/agents/core/model/ChatUsage.scala) |
| AgentEventType | 31 canonical uppercase protocol names, 11 aliases, typed case-sensitive lookup; separate from event payload adaptation | [AgentEventType.scala](../../modules/core/src/main/scala/com/weisgnosis/agents/core/event/AgentEventType.scala) |
| AgentError | Typed sealed domain failures, including InvalidConfiguration and InvalidMessage | [AgentError.scala](../../modules/core/src/main/scala/com/weisgnosis/agents/core/error/AgentError.scala) |
| AgentEvent | Sealed 39-variant hierarchy; shared concrete/base tagged encoding, legacy leaf decoding, source tagging, immutable tool-result event metadata | [AgentEvent.scala](../../modules/core/src/main/scala/com/weisgnosis/agents/core/event/AgentEvent.scala) |

Ordinary products/sums use derives JsonCodec. Explicit codecs are reserved for validation, wire compatibility, and AST transformations. Pure constructors and decoders must not allocate random IDs or inspect time/filesystems. Msg.identified and Data.identified allocate optional IDs inside ZIO and preserve existing identity.

Msg.chatUsage performs a pure direct-first lookup with a legacy `_chat_usage` metadata fallback. Invalid legacy data is absent; malformed supplied canonical optional fields fail decoding. Msg has a private constructor and no apply/copy/Product reconstruction. Typed factories preserve role/content compatibility; dynamic create, withContent, and withRole return Either[AgentError, Msg]. Safe filtering and tool-specific mapping preserve the invariant, and mapToolResultsZIO suspends ordered callbacks and preserves cancellation. Media sources share tagged concrete/base encoding with strict nested discriminators and legacy untagged concrete decoding. Required Data source arguments and missing/JSON-null source rejection are the Scala/JSON boundary contract; direct Java-null constructor behavior is not claimed. Base64 media encodes canonical `media_type`, reads legacy `mediaType`, and rejects both supplied together. ToolResult, ToolUse, and Thinking reject malformed supplied optional fields. Concrete content encoding now includes exactly one type tag; concrete decoding accepts legacy untagged objects, but the root and all nested content require a valid unique tag. ToolUse missing/JSON-null input becomes an empty object, missing/JSON-null state becomes Pending, and unknown states fail. Canonical serverTool metadata takes precedence over the legacy agentscope_server_tool key for both calls and results. See the [model README](../../modules/core/src/main/scala/com/weisgnosis/agents/core/model/README.md) for scope and migration details.

AgentResult, data-block boundaries/string deltas, tool-result data deltas, iteration limits, stop requests, and denied-call payloads are available. RequestStop defaults absent/JSON-null generation reasons to MiddlewareStopRequested; AllToolsDenied defaults absent/JSON-null calls to an empty Chunk. Supplied malformed values fail. These payload codecs do not implement runtime publication or stop/resume authority.

AgentStart/AgentEnd share a fresh replyId per engine attempt. streamEvents drains ordered events and propagates the original runtime exit using a private completion signal. Provider-published end events do not control termination. Structured decoding follows the attempt boundary; no complete structured-stream contract is claimed. See the [agent README](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md).

## Tools and paths

[Tool](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/Tool.scala) defines typed decoding, encoding, and suspended execution. Throwable is allowed at the external adapter boundary; [Toolkit](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/Toolkit.scala) normalizes tool failures into Error observations while preserving cancellation.

Registration, withTool, and merging return Either[AgentError, Toolkit]. Duplicate names fail. Resolve returns Option. Existentially stored tools retain type-safe execution without casts.

[PathSafety](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/PathSafety.scala) exposes effectful home expansion/canonicalization and pure sensitive-name screening. Errors remain typed. Reject parent traversal before normalization can erase link-aware traversal semantics.

## Client and agent

[LlmClient.streamTurn](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/LlmClient.scala) receives history, definitions, LlmConfig, and event hub, returning ZIO[Scope, AgentError, TurnOutput]. Current clients are scripted. Transports must register resources in the supplied request scope.

[ReActAgent](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/ReActAgent.scala) construction through make/builder returns IO[AgentError, ReActAgent]. A client is mandatory; invalid iteration, timeout, and retry bounds fail.

- run accepts Msg or Chunk[Msg] and produces AgentResult.
- call produces the reply Msg.
- streamEvents is scoped and preserves model failures.
- history returns UIO[Chunk[Msg]].
- interrupt signals the active run.
- runStructured uses a JsonCodec, schema, and StructuredMode and returns AgentResult with the decoded value.

[AgentConfig](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/AgentConfig.scala) defaults to 20 iterations, 5 minutes, two thinking retries, and ToolExecution.Parallel. [AgentResult](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/AgentResult.scala) contains iterationCount, optional ChatUsage, and one AgentRunState; Boolean accessors are derived queries.

[StructuredOutput](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/StructuredOutput.scala) hoists both definition namespaces without changing references. Native mode supplies responseFormat. Auto prefers native with no application tools and can fall back to synthetic capture. Successful generate_response terminates without an extra model turn after the acting phase completes. Non-Completed runs produce AgentError.Interrupted without replacing reconciled history. The final reply retains generating-request thinking and usage; AgentResult.usage aggregates the run. Compression keeps ordinary tool pairs and unrelated empty messages.

## Harness

| Component | Contract | Declaration |
| --- | --- | --- |
| ConversationCompactor | Injected summarizer/counter/hooks, safe cutoff, summary rebuilding, typed failures | [source](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/ConversationCompactor.scala) |
| CompactorConfig | 120k trigger, 40k keep/protect, six retained messages, 2k pruning threshold | [source](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/CompactorConfig.scala) |
| ToolResultEvictor | Above 80k text chars, 2k head/tail, persistence failure retains original | [source](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/eviction/ToolResultEvictor.scala) |
| ToolResultStore | Effectful persistence; local backend requires WorkspaceTrust and hard-link support | [source](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/eviction/ToolResultStore.scala) |
| PermissionEngine | Immutable evaluate/authorize/resume, no model request or durable storage | [source](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/permission/PermissionEngine.scala) |
| PermissionRule | Tool-name/glob matching against explicit argumentPath, command by default | [source](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/permission/PermissionRule.scala) |
| PermissionRequests | Promise registration before publication, one-shot resolution, cancellation cleanup | [source](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/permission/PermissionRequests.scala) |

## Pending interfaces

Memory services, middleware, durable checkpoint storage, subagents, plan-mode enforcement, and MCP are not implemented. Consult [planned behavior](mid-level-design.md) and the [tracker](tracker.md); architectural sketches are not importable APIs.

# Typed tools and path validation

Tool keeps its input/output codecs and effectful handler together. Toolkit registration and merging return Either[AgentError, Toolkit], including duplicate-name failures. Execution normalizes tool failures and defects into Error observations; interruption propagates. PathSafety rejects parent traversal before canonicalization and resolves existing ancestors through toRealPath. Canonical checks do not provide an atomic sandbox against hostile filesystem mutation.

## Descriptive output schemas

`ToolDef.outputSchema: Option[Json]` carries an optional host-selected output schema. Toolkit snapshots and model-call tool definitions retain this AST; it does not validate returned values or grant execution authority. A host selecting an MCP tool may explicitly retain, replace or omit the remote schema. Preset input-schema projection leaves this field unchanged.

The current Chat Completions encoder emits only function name, description and input parameters. It does not send outputSchema as a provider extension. Custom model clients receive the complete ToolDef and own any supported provider projection.

The derived domain codec reads an absent or explicit-null outputSchema as None and omits None when encoding. Non-null schema ASTs retain their structure; Some(Json.Null) does not round-trip as present. Existing constructors and named copies can omit the defaulted field; positional pattern matches and Product arity now have six fields, and downstream binaries must be rebuilt. No schema evaluation or custom compatibility codec is introduced.

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for the five identities below, with no findings. Formatting and full `verify` passed in `tool-output-schema-verify.log`: 574 core / 673 harness / 77 MCP tests (1,324 total), zero compiler warnings or failures.

| Reviewed source/test | Git blob |
| --- | --- |
| [ToolDef.scala](ToolDef.scala) | `1884f7d4701df7e36c7b10235764534c6f699c29` |
| [ToolDefSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/tool/ToolDefSpec.scala) | `2d00729523014e9c29ca576bcf11fe90a720d379` |
| [ChatCompletionsRequestSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsRequestSpec.scala) | `af9528e389c6de56042421fbf8bdbf7639d775f5` |
| [McpToolMetadataSpec.scala](../../../../../../../../../mcp/src/test/scala/com/weisgnosis/agents/mcp/tool/McpToolMetadataSpec.scala) | `aea7102a3970ca7be863ea69bdf219e7df7651e5` |
| [McpClientManagerSpec.scala](../../../../../../../../../mcp/src/test/scala/com/weisgnosis/agents/mcp/client/McpClientManagerSpec.scala) | `35ab8ddbb588f1600848a04fe59ed93a0a53e72d` |

## Typed execution observations

`Tool.encodeResult(out)` defaults to the existing `encodeOutput(out)` as a successful text observation. Implementations may override it to return `ToolExecutionResult(payload, state)`, where `ToolResultPayload` retains ordered content blocks and metadata. Toolkit accepts only Success or Error from this projection; Running, Interrupted and Denied become descriptive Error observations. Authorization denials are produced separately by the runtime, and actual interruption remains cancellation.

ReAct supplies the original call ID, name and accepted state, then applies the payload through `ToolResultPayload.applyTo`. Tool-provided metadata cannot set serverTool, agentscope_server_tool or agentscope_suspended. Typed local content survives history persistence, result processing and ordered text/data events. The current ChatCompletions request codec still rejects non-text tool output; this extension does not add provider multimodal support or incremental tool execution.

Migration: String `ToolExecutionResult(...)` constructors and the `output` text projection remain available. The projection joins Text blocks with newlines and omits other block types. The canonical constructor, generated copy/unapply and derived JSON shape now use `payload` and explicit `state`; persisted legacy output/state JSON requires host migration. There is no manual compatibility codec. Existing Tool implementations inherit encodeResult without an abstract-method migration.

Interrupted compound causes are refailed with `orDie`: interruption and cleanup defects survive, while typed Throwable leaves become defects to retain the UIO contract. Ordinary non-interrupted failures and defects remain Error observations.

## Invocation event capability

`Tool.executeWithContext(input, invocation)` delegates to `execute(input)` by default. Existing tools need no implementation change. The original `Toolkit.executeSafe(name, arguments)` uses `ToolInvocation.Detached`; the overload accepts an explicit host capability. These execution dependencies have no JSON codec and cannot be supplied through model arguments.

ReAct creates `Active(context, call, callOrdinal, events)` only for approved local execution, after persisting approved arguments. The zero-based tool occurrence distinguishes repeated model call IDs. Denied calls and matched provider inline results do not invoke this handler. Runtime request/reply correlation and the approved call are retained.

`ToolEventPort` exposes publication only. Its scoped lease closes after execution cleanup and before result processing. Publication and closure are serialized: Published means enqueued on the runtime's unbounded hub, not consumed; retained ports return Closed after revocation. Cancellation joins cleanup and preserves interruption and cleanup defects. The lease does not own a child task, provide replay/backpressure, or forward child events automatically. Trusted contextual tools remain responsible for the events they publish; the port does not authenticate arbitrary event payloads.

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants at the five identities below, with no unresolved findings. Three grouped tests cover approved occurrences, legacy/detached/denied execution, concurrent revocation and actual ReAct cancellation. Formatting, fatal-warning compilation and full verification passed in `tool-invocation-verify.log`; current totals are in the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).
- [x] The separately reviewed [harness integration](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#scoped-child-event-forwarding) uses this capability for owned child execution and promotion. Its acceptance and bounded parity mappings remain distinct from this core contract.

| Reviewed file | Git blob |
| --- | --- |
| [ToolInvocation.scala](ToolInvocation.scala) | `afaab56599f0bbb22efa95852bafb3938812aff9` |
| [Tool.scala](Tool.scala) | `f03e5bf6e3e51de14a6a209c19dee0de913e8fed` |
| [Toolkit.scala](Toolkit.scala) | `a4290b36029732a0d6d2e4e9714b95fc0661d9b2` |
| [ReActAgent.scala](../agent/ReActAgent.scala) | `73ef850c6317e79d7e2ffaff60efd0c6c5e0c607` |
| [ToolInvocationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/tool/ToolInvocationSpec.scala) | `d43067266a986355091c9057ac55f9771b2947e8` |

## Implementation

- [PathSafety](PathSafety.scala)
- [Tool](Tool.scala)
- [ToolDef](ToolDef.scala)
- [ToolResultProcessor](ToolResultProcessor.scala)
- [ToolAuthorizer](ToolAuthorizer.scala)
- [ToolExecutionResult](ToolExecutionResult.scala)
- [Toolkit](Toolkit.scala)

See the [repository architecture](../../../../../../../../../../ARCHITECTURE.md) for integration boundaries and invariant definitions.

## Command screening

`isDangerousCommand` is a conservative lexical warning helper, not shell parsing or execution authorization. It normalizes input and configured fragments with Locale.ROOT, allows whitespace between fragment words, and requires lexical boundaries around alphanumeric fragment ends. It detects recursive chmod/chown, path-qualified dd, mkfs suffix commands, and the `/dev/` redirection prefix without matching dd inside address. Quoted dangerous text can still match. A false result does not establish command safety; plan admission uses its separate typed inspection and permission policy.

## Current review acceptance

- [x] Independent FP/ZIO and invariant reviewer `path_review` approved the advisory command-screening fix and regressions: source blob `3d7a102d03c47719271ad2e4ba791af81247170e`, test blob `981212ee32c51cf92d12d844f4fe5a7403f70b78`. All eight focused tests pass.
- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for typed execution payloads, state admission, runtime control protection and compound-cause preservation. Both approved the six exact identities below with no findings.
- [x] Full `scalafmtAll scalafmtSbt verify` passed: 564 core / 651 harness / 41 MCP tests (1,256 total), zero compiler warnings or failures. Evidence: `typed-tool-verify.log`.
- [x] Existing processor and authorization contracts below were accepted by review_fp and review_harness in their recorded source revisions; this change does not modify those implementations.

| Typed-result source/test | Reviewed Git blob |
| --- | --- |
| [Tool.scala](Tool.scala) | 39d8303a93bfba9710e0f3fc479310fe11722046 |
| [ToolExecutionResult.scala](ToolExecutionResult.scala) | 1c3e999a1363e6a042a0c83186006fda2060992f |
| [Toolkit.scala](Toolkit.scala) | a6f093492d33b713887cdd4d93ac5ef075ab8cfe |
| [ReActAgent.scala](../agent/ReActAgent.scala) | ee9c1afb5ef6082605ef3318c8744797e2154f10 |
| [ToolExecutionSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/tool/ToolExecutionSpec.scala) | a85af9fa35a0848944627973bda1413e4ca4e881 |
| [ReActAgentToolResultProcessorSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentToolResultProcessorSpec.scala) | 703c7abec55bb3b4ea5d429ecefa76c9f1557b16 |

The table records the typed-result review baseline. Current Tool and Toolkit identities are recorded under [invocation event capability](#invocation-event-capability); the later ReActAgent extension is accepted under [direct event forwarding](../agent/README.md#direct-event-forwarding). Typed result behavior is unchanged. Result-bearing stream acceptance remains in the [agent contract](../agent/README.md#result-bearing-streams).

Platform-specific symlink/junction behavior and hostile filesystem races remain outside the verified guarantees. Current command-screening evidence is recorded in the [test README](../../../../../../../test/scala/com/weisgnosis/agents/core/tool/README.md#command-screening-regressions).

## Completed result processing

ReActAgent.make and Builder.toolResultProcessor accept a ToolResultProcessor, defaulting to identity. Its suspended process callback returns ZIO[Scope, AgentError, ToolResultPayload]. The payload contains output blocks and metadata only. applyTo preserves the original call ID, name, state, and serverTool/agentscope_server_tool/agentscope_suspended control keys. Composition applies each payload safely before passing the observation onward; host processors cannot change execution authority through these fields.

After local execution finishes, the runtime atomically appends the complete raw observation and captures its history position. It then runs processing interruptibly in a separate scope. Typed processing failures restore the original payload; defects propagate. Scope cleanup finishes before a short masked section replaces that exact observation and publishes its text/data deltas and End. Parallel calls own distinct append positions, including calls that reuse IDs; completion may arrive in a different order. Processing assumes history remains append-only during the acting phase under the run lock.

Cancellation during processing keeps the completed raw result and reconciles only unfinished executions. It does not rerun the tool or publish a false ToolResultEnd. A subsequent model request sees the committed processed payload; observers may see the raw result while processing is pending. Processor side effects already performed before failure/cancellation are not rolled back. Callbacks must not reenter a mutating agent operation that requires the held run lock.

Tool execution can return completed typed payloads. A processor can replace their output blocks and metadata before publication; incremental execution remains separate. Empty processed output emits only End after the original Start. Matched inline provider results use this processor after raw assistant persistence, retaining their exact result ordinal. Their Start, ordered text/data deltas, and End are published only after processing and scope cleanup. Inline processing bypasses local authorization/execution and creates no Role.Tool message. Supplied history uses the separate opt-in ToolResultEvictor.preparer adapter.

Appending the optional make parameter preserves ordinary callers; positional Builder pattern matches must follow the current Builder constructor. Processor values are runtime dependencies, not serialized agent configuration.


## Scoped local authorization

ReActAgent.make and Builder.toolAuthorizer accept a ToolAuthorizer, defaulting explicitly to allow for compatibility. Installing a host authorizer is necessary to enforce permission policy. ToolAuthorizationRequest contains a fresh approval UUID, the originating ModelStreamContext, and the original call. ToolAuthorization exposes Approved(input) or Denied(reason); identity, name, metadata, and execution authority are not replaceable decision fields. Requests and decisions have derived codecs, but serialization alone grants no execution authority.

The runtime persists the assistant message before authorization and captures its exact history position. Every pending call that reaches local dispatch is authorized, including provider-marked calls without matched inline observations. Matched provider inline results bypass local authorization. Each callback owns an interruptible scope; cleanup finishes before result Start and local execution. The request scope does not extend across the tool operation. Hosts must not reenter mutating agent operations while the run lock is held.

Approved JSON must have unique keys at every depth. A changed input is denied if either thoughtSignature or reasoningDetails metadata is present, including malformed or opaque values. Otherwise the runtime rewrites only that call's original occurrence, clears cached content, and stores its original input under agentscope_original_tool_input. Identity, other metadata, and protocol state remain unchanged. The next model request and concurrent history readers see the approved arguments before the tool executes. This provenance key records the runtime rewrite; arbitrary preexisting metadata is not authenticated evidence. No JSON Schema validation is added.

Denials never execute the tool. Typed PermissionDenied failures retain their reason while the runtime retains call identity. Authorizer defects and finalizer defects become Denied observations with a generic authorization-failure reason. This explicit fail-closed normalization prevents dangling calls without classifying host failures as provider errors. Cancellation propagates and reconciles unresolved calls as Error; completed siblings survive. Denials use the existing result processor and correlated Start/Delta/End path, so approved host processors can transform their observation content without changing Denied state.

Interruption reconciliation first consumes eligible inline observations, then matches remaining locally dispatched occurrences against Role.Tool results by ID and optional name. These pools cannot consume each other's observations. The internal Msg.mapToolUseAt helper updates by tool ordinal, preserving role/content compatibility; duplicate IDs and identical calls do not collapse into one rewrite.

Appending the optional make argument preserves ordinary calls; positional Builder matches must follow the current Builder constructor. This core extension point is implemented by the opt-in harness InteractivePermissionAuthorizer for policy checks, interactive confirmation, and atomic policy updates. Durable suspension/resumption and full upstream permission event ordering remain pending.


The optional [interactive harness adapter](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md) owns policy lifetime and approval callbacks; the core remains independent of harness dependencies.

Current runtime/processor scope and identities remain in the [agent README](../agent/README.md). Authorization source acceptance is retained in `5eb4bdc`; earlier review chronology is available in Git.

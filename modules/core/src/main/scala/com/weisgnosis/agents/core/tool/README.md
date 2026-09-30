# Typed tools and path validation

Tool keeps its input/output codecs and effectful handler together. Toolkit registration and merging return Either[AgentError, Toolkit], including duplicate-name failures. Execution normalizes tool failures and defects into Error observations; interruption propagates. PathSafety rejects parent traversal before canonicalization and resolves existing ancestors through toRealPath. Canonical checks do not provide an atomic sandbox against hostile filesystem mutation.

## Host-selected tool groups

`ToolGroups` is an immutable registry of named `ToolGroup` declarations. Activation is `Active` or `Inactive`; scope is `Meta` (eligible for agent-managed selection) or `External` (host-managed). Registration and updates return checked values without acquiring resources or changing existing registries. Names are exact and must be nonblank. Batch activation validates every requested group before producing a replacement; unknown names fail instead of partially updating or silently skipping them.

`replaceActive` replaces activation across both scopes. `replaceMetaActive` changes only Meta groups and rejects requested External groups. Membership is independent of a particular Toolkit. Ungrouped tools are available; a tool in several groups is available when at least one is active. `select(toolkit)` filters existing tool values, preserving their fixed arguments, execution overrides and borrowed resources. It produces a snapshot for the host to supply to ReAct; it does not mutate an already-running agent or install a model-facing activation tool.

Removing groups or membership reports `newlyUngrouped` names alongside the next registry. Removing the last membership makes a retained tool available as ungrouped. A host that wants to remove those tools must also prune its Toolkit; group removal performs no disposal or capability revocation. Group selection does not replace permission policy.

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for [ToolGroups.scala](ToolGroups.scala), blob `ee51930197f5091a398e57c53c3f93fed2eb7922`, and [ToolGroupsSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/tool/ToolGroupsSpec.scala), blob `3729dbeef275e731a215743117ef55e4e2de8c62`, with no findings. Four grouped contracts cover atomic transitions, scope separation, membership/removal and policy preservation, and actual ReAct snapshot visibility/dispatch.
- [x] Formatting and full `verify` passed in `tool-groups-verify.log`: 583 core / 673 harness / 78 MCP tests (1,334 total), zero compiler warnings or failures; test task 12 seconds.
The runtime installs an optional [run-owned activation adapter](../agent/README.md#run-owned-tool-group-activation) over this foundation.

- [ ] Next: human-readable group notes. Skill-triggered groups and durable activation remain separate work.

Group data/enums use derived codecs; decoding does not bypass checked registration. Immutable values replace Java builders and defensive copying, and null contracts are not reproduced. Unknown activation/membership groups fail atomically rather than following upstream permissive or partially applied paths. No existing Tool or Toolkit signature changes.

## Fixed raw arguments

[ToolFixedArguments](ToolFixedArguments.scala) is a checked immutable host policy. Nonempty policies require object input, validate the complete original AST before replacement, and apply shallow fixed-wins values. Existing root field positions are retained; missing fixed fields append in policy order, so preparation is AST-idempotent. Empty policy is identity even for scalar/array input and leaves legacy decoder validation unchanged. `validateApproved` rejects changed or missing fixed values while allowing other approved edits. No schema hiding or evaluation is added.

`Tool.withFixedArguments(tool, fixed)` preserves In/Out types, definition, explicit read-only/concurrency overrides, contextual execution and typed result projection. Empty attachment returns the original tool; a second nonempty attachment fails, even if identical. The policy belongs to the Tool value and survives Toolkit selection/merging. Toolkit.prepareInput and validateApprovedInput expose pure checks, and executeSafe prepares before decoding even for custom tools that expose a policy. Unknown names retain ordinary missing-tool dispatch. Trusted typed execute(In) calls bypass this raw-input policy.

ReAct prepares before authorization. Invalid preparation becomes Error without calling the authorizer or handler. A change to arguments carrying provider reasoning metadata is Denied before authorization. The authorizer sees prepared arguments; changing or removing a fixed field is Denied, while edits to other fields are allowed. After approval, the exact final input is committed to history, supplied through Active.call and executed. Original model input remains provenance; while approval is pending or cancelled, history retains the original call. Authorization cancellation joins cleanup and preserves its full cause, mapping typed Throwable leaves to defects for the UIO boundary.

Migration: Tool.fixedArguments has a concrete empty default, and the wrapper and Toolkit checks are additive. Existing raw scalar tools retain their decoding contract. MCP model-overridable defaults remain separate; manager installation of framework-fixed arguments has a separate [MCP contract](../../../../../../../../../mcp/src/main/scala/com/weisgnosis/agents/mcp/tool/README.md#fixed-arguments).

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for this scope, with no findings. Unchanged component identities are below; current ReActAgent identity is in [run-owned activation](../agent/README.md#run-owned-tool-group-activation). Scoped verification passed in `tool-fixed-arguments-verify.log`; current full-repository evidence and totals are recorded in the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).

| Source/test | Reviewed Git blob |
| --- | --- |
| [ToolFixedArguments.scala](ToolFixedArguments.scala) | `5b0a707baa22e1407fe4d87dd7a13fcc48fed3dc` |
| [ToolFixedArgumentsSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/tool/ToolFixedArgumentsSpec.scala) | `367654171296e5aabdc3e0d448822c2eb583a336` |
| [Tool.scala](Tool.scala) | `2f425468675fa3d8a7f5b34232e7c545b3c49958` |
| [Toolkit.scala](Toolkit.scala) | `6b0cc64124fc8d5411388918b6284a4b0dcac3af` |
| [ReActAgentToolAuthorizerSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentToolAuthorizerSpec.scala) | `449a33d7484c16940b00ef2c936bc2c50486f917` |

## Descriptive output schemas

`ToolDef.outputSchema: Option[Json]` carries an optional host-selected output schema. Toolkit snapshots and model-call tool definitions retain this AST; it does not validate returned values or grant execution authority. A host selecting an MCP tool may explicitly retain, replace or omit the remote schema. Preset input-schema projection leaves this field unchanged.

The current Chat Completions encoder emits only function name, description and input parameters. It does not send outputSchema as a provider extension. Custom model clients receive the complete ToolDef and own any supported provider projection.

The derived domain codec reads an absent or explicit-null outputSchema as None and omits None when encoding. Non-null schema ASTs retain their structure; Some(Json.Null) does not round-trip as present. Existing constructors and named copies can omit the defaulted field; positional pattern matches and Product arity now have six fields, and downstream binaries must be rebuilt. No schema evaluation or custom compatibility codec is introduced.

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for this scope, with no findings. Unchanged core identities are below; current integration tests belong to the [MCP binding acceptance](../../../../../../../../../mcp/src/main/scala/com/weisgnosis/agents/mcp/tool/README.md#verification-and-acceptance). Formatting and full `verify` passed; current repository totals are recorded in the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance), and scoped verification evidence remains under [fixed raw arguments](#fixed-raw-arguments).

| Reviewed source/test | Git blob |
| --- | --- |
| [ToolDef.scala](ToolDef.scala) | `1884f7d4701df7e36c7b10235764534c6f699c29` |
| [ToolDefSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/tool/ToolDefSpec.scala) | `2d00729523014e9c29ca576bcf11fe90a720d379` |
| [ChatCompletionsRequestSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsRequestSpec.scala) | `af9528e389c6de56042421fbf8bdbf7639d775f5` |

## Typed execution observations

`Tool.encodeResult(out)` defaults to the existing `encodeOutput(out)` as a successful text observation. Implementations may override it to return `ToolExecutionResult(payload, state)`, where `ToolResultPayload` retains ordered content blocks and metadata. Toolkit accepts only Success or Error from this projection; Running, Interrupted and Denied become descriptive Error observations. Authorization denials are produced separately by the runtime, and actual interruption remains cancellation.

ReAct supplies the original call ID, name and accepted state, then applies the payload through `ToolResultPayload.applyTo`. Tool-provided metadata cannot set serverTool, agentscope_server_tool or agentscope_suspended. Typed local content survives history persistence, result processing and ordered text/data events. The current ChatCompletions request codec still rejects non-text tool output; this extension does not add provider multimodal support or incremental tool execution.

Migration: String `ToolExecutionResult(...)` constructors and the `output` text projection remain available. The projection joins Text blocks with newlines and omits other block types. The canonical constructor, generated copy/unapply and derived JSON shape now use `payload` and explicit `state`; persisted legacy output/state JSON requires host migration. There is no manual compatibility codec. Existing Tool implementations inherit encodeResult without an abstract-method migration.

Interrupted compound causes are refailed with `orDie`: interruption and cleanup defects survive, while typed Throwable leaves become defects to retain the UIO contract. Ordinary non-interrupted failures and defects remain Error observations.

## Invocation event capability

`Tool.executeWithContext(input, invocation)` delegates to `execute(input)` by default. Existing tools need no implementation change. The original `Toolkit.executeSafe(name, arguments)` uses `ToolInvocation.Detached`; the overload accepts an explicit host capability. These execution dependencies have no JSON codec and cannot be supplied through model arguments.

ReAct creates `Active(context, call, callOrdinal, events)` only for approved local execution, after persisting approved arguments. The zero-based tool occurrence distinguishes repeated model call IDs. Denied calls and matched provider inline results do not invoke this handler. Runtime request/reply correlation and the approved call are retained.

`ToolEventPort` exposes publication only. Its scoped lease closes after execution cleanup and before result processing. Publication and closure are serialized: Published means enqueued on the runtime's unbounded hub, not consumed; retained ports return Closed after revocation. Cancellation joins cleanup and preserves interruption and cleanup defects. The lease does not own a child task, provide replay/backpressure, or forward child events automatically. Trusted contextual tools remain responsible for the events they publish; the port does not authenticate arbitrary event payloads.

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for this capability, with no unresolved findings. Unchanged capability identities are below; current Tool/Toolkit identities are in [fixed raw arguments](#fixed-raw-arguments), and current ReActAgent identity is in [run-owned activation](../agent/README.md#run-owned-tool-group-activation). Three grouped tests cover approved occurrences, legacy/detached/denied execution, concurrent revocation and actual ReAct cancellation. Formatting, fatal-warning compilation and full verification passed in `tool-invocation-verify.log`; current totals are in the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).
- [x] The separately reviewed [harness integration](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#scoped-child-event-forwarding) uses this capability for owned child execution and promotion. Its acceptance and bounded parity mappings remain distinct from this core contract.

| Reviewed file | Git blob |
| --- | --- |
| [ToolInvocation.scala](ToolInvocation.scala) | `afaab56599f0bbb22efa95852bafb3938812aff9` |
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
- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for typed execution payloads, state admission, runtime control protection and compound-cause preservation. Both approved this scope with no findings; unchanged component identities are below, and current Tool/Toolkit identities are in [fixed raw arguments](#fixed-raw-arguments), and current ReActAgent identity is in [run-owned activation](../agent/README.md#run-owned-tool-group-activation).
- [x] Full formatting, fatal-warning compilation and verification passed; current repository totals are recorded in the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance), and scoped verification evidence remains under [fixed raw arguments](#fixed-raw-arguments).
- [x] Existing processor contracts below retain their scoped acceptance and unchanged implementations. Current authorization acceptance belongs to [fixed raw arguments](#fixed-raw-arguments).

| Typed-result source/test | Reviewed Git blob |
| --- | --- |
| [ToolExecutionResult.scala](ToolExecutionResult.scala) | 1c3e999a1363e6a042a0c83186006fda2060992f |
| [ToolExecutionSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/tool/ToolExecutionSpec.scala) | a85af9fa35a0848944627973bda1413e4ca4e881 |
| [ReActAgentToolResultProcessorSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentToolResultProcessorSpec.scala) | 703c7abec55bb3b4ea5d429ecefa76c9f1557b16 |

The table records unchanged typed-result components. Current Tool and Toolkit identities are recorded under [fixed raw arguments](#fixed-raw-arguments), and ReActAgent under [run-owned activation](../agent/README.md#run-owned-tool-group-activation); separate invocation and [direct event forwarding](../agent/README.md#direct-event-forwarding) contracts retain their scoped evidence. Typed result behavior is unchanged. Result-bearing stream acceptance remains in the [agent contract](../agent/README.md#result-bearing-streams).

Platform-specific symlink/junction behavior and hostile filesystem races remain outside the verified guarantees. Current command-screening evidence is recorded in the [test README](../../../../../../../test/scala/com/weisgnosis/agents/core/tool/README.md#command-screening-regressions).

## Completed result processing

ReActAgent.make and Builder.toolResultProcessor accept a ToolResultProcessor, defaulting to identity. Its suspended process callback returns ZIO[Scope, AgentError, ToolResultPayload]. The payload contains output blocks and metadata only. applyTo preserves the original call ID, name, state, and serverTool/agentscope_server_tool/agentscope_suspended control keys. Composition applies each payload safely before passing the observation onward; host processors cannot change execution authority through these fields.

After local execution finishes, the runtime atomically appends the complete raw observation and captures its history position. It then runs processing interruptibly in a separate scope. Typed processing failures restore the original payload; defects propagate. Scope cleanup finishes before a short masked section replaces that exact observation and publishes its text/data deltas and End. Parallel calls own distinct append positions, including calls that reuse IDs; completion may arrive in a different order. Processing assumes history remains append-only during the acting phase under the run lock.

Cancellation during processing keeps the completed raw result and reconciles only unfinished executions. It does not rerun the tool or publish a false ToolResultEnd. A subsequent model request sees the committed processed payload; observers may see the raw result while processing is pending. Processor side effects already performed before failure/cancellation are not rolled back. Callbacks must not reenter a mutating agent operation that requires the held run lock.

Tool execution can return completed typed payloads. A processor can replace their output blocks and metadata before publication; incremental execution remains separate. Empty processed output emits only End after the original Start. Matched inline provider results use this processor after raw assistant persistence, retaining their exact result ordinal. Their Start, ordered text/data deltas, and End are published only after processing and scope cleanup. Inline processing bypasses local authorization/execution and creates no Role.Tool message. Supplied history uses the separate opt-in ToolResultEvictor.preparer adapter.

Appending the optional make parameter preserves ordinary callers; positional Builder pattern matches must follow the current Builder constructor. Processor values are runtime dependencies, not serialized agent configuration.


## Scoped local authorization

ReActAgent.make and Builder.toolAuthorizer accept a ToolAuthorizer, defaulting explicitly to allow for compatibility. Installing a host authorizer is necessary to enforce permission policy. ToolAuthorizationRequest contains a fresh approval UUID, the originating ModelStreamContext, and the call with framework-fixed arguments prepared before authorization. ToolAuthorization exposes Approved(input) or Denied(reason); identity, name, metadata, and execution authority are not replaceable decision fields. Requests and decisions have derived codecs, but serialization alone grants no execution authority.

The runtime persists the assistant message before authorization and captures its exact history position. Every pending local call passes preparation and authorization checks, including provider-marked calls without matched inline observations; rejected preparation or signed changes do not invoke the authorizer. Matched provider inline results bypass local authorization. Each callback owns an interruptible scope; cleanup finishes before result Start and local execution. The request scope does not extend across the tool operation. Hosts must not reenter mutating agent operations while the run lock is held.

Approved JSON must have unique keys at every depth and retain all attached fixed values. A changed input is denied if either thoughtSignature or reasoningDetails metadata is present, including malformed or opaque values. Otherwise the runtime rewrites only that call's original occurrence, clears cached content, and stores its original input under agentscope_original_tool_input. Identity, other metadata, and protocol state remain unchanged. The next model request and concurrent history readers see the approved arguments before the tool executes. This provenance key records the runtime rewrite; arbitrary preexisting metadata is not authenticated evidence. No JSON Schema validation is added.

Denials never execute the tool. Typed PermissionDenied failures retain their reason while the runtime retains call identity. Non-interrupted authorizer failures and finalizer defects become Denied observations with a generic authorization-failure reason. Interrupted compound causes propagate intact with typed Throwable leaves mapped to defects; cleanup defects are not normalized into denial or discarded. This explicit fail-closed normalization prevents dangling calls without classifying host failures as provider errors. Cancellation propagates and reconciles unresolved calls as Error; completed siblings survive. Denials use the existing result processor and correlated Start/Delta/End path, so approved host processors can transform their observation content without changing Denied state.

Interruption reconciliation first consumes eligible inline observations, then matches remaining locally dispatched occurrences against Role.Tool results by ID and optional name. These pools cannot consume each other's observations. The internal Msg.mapToolUseAt helper updates by tool ordinal, preserving role/content compatibility; duplicate IDs and identical calls do not collapse into one rewrite.

Appending the optional make argument preserves ordinary calls; positional Builder matches must follow the current Builder constructor. This core extension point is implemented by the opt-in harness InteractivePermissionAuthorizer for policy checks, interactive confirmation, and atomic policy updates. Durable suspension/resumption and full upstream permission event ordering remain pending.


The optional [interactive harness adapter](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md) owns policy lifetime and approval callbacks; the core remains independent of harness dependencies.

Current authorization source/spec identities are in [fixed raw arguments](#fixed-raw-arguments); separate runtime/processor contracts remain in the [agent README](../agent/README.md).

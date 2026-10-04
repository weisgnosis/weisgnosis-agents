# Typed tools and path validation

Tool keeps its input/output codecs and effectful handler together. Toolkit registration and merging return Either[AgentError, Toolkit], including duplicate-name failures. Execution normalizes tool failures and defects into Error observations; interruption propagates. PathSafety rejects parent traversal before canonicalization and resolves existing ancestors through toRealPath. Canonical checks do not provide an atomic sandbox against hostile filesystem mutation.

## Host-selected tool groups

`ToolGroups` is an immutable registry of named `ToolGroup` declarations. Activation is `Active` or `Inactive`; scope is `Meta` (eligible for agent-managed selection) or `External` (host-managed). Registration and updates return checked values without acquiring resources or changing existing registries. Names are exact and must be nonblank. Batch activation validates every requested group before producing a replacement; unknown names fail instead of partially updating or silently skipping them.

`replaceActive` replaces activation across both scopes. `replaceMetaActive` changes only Meta groups and rejects requested External groups. Membership is independent of a particular Toolkit. Ungrouped tools are available; a tool in several groups is available when at least one is active. `select(toolkit)` filters existing tool values, preserving their fixed arguments, execution overrides and borrowed resources. It produces a snapshot for the host to supply to ReAct; it does not mutate an already-running agent or install a model-facing activation tool.

`activeNotes` renders active groups from both scopes for the host. `metaNotes` renders separate active and inactive Meta sections and excludes External groups. Both are pure snapshot projections, sort names, include host descriptions and handle empty sections explicitly. They grant no permissions or skill activation.

Removing groups or membership reports `newlyUngrouped` names alongside the next registry. Removing the last membership makes a retained tool available as ungrouped. A host that wants to remove those tools must also prune its Toolkit; group removal performs no disposal or capability revocation. Group selection does not replace permission policy.

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for [ToolGroups.scala](ToolGroups.scala), blob `43d6517ae7940fbacf714e0e03512cacd5ff5e6f`, and [ToolGroupsSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/tool/ToolGroupsSpec.scala), blob `64db7b416f38bced4792c2eb653da87a74850626`, with no findings. Five grouped contracts cover sorted/empty/scoped notes, atomic transitions, scope separation, membership/removal and policy preservation, and actual ReAct snapshot visibility/dispatch.
- [x] Formatting and full `verify` passed in `tool-group-notes-verify.log`, with zero compiler warnings or failures; the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance) owns current repository totals.
The runtime installs an optional [run-owned activation adapter](../agent/README.md#run-owned-tool-group-activation) over this foundation.

- [x] Opt-in [harness Plan admission](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md#dynamic-plan-admission) consumes the runtime admitted-access view.
- [ ] Complete remaining skill/child integration from the root tracker; pure reminders have separate [catalogue acceptance](../skill/README.md). Durable activation remains separate work.

Group data/enums use derived codecs; decoding does not bypass checked registration. Immutable values replace Java builders and defensive copying, and null contracts are not reproduced. Unknown activation/membership groups fail atomically rather than following upstream permissive or partially applied paths. The pure activeNotes/metaNotes accessors are additive; no existing Tool or Toolkit signature changes. Runtime note output has the [adapter migration](../agent/README.md#run-owned-tool-group-activation).

## Fixed raw arguments

[ToolFixedArguments](ToolFixedArguments.scala) is a checked immutable host policy. Nonempty policies require object input, validate the complete original AST before replacement, and apply shallow fixed-wins values. Existing root field positions are retained; missing fixed fields append in policy order, so preparation is AST-idempotent. Empty policy is identity even for scalar/array input and leaves legacy decoder validation unchanged. `validateApproved` rejects changed or missing fixed values while allowing other approved edits. No schema hiding or evaluation is added.

`Tool.withFixedArguments(tool, fixed)` preserves In/Out types, definition, explicit read-only/concurrency overrides, contextual execution and typed result projection. Empty attachment returns the original tool; a second nonempty attachment fails, even if identical. The policy belongs to the Tool value and survives Toolkit selection/merging. Toolkit.prepareInput and validateApprovedInput expose pure checks, and executeSafe prepares before decoding even for custom tools that expose a policy. Unknown names retain ordinary missing-tool dispatch. Trusted typed execute(In) calls bypass this raw-input policy.

ReAct prepares before authorization. Invalid preparation becomes Error without calling the authorizer or handler. A change to arguments carrying provider reasoning metadata is Denied before authorization. The authorizer sees prepared arguments; changing or removing a fixed field is Denied, while edits to other fields are allowed. After approval, the exact final input is committed to history, supplied through Active.call and executed. Original model input remains provenance; while approval is pending or cancelled, history retains the original call. Authorization cancellation joins cleanup and preserves its full cause, mapping typed Throwable leaves to defects for the UIO boundary.

Migration: Tool.fixedArguments has a concrete empty default, and the wrapper and Toolkit checks are additive. Existing raw scalar tools retain their decoding contract. MCP model-overridable defaults remain separate; manager installation of framework-fixed arguments has a separate [MCP contract](../../../../../../../../../mcp/src/main/scala/com/weisgnosis/agents/mcp/tool/README.md#fixed-arguments).

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for this scope, with no findings. Unchanged component identities are below; current Tool/Toolkit identities are in [incremental execution](#incremental-execution), and current ReActAgent identity is in [acting-stop decisions](../agent/README.md#acting-stop-decisions). Scoped verification passed in `tool-fixed-arguments-verify.log`; current full-repository evidence and totals are recorded in the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).

| Source/test | Reviewed Git blob |
| --- | --- |
| [ToolFixedArguments.scala](ToolFixedArguments.scala) | `5b0a707baa22e1407fe4d87dd7a13fcc48fed3dc` |
| [ToolFixedArgumentsSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/tool/ToolFixedArgumentsSpec.scala) | `367654171296e5aabdc3e0d448822c2eb583a336` |
| [ReActAgentToolAuthorizerSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentToolAuthorizerSpec.scala) | [Current identity](../client/README.md#encrypted-reasoning) |

## Descriptive output schemas

`ToolDef.outputSchema: Option[Json]` carries an optional host-selected output schema. Toolkit snapshots and model-call tool definitions retain this AST; it does not validate returned values or grant execution authority. A host selecting an MCP tool may explicitly retain, replace or omit the remote schema. Preset input-schema projection leaves this field unchanged.

The current Chat Completions encoder emits only function name, description and input parameters. It does not send outputSchema as a provider extension. Custom model clients receive the complete ToolDef and own any supported provider projection.

The derived domain codec reads an absent or explicit-null outputSchema as None and omits None when encoding. Non-null schema ASTs retain their structure; Some(Json.Null) does not round-trip as present. Existing constructors and named copies can omit the defaulted field; positional pattern matches and Product arity now have seven fields (including returnMode), and downstream binaries must be rebuilt. No schema evaluation or custom compatibility codec is introduced.

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for this scope, with no findings. Current core identities are linked below; current integration tests belong to the [MCP binding acceptance](../../../../../../../../../mcp/src/main/scala/com/weisgnosis/agents/mcp/tool/README.md#verification-and-acceptance). Formatting and full `verify` passed; current repository totals are recorded in the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance), and scoped verification evidence remains under [fixed raw arguments](#fixed-raw-arguments).

| Reviewed source/test | Git blob |
| --- | --- |
| [ToolDef.scala](ToolDef.scala) | [Current identity](../agent/README.md#host-selected-return-direct-completion) |
| [ToolDefSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/tool/ToolDefSpec.scala) | [Current identity](../agent/README.md#host-selected-return-direct-completion) |
| ChatCompletionsRequestSpec.scala | Current identity: [return-direct scope](../agent/README.md#host-selected-return-direct-completion); output-schema and returnMode omission remain covered. |

## Local return mode

ToolDef.returnMode is a host-selected Continue (default) or Direct policy, not model/output metadata. Only an ordinary all-local, all-Direct successful batch may return processed presentation content without another model call, after joined cleanup and approval/Stop precedence. Full Tool observations remain in history. See the [runtime contract and seven-field migration](../agent/README.md#host-selected-return-direct-completion) for exact limits, current identities and full verification.

## Incremental execution

`Tool.streamWithContext(input, invocation)` produces `ToolExecutionUpdate.Progress(payload)` followed by exactly one `Completed(out)`. The default delegates to executeWithContext once; existing implementations need no new abstract method. Fixed-argument wrappers retain the stream override and existing input policy. Toolkit consumes the stream inside an owned scope; missing completion, any update after completion, stream failure or cleanup failure prevents that value becoming a successful final observation. Only EOF and successful cleanup admit encodeResultChecked, whose existing Success/Error restriction remains.

Progress contains provisional content and metadata, not call IDs or execution state. The runtime supplies identity/context and strips reserved control metadata. Nonempty output blocks publish ordered text/data deltas; metadata-only updates publish nothing. Progress never enters canonical history, result processors or later model input. Exactly one authoritative final result is persisted and processed. After any nonempty progress for that invocation occurrence, the final aggregate payload is not replayed as deltas; ToolResultEnd still carries final text/state/metadata. Complete typed final content remains in history/result, so provisional deltas are not a reconstruction of the final payload. Empty progress and ordinary tools retain aggregate final publication. Repeated call IDs do not share suppression state; inline provider results retain their separate path.

Migration: streamWithContext is a defaulted source-compatible method, but dependent binaries should be rebuilt. ToolExecutionUpdate has no wire codec; no event schema, public callback setter, mutable emitter or hook fanout is added. Existing executeSafe remains a final-result operation and discards progress. Caller cancellation joins stream-owned cleanup and must preserve its complete cause; errors without interruption remain Error observations. Consumers cancelling an event stream still abandon later output.

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for the six-file incremental scope, with no findings. Unchanged identities and the renewed current ReAct identity link are below. Focused `tool-stream-focused-green.log` passed 32 tests in 841 ms, including the unchanged legacy external-cancellation regression.
- [x] Full `scalafmtAll scalafmtSbt verify` passed in `tool-stream-verify.log`, with zero compiler warnings or test failures. Current repository totals and task timings are in the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).
- [x] `tool-stream-legacy-cancel-red.log` records 31 passing tests and one failure in 848 ms against uncommitted draft Tool `d4adead5f19a6b775cf00dbb7f3bc2da66fbb00e` and Toolkit `caffe50fd4de998be892d0a65add4e8f200f9885`: the unchanged ToolExecutionSpec external-cancellation assertion lost its held cleanup defect. This is a new-feature draft defect, not a defect in the committed baseline. The corrected Toolkit consumer uses a scoped public channel pull and immutable chunk fold, preserving the complete Cause before final normalization. The default Tool bridge is unchanged; no producer fiber or output journal is added. Custom streams remain responsible for their own internal effect/combinator semantics.

| Current incremental execution file | Git blob |
| --- | --- |
| [Tool.scala](Tool.scala) | [Current identity](../json/README.md#exact-domain-and-tool-output) |
| [Toolkit.scala](Toolkit.scala) | [Current identity](../json/README.md#exact-domain-and-tool-output) |
| [ToolExecutionUpdate.scala](ToolExecutionUpdate.scala) | `36f338a5d77db0660a283be80b709fb67ec10391` |
| [ReActAgent.scala](../agent/ReActAgent.scala) | [Current identity](../agent/README.md#acting-stop-decisions) |
| [ToolExecutionUpdateSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/tool/ToolExecutionUpdateSpec.scala) | `7db9194fc19f472c34ecd1a552403692f28bc32a` |
| [ReActAgentToolEventSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentToolEventSpec.scala) | Current fixture identity and verification: [shared-Toolkit isolation](../agent/README.md#shared-toolkit-destination-isolation). |

## Typed execution observations

`Tool.encodeResult(out)` defaults to the existing `encodeOutput(out)` as a successful text observation. Implementations may override it to return `ToolExecutionResult(payload, state)`, where `ToolResultPayload` retains ordered content blocks and metadata. Toolkit accepts only Success or Error from this projection; Running, Interrupted and Denied become descriptive Error observations. Authorization denials are produced separately by the runtime, and actual interruption remains cancellation.

ReAct supplies the original call ID, name and accepted state, then applies the payload through `ToolResultPayload.applyTo`. Tool-provided metadata cannot set serverTool, agentscope_server_tool or agentscope_suspended. Typed local content survives history persistence, result processing and ordered text/data events. The current ChatCompletions request codec still rejects non-text tool output; provider multimodal support remains separate; incremental local execution has its separate contract above.

Migration: String `ToolExecutionResult(...)` constructors and the `output` text projection remain available. The projection joins Text blocks with newlines and omits other block types. The canonical constructor, generated copy/unapply and derived JSON shape now use `payload` and explicit `state`; persisted legacy output/state JSON requires host migration. There is no manual compatibility codec. Existing Tool implementations inherit encodeResult without an abstract-method migration.

Interrupted compound causes are refailed with `orDie`: interruption and cleanup defects survive, while typed Throwable leaves become defects to retain the UIO contract. Ordinary non-interrupted failures and defects remain Error observations.

The additive encodeResultChecked returns Either[AgentError, ToolExecutionResult]. Its default preserves custom encodeResult projections; fromFunction uses ExactJson and fixed wrappers forward it. Toolkit normalizes encoding failures/defects after joined cleanup without swallowing interruption. Legacy direct encodeOutput and encodeResult calls are unchanged. There is no new abstract-method requirement; rebuild dependent binaries. [Exact output acceptance](../json/README.md#exact-domain-and-tool-output) owns current Tool/Toolkit/ToolExecutionSpec identities and the numeric regression.

## Invocation event capability

`Tool.executeWithContext(input, invocation)` delegates to `execute(input)` by default. Existing tools need no implementation change. The original `Toolkit.executeSafe(name, arguments)` uses `ToolInvocation.Detached`; the overload accepts an explicit host capability. These execution dependencies have no JSON codec and cannot be supplied through model arguments.

ReAct creates `Active(context, call, callOrdinal, events)` only for approved local execution, after persisting approved arguments. The zero-based tool occurrence distinguishes repeated model call IDs. Denied calls and matched provider inline results do not invoke this handler. Runtime request/reply correlation and the approved call are retained.

`ToolEventPort` exposes publication only. Its scoped lease closes after execution cleanup and before result processing. Publication and closure are serialized: Published means enqueued on the runtime's unbounded hub, not consumed; retained ports return Closed after revocation. Cancellation joins cleanup and preserves interruption and cleanup defects. The lease does not own a child task, provide replay/backpressure, or forward child events automatically. Trusted contextual tools remain responsible for the events they publish; the port does not authenticate arbitrary event payloads.

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for this capability, with no unresolved findings. Unchanged capability identities are below; current Tool/Toolkit identities are in [incremental execution](#incremental-execution), and current ReActAgent identity is in [acting-stop decisions](../agent/README.md#acting-stop-decisions). Three grouped tests cover approved occurrences, legacy/detached/denied execution, concurrent revocation and actual ReAct cancellation. Formatting, fatal-warning compilation and full verification passed in `tool-invocation-verify.log`; current totals are in the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).
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
- [ToolExecutionUpdate](ToolExecutionUpdate.scala)
- [Toolkit](Toolkit.scala)

See the [repository architecture](../../../../../../../../../../ARCHITECTURE.md) for integration boundaries and invariant definitions.

## Command screening

`isDangerousCommand` is a conservative lexical warning helper, not shell parsing or execution authorization. It normalizes input and configured fragments with Locale.ROOT, allows whitespace between fragment words, and requires lexical boundaries around alphanumeric fragment ends. It detects recursive chmod/chown, path-qualified dd, mkfs suffix commands, and the `/dev/` redirection prefix without matching dd inside address. Quoted dangerous text can still match. A false result does not establish command safety; plan admission uses its separate typed inspection and permission policy.

## Current review acceptance

- [x] Independent FP/ZIO and invariant reviewer `path_review` approved the advisory command-screening fix and regressions: source blob `3d7a102d03c47719271ad2e4ba791af81247170e`, test blob `981212ee32c51cf92d12d844f4fe5a7403f70b78`. All eight focused tests pass.
- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for typed execution payloads, state admission, runtime control protection and compound-cause preservation. Both approved this scope with no findings; unchanged component identities are below, and current Tool/Toolkit identities are in [incremental execution](#incremental-execution), and current ReActAgent identity is in [acting-stop decisions](../agent/README.md#acting-stop-decisions).
- [x] Full formatting, fatal-warning compilation and verification passed; current repository totals are recorded in the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance), and scoped verification evidence remains under [fixed raw arguments](#fixed-raw-arguments).
- [x] Existing processor contracts below retain their scoped acceptance and unchanged implementations. Authorization acceptance is scoped by [fixed raw arguments](#fixed-raw-arguments) and [admitted tool access](#admitted-tool-access).

| Typed-result source/test | Reviewed Git blob |
| --- | --- |
| [ToolExecutionResult.scala](ToolExecutionResult.scala) | 1c3e999a1363e6a042a0c83186006fda2060992f |
| [ToolExecutionSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/tool/ToolExecutionSpec.scala) | [Current identity](../json/README.md#exact-domain-and-tool-output) |
| [ReActAgentToolResultProcessorSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentToolResultProcessorSpec.scala) | 703c7abec55bb3b4ea5d429ecefa76c9f1557b16 |

The table records unchanged typed-result components. Current Tool and Toolkit identities are recorded under [incremental execution](#incremental-execution); current ReActAgent identity is in [acting-stop decisions](../agent/README.md#acting-stop-decisions); separate invocation and [direct event forwarding](../agent/README.md#direct-event-forwarding) contracts retain their scoped evidence. Typed result behavior is unchanged. Result-bearing stream acceptance remains in the [agent contract](../agent/README.md#result-bearing-streams).

Platform-specific symlink/junction behavior and hostile filesystem races remain outside the verified guarantees. Current command-screening evidence is recorded in the [test README](../../../../../../../test/scala/com/weisgnosis/agents/core/tool/README.md#command-screening-regressions).

## Completed result processing

ReActAgent.make and Builder.toolResultProcessor accept a ToolResultProcessor, defaulting to identity. Its legacy process callback returns ZIO[Scope, AgentError, ToolResultPayload]. The concrete processDecision default invokes it once and returns Continue. A host may select deciding for Continue/Stop; decision composition stops only that observation chain, while legacy payload-only composition still evaluates all stages. The [runtime contract](../agent/README.md#acting-stop-decisions) defines full-batch draining, failure precedence and migration. The payload contains output blocks and metadata only. applyTo preserves the original call ID, name, state, and serverTool/agentscope_server_tool/agentscope_suspended control keys. Composition applies each payload safely before passing the observation onward; host processors cannot change execution authority through these fields.

After local execution finishes, the runtime atomically appends the complete raw observation and captures its history position. It then runs processing interruptibly in a separate scope. Typed processing failures restore the original payload; defects propagate. Scope cleanup finishes before a short masked section replaces that exact observation and publishes End, plus aggregate text/data deltas only when that execution emitted no nonempty progress. Parallel calls own distinct append positions, including calls that reuse IDs; completion may arrive in a different order. Processing assumes history remains append-only during the acting phase under the run lock.

Cancellation during processing keeps the completed raw result and reconciles only unfinished executions. It does not rerun the tool or publish a false ToolResultEnd. A subsequent model request sees the committed processed payload; observers may see the raw result while processing is pending. Processor side effects already performed before failure/cancellation are not rolled back. Callbacks must not reenter a mutating agent operation that requires the held run lock.

Tool execution can return completed typed payloads. A processor can replace their output blocks and metadata before publication; the provisional execution path has the separate contract above. Empty processed output emits only End after the original Start. Matched inline provider results use this processor after raw assistant persistence, retaining their exact result ordinal. Their Start, ordered text/data deltas, and End are published only after processing and scope cleanup. Inline processing bypasses local authorization/execution and creates no Role.Tool message. Supplied history uses the separate opt-in ToolResultEvictor.preparer adapter.

Appending the optional make parameter preserves ordinary callers; positional Builder pattern matches must follow the current Builder constructor. Processor values are runtime dependencies, not serialized agent configuration.


## Admitted tool access

`AdmittedToolAccess.ReadOnly | Mutating` is transient host/runtime metadata with no codec. The additive concrete `authorize(request, admittedAccess: Option[AdmittedToolAccess])` overload delegates to the original abstract single-argument method by default. Lambda construction is unchanged; the partial-confirmation extension adds a Deferred decision codec case. The separate checkpoint-confirmation increment appends optional callOrdinal to ToolAuthorizationRequest and is accepted.

ReAct resolves the tool from the same admitted Toolkit used for the request batch and samples its actual Tool.isReadOnly accessor, including host overrides; it does not trust the definition flag or model metadata. Missing tools provide None. Sampling and overload invocation stay inside the suspended authorization decision boundary, preserving existing defect/interruption handling and ownership. Fixed preparation, approval validation and dispatch ordering are unchanged.

Custom authorizers compile without implementing the overload. To consume or forward the metadata they must override it explicitly; legacy wrappers otherwise delegate through the old method. The harness SubagentFactory composition now forwards the sample through live parent-denial checks; its scoped acceptance belongs to the [child construction contract](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#admitted-tool-plan-access). Rebuild dependent binaries; source compatibility is not a binary compatibility claim. The opt-in [harness Plan adapter](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md#dynamic-plan-admission) combines this view with existing permission policy and confirmation rather than treating read-only as an Allow decision.

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for the two identities below and the linked [runtime integration](../agent/README.md#run-owned-tool-group-activation), with no findings. Full `scalafmtAll scalafmtSbt verify` passed in `plan-admission-verify.log`, with zero compiler warnings or failures; current totals belong to the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).

| Reviewed file | Git blob |
| --- | --- |
| [ToolAuthorizer.scala](ToolAuthorizer.scala) | [Current confirmed-batch identity](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#complete-batch-checkpoint-confirmations) |
| [ToolAuthorizerSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/tool/ToolAuthorizerSpec.scala) | [Current confirmed-batch identity](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#complete-batch-checkpoint-confirmations) |

## Scoped local authorization

ReActAgent.make and Builder.toolAuthorizer accept a ToolAuthorizer, defaulting explicitly to allow for compatibility. Installing a host authorizer is necessary to enforce permission policy. ToolAuthorizationRequest contains a fresh approval UUID, the originating ModelStreamContext, the call with framework-fixed arguments prepared before authorization, and an optional original tool-use ordinal. ReAct supplies that ordinal independently of repeated call IDs; legacy construction and missing JSON fields default to None. Four-field positional patterns need updating and dependent binaries must be rebuilt. The [complete-batch adapter](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#complete-batch-checkpoint-confirmations) is accepted. ToolAuthorization exposes Approved(input), Denied(reason), or Deferred(reason); the partial-confirmation extension has final independent acceptance. Exhaustive matches and decision-codec consumers must handle Deferred; identity, name, metadata, and execution authority are not replaceable decision fields. Requests and decisions have derived codecs, but serialization alone grants no execution authority.

The runtime persists the assistant message before authorization and captures its exact history position. Every pending local call passes preparation and authorization checks; unresolved provider-owned calls bypass local dispatch and authorization, and rejected preparation or signed changes do not invoke the authorizer. Matched provider inline results bypass local authorization. Each callback owns an interruptible scope; cleanup finishes before result Start and local execution. The request scope does not extend across the tool operation. Hosts must not reenter mutating agent operations while the run lock is held.

Approved JSON must have unique keys at every depth and retain all attached fixed values. A changed input is denied if either thoughtSignature or reasoningDetails metadata is present on the call or any Thinking block in its originating assistant message, including empty arrays, malformed or opaque values. Otherwise the runtime rewrites only that call's original occurrence, clears cached content, and stores its original input under agentscope_original_tool_input. Identity, other metadata, and protocol state remain unchanged. The next model request and concurrent history readers see the approved arguments before the tool executes. This provenance key records the runtime rewrite; arbitrary preexisting metadata is not authenticated evidence. No JSON Schema validation is added.

Denials never execute the tool. Typed PermissionDenied failures retain their reason while the runtime retains call identity. Non-interrupted authorizer failures and finalizer defects become Denied observations with a generic authorization-failure reason. Interrupted compound causes propagate intact with typed Throwable leaves mapped to defects; cleanup defects are not normalized into denial or discarded. This explicit fail-closed normalization prevents dangling calls without classifying host failures as provider errors. Cancellation propagates and reconciles unresolved calls as Error; completed siblings survive. Denials use the existing result processor and correlated Start/Delta/End path, so approved host processors can transform their observation content without changing Denied state.

Interruption reconciliation first consumes eligible inline observations, then matches remaining locally dispatched occurrences against Role.Tool results by ID and optional name. These pools cannot consume each other's observations. The internal Msg.mapToolUseAt helper updates by tool ordinal, preserving role/content compatibility; duplicate IDs and identical calls do not collapse into one rewrite.

Appending the optional make argument preserves ordinary calls; positional Builder matches must follow the current Builder constructor. This core extension point is implemented by the opt-in harness InteractivePermissionAuthorizer for policy checks, interactive confirmation, and atomic policy updates. Durable suspension/resumption and full upstream permission event ordering remain pending.


The optional [interactive harness adapter](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md) owns policy lifetime and approval callbacks; the core remains independent of harness dependencies.

Current authorizer-port identities are in [admitted tool access](#admitted-tool-access); unchanged fixed-argument evidence is in [fixed raw arguments](#fixed-raw-arguments); separate runtime/processor contracts remain in the [agent README](../agent/README.md).

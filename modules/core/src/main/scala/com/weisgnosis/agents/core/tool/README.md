# Typed tools and path validation

Tool keeps its input/output codecs and effectful handler together. Toolkit registration and merging return Either[AgentError, Toolkit], including duplicate-name failures. Execution normalizes tool failures and defects into Error observations; interruption propagates. PathSafety rejects parent traversal before canonicalization and resolves existing ancestors through toRealPath. Canonical checks do not provide an atomic sandbox against hostile filesystem mutation.

## Implementation

- [PathSafety](PathSafety.scala)
- [Tool](Tool.scala)
- [ToolDef](ToolDef.scala)
- [ToolResultProcessor](ToolResultProcessor.scala)
- [ToolAuthorizer](ToolAuthorizer.scala)
- [ToolExecutionResult](ToolExecutionResult.scala)
- [Toolkit](Toolkit.scala)

See the [repository architecture](../../../../../../../../../../ARCHITECTURE.md) for integration boundaries and invariant definitions.

## Current review acceptance

- [x] Reviewed revision: `fb80428`; accepted files: PathSafety.scala and PathSafetySpec.scala.
- [x] Independent final approval: `review_harness`, reconfirmed for unchanged code on 2026-09-29 after corrections and cross-review.
- [x] No unresolved findings in the accepted scope: Parent traversal rejection before normalization and its deterministic regression.
- [x] Verification at the reviewed revision: `sbt verify`, 108 core and 40 harness tests, no failed tests or compiler warnings.

This is the accepted reference behavior for the scope above. Actual symlink/junction behavior was not dynamically verified; this acceptance does not cover all neighboring tool APIs. Changes to this behavior require renewed independent review and an updated acceptance entry.

## Completed local-result processing

ReActAgent.make and Builder.toolResultProcessor accept a ToolResultProcessor, defaulting to identity. Its suspended process callback returns ZIO[Scope, AgentError, ToolResultPayload]. The payload contains output blocks and metadata only. applyTo preserves the original call ID, name, state, and serverTool/agentscope_server_tool/agentscope_suspended control keys. Composition applies each payload safely before passing the observation onward; host processors cannot change execution authority through these fields.

After local execution finishes, the runtime atomically appends the complete raw observation and captures its history position. It then runs processing interruptibly in a separate scope. Typed processing failures restore the original payload; defects propagate. Scope cleanup finishes before a short masked section replaces that exact observation and publishes its text/data deltas and End. Parallel calls own distinct append positions, including calls that reuse IDs; completion may arrive in a different order. Processing assumes history remains append-only during the acting phase under the run lock.

Cancellation during processing keeps the completed raw result and reconciles only unfinished executions. It does not rerun the tool or publish a false ToolResultEnd. A subsequent model request sees the committed processed payload; observers may see the raw result while processing is pending. Processor side effects already performed before failure/cancellation are not rolled back. Callbacks must not reenter a mutating agent operation that requires the held run lock.

Tool execution still returns completed text. A processor can replace its output blocks and metadata for the existing typed result publisher; this does not implement incremental tool execution or native structured tool return values. Empty processed output emits only End after the original Start. Inline provider results bypass this processor, and preexisting history is not processed automatically. These integration gaps remain explicit.

Appending the optional make parameter preserves ordinary callers; positional Builder pattern matches must account for the appended processor and authorizer fields. Processor values are runtime dependencies, not serialized agent configuration.

- [x] `review_fp` and `review_harness` approved the final eight-file local-result processing/eviction increment against `8850a60` on 2026-09-29. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 466 core and 45 harness tests.

## Current processor and eviction identities

Unchanged processor/eviction identities from the scope against `8850a60`; commit `a34201f` preserves its full eight-file scope. The changed ReActAgent identity belongs to the authorization scope below:

| File | Git blob |
| --- | --- |
| [ToolResultProcessor.scala](ToolResultProcessor.scala) | 72af085f101cb253a9c24004df0c32aed6488478 |
| [ToolResultProcessorSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/tool/ToolResultProcessorSpec.scala) | 76dfbb00d2aa68613b8770cf727b418d33f79b93 |
| [ReActAgentToolResultProcessorSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentToolResultProcessorSpec.scala) | 9096fbc789d8e3b81037def4c23e8d3a843298a5 |
| [ToolResultEvictor.scala](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/eviction/ToolResultEvictor.scala) | 525c559c64141e6c3721ad6ee0e391681432b12f |
| [ToolResultEvictorIntegrationSpec.scala](../../../../../../../../../harness/src/test/scala/com/weisgnosis/agents/harness/eviction/ToolResultEvictorIntegrationSpec.scala) | e57e0ef9cbbbe2ce0b0a3bea056b843346545644 |
| [ToolResultStoreSpec.scala](../../../../../../../../../harness/src/test/scala/com/weisgnosis/agents/harness/eviction/ToolResultStoreSpec.scala) | 50206a7f3310ea0d8939c892dae0ee6b6e670215 |
| [EvictionTestSupport.scala](../../../../../../../../../harness/src/test/scala/com/weisgnosis/agents/harness/eviction/EvictionTestSupport.scala) | 7bcf8ad1d2ad45469ac91bec9e7b1079e1e64d92 |

## Scoped local authorization

ReActAgent.make and Builder.toolAuthorizer accept a ToolAuthorizer, defaulting explicitly to allow for compatibility. Installing a host authorizer is necessary to enforce permission policy. ToolAuthorizationRequest contains a fresh approval UUID, the originating ModelStreamContext, and the original call. ToolAuthorization exposes Approved(input) or Denied(reason); identity, name, metadata, and execution authority are not replaceable decision fields. Requests and decisions have derived codecs, but serialization alone grants no execution authority.

The runtime persists the assistant message before authorization and captures its exact history position. Every pending call that reaches local dispatch is authorized, including provider-marked calls without matched inline observations. Matched provider inline results bypass local authorization. Each callback owns an interruptible scope; cleanup finishes before result Start and local execution. The request scope does not extend across the tool operation. Hosts must not reenter mutating agent operations while the run lock is held.

Approved JSON must have unique keys at every depth. A changed input is denied if either thoughtSignature or reasoningDetails metadata is present, including malformed or opaque values. Otherwise the runtime rewrites only that call's original occurrence, clears cached content, and stores its original input under agentscope_original_tool_input. Identity, other metadata, and protocol state remain unchanged. The next model request and concurrent history readers see the approved arguments before the tool executes. This provenance key records the runtime rewrite; arbitrary preexisting metadata is not authenticated evidence. No JSON Schema validation is added.

Denials never execute the tool. Typed PermissionDenied failures retain their reason while the runtime retains call identity. Authorizer defects and finalizer defects become Denied observations with a generic authorization-failure reason. This explicit fail-closed normalization prevents dangling calls without classifying host failures as provider errors. Cancellation propagates and reconciles unresolved calls as Error; completed siblings survive. Denials use the existing result processor and correlated Start/Delta/End path, so approved host processors can transform their observation content without changing Denied state.

Interruption reconciliation first consumes eligible inline observations, then matches remaining locally dispatched occurrences against Role.Tool results by ID and optional name. These pools cannot consume each other's observations. The internal Msg.mapToolUseAt helper updates by tool ordinal, preserving role/content compatibility; duplicate IDs and identical calls do not collapse into one rewrite.

Appending the optional make argument preserves ordinary calls; positional Builder matches must include the new authorizer field. This core extension point is implemented by the opt-in harness InteractivePermissionAuthorizer for policy checks, interactive confirmation, and atomic policy updates. Durable suspension/resumption and full upstream permission event ordering remain pending.

- [x] `review_fp` and `review_harness` approved the final five-file core authorization increment against `a34201f` on 2026-09-29. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 481 core and 45 harness tests, including fifteen added authorization tests. Interactive and durable policy adapters are excluded from this acceptance.

## Current authorization identities

Final formatted five-file scope against `a34201f`:

| File | Git blob |
| --- | --- |
| [ToolAuthorizer.scala](ToolAuthorizer.scala) | d0e3d498ddabc4f57b0ea28203b048b9696bb1dd |
| [ReActAgent.scala](../agent/ReActAgent.scala) | 0568d6e87c7e19a6796d56662743d19bacd0293f |
| [Msg.scala](../model/Msg.scala) | 0f16756e28e6d24a24658e69769058d91979855f |
| [ToolAuthorizerSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/tool/ToolAuthorizerSpec.scala) | 6e9b21dd13f4c23281f0672033bcc465ee8d3664 |
| [ReActAgentToolAuthorizerSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentToolAuthorizerSpec.scala) | aeb56e34bb6a7c7f2fce1c956a21e79d6e4ac6d9 |

The optional [interactive harness adapter](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md) owns policy lifetime and approval callbacks; the core remains independent of harness dependencies.

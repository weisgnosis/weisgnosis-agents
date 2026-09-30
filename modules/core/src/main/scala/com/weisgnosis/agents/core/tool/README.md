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

## Command screening

`isDangerousCommand` is a conservative lexical warning helper, not shell parsing or execution authorization. It normalizes input and configured fragments with Locale.ROOT, allows whitespace between fragment words, and requires lexical boundaries around alphanumeric fragment ends. It detects recursive chmod/chown, path-qualified dd, mkfs suffix commands, and the `/dev/` redirection prefix without matching dd inside address. Quoted dangerous text can still match. A false result does not establish command safety; plan admission uses its separate typed inspection and permission policy.

## Current review acceptance

- [x] Independent FP/ZIO and invariant reviewer `path_review` approved the advisory command-screening fix and regressions: source blob `3d7a102d03c47719271ad2e4ba791af81247170e`, test blob `981212ee32c51cf92d12d844f4fe5a7403f70b78`. All eight focused tests pass.
- [x] Accept this scope after `scalafmtAll scalafmtSbt verify` on 2026-09-30: 561 core and 609 harness tests passed with zero compiler warnings or failures.
- [x] Existing processor and authorization contracts below were accepted by review_fp and review_harness in their recorded source revisions; this change does not modify those implementations.

Platform-specific symlink/junction behavior and hostile filesystem races remain outside the verified guarantees. Current command-screening evidence is recorded in the [test README](../../../../../../../test/scala/com/weisgnosis/agents/core/tool/README.md#command-screening-regressions).

## Completed result processing

ReActAgent.make and Builder.toolResultProcessor accept a ToolResultProcessor, defaulting to identity. Its suspended process callback returns ZIO[Scope, AgentError, ToolResultPayload]. The payload contains output blocks and metadata only. applyTo preserves the original call ID, name, state, and serverTool/agentscope_server_tool/agentscope_suspended control keys. Composition applies each payload safely before passing the observation onward; host processors cannot change execution authority through these fields.

After local execution finishes, the runtime atomically appends the complete raw observation and captures its history position. It then runs processing interruptibly in a separate scope. Typed processing failures restore the original payload; defects propagate. Scope cleanup finishes before a short masked section replaces that exact observation and publishes its text/data deltas and End. Parallel calls own distinct append positions, including calls that reuse IDs; completion may arrive in a different order. Processing assumes history remains append-only during the acting phase under the run lock.

Cancellation during processing keeps the completed raw result and reconciles only unfinished executions. It does not rerun the tool or publish a false ToolResultEnd. A subsequent model request sees the committed processed payload; observers may see the raw result while processing is pending. Processor side effects already performed before failure/cancellation are not rolled back. Callbacks must not reenter a mutating agent operation that requires the held run lock.

Tool execution still returns completed text. A processor can replace its output blocks and metadata for the existing typed result publisher; this does not implement incremental tool execution or native structured tool return values. Empty processed output emits only End after the original Start. Matched inline provider results use this processor after raw assistant persistence, retaining their exact result ordinal. Their Start, ordered text/data deltas, and End are published only after processing and scope cleanup. Inline processing bypasses local authorization/execution and creates no Role.Tool message. Supplied history uses the separate opt-in ToolResultEvictor.preparer adapter.

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

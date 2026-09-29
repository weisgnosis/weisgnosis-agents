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

AgentResult, data-block boundaries/string deltas, tool-result data deltas, iteration limits, stop requests, and denied-call payloads are available. RequestStop defaults absent/JSON-null generation reasons to MiddlewareStopRequested; AllToolsDenied defaults absent/JSON-null calls to an empty Chunk. Supplied malformed values fail. The runtime now publishes correlated iteration-limit events and ordered inline provider tool-result text/data events with full metadata. Finalized AgentResult publication is also implemented; remaining payload publication and stop/resume authority are pending.

AgentStart/AgentEnd share a fresh replyId per engine attempt. streamEvents drains ordered events and propagates the original runtime exit using a private completion signal. Provider-published end events do not control termination. Inline results are matched once per server-call occurrence; local calls sharing IDs remain pending. Reconciliation consumes completed local observations by ID, provided name, and occurrence, preserving unfinished duplicate calls. Structured decoding runs inside the attempt timeout, followed by masked history commit and AgentResult publication before AgentEnd. Cancellation after commit may follow an already published result. Auto fallback retains two attempt boundary pairs under one run lock. See the [agent README](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md).

## Tools and paths

[Tool](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/Tool.scala) defines typed decoding, encoding, and suspended execution. Throwable is allowed at the external adapter boundary; [Toolkit](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/Toolkit.scala) normalizes tool failures into Error observations while preserving cancellation.

Registration, withTool, and merging return Either[AgentError, Toolkit]. Duplicate names fail. Resolve returns Option. Existentially stored tools retain type-safe execution without casts.

[PathSafety](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/PathSafety.scala) exposes effectful home expansion/canonicalization and pure sensitive-name screening. Errors remain typed. Reject parent traversal before normalization can erase link-aware traversal semantics.

## Client and agent

[LlmClient.streamTurn](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/LlmClient.scala) receives history, definitions, LlmConfig, event hub, and ModelStreamContext, returning ZIO[Scope, AgentError, TurnOutput]. LlmClient implementations include scripted clients and ChatCompletionsClient. Transports must register resources in the supplied request scope. ChatCompletionsTransport owns an inner scoped HTTP operation; the live client publishes validated incremental events. ReAct supplies fresh per-request identity and publishes successful model-end only after request cleanup.

[ChatCompletionsRequest.encode](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/ChatCompletionsRequest.scala) is a pure Either-based text/function body encoder. It requires complete local call/result pairs and rejects unsupported media, server/signed calls, suspended/running results, malformed configuration, and ambiguous JSON. ReasoningReplay explicitly selects omission or unsigned latest-assistant replay. Credentials and endpoint are excluded from the body. See the [client contracts](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md) for supported fields and limits.

[ReActAgent](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/ReActAgent.scala) construction through make/builder returns IO[AgentError, ReActAgent]. A client is mandatory; invalid iteration, timeout, and retry bounds fail.

- run accepts Msg or Chunk[Msg] and produces AgentResult.
- call produces the reply Msg.
- streamEvents is scoped and preserves model failures.
- history returns UIO[Chunk[Msg]].
- interrupt signals the active run.
- runStructured uses a JsonCodec, schema, and StructuredMode and returns AgentResult with the decoded value.
- streamStructuredEvents uses the same structured preparation and commit path and returns ZStream[Scope, AgentError, AgentEvent]. It emits the finalized Msg through AgentResultEvent; malformed or interrupted preparation fails without publishing a finalized result.

[AgentConfig](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/AgentConfig.scala) defaults to 20 iterations, 5 minutes, two thinking retries, and ToolExecution.Parallel. [AgentResult](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/AgentResult.scala) contains iterationCount, optional ChatUsage, and one AgentRunState; Boolean accessors are derived queries.

[StructuredOutput](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/StructuredOutput.scala) hoists both definition namespaces without changing references. Native mode supplies responseFormat. Auto selects from LlmConfig.structuredOutputCapability, preserving prior routing for Unknown. Native() permits application tools; Native(NativeToolSupport.Unsupported) permits native only without them; Unsupported selects synthetic immediately. Explicit modes override declarations. Synthetic clears responseFormat, while native Auto retains status-400/422/501 fallback. Successful generate_response terminates without an extra model turn after the acting phase completes. Non-Completed runs produce AgentError.Interrupted without replacing reconciled history. The final reply retains generating-request thinking and usage; AgentResult.usage aggregates the run. Compression keeps ordinary tool pairs and unrelated empty messages. Native payload resolution prefers existing structured metadata over visible text and uses the same AST for decoding and committed metadata.

PostReasoningHook accepts typed Keep/Replace/Discard decisions before state persistence and dispatch. Dedicated ReasoningHookError prevents provider-fallback confusion. Replacement restores model-owned usage, discard preserves thinking retries, and invocation-local accounting spans both Auto attempts. See the [agent contracts](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md) for scope, ordering, and migration details.

ToolAuthorizer.authorize accepts ToolAuthorizationRequest(requestId, context, call) and returns ZIO[Scope, AgentError.PermissionDenied, ToolAuthorization]. Approved(input) and Denied(reason) are the only decisions. ReActAgent.make and Builder.toolAuthorizer default to explicit allow. Host failures fail closed; approved rewrites preserve runtime identity, clear stale content, and retain original-input provenance. See the [tool contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md) for provider reasoning metadata restrictions and cancellation behavior. The harness InteractivePermissionAuthorizer implements policy and interactive confirmation through this port; durable resume remains pending.

HistoryPreparer.prepare returns ZIO[Scope, AgentError.HistoryPreparationError, Chunk[Msg]], defaulting to identity. ReAct closes its scope, validates actual rewrites, commits canonical history, then emits ModelRequestStart. Attempt-local Ready/PersistedAssistant/Recovered progress replaces history-length offsets. Internal ToolHistory supplies shared occurrence analysis; structural checks do not prove summary fidelity. ConversationCompactor.preparer maps harness failures to the dedicated channel. See the [agent contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md).

ModelInputTransform projects a request-only history after canonical preparation commits and before ModelRequestStart. Its scope closes before structural validation and dispatch. Canonical history, recovery positions, and usage remain authoritative; projection failures use ModelInputError and cannot trigger native fallback. Complete pairs may be omitted without losing canonical eviction or stored payloads. The port transforms history only; semantic fidelity and newly injected content remain host responsibilities.

## Harness

| Component | Contract | Declaration |
| --- | --- | --- |
| ConversationCompactor | Injected summarizer/counter/hooks, occurrence-safe cutoff, isolated inline pairs, signed-argument protection, summary rebuilding | [source](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/ConversationCompactor.scala) |
| MemoryFlushManager | make returns UIO; flush returns IO[AgentError, Unit]; one-manager serialization and scope-before-append | [source](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/MemoryFlushManager.scala) |
| DailyMemoryStore | Read curated/daily context and append by LocalDate; no curated writer or path input | [source](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/DailyMemoryStore.scala) |
| MemoryConsolidator | make validates budget 1..4000; consolidate returns typed no-op/commit/conflict outcomes | [source](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/MemoryConsolidator.scala) |
| ConsolidationStore | Abstract Revision, atomic snapshot, conditional atomic content/progress commit; no daily writer | [source](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/ConsolidationStore.scala) |
| MemoryRepository | inMemory returns separate daily/consolidation ports sharing Ref authority | [source](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/MemoryRepository.scala) |
| LocalMemoryRepository | scoped shared lease; journal recovery precedes every daily/consolidation operation | [source](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/LocalMemoryRepository.scala) |
| LocalDailyMemoryStore | open returns ZIO[Scope, AgentError, DailyMemoryStore]; cooperative lease, canonical paths, atomic daily replacement | [source](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/LocalDailyMemoryStore.scala) |
| MemoryExtractor | Scoped extract to visible text; fromClient removes tools/response formatting and preserves interruption | [source](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/MemoryExtractor.scala) |
| CompactorConfig | 120k trigger, 40k keep/protect, six retained messages, 2k pruning threshold | [source](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/CompactorConfig.scala) |
| ToolResultEvictor | Above 80k text chars, 2k head/tail, failure fallback, opt-in local-result processor | [source](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/eviction/ToolResultEvictor.scala) |
| ToolResultStore | Effectful persistence and authoritative key lookup; local backend requires WorkspaceTrust and hard-link support | [source](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/eviction/ToolResultStore.scala) |
| InteractivePermissionAuthorizer | Scoped gates, Promise confirmation, current-policy atomic merge, fail-closed decisions | [source](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/permission/InteractivePermissionAuthorizer.scala) |
| PermissionGate | Suspended scoped name/input safety decision with optional behavior | [source](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/permission/PermissionGate.scala) |
| PermissionEngine | Immutable evaluate/authorize/resume, no model request or durable storage | [source](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/permission/PermissionEngine.scala) |
| PermissionRule | Tool-name/glob matching against explicit argumentPath, command by default | [source](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/permission/PermissionRule.scala) |
| PermissionRequests | Promise registration before publication, one-shot resolution, cancellation cleanup | [source](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/permission/PermissionRequests.scala) |

ToolResultEvictor.preparer can precede ConversationCompactor.preparer via HistoryPreparer.andThen. Changed stages are validated before the next runs; runtime commits once after scope cleanup. Store-backed preview verification prevents cache chains while valid original content remains available in the configured key namespace. Custom stores must implement resolve; missing or forged references are raw candidates, while typed storage failures preserve input. See the [eviction contract and migration](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/eviction/README.md).

ToolResultStore.local accepts ResultStorageLocation.WorkspaceDefault or AbsoluteDirectory(path). Absolute selection requires an existing host-owned directory and leaves the unused workspace untouched. Canonical/type checks run before each operation; protection of external ancestors remains a host requirement. The default path and publication semantics are unchanged.

## Pending interfaces

General model-call middleware, broader legacy hooks, remote memory/transcript storage and scheduling, durable checkpoint storage, child factories/depth/registry, plan child propagation, and MCP are not implemented. Static plan inspection, opt-in permission admission and in-memory reviewed sessions, request projection and standalone draft storage are available. The live client supports a narrower scoped ModelTextTransform that changes only visible fragments before validated assembly and publication. Consult [planned behavior](mid-level-design.md) and the [tracker](tracker.md); architectural sketches are not importable APIs.

## Completion delta decoding

ReasoningModelAdapter.parseChunk returns Either[AgentError, Chunk[LlmChunk]]. Normal frames require explicit choices and exactly choice zero, or empty choices with usage. Tool indexes and token counts use strict nonnegative JSON integers; duplicate object keys, malformed recognized fields, unsupported roles/refusals/tool kinds, and legacy function_call fail explicitly. Provider error payloads become ModelError(200, message). Unknown extension payloads are not interpreted. Additional request extensions and remaining tool/subagent event integration remain pending; see the client README for migration and acceptance.

## Stream finalization

StreamAccumulator.empty is the construction boundary; feed and toTurnOutput now return Either[AgentError, ...]. Successful finalization requires stop/no-tools or tool_calls/tools and complete, unique-key JSON object arguments with stable tool identity. toInterrupted preserves partial observations, sanitizing invalid parsed/raw arguments to {}. Indexed thinking signatures and text retain block order; usage is the latest snapshot and may follow finish. JsonBoundary validates complete documents rather than trusting prefix-only AST decoding. These source-breaking contracts are documented in the client README. ChatCompletionsTransport owns HTTP request resources, enforces bounded response bodies, and requires [DONE] after a valid finish marker. ChatCompletionsClient connects it to the agent and incremental correlated events.

## Completed tool-result publication

ToolResultStart/Delta/End and ToolResultDataDelta carry optional ModelStreamContext. Local execution commits its Tool observation before publishing completed text and End; normalization preserves failures as Error observations. Inline results keep ordered text/data and metadata in their assistant message. Parallel calls retain per-call ordering and request provenance. Interruption reconciles only unfinished history without inventing completed result events. Processed completed local output blocks and metadata are published through the same event path. Native structured tool returns and incremental execution remain pending.

ToolResultProcessor returns a payload of output blocks and metadata. Runtime application preserves call identity/state and control keys; processing has a per-result scope and cannot erase raw completion on cancellation. ReActAgent.make and its builder accept the optional processor. The harness adapter processes new local observations only.

## Shared JSON authority

The library-internal json.JsonBoundary replaces client.ProtocolJson. Its decode operation requires one complete unique-key JSON document; validate checks AST fields directly. Msg structured extraction, agent preparation, synthetic wrapper decoding, and client boundaries share these contracts. Harness memory checkpoints and journals also reuse its syntax validation. Invalid authoritative metadata does not fall back to text, and duplicate wrapper fields fail before subtree selection. Native/synthetic HTTP integration is verified without claiming a general JSON Schema validator.

ToolResultProcessor applies to completed local observations and matched provider inline results. Local observations own appended Tool-message positions; inline observations own assistant positions and result ordinals. Scope cleanup precedes processed payload commit and completion events. A bounded failed acting phase pairs unrecorded local calls with Error observations while preserving the original cause. Explicit worker ownership retains cancellation-finalizer defects; ordinary cancellation retains recovery semantics. See the [agent contracts](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md).

## Transcript declarations

| Contract | Current behavior | Source |
| --- | --- | --- |
| TranscriptEntry | UUID/parent/recordedAt, structural stable key, typed payload and preview variants | [source](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/transcript/TranscriptEntry.scala) |
| TranscriptStore | per-transcript atomic append; inMemory provides Ref-backed authority | [source](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/transcript/TranscriptStore.scala) |
| SessionTranscriptWriter | pure projection, appendMessages returns committed observations, offload adapts compaction, completionObserver requires explicit anonymous policy | [source](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/transcript/SessionTranscriptWriter.scala) |
| LocalTranscriptStore | open(existingDirectory, HostControlled), scoped exclusive ownership, strict full-chain validation, atomic whole-log append | [source](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/transcript/LocalTranscriptStore.scala) |

These codecs are observation DTO codecs; LocalTranscriptStore uses a separate strict versioned disk envelope; remote backend APIs remain pending.

## Completion declarations

TurnCompletion(agentId, history, outcome: Exit[AgentError, AgentResult]) is runtime-only read-only data. TurnCompletionObserver.observe returns ZIO[Scope, AgentError.CompletionObservationError, Unit]. ReActAgent.make and Builder accept the optional observer. SessionTranscriptWriter.completionObserver(policy: AnonymousTranscriptPolicy) supplies the harness adapter. Required observation has one invocation-local Ref gate and a separate scope under the run lock; it cannot rewrite history or retry itself.

See the [completion source contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md) for exact publication/cancellation ordering, cause composition, callback restrictions, and early-stream error visibility.

## Memory trigger declarations

MemoryInterval.fromDuration validates a positive finite duration below Long.MaxValue nanoseconds. MemoryTrigger.throttled normalizes zero to Always; policy ADTs derive codecs and the opaque interval validates numeric JSON shape. MemoryTriggerGate.inMemory(policy) returns a shared capability with claim(operation, scope): IO[AgentError, MemoryTriggerDecision] and cleanup(retention): IO[AgentError, Int]. Policies are immutable per gate; owner identity is supplied by capability sharing, not inferred from strings.

The in-memory implementation uses Ref.Synchronized.modifyZIO around Clock.nanoTime plus the immutable map transition. Expiry uses elapsed subtraction. No scopes, background fibers or external resources are allocated by gate construction; the host owns its lifetime and cleanup cadence.

## Memory task declarations

MemoryTaskQueue.open(maxPending = 256): ZIO[Scope, AgentError, MemoryTaskQueue] owns the persistent worker. MemoryConversationToken.make returns a runtime identity in ZIO. submit(token)(task: => ZIO[Scope, AgentError, Unit]) returns an Accepted ticket or a typed Closed/Capacity rejection decision. MemoryTaskTicket.await returns Completed(Exit), Superseded or CancelledBeforeStart. Runtime tokens/tickets/Exit payloads deliberately have no wire codecs; only serializable rejection decisions derive JsonCodec.

TRef and TPromise commit registration, replacement, state transitions and receipt settlement atomically. Each job has its own child fiber; worker cancellation explicitly interrupts and awaits that child rather than discarding cleanup failures. A terminal worker finalizer also settles a claimed task if interruption preceded child registration. See the [memory source](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/MemoryTaskQueue.scala).

## Flush scheduler declarations

MemoryFlushScheduler.make(queue, gate, scope, manager): UIO[MemoryFlushScheduler] allocates a conversation capability. submit(history): IO[AgentError, MemoryFlushSubmission] returns Empty or Queued(MemoryTaskSubmission). completionObserver(registerTicket: MemoryTaskTicket => IO[CompletionObservationError, Unit]) supplies the core adapter. MemoryFlushManager.prepare is package-private and pure, returning Empty or Ready with lazy effects; direct flush and scheduler reuse it.

Runtime submissions include tickets and have no JSON codec. The injected callback is a receipt handoff contract, not an automatic background monitor. See the [scheduler source](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/MemoryFlushScheduler.scala).

## Consolidation worker contract

MemoryConsolidationScheduler.submit enqueues a suspended Maintenance claim under its stable owner token. Admitted runs consolidator.consolidate and then the required result callback; Disabled/Throttled completes without a result report. Completion observation registers accepted tickets synchronously or fails with CompletionObservationError on Closed/Capacity. Registration failure follows admission and does not roll back work. Typed callback errors, defects and cancellation remain in the task receipt; storage can already contain the commit. Reporting is neither transactional with persistence nor automatically retried.

TurnCompletionObserver.andThen runs each callback through its own ZIO.scoped region. Scope cleanup precedes the successor, and any failure prevents it. Queue replacement preserves pending FIFO positions, so admission composition does not impose flush/consolidation execution dependencies. See the [memory contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md) for ownership and current source identities.

## Local archive publication

Archive paths are fixed `memory/archive/<date>_<SHA-256>.md`, derived only from typed DailyMemoryArchive values. MemoryFingerprint validates lowercase digest syntax and rejects unpaired UTF-16 before UTF-8 hashing; MemoryJournal shares it. Under DailyMemoryLease, recover the consolidation journal before reading eligibility. Validate every source and destination, publish all missing matching copies through scoped temporary files, verify content, then write a fresh checkpoint generation/transaction without the selected acknowledgements. Remove each daily source only after rechecking source and archive content. A target with differing bytes or a nonregular path fails explicitly. Archive reads verify digest integrity; listing ignores unrelated names.

This protocol favors preservation over multi-file atomicity: publication-only interruption leaves reusable copies, and checkpoint-before-deletion interruption leaves surviving daily content pending. Archive presence never acknowledges later daily recreation. Version 1 checkpoints remain readable. The implementation assumes cooperative host-controlled paths and SHA-256 collision resistance; it does not promise fsync durability or resistance to uncoordinated ancestor mutation.

## Maintenance policy execution

RetentionDays is an opaque positive Int with a strict JSON-number codec. MemoryRetentionPolicy.KeepAll yields no cutoff; ArchiveAfter subtracts calendar days from a zoned LocalDate and rejects dates below LocalDate.MIN. MemoryMaintenance converts one Clock.instant inside ZIO before running consolidation. It retains that cutoff across extraction delays. Only Committed and NoPendingMemories invoke retention; blank pending input remains protected by checkpoint matching. DailyArchivalResult distinguishes Kept from Archived(cutoff, entries), including empty eligible passes.

Both scheduling adapters delegate to one private suspended MemoryMaintenanceTask. A combined task claims Maintenance once, runs maintenance and then reports; suppression skips every phase. Admission coalescing and post-admission registration errors retain existing semantics. The adapters share a trigger window, so combined maintenance replaces consolidation-only installation when archival is wanted. Automatic session discovery and remote authority remain pending; immutable memory policy configuration is implemented below.

## Transcript pruning operation

TranscriptFiles.pruneBefore branches on existingFile rather than the read API's empty-string fallback, preserving Missing versus empty-log semantics. Under WorkspaceLease.protect it reads modification time and content, applies TranscriptLog.decode/TranscriptState restoration, folds all recordedAt values into the metadata timestamp, and checks strict isBefore. Only then can Files.delete remove the already validated fixed payload. Retained/Pruned results expose effective activity, with Pruned also exposing entry count. Final lease release remains scoped and cannot delete a competing token. Admitted cancellation may follow completed deletion; this is not an atomic undo contract.

## Transcript cutoff arithmetic

For validated positive Int days, multiplying by 86,400 and subtracting from Instant epoch seconds stays within Long. Compare the resulting seconds to Instant.MIN before constructing the cutoff with the original nanoseconds. KeepAll bypasses Clock and target validation. Enabled execution performs this check before WorkspaceLease acquisition and preserves the evaluated cutoff in its result. The explicit-cutoff API remains unchanged.

## Explicit batch APIs

TranscriptTargetId validates exact nonblank correlation strings. TranscriptRetentionTarget.apply suspends the callback; local delegates to lease-protected pruning. TranscriptRetentionBatch.make validates unique IDs; run prepares once then traverses sequentially. TranscriptBatchResult distinguishes Disabled from Evaluated(cutoff, ordered target results), including empty enabled batches. Private PreparedTranscriptPruning holds a replayable effect without acquiring resources.

MemoryMaintenanceCoordinator.run prepares transcript timing, runs MemoryMaintenance, then executes the batch and returns MaintenanceResult. The new MemoryMaintenanceScheduler.make overload accepts this coordinator and a required aggregate callback, using the existing MemoryMaintenanceTask. Old daily-only and consolidation-only factories remain compatible. See the [current source identities and contracts](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md#selected-transcript-maintenance).

## Configuration APIs

MemoryTokenBudget.fromInt validates 1..4000; its codec accepts integral JSON numbers only. ConsolidationPrompt(beforeTokens, betweenBudgets, afterCharacters).render(budget) inserts budget.value and budget.approximateCharacters. MemoryConfig resolves optional prompts and composes existing typed policies. MemoryFlushManager.fromConfig, MemoryConsolidator.fromConfig and MemoryMaintenance.fromConfig preserve prior runtime ownership and validation. Legacy Int make arguments delegate to the same budget authority; optional template arguments are appended with None defaults.

See the [current configuration identity table](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md#current-configuration-identities). Configuration does not install schedulers or gates and does not reproduce Java wire/model-registry APIs.

## Static command inspection APIs

PlanShellInspection.inspect(String) returns Either[ShellRejection, PosixReadCommand]. The result exposes PosixReadProgram, arguments and argv; construction is private to the plan package and no command codec bypasses validation. See the [exact grammar and identities](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md). No command execution, filesystem authorization or runtime plan gate is provided by this API.

## Plan permission APIs

PlanMode is Build or Plan. PlanToolAccess is ReadOnly, PlanControl, RequestExecution, Mutation or PosixReadCommand. PlanPermissionGate.make(UIO[PlanMode], Map[String, PlanToolAccess]) returns a PermissionGate without reading mode during construction. PlanShellInspection.inspectInput(Json) rejects missing/extra/duplicate/nonstring fields before parsing the sole command string. The [current plan identities](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md#current-admission-identities) cover runtime admission and its precise limits.

## Plan-session APIs

PlanSession.make returns control and reviewer capabilities. Control exposes snapshot/mode, enter, replaceDraft(PlanDraft), and scoped requestReview. PlanReview.await returns Approved(exact ApprovedPlan), Declined, Superseded or Cancelled. Reviewer.resolve returns Applied or NotCurrent after exact current-ticket comparison. PlanSessionSnapshot has Build, Planning and AwaitingReview cases; its codec is observational data, not a restore API. See the [unchanged prior session identities](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md#current-session-identities).

## Plan projection APIs

PlanPrompt.render(PlanSessionSnapshot) returns Option[String]. PlanInputTransform.make(UIO[PlanSessionSnapshot]) returns the existing scoped ModelInputTransform. It preserves fresh Build input, appends validated content to a final User, or adds a synthetic User named system with reminder kind plan_context. Snapshot failures/cancellation receive no fallback. See the [unchanged prior projection identities](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md#current-projection-identities).

## Plan draft-store APIs

PlanDraftStore exposes read: IO[AgentError, Option[PlanDraft]] and write(PlanDraft): IO[AgentError, Unit]. LocalPlanDraftStore.open(Path, WorkspaceTrust) returns a scoped capability after canonical directory validation, lease acquisition and existing-content validation. PlanStorageError is the new sealed AgentError leaf; exhaustive matches must include it. The fixed payload is PLAN.md, ownership is .plan-writer.lease, and the backend requires atomic replacement support. See the [current storage contract and identities](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md#standalone-draft-storage).

## Stored-session API migration

PlanSession.stored(PlanDraftStore): IO[AgentError, PlanSession] reads once into unapproved Plan state. It does not acquire the injected store's lifetime. PlanSessionControl.replaceDraft now returns IO[AgentError, Unit]; explicitly narrower callers must widen or handle storage failures. Existing make and DTO shapes remain compatible. Stored publication uses the same STM review authority and a per-session mutation semaphore; host callbacks must not reenter mutations. See the [stored-session contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md#stored-session-publication-and-review) for cause, cancellation and ownership limits.

Changed session and prompt production files now have [current location-context identities](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md#current-location-context-identities); the earlier tables retain only unchanged files.

## Plan control APIs

PlanTools.make(PlanSessionControl, PlanReviewPublisher): Toolkit is pure construction; PlanTools.access supplies exact trusted PlanControl classifications. PlanReviewPublisher.publish(PlanReviewRequest) is scoped and typed; its adapter suspends delivery. PlanReviewRequest contains a runtime PlanReview and optional untrusted summary, with no credential codec. PlanToolResult is a derived-codec enum for EnteredPlan, DraftReplaced and ReviewSettled(PlanReviewOutcome). See the [control contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md#model-facing-controls-and-host-review-delivery) for schemas, installation and lifecycle limits.

## Plan location APIs

PlanLocation.fromString returns Either[AgentError.InvalidConfiguration, PlanLocation]; the opaque value exposes text and a validated string codec. PlanDraftStore.location and PlanSessionControl.location default to None for existing implementations. Control.context: UIO[PlanContext] associates one snapshot with immutable metadata. PlanPrompt.fromContext(PlanContext) and PlanInputTransform.fromContext(UIO[PlanContext]) add location-aware projection without changing legacy signatures. These values cannot authorize reads/writes or restore approval. See the [location contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md#descriptive-locations-and-context-provenance).

## Plan directory API

PlanDirectory.fromString returns Either[AgentError.InvalidConfiguration, PlanDirectory]; its strict string codec shares validation. LocalPlanDraftStore.inWorkspace(workspace, trust, directory = PlanDirectory.Default) returns ZIO[Scope, AgentError, PlanDraftStore]. Both directories must already exist. Native open(Path, trust) remains available with unchanged relative/absolute semantics. Normalization uses strip, preserving non-whitespace controls for rejection.

## Task supervision API

TaskSupervisor.open[A] returns ZIO[Scope, Nothing, TaskSupervisor[A]]. attach accepts a suspended ZIO[Scope, AgentError, A] and returns scoped TaskAdmission.Accepted(handle) or Closed. SupervisedTask.await and cancel return UIO[Exit[AgentError, A]]; awaitOrPromote returns Completed(exit) or Promoted after its own 30-second deadline. These are runtime capabilities without codecs. Parent/host cleanup joins the task; callers inspect its Exit for failures. Tasks must not await their own handle or closure of a scope currently joining them. Explicitly masked work/finalizers can delay cancellation.

# ReAct execution and structured output

## Agent construction and checked admission

`ReActAgent.builder().config(config)` installs the full immutable `AgentConfig`; individual fluent setters refine it. `emptyThinkingRetryLimit(0)` disables thinking-only retries. `build` validates the final configuration through the same factory as `make`. Hook setters replace their selected slot; adapters must compose explicitly with the installed hook rather than supply a second set of defaults.

The internal [ToolCallAdmission](ToolCallAdmission.scala) checks schemas, external arguments, approved input rewrites and scoped authorization. It owns no history, dispatch, event publication or completion state. The runtime still persists calls before execution and records/reconciles results by occurrence. This separation changes no authorization or cancellation contract.

## Passive peer context

`recipient.observe(sender, messages)` receives data from an explicitly host-selected persistent runtime. It starts no model, tool or broadcast. Admission rejects active execution and suspended checkpoints immediately; it validates the complete batch before one atomic history append. Batches contain 1â€“64 messages and at most 1,000,000 encoded characters including attribution. Names must be nonblank and at most 256 characters. Nested peer observations are rejected.

Each appended User message contains a typed `ContentBlock.PeerObservation` with the original message and sender runtime identity/name. Nested calls and results are inert data and never join the local occurrence ledger. Existing snapshots retain these values; restoration treats attribution as data, not authentication. Reset and history preparation retain their ordinary ownership and rewriting rules.

Install `ModelInputTransform.peerAware` through the builder or `make` to project these observations into labelled User context on model requests. The view preserves local call/result pairs and system messages; stored originals remain unchanged. It supports coalesced User content, omits thinking recursively, strips peer tool metadata/content extensions, and presents peer tools as descriptive JSON text. Media remain typed inputs subject to the selected provider's support. Unsupported peer blocks fail explicitly. Without this projection, Chat Completions rejects the new block rather than silently dropping it.

Passive observation is verified through [public scenarios](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentObservationSpec.scala). Scoped MsgHub membership and post-gate publication pass the public integration scenarios; see [MsgHub](../coordination/README.md). Transcript projection currently records the peer block type without its body; daily-memory extraction does not include peer blocks. Canonical history/snapshots preserve the originals. See the [delivery scope](../../../../../../../../../../docs/architecture/tracker.md#m-msghub-with-persistent-participants).

## Scoped phase observation

`ReActAgent.make` and its builder accept an optional `PhaseObserver`, defaulting to `PhaseObserver.ignore`. The typed contexts distinguish one invocation, each reasoning iteration and each actual model call. A fallback model receives its own model-call context. Reasoning observation ends after history preparation, the model request and the post-reasoning hook, before accepting the response or starting tools.

Observers compose with `andThen`: first registered enters first, then finishes and closes last. Each observer owns a nested bounded Scope; its completion receives the actual work Exit. It cannot rerun the body or replace its result. Work failures, observer failures, cleanup defects and genuine caller interruption remain visible in the composed cause, and owned cleanup joins before return. A successful observed effect is not necessarily semantic agent completion. An observer failure after committed history cannot roll that history back.

Reasoning/model contexts receive an optional revocable Hint-only capability. Publishing reports diagnostic admission, not subscriber delivery; invocation entry deliberately has no Hint capability. Callbacks run on the critical path and must not reenter the agent or wait for its run lock. The new trailing factory argument is source-compatible through its default; rebuild downstream binaries.

## Host-side presentation

The returned `ZStream` supports subscription-local presentation filtering: `streamEvents(input).filterNot(_.isInstanceOf[AgentEvent.TextBlockDeltaEvent])` omits text deltas for that subscriber while canonical assembly, history, result publication and execution remain unchanged. It does not suppress the model fragment before accumulation, redact other event payloads or install general model-call middleware. `ModelTextTransform` instead replaces visible fragments before assembly and still emits a delta for an empty replacement.

Immutable host composition can project a returned `AgentResult`, for example `result.copy(reply = result.reply.withMetadata("modified", Json.Bool(true)))`, after `run` or on the `AgentRunUpdate.Result` branch of `streamWithResult`. This changes the host-facing value only: existing canonical history, runtime state/iteration/usage, emitted events and completion observations are not rewritten. It is not an installed before-broadcast post-call hook and grants no execution or completion authority. [ChatCompletionsTransformSpec](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsTransformSpec.scala) verifies both adaptations through actual ReAct requests, with a positive unfiltered stream control.

## Atomic runtime snapshots

[AgentSnapshot](AgentSnapshot.scala) represents either `Idle(history)` or `Suspended(AgentResult)`. `ReActAgent.snapshot` reads history and pending acting state under the existing run lock; a separate history read cannot establish whether the runtime is suspended. `restoreSnapshot` accepts data only into an empty runtime. Idle history must have complete local call/result pairing, and suspended data uses the existing checked checkpoint restoration path and current executable bindings. Neither accessor starts model/tool execution or imports saved configuration/authority.

The enum has a derived JSON codec, but decoding alone does not validate runtime restoration. Hosts must call checked restoration, hold exclusive runtime ownership and retain their current authorization. The harness [persistent named sessions](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#persistent-named-sessions) uses the atomic accessor after joined commands and handles persistence uncertainty separately.

Snapshot operations acquire the run lock and must not be called reentrantly from an observer executing inside that same run. Historical inline provider previews are retained; incomplete local calls and local Running observations are rejected in idle snapshots.

## Persisted local tool observation

[LocalToolResultObserver](LocalToolResultObserver.scala) is a trailing optional dependency of ReActAgent.make and Builder.localToolResultObserver, with an ignore default. When installed, its observation is required rather than best effort. PersistedLocalToolObservation contains the ModelStreamContext, actual approved ToolUse, original occurrence ordinal, original completed ToolResult, final processed ToolResult and canonical history returned atomically by the final persistence operation. Rewritten authorization inputs therefore retain the exact dispatched identity even when tool IDs repeat.

Only completed local dispatches invoke the port, including normalized execution or decoding Error observations. Dispatch completion does not prove Tool.execute ran. Pre-dispatch preparation failures and denials, externally supplied results, provider-inline results and synthetic interruption/abort reconciliation do not invoke it. A resumed invocation can observe newly dispatched local calls, but replayed supplied observations do not count.

The runtime first records the completed observation and marks its occurrence completed, runs the optional ToolResultProcessor in its scope, then commits the final result and history snapshot. The required observer runs interruptibly in a fresh scope before ToolResultEnd, acting-stop handling, return-direct completion or the next reasoning turn. ToolResultStart and streamed progress may already have been emitted. andThen closes each callback scope before the next callback. Callback construction is suspended; callbacks must not await this agent's run lock and must explicitly join children whose success matters.

CompletionObservationError propagates outside the optional processor fallback, preserving the full cause with cleanup defects. Ordinary failure joins owned parallel siblings and reconciles only unfinished occurrences; the recorded current result remains intact. Internal sibling interruption may remain in a compound failure cause and does not itself request assistant interruption recovery. Genuine fiber or cooperative interruption joins callback cleanup and uses ordinary INV-2 recovery exactly once. No failed observer retries a model request or emits a completed-result event for that observation.

This boundary proves runtime persistence of the selected observation, not model receipt, caller delivery, durable conversation storage or an atomic transaction with a host acknowledgement store. The separate [bound task-result integration](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#bound-task-inspection-consumption) is accepted within its own explicitly reviewed scope. It requires trusted execution provenance and unchanged final output/state; erased or replaced results cannot count as delivered merely because their original result was transiently recorded. The separate [local wait contract](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#local-task-wait-barriers) now uses this unchanged port for complete selected/current results; scoped inbox waiting supplies no acknowledgement authority; routed consumption uses explicit selected-registry capabilities.

Migration: make calls retain their source defaults and builder composition gains localToolResultObserver. JVM signatures and Builder product/extractor arity change, requiring recompilation. The snapshot is runtime-only data rather than a serialized capability or checkpoint. No dependency/build-setting change is required.

LocalToolResultObserverSpec uses three grouped contracts: actual authorized occurrence/raw/final identity and scoped composition with return-direct; typed/cleanup-defect failure and held parallel sibling cleanup; genuine cancellation with exact defects and mixed local Error/excluded origins. The Error fixture exercises execution failure, not a separate custom decoding failure. Completion-event ordering is established by the inspected processing path; it is not a direct event-order assertion in this suite. This is a new lifecycle seam, not closure of upstream TaskTool or wait methods.

## Accepted model response observation

[ModelResponseObserver](ModelResponseObserver.scala) is an optional dependency of ReActAgent.make and Builder.modelResponseObserver. The default ignore retains existing behavior. AcceptedModelResponse contains the selected ModelStreamContext, the winning ModelRequestInput, the accepted assistant message and its persisted canonical history. The request snapshot includes effective projected history/settings, selected model/tools and skill enrichment. It does not describe every provider wire field or HTTP header. Request-only transformations do not replace canonical history.

Observation runs after the reasoning hook accepts or replaces the response and after assistant persistence, before local authorization/execution, thinking-only reminders, final result publication or installation of a suspended checkpoint. Accepted thinking-only responses and empty visible final responses count. Discarded responses, interrupted partial output, synthetic retry/limit/recovery messages and replay of a saved suspended assistant do not count. Resume can observe a later fresh model response. Subsequent structured decoding failure does not undo acceptance of an already observed response.

Each callback owns a scope that closes before execution proceeds. andThen closes the first scope before invoking the next. Callbacks must not await this agent's run lock. Resources and owned children are joined on cancellation; callbacks must explicitly join children whose success matters. Scope closure does not reorder an acknowledgement written inside the callback after its own finalizers. This boundary supplies neither durable delivery nor automatic acknowledgement.

A typed observer failure uses CompletionObservationError and cannot trigger model failover or structured Auto fallback. Ordinary failure or defects reconcile unfinished local call occurrences as Error observations before preserving the original cause; completed and inline provider observations remain intact. Genuine interruption preserves interruption and cleanup defects and invokes ordinary interruption reconciliation. Failed observation does not install a suspended checkpoint. This interruptible per-response boundary is distinct from the mandatory terminal TurnCompletionObserver, which observes one invocation outcome under its existing cancellation mask.

Migration: the optional make argument and builder setting are additive at source level; Builder product/extractor and JVM signatures change, requiring recompilation. AcceptedModelResponse is runtime data, not a serialized checkpoint or provider request envelope. No dependency or build-setting change is required. The explicitly installed [harness reminder adapter](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#automatic-reasoning-reminders) verifies that its exact reminder survived request projection before acknowledging consumption.

## Fresh invocation preparation

Install [InvocationPreparer](InvocationPreparer.scala) through `ReActAgent.make(invocationPreparer = ...)`
or `Builder.invocationPreparer`. The identity default preserves existing behavior. A scoped callback
receives the invocation context and separate immutable prior history/new input, after admission under
the run permit and before input persistence. It runs once for each fresh ordinary, prepared, streamed,
forwarded or structured execution; native/synthetic Auto fallback does not repeat it.

`andThen` preserves explicit order and validates each stage before calling the next. Rewrites preserve
ordered system messages and complete tool-call/result history, both for prior history and the combined
candidate. Callback, validation and preparation-cleanup failures retain their causes, stop subsequent
preparation/model work and leave history uncommitted. Preparation resources close before validated
prior history commits. Input is appended afterward: this is not a transaction combining the two, and
later cancellation/failure can retain committed prepared history without appended input.

The cooperative signal is installed before preparation and retained through Auto attempts. A signal
received during preparation is honored before the model starts, after preparation returns. It does
not preempt a blocked preparation callback. Explicit long-running preparation must cooperate with
cancellation and own its resources. Caller interruption joins preparation cleanup
before releasing the run permit. Restoration alone invokes no callback; the next fresh run sees restored
prior history. Resume retains its checked saved acting state and bypasses fresh-input preparation.

This port changes canonical state/input. [HistoryPreparer](HistoryPreparer.scala) remains a separate
per-model history boundary, and [ModelInputTransform](ModelInputTransform.scala) changes only the
request view. None of these values grants tool or durable execution authority. The
[preparation suite](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/InvocationPreparerSpec.scala)
checks ordered validation, cold/warm state, immutable caller input, actual streaming/Auto behavior,
restore/resume boundaries and joined failure/interruption cleanup.

## Invocation identity and prepared execution

[InvocationContext](../event/InvocationContext.scala) carries a typed UUID allocated in ZIO. Every admitted ordinary, structured, resumed or forwarded execution owns one identity. Model requests, post-reasoning hooks, tool authorization, contextual history preparation and TurnCompletion observe that same identity. Auto native/synthetic attempts and model failover retain it, although individual model request IDs remain fresh. A resumed invocation is fresh while its saved acting replyId/requestId remain unchanged. Forwarding preserves the child execution's context; source tagging does not substitute a parent identity.

`prepareRun(messages)` and its single-message overload return [PreparedInvocation](PreparedInvocation.scala), exposing `context` before execution. Preparation allocates identity and control state, without history mutation, admission, model request, owned worker or run scope. `run` and `streamWithResult` compete for one execution claim under that reserved identity. A second attempt fails with InvalidConfiguration; create a new handle for another attempt.

`status` observes Created, Queued, Running, Returned, Failed or Cancelled without starting execution. Running begins under the agent run permit. Returned means the invocation returned a value, including suspended or cooperatively interrupted AgentResult; only AgentResult.state determines agent completion. Ordinary typed/defect failure remains Failed even when its cause retains sibling interruption. Controller/caller cancellation is distinguished while preserving the complete cause. `termination` waits for and replays the exact Exit after owned execution cleanup. Neither accessor executes the source.

`cancel` returns an optional joined Exit: Some means it admitted cancellation, None means no new cancellation was admitted. Before execution it consumes the handle without invoking the model. Queued cancellation joins only its worker and does not wait for an unrelated active invocation, including when the caller submitted under a mask. Active cancellation joins model/tool/finalizer cleanup and retains original failures, defects and interrupters. Owned workers and internal cancellation observers are interruptible; caller waiting semantics and explicit masks inside the supplied work remain respected. A callback or its descendant cannot cancel its own invocation: that self-join fails with typed InvalidConfiguration before changing control state. Execution callbacks must not await their own termination.

`interrupt` cancels a created/queued handle, or signals the running handle's own cooperative Promise. It cannot target a replacement invocation through a stale handle. Cooperative interruption may return an AgentResult normally, so invocation status can be Returned while its result is Interrupted. Streaming reuses ordinary result-bearing event delivery and the same one-shot control. The termination receipt joins execution resources; delivery drains afterward under the consuming stream's scope. Consumers must retain/close that scope and must not treat an execution receipt as proof of event delivery. No durable lease, structured/resume preparation or implicit session routing is added.

HistoryPreparer retains its legacy `prepare(history)` contract and adds `prepare(context, history)` with default delegation. `andThen` forwards the same context through both stages, validates each stage and preserves request-owned cleanup. Hosts overriding the contextual method must explicitly forward it through custom wrappers. Invocation data is observational: ConfirmedBatchAuthorizer binds the exact saved replyId/requestId and original ordinal/id/name, then retains its current policy checks; the fresh invocation UUID grants no confirmation authority.

Existing public constructor calls for ModelStreamContext and TurnCompletion retain trailing defaults; legacy context JSON without invocation decodes None. Runtime execution supplies Some(context), and malformed supplied UUID data is rejected. These case-class product/extractor arities and JVM constructor/apply/copy signatures change, requiring consumer recompilation. InvocationContext is serializable correlation, while TurnCompletion retains a runtime Exit and is not a durable checkpoint. See the [migration policy](../../../../../../../../../../project/README.md#compatibility-policy).

The two bounded pinned run-ID observations are mapped in the [parity audit](../../../../../../../../../../docs/architecture/test-parity.md#thinking-and-invocation-observations). InvocationContextSpec covers Auto/hooks/pre-model failure and original/restored resume; PreparedInvocationSpec covers lazy creation, overlapping double execution, queued cancellation isolation and joined cleanup defects. Existing preparer, event codec, forwarding and result-stream groups retain exact ordering/context assertions. This is not full Java AgentRun lifecycle parity.

The [prepared-invocation suite](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/PreparedInvocationSpec.scala) exercises pre-start cancellation, shared ordinary/stream admission, exact context, masked queued cancellation, stale controls, self/descendant rejection and joined cleanup defects. [Local observer failure tests](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/LocalToolResultObserverSpec.scala) retain typed failures and sibling interruption without misclassifying cancellation. Invocation correlation remains independent of saved confirmation authority; exact replyId/requestId and original occurrence guards are retained.

## Empty-text stream completion

The existing successful-stream group in ReActAgentEventSpec now covers both `FinalText("done")` and empty nonreasoning `FinalText("")`. Each row fully drains two successive same-agent `streamWithResult` invocations to EOF, preserving the gate-reuse/fresh-identity checks. Each invocation must emit exactly one correlated runtime Start/Result/End sequence and one trailing Completed result whose reply equals the result event and retains the expected visible text. A five-second test timeout bounds a possible EOF hang; it does not assert upstream wall-clock timing.

This is a bounded runtime-intent adaptation of pinned AgentStreamingTest.testReActAgentStreamCompletesWhenModelReturnsEmptyText (256). Its legacy first REASONING/isLast envelope is deliberately outside the typed event contract. Production, public APIs and test-group counts are unchanged; this is additional liveness evidence, not a reproduced defect or classification of the other streaming methods.

## External execution checkpoints

The accepted [external binding scope](../tool/README.md#external-tool-bindings) distinguishes external execution from permission waiting. AwaitingResults retains completed occurrence/history positions, current group state, saved Stop, exact externalRequests inputs and an externalTools binding-kind manifest. Mixed batches retain separate permissionRequests; only those publish Ask. The assistant call is persisted before acting, and approved argument edits are persisted before dispatch. External waiting contributes no placeholder Tool message or successful result event.

Partial host results use SuppliedToolResult with checked original ordinals, preserving repeated IDs and completed local observations. Rewaiting without a supplied result preserves the already admitted external request without another authorization callback; it neither authorizes a new external handoff nor establishes ongoing permission. Restoration checks definitions and Local/External binding kinds. BeforeActing host-supplied observations retain their existing generic contract and bypass ordinary dispatch validation; they are not proof of validated execution.

Hosts must dispatch from the returned or fully consumed validated Suspended result, authenticate supplied observations and own remote execution/claims/recovery. Interrupted runs reconcile unresolved calls with Error observations whose external wording reports an unaccepted result and unknown execution status. Joined local cleanup does not cancel remote work. Checkpoint storage supplies no lease, exclusive claim or exactly-once guarantee. Structured runs reject this waiting mode.

## Model turn trust boundary

The runtime validates every custom LlmClient TurnOutput after scoped client cleanup and before accepting usage, model-end publication, post-reasoning hooks or assistant persistence. All variants, including Interrupted, require Role.Assistant. ToolCalls requires at least one tool use. ThinkingOnly requires no calls, blank visible text and reasoning. FinalText requires no calls and either visible text or no reasoning; empty nonreasoning FinalText remains valid. Interrupted permits partial assistant content. Truncated permits only visible Text content and the optional descriptive token-exhaustion metadata; calls, results, thinking and native replay metadata are rejected. It records partial history and returns Stopped, bypassing reasoning replacement and tool execution. Structured runs fail with ExecutionStopped. See [token-budget exhaustion](../client/README.md#token-budget-exhaustion). Inline provider-hosted tool behavior is unchanged.

Invalid role/classification fails with static ModelError code zero before runtime completion/persistence authority. This failure does not trigger model retry/fallback. Public signatures/codecs are unchanged; host custom clients must return consistent tags. Client audit Returned outcomes are observations, not runtime contract; the unpublished audited example keeps that distinction and does not forward private failover authority. This boundary adds no duplicate-ID, arbitrary metadata/general content validation, or retraction of events already emitted directly by a custom client.

- The final full run covers the refined eight-malformed/two-valid table; the earlier focused eleven-test run predates the last row.

Toolkit construction and selection follow the [checked registry identity and migration contract](../tool/README.md#checked-registry-identity).

Fully drained invocations begin with AgentStart and end with correlated AgentEnd; early consumers abandon later output.

ReActAgent retains history and serializes runs. Model requests receive their own Scope, closed before the next request. Assistant tool calls are persisted before dispatch. Completed observations survive cancellation; unfinished local occurrences receive Error observations followed by one recovery message for the run. Thinking-only responses trigger bounded synthetic reminders. AgentConfig uses ToolExecution and a five-minute timeout; AgentResult uses AgentRunState and optional ChatUsage.

- [AgentConfig](AgentConfig.scala)
- [AgentResult](AgentResult.scala)
- [AgentRunUpdate](AgentRunUpdate.scala)
- [PostReasoningHook](PostReasoningHook.scala)
- [ReActAgent](ReActAgent.scala)
- [StructuredOutput](StructuredOutput.scala)
- [TurnCompletionObserver](TurnCompletionObserver.scala)

## Thinking-only retry contract

Thinking-only replies with blank visible text and no calls append one synthetic user reminder named system before the next request. Retry and iteration budgets are independent. For repeated thinking-only replies with sufficient iteration budget, retries 0/1/2 produce exactly 1/2/3 calls and 0/1/2 reminders; a larger retry budget with maxIterations=2 produces two calls/two reminders and max_iterations. There is no extra summarizing request. Visible answers do not consume thinking retries, and reasoning with a tool call enters acting rather than the empty-response loop.

The existing five-group ReActAgentThinkingRetrySpec now directly verifies reminder-dependent model behavior, role/name/metadata, exact request/reminder bounds and separate maximum-iteration precedence. This is test-only contract strengthening: production behavior and group count are unchanged, and no defect RED claim is made.

## Run-owned tool-group activation

The optional `toolGroups` construction dependency uses the [immutable group registry](../tool/README.md#host-selected-tool-groups) as initial configuration. Each public ordinary or structured invocation owns fresh activation state. Structured native-to-synthetic fallback shares that invocation's state; a later public invocation starts from the configured defaults. Retained history does not restore group activation.

One selected Toolkit snapshot supplies both a model request's definitions and its returned local call batch. `reset_equipped_tools` replaces the active Meta groups for the next request. It cannot enable an unadvertised tool in its own batch or revoke tools already admitted in that batch. External groups remain under host control. The reset tool declares both isConcurrencySafe and isReadOnly false, forces its batch through sequential dispatch and goes through ordinary authorization; a denied or invalid reset leaves activation unchanged.

Runtime controls are composed after filtering the host toolkit. The reset tool and synthetic `generate_response` remain available even when group membership names refer to them. Name collisions are checked against the complete host toolkit, including hidden tools. Fixed arguments and host tool flags remain attached to the selected values. The reset tool exists only when Meta groups are configured, and structured-output capability selection counts it as a tool.

Reset input is exactly an object containing `to_activate`, an array of group-name strings. Unknown or External groups produce an Error observation without partial mutation. Its schema includes only configured Meta names. Each request regenerates the discovery description from metaNotes on the same immutable snapshot used for available tools; active/inactive Meta names and descriptions are visible, while External groups remain excluded. Successful output retains active_groups for the selected Meta names and adds notes from the atomic replacement result, without a later state read. Recursive duplicate validation remains enforced; malformed duplicate arguments may be denied by the ordinary authorization boundary before decoding. Group configuration and permission policy are separate: this mechanism grants no filesystem or remote-client authority.

Migration: make appends `toolGroups: Option[ToolGroups] = None`; Builder adds a toolGroups setter and a defaulted field. Ordinary call sites retain prior behavior. Positional Builder patterns/Product arity change and downstream binaries must be rebuilt. This explicit dependency is not Toolkit metadata; filtering the supplied base toolkit cannot recover removed tools. Control-name collisions retain Toolkit's existing ToolExecutionError type/message. The reset result now adds a notes string; consumers that reject unknown result fields must accept it. Descriptions now reflect each request snapshot rather than static construction-time text.

Bounded local discovery/loading is supplied by the harness. Skill activation is invocation-owned; repository fallback and persistent activation are not implicit capabilities. Exact Java prose is not a compatibility contract.

## Preloaded skills

`make` accepts `skills: SkillCatalog | SkillLibrary = SkillCatalog.empty`; Builder adds the same defaulted field and a skills setter. Ordinary source calls keep their behavior, while positional Builder patterns/Product arity change and dependent binaries require rebuilding. The [checked catalogue](../skill/README.md) contains host-preloaded text only. Construction validates its explicit target groups against the actual configured ToolGroups before allocating runtime refs or calling the model; unknown and External targets fail. No generated group names are inferred.

A nonempty catalogue installs the protected `load_skill_through_path` control after base filtering, with complete-base collision checks. The request-only catalogue projection moves the configured ID/description/resource listing out of this tool description; its initial instruction sentence and schema remain unchanged. Input is exactly nonblank skillId and path strings with recursive duplicate validation. The path is data used for catalogue lookup, never filesystem access. SKILL.md selects main content; other resources require exact keys. Missing, malformed or unknown requests cannot activate groups.

The loader is read-only and concurrency-unsafe, uses ordinary authorization and forces sequential batch dispatch. Successful lookup atomically unions the skill's explicit Meta targets with currently active Meta groups while preserving External state, then returns the exact resource content. Existing request/batch snapshots keep new tools unavailable until the next model request. Load and reset controls stay available after filtering; activation shares native-to-synthetic fallback state and resets on the next public run. A content-only catalogue still contributes a control to structured Auto capability routing.

Reset descriptions and result notes include the catalogue's pure group-binding reminders beside the corresponding Meta snapshot. Rendering starts from immutable raw definitions each time, so repeated requests, fresh runs and structured fallback do not accumulate reminders. Notes are advisory: successful reset-to-empty still deactivates all Meta groups and returns their current inactive notes. Empty/no-target catalogues preserve previous note text. No active-skill state or automatic group registration is introduced.

- Actual skill-loader admission is verified through the opt-in [harness Plan adapter](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md#dynamic-plan-admission), including policy/confirmation and denial of mutating tools. The read-only flag alone grants no permission.

## Request-only skill catalogue

After ModelInputTransform output passes validation, the runtime appends SkillCatalog.systemPrompt to the first system message while preserving its existing fields and content, or prepends a system message when absent. Later system messages and nonsystem ordering remain unchanged. The projection contains only selected IDs, descriptions and logical resource names; bodies, resource contents, activation targets and inert metadata are excluded. Empty catalogues preserve prior request input. Canonical history, history preparation, transform input, completion observations and returned results do not acquire this text. No resources are loaded and no groups are activated by rendering.

The same projection runs for every model request, including native-to-synthetic fallback and fresh public runs, without accumulating copies. Numeric Java hook priorities are replaced by this explicit pipeline order; this is not a general hook framework or exact Java prompt reproduction.

Migration: configured skill listings move from the loader description to transient model-request system content. This changes observable prompt text and projection ordering, not tool names, schemas, permission authority or skill activation. The loader retains its instruction sentence and strict schema.

Existing execution, forwarding, group and permission contracts retain their scoped evidence below. The [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance) owns current repository totals and next work.

## Deferred skill reads

The skills parameter and Builder setter accept SkillCatalog or SkillLibrary through one parameter; ordinary catalogue callers retain their behavior. The runtime exposes the stable catalogue through skills, while reader capabilities remain private. Widened method/Builder field types require downstream recompilation and can affect typed method values or positional patterns; no binary compatibility is promised.

The protected loader retains ordinary authorization and sequential dispatch. An approved exact library key performs one scoped read; only successful read and finalizer completion can union catalogue-owned Meta targets. Failed reads or cleanup produce Error observations without activation; cancellation joins cleanup and preserves its cause. Prompt projection lists logical deferred paths without fetching content. There is no automatic disk fallback or new model-controlled location authority.

## Fixed-argument authorization

Attached [fixed raw arguments](../tool/README.md#fixed-raw-arguments) are prepared before local authorization, without changing the persisted original call while approval is pending. Malformed preparation yields Error without callback execution; signed-input changes and approved fixed-field alterations yield Denied. Nonfixed rewrites remain allowed. Approved history, invocation context and execution share the final prepared input, retaining original model-input provenance. Inline provider results still bypass local dispatch. Cancellation joins authorizer cleanup and retains the complete interruption/cleanup cause.

## Incremental local tool results

The [incremental execution contract](../tool/README.md#incremental-execution) publishes provisional text/data only after assistant-call persistence and ordinary authorization. Runtime call identity, state and control metadata remain authoritative. No provisional Tool message is stored, and the next model request receives only the final processed observation after stream EOF and cleanup. The invocation event lease closes before final result processing.

Final-delta suppression is local to each execution occurrence, including repeated model IDs. Provisional content does not replace the final result: ToolResultEnd carries its final text/state/metadata, while full typed final content remains in canonical history. Ordinary tools and metadata-only progress retain aggregate publication. A cancelled unfinished stream follows existing reconciliation; completed siblings survive. Inline server results bypass local execution unchanged.

### Shared-Toolkit destination isolation

Concurrent independently owned agents can share the same Tool and immutable Toolkit while retaining separate incremental-event destinations and canonical histories. The shared fixture holds both executions between their first and second progress chunks, then checks exact per-agent deltas/final results and distinct request/reply contexts despite identical tool-call IDs. This is not support for concurrent runs on one Scala agent; no production API or implementation changed.

## All-tools-denied observation

After successful cleanup of a nonempty entirely local admitted batch, ReAct publishes AllToolsDeniedEvent only when every final observation is Denied. The complete ordinal-to-history ledger includes outcomes from earlier partial resumes and preserves repeated IDs; the payload contains persisted approved calls in original order. Deferred approval and current or saved acting Stop take precedence. Publication follows all result End events and precedes the next model request in ordinary, native and synthetic execution; it does not stop the run or establish human provenance.

Mixed Success/Error, inline/provider batches and incomplete deferred batches do not qualify. A tool cannot grant itself denial authority: tool-produced Denied is normalized to Error. Optional processor failure preserves the authoritative observation. An interrupted or defective phase retains its full cause and does not reach aggregate publication; later cancellation cannot retract an already published observation. No public API or event codec changes.

Provider-owned pending calls have the separate reviewed ownership contract below; AllToolsDenied remains restricted to entirely local batches.

## Provider-owned pending calls

ToolUse marked serverTool=true (or the supported legacy alias) belongs to the provider even when no inline result exists. Calls without inline results bypass local authorization, execution, result processing and synthetic local recovery; matched inline results retain their existing processor path. They remain in the assistant history and keep ordinary reasoning running within its existing iteration/timeout bounds. Local siblings retain original ordinals even when IDs and names collide. Complete inline-only batches still finish normally; explicitly captured structured output retains its existing precedence over provider continuation. No new provider-wire support is added: ChatCompletions still rejects server calls/results.

ToolHistory completeness describes local obligations. Same-message inline results consume provider occurrences first; remaining provider calls create neither a local pending entry nor a cross-message pairing span. Separate Tool messages and later assistant-only results cannot complete those provider calls. Local crossing pairs still move compaction cutoffs backward; wholly completed local prefixes can be removed safely.

BeforeActing checkpoints may preserve a provider-only pause, with no local pending choices; resume continues the provider rather than inventing a result. Supplied results and permission requests can identify only unresolved local ordinals. AwaitingApproval requires unfinished locals, and a saved Stop must identify a completed local ledger entry or an actually matched inline occurrence. No checkpoint wire shape changes; malformed checkpoints relying on local ownership of provider calls now reject.

## Host-selected return-direct completion

ToolDef.returnMode defaults to ToolReturnMode.Continue. Direct permits an ordinary fresh or resumed run to finish without another model request only when its original nonempty admitted batch contains exclusively local Direct calls and every processed observation is Success. Eligibility uses the admitted definitions and complete original-ordinal ledger, including supplied and previously completed results. Later activation changes do not grant this policy. Deferred approval takes precedence, followed by the earliest current or saved acting Stop; all admitted work and processor cleanup join before direct completion. Cancellation and defects retain their existing failure authority. Native and synthetic structured runs continue to their normal capture boundary.

Successful direct completion preserves the full processed Tool history and appends a presentation assistant tagged ToolReturnDirect, using runtime reply identity and no promoted result-level control metadata. Output follows original call order, including repeated IDs and reversed completion. Only Text, Image, Audio, Video and Data blocks are presentational; any Thinking, ToolUse, ToolResult, Custom or Hint block declines the shortcut without dropping the observation. An entirely zero-block batch produces `(no output)`; an empty Text block remains empty, and a partially empty batch gets no placeholder. Result processors can redact content or request Stop; original unprocessed payloads never bypass them. Existing AgentResult/AgentEnd delivery remains; no additional closing TextBlock event sequence is synthesized. Provider serialization limits still apply to retained media history on a future model call.

Migration: ToolDef appends the defaulted returnMode field. Old JSON without it decodes as Continue; named construction/copies retain defaults. Current positional/Product arity and binary migration follow the [tool strictness contract](../tool/README.md#provider-tool-schema-strictness). Restoring a checkpoint still requires exact trusted host definitions, including returnMode. ChatCompletions does not serialize this local completion policy as a provider tool-schema field.

## Current typed-result acceptance

The [tool contract](../tool/README.md#typed-execution-observations) owns the result API migration, protected metadata and cancellation-cause semantics. Typed payloads reach persisted history and existing text/data events without changing runtime call identity.

## Result-bearing streams

`streamResumeWithResult(results = Chunk.empty)` and `streamResumeEvents(results = Chunk.empty)` lazily resume the saved execution under the same run lock as direct resume. Supplied-result validation failure leaves the checkpoint available and starts no completion observation. Ordinary, resumed, structured and prepared streams share one owned diagnostic backlog and a separate complete execution outcome; event-only projection does not execute another run.

`streamWithResult(messages)` emits [AgentRunUpdate](AgentRunUpdate.scala).Event values followed by exactly one complete Result when execution and diagnostic delivery both succeed, after scoped execution cleanup. `streamStructuredWithResult[A](messages, schema, mode = Auto)` retains the complete AgentResult together with the validated decoded A. Single-message overloads are available. Event-only APIs retain terminal failures while projecting Event values from the same execution.

Accepted events precede Result, including AgentEnd when admitted. Result preserves history, iteration count, aggregate usage and runtime state: ordinary cooperative interruption yields an Interrupted result with recovery history. Result does not imply Completed. Execution failure emits no Result and retains its complete typed/defect/interruption Cause.

`AgentConfig.eventDeliveryBudget` and `Builder.eventDeliveryBudget` select checked positive limits. The default host policy permits 256 queued events, 4 MiB of exact encoded UTF-8 event bytes and 1 MiB per event; these are policy choices, not measured performance optima. Source/correlation transformation precedes byte admission. The [publication contract](../event/README.md#diagnostic-publication-and-budget) explains accounting and receipts.

The first capacity or encoding rejection closes diagnostic admission without cancelling execution, changing history, replaying tools or fabricating interruption recovery. The reader drains the accepted FIFO prefix, then receives the recorded EventDeliveryError only after execution finishes and cleanup joins. If execution also fails, its original full Cause precedes the delivery failure in sequential composition. No Result follows diagnostic failure. A retained PreparedInvocation.termination exposes its exact joined execution Exit independently; a convenience stream has no separate result retrieval after delivery failure. Retaining a prepared handle does not transfer execution ownership away from its stream.

Early consumers cancel and join attached execution but abandon later output, including later completion-observer errors. Required observers keep their lifecycle contract, so neither cancellation nor overflow reporting has a new latency bound. Complete results, canonical history, producer inputs, temporary encoding allocations and consumer-owned buffers are outside the diagnostic budget; encoded bytes are not a heap limit. No replay or acknowledgement is provided. AgentRunUpdate has no wire codec, and AgentEvent payloads are unchanged.

Custom clients must accept EventPublisher instead of Hub[AgentEvent]. Rebuild dependent binaries and update exhaustive AgentError matches for EventDeliveryError. The new AgentConfig field also changes product/extractor and constructor signatures; absent JSON fields use the default budget. See the [tracker](../../../../../../../../../../docs/architecture/tracker.md).

## Direct event forwarding

The package-visible `runForwarding(messages, destination, source)` and `resumeForwarding(results, destination, source)` execute once under the existing run lock and completion observer. A source-bound EventPublisher publishes directly through the supplied ToolEventPort, with no intermediate hub, forwarding fiber or unaccounted backlog. The calling effect retains the complete AgentResult and full execution Cause independently of diagnostic admission; owned execution and cleanup finish before return.

The host-selected source prefixes any existing nested source with `/` before admission, so the parent budget charges the final encoded event. Reply/request identities remain unchanged. Source labels are diagnostic data, not authentication. Closed or rejecting destinations discard later diagnostics without cancelling the child or losing its retained outcome. Publication supplies neither replay nor consumer acknowledgement. These internal ordinary-run primitives support [host-only child resume](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#host-only-session-resume); they add no structured child execution.

- The separately reviewed [harness adapters](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#scoped-child-event-forwarding) install this primitive for owned spawn/send operations with task attribution and per-invocation leases.

## Runtime events and result pairing

The runtime attempts one correlated AgentStart/AgentEnd pair around each engine attempt. End follows loop termination/reconciliation, including failure or interruption. Successful preparation commits history, completes required observation and its scope cleanup, then attempts one runtime AgentResultEvent before End. Observation failure suppresses the result event while retaining committed history. Ordinary cooperative interruption publishes its recovery reply; failed structured preparation publishes no finalized result. Diagnostic admission can reject any event without changing these execution outcomes. Producer completion closes admission, drains accepted events and exposes the separately retained execution outcome. A provider-published AgentEnd cannot terminate delivery. Early consumer completion closes owned producer/model scopes; publishers must remain within those scopes.

After all configured iterations complete without a final response, the runtime emits exactly one ExceedMaxItersEvent with the attempt's replyId, configured maximum, and completed iteration count before the normal end. Completion on the last permitted iteration does not emit a limit event. Unlike the pinned upstream guard, this event never intentionally uses an empty replyId.

Completed provider-hosted inline calls bypass local execution and do not create Role.Tool messages. Running inline previews remain in canonical assistant history but do not close calls, enter completed-result processing or publish completed-result events. An unresolved provider call continues the bounded reasoning loop even when visible text accompanies its preview; a matching terminal observation can finish the turn. Each inline observation is consumed once by call ID and occurrence, retaining pending call order. Local calls sharing a server ID remain executable. For each inline result, publication is Start, one TextDelta or DataDelta per output block in original order, then End. Empty output emits only boundaries; state and full result metadata are preserved. Data deltas carry the correlated replyId. Full metadata propagation is an intentional Scala difference: pinned upstream inline events synthesize only the server-tool marker.

Cancellation reconciliation also consumes completed local observations by occurrence, matching a provided tool name as well as ID. A completed result cannot suppress every unfinished same-ID call, and parallel completion of a different named tool does not satisfy a pending call. Nameless observations match by ID/occurrence. Completed observations remain unchanged.

## Acting-stop decisions

A host-installed ToolResultProcessor may override processDecision or use deciding to return Continue(payload) or Stop(payload, reason). Tool output and model metadata cannot supply this authority. Payload application still preserves call identity, execution state and reserved control metadata. Existing process implementations run once through the default Continue adapter. andThen short-circuits the decision chain for that observation on Stop; direct payload-only process calls still run every composed stage.

The runtime persists calls before execution and processes every admitted local or matched inline observation. Stop does not cancel sibling work or skip later calls in a sequential batch. Successful processor scope cleanup, payload persistence and completed result publication precede selection of the earliest Stop by original assistant-call ordinal, independent of completion order or repeated IDs. The owning assistant receives acting_stop_requested, then RequestStopEvent is published. Ordinary execution returns AgentRunState.Stopped(reason), with the canonical assistant reply, all paired observations and accumulated usage; it makes no further model request. A later public run may perform new work.

Typed processor failure retains the raw observation and contributes no Stop; defects and interruption override provisional decisions. Owner cancellation, including held cleanup failure, appends one interrupted recovery while retaining completed observations and the full Cause. A mixed Cause from defect-driven parallel sibling interruption alone is not owner cancellation: unfinished siblings receive Error observations without a spurious interrupted recovery. Cancelling a public event-stream consumer still abandons later output. Structured execution reports AgentError.ExecutionStopped before decoding even an already captured synthetic response, without native fallback or a successful AgentResultEvent. This is completed acting-phase termination, not Suspended state, HITL checkpointing or durable resumption. Required completion observers remain host failure boundaries.

Migration: processDecision is a concrete additive method and deciding is an opt-in factory; legacy processors need no override. ToolResultDecision is a runtime ADT without a wire codec. Exhaustive AgentRunState, AgentError and SubagentToolState matches must handle Stopped/ExecutionStopped. AgentRunState's derived codec gains Stopped(reason); the new error leaf has its own codec. Dependent binaries must be rebuilt. Child spawn/send projects Stopped as a bounded Error observation while task inspection retains the terminal value; retained sessions accept fresh work when the core runtime has no pending checkpoint.

- Accept the separate [before-acting suspension contract](#before-acting-suspension-and-restoration); partial HITL confirmations, returnDirect flags and host persistence remain separate.

## Structured output

StructuredOutput supports native formatting and synthetic generate_response with root definition hoisting. Synthetic output completes only after acting finishes in the Completed state, without another model turn. Ordinary tool pairs and unrelated empty history remain. Final replies retain signed thinking exactly once and generating-request usage; AgentResult usage aggregates the run. Stopped runs fail with AgentError.ExecutionStopped(reason) before captured payload decoding and do not trigger Auto provider fallback. Other incomplete runs fail with AgentError.Interrupted and retain recovery history even after payload capture.

runStructured and streamStructuredEvents share the same preparation and commit path. Structured payload resolution selects existing metadata or parses visible text exactly once; that AST supplies both decoding and committed metadata. Preparation remains interruptible and timeout-bounded. A masked completion section commits finalized history, completes required observation and cleanup, then publishes AgentResultEvent before AgentEnd. Required observation can delay cancellation without the attempt timeout bounding it. Late cancellation may coexist with committed result publication while the caller ultimately exits interrupted. Auto fallback retains two attempt boundary pairs, with no result from the failed native attempt; the run lock covers both attempts. Input event payloads and ToolCallState metadata do not grant runtime permission or stop/resume authority.

## Scoped model failover

ReActAgent.make appends fallback: Option[ModelTarget] = None; Builder.fallback installs one explicit client/config pair. This is a host capability, not model-selected routing or serialized LlmConfig data. Each model request tries its primary client first and may select the fallback once; later loop iterations start with the primary again. Default behavior remains unchanged.

Only the built-in completion adapter can produce the private pre-response rejection evidence used here: an eligible actual HTTP rejection after its status-retry budget and response cleanup. A custom client's public ModelError, acquisition failure, successful-response stream failure, invalid projection, interruption or mixed cleanup Cause cannot trigger failover. Legacy/custom LlmClient implementations keep their existing streamTurn contract and do not gain this authority. Public client/transport calls still expose ordinary AgentError values.

Each target gets a fresh requestId under the same replyId, its own scoped ModelInputTransform invocation from the same prepared canonical history, and the same admitted tool-definition snapshot. Request-only skill projection follows validation again. The primary request view is not reused or persisted as the fallback's input. Model start/end events name the actual selected model; successful fallback usage is counted once. Owned request/projection cleanup completes before another target starts, and the surrounding run timeout/cancellation remains authoritative. If fallback fails, the original primary failure precedes its complete Cause; defects and interruption are retained.

Compatibility follows the active request plan. Without responseFormat, fallback capability declarations need not match. With native responseFormat, the target must declare Native, and must also support application tools when the admitted definitions are nonempty. An incompatible target performs no projection or I/O and leaves the primary failure unchanged. The original responseFormat overrides the target configuration, including clearing it for synthetic requests; schemas and tool definitions are not silently downgraded. Native-to-synthetic Auto fallback accepts only an exact singleton ModelError rejection with status 400, 422 or 501, never a compound failure.

Migration: ordinary construction calls retain fallback=None; appended make/Builder fields may require method-value or positional-extractor updates and dependent binaries must be rebuilt. ModelTarget contains a live client and is intentionally not a JSON codec/configuration registry. There is no failover listener, persistent selected-model state, automatic capability discovery or remote routing.

- Three grouped controlled-driver tests exercise primary HTTP retry exhaustion before target selection, repeated runtime requests, history/projection/context isolation, native/synthetic plan compatibility, excluded failures, held cleanup/cancellation and complete target failure causes. They are not live-provider interoperability or exhaustive scheduling evidence.

## Model request correlation

ReAct allocates a fresh requestId under the current attempt replyId and passes ModelStreamContext explicitly to LlmClient.streamTurn. ModelRequestStart precedes invocation; ModelResponseEnd follows a non-interrupted result and request-scope cleanup. HTTP block deltas may be observed before completion, but incomplete/malformed responses never reach local dispatch. The [client contracts](../client/README.md) describe live ordering, source migration, and remaining parity limits.

## Completed tool observations

All four result-event variants now retain the originating ModelStreamContext. Local execution publishes Start and may publish provisional text/data while running interruptibly. After EOF and cleanup, it records and processes the final observation, publishes aggregate deltas only if no nonempty progress was emitted, and publishes the final End under the existing interruption mask. Empty text remains an explicit empty delta. Normalized failures remain Error observations and continue the loop. Each parallel call keeps its own ordered events; completion order may differ from call order. Later turns reusing a call ID remain distinguishable through requestId.

Inline provider results share the completed-output publisher, preserve text/data ordering and metadata, and remain inside the assistant message without separate Tool messages. Local tools may produce completed typed payloads through Tool.encodeResult. Toolkit admits only Success/Error projections; ReAct owns call identity, authorization denial and protected control metadata. An optional ToolResultProcessor rewrites payload blocks and metadata before completed publication. Provisional updates have the separate incremental contract above; the current ChatCompletions request codec rejects non-text output. Cancellation preserves completed results/events and reconciles unfinished history; it does not publish a false ToolResultEnd for interrupted work. AgentEnd still terminates the attempt.

## Strict structured JSON

Msg.getStructuredData and structured agent preparation share core.json.JsonBoundary. Text must contain one complete JSON value with unique object keys at every depth. Authoritative structured metadata is checked directly; invalid metadata fails without falling back to visible text. Captured synthetic payloads are checked before finalization. The synthetic tool also validates its entire input before extracting response, so duplicate wrapper keys or duplicates in ignored siblings cannot disappear during extraction. Valid wrapped and direct payload adaptation remains supported.

This tightens previously permissive parsing; trailing input and ambiguous fields now fail through existing typed/string error channels. It does not add JSON Schema validation. Failed structured preparation publishes no AgentResult or structured metadata; raw observations can remain in history. The shared validator performs no effects and does not depend on models, agents, or transports.

## Capability selection

LlmConfig.structuredOutputCapability is a host declaration for the configured model/endpoint, not automatic discovery. Unknown is the default for source and JSON compatibility. Native() permits application tools by default; Native(NativeToolSupport.Unsupported) restricts native formatting to requests without application tools. Unsupported cannot declare tool support independently.

| Declaration | Auto without application tools | Auto with application tools |
| --- | --- | --- |
| Unknown | Native with rejection fallback | Synthetic |
| Unsupported | Synthetic | Synthetic |
| Native() | Native with rejection fallback | Native with rejection fallback |
| Native(NativeToolSupport.Unsupported) | Native with rejection fallback | Synthetic |

Explicit Native and SyntheticTool modes override this selection. Native installs the supplied structured schema. Every synthetic attempt clears responseFormat while retaining other configuration, including when falling back from a rejected native attempt. Ordinary runs preserve the host's responseFormat. Auto native attempts fall back only on an exact singleton ModelError failure with status 400, 422, or 501; other errors and cancellation propagate. This status policy is broader than provider-specific capability error classification.

The declaration is serialized with LlmConfig but excluded from the HTTP request body. Existing constructor calls retain their defaults; positional pattern matches and binary rebuilding follow the [current LlmConfig migration](../client/README.md#optional-nucleus-sampling). Unknown preserves prior routing, while declared Unsupported avoids a native probe entirely.

## Current acceptance

The runtime provides scoped request/run cleanup, finalized-result ordering, occurrence-based reconciliation, correlated model/tool events and strict structured JSON. Before-acting checkpoints, partial human confirmations, checked external results and resumed streams use the contracts below; hosts retain authentication, exclusive ownership and side-effect idempotency responsibilities.

Supported codecs alone do not establish runtime producers or provider interoperability. The [tracker](../../../../../../../../../../docs/architecture/tracker.md) is the sole current completion checklist; the parity reference records bounded mappings and intentional API adaptations.

General JSON Schema validation of model-generated structured responses and exhaustive provider compatibility are not claimed. Tool argument schema admission is a separate checked boundary.

## Transformed live-model text

The optional [ModelTextTransform](../client/ModelTextTransform.scala) on ChatCompletionsClient replaces visible fragments before accumulation and event publication. Native structured extraction receives that same transformed text and still enforces complete unambiguous JSON. The real HTTP fence-removal scenario closes the mapped text-replacement behavior with an explicit typed adaptation; it does not establish general model-call or post-reasoning middleware parity.

## Before-acting suspension and restoration

The [checkpoint event integration](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#checkpoint-permission-events) is accepted: deferred occurrence records retain authorization UUIDs and prepared-input snapshots, while the original assistant remains canonical.

This contract supports ordinary before-acting checkpoints and saved-batch execution. [Supplied results](#supplied-results-for-suspended-batches) let the host report completed local occurrences; partial human confirmations extend this contract under the [reviewed extension](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#partial-checkpoint-confirmations); durable host workflows remain separate.

`ReasoningDecision.Suspend(reason)` pauses an ordinary tool-call turn after the model and hook scopes close and before inline-result processing or local execution. The returned `AgentResult` carries the complete assistant, canonical history, iteration and accumulated usage. `AgentRunState.Suspended(PendingActing)` adds data for the reason, original model context, assistant position, admitted tool definitions, group snapshot and response format; it contains no tool implementations, credentials, fibers or approval promises. Structured execution and non-tool-call suspension are rejected before the assistant is committed.

`resume()` consumes the live pending batch once under the run lock, using a fresh owned scope and timeout. It processes the saved batch before another model request or post-reasoning hook, preserving original context and occurrence identities. After checking the saved history, the runtime changes only its ReasoningStopRequested or PermissionAsking generation tag to ToolCalls; assistant content, signatures and other metadata remain intact. Inline provider results remain inline and bypass local dispatch. Local calls retain ordinary authorization and cancellation reconciliation. Resuming the final permitted iteration may finish its saved tools but makes no further model request; the limit check precedes integer increment. A fresh run rejects pending work; `resetHistory` explicitly discards history and pending state.

`restore(result)` accepts a checkpoint only into an empty agent. It validates the saved assistant, complete preceding tool history and iteration count against the receiving host budget, then rebuilds the admitted snapshot from trusted host tool bindings and group configuration and checks exact tool definitions. Equality is a compatibility check, not authorization. The host owns checkpoint authentication, storage, exclusive claims and side-effect idempotency; restoring data does not acquire those capabilities. The harness [complete-batch ConfirmResult adapter](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#complete-batch-checkpoint-confirmations) supports explicit restored authorization; checked external results are described below; the [partial extension](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#partial-checkpoint-confirmations) supports partial saved-batch decisions. Suspended child sessions reject ordinary sends and support explicit [host-only resume](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#host-only-session-resume); no model-facing resume tool is installed.

Migration: `ReasoningDecision` gains `Suspend(reason)` and `AgentRunState.Suspended` becomes a data-bearing case. Exhaustive matches and persisted codecs must account for these shapes; dependent binaries must be rebuilt. The old payload-free Suspended wire value is incompatible: hosts must migrate valid checkpoint data, never fabricate a missing checkpoint. Existing keep/replace/discard behavior remains separately accepted below.

[Supplied external results](#supplied-results-for-suspended-batches) use explicit checked completion of the retained batch.

## Supplied results for suspended batches

`SuppliedToolResult(callOrdinal, result)` carries a host-provided ToolExecutionResult for an original assistant tool-use occurrence. Checked `PendingActing.withResults(checkpoint, results)` augments checkpoint data without changing its history or assistant. Only distinct unresolved local ordinals and Success/Error states are accepted; inline-completed, unknown, repeated or unfinished/Denied observations and ambiguous JSON reject the whole augmentation.

`resume(results)` adds these observations to a live checkpoint; hosts can alternatively augment, serialize and restore a checkpoint before calling `resume()`. Validation occurs before consuming pending state. Resume persists every supplied raw observation and records completed original ordinals before any interruptible result processor or local execution. Runtime-owned call identity/state and protected metadata remain authoritative. Supplied occurrences bypass local authorization and dispatch; only unsupplied local calls execute normally. This represents trusted host-reported execution, not permission approval.

New inline and supplied observations pass through the existing result processor; AwaitingApproval restoration does not replay completed processing. The admitted batch still drains before selecting the earliest original-ordinal Stop; otherwise a batch containing local calls continues to the next model request even when all their results were supplied. Cancellation joins processing cleanup, preserves every persisted supplied/local result and synthesizes Error only for unfinished local ordinals. One interrupted recovery remains the owner-cancellation contract. The runtime ledger is invocation-owned and serialized for partial checkpoints; it is not a durable exactly-once service.

Migration: PendingActing appends default-empty suppliedResults, preserving old missing-field decoding; the partial extension appends a default BeforeActing stage and the event-correlation extension appends default-empty permissionRequests for a nine-field positional shape; dependent binaries must be rebuilt. No-argument resume remains available. ConfirmedBatchAuthorizer requires confirmations only for local ordinals without supplied results; partial confirmations are described in the [partial extension](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#partial-checkpoint-confirmations).

## Post-reasoning policy and invocation usage

ReActAgent.make and Builder.postReasoningHook accept a PostReasoningHook, defaulting to Keep. The suspended callback receives ModelStreamContext and the completed model message, and returns ZIO[Scope, AgentError.ReasoningHookError, ReasoningDecision]. The accepted keep/replace/discard contract is described here; the additional Suspend(reason) path is [defined above](#before-acting-suspension-and-restoration). The dedicated error channel prevents host-policy failures from triggering Auto's provider-rejection fallback; external errors require explicit mapping.

The model request scope closes and its successful ModelResponseEnd is published before policy evaluation. The hook owns a separate nested scope inside the engine-attempt timeout and cooperative cancellation race. Its scope closes before any accepted assistant message is persisted or tools dispatch. Interrupted model output bypasses the hook and retains normal reconciliation. Existing model block events and model-end metrics describe the original observation; replacement/discard does not rewrite already published events. Committed history, final AgentResult, and dispatched tools follow the accepted decision.

Keep retains the original TurnOutput. Replace requires an assistant message, restores the original canonical usage and legacy _chat_usage metadata, then classifies its content as tool calls, thinking-only, or final text. A host cannot mint billing metrics through either usage representation. Replaced tool calls are persisted before execution. Nonempty opaque reasoning follows the same bounded thinking-only reminder as plaintext; an explicit empty detail array alone does not. Replacing calls with final text prevents dispatch. Invalid roles fail before persistence. Discard persists no model message or pending call and performs another iteration, consuming the iteration budget without replenishing the thinking retry budget. Repeated discards reach the normal iteration limit; they do not create dangling tool history.

Usage is now accumulated from returned model observations in one invocation-local Ref, using canonical-first chatUsage. It includes discarded and returned interrupted outputs, spans Auto native/synthetic attempts, excludes supplied history/new input, and resets for each public invocation. Accounting updates are briefly masked after model-scope completion. ModelResponseEnd uses the same canonical-first usage view. Synthetic completion transfers canonical and legacy usage exactly once, clearing both representations from any retained residual generating assistant. Reply usage remains the generating request's usage; AgentResult usage is the invocation aggregate.

Appending the optional make parameter preserves ordinary callers; positional Builder pattern matches must follow the current Builder constructor. The hook and builder are in-memory runtime values, not durable policy serialization. ReasoningDecision has a derived codec, but decoded replacements still pass runtime validation. This API covers replacement/removal through typed decisions; legacy mutable hooks, stop/goto-reasoning commands, and general model-call interception remain separate work.

## Hook-error gate reuse

The existing ReActAgentReasoningHookSpec typed-error group now runs the same agent again after its first post-reasoning hook fails. It checks the exact original typed error without an automatic retry, completed first-hook cleanup before the second invocation, then successful structured completion. Model-call and cleanup counts advance from one after the failed invocation to two after the successful invocation. The existing 20-second suite timeout bounds a leaked serialization gate. No production implementation or test group is added.

This is a bounded intent adaptation of pinned ReActAgentMiddlewareIntegrationTest.serializeOnKeyGateReleasedAfterMiddlewareError (line 474): a failed host extension must not retain the same agent's serialization gate. Java's AgentStart middleware exception, Reactor subscription/cancellation, onion middleware and mutable callback APIs are not reproduced. The test does not establish arbitrary event rewriting, null/throw behavior or general middleware parity, and no production defect/RED claim is made.

## Current post-reasoning identities

Source: [PostReasoningHook](PostReasoningHook.scala). Runtime coverage: [ReActAgentReasoningHookSpec](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentReasoningHookSpec.scala).

## Local-result processing boundary

ReActAgent.make and Builder.toolResultProcessor accept a ToolResultProcessor, defaulting to identity. Its legacy suspended process callback returns ZIO[Scope, AgentError, ToolResultPayload]; runtime processing uses processDecision, whose default invokes that callback once and returns Continue. The explicit [acting-stop decision](#acting-stop-decisions) adds host-only termination authority. The payload contains output blocks and metadata only. applyTo preserves the original call ID, name, state, and serverTool/agentscope_server_tool/agentscope_suspended control keys. Composition applies each payload safely before passing the observation onward; host processors cannot change execution authority through these fields.

After local execution finishes, the runtime atomically appends the complete raw observation and captures its history position. It then runs processing interruptibly in a separate scope. Typed processing failures restore the original payload; defects propagate. Scope cleanup finishes before a short masked section replaces that exact observation and publishes End, plus aggregate text/data deltas only when that execution emitted no nonempty progress. Parallel calls own distinct append positions, including calls that reuse IDs; completion may arrive in a different order. Processing assumes history remains append-only during the acting phase under the run lock.

Cancellation during processing keeps the completed raw result and reconciles only unfinished executions. It does not rerun the tool or publish a false ToolResultEnd. A subsequent model request sees the committed processed payload; observers may see the raw result while processing is pending. Processor side effects already performed before failure/cancellation are not rolled back. Callbacks must not reenter a mutating agent operation that requires the held run lock.

Tool execution can return completed typed payloads. A processor can replace their output blocks and metadata for the existing typed result publisher; incremental local execution has the separate contract above. Empty processed output emits only End after the original Start. Matched inline provider results also use this processor. Supplied history uses the separate opt-in ToolResultEvictor.preparer adapter.

Appending the optional make parameter preserves ordinary callers; positional Builder pattern matches must follow the current Builder constructor. Processor values are runtime dependencies, not serialized agent configuration.

## Authorization before local execution

The optional ToolAuthorizer receives [transient admitted access](../tool/README.md#admitted-tool-access), sampled from the resolved Tool.isReadOnly accessor in the same admitted Toolkit inside the suspended decision boundary. Missing tools provide None; model input cannot supply this metadata. Legacy wrappers must override the additive overload to forward it. The harness SubagentFactory wrapper forwards it through inherited denial checks; the harness explicitly installs narrowed child skill catalogues and immutable group snapshots. It runs after assistant persistence and fixed-argument preparation, before each local tool dispatch, with a fresh request identity and originating model context. Approved input is validated and committed to its exact call occurrence; changed inputs are denied when either reserved reasoning key occurs on that call or any Thinking block in its originating assistant message. A changed fixed-argument candidate is rejected before the authorizer runs. Non-interrupted failures and defects fail closed to Denied observations. Interrupted compound causes preserve interruption and cleanup defects; cancellation preserves completed work and reconciles unresolved calls. Provider-hosted calls bypass the local gate whether or not an inline result is present. The default allows calls; the harness InteractivePermissionAuthorizer provides opt-in policy/interactive confirmation. Complete-batch restored confirmation is available through the harness adapter; host durable persistence remains a host responsibility. Partial approvals have their own checkpoint contract below.

## History preparation before model requests

ReActAgent.make and Builder.historyPreparer accept HistoryPreparer, defaulting to identity. prepare returns ZIO[Scope, AgentError.HistoryPreparationError, Chunk[Msg]]. It runs before every model request, including thinking retries, discarded reasoning, and both Auto attempts. Preparation occurs only after prior authorization, tool execution, and result processing finish. Its scope closes before validation, canonical history commit, and ModelRequestStart. It shares the engine-attempt timeout and cooperative cancellation race; callbacks must not reenter mutating agent operations while the run lock is held.

An equal candidate preserves the existing permissive supplied-history API without adding global validation. An actual rewrite must preserve the exact ordered system-message subsequence and contain complete occurrence-aware tool pairing, with no unmatched inline/local results or unfinished local calls; unresolved provider-owned calls are not local obligations. The internal shared ToolHistory analysis isolates provider inline results to their own assistant message and matches local observations by ID and optional name. A changed incomplete history fails with HistoryPreparationError before state commit or model dispatch. A trusted rewriter may remove old incomplete context; structural validation cannot prove summary fidelity or semantic preservation. It is not a provider-specific request validator.

The harness ConversationCompactor.preparer installs compaction through this port and maps summarizer/hook errors to the distinct preparation error. Such errors cannot masquerade as native-format ModelError rejection and trigger Auto fallback. Typed failure leaves canonical input history unchanged. Defects propagate; cancellation closes preparation resources and appends the current attempt's recovery. External flush/offload effects already completed cannot be rolled back.

Recovery ownership is attempt-local: Ready before preparation, PersistedAssistant(position, completedOrdinalPositions) after accepted assistant or interrupted partial output, and Recovered(message) after reconciliation. Persisting each raw local or supplied observation and marking its original ordinal share a narrow interruption mask. Cancellation and failed-phase reconciliation use this ledger rather than reconstructing completion from result order or repeated IDs. The persisted position is valid only during the append-only active tool phase and is cleared before another rewrite. Nested cancellation finalizers reuse the exact recovery value; a later run, including empty-input continuation, owns a fresh recovery. Unaccepted hook output and supplied historical calls never become the current reconciliation target. A received model snapshot remains recoverable until the runtime accepts the request result. Pure cancellation during response cleanup or the following interruptible handoff persists it once before reconciling unfinished local calls; mixed failure causes remain failures rather than successful observations. Invocation usage remains independent of rewritten history and shared across Auto attempts.

Appending the optional make argument preserves ordinary callers; positional Builder matches must follow the current Builder constructor, including historyPreparer. Adding HistoryPreparationError requires exhaustive AgentError matches to handle the new leaf. Preparer values are runtime dependencies, not serialized configuration. Core remains independent of harness implementations.

## Current history-preparation identities

[HistoryPreparer](HistoryPreparer.scala) and HistoryRewrite define checked composition; [ReActAgentHistoryPreparerSpec](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentHistoryPreparerSpec.scala) exercises runtime commit and cleanup.

## Inline processing and failed acting phases

Matched terminal inline provider observations pass through the optional ToolResultProcessor after the raw assistant message is persisted. Processing owns an interruptible scope; cleanup finishes before the exact assistant/result occurrence is rewritten and Start/ordered text-data deltas/End are published. Duplicate IDs and identical result values retain independent ordinals. Processor payloads preserve runtime-owned identity, execution state, and control metadata. Typed failures retain raw content; partial processing cancellation retains already committed siblings and raw unprocessed inline observations. Provider completion never becomes a local Tool message, and final inline-only replies use the canonical processed assistant.

A bounded acting-phase finalizer runs after failed processors and parallel siblings close. For a non-interruption-only failure, it appends Error observations only for unrecorded local calls and preserves the original cause. It publishes neither synthetic completion events nor a user-interruption recovery. Pure cancellation continues to reconcile unfinished local calls and append one recovery message. Work may have performed external effects before aborting; an Error observation does not claim otherwise.

Model, hook, history-preparation, and acting races now explicitly own a scoped worker. Only worker completion waiting races the cooperative signal. Both cooperative and external cancellation interrupt and await the worker, retaining non-interruption failures from resource cleanup. Successful completion inherits worker fiber references before returning its exact exit. This closes the previously reproduced loss of processor-finalizer defects during cancellation.

Installing a non-identity processor now includes matched inline observations; the identity default preserves payload behavior. Supplied-history eviction is available through an opt-in preparer; exhaustive upstream middleware parity remains pending.

## Current inline-processing identities

HistoryPreparer.andThen runs stages in order within the caller's request scope. Each changed intermediate and final output is structurally validated before proceeding, so later stages cannot conceal an invalid prior rewrite. Failure/cancellation skips remaining stages and releases earlier resources. Only the complete prepared history is committed by ReAct; external stage side effects are not transactional. The eviction adapter can precede compaction to save raw payloads before pruning.

## Request-only model history

ModelInputTransform is distinct from HistoryPreparer: it returns a transient request view without canonical write authority. Existing apply(context, history) implementations remain valid; default projectRequest invokes that legacy callback once and retains the selected target's settings. ModelRequestInput contains prepared history, the selected model name, admitted tool definitions and ModelRequestSettings(temperature, maxTokens, topP, frequencyPenalty, presencePenalty, seed, stopSequences, reasoningEffort, thinkingBudget). It contains no client, credentials, endpoint or capability declarations. ModelInputProjection returns only history and settings; it cannot replace tools, the model, client or response format.

ModelInputTransform.withRequest(base)(project) adds selected-request behavior to an explicit base. Standalone apply still delegates only to the legacy base. projectRequest runs the base and added callback once, validates each intermediate history/settings before the next stage, and shares the caller's scope. Runtime execution closes the projection scope before final validation, request events and client dispatch. Temperature must be finite in [0, 2]; maxTokens must be absent or positive; optional topP must be finite in [0, 1], and optional frequencyPenalty/presencePenalty must be finite in [-2, 2]. Seed is an optional signed 64-bit integer. Stop sequences are an optional checked nonempty collection preserving exact strings. Reasoning effort is an optional typed provider generation preference. Existing HistoryRewrite rules preserve exact ordered system messages and complete occurrence-aware call/result pairs. Structural validation is not semantic fidelity or full provider grammar validation.

Every actual selected model attempt starts from prepared canonical history and its own configured settings, with a fresh request context. This includes explicit model failover and native/synthetic attempts. The admitted tool snapshot and active response format remain unchanged. Request-only skill projection follows validation. Built-in HTTP status retries reuse the serialized request and do not rerun projection. Projected history/settings never replace canonical history, configuration, recovery positions, usage or tool authority. Newly injected content is not automatically evicted.

Typed callback failures and invalid output use ModelInputError and cannot trigger model failover or native-format fallback. Defects and complete cancellation/cleanup causes propagate; scoped cleanup is joined. Completed canonical preparation is not rolled back. Callbacks must not reenter operations needing the held run lock.

Migration: the default projectRequest method preserves existing ModelInputTransform implementations and ordinary factory calls. withRequest requires an explicit base so existing Plan/other history behavior is retained intentionally. New request/projection values are transient immutable data without codecs. Dependent binaries should be rebuilt. Validation now also rejects invalid original temperature/maxTokens/topP/frequencyPenalty/presencePenalty with an identity or legacy transform, including custom clients, before request events or client invocation. This is a deliberate compatibility tightening, not a general middleware/interception framework.

## Current model-input identities

Two new unit groups cover lazy legacy composition and checked intermediate history/settings. Existing runtime tables cover invalid projection/configuration before dispatch, joined cancellation and complete cleanup causes. The failover suite uses the real completion adapter with controlled HTTP drivers to inspect serialized per-target history/settings, retry reuse, unchanged schemas/tool definitions and fixed-argument execution. It adds no new real socket server or live-provider evidence. General interception, changed tool authority and mutable Java hook APIs are outside this scope.

## Required turn completion observation

[TurnCompletionObserver](TurnCompletionObserver.scala) is an optional runtime dependency of ReActAgent.make and Builder.completionObserver; the default ignore preserves existing behavior. TurnCompletion contains agentId, the terminal canonical history, the original execution Exit, and trailing optional invocation correlation. It is runtime data with full Cause information, not a serialized callback or wire event. The snapshot is read-only and cannot replace canonical history.

One admitted invocation owns one observation attempt, including both attempts of structured Auto fallback. Calls cancelled while waiting for the run lock do not observe. Success is observed after request/tool cleanup, structured preparation, and canonical history commit, before AgentResultEvent. A final execution failure, defect, timeout, or external cancellation is observed after execution scope closure and reconciliation. Cooperative interruption may produce a successful Exit containing an interrupted AgentResult. Attempt-level AgentEnd can precede failure observation; it is not a persistence acknowledgement.

Observation runs in its own scope under the held run lock and cancellation mask. Resources close before successful result publication or completion. A slow observer delays cancellation; callbacks must not await this same agent's run, runStructured, streams, or resetHistory, including through another fiber. The model/tool timeout does not bound mandatory observation. No detached background work or automatic retry is introduced.

AgentResultEvent means committed execution output, not caller delivery acknowledgement. Cancellation arriving during successful observation can coexist with subsequent result publication while the caller ultimately exits interrupted. The observer sees the original successful execution outcome; it does not receive a second callback for late cancellation or its own failure. An observation failure suppresses AgentResultEvent. The dedicated CompletionObservationError cannot trigger native-format fallback; original execution and observation failure causes are preserved in sequential order, including interruption and cleanup defects.

Direct calls and fully consumed streams expose terminal observer failures. A consumer that stops a stream early still waits for producer cleanup and mandatory observation, but no longer consumes the queue carrying later terminal errors. Such a consumer cannot infer successful persistence from its own successful take/runDrain. No separate durable acknowledgement capability is provided.

Migration: the optional constructor/builder dependency is additive, but positional Builder matches must follow the current shape. Exhaustive AgentError matches must include CompletionObservationError. Hosts explicitly install the transcript adapter; the runtime does not assign missing Msg identities. See the [transcript contract](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/transcript/README.md).

## Current completion-observer identities

[TurnCompletionObserver](TurnCompletionObserver.scala) and TurnCompletion define the terminal observation contract. [ReActAgentCompletionInterruptSpec](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentCompletionInterruptSpec.scala) covers cancellation, cleanup causes and abandoned delivery.

## Completion observer composition

TurnCompletionObserver.andThen lazily composes two observations in separate scopes. The first scope closes before the second starts; typed failure, defects and interruption skip the successor and preserve their causes. Composition remains subject to the run-lock and completion-observation cancellation rules above. Composing background scheduler observers orders admission, not task dependencies after queue coalescing; see the [memory scheduling contract](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md).

## Current consolidation/composition identities

The harness [memory scheduling contract](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md) describes owned background consolidation and reporting. Observer composition orders admission; it does not establish transactional dependencies between background jobs.

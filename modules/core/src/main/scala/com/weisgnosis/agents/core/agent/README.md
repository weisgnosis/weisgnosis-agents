# ReAct execution and structured output

ReActAgent retains history and serializes runs. Model requests receive their own Scope, closed before the next request. Assistant tool calls are persisted before dispatch. Completed observations survive cancellation; unfinished local occurrences receive Error observations followed by one recovery message for the run. Thinking-only responses trigger bounded synthetic reminders. AgentConfig uses ToolExecution and a five-minute timeout; AgentResult uses AgentRunState and optional ChatUsage.

- [AgentConfig](AgentConfig.scala)
- [AgentResult](AgentResult.scala)
- [AgentRunUpdate](AgentRunUpdate.scala)
- [PostReasoningHook](PostReasoningHook.scala)
- [ReActAgent](ReActAgent.scala)
- [AgentEventForwarder](AgentEventForwarder.scala)
- [StructuredOutput](StructuredOutput.scala)
- [TurnCompletionObserver](TurnCompletionObserver.scala)

## Run-owned tool-group activation

The optional `toolGroups` construction dependency uses the [immutable group registry](../tool/README.md#host-selected-tool-groups) as initial configuration. Each public ordinary or structured invocation owns fresh activation state. Structured native-to-synthetic fallback shares that invocation's state; a later public invocation starts from the configured defaults. Retained history does not restore group activation.

One selected Toolkit snapshot supplies both a model request's definitions and its returned local call batch. `reset_equipped_tools` replaces the active Meta groups for the next request. It cannot enable an unadvertised tool in its own batch or revoke tools already admitted in that batch. External groups remain under host control. The reset tool declares both isConcurrencySafe and isReadOnly false, forces its batch through sequential dispatch and goes through ordinary authorization; a denied or invalid reset leaves activation unchanged.

Runtime controls are composed after filtering the host toolkit. The reset tool and synthetic `generate_response` remain available even when group membership names refer to them. Name collisions are checked against the complete host toolkit, including hidden tools. Fixed arguments and host tool flags remain attached to the selected values. The reset tool exists only when Meta groups are configured, and structured-output capability selection counts it as a tool.

Reset input is exactly an object containing `to_activate`, an array of group-name strings. Unknown or External groups produce an Error observation without partial mutation. Its schema includes only configured Meta names. Each request regenerates the discovery description from metaNotes on the same immutable snapshot used for available tools; active/inactive Meta names and descriptions are visible, while External groups remain excluded. Successful output retains active_groups for the selected Meta names and adds notes from the atomic replacement result, without a later state read. Recursive duplicate validation remains enforced; malformed duplicate arguments may be denied by the ordinary authorization boundary before decoding. Group configuration and permission policy are separate: this mechanism grants no filesystem or remote-client authority.

Migration: make appends `toolGroups: Option[ToolGroups] = None`; Builder adds a toolGroups setter and a defaulted field. Ordinary call sites retain prior behavior. Positional Builder patterns/Product arity change and downstream binaries must be rebuilt. This explicit dependency is not Toolkit metadata; filtering the supplied base toolkit cannot recover removed tools. Control-name collisions retain Toolkit's existing ToolExecutionError type/message. The reset result now adds a notes string; consumers that reject unknown result fields must accept it. Descriptions now reflect each request snapshot rather than static construction-time text.

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for the group/skill baseline below, including group/skill reminders, preloaded skills and admitted-access propagation, with no findings. Formatting and full `verify` passed in `skill-reminders-final-verify.log`: zero compiler warnings or failures; the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance) records current repository totals.
- [ ] Complete other skill repository variants and durable activation; bounded local discovery/loading is available in the harness. Exact Java prose is not a compatibility contract.

| Reviewed group/skill baseline | Git blob |
| --- | --- |
| [ReActAgent.scala](ReActAgent.scala) | [Current request-projection identity](#current-model-input-identities) |
| [ReActAgentToolGroupsSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentToolGroupsSpec.scala) | `e313fa6b8db16c4f935eac3c7aefa620488d53fa` |

The table records the group/skill baseline. The current runtime identity is in [scoped model failover](#scoped-model-failover), while the unchanged groups spec is recorded under [deferred skill reads](#deferred-skill-reads); earlier contracts retain their scoped acceptance.

## Preloaded skills

`make` accepts `skills: SkillCatalog | SkillLibrary = SkillCatalog.empty`; Builder adds the same defaulted field and a skills setter. Ordinary source calls keep their behavior, while positional Builder patterns/Product arity change and dependent binaries require rebuilding. The [checked catalogue](../skill/README.md) contains host-preloaded text only. Construction validates its explicit target groups against the actual configured ToolGroups before allocating runtime refs or calling the model; unknown and External targets fail. No generated group names are inferred.

A nonempty catalogue installs the protected `load_skill_through_path` control after base filtering, with complete-base collision checks. The request-only catalogue projection moves the configured ID/description/resource listing out of this tool description; its initial instruction sentence and schema remain unchanged. Input is exactly nonblank skillId and path strings with recursive duplicate validation. The path is data used for catalogue lookup, never filesystem access. SKILL.md selects main content; other resources require exact keys. Missing, malformed or unknown requests cannot activate groups.

The loader is read-only and concurrency-unsafe, uses ordinary authorization and forces sequential batch dispatch. Successful lookup atomically unions the skill's explicit Meta targets with currently active Meta groups while preserving External state, then returns the exact resource content. Existing request/batch snapshots keep new tools unavailable until the next model request. Load and reset controls stay available after filtering; activation shares native-to-synthetic fallback state and resets on the next public run. A content-only catalogue still contributes a control to structured Auto capability routing.

Reset descriptions and result notes include the catalogue's pure group-binding reminders beside the corresponding Meta snapshot. Rendering starts from immutable raw definitions each time, so repeated requests, fresh runs and structured fallback do not accumulate reminders. Notes are advisory: successful reset-to-empty still deactivates all Meta groups and returns their current inactive notes. Empty/no-target catalogues preserve previous note text. No active-skill state or automatic group registration is introduced.

- [x] Both reviewers approved the preloaded-skill runtime contracts; [scoped runtime evidence](#deferred-skill-reads) and full verification cover union, exact content, error/denial isolation, hidden collisions, construction validation, fresh runs, fallback and content-only Auto routing.
- [x] Actual skill-loader admission is verified through the opt-in [harness Plan adapter](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md#dynamic-plan-admission), including policy/confirmation and denial of mutating tools. The read-only flag alone grants no permission.

## Request-only skill catalogue

After ModelInputTransform output passes validation, the runtime appends SkillCatalog.systemPrompt to the first system message while preserving its existing fields and content, or prepends a system message when absent. Later system messages and nonsystem ordering remain unchanged. The projection contains only selected IDs, descriptions and logical resource names; bodies, resource contents, activation targets and inert metadata are excluded. Empty catalogues preserve prior request input. Canonical history, history preparation, transform input, completion observations and returned results do not acquire this text. No resources are loaded and no groups are activated by rendering.

The same projection runs for every model request, including native-to-synthetic fallback and fresh public runs, without accumulating copies. Numeric Java hook priorities are replaced by this explicit pipeline order; this is not a general hook framework or exact Java prompt reproduction.

Migration: configured skill listings move from the loader description to transient model-request system content. This changes observable prompt text and projection ordering, not tool names, schemas, permission authority or skill activation. The loader retains its instruction sentence and strict schema.

- [x] `registry_contract` approved FP/ZIO correctness and `current_docs` approved invariants for the reviewed request-projection baseline below, with no findings. One runtime table group verifies first-system field/content preservation, prepend/empty behavior and canonical isolation across requests and public runs; existing activation/fallback assertions verify one catalogue projection per request. The paired pure catalogue test checks selection and escaping. Focused verification passed 15 groups in 623 ms (`skill-system-prompt-focused.log`). Full `scalafmtAll scalafmtSbt verify` passed in `skill-system-prompt-verify.log`: 602 core / 690 harness / 82 MCP tests (1,374 total), compile task 2 seconds and test task 15 seconds, zero compiler warnings or failures.

| Reviewed request-projection baseline | Git blob |
| --- | --- |
| [ReActAgent.scala](ReActAgent.scala) | [Current request-projection identity](#current-model-input-identities) |
| [ReActAgentToolGroupsSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentToolGroupsSpec.scala) | `2eb319710008d11433978458c47a01d1c9eac110` |

The [catalogue contract](../skill/README.md#deferred-resource-capabilities) owns current catalogue/library identities and combined verification. Existing execution, forwarding, group and permission contracts retain their scoped evidence below. The [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance) owns current repository totals and next work.

## Deferred skill reads

The skills parameter and Builder setter accept SkillCatalog or SkillLibrary through one parameter; ordinary catalogue callers retain their behavior. The runtime exposes the stable catalogue through skills, while reader capabilities remain private. Widened method/Builder field types require downstream recompilation and can affect typed method values or positional patterns; no binary compatibility is promised.

The protected loader retains ordinary authorization and sequential dispatch. An approved exact library key performs one scoped read; only successful read and finalizer completion can union catalogue-owned Meta targets. Failed reads or cleanup produce Error observations without activation; cancellation joins cleanup and preserves its cause. Prompt projection lists logical deferred paths without fetching content. There is no automatic disk fallback or new model-controlled location authority.

- [x] `registry_contract` approved FP/ZIO correctness and `current_docs` approved invariants for the current identities below, with no findings; full verification passed. The earlier request-projection table records its preloaded baseline. Combined ten-file verification is recorded in the [capability acceptance](../skill/README.md#deferred-resource-capabilities).

| Reviewed deferred-read baseline | Git blob |
| --- | --- |
| [ReActAgent.scala](ReActAgent.scala) | [Current request-projection identity](#current-model-input-identities) |
| [ReActAgentToolGroupsSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentToolGroupsSpec.scala) | `9dcf3a5ef4342db59486413a25675eb74c51efa0` |

Current ReActAgent identity and execution approval are in [scoped model failover](#scoped-model-failover); the groups spec above is unchanged.

## Fixed-argument authorization

Attached [fixed raw arguments](../tool/README.md#fixed-raw-arguments) are prepared before local authorization, without changing the persisted original call while approval is pending. Malformed preparation yields Error without callback execution; signed-input changes and approved fixed-field alterations yield Denied. Nonfixed rewrites remain allowed. Approved history, invocation context and execution share the final prepared input, retaining original model-input provenance. Inline provider results still bypass local dispatch. Cancellation joins authorizer cleanup and retains the complete interruption/cleanup cause.

Current Tool/Toolkit identities and scoped verification belong to the linked tool acceptance; the current ReActAgent identity is in [scoped model failover](#scoped-model-failover); the result/event contracts below remain separate bounded scopes.

## Incremental local tool results

The [incremental execution contract](../tool/README.md#incremental-execution) publishes provisional text/data only after assistant-call persistence and ordinary authorization. Runtime call identity, state and control metadata remain authoritative. No provisional Tool message is stored, and the next model request receives only the final processed observation after stream EOF and cleanup. The invocation event lease closes before final result processing.

Final-delta suppression is local to each execution occurrence, including repeated model IDs. Provisional content does not replace the final result: ToolResultEnd carries its final text/state/metadata, while full typed final content remains in canonical history. Ordinary tools and metadata-only progress retain aggregate publication. A cancelled unfinished stream follows existing reconciliation; completed siblings survive. Inline server results bypass local execution unchanged.

- [x] `path_review` and `current_docs` approved the final six-file scope at the [current identities](../tool/README.md#incremental-execution).
- [x] Full `tool-stream-verify.log` verification passed with zero compiler warnings or test failures; the linked tool contract records scoped identities and evidence. Two added ToolEventSpec groups cover visible provisional text/image before completion, persisted-call and model-history boundaries, protected metadata, cleanup before the next model request, and parallel repeated IDs with two streams plus a legacy sibling. The existing cancellation fixture now includes an unfinished streaming tool. Shared-Toolkit destination isolation has the separate acceptance below.

### Shared-Toolkit destination isolation

Concurrent independently owned agents can share the same Tool and immutable Toolkit while retaining separate incremental-event destinations and canonical histories. The shared fixture holds both executions between their first and second progress chunks, then checks exact per-agent deltas/final results and distinct request/reply contexts despite identical tool-call IDs. This is not support for concurrent runs on one Scala agent; no production API or implementation changed.

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for [ReActAgentToolEventSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentToolEventSpec.scala) at `b634d0f0d15cc56b27a2040d0eb925722af7c609`, with no findings. Focused verification passed nine tests in 417 ms (`shared-toolkit-focused.log`); full `scalafmtAll scalafmtSbt verify` passed in `shared-toolkit-verify.log` with zero compiler warnings or failures. Current repository totals are in the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).

## All-tools-denied observation

After successful cleanup of a nonempty entirely local admitted batch, ReAct publishes AllToolsDeniedEvent only when every final observation is Denied. The complete ordinal-to-history ledger includes outcomes from earlier partial resumes and preserves repeated IDs; the payload contains persisted approved calls in original order. Deferred approval and current or saved acting Stop take precedence. Publication follows all result End events and precedes the next model request in ordinary, native and synthetic execution; it does not stop the run or establish human provenance.

Mixed Success/Error, inline/provider batches and incomplete deferred batches do not qualify. A tool cannot grant itself denial authority: tool-produced Denied is normalized to Error. Optional processor failure preserves the authoritative observation. An interrupted or defective phase retains its full cause and does not reach aggregate publication; later cancellation cannot retract an already published observation. No public API or event codec changes.

| Reviewed file | Git blob |
| --- | --- |
| [ReActAgent.scala](ReActAgent.scala) | [Current provider-ownership identity](#provider-owned-pending-calls) |
| [ReActAgentToolAuthorizerSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentToolAuthorizerSpec.scala) | [Current provider-ownership fixture](#provider-owned-pending-calls) |

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for this pair, including unchanged Direct behavior through the shared completed-ledger helper. Two grouped cases cover fresh/restored batches, priority and exclusion tables, structured runs, held cleanup and cancellation. Focused checks passed 19 tests in 678 ms (`all-denied-final-focused.log`), including the three existing Direct groups; full `scalafmtAll scalafmtSbt verify` passed in `all-denied-verify.log` with zero compiler warnings or failures. The [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance) owns current totals.
Provider-owned pending calls have the separate reviewed ownership contract below; AllToolsDenied remains restricted to entirely local batches.

## Provider-owned pending calls

ToolUse marked serverTool=true (or the supported legacy alias) belongs to the provider even when no inline result exists. Calls without inline results bypass local authorization, execution, result processing and synthetic local recovery; matched inline results retain their existing processor path. They remain in the assistant history and keep ordinary reasoning running within its existing iteration/timeout bounds. Local siblings retain original ordinals even when IDs and names collide. Complete inline-only batches still finish normally; explicitly captured structured output retains its existing precedence over provider continuation. No new provider-wire support is added: ChatCompletions still rejects server calls/results.

ToolHistory completeness describes local obligations. Same-message inline results consume provider occurrences first; remaining provider calls create neither a local pending entry nor a cross-message pairing span. Separate Tool messages and later assistant-only results cannot complete those provider calls. Local crossing pairs still move compaction cutoffs backward; wholly completed local prefixes can be removed safely.

BeforeActing checkpoints may preserve a provider-only pause, with no local pending choices; resume continues the provider rather than inventing a result. Supplied results and permission requests can identify only unresolved local ordinals. AwaitingApproval requires unfinished locals, and a saved Stop must identify a completed local ledger entry or an actually matched inline occurrence. No checkpoint wire shape changes; malformed checkpoints relying on local ownership of provider calls now reject.

| Reviewed file | Git blob |
| --- | --- |
| [ReActAgent.scala](ReActAgent.scala) | [Current topP scope identity](../client/README.md#optional-nucleus-sampling) |
| [PendingActing.scala](PendingActing.scala) | bdcdb1e276a1dcbb1a86dc321c4ecdf39bdbeb4c |
| [ToolHistory.scala](../model/ToolHistory.scala) | ba82c0b9d409c0eb444b2212c603532efa83235f |
| [ReActAgentToolAuthorizerSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentToolAuthorizerSpec.scala) | f3382b5f930c7810c3193d9a027df04382b9707b |
| [PendingActingSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/PendingActingSpec.scala) | 349c047eebb15bcfb2b4c2113714047384780597 |
| [ToolHistorySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/model/ToolHistorySpec.scala) | 2ff6291ca75b49770c0a337da5488608f8fa040e |
| [ConversationCompactorSpec.scala](../../../../../../../../../harness/src/test/scala/com/weisgnosis/agents/harness/memory/ConversationCompactorSpec.scala) | 76675758a9bcebc50f66c05b5b3d7c281d78f5b3 |

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for these seven identities, with no remaining findings. Focused checks passed 33 tests in 795 ms (`provider-ownership-focused.log`); full `scalafmtAll scalafmtSbt verify` passed in `provider-ownership-final-verify.log` with zero compiler warnings or failures. Current totals are in the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).
- [x] `provider-ownership-behavior-red.log` recorded 17 passing and four failing tests against production revision `eca5c4d`, with regression specs `6c2a92f81637503ce70b3c4a928c46637d974cbe` (authorizer), `349c047eebb15bcfb2b4c2113714047384780597` (checkpoint) and `2ff6291ca75b49770c0a337da5488608f8fa040e` (history): provider sentinel execution occurred, mixed history contained an extra local result, cancellation retained provider Success alongside local Error, and local-history/checkpoint ownership assertions failed. The corrected regression assertions remain unchanged; the authorizer fixture only received a title correction. A separate stale compactor fixture was replaced with genuine crossing/noncrossing local-pair cases, rather than treating a Tool-role observation as provider completion. The initial compile-only run is not defect evidence.

## Host-selected return-direct completion

ToolDef.returnMode defaults to ToolReturnMode.Continue. Direct permits an ordinary fresh or resumed run to finish without another model request only when its original nonempty admitted batch contains exclusively local Direct calls and every processed observation is Success. Eligibility uses the admitted definitions and complete original-ordinal ledger, including supplied and previously completed results. Later activation changes do not grant this policy. Deferred approval takes precedence, followed by the earliest current or saved acting Stop; all admitted work and processor cleanup join before direct completion. Cancellation and defects retain their existing failure authority. Native and synthetic structured runs continue to their normal capture boundary.

Successful direct completion preserves the full processed Tool history and appends a presentation assistant tagged ToolReturnDirect, using runtime reply identity and no promoted result-level control metadata. Output follows original call order, including repeated IDs and reversed completion. Only Text, Image, Audio, Video and Data blocks are presentational; any Thinking, ToolUse, ToolResult, Custom or Hint block declines the shortcut without dropping the observation. An entirely zero-block batch produces `(no output)`; an empty Text block remains empty, and a partially empty batch gets no placeholder. Result processors can redact content or request Stop; original unprocessed payloads never bypass them. Existing AgentResult/AgentEnd delivery remains; no additional closing TextBlock event sequence is synthesized. Provider serialization limits still apply to retained media history on a future model call.

Migration: ToolDef appends the defaulted returnMode field. Old JSON without it decodes as Continue; named construction/copies retain defaults. Positional patterns/Product arity now have seven fields, and dependent binaries must be rebuilt. Restoring a checkpoint still requires exact trusted host definitions, including returnMode. ChatCompletions does not serialize this local completion policy as a provider tool-schema field.

| Reviewed file | Git blob |
| --- | --- |
| [ReActAgent.scala](ReActAgent.scala) | [Current reviewed runtime identity](#all-tools-denied-observation); shared completed-ledger extraction preserves Direct behavior. |
| [ToolDef.scala](../tool/ToolDef.scala) | 8848c5c6698a13c05e4dc6fe9a8ec51710ec0708 |
| [ReActAgentReturnDirectSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentReturnDirectSpec.scala) | e9de09814ad62b90d3592fdf8d205de7d5bfd935 |
| [ToolDefSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/tool/ToolDefSpec.scala) | 67e4f5c680d919c0f4a4cc1858b5c4d4d2769219 |
| [ChatCompletionsRequestSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ChatCompletionsRequestSpec.scala) | [Current fixture identity](../client/README.md#user-image-projection); returnMode omission remains covered. |

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for these five identities, with no findings. Three grouped runtime tests cover ordered processed output and rejection cases, live/restored/supplied/partial resumes, joined cleanup/cancellation and native/synthetic exclusion; existing tables cover codec compatibility and provider-field omission. The resumed supplied Error and mixed-mode rows verify zero local execution, exact-once processing, preserved result states/content in the actual following model request and one further request. Supplied Denied remains unsupported. Both reviewers renewed approval of the current spec; the Direct contract remains unchanged, with the runtime helper reviewed in the linked AllToolsDenied scope. Focused checks passed three groups in 599 ms (`return-direct-resume-focused.log`).
- [x] Full `scalafmtAll scalafmtSbt verify` passed in `return-direct-resume-verify.log` with zero compiler warnings or test failures. Current totals and process-local verification settings are in the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).

## Current typed-result acceptance

The [tool contract](../tool/README.md#typed-execution-observations) owns the result API migration, protected metadata and cancellation-cause semantics. Typed payloads reach persisted history and existing text/data events without changing runtime call identity. The current ReActAgent identity belongs to [result-bearing streams](#result-bearing-streams); unchanged typed-result component identities remain in the [tool acceptance](../tool/README.md#current-review-acceptance).

- [x] `path_review` and `current_docs` approved typed local result publication within the linked tool acceptance; full repository verification passed with no warnings or failures.

## Result-bearing streams

The additive `streamResumeWithResult(results = Chunk.empty)` and `streamResumeEvents(results = Chunk.empty)` APIs resume the same saved execution through the shared stream lifecycle. They are lazy: checkpoint validation and claim occur only when consumed, under the same run lock as direct resume. Both use the existing stream owner, single event forwarder and terminal delivery path; event-only projection does not execute another run. Supplied-result validation failure leaves the checkpoint available and starts no completion observation. Successful resumed delivery preserves saved tool context and ends with exactly one Result after AgentEnd and joined cleanup. Fully consumed failures preserve the complete Cause; early consumers join owned cleanup but abandon later output.

`streamWithResult(messages)` emits [AgentRunUpdate](AgentRunUpdate.scala).Event values followed by exactly one Result containing the complete AgentResult after successful invocation and scoped cleanup. `streamStructuredWithResult[A](messages, schema, mode = Auto)` retains the same result together with the validated decoded A. Single-message overloads are available. Event-only APIs project Event values from the same execution; there is no second model call or result reconstruction.

All events precede Result, including the terminal AgentEnd. Result preserves history, iteration count, aggregate usage and runtime state: ordinary cooperative interruption yields an Interrupted result with recovery history. Result does not imply Completed. A failing invocation emits no Result; fully consuming the stream retains its complete typed/defect/interruption Cause.

Early consumers cancel and join owned execution but abandon later output, including later completion-observer errors. Existing unbounded delivery buffers provide neither backpressure nor replay. This additive execution envelope has no wire codec and changes no AgentEvent payload or constructor. The separately reviewed [invocation event capability](../tool/README.md#invocation-event-capability) grants scoped publication to approved local tools; harness child forwarding has separate acceptance, while delivery after promotion remains pending.

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for the three changed identities below, with no remaining findings. Full `scalafmtAll scalafmtSbt verify` passed in `resumed-streams-verify.log`, with zero compiler warnings or failures; current totals belong to the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance). Focused `resumed-streams-green.log` passed 35 core tests in 733 ms and five harness tests in 420 ms. Two new core groups verify lazy resume, supplied-result correlation, full observer failure causes and joined early/cancelled consumers; one harness table verifies custom/default denial text, ordered original-context events and no local execution.

| Reviewed file | Git blob |
| --- | --- |
| [ReActAgent.scala](ReActAgent.scala) | [Current request-projection identity](#current-model-input-identities) |
| [AgentRunUpdateSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/AgentRunUpdateSpec.scala) | 8e28a12574d17faab954663516b2fb9529de34e5 |
| [ConfirmedBatchAuthorizerSpec.scala](../../../../../../../../../harness/src/test/scala/com/weisgnosis/agents/harness/permission/ConfirmedBatchAuthorizerSpec.scala) | e8270b178be3f6d4dd90868be6555025bac07939 |

[AgentRunUpdate.scala](AgentRunUpdate.scala) retains its accepted identity `e441b9614dba3364da17b6b4780c3a6fae002396`; its ADT and codec policy are unchanged. Existing ordinary/structured scenarios retain full-value, cooperative interruption and terminal ordering coverage. The separately reviewed [invocation capability](../tool/README.md#invocation-event-capability) closes each approved tool's publication lease before result processing.

## Direct event forwarding

The package-visible `runForwarding(messages, destination, source)` runs once under the existing run lock and completion observer. The calling effect retains the complete AgentResult and full failure Cause. A private scoped hub and forwarding fiber publish through the supplied ToolEventPort; after execution and producer cleanup finish, the private AgentEventForwarder signals completion to the sole subscription consumer. That consumer delivers every claimed batch and queued suffix in FIFO order; shutdown and join finish before the caller returns. Completion waits race only signal observers, never competing data consumers. Successful cleanup then restores the caller's interruption status; delivery defects retain their full cause. Result authority does not depend on consuming an event stream. Public stream early-consumer behavior is unchanged.

The host-selected source prefixes any existing nested source with `/`; reply/request identities remain unchanged. Source labels are diagnostic data, not authentication. A closed destination drops publication without cancelling execution. Delivery remains unbounded, with no replay or backpressure. This internal ordinary-run primitive does not install forwarding in harness tools or add structured child execution.

The unchanged [ReActAgentForwardingSpec](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentForwardingSpec.scala) exposed the previous competing-consumer race in `local-skill-verify.log`: against ReActAgent blob `0fbbe1a282f1740efcb96b05d3d4befb39f44f4c`, the final event was ModelResponseEnd rather than the expected AgentEnd. The fix changes delivery ownership, not the regression expectation. Its existing three groups retain result, attribution, compound-cause and cancellation coverage.

[AgentEventForwarderSpec](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/AgentEventForwarderSpec.scala) adds three barrier-based groups for held-prefix/queued-suffix order, empty and claimed-only shutdown, exact delivery defects, and cancellation joining held publication. The focused helper/integration/stream run passed 27 tests in 522 ms.

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for the three changed identities below, with no findings; the original forwarding spec remains unchanged. Full `event-forwarder-verify.log` verification passed: 599 core / 678 harness / 79 MCP tests (1,356 total), zero compiler warnings or failures, compile task 5 seconds and test task 12 seconds. The [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance) owns current repository totals.

| Reviewed forwarding baseline | Git blob |
| --- | --- |
| [AgentEventForwarder.scala](AgentEventForwarder.scala) | `9198abc9f0b13f1cb1e347df0a73c57c50f6e2d1` |
| [ReActAgent.scala](ReActAgent.scala) | [Current request-projection identity](#current-model-input-identities) |
| [AgentEventForwarderSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/AgentEventForwarderSpec.scala) | `2c0bbde7e0e5eb94f8c3f963ead3f1ed4eed37ab` |
| [ReActAgentForwardingSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentForwardingSpec.scala) | `9c602ab087cb78362035043bf07181b0aa108f89` |

- [x] The separately reviewed [harness adapters](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#scoped-child-event-forwarding) install this primitive for owned spawn/send operations with task attribution and per-invocation leases.

## Runtime events and result pairing

One correlated AgentStart/AgentEnd pair surrounds each engine attempt. End follows loop termination/reconciliation, including failure or interruption. Successful preparation commits history, completes required observation and its scope cleanup, then publishes one runtime AgentResultEvent before End. Observation failure suppresses the result event while retaining committed history. Ordinary cooperative interruption publishes its recovery reply; failed structured preparation publishes no finalized result. Streaming uses the same sole-consumer AgentEventForwarder and a private Take queue carrying the original exit. Producer completion signals draining; the consumer finishes its claimed batch and queued events before shutdown and join, then the terminal take is published. A provider-published AgentEnd cannot terminate delivery. Early consumer completion closes owned producer/model scopes. Publishers must remain within those scopes; the unbounded hub/queue do not provide backpressure.

After all configured iterations complete without a final response, the runtime emits exactly one ExceedMaxItersEvent with the attempt's replyId, configured maximum, and completed iteration count before the normal end. Completion on the last permitted iteration does not emit a limit event. Unlike the pinned upstream guard, this event never intentionally uses an empty replyId.

Completed provider-hosted inline calls bypass local execution and do not create Role.Tool messages. Each inline observation is consumed once by call ID and occurrence, retaining pending call order. Local calls sharing a server ID remain executable. For each inline result, publication is Start, one TextDelta or DataDelta per output block in original order, then End. Empty output emits only boundaries; state and full result metadata are preserved. Data deltas carry the correlated replyId. Full metadata propagation is an intentional Scala difference: pinned upstream inline events synthesize only the server-tool marker.

Cancellation reconciliation also consumes completed local observations by occurrence, matching a provided tool name as well as ID. A completed result cannot suppress every unfinished same-ID call, and parallel completion of a different named tool does not satisfy a pending call. Nameless observations match by ID/occurrence. Completed observations remain unchanged.

## Acting-stop decisions

A host-installed ToolResultProcessor may override processDecision or use deciding to return Continue(payload) or Stop(payload, reason). Tool output and model metadata cannot supply this authority. Payload application still preserves call identity, execution state and reserved control metadata. Existing process implementations run once through the default Continue adapter. andThen short-circuits the decision chain for that observation on Stop; direct payload-only process calls still run every composed stage.

The runtime persists calls before execution and processes every admitted local or matched inline observation. Stop does not cancel sibling work or skip later calls in a sequential batch. Successful processor scope cleanup, payload persistence and completed result publication precede selection of the earliest Stop by original assistant-call ordinal, independent of completion order or repeated IDs. The owning assistant receives acting_stop_requested, then RequestStopEvent is published. Ordinary execution returns AgentRunState.Stopped(reason), with the canonical assistant reply, all paired observations and accumulated usage; it makes no further model request. A later public run may perform new work.

Typed processor failure retains the raw observation and contributes no Stop; defects and interruption override provisional decisions. Owner cancellation, including held cleanup failure, appends one interrupted recovery while retaining completed observations and the full Cause. A mixed Cause from defect-driven parallel sibling interruption alone is not owner cancellation: unfinished siblings receive Error observations without a spurious interrupted recovery. Cancelling a public event-stream consumer still abandons later output. Structured execution reports AgentError.ExecutionStopped before decoding even an already captured synthetic response, without native fallback or a successful AgentResultEvent. This is completed acting-phase termination, not Suspended state, HITL checkpointing or durable resumption. Required completion observers remain host failure boundaries.

Migration: processDecision is a concrete additive method and deciding is an opt-in factory; legacy processors need no override. ToolResultDecision is a runtime ADT without a wire codec. Exhaustive AgentRunState, AgentError and SubagentToolState matches must handle Stopped/ExecutionStopped. AgentRunState's derived codec gains Stopped(reason); the new error leaf has its own codec. Dependent binaries must be rebuilt. Child spawn/send projects Stopped as a bounded Error observation while task inspection retains the terminal value; retained sessions remain ready because only Suspended activates their continuation latch.

| Reviewed file | Git blob |
| --- | --- |
| [AgentResult.scala](AgentResult.scala) | [Current identity](#before-acting-suspension-and-restoration) |
| ReActAgent.scala | Current identity: [scoped model failover](#scoped-model-failover). |
| [AgentError.scala](../error/AgentError.scala) | 595899f138addfb459d8845f57806bb4a1d2eca6 |
| [ToolResultProcessor.scala](../tool/ToolResultProcessor.scala) | bf23b2d700291b38545f9c0ed9f57826ac9c5158 |
| [AgentResultSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/AgentResultSpec.scala) | a94ecf5e07e816c1b53bbb2532ad251a111cc064 |
| [AgentErrorSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/error/AgentErrorSpec.scala) | d54380b0026082045e870169362ef81dfe3abd2f |
| [ToolResultProcessorSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/tool/ToolResultProcessorSpec.scala) | 79dc47e7a7d411978aed8d7361e02d351bd2c894 |
| [SubagentTools.scala](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/subagent/SubagentTools.scala) | [Current identity](#before-acting-suspension-and-restoration) |
| [SubagentToolsSpec.scala](../../../../../../../../../harness/src/test/scala/com/weisgnosis/agents/harness/subagent/SubagentToolsSpec.scala) | bc13a74044ad3d6e5bfd92efb0eda362dae71bb4 |
| [ReActAgentActingStopSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentActingStopSpec.scala) | d6bd7f1768f9abc1e6178f758010d1677b750d1d |
| [ReActAgentInlineProcessingSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentInlineProcessingSpec.scala) | a96798914868b8698405643d4f5f15618748bf14 |
| [ReActAgentModelInputSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentModelInputSpec.scala) | [Current identity](#current-model-input-identities) |

- [x] path_review approved FP/ZIO correctness and current_docs approved invariants for the acting-stop scope (unchanged identities above and runtime baseline e9f789c2de31afa312c01e04ec1d464efbcb441f), with no findings. Full scalafmtAll scalafmtSbt verify passed in acting-stop-accepted-verify.log with zero compiler warnings or failed tests. Focused acting-stop-origin-green.log passed 39 core tests in 568 ms and eight harness tests in 519 ms. Current aggregate totals belong to the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).
- [x] Accept the separate [before-acting suspension contract](#before-acting-suspension-and-restoration); partial HITL confirmations, returnDirect flags and host persistence remain separate.

The cancellation-origin regression in acting-stop-origin-red.log ran unchanged expectations against draft ReActAgent 88020c23137d08e7bf58eeba232b59c8155dcc27 and InlineProcessingSpec a96798914868b8698405643d4f5f15618748bf14: eight tests passed and two failed. Cooperative interruption with a cleanup defect lacked the required recovery and complete pairing; timeout with a cleanup defect incorrectly added user-interruption recovery. The final caller-boundary exit hook checks actual caller interruption or this run's cooperative signal after joined cleanup, retains generic failure pairing and preserves the full Cause. The same expectations pass alongside unchanged timeout and sibling-defect controls.

## Structured output

StructuredOutput supports native formatting and synthetic generate_response with root definition hoisting. Synthetic output completes only after acting finishes in the Completed state, without another model turn. Ordinary tool pairs and unrelated empty history remain. Final replies retain signed thinking exactly once and generating-request usage; AgentResult usage aggregates the run. Stopped runs fail with AgentError.ExecutionStopped(reason) before captured payload decoding and do not trigger Auto provider fallback. Other incomplete runs fail with AgentError.Interrupted and retain recovery history even after payload capture.

runStructured and streamStructuredEvents share the same preparation and commit path. Structured payload resolution selects existing metadata or parses visible text exactly once; that AST supplies both decoding and committed metadata. Preparation remains interruptible and timeout-bounded. A masked completion section commits finalized history, completes required observation and cleanup, then publishes AgentResultEvent before AgentEnd. Required observation can delay cancellation without the attempt timeout bounding it. Late cancellation may coexist with committed result publication while the caller ultimately exits interrupted. Auto fallback retains two attempt boundary pairs, with no result from the failed native attempt; the run lock covers both attempts. Input event payloads and ToolCallState metadata do not grant runtime permission or stop/resume authority.

## Scoped model failover

ReActAgent.make appends fallback: Option[ModelTarget] = None; Builder.fallback installs one explicit client/config pair. This is a host capability, not model-selected routing or serialized LlmConfig data. Each model request tries its primary client first and may select the fallback once; later loop iterations start with the primary again. Default behavior remains unchanged.

Only the built-in completion adapter can produce the private pre-response rejection evidence used here: an eligible actual HTTP rejection after its status-retry budget and response cleanup. A custom client's public ModelError, acquisition failure, successful-response stream failure, invalid projection, interruption or mixed cleanup Cause cannot trigger failover. Legacy/custom LlmClient implementations keep their existing streamTurn contract and do not gain this authority. Public client/transport calls still expose ordinary AgentError values.

Each target gets a fresh requestId under the same replyId, its own scoped ModelInputTransform invocation from the same prepared canonical history, and the same admitted tool-definition snapshot. Request-only skill projection follows validation again. The primary request view is not reused or persisted as the fallback's input. Model start/end events name the actual selected model; successful fallback usage is counted once. Owned request/projection cleanup completes before another target starts, and the surrounding run timeout/cancellation remains authoritative. If fallback fails, the original primary failure precedes its complete Cause; defects and interruption are retained.

Compatibility follows the active request plan. Without responseFormat, fallback capability declarations need not match. With native responseFormat, the target must declare Native, and must also support application tools when the admitted definitions are nonempty. An incompatible target performs no projection or I/O and leaves the primary failure unchanged. The original responseFormat overrides the target configuration, including clearing it for synthetic requests; schemas and tool definitions are not silently downgraded. Native-to-synthetic Auto fallback accepts only an exact singleton ModelError rejection with status 400, 422 or 501, never a compound failure.

Migration: ordinary construction calls retain fallback=None; appended make/Builder fields may require method-value or positional-extractor updates and dependent binaries must be rebuilt. ModelTarget contains a live client and is intentionally not a JSON codec/configuration registry. There is no failover listener, persistent selected-model state, automatic capability discovery or remote routing.

| File | Reviewed Git blob |
| --- | --- |
| [ReActAgent.scala](ReActAgent.scala) | [Current request-projection identity](#current-model-input-identities) |
| [LlmClient.scala](../client/LlmClient.scala) | [Current topP scope identity](../client/README.md#optional-nucleus-sampling) |
| [ChatCompletionsClient.scala](../client/ChatCompletionsClient.scala) | 41fe7ee22d997bb6e190d89df49d72747fce9f20 |
| [ChatCompletionsTransport.scala](../client/ChatCompletionsTransport.scala) | 80b7ff0f54208c99085192edc59cdcc417e7ac04 |
| [BeforeResponseRejection.scala](../client/BeforeResponseRejection.scala) | 96121e91541c7858bd5f6f6764546cb6aa2d4bf2 |
| [ModelTarget.scala](../client/ModelTarget.scala) | 5820bc6829e9ae0eaa81ca1bcad1d5024bba3bec |
| [ModelFailoverSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ModelFailoverSpec.scala) | [Current identity](#current-model-input-identities) |
| [StructuredOutputCapabilityRoutingSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/StructuredOutputCapabilityRoutingSpec.scala) | 5eb368d7df046fafb4a3993711800a667675432a |

- [x] path_review approved FP/ZIO correctness and current_docs approved invariants for the eight exact identities above, with no findings. Focused model-failover-green.log passed 51 tests in 5.313 seconds. Full scalafmtAll scalafmtSbt verify passed in model-failover-verify.log with zero compiler warnings or failed tests. Current aggregate totals belong to the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).
- [x] Three grouped controlled-driver tests exercise primary HTTP retry exhaustion before target selection, repeated runtime requests, history/projection/context isolation, native/synthetic plan compatibility, excluded failures, held cleanup/cancellation and complete target failure causes. They are not live-provider interoperability or exhaustive scheduling evidence.

The existing Auto boundary regression failed against ReActAgent e9f789c2de31afa312c01e04ec1d464efbcb441f with StructuredOutputCapabilityRoutingSpec 5eb368d7df046fafb4a3993711800a667675432a: model-failover-structured-red.log recorded 12 passing tests and one failure in 408 ms. Sequential/parallel compound failures incorrectly produced a successful synthetic result with two model calls instead of the original Cause and one call. The unchanged regression passes after narrowing fallback to the exact singleton failure shape.

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

- [x] The accepted runtime baseline through `06bd2e1` includes lifecycle cleanup, finalized result ordering, occurrence-based reconciliation, correlated model/tool events, strict structured JSON, and loopback HTTP completion. Git preserves the earlier reviewer approvals and exact identities.
- [x] `review_fp` and `review_harness` approved the final five-file capability increment against `06bd2e1` on 2026-09-29 after the preset-format correction. No findings remain in this scope. Required formatting, fatal-warning compilation and verification passed for this reviewed scope.
- [ ] Complete general model-call middleware and remaining legacy hook behavior.
- [ ] Complete provider continuation and provider multimodal serialization, full event ordering, and event adapters.
- [x] Review and verify before-acting suspension/restoration.
- [x] Integrate the host-selected [complete-batch ConfirmResult adapter](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#complete-batch-checkpoint-confirmations) with restored checkpoints.
- [x] Integrate checked external partial results with an original-ordinal completion ledger.
- [x] Expose resumed result/event streams through the shared run/stream lifecycle.
- [x] Independently review and verify [partial confirmations](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#partial-checkpoint-confirmations); host persistence and broader middleware remain separate.

The [parity mapping](../../../../../../../../../../docs/architecture/test-parity.md) distinguishes verified contracts from remaining scenarios. General JSON Schema validation and exhaustive provider compatibility are not claimed.

The [client capability table](../client/README.md) records unchanged capability identities; commit `02b4654` preserves all five identities from that increment.

## Transformed live-model text

The optional [ModelTextTransform](../client/ModelTextTransform.scala) on ChatCompletionsClient replaces visible fragments before accumulation and event publication. Native structured extraction receives that same transformed text and still enforces complete unambiguous JSON. The real HTTP fence-removal scenario closes the mapped text-replacement behavior with an explicit typed adaptation; it does not establish general model-call or post-reasoning middleware parity.

- [x] `review_fp` and `review_harness` approved the final seven-file text-transformation increment against `02b4654` on 2026-09-29 after the fallback-provenance correction. No findings remain within this scope. Required formatting, fatal-warning compilation and verification passed for this reviewed scope. Exact identities are in the [client README](../client/README.md); ReAct source was unchanged by this increment.

## Before-acting suspension and restoration

The [checkpoint event integration](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#checkpoint-permission-events) is accepted: deferred occurrence records retain authorization UUIDs and prepared-input snapshots, while the original assistant remains canonical.

This contract supports ordinary before-acting checkpoints and saved-batch execution. [Supplied results](#supplied-results-for-suspended-batches) let the host report completed local occurrences; partial human confirmations extend this contract under the [reviewed extension](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#partial-checkpoint-confirmations); durable host workflows remain separate.

`ReasoningDecision.Suspend(reason)` pauses an ordinary tool-call turn after the model and hook scopes close and before inline-result processing or local execution. The returned `AgentResult` carries the complete assistant, canonical history, iteration and accumulated usage. `AgentRunState.Suspended(PendingActing)` adds data for the reason, original model context, assistant position, admitted tool definitions, group snapshot and response format; it contains no tool implementations, credentials, fibers or approval promises. Structured execution and non-tool-call suspension are rejected before the assistant is committed.

`resume()` consumes the live pending batch once under the run lock, using a fresh owned scope and timeout. It processes that saved batch before another model request or post-reasoning hook, preserving the original context and occurrence identities. After checking the saved history, the runtime changes only its ReasoningStopRequested or PermissionAsking generation tag to ToolCalls; assistant content, signatures and other metadata remain intact. Inline provider results remain inline and bypass local dispatch. Local calls retain ordinary authorization and cancellation reconciliation. Resuming the final permitted iteration may finish its saved tools but makes no further model request; the limit check precedes integer increment. A fresh run rejects pending work; `resetHistory` explicitly discards history and pending state.

`restore(result)` accepts a checkpoint only into an empty agent. It validates the saved assistant, complete preceding tool history and iteration count against the receiving host budget, then rebuilds the admitted snapshot from trusted host tool bindings and group configuration and checks exact tool definitions. Equality is a compatibility check, not authorization. The host owns checkpoint authentication, storage, exclusive claims and side-effect idempotency; restoring data does not acquire those capabilities. The harness [complete-batch ConfirmResult adapter](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#complete-batch-checkpoint-confirmations) supports explicit restored authorization; checked external results are described below; the [partial extension](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#partial-checkpoint-confirmations) has final independent acceptance. Suspended child sessions continue to reject ordinary sends; no child resume tool or session API is added.

Migration: `ReasoningDecision` gains `Suspend(reason)` and `AgentRunState.Suspended` becomes a data-bearing case. Exhaustive matches and persisted codecs must account for these shapes; dependent binaries must be rebuilt. The old payload-free Suspended wire value is incompatible: hosts must migrate valid checkpoint data, never fabricate a missing checkpoint. Existing keep/replace/discard behavior remains separately accepted below.

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for the eight identities below, with no remaining findings. Full `scalafmtAll scalafmtSbt verify` passed in `acting-resume-verify.log`, with zero compiler warnings or failures; current totals are in the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance). One checkpoint group and five runtime groups establish the bounded contracts, including actual original/restored execution and cancellation.

| File | Git blob |
| --- | --- |
| [AgentResult.scala](AgentResult.scala) | a01e888e63c94c626c918e1e3688e6d6a683d431 |
| [PostReasoningHook.scala](PostReasoningHook.scala) | 8aeaee152f7d7ecbfd0896acd0fc018200a8dc38 |
| [ReActAgent.scala](ReActAgent.scala) | [Current request-projection identity](#current-model-input-identities) |
| [PendingActing.scala](PendingActing.scala) | [Current reviewed event identity](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#checkpoint-permission-events) |
| [PendingActingSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/PendingActingSpec.scala) | [Current reviewed event identity](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#checkpoint-permission-events) |
| [ReActAgentResumeSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentResumeSpec.scala) | [Current reviewed identity](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#partial-checkpoint-confirmations) |
| [SubagentSession.scala](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/subagent/SubagentSession.scala) | a592c82bbb5720be9a9c9ca39635fb7e89990692 |
| [SubagentTools.scala](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/subagent/SubagentTools.scala) | 00499f82e283cc106944c67c4dd5fdc8ed38c298 |

Regression evidence: `acting-resume-focused.log` reproduced extraction-induced masked acting at runtime `eb8ce2db4c836637d7e65e5c255dc78ec24783a9` with spec `88e8b60711a27a8b5d6cd302218c38a8258692ad` (six passed, four timed out); restoring interruptibility before the shared acting phase passed unchanged expectations in `acting-resume-green.log`. `acting-resume-bounds-red.log` reproduced acceptance beyond the host budget and Int.MaxValue overflow at runtime `279b921866d4a8143a55a77cc3077933cfd4b253` with spec `1a5e0f8e51f1c04715bb3a41671970994830a365` (four passed, one failed): iteration became negative and one unexpected model request occurred. Host-budget validation and the pre-increment limit check passed the unchanged regression in `acting-resume-bounds-green.log`; final tables also verify thinking-only rejection and inline metadata preservation.

- [x] Integrate [supplied external results](#supplied-results-for-suspended-batches).
- [x] Complete [partial-confirmation acceptance](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#partial-checkpoint-confirmations); authenticated durable persistence remains host-owned.

## Supplied results for suspended batches

`SuppliedToolResult(callOrdinal, result)` carries a host-provided ToolExecutionResult for an original assistant tool-use occurrence. Checked `PendingActing.withResults(checkpoint, results)` augments checkpoint data without changing its history or assistant. Only distinct unresolved local ordinals and Success/Error states are accepted; inline-completed, unknown, repeated or unfinished/Denied observations and ambiguous JSON reject the whole augmentation.

`resume(results)` adds these observations to a live checkpoint; hosts can alternatively augment, serialize and restore a checkpoint before calling `resume()`. Validation occurs before consuming pending state. Resume persists every supplied raw observation and records completed original ordinals before any interruptible result processor or local execution. Runtime-owned call identity/state and protected metadata remain authoritative. Supplied occurrences bypass local authorization and dispatch; only unsupplied local calls execute normally. This represents trusted host-reported execution, not permission approval.

New inline and supplied observations pass through the existing result processor; AwaitingApproval restoration does not replay completed processing. The admitted batch still drains before selecting the earliest original-ordinal Stop; otherwise a batch containing local calls continues to the next model request even when all their results were supplied. Cancellation joins processing cleanup, preserves every persisted supplied/local result and synthesizes Error only for unfinished local ordinals. One interrupted recovery remains the owner-cancellation contract. The runtime ledger is invocation-owned and serialized for partial checkpoints; it is not a durable exactly-once service.

Migration: PendingActing appends default-empty suppliedResults, preserving old missing-field decoding; the partial extension appends a default BeforeActing stage and the event-correlation extension appends default-empty permissionRequests for a nine-field positional shape; dependent binaries must be rebuilt. No-argument resume remains available. ConfirmedBatchAuthorizer requires confirmations only for local ordinals without supplied results; partial confirmations are described in the [partial extension](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#partial-checkpoint-confirmations).

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants at the seven identities below, with no remaining findings. Focused `supplied-results-green.log` passed 46 core tests in 601 ms and four harness tests in 451 ms. Full `scalafmtAll scalafmtSbt verify` passed in `supplied-results-verify.log`, with zero compiler warnings or failures; current totals are in the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance). One pure validation group and three runtime groups cover the new contract; the existing confirmation group verifies supplied ordinals are excluded from confirmation coverage.

| File | Reviewed Git blob |
| --- | --- |
| [PendingActing.scala](PendingActing.scala) | [Current reviewed event identity](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#checkpoint-permission-events) |
| [ReActAgent.scala](ReActAgent.scala) | [Current request-projection identity](#current-model-input-identities) |
| [SuppliedToolResult.scala](SuppliedToolResult.scala) | defe9eb357ca4848b26df41dece316cfd3ec3655 |
| [SuppliedToolResultSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/SuppliedToolResultSpec.scala) | b28ee0d4a35bac5fb1f35a201bd32c249de4a405 |
| [ReActAgentSuppliedResultSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentSuppliedResultSpec.scala) | 544ba0673471232ce7fa0b45fc88e002a887b917 |
| [ConfirmedBatchAuthorizer.scala](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/ConfirmedBatchAuthorizer.scala) | [Current reviewed identity](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#partial-checkpoint-confirmations) |
| [ConfirmedBatchAuthorizerSpec.scala](../../../../../../../../../harness/src/test/scala/com/weisgnosis/agents/harness/permission/ConfirmedBatchAuthorizerSpec.scala) | [Current identity](#result-bearing-streams) |

- [x] Complete [partial-confirmation acceptance](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#partial-checkpoint-confirmations); side-effect idempotency remains host-owned.

## Post-reasoning policy and invocation usage

ReActAgent.make and Builder.postReasoningHook accept a PostReasoningHook, defaulting to Keep. The suspended callback receives ModelStreamContext and the completed model message, and returns ZIO[Scope, AgentError.ReasoningHookError, ReasoningDecision]. The accepted keep/replace/discard contract is described here; the additional Suspend(reason) path is [defined above](#before-acting-suspension-and-restoration). The dedicated error channel prevents host-policy failures from triggering Auto's provider-rejection fallback; external errors require explicit mapping.

The model request scope closes and its successful ModelResponseEnd is published before policy evaluation. The hook owns a separate nested scope inside the engine-attempt timeout and cooperative cancellation race. Its scope closes before any accepted assistant message is persisted or tools dispatch. Interrupted model output bypasses the hook and retains normal reconciliation. Existing model block events and model-end metrics describe the original observation; replacement/discard does not rewrite already published events. Committed history, final AgentResult, and dispatched tools follow the accepted decision.

Keep retains the original TurnOutput. Replace requires an assistant message, restores the original canonical usage and legacy _chat_usage metadata, then classifies its content as tool calls, thinking-only, or final text. A host cannot mint billing metrics through either usage representation. Replaced tool calls are persisted before execution. Nonempty opaque reasoning follows the same bounded thinking-only reminder as plaintext; an explicit empty detail array alone does not. Replacing calls with final text prevents dispatch. Invalid roles fail before persistence. Discard persists no model message or pending call and performs another iteration, consuming the iteration budget without replenishing the thinking retry budget. Repeated discards reach the normal iteration limit; they do not create dangling tool history.

Usage is now accumulated from returned model observations in one invocation-local Ref, using canonical-first chatUsage. It includes discarded and returned interrupted outputs, spans Auto native/synthetic attempts, excludes supplied history/new input, and resets for each public invocation. Accounting updates are briefly masked after model-scope completion. ModelResponseEnd uses the same canonical-first usage view. Synthetic completion transfers canonical and legacy usage exactly once, clearing both representations from any retained residual generating assistant. Reply usage remains the generating request's usage; AgentResult usage is the invocation aggregate.

Appending the optional make parameter preserves ordinary callers; positional Builder pattern matches must follow the current Builder constructor. The hook and builder are in-memory runtime values, not durable policy serialization. ReasoningDecision has a derived codec, but decoded replacements still pass runtime validation. This API covers replacement/removal through typed decisions; legacy mutable hooks, stop/goto-reasoning commands, and general model-call interception remain separate work.

- [x] `review_fp` and `review_harness` approved the final six-file post-reasoning increment against `2a400e0` on 2026-09-29 after the legacy-usage correction. No findings remain in this scope. Required formatting, fatal-warning compilation and verification passed for this reviewed scope.

## Current post-reasoning identities

Baseline post-reasoning identities from the scope against `2a400e0`; commit `8850a60` preserves its full scope. The current hook and runtime identities belong to [before-acting suspension](#before-acting-suspension-and-restoration); unchanged error contracts remain separate.

| File | Git blob |
| --- | --- |
| [PostReasoningHook.scala](PostReasoningHook.scala) | [Current identity](#before-acting-suspension-and-restoration) |
| [StructuredOutput.scala](StructuredOutput.scala) | 5d6d81c944530f8ff7094733abfc21885729e456 |
| [PostReasoningHookSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/PostReasoningHookSpec.scala) | 0a024cbdaf20b5a7d3be015b30a60c07118310ab |
| [ReActAgentReasoningHookSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentReasoningHookSpec.scala) | [Current identity](../client/README.md#encrypted-reasoning) |

## Local-result processing boundary

ReActAgent.make and Builder.toolResultProcessor accept a ToolResultProcessor, defaulting to identity. Its legacy suspended process callback returns ZIO[Scope, AgentError, ToolResultPayload]; runtime processing uses processDecision, whose default invokes that callback once and returns Continue. The explicit [acting-stop decision](#acting-stop-decisions) adds host-only termination authority. The payload contains output blocks and metadata only. applyTo preserves the original call ID, name, state, and serverTool/agentscope_server_tool/agentscope_suspended control keys. Composition applies each payload safely before passing the observation onward; host processors cannot change execution authority through these fields.

After local execution finishes, the runtime atomically appends the complete raw observation and captures its history position. It then runs processing interruptibly in a separate scope. Typed processing failures restore the original payload; defects propagate. Scope cleanup finishes before a short masked section replaces that exact observation and publishes End, plus aggregate text/data deltas only when that execution emitted no nonempty progress. Parallel calls own distinct append positions, including calls that reuse IDs; completion may arrive in a different order. Processing assumes history remains append-only during the acting phase under the run lock.

Cancellation during processing keeps the completed raw result and reconciles only unfinished executions. It does not rerun the tool or publish a false ToolResultEnd. A subsequent model request sees the committed processed payload; observers may see the raw result while processing is pending. Processor side effects already performed before failure/cancellation are not rolled back. Callbacks must not reenter a mutating agent operation that requires the held run lock.

Tool execution can return completed typed payloads. A processor can replace their output blocks and metadata for the existing typed result publisher; incremental local execution has the separate contract above. Empty processed output emits only End after the original Start. Matched inline provider results also use this processor. Supplied history uses the separate opt-in ToolResultEvictor.preparer adapter.

Appending the optional make parameter preserves ordinary callers; positional Builder pattern matches must follow the current Builder constructor. Processor values are runtime dependencies, not serialized agent configuration.

- [x] `review_fp` and `review_harness` approved the final eight-file local-result processing/eviction increment against `8850a60` on 2026-09-29. No findings remain in this scope. Required formatting, fatal-warning compilation and verification passed for this reviewed scope. Exact current identities are in the [tool package table](../tool/README.md). The post-reasoning ReActAgent identity is preserved in `8850a60`.

## Authorization before local execution

The optional ToolAuthorizer receives [transient admitted access](../tool/README.md#admitted-tool-access), sampled from the resolved Tool.isReadOnly accessor in the same admitted Toolkit inside the suspended decision boundary. Missing tools provide None; model input cannot supply this metadata. Legacy wrappers must override the additive overload to forward it. The harness SubagentFactory wrapper forwards it through inherited denial checks; the harness explicitly installs narrowed child skill catalogues and immutable group snapshots. It runs after assistant persistence and fixed-argument preparation, before each local tool dispatch, with a fresh request identity and originating model context. Approved input is validated and committed to its exact call occurrence; changed inputs are denied when either reserved reasoning key occurs on that call or any Thinking block in its originating assistant message. A changed fixed-argument candidate is rejected before the authorizer runs. Non-interrupted failures and defects fail closed to Denied observations. Interrupted compound causes preserve interruption and cleanup defects; cancellation preserves completed work and reconciles unresolved calls. Provider-hosted calls bypass the local gate whether or not an inline result is present. See the [tool contracts](../tool/README.md) for argument provenance, scope ownership, failure semantics, migration, and exact current identities. The default allows calls; the harness InteractivePermissionAuthorizer provides opt-in policy/interactive confirmation. Complete-batch restored confirmation is available through the harness adapter; host durable persistence remains a host responsibility. Partial approvals have their own checkpoint contract below.

- [x] `path_review` and `current_docs` approved fixed-argument authorization and compound-cause preservation with no findings; full verification passed. The [scoped fixed-argument acceptance](../tool/README.md#fixed-raw-arguments) records exact identities and evidence. Durable policy adapters remain separate.

## History preparation before model requests

ReActAgent.make and Builder.historyPreparer accept HistoryPreparer, defaulting to identity. prepare returns ZIO[Scope, AgentError.HistoryPreparationError, Chunk[Msg]]. It runs before every model request, including thinking retries, discarded reasoning, and both Auto attempts. Preparation occurs only after prior authorization, tool execution, and result processing finish. Its scope closes before validation, canonical history commit, and ModelRequestStart. It shares the engine-attempt timeout and cooperative cancellation race; callbacks must not reenter mutating agent operations while the run lock is held.

An equal candidate preserves the existing permissive supplied-history API without adding global validation. An actual rewrite must preserve the exact ordered system-message subsequence and contain complete occurrence-aware tool pairing, with no unmatched inline/local results or unfinished local calls; unresolved provider-owned calls are not local obligations. The internal shared ToolHistory analysis isolates provider inline results to their own assistant message and matches local observations by ID and optional name. A changed incomplete history fails with HistoryPreparationError before state commit or model dispatch. A trusted rewriter may remove old incomplete context; structural validation cannot prove summary fidelity or semantic preservation. It is not a provider-specific request validator.

The harness ConversationCompactor.preparer installs compaction through this port and maps summarizer/hook errors to the distinct preparation error. Such errors cannot masquerade as native-format ModelError rejection and trigger Auto fallback. Typed failure leaves canonical input history unchanged. Defects propagate; cancellation closes preparation resources and appends the current attempt's recovery. External flush/offload effects already completed cannot be rolled back.

Recovery ownership is attempt-local: Ready before preparation, PersistedAssistant(position, completedOrdinalPositions) after accepted assistant or interrupted partial output, and Recovered(message) after reconciliation. Persisting each raw local or supplied observation and marking its original ordinal share a narrow interruption mask. Cancellation and failed-phase reconciliation use this ledger rather than reconstructing completion from result order or repeated IDs. The persisted position is valid only during the append-only active tool phase and is cleared before another rewrite. Nested cancellation finalizers reuse the exact recovery value; a later run, including empty-input continuation, owns a fresh recovery. Unaccepted hook output and supplied historical calls never become the current reconciliation target. Invocation usage remains independent of rewritten history and shared across Auto attempts.

Appending the optional make argument preserves ordinary callers; positional Builder matches must follow the current Builder constructor, including historyPreparer. Adding HistoryPreparationError requires exhaustive AgentError matches to handle the new leaf. Preparer values are runtime dependencies, not serialized configuration. Core remains independent of harness implementations.

- [x] `review_fp` and `review_harness` approved the final nine-file history-preparation increment against `3c6958f` on 2026-09-29. No findings remain in this scope. Required formatting, fatal-warning compilation and verification passed for this reviewed scope. Acceptance covers structural rewrite validation and scoped compactor integration; semantic summary fidelity and exhaustive provider-format validation are excluded.

## Current history-preparation identities

Unchanged identities from the scope against `3c6958f`; commit `2b99226` preserves the full nine-file scope. The current ReActAgent identity is recorded under [result-bearing streams](#result-bearing-streams); unchanged components follow:

| File | Git blob |
| --- | --- |
| [ToolHistory.scala](../model/ToolHistory.scala) | [Current provider-ownership identity](#provider-owned-pending-calls) |
| [ReActAgentHistoryPreparerSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentHistoryPreparerSpec.scala) | 9f98df0f18d26e42766f1a41cdfe8c441b33c329 |
| [ToolHistorySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/model/ToolHistorySpec.scala) | [Current provider-ownership identity](#provider-owned-pending-calls) |

## Inline processing and failed acting phases

Matched inline provider observations now pass through the optional ToolResultProcessor after the raw assistant message is persisted. Processing owns an interruptible scope; cleanup finishes before the exact assistant/result occurrence is rewritten and Start/ordered text-data deltas/End are published. Duplicate IDs and identical result values retain independent ordinals. Processor payloads preserve runtime-owned identity, execution state, and control metadata. Typed failures retain raw content; partial processing cancellation retains already committed siblings and raw unprocessed inline observations. Provider completion never becomes a local Tool message, and final inline-only replies use the canonical processed assistant.

A bounded acting-phase finalizer runs after failed processors and parallel siblings close. For a non-interruption-only failure, it appends Error observations only for unrecorded local calls and preserves the original cause. It publishes neither synthetic completion events nor a user-interruption recovery. Pure cancellation continues to reconcile unfinished local calls and append one recovery message. Work may have performed external effects before aborting; an Error observation does not claim otherwise.

Model, hook, history-preparation, and acting races now explicitly own a scoped worker. Only worker completion waiting races the cooperative signal. Both cooperative and external cancellation interrupt and await the worker, retaining non-interruption failures from resource cleanup. Successful completion inherits worker fiber references before returning its exact exit. This closes the previously reproduced loss of processor-finalizer defects during cancellation.

Installing a non-identity processor now includes matched inline observations; the identity default preserves payload behavior. Supplied-history eviction is available through an opt-in preparer; exhaustive upstream middleware parity remains pending.

- [x] `review_fp` and `review_harness` approved the final inline-processing and cancellation-cleanup scope against `2b99226` on 2026-09-29. Required checks and regressions passed for this reviewed scope. No findings remain in this scope. Supplied-history eviction is covered by a later scope; exhaustive upstream middleware parity remains pending.

## Current inline-processing identities

Unchanged identities from the seven-file scope against `2b99226`; commit `cc8fa7b` preserves its complete source identities. Changed eviction identities belong to the eviction table linked below:

| File | Git blob |
| --- | --- |
| [Msg.scala](../model/Msg.scala) | d8c616052015b59c18b69cd52bfa66840edfcae6 |
| [ToolResultProcessor.scala](../tool/ToolResultProcessor.scala) | [Current identity](#acting-stop-decisions) |
| [ReActAgentInlineProcessingSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentInlineProcessingSpec.scala) | [Current identity](#acting-stop-decisions) |
| [ReActAgentToolResultProcessorSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentToolResultProcessorSpec.scala) | 9c76a00f1c3309520ed0823ff91e8c6ac49b3be8 |

Superseded history/eviction source identities remain in `cc8fa7b`; changed identities are in the [eviction table](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/eviction/README.md).

- [x] `review_fp` and `review_harness` approved the final nine-file history-eviction scope against `cc8fa7b` on 2026-09-29. Required checks and regressions passed for this reviewed scope. Two new regressions exposed and closed strict-number and Windows storage-layout gaps. No findings remain within store-backed integrity, explicit workspace trust, and validated composition; absolute storage is covered by a later scope; request-only input views are covered by a later scope. See the [eviction contract/table](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/eviction/README.md).

HistoryPreparer.andThen runs stages in order within the caller's request scope. Each changed intermediate and final output is structurally validated before proceeding, so later stages cannot conceal an invalid prior rewrite. Failure/cancellation skips remaining stages and releases earlier resources. Only the complete prepared history is committed by ReAct; external stage side effects are not transactional. The eviction adapter can precede compaction to save raw payloads before pruning.

Superseded model-input scope identities remain in `40f6f05`; changed identities are in the [agent model-input table](README.md).

## Request-only model history

ModelInputTransform is distinct from HistoryPreparer: it returns a transient request view without canonical write authority. Existing apply(context, history) implementations remain valid; default projectRequest invokes that legacy callback once and retains the selected target's settings. ModelRequestInput contains prepared history, the selected model name, admitted tool definitions and ModelRequestSettings(temperature, maxTokens, topP). It contains no client, credentials, endpoint or capability declarations. ModelInputProjection returns only history and settings; it cannot replace tools, the model, client or response format.

ModelInputTransform.withRequest(base)(project) adds selected-request behavior to an explicit base. Standalone apply still delegates only to the legacy base. projectRequest runs the base and added callback once, validates each intermediate history/settings before the next stage, and shares the caller's scope. Runtime execution closes the projection scope before final validation, request events and client dispatch. Temperature must be finite in [0, 2]; maxTokens must be absent or positive; optional topP must be finite in [0, 1]. Projected topP=None clears the selected target value; [nucleus-sampling acceptance](../client/README.md#optional-nucleus-sampling) owns current identities and arity migration. Existing HistoryRewrite rules preserve exact ordered system messages and complete occurrence-aware call/result pairs. Structural validation is not semantic fidelity or full provider grammar validation.

Every actual selected model attempt starts from prepared canonical history and its own configured settings, with a fresh request context. This includes explicit model failover and native/synthetic attempts. The admitted tool snapshot and active response format remain unchanged. Request-only skill projection follows validation. Built-in HTTP status retries reuse the serialized request and do not rerun projection. Projected history/settings never replace canonical history, configuration, recovery positions, usage or tool authority. Newly injected content is not automatically evicted.

Typed callback failures and invalid output use ModelInputError and cannot trigger model failover or native-format fallback. Defects and complete cancellation/cleanup causes propagate; scoped cleanup is joined. Completed canonical preparation is not rolled back. Callbacks must not reenter operations needing the held run lock.

Migration: the default projectRequest method preserves existing ModelInputTransform implementations and ordinary factory calls. withRequest requires an explicit base so existing Plan/other history behavior is retained intentionally. New request/projection values are transient immutable data without codecs. Dependent binaries should be rebuilt. Validation now also rejects invalid original temperature/maxTokens/topP with an identity or legacy transform, including custom clients, before request events or client invocation. This is a deliberate compatibility tightening, not a general middleware/interception framework.

## Current model-input identities

| File | Git blob |
| --- | --- |
| [ModelInputTransform.scala](ModelInputTransform.scala) | [Current topP scope identity](../client/README.md#optional-nucleus-sampling) |
| [ReActAgent.scala](ReActAgent.scala) | [Current runtime identity](#host-selected-return-direct-completion) |
| [ModelInputTransformSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ModelInputTransformSpec.scala) | [Current topP scope identity](../client/README.md#optional-nucleus-sampling) |
| [ReActAgentModelInputSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentModelInputSpec.scala) | [Current topP scope identity](../client/README.md#optional-nucleus-sampling) |
| [ModelFailoverSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ModelFailoverSpec.scala) | [Current topP scope identity](../client/README.md#optional-nucleus-sampling) |

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for the five identities above, with no findings. Final focused checks passed 18 tests in 807 ms (`request-projection-final-focused.log`). Full `scalafmtAll scalafmtSbt verify` passed in `request-projection-final-verify.log` with zero compiler warnings or failures; current totals are in the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance). Verification used process-local `JAVA_TOOL_OPTIONS=-Xms64m -Xmx512m -Xss1m -XX:ActiveProcessorCount=2` and `sbt --mem 512`; repository defaults and production behavior were not changed for the host resource limit.

Two new unit groups cover lazy legacy composition and checked intermediate history/settings. Existing runtime tables cover invalid projection/configuration before dispatch, joined cancellation and complete cleanup causes. The failover suite uses the real completion adapter with controlled HTTP drivers to inspect serialized per-target history/settings, retry reuse, unchanged schemas/tool definitions and fixed-argument execution. It adds no new real socket server or live-provider evidence. General interception, changed tool authority and mutable Java hook APIs are outside this scope.

Unchanged HistoryPreparer (088d1464febe6be050ce43856305a0efe3b7f0e5) and ToolResultEvictorHistorySpec (3070eb8d3caf57c99243fb7675b5fbbe8d7f25e8) retain their separately reviewed history/eviction contracts. Current compactor and memory error identities belong to the [memory contract](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md).

## Required turn completion observation

[TurnCompletionObserver](TurnCompletionObserver.scala) is an optional runtime dependency of ReActAgent.make and Builder.completionObserver; the default ignore preserves existing behavior. TurnCompletion contains agentId, the terminal canonical history, and the original execution Exit. It is runtime data with full Cause information, not a serialized callback or wire event. The snapshot is read-only and cannot replace canonical history.

One admitted invocation owns one observation attempt, including both attempts of structured Auto fallback. Calls cancelled while waiting for the run lock do not observe. Success is observed after request/tool cleanup, structured preparation, and canonical history commit, before AgentResultEvent. A final execution failure, defect, timeout, or external cancellation is observed after execution scope closure and reconciliation. Cooperative interruption may produce a successful Exit containing an interrupted AgentResult. Attempt-level AgentEnd can precede failure observation; it is not a persistence acknowledgement.

Observation runs in its own scope under the held run lock and cancellation mask. Resources close before successful result publication or completion. A slow observer delays cancellation; callbacks must not await this same agent's run, runStructured, streams, or resetHistory, including through another fiber. The model/tool timeout does not bound mandatory observation. No detached background work or automatic retry is introduced.

AgentResultEvent means committed execution output, not caller delivery acknowledgement. Cancellation arriving during successful observation can coexist with subsequent result publication while the caller ultimately exits interrupted. The observer sees the original successful execution outcome; it does not receive a second callback for late cancellation or its own failure. An observation failure suppresses AgentResultEvent. The dedicated CompletionObservationError cannot trigger native-format fallback; original execution and observation failure causes are preserved in sequential order, including interruption and cleanup defects.

Direct calls and fully consumed streams expose terminal observer failures. A consumer that stops a stream early still waits for producer cleanup and mandatory observation, but no longer consumes the queue carrying later terminal errors. Such a consumer cannot infer successful persistence from its own successful take/runDrain. No separate durable acknowledgement capability is provided.

Migration: the optional constructor/builder dependency is additive, but positional Builder matches must follow the current shape. Exhaustive AgentError matches must include CompletionObservationError. Hosts explicitly install the transcript adapter; the runtime does not assign missing Msg identities. See the [transcript contract](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/transcript/README.md).

- [x] `review_fp` and `review_harness` approved the final eight-file completion-observer increment against `0349b1a` on 2026-09-29. Required `sbt scalafmtAll scalafmtSbt verify` passed with zero compiler warnings for this reviewed scope. No findings remain within scoped once-per-invocation observation, canonical snapshots, cause preservation, explicit transcript identity policy, and local persistence integration. Background memory scheduling, remote persistence, delivery acknowledgement, and exhaustive upstream middleware parity remain outside this scope.

The current ReActAgent identity is recorded under [result-bearing streams](#result-bearing-streams); typed-result acceptance remains in the [tool contract](../tool/README.md#current-review-acceptance). Completion-observer behavior retains its separately reviewed scope.

## Current completion-observer identities

Four unchanged identities from the eight-file scope against `0349b1a`; changed observer identities are recorded below, with their previous versions preserved in `3e817e3`:

| File | Git blob |
| --- | --- |
| [ReActAgentCompletionInterruptSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentCompletionInterruptSpec.scala) | c26004f7c289c66638e725857bc2225acb7e1a03 |
| [ReActAgentCompletionSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentCompletionSpec.scala) | 2b6f5f8edad11ad0023c15c5905d9d69136e908f |
| [SessionTranscriptWriter.scala](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/transcript/SessionTranscriptWriter.scala) | da27d3446e30ee7f77f4eaaac2d424200ac17383 |
| [TranscriptCompletionSpec.scala](../../../../../../../../../harness/src/test/scala/com/weisgnosis/agents/harness/transcript/TranscriptCompletionSpec.scala) | 5a6a1a89ff46ae70df874db6b7826154a84065b5 |

- [x] `review_docs` approved completion-observer documentation against `0349b1a`, including exact identities, late cancellation, early-stream error visibility, explicit anonymous policy, compactor offload and remaining lifecycle work. Required verification, local Markdown links, script checks and git diff --check passed for this reviewed scope.

## Completion observer composition

TurnCompletionObserver.andThen lazily composes two observations in separate scopes. The first scope closes before the second starts; typed failure, defects and interruption skip the successor and preserve their causes. Composition remains subject to the run-lock and completion-observation cancellation rules above. Composing background scheduler observers orders admission, not task dependencies after queue coalescing; see the [memory scheduling contract](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md).

Previous TurnCompletionObserver.scala and TurnCompletionObserverSpec.scala identities are preserved in `3e817e3`. Their current identities belong to the consolidation/composition increment below.

## Current consolidation/composition identities

Final formatted increment against `3e817e3`; independent acceptance is recorded below.

| Source | Git blob |
| --- | --- |
| [TurnCompletionObserver.scala](TurnCompletionObserver.scala) | 8ac76932121b863c3de7a461452baea3c1bd3489 |
| [TurnCompletionObserverSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/TurnCompletionObserverSpec.scala) | 8a0a1031e7e8abf00c529a6bfe1b2b62d53dd0d1 |

- [x] `review_fp` and `review_harness` approved the final seven-file consolidation/composition increment against `3e817e3` on 2026-09-29 after correcting nested test-scope ownership. Required `sbt scalafmtAll scalafmtSbt verify` passed with zero compiler warnings for this reviewed scope. No findings remain within separately scoped observer composition, owned consolidation/result reporting, eight local throttle integration combinations, FIFO replacement and post-commit cancellation/failure. Retention, remote authority, strict cross-job dependencies and transactional reporting are excluded.

- [x] `review_docs` approved the corrected consolidation/composition documentation against `3e817e3`: exact identities, scoped composition, reporting limits, FIFO ordering and conservative parity classifications. Required verification, local Markdown links, script checks and git diff --check passed for this reviewed scope.

Current AgentError identity is recorded in [acting-stop decisions](#acting-stop-decisions); lineage errors retain their scoped [subagent contract](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#current-lineage-identities).

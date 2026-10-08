# Build definition support

Use JDK 21 and the sbt version pinned in [build.properties](build.properties). [plugins.sbt](plugins.sbt) declares scalafmt; [build.sbt](../build.sbt) defines modules, dependencies and fatal compiler warnings. Run `sbt scalafmtAll scalafmtSbt` followed by `sbt verify` for source changes. Add packageArtifacts and fresh consumer checks for public API, dependency, build, integration or release changes.

Core uses NetworkNT json-schema-validator 2.0.4 behind a suspended private adapter; public schemas remain zio-json ASTs. Harness uses SnakeYAML Engine 3.1.1 for bounded declaration events without object loading. MCP uses zio-process 0.7.2; its adapter supplies the resource ownership missing from the dependency launcher. PostgreSQL production code borrows a host DataSource and does not bundle a driver or pool.

MCP tests fork a JVM to start a peer with its actual test classpath. Java MCP SDK 0.17.2, Tomcat 11.0.18 and logging fixtures remain Test-only; the test JVM opens the JDK packages required for Tomcat cleanup. storagePostgresql reuses harness test fixtures through its test configuration without exporting them in production artifacts.

## Phase, advice and recovery compatibility

The optional [PhaseObserver](../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#scoped-phase-observation) adds a defaulted factory/builder dependency. Recompile JVM consumers and update builder product patterns; no serialized callback or result-replacement capability is introduced. Subscription filtering and immutable host result projection remain separate presentation operations.

[Permission advice](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#permission-rule-advice) is additive and opt-in; the original interactive callback remains advice-free. Advice has no policy authority. Custom daily stores inherit an unsupported `days` operation until implemented, and custom transcript catalogs inherit unsupported retirement; neither silently fabricates empty state.

Transcript catalogs strictly read version 1 and publish version 2 with retained retired bindings. Older readers cannot consume the new schema; coordinate upgrades and host-owned backups. [Local handoff](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/transcript/README.md#local-and-remote-handoff) preserves legacy version-1 linear rows until branch recovery requires a version-2 document. Current ordinary local writers retain recovered branches while extending the selected continuation; older binaries fail closed on recovery documents. No downgrade, remote write, executable checkpoint conversion or distributed writer fence is supplied.

## Local artifact packaging

`packageArtifacts` creates binary JARs, source JARs and Maven POMs for core, harness, MCP and storage-postgresql under each module's target/scala-3.9.0. Each archive includes one META-INF/LICENSE. The root aggregate, examples and external consumer remain unpublished. Examples compile against core/harness/MCP and have their own test dependencies; they do not inherit the optional storage module or Java server fixtures.

Default coordinates are `com.weisgnosis:weisgnosis-agents-{core,harness,mcp,storage-postgresql}_3:0.1.0-SNAPSHOT`. The checked `weisgnosis.candidate.version` property selects a canonical RC version for unsigned preparation. Harness/MCP depend on packaged core; storage-postgresql depends on packaged harness. No SCM URL is invented while no repository remote is configured. Packaging neither installs nor publishes artifacts.

[CI](../.github/workflows/ci.yml) verifies and packages, then [archive inspection and the external consumer](../verification/artifact-consumer/README.md) check licenses, coordinates, dependency scopes, source admission, selected public APIs and the runtime classpath. Actual PostgreSQL requires its disposable fixture. Local success does not establish remote workflow execution, signing or publication; the [completion checklist](../docs/architecture/tracker.md#remaining-library-completion-work) records those remaining outcomes.

## Compatibility policy

InvocationPreparer adds a trailing defaulted make argument and Builder setting/product field. Existing
source construction keeps the identity default; recompile JVM consumers and update Builder product
patterns. Preparation changes canonical prior history/input once per fresh invocation; restore and
resume bypass it. See [preparation ownership](../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#fresh-invocation-preparation).

All four libraries use development SNAPSHOT coordinates unless the host explicitly selects an unsigned RC version. There is no frozen public ABI or previously published compatibility baseline. Defaulted arguments can preserve ordinary Scala calls while changing JVM signatures, constructor method values, generated copy/unapply methods and Product/extractor arity. Rebuild dependent binaries and check positional patterns. Public JSON migration and persisted storage migration are separate from recompiling source.

Package documentation owns detailed migrations. The following notes identify changes that consumers must account for; they do not establish exhaustive compatibility coverage or release readiness.

### Core APIs and request settings

[Prepared invocations](../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#invocation-identity-and-prepared-execution) expose status, replayable termination, typed cancel/interrupt and one-shot streamWithResult while retaining prepareRun/context/run source signatures. Returned means invocation return, including a suspended or cooperatively interrupted AgentResult. Cancellation receipts preserve full Exit causes. Execution callbacks must not await their own termination; self-cancel is rejected before mutation. Execution termination does not prove stream delivery has drained.

InvocationContext adds observational UUID correlation. ModelStreamContext and TurnCompletion append optional invocation fields; legacy JSON remains readable, but product patterns and JVM signatures change. Custom HistoryPreparer wrappers must forward its contextual overload. Saved confirmation authority remains exact replyId/requestId and original occurrence/call guards, independently of the fresh invocation identity.

[ChatUsage arithmetic](../modules/core/src/main/scala/com/weisgnosis/agents/core/model/README.md#checked-usage-arithmetic) changes totalTokens/combine to checked Either and adds validated without changing wire fields. [Cache usage decoding](../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#cache-usage-decoding) remains distinct from token-estimate arithmetic.

The [request settings migration](../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#optional-thinking-budget) describes sixteen-field LlmConfig and nine-field ModelRequestSettings. Checked [stop sequences](../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#optional-stop-sequences), [reasoning effort](../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#optional-reasoning-effort) and thinking budget remain optional endpoint preferences. Defaulted additions preserve omitted-argument calls and documented legacy JSON, not positional or binary compatibility.

[ToolDef provider strictness](../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#provider-tool-schema-strictness) has eight fields. [Typed tool execution observations](../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#typed-execution-observations) preserve String construction conveniences while changing payload/state JSON and generated extractors. [Checked Toolkit construction](../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#checked-registry-identity) removes unchecked Map/copy/Product reconstruction and retains checked name-based registration.

[External bindings](../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#external-tool-bindings) change Toolkit.tools/resolve to ToolBinding and extend PendingActing with external fields and AwaitingResults. Migrate handler-access callers, checkpoint patterns and exhaustive stage matches. Existing local registration conveniences remain. ToolAuthorizer's default preflight extension screens monotonic denials; it grants no approval, and dynamic gates still run at execution time. Custom forwarding wrappers must preserve the extension where required.

[Envelope profiles](../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#completion-envelope-profiles) append defaulted client/transport parameters; decoder implementation types remain restricted. Exhaustive ReasoningReplay matches must handle [OpaqueSignatureDetails](../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#opaque-signature-details). The checked [summary text alias](../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#summary-text-alias) coalesces equal dual strings and rejects malformed/conflicting fields without changing literal replay. [Ordinary JSON helpers](../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#ordinary-json-completions) retain existing public factory/encoder signatures.

[Tool-image projection](../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#tool-image-result-projection) adds defaulted encoder/client/transport parameters without changing LlmConfig/settings arity. [User video URL projection](../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#user-video-url-projection) retains request API signatures/defaults and requires a compatible endpoint; serialization supplies no fetching or MIME inference. The restricted [interrupted progress capability](../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#interrupted-transformed-progress) is not implicitly forwarded by custom clients or public wrappers. Only validated request-local progress can be accepted after interruption-only cleanup; compound failures remain failures.

### Harness and MCP APIs

[Wait APIs](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#local-task-wait-barriers) add wait_async_results and typed inbox/no-work/budget outcomes. Update tool allowlists and exhaustive matches. Scoped construction explicitly installs a borrowed inbox; pure factories remain barrier-only.

[Subagent milestones](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#subagent-milestones) add trailing defaulted registry/session lifecycle parameters and named-owner emission selection. Omitted arguments preserve disabled behavior, not JVM method signatures; rebuild consumers and method-value users. Exhaustive `SubagentTaskRemoval` matches must handle `MilestonesPending`. Existing event JSON and durable schemas are unchanged; host-selected correlation is runtime metadata, not restored authority or a delivery receipt.

[Completion notifications](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#completion-notifications) are host-only policy/receipt ports with Suppress defaults. Model data cannot select policy. Custom registries fail explicitly when unsupported; update binding/product patterns and exhaustive removal matches, including Unnotified. Callbacks and Cause-bearing receipts have no durable codec or reopen replay. Inspect typed failures through receipt/flush; legacy UIO closure joins cleanup without surfacing those typed failures.

[Host-driven recovery](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#host-driven-task-recovery) requires checked policy, a scheduling-domain gate and scoped authority. Custom implementations inherit typed unsupported defaults; no daemon, fence or execution claim is created.

[Declaration eligibility](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#declaration-eligibility-and-visibility) adds mode/visibility with All/Visible defaults. SubagentDeclaration has eleven fields and SubagentEntry has six. Carry parsed policy through SubagentEntry.fromDeclaration; unrelated raw host factories do not inherit it.

[Segmented transcript compaction](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/transcript/README.md#immutable-transcript-segments) narrows make's result to SegmentedTranscriptStore, which remains assignable to TranscriptStore. Schema 1 and exact stored entries are unchanged; old segments remain undeleted. Recompile for the new public type/method.

[Local JSON snapshots and task discovery](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#local-task-workspace) retain explicit path/address binding and borrowed ownership. Canonical path screening is not an atomic hostile-filesystem sandbox; the resource checkpoint preserves external interrupter IDs without supplying lease takeover.

[McpClient.Deadlines](../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/client/README.md#independent-deadlines) adds checked independent deadlines while retaining the original Duration overload and default. [McpMetadata.merge](../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/tool/README.md#ordered-metadata-merge) retains binding/resolver signatures and omission defaults; explicit composition installs no ambient context, authentication or cache.

## First RC API candidate

| Artifact | Candidate host contracts | Internal boundary |
| --- | --- | --- |
| [core](../modules/core/README.md) | Documented model, error, event, tool, skill, client and agent APIs, including opt-in ExactJson | Restricted JSON validators, model-attempt rejection markers and runtime implementation state |
| [harness](../modules/harness/README.md) | Documented eviction, memory, permission, plan, skill, subagent, transcript and versioned-storage APIs | WorkspaceLease and restricted store/supervisor machinery |
| [mcp](../modules/mcp/README.md) | Protocol, client, tool and transport APIs, including intentional low-level extension ports | Restricted connection/manager/transport implementation state |
| [storage-postgresql](../modules/storage-postgresql/README.md) | Checked host-DataSource construction and effectful initialize/read/compareAndSet | Fixed SQL/resource machinery; driver, pool and schema deployment remain host-owned |

Public low-level ports retain their documented borrowed-resource, ordering, cancellation and reentrancy obligations. Public data constructors do not replace checked wire conversion. Local stores, approval adapters and retained sessions keep their explicit trust/lifetime limits. Published names do not imply frozen signatures.

Local checks and selected packaged API probes establish neither exhaustive compatibility nor remote CI success. A release baseline requires identified tagged sources, published coordinates and an explicit compatibility policy. Signing/publication and remaining library outcomes belong to the [tracker](../docs/architecture/tracker.md); unsigned RC preparation is not that baseline.

## Remote storage API and artifact boundary

storage-postgresql is a separately consumable library with no production JDBC driver or pool. CI supplies PostgreSQL 16 for integration tests. Hosts own DataSource lifetime and schema deployment; initialize is not cross-process provisioning coordination. [Sanitized diagnostics](../modules/storage-postgresql/src/main/scala/com/weisgnosis/agents/storage/postgresql/README.md#sanitized-diagnostics) classify bounded StorageError messages without changing public error cases or retry behavior.

The host surface includes checked VersionedJsonStore data and CAS, VersionedMemoryRepository, aggregate and segmented transcripts, transcript sessions/layouts/registration/catalogs, VersionedMemoryTriggerGate and versioned acting checkpoints. Shared transitions and JSON helpers remain restricted. See [storage contracts](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/storage/README.md#versioned-acting-checkpoints) and [persistent gate lifetime](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md#versioned-memory-trigger-gate). Explicit catalogs use known addresses; they do not automatically install writers or provide global enumeration.

The external consumer checks selected APIs and fresh packaged modules; it does not construct every domain adapter. Actual PostgreSQL/runtime tests provide separate integration evidence. Per-address JSON publication does not establish durable workflow ownership, checkpoint authentication, distributed fencing or exactly-once execution.

## Durable registry compatibility

[Terminal observations](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#durable-terminal-observations) are text/data, not executable Exit values or resumable checkpoints. Ordinary live factories/custom defaults do not persist them automatically. [openDurable](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#registry-terminal-publication) installs owned publication and typed storage/publication observations. Backend ownership stays with the host. Update exhaustive removal matches for Unpublished and notification-related Unnotified; failures must not be bypassed by forgetting retained work.

[Stored views](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#stored-task-views) add SubagentStoredTaskState, Stored observation/model cases, ListedTaskStatus.Pending and task-output NotLocallyWaitable. Stored nonterminal data creates no local wait handle. Custom stores retain terminal-only behavior unless taskRecordSnapshot explicitly returns Some; supported snapshot errors propagate.

[Cancellation](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#durable-cancellation-intent) distinguishes persisted intent from joined completion. Stored-only requests create no execution ownership. Custom stores must implement persistence and define atomic start semantics; inherited defaults do not establish those guarantees. Legacy UIO cancel remains runtime-only. Update TaskRecord constructors/product patterns and exhaustive model-state matches.

[Typed failures](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#typed-task-failure-details) replace Failed/Interrupted strings with SubagentTaskFailure(message,cause). Update constructors, patterns and standalone outcome/task_output JSON readers. Legacy stored strings normalize without invented causes; storage compatibility does not make old public JSON decoders accept new failure objects.

## Durable acknowledgement compatibility

The built-in store now writes [schema 8 with execution origin and remote approval state](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#persisted-execution-origin). Dedicated readers validate schemas 1–7 in their original representation before normalization, without rewriting or inventing dates/causes. Legacy schema 1 remains unacknowledged; schema 2 retains acknowledgement stamps; schema 3 retains worker identity; schema 4 retains admission metadata; schema 5 retains typed failures; schema 6 retains cancellation intent. Schema 7 retains execution origin. Legacy records normalize absent remoteApproval to NotAwaiting only after original-wire validation; absent origin normalizes to Local for schemas 1–6. SubagentTaskRecord adds a seventh field; rebuild dependents and update positional patterns. Older readers reject newer schemas: coordinate upgrades and host-owned backups. No downgrade or backup service is provided.

[Acknowledgement](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#durable-terminal-acknowledgement) records host acknowledgement time, not task completion or proof of delivery. Custom store/registry ports default to typed unsupported failures. [Worker identity](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#worker-identity) is explicit optional host data, not inferred from a name or UUID. The earlier identity/origin additions changed constructor/extractor/JVM shapes: admission identity from three to four fields, TaskRecord from five to six, and TerminalRecord from three to four. The current remote approval addition changes TaskRecord from six to seven fields. Defaults preserve ordinary source calls, not binary compatibility.

## Explicit delivery compatibility

[SubagentTerminalDelivery](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#explicit-terminal-batch-delivery) is a caller-owned IO effect and receipt API. It owns callback child scopes without requiring a redundant ambient Scope, and acknowledges only after successful joined callback cleanup. It installs no middleware or background delivery. Uncertain acknowledgement failures propagate; earlier acknowledgements are not rolled back.

## Accepted-response observer compatibility

[ModelResponseObserver](../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#accepted-model-response-observation) and the required [local-tool observer](../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#persisted-local-tool-observation) append defaulted make arguments and builder settings. Rebuild JVM consumers and update Builder product/extractor patterns. Accepted-response snapshots are runtime data, not serialized checkpoints. The terminal observer remains separate; required observer failure can occur after persisted work and does not authorize blind replay.

## Reasoning reminder and wait compatibility

[SubagentDeliveryMiddleware](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#automatic-reasoning-reminders) replaces four builder hook slots using explicit prior hooks, including the required local-tool-result observer. Keep its scope alive through configured invocations; callbacks must not reenter the adapter or agent run lock. Scoped construction installs an explicitly borrowed inbox and same-registry wait session. Pure tool factories remain barrier-only. New wait-result variants require exhaustive matches to be updated.

## Terminal binding compatibility

[Terminal bindings](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#terminal-store-bindings) are borrowed capability data without a codec or implicit acquisition. Hosts select exact partitions and own backend lifetime; catalog names derive no addresses. Trailing optional bindings preserve omitted-argument Scala calls, while entry product patterns and session factory JVM signatures require recompilation.

## Routed task consumption compatibility

[Bound routed factories](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#routed-task-result-consumption) opt into trusted consumption for fixed-session/catalog/session-catalog/directory tools. Ordinary factories remain observational. Receipts retain the resolved borrowed registry; persisted matching does not re-resolve authority from model route strings. Hosts must keep that registry alive.

## Full task output compatibility

[SubagentTaskOutputTool](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#full-task-output-retrieval) is explicit host installation without default registration or a new dependency. Its typed terminal data and optional worker/creation metadata contain no execution capability. Lookup/check/readiness precede the timed wait; backend I/O remains separately host-bounded. Failed/interrupted child outcomes are result data, while actual read failures and caller cancellation retain their separate failure semantics.

## Bound full output compatibility

[SubagentDeliveryMiddleware.taskOutputTool](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#bound-full-task-output-consumption) uses the existing task_output schema. Compose it explicitly with Toolkit.updated and configure the same middleware on the parent. It adds no default registration or receipt-budget change. Valid stored output can exceed encoded receipt capacity; oversized capture fails instead of weakening the bound. Persisted projection matching governs acknowledgement; redacted-away output is not treated as consumed.

## Durable admission compatibility

[Durable registry/session launches](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#durable-task-admission) require custom stores to implement admission and running/start ports. Terminal-only defaults fail closed; confirmStart's compatibility delegation to markRunning does not establish atomic cancellation denial. Ordinary volatile launch acquisition remains separate. Stored Pending/Running records do not recreate executions or acquire recovery ownership. Legacy records gain no invented admission dates.

## Task output metadata compatibility

[Task output metadata](../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#task-output-creation-and-check-metadata) appends optional createdAt with a None default to Running, PublicationPending and Terminal; legacy JSON remains readable, but positional enum patterns and binaries must migrate. Built-in stores persist lastCheckedAt through markChecked. Custom stores inherit a read-checked None result without a persistence claim and may override the port. Checking retains the selected store capability rather than resolving a replacement owner.

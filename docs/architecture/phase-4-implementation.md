# Phase 4 implementation notes

## Context compaction

`ConversationCompactor.preparer` installs opt-in scoped history preparation before every model request. Scope cleanup and structural validation precede canonical commit. Changed candidates require complete occurrence pairing and exact ordered system messages; unchanged candidates preserve the existing permissive input contract. Attempt-local recovery progress survives shrinking history. Summary failures become HistoryPreparationError and cannot trigger native fallback; cancellation preserves original input history plus one recovery.

`ConversationCompactor` accepts an effectful summarizer, a pure `TokenCounter`, and optional flush/offload callbacks. The default counter estimates serialized message size at four characters per token; hosts can inject a model tokenizer. Summary failures remain typed failures and cancellation propagates through every hook.

Boundary selection uses a token-window binary search, retains the configured recent message count, and preserves assistant calls with their results. Reused call IDs are paired by consumed-once occurrence and optional result name, with inline provider pairs isolated from local results. Orphaned tool results move into the summarized prefix. Prior summaries are included in summarization but excluded from flushing and retained history. System messages are preserved. Consecutive user turns are coalesced after summary insertion. The coalesced message records retained-user provenance so subsequent compaction can flush original user content without flushing summary text. Histories below the configured trigger are returned unchanged.

Old tool output and unsigned arguments are pruned before summarization, leaving the protected tail unchanged. Argument rewrites clear stale content; the presence of thoughtSignature or reasoningDetails preserves original arguments and content. MemoryFlushManager now supplies daily extraction through an injected DailyMemoryStore. Install manager.flush as the compactor flush callback. The extractor scope closes before append, and flush completes before offload/summarization. The store capability exposes curated reads and date-keyed daily appends only; LocalDailyMemoryStore now provides scoped cooperative filesystem ownership and atomic daily replacement; MemoryConsolidator and MemoryRepository.inMemory now implement budget validation and atomic conditional content/progress commits. LocalMemoryRepository adds a shared lease and versioned journal, recovering coherent content/progress before each exposed operation. Journal publication makes the candidate recoverable even if a later I/O failure prevents acknowledgement. This does not provide atomic visibility to raw filesystem readers or power-loss durability. LocalDailyMemoryStore delegates to the same recovery path. See the [memory contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md) for date, failure, provenance, and ownership boundaries.

## Tool result eviction

`ToolResultEvictor.processor(config)` installs opt-in processing of newly completed local and matched inline provider observations in ReAct. Raw completion is stored before cancellable persistence; processed previews commit before continuation and result events. Typed failures preserve raw output. Supplied history uses the separate ToolResultEvictor.preparer adapter. Inline results stay within their original assistant message and bypass local execution/authorization. Scope cleanup precedes processed inline Start/Delta/End publication; the returned reply uses canonical processed content.

`ToolResultEvictor` offloads text exceeding 80,000 characters, retaining 2,000 characters at each end and a read_file instruction. Configured file and memory tools are excluded. Content hashes distinguish reused call IDs with different output. Rewritten metadata, result states, and non-text blocks survive eviction. Failed persistence preserves the complete original observation; interruption remains cancellation.

`ToolResultStore.local(workspace, WorkspaceTrust.HostControlled)` resolves the workspace and output directory canonically. The explicit trust argument requires OS protection against untrusted mutation of the workspace, storage directory, and their ancestors; `WorkspaceTrust.Untrusted` is rejected. Canonical checks are defense in depth, not an atomic sandbox for attacker-writable directories.

The store writes a scoped temporary file and publishes completed content through an atomic hard link. Cooperative writers across separate store instances cannot expose partially written results. Existing files must be regular files in the expected directory with identical content. Hard-link support is required; unsupported filesystems produce a typed persistence failure, allowing eviction to retain the original result. Cleanup failures are logged without overriding persistence success. This is atomic publication, not a crash-durability guarantee. Filesystem effects run through ZIO.attemptBlocking. Privileged symbolic-link creation was unavailable on the Windows verification host.

ToolResultEvictor.preparer can precede ConversationCompactor.preparer via HistoryPreparer.andThen. Changed stages are validated before the next runs; runtime commits once after scope cleanup. Store-backed preview verification prevents cache chains while valid original content remains available in the configured key namespace. Custom stores must implement resolve; missing or forged references are raw candidates, while typed storage failures preserve input. See the [eviction contract and migration](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/eviction/README.md).

ToolResultStore.local accepts ResultStorageLocation.WorkspaceDefault or AbsoluteDirectory(path). Absolute selection requires an existing host-owned directory and leaves the unused workspace untouched. Canonical/type checks run before each operation; protection of external ancestors remains a host requirement. The default path and publication semantics are unchanged.

## Permission decisions and confirmation

`PermissionEngine` is immutable. Independent policy, tool, and interceptor decisions combine with Deny > Ask > Passthrough > Allow. Unknown tools default to Ask. Restricted rules inspect an explicit argument path (command by default); unrelated JSON strings cannot authorize a call.

`PermissionRequests` registers a Promise before publishing an interactive request. Duplicate IDs fail, confirmations complete once, and interruption removes the pending entry. `PendingApproval` and `ConfirmResult` are serializable values for durable hosts. `PermissionEngine.resume` returns the updated policy and an approved or denied call, applying modified input without another model request. Deny rules are checked again against modified input.

InteractivePermissionAuthorizer installs policy through the core ToolAuthorizer port. It registers before publication, reruns both PermissionGate callbacks on approved name/input, closes their scopes, and atomically merges confirmed rules against the latest policy. Deny remains absolute. Confirmed rules are independent of subsequent execution; core may deny signed/invalid input after rule installation. Hosts authenticate confirmations and supply durable checkpoints/idempotency; this adapter waits within the active invocation and provides no durable database.

## Core API hardening

- Message constructors and JSON decoding are now pure. `Msg.id` is optional, preserving externally supplied IDs; `message.identified` allocates an ID within ZIO when required.
- Toolkit registration, withTool, and merging return Either[AgentError, Toolkit]. Duplicate names are typed errors, and execution preserves existential input/output types without casts. Tool defects become error observations; interruption still cancels execution.
- PathSafety.isDangerousPath and expandTilde return effects. Parent traversal is rejected before normalization. Canonicalization resolves the nearest existing ancestor for new paths and reports failures instead of allowing them silently.
- Agent construction rejects invalid bounds and a missing model client. Agent history is exposed as an effect through `history`. AgentResult uses mutually exclusive AgentRunState cases instead of independent suspension/interruption flags.
- Synthetic structured output completes after the acting phase finishes in the Completed state with a captured generate_response value, without an extra model turn. Compression removes synthetic blocks while retaining ordinary calls and results in mixed turns. AgentResult.usage combines model turns; reply.usage retains the generating request without duplicating history metrics. Thinking blocks and unrelated empty messages are preserved. Interrupted structured runs fail before publishing captured data.

Structured completion resolves one payload AST for decoding and metadata, prepares within the attempt timeout, then commits history, completes required observation and cleanup, and publishes AgentResult before AgentEnd. Direct and structured streaming APIs share this path; Auto fallback retains two attempt boundary pairs. Pre-commit failures publish no finalized result.

ModelInputTransform projects a request-only history after canonical preparation commits and before ModelRequestStart. Its scope closes before structural validation and dispatch. Canonical history, recovery positions, and usage remain authoritative; projection failures use ModelInputError and cannot trigger native fallback. Complete pairs may be omitted without losing canonical eviction or stored payloads. The port transforms history only; semantic fidelity and newly injected content remain host responsibilities.

## Verification

Run `sbt scalafmtAll scalafmtSbt`, then `sbt verify`. The Scala suites use local scripted clients, ZIO Test services, and temporary filesystem resources. They do not exercise a live provider endpoint. The configured formatter plugin is required for the repository verification commands.

The current implementation passed 555 core and 475 harness tests, with no failures or compiler warnings. The cleanup-failure regression intentionally emits a runtime warning. Current review acceptance is maintained in the [core README](../../modules/core/README.md) and [harness README](../../modules/harness/README.md), with package-level scope details. Resolved review reports are removed from the active tree; their history remains in Git. Remaining provider/event integration, exhaustive upstream parity, and host integration remain tracked work.

SessionTranscriptWriter now supplies an independent offload callback using typed observations and atomic TranscriptStore.inMemory authority. Projection retains summaries and thinking, bounds tool previews, and separates structural stable keys from UUID identity. LocalTranscriptStore now provides strict versioned whole-log persistence through shared WorkspaceLease ownership. TurnCompletionObserver installs terminal canonical snapshot recording with an explicit anonymous-message policy. Immutable remote segments and session indexes remain pending; opt-in local flush/consolidation scheduling is implemented below.

MemoryTrigger, validated MemoryInterval and MemoryTriggerGate now provide typed local admission for flush/maintenance. One explicitly shared authority owns structural operation/scope keys; fixed policies and monotonic sampling make exact expiry deterministic. Cleanup never shortens an active reserved interval. Opt-in completion-triggered background flush is implemented below; consolidation scheduling is also available, while distributed coordination remains pending; explicit session deletion is implemented below; explicit daily archival is available below.

MemoryTaskQueue now provides bounded host-owned serial work with pending conversation coalescing and explicit ticket outcomes. Its persistent worker remains interruptible after masked acquisition, and shutdown waits for running task cleanup. This is the reviewed queue foundation; completion-triggered flush integration is described below and consolidation is also available.

MemoryFlushScheduler now installs successful-completion background flush through an explicit conversation capability, shared trigger/queue authority, pure eligibility preparation and host ticket registration. The four upstream async-flush methods are adapted with real runtime/extractor evidence. Owned consolidation scheduling is available; combined daily-retention maintenance is available below; operational receipt monitoring remains pending.

MemoryConsolidationScheduler now connects successful completion to owned worker-side Maintenance admission, current committed storage snapshots and mandatory result reporting. TurnCompletionObserver.andThen provides separately scoped, fail-fast composition. Reporting errors preserve task failure without undoing commits. Replaced pending maintenance can precede a newer flush: consolidation is opportunistic, not a strict per-turn dependency. Combined daily expiry is available below; automatic session pruning and durable/remote scheduling remain unfinished.

MemoryRepository.retention now provides explicit acknowledged-daily archival with typed date/content identities. Shared in-memory/local tests preserve pending facts and reject stale snapshots after equal-content recreation. The local protocol publishes verified copies before checkpoint advancement and active-file removal, and exposes conservative partial progress after failure. Combined cutoff policy/scheduling is available below; automatic session pruning remains pending; explicit local retirement is available in the transcript package.

MemoryMaintenance and MemoryMaintenanceScheduler now install explicit daily retention policy into owned maintenance. Cutoff validation precedes work, consolidation precedes acknowledged archival, and aggregate reporting follows both. KeepAll bypasses date conversion; deferred consolidation outcomes skip archival. One gate/job covers the service, while the old consolidation-only scheduler remains compatible. Automatic session discovery remains unfinished; the six MemoryConfig adaptations are completed below.

Explicit local transcript pruning now runs under the writer's ownership lease, with strict log validation, observation-aware activity and fixed-payload deletion. Thirteen added cases cover preservation and ownership boundaries. The host selects the inactive transcript; the explicit expiry policy is available below, while automatic enumeration and remote pruning remain pending; explicit batch integration is implemented below.

TranscriptRetentionPolicy and pruneWithPolicy now provide explicit 180-day expiry selection, strict day validation, one pre-ownership clock sample and a complete KeepAll bypass. Eight new cases verify the policy boundary. Ordered batch selection and integration into the shared maintenance job are implemented below.

## Explicit batch maintenance

Ordered TranscriptRetentionTarget capabilities now integrate through MemoryMaintenanceCoordinator and an additive scheduler overload, sharing one Maintenance admission/job. The transcript cutoff is validated before memory effects. Returned memory outcomes permit independent transcript work; failures stop following effects and preserve prior commits/deletions. Twenty-two new tests cover target identity/order, cutoff preparation, full causes, local partial persistence, completion observers, coalescing and owner cleanup. Discovery and remote authority remain pending; immutable configuration is implemented below.

## Memory configuration acceptance

Immutable MemoryConfig, strict MemoryTokenBudget and total ConsolidationPrompt now connect defaults and overrides to flush/consolidation/maintenance factories. Seventeen new cases verify six explicit upstream method adaptations plus actual prompt, retention, throttle and budget enforcement behavior. All ten MemoryConfigTest methods are classified with intentional differences documented; exhaustive harness parity remains unfinished. Model selection, session discovery and remote authority remain separate work.

## Plan-mode foundation

A reviewed pure POSIX command-shape inspector now supplies ten syntax/type regressions. Opt-in runtime authorization is implemented below. Fourteen upstream plan-mode methods are inventoried; static inspection alone closes none, and the later admission increment adapts two.

## Plan permission integration

Fourteen new tests now verify opt-in plan restrictions through the real permission/ReAct path, including denied observation events, replacement input, mode activation during approval, mandatory Ask and already-admitted execution. Two middleware methods are explicitly adapted; twelve plan lifecycle/propagation methods remain pending. No process execution or automatic mode transition is provided.

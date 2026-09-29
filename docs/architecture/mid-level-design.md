# Mid-level behavior and planned integrations

Sections 1-6 describe current components. Section 7 describes implemented daily extraction, scoped daily storage, and process-local consolidation, and recoverable local consolidation. Sections 8-10 describe planned behavior. [AGENTS.md](../../AGENTS.md) owns invariant IDs; [low-level contracts](low-level-design.md) link to current declarations.

## 1. ReAct lifecycle

1. Acquire the instance run permit, establish run-owned resources, initialize interruption, and append input.
2. Prepare and commit canonical history, close and validate a scoped request-only projection, then invoke the client in its own scope. Explicit worker ownership preserves completion and cleanup causes across cooperative interruption and timeout.
3. Persist assistant tool calls before dispatch. Parallel execution requires ToolExecution.Parallel and all pending tools to be concurrency-safe.
4. Persist completed observations under a narrow interruption mask. Tool failures/defects become Error observations; cancellation propagates.
5. Restore interruptibility before recursive reasoning. Bound iteration count, total duration, and thinking retries.
6. Finish on visible output, completed inline provider results, synthetic capture, or a bounded termination.

Cleanup completes before returning. Concurrent runs on one instance serialize; upstream concurrent-call rejection is an intentional difference.

ModelInputTransform projects a request-only history after canonical preparation commits and before ModelRequestStart. Its scope closes before structural validation and dispatch. Canonical history, recovery positions, and usage remain authoritative; projection failures use ModelInputError and cannot trigger native fallback. Complete pairs may be omitted without losing canonical eviction or stored payloads. The port transforms history only; semantic fidelity and newly injected content remain host responsibilities.

## 2. Thinking and interruption

Thinking-only responses with no calls and blank visible content append a synthetic user system-reminder. Existing agentscope_synthetic and agentscope_reminder_kind metadata keys preserve compatibility.

Cooperative interruption returns Interrupted AgentRunState. Fiber cancellation stays cancelled. Both reconcile unfinished local calls with Error observations and append a recovery assistant carrying agentscope_generate_reason=interrupted. Completed and inline provider observations survive. An older run's recovery must not suppress recovery for the current run.

## 3. Streaming

StreamAccumulator returns typed results, groups tools by streamIndex, preserves indexed text/thinking and signatures, and requires compatible finish markers plus complete object arguments. Only interrupted output sanitizes invalid input and raw content to {}. Usage retains the latest snapshot, including usage after finish. ReasoningModelAdapter returns typed errors for malformed protocol deltas, requires explicit stream indexes, rejects merged alternatives, and preserves provider errors; SseParser handles framing. ChatCompletionsRequest supplies a pure validated text/function request body with complete call/result pairing and explicit reasoning replay. Unsupported provider content fails rather than disappearing. ChatCompletionsTransport performs scoped HTTP completion with bounded bodies and strict [DONE] termination. ChatCompletionsClient implements LlmClient, publishes validated incremental deltas with runtime-assigned ModelStreamContext, and closes content blocks only after complete stream validation.

AgentEvent defines 39 variants; streamEvents delivers those emitted by available components and propagates model failures. Closing the stream cancels its scoped producer.

Structured completion resolves one payload AST for decoding and metadata, prepares within the attempt timeout, then commits history and publishes AgentResult before AgentEnd. Direct and structured streaming APIs share this path; Auto fallback retains two attempt boundary pairs. Pre-commit failures publish no finalized result.

Completed local tool observations are persisted before their text-delta/end events under the completion mask. Every local and inline result event retains the generating reply/request context. Cancellation preserves already committed results and reconciles pending history; it does not assert completion for interrupted tool work.

## 4. Structured output

Native mode supplies LlmConfig.responseFormat; the transport serializes it. Auto uses the declared StructuredOutputCapability: Native() supports tools, Native(Unsupported) requires an empty toolkit, and Unsupported selects synthetic directly. Unknown preserves native probing without tools and synthetic selection with tools. Explicit modes override this routing; synthetic attempts clear responseFormat. Native Auto attempts retain rejection fallback for status 400/422/501. Synthetic mode registers generate_response, wrapping the schema and hoisting both definition namespaces. A captured response completes only after the acting phase finishes in the Completed state, without an extra model turn. Compression removes synthetic blocks while preserving mixed ordinary calls/results; AgentResult.usage aggregates model turns, while reply.usage records the generating request once. Signed thinking is transferred to the final reply without duplicating it in history. Unrelated empty messages survive compression. A non-Completed structured run fails with AgentError.Interrupted before decoding or rewriting recovery history.

Empty-input continuation, signed thinking, usage, and immediate repeated calls have regression coverage. Runtime-schema direct and structured streaming scenarios are also verified. General model-call middleware and broader legacy hook gaps remain explicit [parity work](test-parity.md).

Structured text requires a complete unique-key JSON document. Metadata and captured ASTs are validated directly, and synthetic arguments are validated before selecting response. Five loopback HTTP scenarios now verify native and synthetic paths; explicit capability selection is implemented; general model-call middleware remains pending. Typed post-reasoning Keep/Replace/Discard decisions run in their own cancellable scope before persistence and dispatch; invocation-local usage accounts returned observations across discards and Auto fallback. Optional request-scoped ModelTextTransform replaces visible fragments before accumulation and typed publication; the runtime still validates frames, finish markers, and final structured JSON. Raw frame diagnostics remain unchanged.

## 5. Compaction and eviction

| Operation | Trigger | Behavior |
| --- | --- | --- |
| Immediate eviction | Text >80,000 chars | Store complete text; preserve 2,000 head/tail chars and read_file instruction |
| Historical pruning | History exceeds triggerTokens | Prune older output/arguments above 2,000 chars, protecting the tail |
| Summary compaction | Still over trigger after pruning, with safe prefix | Summarize prefix, replace prior summaries, preserve safe tail |

Below-trigger valid history remains unchanged. Summary, flush, and offload effects are injected. Prior summaries enter summarization but never raw-memory flushing. Coalesced users retain serialized provenance, validated before reconstructing original user messages for later flushing.

A token-window binary search respects a minimum recent-message count. Boundaries shift backward across paired calls/results, consuming each occurrence once with optional local result-name matching. Inline results consume only their own assistant provider calls. Unsigned argument pruning clears cached content; reasoning metadata keys protect arguments from rewriting. Orphaned tool results move into the prefix.

Immediate local/inline eviction is installed through ToolResultEvictor.processor and the core ToolResultProcessor port. The runtime appends raw completion atomically before scoped interruptible processing, then commits the processed observation before model continuation/publication. Typed failures retain original output; cancellation retains completed raw successes. ConversationCompactor.preparer installs scoped canonical-history preparation before model requests. Changed history must preserve ordered system messages and complete occurrence pairing; unchanged input remains permissive. ToolResultEvictor.preparer installs supplied-history eviction. Inline observations are processed at their original assistant/result ordinal, and final replies read the processed canonical assistant. Defective acting phases preserve their original cause and close only unmatched local calls; pure cancellation retains the interruption recovery contract.

ToolResultEvictor.preparer can precede ConversationCompactor.preparer via HistoryPreparer.andThen. Changed stages are validated before the next runs; runtime commits once after scope cleanup. Store-backed preview verification prevents cache chains while valid original content remains available in the configured key namespace. Custom stores must implement resolve; missing or forged references are raw candidates, while typed storage failures preserve input. See the [eviction contract and migration](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/eviction/README.md).

## 6. Permission and storage boundaries

The core ToolAuthorizer port runs in a per-call scope after assistant persistence and before local execution. The default allows calls; an installed authorizer can approve validated input or deny. Input rewrites target exact occurrences, clear cached arguments, and preserve original-input provenance. Changed inputs carrying thoughtSignature or reasoningDetails are denied. Typed failures and defects become Denied observations; cancellation remains interruption. InteractivePermissionAuthorizer installs harness policy and active-invocation confirmation through this port.

PermissionEngine combines gates as Deny > Ask > Passthrough > Allow. Restricted rules inspect their configured argumentPath. PermissionRequests registers before publishing, resolves once, and removes cancelled waiters.

PendingApproval and ConfirmResult serialize durable host state. Resume updates policy/input without calling the model. InteractivePermissionAuthorizer registers through PermissionRequests, rechecks both gates on approved arguments, and atomically resumes against current policy. Confirmed rules survive later core rejection or cancellation after the decision point. Hosts authenticate approvals and supply durable checkpoints/idempotency; no durable workflow is implicit.

ToolResultStore.local requires WorkspaceTrust. Untrusted fails before filesystem access. HostControlled requires OS protection against hostile mutation of workspace/storage ancestors. Scoped temporary files are completely written before atomic hard-link publication; unsupported filesystems return typed errors. Eviction preserves observations on persistence failure. This is cooperative atomic publication, not hostile-workspace sandboxing or crash durability.

ToolResultStore.local accepts ResultStorageLocation.WorkspaceDefault or AbsoluteDirectory(path). Absolute selection requires an existing host-owned directory and leaves the unused workspace untouched. Canonical/type checks run before each operation; protection of external ancestors remains a host requirement. The default path and publication semantics are unchanged.

## 7. Daily memory, local storage, and consolidation

MemoryFlushManager reads curated and dated context through DailyMemoryStore, extracts new facts in a bounded scope, then appends a dated Markdown entry through that capability. No curated write or arbitrary path operation is exposed. One captured Clock instant and explicit zone (UTC by default) select both read and append dates. The manager expands validated coalesced-user provenance before excluding summaries, system/internal context, and thinking/media. Blank or trimmed exact NO_REPLY output skips append. Typed read/extraction/append failures propagate; extraction cancellation and extraction-scope cleanup defects prevent append. Cancellation or failure during append has backend-defined partial effects. One manager serializes read/extract/append operations; independent managers and backend/process concurrency require host coordination. Completed external writes cannot be rolled back.

Install manager.flush as ConversationCompactor's flush callback. MemoryExtractor.fromClient sends a scoped standalone text request with no tools or response formatting; interrupted output remains cancellation. Semantic extraction quality and deduplication are model responsibilities.

LocalDailyMemoryStore.open(workspace, WorkspaceTrust.HostControlled) owns memory/YYYY-MM-DD.md through a scoped CREATE_NEW lease with an opaque token. It rejects competing owners, unsafe paths and nonregular payload targets, and old handles after scope closure. Each append reads the current ledger, writes a scoped temporary file, then publishes through ATOMIC_MOVE without a non-atomic fallback. Scope closing waits for admitted work; ordinary pending cancellation is delivered after work completes, while explicit caller masking remains effective. No fsync/crash durability or exactly-once delivery is implied. Cleanup ownership failures become finalizer defects and retain the lease; an unfinished lease is never automatically removed.

MemoryConsolidator receives a separate ConsolidationStore capability with no daily-write operations. It reads one atomic immutable snapshot, sorts nonblank daily inputs, closes extraction scope, strips the candidate, then enforces a configured 1–4,000 token budget before commit. Blank output leaves both content and progress unchanged. The supplied TokenCounter defines the measurement; the default estimates serialized message size and is not an exact provider tokenizer. Negative counts fail explicitly. Semantic fidelity and deduplication remain model responsibilities.

A snapshot revision covers every curated/daily/progress mutation and must never be reused. Commit atomically replaces curated content and acknowledges precisely that snapshot, or returns Conflict without change. Stale output is never retried against a refreshed revision. MemoryRepository.inMemory provides concrete Ref-based authority with separate daily and consolidation ports and a non-wrapping BigInt revision. Later appends make the complete changed day pending again. No timestamps are used as progress authority.

LocalMemoryRepository opens both capabilities under the same cooperative .daily-writer.lease. Every operation first recovers a pending consolidation journal. The journal binds old/new curated content, checkpoint generations, and all daily SHA-256 fingerprints; mismatches fail before writes and retain the journal. Recovery publishes missing new components, verifies them, then removes the journal. Publishing the journal commits the candidate to recovery; a later failure may therefore have uncertain acknowledgement. This is coherent visibility through cooperating APIs, not a multi-file transaction for arbitrary filesystem readers or fsync/power-loss durability.

LocalDailyMemoryStore delegates to this repository. Its capability cannot propose curated changes, but opening or using it may publish an already journaled curated transaction during recovery. Open one full repository and distribute its ports; independent owners conflict. The versioned JSON checkpoint uses memory/.consolidation_state and rejects legacy timestamps instead of silently resetting progress. Remote storage, watermark migration, transcript lifecycle integration, and scheduling remain pending.

## 8. Planned durable HITL integration

Middleware checks permission before execution. Interactive hosts route authenticated confirmations to Promise waiters. Durable hosts persist partial history and pending approvals before ending an activity, then resume pending execution from ConfirmResult without re-prompting the model. Tool execution checkpoints and idempotency belong to the host.

## 9. Planned subagents

Enforce MAX_SPAWN_DEPTH=3. In-turn children use scoped fibers and propagate cancellation. At a 30-second synchronous timeout, promotion transfers ownership to a persistent TaskSupervisor; finishing the parent turn must not terminate promoted tasks. Task inspection and cancellation interfaces remain design targets.

## 10. Planned plan-mode hardening

Reject mutating tools in plan mode. If shell exploration is allowed, statically inspect commands and reject output redirection and mutating binaries. Plan exit requires explicit Ask approval. Shell inspection, policy propagation, and plan tools are not implemented.

## Transcript observation authority

One TranscriptStore capability represents one transcript. The writer projects Msg values into drafts without assigning parent links. The in-memory backend allocates UUID/time candidates in ZIO, then atomically filters structural stable keys, links only accepted rows, and publishes the complete batch. UUID collisions reject the whole batch. Repeated stable keys retain the first payload; anonymous observations append on every call. recordedAt is sampled before commit and does not define order. Errors and interruption propagate, unlike upstream swallowed persistence errors. Install writer.offload as a compactor callback; this does not establish recording at every turn or physical persistence.

LocalTranscriptStore binds an existing host-controlled directory to one transcript. Shared WorkspaceLease admission encloses canonical validation, full log read/strict decode, chain restoration, deduplication/linkage, and atomic replacement. Private versioned JSONL envelopes reject unknown/coerced fields and invalid chains before writing. A write replaces the complete log; costs grow with history size. Stale leases require operator recovery, and publication does not imply fsync durability. Preview limits preserve Unicode surrogate pairs. Java paths/dual files/indexes and remote segments are not reproduced.

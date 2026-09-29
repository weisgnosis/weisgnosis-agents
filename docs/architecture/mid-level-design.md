# Mid-level behavior and planned integrations

Sections 1-6 describe current components. Sections 7-10 describe planned behavior. [AGENTS.md](../../AGENTS.md) owns invariant IDs; [low-level contracts](low-level-design.md) link to current declarations.

## 1. ReAct lifecycle

1. Acquire the instance run permit, establish run-owned resources, initialize interruption, and append input.
2. Invoke the client in a request-owned scope. Race its Exit against cooperative interruption so success and failure both complete promptly.
3. Persist assistant tool calls before dispatch. Parallel execution requires ToolExecution.Parallel and all pending tools to be concurrency-safe.
4. Persist completed observations under a narrow interruption mask. Tool failures/defects become Error observations; cancellation propagates.
5. Restore interruptibility before recursive reasoning. Bound iteration count, total duration, and thinking retries.
6. Finish on visible output, completed inline provider results, synthetic capture, or a bounded termination.

Cleanup completes before returning. Concurrent runs on one instance serialize; upstream concurrent-call rejection is an intentional difference.

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

A token-window binary search respects a minimum recent-message count. Boundaries shift backward across paired calls/results, matching repeated IDs by occurrence. Orphaned tool results move into the prefix.

Immediate local eviction is installed through ToolResultEvictor.processor and the core ToolResultProcessor port. The runtime appends raw completion atomically before scoped interruptible processing, then commits the processed observation before model continuation/publication. Typed failures retain original output; cancellation retains completed raw successes. Compaction and harness permission policy installation, supplied history, and inline provider result processing remain pending.

## 6. Permission and storage boundaries

The core ToolAuthorizer port runs in a per-call scope after assistant persistence and before local execution. The default allows calls; an installed authorizer can approve validated input or deny. Input rewrites target exact occurrences, clear cached arguments, and preserve original-input provenance. Changed inputs carrying thoughtSignature or reasoningDetails are denied. Typed failures and defects become Denied observations; cancellation remains interruption. The harness policy/interactive adapter is not yet installed.

PermissionEngine combines gates as Deny > Ask > Passthrough > Allow. Restricted rules inspect their configured argumentPath. PermissionRequests registers before publishing, resolves once, and removes cancelled waiters.

PendingApproval and ConfirmResult serialize durable host state. Resume updates policy/input without calling the model. Hosts authenticate approvals, validate request identity, recheck modified-input safety, persist checkpoints, and enforce idempotency. ReActAgent does not silently provide those integrations.

ToolResultStore.local requires WorkspaceTrust. Untrusted fails before filesystem access. HostControlled requires OS protection against hostile mutation of workspace/storage ancestors. Scoped temporary files are completely written before atomic hard-link publication; unsupported filesystems return typed errors. Eviction preserves observations on persistence failure. This is cooperative atomic publication, not hostile-workspace sandboxing or crash durability.

## 7. Planned memory services

MemoryFlushManager writes only daily memory/YYYY-MM-DD.md scratch files, excluding summary text and ignoring no-content flush responses. MemoryConsolidator alone updates curated MEMORY.md within a 4,000-token budget. Watermarks, scheduling, and durable persistence remain pending.

## 8. Planned durable HITL integration

Middleware checks permission before execution. Interactive hosts route authenticated confirmations to Promise waiters. Durable hosts persist partial history and pending approvals before ending an activity, then resume pending execution from ConfirmResult without re-prompting the model. Tool execution checkpoints and idempotency belong to the host.

## 9. Planned subagents

Enforce MAX_SPAWN_DEPTH=3. In-turn children use scoped fibers and propagate cancellation. At a 30-second synchronous timeout, promotion transfers ownership to a persistent TaskSupervisor; finishing the parent turn must not terminate promoted tasks. Task inspection and cancellation interfaces remain design targets.

## 10. Planned plan-mode hardening

Reject mutating tools in plan mode. If shell exploration is allowed, statically inspect commands and reject output redirection and mutating binaries. Plan exit requires explicit Ask approval. Shell inspection, policy propagation, and plan tools are not implemented.

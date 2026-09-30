# Architecture: weisgnosis-agents

## Runtime authority

The runtime owns state, cancellation, permission decisions, and completion. Model output is input to a deterministic state machine, never authority to bypass invariants. Pure transformations use immutable domain values. Randomness, clocks, filesystem access, network operations, and resource lifetimes belong inside ZIO effects.

ReActAgent retains in-memory history through private Ref values and serializes runs per instance. Durable workflow replay, database transactions, authentication, and execution idempotency belong to the host.

## Module boundaries

| Module | Available | Pending |
| --- | --- | --- |
| core | Models/codecs, event ADT, tools with immutable groups, scoped notes, preloaded skills and snapshot-consistent run-owned activation, attached fixed raw arguments and invocation-scoped publication, path checks, SSE parsing/assembly, scoped HTTP completion, client port, ReAct, structured output and result-bearing streams | Generic skill metadata, skill repository discovery, durable group activation, provider extensions, complete event integration, and exhaustive upstream parity |
| harness | Daily-memory extraction/local storage, in-memory/recoverable local consolidation, in-memory/local transcript observations, compactor, token estimator, opt-in result eviction/store and permission adapters, reviewed plan sessions and local drafts, scoped task supervision, configured child factories, task/session registries and tools, live denial/Plan inheritance and explicit policy-aware child construction, inline subagent declarations, bounded Markdown/YAML parsing and host-controlled local subagent loading, strict inline SKILL.md parsing, scoped named factory and reusable-session catalogs with model-facing routing, retained resource ownership and invocation-scoped child event forwarding | Durable checkpoints/reuse, remote memory/transcript storage, post-promotion/remote event delivery |
| mcp | Checked 2025-06-18 initialization, capability-checked list/call, bounded discovery, joined deadlines, bounded optional outbound cancellation, scoped stdio, bounded HTTP POST and explicit GET with JSON/SSE responses, session headers, opt-in same-send continuation, bounded listener resumption and explicit session termination, explicit Toolkit binding, scoped manager ownership and coherent host-selected group installation, opt-in metadata projection and distinct fixed arguments and model-overridable defaults with root input-schema exclusion and host-selected descriptive output schemas | General I/O recovery, session recovery; child propagation, ambient context bags, caching and schema evaluation |

Dependencies point from harness and mcp to core. Core must not import harness services. Hosts compose components and supply external dependencies.

## Core contracts

- Msg factories and codecs are pure. A private constructor, typed role factories, and validated dynamic updates make role/content checks unavoidable. The explicit identified effect allocates optional identity.
- Toolkit registration and merging return typed errors. Tools retain input/output types; failures become Error observations and cancellation propagates.
- AgentResult.state is Completed, Suspended, or Interrupted. ReAct currently produces Completed or Interrupted; Suspended is a domain capability, not installed durable middleware.
- LlmClient has scripted implementations and ChatCompletionsClient for scoped text/function HTTP completion. Runtime-assigned ModelStreamContext correlates incremental model events; malformed or incomplete streams never dispatch tools.
- AgentEvent is a sealed hierarchy of 39 variants. Some variants belong to planned subsystems.
- Native structured output supplies LlmConfig.responseFormat; transports serialize it. Synthetic output uses generate_response and removes only synthetic context blocks.

## Context and permissions

Immediate eviction applies above 80,000 text characters with 2,000-character head/tail previews. Historical pruning uses a separate 2,000-character threshold when compaction is triggered. Safe boundaries preserve tool pairs; summary rebuilding retains raw-user provenance across coalescing for later memory flushing.

Permission gates combine with Deny > Ask > Passthrough > Allow. Unknown requests ask. Interactive requests use Promise; durable hosts serialize PendingApproval and later supply ConfirmResult. Decisions are not execution checkpoints or authenticated external approval tokens.

Filesystem protection requires canonical paths and a valid ownership contract. Local stores require explicit WorkspaceTrust.HostControlled and OS-protected ancestors. Tool-result publication requires hard-link support; daily-memory publication requires atomic-move support. Untrusted workspaces fail closed. Canonical checks alone do not provide an atomic sandbox against hostile directory replacement.

## Specifications and evidence

[AGENTS.md](AGENTS.md) owns canonical invariant IDs and engineering rules. The [tracker](docs/architecture/tracker.md) separates implemented contracts, tests, and pending integration. [High-level](docs/architecture/high-level-design.md), [mid-level](docs/architecture/mid-level-design.md), and [low-level](docs/architecture/low-level-design.md) documents describe current contracts and planned behavior. Source declarations remain authoritative for signatures.

Test counts do not establish exhaustive parity. Map each applicable upstream scenario and state pending or inapplicable cases explicitly in the [parity audit](docs/architecture/test-parity.md).

The typed AgentEvent API is the core event contract. Deprecated upstream v1 Event/EventType/EventSource envelopes are deliberately outside this port: no legacy filtering, cumulative snapshots, isLast field, or Java diagnostic compatibility is implied. The parity audit records all ten applicability decisions separately from behavioral evidence. Actual model streams now carry reply/request correlation; richer subagent attribution and remaining model/tool ordering are still required work.

Completed local and matched inline provider results can use a scoped ToolResultProcessor. The runtime stores raw completion before processing; cancellation preserves it, and successful payloads commit before model continuation/events. Harness ToolResultEvictor.processor installs immediate local/inline eviction. ConversationCompactor.preparer installs scoped preparation of canonical history before each model request; ToolResultEvictor.preparer installs supplied-history eviction. Core ToolAuthorizer now provides scoped per-call decisions before local execution, with precise approved-input persistence and fail-closed Denied observations. Its default explicitly allows calls; the harness interactive adapter is available through explicit installation. Durable resumption remains pending.

Absolute result storage is an opt-in ResultStorageLocation on ToolResultStore.local. It requires an existing host-controlled directory and protected ancestors outside the workspace; canonical location validation does not pin filesystem object identity.

ModelInputTransform provides a scoped request-only history view after canonical preparation. Structural validation precedes dispatch; projected values never replace canonical history, recovery positions, or invocation usage. Canonical eviction remains independent of pair omission from the model input.

Stored plan sessions now invalidate review before writing and reopen content only as unapproved Plan drafts. Opt-in model-facing controls are now available; descriptive location context is available; typed workspace-relative directories are available; durable approval restoration remains separate work.

PlanTools now adds strict opt-in enter/write/exit capabilities. Exact-ticket host review is mandatory inside exit even with ordinary Allow rules; publisher transport and authenticated decision routing remain host responsibilities.

PlanContext now associates one snapshot with configured/observed location metadata. Four additional manager/prompt scenarios and the stricter typed-directory scenario are adapted; no location value grants filesystem or approval authority.

Typed PlanDirectory configuration selects an existing strict descendant of an explicit host-controlled workspace. Native Path opening retains its semantics; no directory creation or implicit workspace discovery is provided. Opt-in Plan admission also consumes transient access from the actual admitted Tool while preserving explicit host classifications and live confirmation rechecks. Plan parity records nine of fourteen named adaptations; complete child installation and propagation remain pending.

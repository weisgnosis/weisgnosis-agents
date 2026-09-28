# Architecture: weisgnosis-agents

## Runtime authority

The runtime owns state, cancellation, permission decisions, and completion. Model output is input to a deterministic state machine, never authority to bypass invariants. Pure transformations use immutable domain values. Randomness, clocks, filesystem access, network operations, and resource lifetimes belong inside ZIO effects.

ReActAgent retains in-memory history through private Ref values and serializes runs per instance. Durable workflow replay, database transactions, authentication, and execution idempotency belong to the host.

## Module boundaries

| Module | Available | Pending |
| --- | --- | --- |
| core | Models/codecs, event ADT, tools, path checks, SSE parsing/assembly, client port, ReAct and structured output | Live HTTP transport and exhaustive upstream parity |
| harness | Compactor, token estimator, eviction/store interface, policies, interactive approval requests, durable approval DTOs | Middleware, durable checkpoints, memory services, subagents, plan mode |
| mcp | Design scaffolding | Protocol, transports, discovery and toolkit bridge |

Dependencies point from harness and mcp to core. Core must not import harness services. Hosts compose components and supply external dependencies.

## Core contracts

- Msg constructors and codecs are pure. The explicit identified effect allocates optional identity.
- Toolkit registration and merging return typed errors. Tools retain input/output types; failures become Error observations and cancellation propagates.
- AgentResult.state is Completed, Suspended, or Interrupted. ReAct currently produces Completed or Interrupted; Suspended is a domain capability, not installed durable middleware.
- LlmClient is an interface with scripted implementations. Independent SSE parsing and assembly do not imply a live transport.
- AgentEvent is a sealed hierarchy of 31 variants. Some variants belong to planned subsystems.
- Native structured output supplies LlmConfig.responseFormat; transports serialize it. Synthetic output uses generate_response and removes only synthetic context blocks.

## Context and permissions

Immediate eviction applies above 80,000 text characters with 2,000-character head/tail previews. Historical pruning uses a separate 2,000-character threshold when compaction is triggered. Safe boundaries preserve tool pairs; summary rebuilding retains raw-user provenance across coalescing for later memory flushing.

Permission gates combine with Deny > Ask > Passthrough > Allow. Unknown requests ask. Interactive requests use Promise; durable hosts serialize PendingApproval and later supply ConfirmResult. Decisions are not execution checkpoints or authenticated external approval tokens.

Filesystem protection requires canonical paths and a valid ownership contract. The local store requires explicit WorkspaceTrust.HostControlled, OS-protected ancestors, and hard-link support. Untrusted workspaces fail closed. Canonical checks alone do not provide an atomic sandbox against hostile directory replacement.

## Specifications and evidence

[AGENTS.md](AGENTS.md) owns canonical invariant IDs and engineering rules. The [tracker](docs/architecture/tracker.md) separates implemented contracts, tests, and pending integration. [High-level](docs/architecture/high-level-design.md), [mid-level](docs/architecture/mid-level-design.md), and [low-level](docs/architecture/low-level-design.md) documents describe current contracts and planned behavior. Source declarations remain authoritative for signatures.

Test counts do not establish exhaustive parity. Map each applicable upstream scenario and state pending or inapplicable cases explicitly in the [parity audit](docs/architecture/test-parity.md).

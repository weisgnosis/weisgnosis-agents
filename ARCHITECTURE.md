# Architecture: weisgnosis-agents

The library is an execution engine built from immutable Scala 3 values and scoped ZIO 2 effects. Hosts explicitly select clients, tools, storage, authorization and resource lifetimes. See the [component index](docs/architecture/README.md) for source-linked contracts.

## Runtime authority

The runtime owns conversation history, cancellation and completion. Model output proposes actions; it cannot bypass authorization, fabricate executed observations or declare completion independently. Clocks, randomness, filesystem, network and resource acquisition/release run inside ZIO.

One ReActAgent owns one in-memory conversation and serializes admitted runs. InvocationPreparer prepares separate prior history/new input once before fresh-input persistence; per-model history preparation and request projection remain separate. Structured fallback shares the admitted invocation and preparation. Resume/restoration validates saved pending data under current host factories and authorization without repeating preparation or supplied observations.

Subagent supervisors explicitly own promoted work and retained resources. Named sessions can restore idle history or checked suspension through exclusive host claims. Stored task status, worker names, execution origin, transcript entries and acknowledgement stamps are data, not execution capabilities. Restoration of running processes, distributed fencing, authentication and durable workflow replay belong to the host.

## Module boundaries

| Module | Responsibility | Explicit limits |
| --- | --- | --- |
| [core](modules/core/README.md) | Immutable models/events, typed tools and schema admission, ReAct/checkpoints, scoped phase observation, structured output, OpenAI-compatible completion and native Anthropic/Gemini adapters | No unrestricted OS toolkit or durable workflow host |
| [harness](modules/harness/README.md) | Paired context compaction, tool-output offload, memory/transcripts, permission/Plan admission, skills and supervised subagents | Components are explicitly installed; no implicit distributed scheduler or authenticated UI |
| [mcp](modules/mcp/README.md) | Checked initialization, tools/resources, direct stdio, legacy SSE and Streamable HTTP, manager ownership and request projection | Backend interoperability and recovery follow the selected protocol/transport contracts |
| [storage-postgresql](modules/storage-postgresql/README.md) | Conditional versioned JSON over a host DataSource | No connection pool, deployment, distributed execution claim or replay of uncertain writes |
| [examples](modules/examples/README.md) | Host composition, lifecycle logging and explicitly enabled live tool/file applications | Selected live evidence is not universal provider certification |

Harness and MCP depend on core; core imports neither service layer. PostgreSQL implements a harness storage port. Hosts compose dependencies through constructors and owned scopes.

## Core contracts

- Pure message factories/codecs never allocate time or identity. Checked role/content construction and updates reject invalid values; explicit effects allocate optional identities.
- Tools retain existential input/output types. Checked Toolkit registration/merge preserves identity; failures become Error observations while cancellation propagates.
- Assistant calls persist before local execution. Interruption joins children and reconciles only unfinished local calls, retaining completed observations and one recovery message.
- Raw completed tool output persists before optional processing. Required accepted-response/local-result/completion observers own bounded scopes; late failure preserves committed history and full causes rather than authorizing blind replay.
- AgentResult.state distinguishes Completed, Suspended, Interrupted and Stopped. Phase Exit describes effect completion, not semantic agent completion. Scoped phases cannot repeat a continuation or replace runtime truth.
- Model requests own bounded scopes and runtime correlation. Reasoning profiles explicitly validate replay/signatures/metadata. Status retry closes each attempt; eligible failover receives a fresh model context. Malformed/incomplete streams cannot dispatch tools.
- Native structured output and synthetic generate_response are distinct checked paths. Synthetic schema/context processing preserves root definition namespaces and removes only synthetic context.
- Canonical events use the Scala payload format. An opt-in pinned-Java DTO adapter has explicit identities and actual Jackson fixtures; it does not automatically project runtime events or implement deprecated v1 envelopes. Informational codec-only payloads have no invented producer.
- Subscriber filtering and immutable host returned-result projection change presentation only. They cannot rewrite canonical history, authorization, runtime events or completion state.

## Context and permissions

Compaction preserves tool call/result pairs by occurrence, merges prior summaries and retains raw-user provenance across coalescing. Immediate eviction offloads text above 80,000 characters with 2,000-character head/tail previews; persistence failure preserves the observation. Historical pruning has a separate 2,000-character threshold. ModelInputTransform projects a validated request-only view without replacing canonical history or recovery positions.

Independent permission gates combine as Deny > Ask > Passthrough > Allow. Unknown requests ask. Host modes do not override explicit votes or Plan restrictions. Interactive approval uses Promise; durable hosts persist checked pending data and resume from ConfirmResult under current authority. Immutable rule advice is presentation only; installation requires explicit confirmation. Neither stored decisions nor suggestions authenticate a human.

Plan tools require explicit installation and exact-ticket host review for exit. Stored plans reopen as unapproved drafts; location metadata grants no filesystem or execution authority. Static shell inspection is not an unrestricted shell executor or an atomic sandbox.

Daily extraction writes dated memory; only consolidation writes curated memory within its budget. Explicit source discovery unions active dates/bindings without a global snapshot or content merge. Registered transcript writers append before indexing. Catalog retirement requires an exact host fence and preserves history/reservations. Local/remote handoff atomically retains individually validated branches and selected local continuation; observations cannot restore canonical agent checkpoints.

Local persistence requires HostControlled trust and protected ancestors. Canonical path checks do not pin object identity against hostile replacement. Result publication requires hard links; atomic replacement requires atomic-move support. Absolute result directories must already exist and be explicitly owned. Conditional storage retries only definite conflicts; uncertain writes can have committed and are not automatically replayed. Hosts own DataSource/schema provisioning, partitions, recovery claims and idempotency.

## Specifications and evidence

[AGENTS.md](AGENTS.md) defines the engineering rules and canonical invariants. The [tracker](docs/architecture/tracker.md) is the single completion checklist; the [parity reference](docs/architecture/test-parity.md) records named behavior, immutable API adaptations and unsupported differences. Upstream remains pinned; the separately fetched commit delta is classified without claiming implementation of new provider/service features.

[High-level](docs/architecture/high-level-design.md), [mid-level](docs/architecture/mid-level-design.md) and [low-level](docs/architecture/low-level-design.md) documents elaborate component contracts. Public source declarations remain authoritative for signatures. Test counts, passing mocks, packaged artifacts and local workflow checks do not establish exhaustive parity, remote CI success, public publication or universal provider/platform support. Selected actual PostgreSQL, native Windows and live-provider evidence retains its stated limits.

# weisgnosis-agents

The [durable admission contract](modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#durable-task-admission) persists Pending before child execution and confirms Running before per-task effects. Both independent source reviews and focused harness/PostgreSQL checks pass; full/artifact/archive/external-consumer checks also pass; review_fp and review_invariants approve the final combined scope against f87ed98: ten unchanged Scala identities and fourteen Markdown files, with no unresolved findings. Legacy records gain no invented dates, ordinary volatile acquisition remains compatible, and uncertain admission never authorizes execution. Task-output creation/check projection, durable cancellation, notification and owner-aware recovery remain unfinished.

The [invocation and prepared-run contract](modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#invocation-identity-and-prepared-execution) separates fresh execution correlation from saved model-request identity. Ordinary prepared runs expose a lazy single-attempt handle; Auto attempts share one invocation and resumed executions receive a new one. Confirmation authority remains bound to the exact saved reply/request pair and call occurrence, independently of observational invocation identity. Both independent reviewers approve the fourteen-file source manifest against `db80dc0`; full verification, four RC1 packages, source-archive preparation and fresh external consumption pass; review_fp and review_invariants approve final combined documentation against `db80dc0` for fourteen unchanged Scala files and fourteen Markdown files.

The [incremental and accumulated thinking contract](modules/core/src/main/scala/com/weisgnosis/agents/core/event/README.md#incremental-and-accumulated-thinking) preserves exact fragments and adds an optional per-index accumulated plaintext prefix. Existing test groups cover both pinned observations, interleaving, old/new codecs and public agent streaming; opaque reasoning stays outside plaintext. The linked six-file manifest owns migration, verification and final combined approval by review_fp and review_invariants against 4cc1225 for six unchanged Scala files and fourteen Markdown files.

The current [supported server revision agreement](modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/transport/README.md#supported-server-revision-agreement) adds opt-in checked revision membership, one scoped negotiation owner, atomic correlated initialization and immediate framing/header transitions. Exact-version defaults remain; legacy POST additionally carries the revision header. The linked manifest owns migration, exact source identities, verification and final combined approval by review_fp and review_invariants against 1e18d44 for eighteen unchanged Scala files and fifteen Markdown files. This does not establish complete original-plan parity.

The [immutable MCP process launcher](modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/transport/README.md#immutable-process-launcher) removes direct mutable Java environment-map manipulation while preserving scoped process ownership. Both source reviews and focused real-process checks pass; full verification, fresh archive admission and standalone consumption pass; review_fp and review_invariants approved final combined documentation against 203401a for four unchanged source/build identities and thirteen Markdown files.

A Scala 3 and ZIO 2 library for agent execution with explicit resource ownership and deterministic runtime authority. It adapts agent mechanics from [AgentScope Java](https://github.com/agentscope-ai/agentscope-java), licensed under Apache-2.0.

**Development status:** locally verified candidate artifacts are available from this source tree; no public distribution, frozen binary compatibility baseline or complete upstream transfer is claimed. Read the [getting-started guide](docs/getting-started.md), [current tracker](docs/architecture/tracker.md) and [method-level parity audit](docs/architecture/test-parity.md) before integration.

## Implemented components

| Module | Current capabilities |
| --- | --- |
| [core](modules/core/README.md) | Immutable messages and events; typed local and external tool bindings; runtime schema admission; scoped ReAct execution; interruption reconciliation; checked usage; native/synthetic structured output; HTTP SSE and ordinary JSON completion. |
| [harness](modules/harness/README.md) | Pair-safe context compaction; tool-output offload; memory and transcripts; permission gates and interactive/checkpoint confirmation; Plan mode; owned subagents, reusable sessions and bounded task listing; local skill loading. |
| [mcp](modules/mcp/README.md) | Checked tools/resource APIs with explicit lifecycle profiles; direct-process stdio, legacy HTTP/SSE and Streamable HTTP JSON/SSE; scoped installation and explicit host-owned session replacement. |
| [storage-postgresql](modules/storage-postgresql/README.md) | Optional conditional JSON publication backend for bounded memory, transcript, catalog, trigger-gate and acting-checkpoint adapters. The host supplies DataSource, driver and schema ownership. |

The runtime persists proposed tool calls before execution, validates model turns and maintains conversation and completion authority. Local tool failures become model observations; cancellation joins owned cleanup and reconciles unfinished work. [External tools](modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#external-tool-bindings) suspend for checked host-supplied observations instead of inventing a local handler.

Provider compatibility is explicit. The [client contract](modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md) defines selected reasoning/media representations, retry boundaries and failover. Tool messages default to text; image parts require an explicit compatible-endpoint selection. Serialization does not fetch media or establish support across providers.

## Build and verification

Use JDK 21 and the sbt version pinned in [project/build.properties](project/build.properties).

```text
sbt scalafmtAll scalafmtSbt
sbt verify packageArtifacts
```

`verify` checks formatting, compiles with fatal warnings and runs tests. Real PostgreSQL scenarios require the explicitly configured fixture documented in the [fixture contract](modules/storage-postgresql/src/test/scala/com/weisgnosis/agents/storage/postgresql/README.md); default checks do not contact a public model provider. See [CONTRIBUTING.md](CONTRIBUTING.md) for the complete contribution protocol.

## Host integration

Begin with the [scripted, HTTP/tool and owned MCP examples](docs/getting-started.md). The unpublished [examples project](modules/examples/README.md) provides tested cross-module assembly, registered transcript selection, lifecycle logging, audited calls and an explicitly enabled live-provider smoke program.

Scopes own resources and background workers. The host owns authentication, approval delivery, durable workflow replay, exclusive execution claims and idempotency. Process-local Promises and scopes do not establish recovery after a restart. [Checkpoint restoration](modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#before-acting-suspension-and-restoration) validates data and runtime bindings; it does not grant authorization to resume.

## Minimal local example

The complete [scripted application](docs/getting-started.md#scripted-agent) runs without credentials or a provider. The guide explains local installation into a separate sbt project; no unpublished dependency is presented as available from Maven Central.

## Documentation

- [Getting started](docs/getting-started.md): installation, complete examples and ownership boundaries.
- [Documentation index](docs/README.md): architecture, package contracts and integration routes.
- [API compatibility and migration](project/README.md#compatibility-policy): current public contracts and rebuilding obligations.
- [Artifact verification](verification/artifact-consumer/README.md): independent consumption of actual packaged JARs.
- [Implementation tracker](docs/architecture/tracker.md): completed scopes and unfinished work.
- [Upstream parity audit](docs/architecture/test-parity.md): pinned method mappings and intentional differences.

## License

[Apache License 2.0](LICENSE), copyright WeisGnosis. Upstream names are retained where needed for attribution, protocol compatibility and traceability.

## Current acceptance

Acceptance applies to the exact source/test revisions recorded in each package README, after independent FP/ZIO and invariant review. The [tracker](docs/architecture/tracker.md#current-acceptance) links current verification. Automated tests and scoped approvals do not prove exhaustive upstream parity, public-provider interoperability or production readiness. The [publication documentation audit](docs/architecture/tracker.md#publication-documentation-audit) records final independent acceptance of this bounded documentation/source correction scope.

## Integration boundaries

Bounded local persistence requires host-controlled filesystem ancestors. Canonical path screening is not an atomic filesystem sandbox. Remote adapters use explicit host-selected addresses and bounded aggregates or complete immutable segment chains; the [storage](modules/harness/src/main/scala/com/weisgnosis/agents/harness/storage/README.md) and [transcript](modules/harness/src/main/scala/com/weisgnosis/agents/harness/transcript/README.md) contracts define revision, orphan and uncertain-acknowledgement limits. Known-address catalog discovery is implemented; global discovery, automatic registration/selection, physical reclamation and layout migration remain unfinished.

MCP resource APIs, visible embedded-resource projection and runtime admission for declared Draft 4, 6, 7, 2019-09 and default 2020-12 are implemented within their linked review scopes. Selected legacy SSE and explicit revision profiles are implemented and verified. Bounded March 2025 batch messages are implemented within their finite review scope. An explicit [connection-local tool catalogue](modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/client/README.md#connection-local-original-definition-catalogue) retains bounded original definitions with effectful lookup and close-time clearing; live discovery remains separate. Skill/subagent declarations accept one initial BOM and LF/CRLF/CR while preserving interior data and original bounds; the [parser manifest](modules/harness/src/main/scala/com/weisgnosis/agents/harness/skill/README.md#utf-8-bom-frontmatter-compatibility) records source review and current delivery gates. Ordered preference/revision-method applicability and remaining behavior mappings stay open. Their [original-plan disposition](docs/architecture/test-parity.md#original-plan-disposition) remains explicit unfinished work; documenting a difference does not approve removal from the original plan. Broader middleware/event/provider parity, platform security validation, durable host integration, live-provider checks, remote CI and publication remain in the tracker.

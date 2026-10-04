# Scoped child execution and sessions

[TaskSupervisor](TaskSupervisor.scala), [SupervisedTask](SupervisedTask.scala) and [SubagentSpawner](SubagentSpawner.scala) provide host-owned execution and runtime lineage. [SubagentFactory](SubagentFactory.scala) assembles fresh configured ReAct children with a final [static tool boundary](SubagentToolBoundary.scala) and optional live parent-denial inheritance. [SubagentTaskRegistry](SubagentTaskRegistry.scala) adds bounded task admission and inspection; [SubagentSessionDirectory](SubagentSessionDirectory.scala) supplies bounded host membership for acquired sessions. These APIs are accepted within the exact reviewed scope below.

## Ownership, cancellation and promotion

`TaskSupervisor.open[A]` creates an execution owner in the host Scope. `attach` accepts a suspended scoped task and a separate parent cancellation lease. Admission is serialized against host closure; closed admission evaluates no task callback. Launch registration is masked, but task execution remains interruptible. Task effects and completion waits never hold the admission permit.

The supervisor owns each fiber from launch. Closing the attaching parent Scope cancels attached work and joins all task cleanup, including cleanup already started by explicit cancellation. `await` observes the complete Exit without cancelling. `poll` observes completed work without waiting and reports completion only after owned cleanup finishes. `cancel` also stops promoted work and waits for cleanup. Parent/host finalizers join tasks; task failures remain in the handle Exit. Live handles, admissions and lineage capabilities have no JSON codecs.

`awaitOrPromote` races completion against a fresh 30-second budget on each call, without restarting work. Only the winning timer's masked transition promotes the attached lease. Released work cannot be promoted. Promoted work survives parent closure; explicit cancellation and host closure still stop it. At simultaneous completion/deadline, the race winner decides. Cancelling a waiting observer does not by itself cancel the task or reverse committed promotion.

Host shutdown closes worker scopes in parallel, preventing one blocked finalizer from postponing interruption of other workers. Each worker closes its own sequential scope on completion, avoiding the ZIO 2.1.26 `forkIn` self-join deadlock. Task resources keep ordinary scoped cleanup and complete failure causes. Explicitly masked work or blocked finalizers can delay cancellation indefinitely. Tasks/finalizers must not wait for an owner or parent whose closure is joining them, or for their own handle. The host must retain its Scope for all desired background work; no daemon lifetime, remote adoption or restart durability exists.

## Shared resource ownership

`TaskSupervisor.own(initialize)` acquires a [TaskResource](TaskResource.scala) in both the supervisor and acquiring caller Scope. Closed admission evaluates no initializer. The initializer receives a nested supervisor and runs interruptibly; its scoped acquisitions outlive initialization and individual nested tasks. Initialization failure, defect or cancellation joins partial cleanup before returning, preserving the failure Exit supplied to resource finalizers.

Explicit `resource.close`, acquiring-caller finalization and supervisor shutdown share one closure authority. It closes nested admission, joins initialization and all nested workers (including queued and promoted work), then releases shared resources exactly once. Competing library close paths wait for the same complete cleanup outcome, including defects. The first close supplies the resource finalizer Exit: explicit close and normal supervisor shutdown use success; an initialization failure or caller finalizer can supply failure. This does not alter concurrent calls to a raw ZIO `Scope.close`. Completed resource registrations detach from the supervisor.

`TaskSupervisor.ownRetained(initialize)` uses the same admission, initialization and cleanup kernel without adding a caller-scope lease. After successful acquisition, only the supervisor lifetime and explicit resource closure bound retention. Failure or cancellation during acquisition joins the reserved owner before returning. For `own`, the host must still retain the acquiring Scope for the intended resource lifetime. Owned workers and finalizers must not close or await their own resource owner. Resource values and handles are process-local capabilities, with no codecs, session lookup, restart recovery or child-runtime reuse implied.

Migration: `TaskSupervisor` now requires abstract `ownRetained[B](TaskSupervisor[A] => ZIO[Scope, AgentError, B]): IO[AgentError, TaskResourceAdmission[B]]` in addition to `own`. This is source-incompatible for existing custom implementations. Custom implementations, decorators and test doubles must implement or delegate the retained ownership contract; there is no default fallback. Wrapping `own` in a short-lived `ZIO.scoped`, or using `attach`, would release resources too early. Public `own` retains its caller-scope lease semantics.

## Runtime-owned lineage

`SubagentSpawner.root[A]` suspends creation of a UUID-derived root at depth zero. Trusted hosts control root and retained capability distribution. Private construction and no codec prevent descriptive fields from recreating authority. A retained spawner can be reused under another caller Scope while its supervisor is open.

`spawn` supplies the callback a new descendant capability under the supervisor worker Scope. It preserves structural ancestry and the supervisor, admitting depths one through three. Depth three rejects another spawn with `AgentError.SpawnDepthExceeded(3)` before identity allocation or callback invocation. Constructing a spawn effect and closed admission invoke no callback. At an eligible depth, identity allocation precedes admission and may consume an unused UUID if admission is closed.

`source` is slash-separated UUID lineage for diagnostics, not a path, registry credential or automatically assigned event tag. Nested attached cancellation waits for descendant cleanup and ReAct reconciliation. Promoted descendants remain supervisor-owned. The tool boundary normalizes depth failure to an Error observation. Model-facing controls require the explicit installation below.

Migration: exhaustive `AgentError` matches must handle the existing `SpawnDepthExceeded(maximumDepth)` leaf. Its codec conveys diagnostics only.

## Current lineage identities

The spawner's context and retained-child helpers have the current session-catalog approval below; context ownership preserves lineage without consuming a UUID or depth, and retained child admission uses the existing depth check. Its unchanged lineage tests and typed error retain the scoped `review_fp` and `review_harness` approval against `279b7a3` for runtime-owned depth, lineage, nested cancellation and typed depth errors.

| Source | Git blob |
| --- | --- |
| [SubagentSpawner.scala](SubagentSpawner.scala) | `910ddfabb5f6b5b3e36025425feb5bfec423612f` |
| [SubagentSpawnerSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentSpawnerSpec.scala) | `b2866e1bab047dc31f463a1f809d45fe4676b7ba` |
| [SubagentSpawnerAgentSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentSpawnerAgentSpec.scala) | `56b59a7fa9a7af22f4d63b8578533a068312cd21` |
| [AgentError.scala](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/error/AgentError.scala) | [Current error identity](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#acting-stop-decisions) |
| [AgentErrorSpec.scala](../../../../../../../../../core/src/test/scala/com/weisgnosis/agents/core/error/AgentErrorSpec.scala) | `b564c375448d4a6789dc2e45f9261c02a8075650` |

## Configured child construction

`SubagentFactory.make` validates AgentConfig and combines host-supplied parent and child boundaries without acquiring resources. `narrowed` returns an immutable factory whose selection is intersected and denials accumulated. Exact tool names are used. `AllAvailable` means all tools present at construction, including automatic registrations; `Only(Set.empty)` permits none. Unknown selected names do not create tools.

`build` suspends the registration callback, validates inherited and registered map keys against declared names, merges all registrations with collision rejection, and only then applies the static filter before client acquisition and `ReActAgent.make`. Invalid keys are checked in lexical key order, inherited before registered. Registration collisions are checked in lexical key order even for excluded names. The inherited Toolkit is immutable. Tool existential types are preserved without casts.

Each build creates fresh runtime/history state with the supplied AgentConfig, LlmConfig and ToolAuthorizer. The authorizer still controls actual execution of statically available tools. Static filtering is separate from the explicit live denial wrapper described below. Host callbacks are trusted adapters and must suspend external work, translate boundary failures and acquire resources with scoped combinators.

The caller must retain `build`'s Scope for the runtime lifetime; acquired registration/client resources are released when that Scope closes, including after construction failure. `spawn` delegates to `SubagentSpawner` so construction and execution share a supervised worker Scope, including cancellation and cleanup. Depth rejection and closed admission precede factory callbacks. Ordinary `spawn` does not itself wait or promote; the returned handle supplies those operations.

A raw spawner does not automatically carry the factory's static boundary or inherited-denial wrapper. Trusted host callbacks can construct another factory or task. Model-facing controls retain their registry or session capability; hosts must separately configure any nested child controls. The opt-in construction path below propagates restrictions to descendants; inline declaration/general-purpose composition is available below. Explicit directory-bound routing is available below. No production automatic registrations are introduced: the callback supplies host-selected registrations.

## Inline declarations

[SubagentDeclaration](SubagentDeclaration.scala) is immutable configuration for a local child: name, description, inline prompt, independent tool/skill selection and optional model/temperature/topP/iteration overrides. `validated` rejects blank identity/description/model/tool names and skill IDs, nonfinite temperature, nonfinite/out-of-range topP and nonpositive iteration overrides. Its derived JSON codec represents data; decoding does not validate it. An empty prompt uses worker-role guidance alone.

`factory(construction, parentConfig, parentModel, inherited, initialize, register, client)` validates and composes through the supplied SubagentConstruction without acquiring resources or running callbacks. It replaces child identity/description/prompt, appends delegated-task guidance, and copies only explicit overrides. Other AgentConfig and LlmConfig fields remain inherited, including timeout, concurrency, endpoint and credentials. The parent's system prompt is not copied. `generalPurpose` is an explicit preset through this same path; it installs nothing in the parent. No model lookup or provider-specific temperature range is imposed.

Final tool selection includes inherited and registered tools under the ancestor boundary. `AllAvailable` inherits available tools; `Only(Set.empty)` permits none. The independent `skills` selection applies to the catalogue captured by SubagentConstruction: unknown IDs fail before installation, and selected descendants cannot regain omitted entries. Required runtime controls must still satisfy the final static tool boundary. Declarations cannot disable live ancestor Deny/Plan checks or inherit approval authority. Callbacks remain trusted scoped host capabilities; supply fresh authorizer/Plan state per build for isolation. Plan tools, review delivery and nested controls require explicit installation.

The resulting factory works with existing fresh-child registries and retained sessions. Declaration names are descriptive configuration, not session credentials or reuse keys. Migration: defaulted skills and topP preserve ordinary construction and missing-field JSON decoding. SubagentDeclaration has nine positional/Product fields; dependent binaries must be rebuilt. topP=None inherits the parent value; request projection None instead clears it. The [current topP contract](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/client/README.md#optional-nucleus-sampling) owns exact identities and migration. Dynamic named-session creation, workspace policies, variant options, structured-output execution and remote/durable declarations remain separate work.

## Inline declaration parsing

`SubagentDeclarationParser.parse(name, markdown, limits)` returns `IO[AgentError, SubagentDeclaration]` and validates the existing model before returning it. The host supplies the name; the parser reads no files and installs no factory, catalog or session. The appended declaration field follows the migration above; parser entry-point arguments are unchanged.

The supported Markdown/YAML profile accepts required nonblank `description`, optional `model`, `temperature`, `top_p` or `topP`, `tools`, `skills`, `steps` and deprecated `maxIters`. Both topP aliases cannot appear together, including explicit-null values. Scalars are textual. Missing optional fields and plain YAML null spellings inherit; quoted `null` is literal text. Temperature must be a finite decimal number; topP must additionally lie in [0, 1]; iteration overrides must be positive decimal Int values. Both iteration aliases are validated even when `steps` wins. Omitted iterations inherit the parent, rather than introducing a parser default.

Tools and skills accept independent scalar comma-separated lists or scalar sequences. Omitted/plain-null selections inherit; an empty sequence or empty quoted scalar selects none. Unlike the upstream declaration empty-list fallback, an explicit empty skills list cannot restore all parent skills. Names are trimmed and deduplicated, with blank entries rejected. Duplicate/unknown fields, nested structures, anchors/aliases, explicit tags and YAML version/tag directives are rejected. Unsupported workspace, permissions and variant fields cannot silently grant or discard configuration.

Opening and closing delimiters are column-zero `---` lines with optional trailing spaces/tabs and LF or CRLF; a delimiter may end at EOF. A BOM is rejected. The prompt body after the closing line ending is preserved exactly, including blank lines and trailing whitespace. Positive limits default to 128,000 UTF-16 code units for the whole document and 16,000 for the header excluding delimiter lines. Limits are checked before YAML decoding. SnakeYAML Engine 3.1.1 event iteration is suspended in ZIO and translated through immutable state, without object loading. Syntax failures become bounded InvalidConfiguration diagnostics. This is a supported profile, not full YAML compatibility; the engine may ignore reserved directives that its public event API does not expose.

- [x] `path_review` and `current_docs` approved the current topP extension across declarations, parsing and runtime projection. Existing parser tables verify alias/null conflicts and bounds; actual child requests verify inherited and overridden settings. Exact twelve-file identities and full top-p-verify.log verification are in the [core contract](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/client/README.md#optional-nucleus-sampling). Existing tool/skill selection and body-preservation contracts remain in scope.
- [x] `subagent-declaration-parser-red.log` reproduced two failed groups against parser blob `8ffa283fef78d674023cfe230debbd15088bd027`: bare-CR/NEL/LS/PS boundaries were accepted as closing markers, and valid CRLF body equality failed. One group passed; the same expectations pass after the explicit LF/CRLF correction.
- [x] Bounded local discovery/loading is available through the separately reviewed adapter below; named-session tools are separately reviewed below.

| Reviewed file | Git blob |
| --- | --- |
| [SubagentDeclarationParser.scala](SubagentDeclarationParser.scala) | [Current topP scope identity](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/client/README.md#optional-nucleus-sampling) |
| [SubagentDeclarationParserSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentDeclarationParserSpec.scala) | [Current topP scope identity](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/client/README.md#optional-nucleus-sampling) |
| [build.sbt](../../../../../../../../../../build.sbt) | `87773171635d77e6fe408aab5531d6554f8e1c7e` |

## Local declaration loading

[LocalSubagentDeclarations.load](LocalSubagentDeclarations.scala) returns `IO[AgentError, Chunk[SubagentDeclaration]]` from one existing directory and an explicit `WorkspaceTrust.HostControlled` acknowledgement. `Untrusted` is rejected before filesystem access. This additive adapter only reads declaration data: it acquires no writer lease, writes nothing and installs no factory, catalog, session or execution capability. Existing parser and construction APIs are unchanged.

Discovery is nonrecursive. Exact lowercase `.md` entries are sorted by filename; the filename without that suffix supplies declaration identity. Defaults count every direct entry toward `maxEntries = 1024`, permit at most 64 selected declarations and read at most 512,000 bytes per document, with one additional byte used to detect overflow. Positive parser limits also apply (128,000 document and 16,000 header UTF-16 code units). Strict UTF-8 decoding rejects malformed input. Every selected file must validate; missing directories, scan/read/parse errors and exceeded bounds fail the whole batch instead of returning partial results.

Traversal components are rejected before canonicalization. Both lexical and canonical paths are screened for sensitive locations; the root must be a non-symlink directory, and selected entries must be non-symlink regular files directly inside its canonical directory. Host-controlled ancestors are required throughout the operation. These checks are advisory, not an atomic sandbox against replacement races. Directory and input streams have scoped release; each blocking operation is masked until it finishes, so cancellation waits for that operation and cleanup without a deadline. No hostile/network-filesystem responsiveness or snapshot consistency is promised.

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants at the exact source/spec identities below, with no findings. Full `scalafmtAll scalafmtSbt verify` passed in `local-subagent-declarations-verify.log`, with zero compiler warnings or failures; current totals are in the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).
- [x] Three grouped tests establish suspended sorted discovery and exact parsed values; trust/path/nonregular rejection and whole-batch recovery; scan/count/byte/parser bounds, strict UTF-8 and resource release.
- [ ] Add runtime symlink and held-I/O cancellation integration coverage. Symlink preflight was rejected by automatic execution policy, not demonstrated to be unsupported by the OS; no runtime symlink test or reproduced-defect claim is made.

| Reviewed file | Git blob |
| --- | --- |
| [LocalSubagentDeclarations.scala](LocalSubagentDeclarations.scala) | `9fb5713fb50778e5ea5135b7f65d1a8c290fadae` |
| [LocalSubagentDeclarationsSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/LocalSubagentDeclarationsSpec.scala) | `ee16735690e57793ec281df869f21f70836d433d` |

The pinned Java `AgentSpecLoader.loadFromDirectory`/`loadFromFile` source supplies the sorted, direct-child, filename-identity adaptation. Scala intentionally rejects missing directories and any selected-file failure instead of returning empty or silently skipping entries, and rejects selected directories/symlinks. No corresponding standalone upstream test method is claimed closed; filesystem namespaces and workspace/skills configuration remain separate.

## Policy-aware child construction

[SubagentConstruction.root](SubagentConstruction.scala)(spawner, boundary, parentPolicy, parentMode) creates a trusted host construction capability without reading the supplied live effects. Its `factory` accepts ordinary config/model/inherited tools and child boundary, a scoped `initialize` effect returning `SubagentPermissions`, a registration callback receiving the derived child construction capability and permissions, and a deferred client callback. `SubagentPermissions` contains the child's InteractivePermissionAuthorizer, PlanSessionControl, trusted PlanToolAccess map and explicit SubagentPlanAccess mode; these live capabilities have no codecs or parent reviewer authority.

Each build runs permission initialization before registration in the same owned Scope. The host supplies fresh child authorizer/Plan capabilities per build when isolation is required. Registration can install tools from the child's own controls and construct descendants through `child.factory`; it is responsible for any PlanTools, request prompt or review-delivery installation. The common build stage validates and filters inherited/registered tools before client acquisition, preserving the raw factory order and cancellation/resource cleanup.

The derived capability carries the effective static boundary, ancestor live denial readers plus the child's current policy, and the effective live Plan mode. Further `narrowed` and `inheritingParentDenials` factory derivatives propagate through this path. Child authorization preserves its own interactive policy/confirmation authority; added Plan admission uses `withAdditionalGate`, while parent Deny wrappers recheck original and approved arguments. Reused sessions retain these live readers rather than copying policy or approval state.

Effective Plan is the live OR of ancestor and child modes. An ancestor returning to Build removes only its restriction; the child's own Plan remains until its own review. Neither Plan control is mutated, no historical parent denials accumulate, and reads are decision snapshots rather than atomic revocation. Only construction through the derived `child.factory` path inherits these restrictions automatically. Public raw spawners, `SubagentFactory.make` and factories used under another parent remain explicit trusted-host capabilities; model input cannot select a new root. Inline parsing and general-purpose factory composition are available; read-only local discovery is available above, and named-session tools are available below.

### Admitted-tool Plan access

SubagentPermissions.planAccess defaults to ExplicitAccess, preserving closed-map Plan admission. AdmittedTools explicitly enables fallback to the runtime-sampled Tool.isReadOnly capability for names absent from the host access map. Explicit host classifications still take priority; read-only eligibility abstains rather than granting Allow. The live ancestor/child Plan OR and ordinary child policy/confirmation remain authoritative. Each child initialization chooses its mode; no parent approval or permission grant is copied.

Factory parent-denial wrappers forward the captured admitted-access value to child authorization while checking original and approved input against live ancestor policies. Legacy one-argument callers supply None. This carries trusted invocation data, without accepting model-supplied metadata, rediscovering tools or changing static filtering. Skill catalogue/group inheritance remains separate.

Migration: the appended defaulted field preserves ordinary SubagentPermissions construction; positional patterns/Product arity change and dependent binaries must be rebuilt. These are live host capabilities, without new wire codecs.

- [x] `registry_contract` approved FP/ZIO correctness and `current_docs` approved invariants for the current SubagentFactory, SubagentConstruction and their paired specs at the four identities in the [acceptance table](#verification-and-acceptance), with no findings. The existing confirmation table now verifies exact evidence through nested wrappers; one new retained-child group checks default/opt-in behavior, live Plan and host/parent denial. Focused `child-access-test.log` passed 23 tests in 515 ms. Full `child-access-verify.log` passed formatting, fatal-warning compilation and all tests, with zero compiler warnings or failures; the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance) owns current totals.

### Child Plan installation

SubagentPermissions appends planInstallation: SubagentPlanInstallation, default Disabled. Enabled(publisher) installs PlanTools using that child's control and the host-selected publisher, plus PlanInputTransform.inherited for request-only context. The derived ancestor/child gate remains a live OR; approval is child-local and cannot lift an ancestor restriction. Raw SubagentFactory.make retains identity input projection and existing registration behavior.

The final static boundary must admit all three controls before registration runs. Checked toolkit assembly rejects collisions before client acquisition. Default PlanTools.access entries are overridden by explicit host classifications; ordinary permission Deny still wins. Controls remain ordinary grouped tools: installation does not activate groups or guarantee visibility. Prepared state adds no fibers, reviewer ownership or storage acquisition; hosts retain borrowed dependency lifetimes.

Migration: existing SubagentPermissions constructor calls retain Disabled behavior; positional patterns/Product arity now include its fifth field, and method values/dependent binaries may require recompilation. No binary compatibility is promised. There is no serialized capability reconstruction.

- [x] `registry_contract` approved FP/ZIO correctness and `current_docs` approved invariants for current factory/construction identities in the [acceptance table](#verification-and-acceptance) and the spec below, with no findings. The [Plan acceptance](../plan/README.md#child-installation-acceptance) records the other two identities and full `child-plan-verify.log` verification. Actual declared/general-purpose runs preserve local draft review/location and deny mutations under ancestor Plan; named sends retain one runtime/history and live restrictions without copying mode flags. Stored fixture evidence uses an in-memory store, not a real plan file.

| Reviewed file | Git blob |
| --- | --- |
| [SubagentPlanInstallationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentPlanInstallationSpec.scala) | `fb8c7ddbde20714add34d47e77dec43f0e09632b` |

### Child skill snapshots

The deferred-resource extension widens the existing skills parameter to SkillCatalog | SkillLibrary. Library selection narrows definitions and exact readers together before registration; derived descendants retain only that narrowed capability. Existing static loader/reset checks still use the selected catalogue and final tool boundary. It creates no readers from paths, copies no broader root capability, and does not revive excluded skills. Ordinary catalogue source calls remain valid; widened signatures require rebuilding dependent binaries. `registry_contract` approved FP/ZIO correctness and `current_docs` approved invariants for the updated factory/construction/source-spec identities in the acceptance table, with no findings; full verification passed. Unchanged factory tests retain their prior identity. Combined `skill-library-final-verify.log` verification and all ten-file scope references are in the [core capability acceptance](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/skill/README.md#deferred-resource-capabilities). The extended nested runtime fixture observes only second/first/first reader effects; excluded skills cannot run their readers.

SubagentConstruction.root accepts an explicit SkillCatalog or SkillLibrary and optional immutable ToolGroups snapshot. Each factory selects AllAvailable from that captured catalogue or Only(ids); Only(empty) selects no skills. Blank or unavailable IDs fail before callbacks. The derived descendant construction carries only the selected catalogue, so further selection cannot recover an excluded skill. This is explicit host configuration, without parent-runtime lookup, repository discovery or declaration-file parsing.

SubagentFactory.make accepts the same default-empty/None dependencies; narrowed and inherited-denial derivatives retain them. Nonempty catalogues require the loader control; any Meta group requires the reset control. Group bindings and required controls must satisfy the final static tool boundary, including late factory narrowing, before build callbacks run. Control-name collisions in the filtered toolkit fail before client acquisition. Invalid configuration is rejected instead of widening the boundary.

Group activation selects only surviving Tool values; group membership never restores an excluded base tool. Skills do not grant authorization, and group visibility remains separate from skill-content selection. The existing ReAct runtime owns fresh invocation activation and ordinary tool permission checks; reusable history does not make activation persist between public runs. Raw host spawners, factories and fresh root capabilities remain outside automatic descendant narrowing.

Migration: appended defaulted arguments preserve ordinary call sites; downstream binaries must be rebuilt. The typed selection enum has a codec, but decoding it cannot reconstruct a catalogue or child capability. Inline/YAML declarations now project their independent skills selection through this same checked construction path.

- [x] `registry_contract` approved FP/ZIO correctness and `current_docs` approved invariants for the original catalogue-selection scope, plus the [catalogue selection identities](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/skill/README.md), with no findings. One pure selection group and two child integration groups cover narrowing, control admission, exact fixed arguments and fresh activation. Focused `child-skill-core-test.log` passed five tests in 261 ms; `child-skills-focused.log` passed 18 in 610 ms. Full `child-skills-verify.log` passed with zero compiler warnings or failures; current totals belong to the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).

## Reusable child sessions

`SubagentSession.open(parent, factory, maxTracked = 256)` returns scoped `TaskResourceAdmission[SubagentSession]`. It validates positive record capacity before acquiring resources, consumes one descendant depth and builds one configured runtime in the retained resource Scope. The supplied child spawner and all further descendants belong to the session's nested supervisor. Depth-four rejection precedes identity allocation and factory callbacks; closed ownership evaluates no initializer. Initialization failure joins acquired registration/client cleanup.

`session.send(input)` submits another ordinary turn to that runtime. `session.executions` exposes its bounded execution registry for observation, waiting, promotion, cancellation and completed-record retirement. Every accepted send receives a separate task ID; history, lineage, filtered toolkit, client and authorizer are retained. Sends do not consume another lineage depth. Running and queued turns count toward capacity. The runtime itself is not exposed for bypassing admission.

Turns are serialized, without a FIFO guarantee. Cancelling a queued turn reaches neither the model nor runtime history. Active cancellation completes runtime reconciliation and scoped cleanup before another turn enters. Installed parent-denial effects and plan gates are evaluated against current values on later turns; the session does not copy approval authority or install new policy automatically.

The host retains the returned TaskResource and its acquiring Scope. Closing it joins running, queued and promoted turns and further descendants before releasing registration/client resources. Promoted turns survive their individual caller Scope, not session closure. After closure, sends return Closed. Complete execution records remain inspectable through the retained registry; hosts manage retirement and retained result memory.

This API supports ordinary runs and explicit [host-only resume](#host-only-session-resume). Factories produced by SubagentConstruction retain inherited restrictions across turns. The directory below supplies host session-ID lookup; model-facing adapters expose send/task/cancel only.

## Host-only session resume

`session.resume(results = Chunk.empty, events = None)` submits an immutable resume command through the same bounded registry, caller lease and supervisor as `send`. Every accepted command receives a fresh execution ID; running and queued commands occupy retained capacity. The session permit serializes execution without FIFO ordering. The checkpoint is selected when the command acquires that permit, not when submitted: hosts must sequence confirmation delivery. This API supplies no checkpoint compare-and-swap, replay guard or durable ownership.

Core pending state is the sole continuation authority. Ordinary sends reject while it exists. Invalid supplied results and absence of a checkpoint fail the retained execution without changing history or consuming pending state; after a valid claim, interruption consumes that attempt, joins cleanup and reconciles unfinished local calls. Later fresh sends remain available after completion, Stop or interrupted recovery. Closed sessions reject resume admission just as they reject sends.

Resume retains the original checkpoint context, reply ID, iteration and usage, and uses the installed authorizer and trusted tool bindings. A host can supply checked external observations or install a live confirmation adapter through its factory; resume itself grants no approval. An optional event port is captured for this execution only. The shared core forwarding path prefixes events with the new task ID while preserving their original context and joins terminal delivery and cleanup. Public stream-consumer cancellation still abandons later output.

Migration: `resume` is additive; existing send, registry and model-facing tool signatures are unchanged. There is no model-facing resume tool or automatic policy installation; [host checkpoint import](#host-checkpoint-import) acquires a separate child resource. The core package-visible `resumeForwarding` shares the existing owned forwarding lifecycle; the registry's generic command entry point remains package-private.

| Reviewed source | Git blob |
| --- | --- |
| [ReActAgent.scala](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/ReActAgent.scala) | [Current numeric-integrity identity](../transcript/README.md#exact-owned-numeric-output) |
| [SubagentFactory.scala](SubagentFactory.scala) | `013920f0ad1ab6c082e3362bb40f83064d11af51` |
| [SubagentSession.scala](SubagentSession.scala) | [Current checkpoint-import identity](#host-checkpoint-import) |
| [SubagentTaskRegistry.scala](SubagentTaskRegistry.scala) | `a0e302022db3f9666d0c5c3beab326ec5a424516` |
| [SubagentSessionSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentSessionSpec.scala) | [Current checkpoint-import identity](#host-checkpoint-import) |

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for these five identities, with no open findings. Two new grouped session tests cover actual partial confirmations and supplied completion, repeated IDs, invalid/no-pending/closed outcomes, queued cancellation, held cleanup defects, original-context terminal forwarding and fresh-send reuse. Focused `child-resume-final-focused.log` passed nine core tests in 633 ms and fifteen harness tests in 603 ms.
- [x] Full `scalafmtAll scalafmtSbt verify` passed in `child-resume-verify.log`, with zero compiler warnings or failures. Current totals and environment are in the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).
- [x] Independently review [host checkpoint import](#host-checkpoint-import) into a new child resource; its verification gate is recorded below.

## Host checkpoint import

`SubagentSession.openRestored(parent, factory, checkpoint, maxTracked = 256)` acquires a new caller-owned child resource and imports a host-supplied suspended AgentResult. Capacity validation and existing descendant/closed admission precede factory initialization. The current factory acquires its registrations and client under the child owner, then core `restore` checks the suspended state, history/occurrence ledger, host iteration budget and exact rebuilt tool definitions before the session registry is published. Acquisition can perform host effects; importing the checkpoint starts no model/tool execution and creates no execution record. The new child has fresh lineage and resources; the checkpoint does not restore old task IDs or owners.

The resulting session uses ordinary host `resume`, preserving completed and supplied observations and the original checkpoint context. Only unresolved local calls reach the current bindings and installed authorization, including current inherited parent denials. Tool-definition equality checks compatibility, not approval. A failed import joins acquired cleanup before returning its full typed/defect cause; the caller lease retains its existing joined-close replay contract. Closing the original session or importing the same data elsewhere is not an exclusive claim.

Migration: `openRestored` is additive; `open`, retained catalog construction and existing model-facing adapters retain their behavior. The host owns checkpoint authentication, storage, exclusive delivery and side-effect idempotency. This is a scoped import API, not a durable database, automatic session-key restoration or model-facing control.

| Reviewed source | Git blob |
| --- | --- |
| [SubagentSession.scala](SubagentSession.scala) | `3f3ef7a63508f40dd896376fc04dcf13b10d12f2` |
| [SubagentSessionSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentSessionSpec.scala) | `bb529004fb95d37ad6b852eddefc1311b4072963` |

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for these two identities, with no open findings. Two new grouped tests cover JSON-restored partial/supplied observations with current parent denial, fresh lineage, empty records and no execution before resume; invalid state/changed bindings, held cleanup, initialization cause and exact caller-close defect replay. Focused `child-restore-final-focused.log` passed fifteen tests in 885 ms.
- [x] Full `scalafmtAll scalafmtSbt verify` passed in `child-restore-verify.log`, with zero compiler warnings or failures. Current totals and environment belong to the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).

## Bounded host session directory

`SubagentSessionDirectory.open(maxSessions = 64)` acquires a host-scoped directory and rejects nonpositive capacity. `register(resource)` accepts an already acquired `TaskResource[SubagentSession]`, returning Accepted(directory-local UUID ID), Capacity, Closed or IdentityCollision. IDs describe membership independently of execution IDs; they carry no durable or authentication authority. Registration adds a closure obligation without transferring ownership. Original acquiring scopes and supervisors still bound the session lifetime, and every rejected registration leaves the resource untouched.

Aliases are permitted and each retained ID counts toward capacity. Removing one alias closes their shared resource; other aliases may still be listed and return the now-closed session. `lookup` returns only ready members while the directory is open, without promising liveness after an external owner closes a resource. `list` reports all retained IDs, including removal/shutdown entries awaiting cleanup. Membership is process-local and the count bound does not bound session history or result bytes.

`remove` hides the member before joining its resource cleanup and retains capacity until cleanup completes. Overlapping removals join the same cleanup outcome; missing IDs return Missing, successful removal returns Removed, and cleanup defects remain defects. The entry is retired even if cleanup defects. Explicit directory close and its host finalizer share closure completion, reject new registration/lookup, close retained resources in parallel and join all cleanup before clearing membership. Closure and removal wait outside the admission permit. Session workers/finalizers must not remove themselves or close their own directory.

The host selects which resources to register and which returned capabilities to distribute. The directory itself supplies no session construction, ownership transfer, durable restoration or automatic policy installation. The explicit model adapter below routes only within its membership; existing fixed-session toolkits remain unchanged.

## Live parent-denial inheritance

`inheritingParentDenials(readParentPolicy: UIO[PermissionEngine])` returns an immutable factory derivative. The supplied policy effect runs only during authorization. It reads parent policy before delegating to the child's existing authorizer, then reads again against the exact `Approved(input)` returned by that authorizer. A matching parent Deny produces a Denied observation without execution. All other parent decisions abstain; the child's Allow, Ask, Deny, confirmation requests and confirmed rules remain its own. Call identity is unchanged, and the parent's authorizer or approval request service is never invoked or copied.

Hosts can supply the existing interactive authorizer's effectful policy read. This inherits rules from that engine, not external parent tool/interceptor gates, permission modes or working directories. `narrowed` preserves the wrapper; multiple inherited wrappers retain their separate denial checks. Provider defects fail closed through the existing ReAct authorization boundary, while interruption remains cancellation and joins scoped cleanup.

Positive child confirmation is checked against both modified arguments and a fresh parent policy read. The final read is the decision point; later updates do not atomically revoke already returned approval. Live replacement does not accumulate historical parent rules, and confirmed child rules may remain installed even when the final parent check denies execution. Durable policy history and execution transactions remain host integration work. Spawn/send tools retain the configured factory/session; automatic descendant inheritance requires the SubagentConstruction path above.

## Bounded task registry

`SubagentTaskRegistry.open(parent, factory, maxTracked = 256)` acquires a registry in a host Scope and requires positive capacity. Each `submit` creates a fresh configured child through the retained factory and parent spawner. Accepted submissions return a registry-local UUID task ID; other outcomes are Capacity, Closed or IdentityCollision. Depth rejection remains a typed spawn error. Rejected admission acquires no child factory resources and retains no record. IDs identify execution records within one registry; they are not durable sessions or standalone authority.

Capacity counts every retained record, including completed results. `forgetCompleted` removes only terminal work, returning Removed, Running or Missing. This frees record capacity without restarting or replacing a child. The bound is a record count, not a bound on the bytes retained by full AgentResult histories and causes. Hosts must retire completed records according to their retention needs.

`inspect` and `list` observe Running or Completed with a complete Exit, preserving typed failures, defects, interruption and cleanup causes. A list captures membership once and polls each entry independently; it is not an atomic completion snapshot. Lookup-based await, promotion and cancellation return None for missing IDs and otherwise delegate to the existing task handle. They do not hold the admission permit while waiting or joining cleanup. Completed records remain inspectable after registry closure until explicitly forgotten or the registry becomes unreachable.

Each submission owns a private caller cancellation lease. Admission records the task before attaching that lease's finalizer to the caller Scope, outside the admission permit. An already closed caller can therefore join its child cleanup without hiding the retained record or blocking unrelated capacity checks. Closing a normal caller cancels and joins attached work; promoted work survives caller closure. Closing the registry first closes admission, then cancels registered tasks in parallel and joins cleanup, including promoted tasks, even if the underlying supervisor outlives the registry. Public `open(parent, factory)` creates fresh children; the session uses a package-private launcher to retain its runtime. Neither registry provides remote ownership or durable restart recovery.

## Completion delivery inbox

SubagentTaskRegistry.pendingDeliveries returns SubagentTaskDelivery(id, exit) for unacknowledged, fully joined foreground and promoted tasks in submission order. It captures membership and acknowledgement once, then polls completion independently without holding admission. Running tasks and tasks still cleaning up are excluded. The complete Exit retains successful AgentResult values, typed failures, defects and interruption. This is a snapshot, not a claim: concurrent acknowledgement or forgetting can leave a stale returned delivery, and multiple consumers may receive duplicates.

acknowledge(id) returns Acknowledged, AlreadyAcknowledged, Running or Missing under the existing gate. It retains the task result and capacity; only forgetCompleted discards the record and its delivery. Cancellation may lose an acknowledgement receipt, so retry can report AlreadyAcknowledged. Reading and acknowledging retained terminal work remain available after registry closure. Failed or cancelled host delivery does not implicitly acknowledge anything.

The host owns transport, authentication, retries, deduplication and the decision to acknowledge. No new worker, Hub, execution owner, automatic push or event-stream replay is installed. Retention bounds record count, not payload bytes. Migration is additive: custom registries inherit typed InvalidConfiguration failures for both operations until explicitly implemented; no fake empty inbox is returned. These live outcomes have no new durable codec or exactly-once guarantee.

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for the current registry/spec identities in the acceptance table, with no findings. Focused `task-delivery-focused.log` passed 27 tests in 1.788 seconds. Full `scalafmtAll scalafmtSbt verify` passed in `task-delivery-verify.log` with zero compiler warnings or failures; current totals and verification settings are in the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).

## Model-facing task controls

[SubagentTools.make](SubagentTools.scala) returns an immutable opt-in Toolkit with `agent_spawn`, `agent_task` and `agent_cancel` bound to one registry. `SubagentTools.forSession(session)` instead supplies `agent_send`, `agent_task` and `agent_cancel` bound to that session's execution registry. Both invocation tools accept exactly one string field, `prompt`. Inspection and cancellation accept exactly one string field, `task_id`, using canonical hyphenated UUID syntax. Missing, duplicate, extra or wrong-type fields are rejected. Model input cannot select another factory, parent, depth, timeout or session. Only inspection is marked read-only; trusted host plan classifications still control eligibility.

By default, `agent_spawn` submits a fresh child and waits through the existing 30-second promotion budget. Its invocation owns a scoped caller lease. Completion returns the retained ID and result projection; promotion returns that same ID while the same execution continues. Caller cancellation before promotion interrupts the wait and closes the attached lease, joining child cleanup. Committed promotion survives that tool invocation, so the host must keep both registry and supervisor Scopes alive for the desired background lifetime. Either owner closing still stops its owned work.

`agent_send` uses the same scoped submission/wait protocol for another turn on its bound session. It retains history and receives a distinct execution ID. Under the default policy, waiting turns can be promoted after the same 30-second budget without starting another runtime. Caller interruption joins the attached turn's cleanup; cancelling a queued turn does not append its input. Task cancellation leaves the session available for later turns. Session closure still ends its retained resources and rejects further sends.

`agent_task` polls retained status without a model request or restart. `agent_cancel` waits for task cleanup and leaves the terminal record inspectable. Completed results remain unchanged by cancellation. IDs from another registry are Missing. The make and forSession variants retain their existing input and result shapes. Toolkit variants share tool names and are alternatives; hosts must select the intended variant rather than merge colliding definitions.

Spawn/send rejection, missing IDs, child failure and a child's interrupted or Stopped result become Error observations through the standard tool boundary, naming the invoked tool. Interruption of the calling fiber remains cancellation, not a manufactured success or Error reply. Inspecting or cancelling an existing failed/interrupted child is a successful observation of that terminal state. Task observations expose IDs and Running, Promoted, Completed, Suspended, Stopped, Failed, Interrupted or Missing projections, never task handles; synchronous timeout has its separate ID-free variant below. Stopped exposes only the bounded reason; the registry retains the complete AgentResult. Exhaustive SubagentToolState matches and wire consumers must accept the additive Stopped variant. Typed failure text is retained; defect-only detail uses a generic message.

Reply and failure text previews are bounded to 4,000 UTF-16 code units, including a head/tail separator; truncation avoids splitting surrogate pairs. `omittedCharacters` counts omitted original UTF-16 units, excluding the separator. JSON framing is outside this text bound. The registry retains the complete original Exit and AgentResult, so bounded tool output does not bound retained memory.

The host can install [PlanPermissionGate.inherited](../plan/PlanPermissionGate.scala) in the child's authorizer to restrict admission whenever the live parent or child mode is Plan. This adapter changes neither session nor review authority, and it adds no default exemption for spawn/send/task/cancel names. SubagentConstruction installs equivalent live admission and propagates restrictions through its derived factory path; opt-in child-local Plan tool/context installation is available through the [explicit construction setting](#child-plan-installation). Inline declarations use this construction path.

## Host execution policy

[SubagentExecutionPolicy](SubagentExecutionPolicy.scala) is trusted host configuration for spawn/send. All five toolkit builders retain their original one-argument overloads and default PromoteAfter30Seconds behavior. Their new two-argument overloads accept Synchronous(timeout); inspection and cancellation are unchanged. Model schemas expose no timeout or policy field, and routed observations retain their selected agent/session identity.

`SubagentSyncTimeout.fromDuration` checks a positive budget of at most ten minutes. Its checked JSON codec uses integer nanoseconds and rejects zero, nonintegral values, oversized values and nonnumeric input; it does not coerce or clamp invalid settings. The wait budget starts after accepted submission, not before admission, and cleanup may exceed it.

Synchronous execution uses the existing retained registry record without promotion. Completion returns the ordinary task projection. A winning timeout cancels the same execution and joins its owned cleanup before returning an Error containing `SubagentToolResult.TimedOut(message)`, with no task ID. Timeout diagnostics use the existing bounded text projection; the complete child Exit stays in the host registry and still counts toward capacity until forgotten. Concurrent completion during cancellation does not convert the winning timeout to success. Caller interruption remains interruption and retains child cleanup failures, including the admitted-ID handoff; pre-admission waiting remains interruptible.

Migration: existing one-argument calls, method values and JVM method shapes remain available. Opt-in callers supply a checked policy explicitly. Exhaustive SubagentToolResult matches and strict wire consumers must accept the additive TimedOut variant. No model timeout coercion, ambient RuntimeContext flag, separate background repository or automatic completed-record retirement is added.

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for the four policy/tool identities in the shared table below, with no findings. Focused `subagent-sync-focused-final.log` passed 76 groups in 1.844 seconds.
- [x] Full `scalafmtAll scalafmtSbt verify` passed in `subagent-sync-verify.log`: 605 core / 698 harness / 83 MCP tests (1,386 total), compile task 2 seconds and test task 15 seconds, with zero compiler warnings or test failures. The [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance) owns current repository totals.
- [x] `subagent-sync-handoff-red.log` recorded eight passing groups and one failure in 713 ms against draft Tools `a8ec6cdc2922fb231e9c08078401f2e8b5cb98c2` and spec `b0c9078e1b9484c01685455bb868b02d2077d4d1`: caller interruption lost the expected cleanup defect while the retained child Exit assertion passed. The outer restored-submission mask preserves the admitted ID for cancellation joining. The completed-result interruption handoff was additionally hardened by inspection; no separate RED is claimed for it. Numeric decoding bounds were corrected during draft review, not from a reproduced behavioral failure.

## Host factory catalog

[SubagentCatalog.open](SubagentCatalog.scala)(parent, entries, maxTrackedPerAgent = 256) validates nonblank unique entry names, nonblank descriptions and positive capacity before acquiring existing task registries. Entries pair trusted display metadata with a factory; they have no codec. Membership is immutable, and all entries retain the supplied parent capability. Opening acquires no child/client. Each spawn builds a fresh runtime. Host `lookup` exposes its registry, including explicit completed-record retirement.

Capacity counts retained tasks per entry; total records are bounded by the finite entry count times that capacity, not by a byte limit. Scope closure closes entry registries in parallel and joins their tasks, including promoted work. There is no additional supervisor or lifecycle state machine. Retain the catalog and parent owner scopes for background execution; owned tasks must not close their own enclosing owner.

`SubagentTools.forCatalog` installs `agent_spawn(agent_id, prompt)`, `agent_task(agent_id, task_id)` and `agent_cancel(agent_id, task_id)`. Definitions list host-selected names/descriptions. Strict two-string inputs reject extra fields; task IDs remain confined to the selected registry. Results wrap the selected agent and bounded task observation. Unknown entries and typed admission failures, including depth rejection, retain the selected identity as Error observations. Invalid input and unexpected defects use ordinary Toolkit diagnostics. The selected [host execution policy](#host-execution-policy), bounded text and joined cancellation apply; the default retains 30-second promotion.

This additive adapter changes no fixed-registry, session or directory call sites. Catalog names are exact host keys, not runtime IDs or credentials. Factories determine child policy; compiling them through SubagentDeclaration/SubagentConstruction preserves inherited restrictions, while raw host factories remain trusted capabilities. There is no dynamic membership, hidden/primary mode, context-selected manager, implicit declaration loading, reusable-session key or durable recovery. Scoped event forwarding follows the execution contract below.

## Directory-bound model routing

`SubagentTools.forDirectory(directory)` supplies `agent_send`, `agent_task` and `agent_cancel` within one host-selected directory. Send requires exactly two string fields, `session_id` and `prompt`; task inspection/cancellation require exactly `session_id` and `task_id`. IDs require canonical UUID shape. Missing, duplicate, extra or wrong-type fields are rejected before lookup or work. The host supplies known membership IDs to the model; the toolkit has no discovery, construction, registration, removal or directory-closure tool.

Each invocation looks up a ready membership and then uses that session's execution registry. [SubagentSessionToolResult](SubagentTools.scala) wraps accepted routing as `Session(sessionId, result: SubagentToolResult)` and missing membership as `Missing(sessionId)`. Rejections and child failures retain the selected session in the wrapped Error observation; a task ID from another session remains Missing within the selected session result. Caller interruption remains interruption. Fixed-registry and fixed-session result shapes are unchanged.

Lookup is a membership snapshot, not atomic revocation. Removal or closure hides subsequent lookup and joins owned cleanup, but an invocation that already obtained the session can race with closure. Original resource owners still bound lifetime; membership may refer to an externally closed session, whose send returns a wrapped Closed rejection. Routed sends use the selected host execution policy and joined task cancellation; inspection/cancellation do not make another model request. Installing routing grants no new child tools, policy inheritance or approval authority. The host retains the directory and original resource/supervisor scopes for intended background work.

## Scoped child event forwarding

Model-facing spawn/send tools propagate the current `ToolInvocation.Active.events` through fixed registries, catalogs, bound sessions and directories. Each registry allocates its execution UUID before launch and uses it as the event source prefix; nested child sources append with `/`. Names and model call IDs do not supply this identity. Child request/reply correlation remains unchanged, parent events retain their own source, and child lifecycle events come from the actual runtime rather than synthetic duplicate bookends.

Factory and session workers use the core [direct forwarding primitive](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#direct-event-forwarding) within existing supervisor ownership. Registry snapshots retain complete results and failure causes. Each session send captures a fresh port; neither the shared session nor factory stores an invocation destination. Inspection/cancellation does not attach a new destination or replay events.

Returning Promoted after the existing 30-second wait ends the tool invocation and revokes its publication lease. The supervised child continues once and retains its outcome; later events are dropped. Live event forwarding has no replay, acknowledgement or backpressure; the separate completion inbox acknowledges retained terminal outcomes only. Cancelling a public stream consumer still abandons subsequent output, even though owned cancellation joins cleanup. A separately retained active observer does receive the child lifecycle End after parent execution is interrupted and child cleanup joins. The actual nested fixture verifies exactly one Start/End with the runtime task source and matching nonempty reply ID, child End before parent End, and the retained child interrupted Exit. The observer outlives the cancelled parent execution; this does not extend a cancelled public consumer's publication lifetime.

Migration: existing `submit(input)`, `send(input)` and factory spawn calls retain detached behavior. The additive registry `submit(input, events)` and session `send(input, events)` accept an optional host-held port. Existing custom registry implementations remain source-compatible through a detached default; they must override the new overload to support forwarding. Port possession is an explicit runtime capability, not serializable model input. Hosts remain responsible for retaining supervisor scopes for promoted work.

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for the updated SubagentForwardingSpec identity in the acceptance table, with no findings. Focused `child-cancel-events-focused.log` passed 12 tests in 612 ms. Full `scalafmtAll scalafmtSbt verify` passed in `child-cancel-events-verify.log` with zero compiler warnings or failures; the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance) owns current totals. Production code and APIs are unchanged.

## Named session catalog

[SubagentSessionCatalog.open](SubagentSessionCatalog.scala)(parent, entries, maxSessions = 64, maxTrackedPerSession = 256) returns scoped `TaskResourceAdmission[SubagentSessionCatalog]`. The host supplies a finite ordered set of `SubagentEntry` factories. Exact names/descriptions must be nonblank, names unique, capacities positive and entry count within the session bound before any acquisition. Installation eagerly initializes each session but makes no model request. Factory callbacks and raw factories remain trusted host capabilities; factories composed through SubagentConstruction retain their installed static and live policy restrictions.

One owned initializer acquires the entire catalog; any partial failure, defect, caller cancellation or owner closure joins rollback before returning. Its private context spawner preserves the parent lineage without allocating an identity or consuming depth. Each retained session still allocates one descendant UUID and passes the existing depth admission. The context supervisor is the sole automatic session-resource owner. Its private directory index manages membership without another automatic resource-close obligation, so aggregate closure joins nested work and resource cleanup without replaying the same defect through duplicate ownership edges.

`entries` remains the immutable descriptive input. `lookup(name)` resolves current membership to a session ID and its initialized session; repeated lookups reuse that runtime/history, while different names and catalogs have isolated sessions. `remove(name)` hides the entry, joins its existing resource closure and never recreates it. Lookup is a snapshot, not an atomic liveness guarantee: aggregate closure may leave membership visible while cleanup is underway, then closes the index. Existing task retention bounds apply per session; completed records still require host retention policy.

Close the returned resource to close the aggregate, or end its acquiring caller/supervisor scope. Session workers and finalizers must not remove themselves or await their own catalog/resource closure. Model-facing named routing is an opt-in adapter below. The catalog adds no dynamic arbitrary labels, deterministic durable reuse keys, restoration or automatic policy installation through raw factories. Public SubagentSession.open and SubagentSessionDirectory.open retain their existing caller-lease and closure behavior.

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for the catalog, retained ownership kernel and paired evidence at the exact identities below and in the shared acceptance table, with no findings. Full `scalafmtAll scalafmtSbt verify` passed in `subagent-session-catalog-verify.log`, with zero compiler warnings or failures; current totals belong to the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).
- [x] Three catalog groups verify validation before acquisition, stable names/history and isolated sessions with unchanged depth; partial compound-failure/caller/owner rollback; and concurrent removal/aggregate closure with exact cleanup causes. One additional TaskResourceSpec group verifies retention past a short caller scope, later use, single physical cleanup, exact repeated cause replay and closed admission.
- [x] `subagent-session-catalog-red.log` recorded two passing groups and one failure against catalog `d2a1708ca1c4e1d79cf2bb8d59663e133abea425` and spawner `a2cfbbec9191e80a8be5bbbed1f56e5c4782b6ef`: resource and caller closure expected `Die(defect)` but received `Then(Die(defect), Both(Die(defect), Die(defect)))`, despite each client finalizer running once. The unchanged catalog spec below passes after removing duplicate automatic ownership edges; no Cause filtering or deduplication is used.
- [x] Named session tools are independently reviewed below.
- [ ] Define dynamic labels, durable keys/restoration and post-promotion event delivery separately; terminal outcomes have the completion inbox above.

| Reviewed file | Git blob |
| --- | --- |
| [SubagentSessionCatalog.scala](SubagentSessionCatalog.scala) | `0e2668dcf149eab0adfec29a43481b5b5430a82c` |
| [SubagentSessionCatalogSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentSessionCatalogSpec.scala) | `8b795d88b930efd93fc293913a42bb59725bb1fb` |

## Named session tools

`SubagentTools.forSessionCatalog(catalog)` adds opt-in `agent_send`, `agent_task` and `agent_cancel` bound to one host-installed catalog. Send requires exactly string `agent_id` and `prompt`; task/cancel require exactly string `agent_id` and canonical UUID `task_id`. Duplicate or extra fields are rejected. Exact names select current membership only: unknown or removed names return Missing, foreign task IDs cannot reach another registry, and no call creates or replaces a session. Tool descriptions expose the immutable entry names/descriptions, including entries subsequently removed.

The existing `SubagentCatalogToolResult` carries selected agent identity and bounded task observations; there is no new session-ID DTO. Typed execution errors retain selected identity, while malformed-input and defect diagnostics keep existing Toolkit normalization. Each accepted send reuses the selected runtime/history but gets a fresh execution ID and current invocation event port. The selected host execution policy and joined cancellation apply, with same-execution promotion by default; inspection/cancellation do not create a model request. Full results and cleanup causes remain in the selected registry.

Migration: this is an additive toolkit factory. `forCatalog` still spawns fresh children, and fixed-session/directory call sites are unchanged. The adapter grants no factory selection, dynamic labels, recreation, durable keys, restoration or new approval authority. Lookup remains a snapshot subject to owner closure.

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for the named-session tool scope (current tool identity is in the [host execution policy](#host-execution-policy) acceptance) and [SubagentSessionCatalogToolsSpec](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentSessionCatalogToolsSpec.scala) `a76f336edcf65df903aa4784eafd0ac6eed3d82a`, with no findings. Three grouped tests establish actual parent reuse with fresh event/task identities, strict selection/isolation/removal without work, and promotion with held cleanup cancellation and retained defect. The three existing fresh-catalog groups also pass.
- [x] Full `scalafmtAll scalafmtSbt verify` passed in `named-session-tools-verify.log`, with zero compiler warnings or failures; the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance) owns current totals.

## Verification and acceptance

SubagentFactory forwards Deferred through its existing live parent-denial wrapper without granting approval. The [partial-confirmation scope](../permission/README.md#partial-checkpoint-confirmations) retains its authorization contract; [host-only session resume](#host-only-session-resume) owns the current factory identity. Suspended sessions reject ordinary sends and require explicit host resume.

The [test index](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/README.md) maps current contract evidence and bounded upstream adaptations. The unchanged lineage and static boundary retain their recorded approvals; the supervisor's current resource extension is reviewed below.

`path_review` and `current_docs` independently approved policy-aware construction and its scoped dependencies. Scoped forwarding retains its approval for the factory and registry. The host execution policy section records current tool approval; the named-session routing contract retains its separately reviewed scope. Both reviewers also approved the session catalog and retained ownership changes to supervisor, spawner, session and private directory indexing at the exact identities below, with no unresolved findings. Public caller-leased ownership and public directory closure semantics remain unchanged; TaskResource changes only its ownership comment, with TaskResourceOwner code unchanged. Current factory/construction identities have the separate [child skill snapshot acceptance](#child-skill-snapshots), retaining their admitted-access contracts; static boundaries retain their existing scoped acceptance; inherited plan admission is recorded at the [plan admission identities](../plan/README.md#current-admission-identities). Current full verification is recorded in the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance).

- [x] Both reviewers approved scoped forwarding, runtime task attribution, per-send leases and unchanged ownership, including three grouped integration scenarios for nested events/results, promotion and session cancellation/removal. Full formatting, fatal-warning compilation and repository tests passed.

- [x] `registry_contract` approved FP/ZIO correctness and `current_docs` approved invariants for current declarations/parser and their paired specs, with no findings. Four declaration groups cover pure validation/lazy composition and codec migration, parsed skill selection reaching actual runtime dispatch, actual configuration/filtering and retained-session live policy/independent Plan review. The [parser acceptance](#inline-declaration-parsing) records current verification. Existing construction tests supply the unchanged nested/cancellation coverage.

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for the catalog, updated tools and three grouped integration tests at the exact identities below, with no findings. Existing fixed/session/directory paths retain their full-suite verification.
- [x] `subagent-catalog-red.log` reproduced selected-agent identity loss on depth rejection against tools blob `f005f40434e2fe5812235abfd7d08e0137f43110`: two groups passed and the strict-routing group failed because `depthPayload` was absent. The unchanged regression passes with bounded selected-agent failure projection.

| Reviewed source | Git blob |
| --- | --- |
| [SubagentCatalog.scala](SubagentCatalog.scala) | `fd4bb5846275c82ded15994123b299408f537e94` |
| [SubagentCatalogSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentCatalogSpec.scala) | `1659fa0d628ea35c1d394da92d953ec4ee391c21` |
| [SubagentDeclaration.scala](SubagentDeclaration.scala) | [Current topP scope identity](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/client/README.md#optional-nucleus-sampling) |
| [SubagentDeclarationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentDeclarationSpec.scala) | [Current topP scope identity](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/client/README.md#optional-nucleus-sampling) |
| [SubagentSessionDirectory.scala](SubagentSessionDirectory.scala) | `5f924361cc657e128abe057354aa260848d67035` |
| [SubagentSessionDirectorySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentSessionDirectorySpec.scala) | `3210d1e7f1e86fd65cd54727ebe287431cac0b94` |
| [SubagentSession.scala](SubagentSession.scala) | [Current checkpoint-import identity](#host-checkpoint-import) |
| [SubagentSessionSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentSessionSpec.scala) | [Current checkpoint-import identity](#host-checkpoint-import) |
| [TaskSupervisor.scala](TaskSupervisor.scala) | `3ae295da2603a952bc23b7b05d2546ed274cb600` |
| [TaskResource.scala](TaskResource.scala) | `ff8c69ba4d8c1623388325e4b2e33bd26df45314` |
| [TaskResourceSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/TaskResourceSpec.scala) | `b8c862182e0a625e7d6f83c0ac72c120f700e80b` |
| [SubagentExecutionPolicy.scala](SubagentExecutionPolicy.scala) | `df95802fbd98e3db0d5e64fe958f812b6f470003` |
| [SubagentExecutionPolicySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentExecutionPolicySpec.scala) | `ccab2966d1d868250b00eed9150138e3a5df69f1` |
| [SubagentTools.scala](SubagentTools.scala) | [Current checkpoint-pattern identity](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#before-acting-suspension-and-restoration) |
| [SubagentForwardingSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentForwardingSpec.scala) | `1af49c26a4586eab5d95bceacb5091cb089781d5` |
| [SubagentDirectoryToolsSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentDirectoryToolsSpec.scala) | `c661d08dd03006ef39f2c710f725432f34190018` |
| [SubagentToolsSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentToolsSpec.scala) | [Current acting-stop identity](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#acting-stop-decisions) |
| [SubagentTaskRegistry.scala](SubagentTaskRegistry.scala) | [Current host-resume identity](#host-only-session-resume) |
| [SupervisedTask.scala](SupervisedTask.scala) | `f3ddf1c653699ecd98cb021d4065608d561d591f` |
| [SubagentTaskRegistrySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentTaskRegistrySpec.scala) | `6670f66d25c04131fa7f5e50e982b0775142acc7` |
| [SubagentConstruction.scala](SubagentConstruction.scala) | `381703a6f1f92239110db9ce5bbec5d208047c52` |
| [SubagentConstructionSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentConstructionSpec.scala) | `1ece182d43038473274d5bfa5c4bb2c9c5c664c2` |
| [SubagentFactory.scala](SubagentFactory.scala) | [Current host-resume identity](#host-only-session-resume) |
| [SubagentFactorySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentFactorySpec.scala) | `e7c0567f3b7cd873cb5f6ff39042a99152464e87` |
| [SubagentToolBoundary.scala](SubagentToolBoundary.scala), unchanged accepted boundary | `f2e7df236e266050c733d1974bed02f42119efea` |
| [SubagentToolBoundarySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentToolBoundarySpec.scala), unchanged accepted boundary tests | `20d9c69ced9d6c68c6307ba0961a0b5703aeef1f` |

Inheritance through arbitrary raw host callbacks, model-selected session construction, external parent permission gates, durable historical policy accumulation and automatic event delivery after promotion remain outside this accepted scope. The host-resume tests cover ordinary-send rejection while suspended.

- [x] Verify scoped admission, cancellation leases, full-Exit observation, same-execution promotion and parallel shutdown with sequential worker cleanup.
- [x] Verify runtime-owned depth <=3, suspended identity/callback effects and actual nested ReAct reconciliation.
- [x] Verify and independently approve the configured factory and final static tool boundary baseline in `f92772b`.
- [x] Verify and independently approve bounded registry admission, inspection, retention and shutdown at the recorded identities.
- [x] Verify and independently approve live parent-denial inheritance without approval-authority copying at the recorded identities.
- [x] Verify and independently approve strict model-facing spawn, inspection and cancellation through the retained registry/factory.
- [x] Verify the host-installed inherited plan gate through configured-child execution without copying review authority.
- [x] Verify and independently approve shared resource ownership, failure/cancellation cleanup and competing closure.
- [x] Verify and independently approve host-held reusable child sessions with retained resources, history and current installed policy.
- [x] Verify and independently approve model-facing send, inspection and cancellation bound to one trusted session.
- [x] Verify and independently approve bounded host session membership, aliases, rejection ownership and joined removal/shutdown.
- [x] Verify and independently approve strict model-facing routing among host-registered sessions with scoped task controls.
- [x] Verify and independently approve policy-aware child construction with automatic descendant restrictions through derived factories and live reused-session admission.
- [x] Implement inline declarations and the general-purpose preset while retaining construction capability and child approval authority.
- [x] Implement and independently approve named host factory routing with bounded per-entry registries and parallel joined closure.
- [x] Parse bounded inline Markdown/YAML declarations into the validated model; see [parser acceptance](#inline-declaration-parsing).
- [x] Load bounded local declaration data without acquiring execution capabilities; see [loader acceptance](#local-declaration-loading).
- [x] Install and independently approve finite named reusable sessions with a sole automatic supervisor owner; see [session catalog acceptance](#named-session-catalog).
- [ ] Complete broader host installation.
- [x] Implement and independently approve scoped correlated child forwarding through all existing spawn/send routes.
- [ ] Complete optional remote/durable task routing and independently observed cancellation-tail delivery.

See the [repository tracker](../../../../../../../../../../docs/architecture/tracker.md#immediate-next-work) for immediate next work. No exhaustive upstream parity is claimed.

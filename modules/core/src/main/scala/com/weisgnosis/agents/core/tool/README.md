# Typed tools and path validation

The additive [interactive batch preflight](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#interactive-batch-preflight) screens initial monotonic policy denials before approval publication while retaining fresh ordinary authorization, occurrence identity and runtime-owned persistence/cleanup. ToolPreflight.Continue grants no approval; legacy authorizers default to Continue. Dynamic gates remain execution-time checks so earlier plan transitions affect later calls.

Tool keeps its input/output codecs and effectful handler together. [External bindings](#external-tool-bindings) add an explicit declaration-only capability. Toolkit registration and merging return Either[AgentError, Toolkit], including duplicate-name failures. Execution normalizes tool failures and defects into Error observations; interruption propagates. PathSafety rejects parent traversal before canonicalization and resolves existing ancestors through toRealPath. Canonical checks do not provide an atomic sandbox against hostile filesystem mutation. The [native alias check](../../../../../../../../../../verification/README.md#native-directory-aliases) exercises existing and missing children through real directory aliases; Windows junction verification is available, while POSIX execution and Windows file-symlink privileges remain separate prerequisites.

## Typed function tools

`Tool.derived` uses a compile-time argument schema alongside standard zio-json derived codecs:

```scala
import com.weisgnosis.agents.core.tool.{Tool, ToolSchema}
import zio.ZIO
import zio.json.*

final case class ReleaseQuery(release: String) derives JsonCodec, ToolSchema

val evidence: Tool[Any, ReleaseQuery, String] =
  Tool.derived[ReleaseQuery, String]("read_evidence", "Read release evidence", isReadOnly = true)(
    query => ZIO.succeed(s"Evidence for ${query.release}")
  )
```

Derivation supports object case classes, nested and generic products, strings, booleans,
Scala numeric primitives and BigInt/BigDecimal, Option, List, Vector, immutable Seq/Set,
Chunk and string-key immutable Map. `@jsonField` renames are retained. Collections are
required unless optional or defaulted; Option fields accept missing or null values.
Defaulted fields also accept missing or null values, matching zio-json; derivation never
evaluates a default expression and does not emit a schema `default`. Object properties
are closed with `additionalProperties=false`. The decoder still checks numeric ranges.

This contract assumes the default zio-json derived wire format. Custom codecs and custom
JsonCodecConfiguration must use an explicitly aligned schema. Recursive inputs, nested
Option, sums/enums, tuples, unsupported field types, non-string map keys, duplicate JSON
names and JSON annotations other than literal `@jsonField` fail compilation. The macro
uses compiler type information; no runtime reflection or schema dependency is added.

`Tool.fromFunction` remains the explicit-schema factory for custom formats and additional
constraints. `JsonCodec` alone does not derive a schema. Runtime schema admission and
decoding both run before local execution.

Factory callbacks in `Tool.derived`, `Tool.fromFunction` and `Tool.make` are evaluated
inside the returned ZIO execution. Toolkit normalizes failures and defects into Error
observations while preserving interruption. Custom Tool implementations remain responsible
for suspending their own effects. Existing explicit-schema calls remain source compatible.
The derived factory is available in `0.1.1-SNAPSHOT` and is not included in RC1.

## Runtime schema admission

Toolkit.validateSchemaInput is an explicit ZIO effect. A request-owned NetworkNT 2.0.4 evaluator checks the explicitly declared standard schema (default Draft 2020-12) and argument constraints before fresh ReAct preflight/authorization, standalone local execution and external suspension. Approved argument edits are checked again. Invalid initial arguments produce Error observations; invalid approved edits are Denied. Empty schemas retain unconstrained admission, with duplicate-key JSON validation still enforced. Provider strictness remains an independent generation preference.

Optional declared object properties whose value is JSON null are removed only from a private validation view. Required nulls and unknown properties remain; nested properties, object additionalProperties and homogeneous items follow the pinned ToolValidator normalization behavior. Positional legacy items also apply the same private optional-null policy to each tuple position; this extends the pinned helper, which does not traverse tuple schemas. The decoder, invocation context, persisted approved history and external request retain exact original prepared/approved arguments. No coercion, default injection or automatic schema repair occurs.

Numeric evaluation preserves integer node kinds and exact decimal text. The multipleOf keyword alone receives an exact decimal validation node, preventing integer rounding and tiny-positive-divisor underflow without changing other keywords or dispatched arguments. Local pointer references are decoded as strict UTF-8 URI fragments before JSON Pointer lookup; $ref and $dynamicRef share checked canonicalization at schema-bearing keywords. const, enum and examples remain instance data. Only bundled standard meta-schema resources may load; external network, filesystem and classpath reference acquisition is denied. This is schema admission, not a CPU sandbox or a filesystem security boundary.

Trusted AwaitingResults checkpoints describe previously issued external work. They retain saved exact input and accept supplied completion without retrospective schema reinterpretation, authorization or execution. Fresh BeforeActing/AwaitingApproval work and new authorization edits receive current admission checks. Hosts own authentication, exclusive execution and checkpoint migration.

Migration: declared constraints now reject arguments formerly accepted by a permissive decoder. Initial validation errors may appear in history before executable siblings complete; consumers correlate observations by occurrence/ID rather than assuming invocation arrival order. Toolkit.validateSchemaInput is additive; direct typed Tool.execute calls remain a trusted typed API. The declared standard profiles below are implemented; remaining method-level applicability stays explicit in the tracker.

## Declared standard schema dialects

The evaluator admits the five standard dialects recognized by the pinned upstream registry: Draft 4, 6, 7, 2019-09 and 2020-12. An absent declaration selects 2020-12; unknown or malformed declarations fail closed. Every evaluation owns its registry/configuration and uses bundled official meta-schemas only. Keyword traversal follows each dialect, so unsupported annotation data is not interpreted as a schema. Draft 4 retains lexical integer node semantics; newer drafts accept mathematically integral decimal nodes. Original input remains unchanged.

Local $ref pointers retain strict UTF-8 canonicalization in every supported draft; 2020-12 $dynamicRef uses its own validator. Draft 2019-09 retains the standard root-only $recursiveRef policy: '#' is supported and other values are rejected. This follows [core section 8.2.4.2.1](https://json-schema.org/draft/2019-09/json-schema-core#rfc.section.8.2.4.2.1), rather than substituting ordinary pointer semantics for recursion. Remote schema acquisition remains disabled.

## Pinned tool validator method evidence

All 39 pinned ToolValidatorTest methods now have an explicit [method-level disposition](../../../../../../../../../../docs/architecture/test-parity.md#runtime-schema-admission-finite-evidence). Existing test tables add missing standard constraints, root versus misplaced definitions, exact nested error paths, blank call names and incomplete parallel results. No production behavior or test-group count changes. Java nullable strings/maps, mutable bean reflection and throwing helpers have explicit typed API adaptations; JSON literal null is not conflated with an absent Java reference.

## External tool bindings

ToolBinding distinguishes Local executable tools from checked External declarations in one immutable Toolkit registry. Local bindings delegate definition, read-only/concurrency overrides and fixed arguments to their actual Tool. External bindings have no handler or fixed-argument injection. The ToolBinding.external factory captures the compile-time JsonDecoder for the selected argument type and checks the declaration; its decoder and the independent [runtime schema admission](#runtime-schema-admission) check both validate arguments. parametersSchema is both provider-facing metadata and the local admission contract.

Runtime dispatch validates external arguments before authorization and validates approved arguments again. Malformed input becomes an ordinary Error observation. Valid admitted runtime dispatch becomes pending external execution, without a fake Tool result or result events. Legacy Toolkit.executeSafe does not hand work to a host: it returns an explicit Error for an external binding. Decoder defects normalize at suspended effect boundaries while interruption remains cancellation.

The [acting contract](../agent/README.md#external-execution-checkpoints) preserves original call ordinals and input in a serializable suspended result. Hosts dispatch only from a returned or fully consumed, validated Suspended result. No local handler or background external-execution fiber exists. Host authentication, external execution, claims, idempotency and recovery remain host-owned; cancellation records abandonment without asserting the remote outcome.

Migration: Toolkit.tools is Map[String, ToolBinding] and resolve returns Option[ToolBinding]. Pattern-match Local to access an executable Tool. Existing Tool-based register/updated/single/fromMap conveniences remain; binding overloads and checked fromBindings support mixed registries. Overlay/filter/merge preserve exact bindings and collision rules. Public signatures change, so dependent source using the former view must migrate and dependent JVM binaries must rebuild. PendingActing adds defaulted externalRequests/externalTools fields and AwaitingResults; absent JSON fields retain empty defaults, but positional patterns/Product arity and enum matches must migrate. No frozen ABI or general checkpoint compatibility is claimed.

## Provider tool-schema strictness

ToolDef.schemaStrictness is an optional host-selected ToolSchemaStrictness.Strict or Relaxed preference. ChatCompletions emits tools[].function.strict as true or false respectively; None omits it. This is per-tool provider generation metadata. Parameters remain unchanged, and the setting adds no local schema-validation, authorization or successful-completion guarantee. Hosts must supply suitable schemas and compatible endpoints/fallback targets; there is no automatic model detection or compatibility-formatter suppression.

Fixed-argument wrappers and MCP bindings preserve the host's full definition. Remote hints never select this setting automatically. The admitted definition snapshot retains it across targets and retries. Synthetic generate_response keeps None by default; native response_format is a separate contract and is not modified by tool strictness.

Migration: ToolDef has eight positional/Product fields. Existing construction/named copy calls and legacy JSON retain the default; absent/JSON-null schemaStrictness means None. Supplied domain values use the enum codec, distinct from provider Booleans. Adapt positional patterns/constructor method values and rebuild dependent binaries.

## Checked registry identity

Toolkit is a final class with a private representation. Every key equals its tool's declared definition.name, so advertised definitions, authorization lookup and execution share one identity. Tool.definition is a pure, stable host extension contract; mutable or effectful custom metadata violates that contract.

Use Toolkit.fromMap for a host-supplied immutable map; it returns Either[AgentError, Toolkit] and rejects the first lexical key/name mismatch as InvalidConfiguration. It never silently renames, drops or dispatches a mismatched binding. Single-tool construction uses Toolkit.single. updated explicitly inserts or replaces by declared name; overlay explicitly gives the right registry precedence. register, withTool, checked tool-varargs construction and ++ continue rejecting collisions. filterNames retains exact values and their policies/resources. These operations grant no model permission or resource ownership.

Migration: unchecked Toolkit(map) and new Toolkit(map), copy, generated unapply and Product reconstruction are removed. Lift fromMap through ZIO.fromEither at effectful configuration boundaries. Use single/updated for deliberate pure construction/replacement, overlay for deliberate replacement merges, and filterNames for selection. Toolkit(), empty, the immutable binding-based tools read view, checked registration/merge and map-based equality/hash semantics remain available. Rebuild dependent binaries; this is a source/JVM migration, not a new persisted checkpoint format.

## Host-selected tool groups

`ToolGroups` is an immutable registry of named `ToolGroup` declarations. Activation is `Active` or `Inactive`; scope is `Meta` (eligible for agent-managed selection) or `External` (host-managed). Registration and updates return checked values without acquiring resources or changing existing registries. Names are exact and must be nonblank. Batch activation validates every requested group before producing a replacement; unknown names fail instead of partially updating or silently skipping them.

`replaceActive` replaces activation across both scopes. `replaceMetaActive` changes only Meta groups and rejects requested External groups. Membership is independent of a particular Toolkit. Ungrouped tools are available; a tool in several groups is available when at least one is active. `select(toolkit)` filters existing tool values, preserving their fixed arguments, execution overrides and borrowed resources. It produces a snapshot for the host to supply to ReAct; it does not mutate an already-running agent or install a model-facing activation tool.

`activeNotes` renders active groups from both scopes for the host. `metaNotes` renders separate active and inactive Meta sections and excludes External groups. Both are pure snapshot projections, sort names, include host descriptions and handle empty sections explicitly. They grant no permissions or skill activation.

Removing groups or membership reports `newlyUngrouped` names alongside the next registry. Removing the last membership makes a retained tool available as ungrouped. A host that wants to remove those tools must also prune its Toolkit; group removal performs no disposal or capability revocation. Group selection does not replace permission policy.

The runtime installs an optional [run-owned activation adapter](../agent/README.md#run-owned-tool-group-activation) over this foundation.

- Opt-in [harness Plan admission](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md#dynamic-plan-admission) consumes the runtime admitted-access view.
Pure group reminders follow the [catalogue contract](../skill/README.md); child installation is explicit harness composition. Group activation does not persist across public invocations.

Group data/enums use derived codecs; decoding does not bypass checked registration. Immutable values replace Java builders and defensive copying, and null contracts are not reproduced. Unknown activation/membership groups fail atomically rather than following upstream permissive or partially applied paths. The pure activeNotes/metaNotes accessors are additive; no existing Tool or Toolkit signature changes. Runtime note output has the [adapter migration](../agent/README.md#run-owned-tool-group-activation).

## Fixed raw arguments

[ToolFixedArguments](ToolFixedArguments.scala) is a checked immutable host policy. Nonempty policies require object input, validate the complete original AST before replacement, and apply shallow fixed-wins values. Existing root field positions are retained; missing fixed fields append in policy order, so preparation is AST-idempotent. Empty policy is identity even for scalar/array input and leaves legacy decoder validation unchanged. `validateApproved` rejects changed or missing fixed values while allowing other approved edits. No schema hiding or evaluation is added.

`Tool.withFixedArguments(tool, fixed)` preserves In/Out types, definition, explicit read-only/concurrency overrides, contextual execution and typed result projection. Empty attachment returns the original tool; a second nonempty attachment fails, even if identical. The policy belongs to the Tool value and survives Toolkit selection/merging. Toolkit.prepareInput and validateApprovedInput expose pure checks, and executeSafe prepares before decoding even for custom tools that expose a policy. Unknown names retain ordinary missing-tool dispatch. Trusted typed execute(In) calls bypass this raw-input policy.

ReAct prepares before authorization. Invalid preparation becomes Error without calling the authorizer or handler. A change to arguments carrying provider reasoning metadata is Denied before authorization. The authorizer sees prepared arguments; changing or removing a fixed field is Denied, while edits to other fields are allowed. After approval, the exact final input is committed to history, supplied through Active.call and executed. Original model input remains provenance; while approval is pending or cancelled, history retains the original call. Authorization cancellation joins cleanup and preserves its full cause, mapping typed Throwable leaves to defects for the UIO boundary.

Migration: Tool.fixedArguments has a concrete empty default, and the wrapper and Toolkit checks are additive. Existing raw scalar tools retain their decoding contract. MCP model-overridable defaults remain separate; manager installation of framework-fixed arguments has a separate [MCP contract](../../../../../../../../../mcp/src/main/scala/com/weisgnosis/agents/mcp/tool/README.md#fixed-arguments).

## Descriptive output schemas

`ToolDef.outputSchema: Option[Json]` carries an optional host-selected output schema. Toolkit snapshots and model-call tool definitions retain this AST; it does not validate returned values or grant execution authority. A host selecting an MCP tool may explicitly retain, replace or omit the remote schema. Preset input-schema projection leaves this field unchanged.

The current Chat Completions encoder emits function name, description, input parameters and explicitly selected provider schema strictness. It does not send outputSchema as a provider extension. Custom model clients receive the complete ToolDef and own any supported provider projection.

The derived domain codec reads an absent or explicit-null outputSchema as None and omits None when encoding. Non-null schema ASTs retain their structure; Some(Json.Null) does not round-trip as present. Existing constructors and named copies can omit the defaulted field; positional pattern matches and Product arity follow the [current eight-field migration](#provider-tool-schema-strictness), and downstream binaries must be rebuilt. No schema evaluation or custom compatibility codec is introduced.

## Local return mode

ToolDef.returnMode is a host-selected Continue (default) or Direct policy, not model/output metadata. Only an ordinary all-local, all-Direct successful batch may return processed presentation content without another model call, after joined cleanup and approval/Stop precedence. Full Tool observations remain in history.

## Incremental execution

`Tool.executeScoped(input, invocation)(publish)` returns `ZIO[R & Scope, Throwable, Out]`. Its `publish: ToolResultPayload => UIO[Unit]` argument supplies provisional output; the typed effect result supplies the single final value. The default delegates to executeWithContext once, and fixed-argument wrappers forward the scoped override with the existing input policy. Toolkit owns the execution scope and joins its resources and scoped children before final encoding. Execution or cleanup failure prevents a returned value from becoming a successful final observation. Only successful scoped completion admits encodeResultChecked, whose existing Success/Error restriction remains.

Progress contains provisional content and metadata, not call IDs or execution state. The runtime supplies identity/context and strips reserved control metadata. Nonempty output blocks publish ordered text/data deltas; metadata-only updates publish nothing. Progress never enters canonical history, result processors or later model input. Exactly one authoritative final result is persisted and processed. After any nonempty progress for that invocation occurrence, the final aggregate payload is not replayed as deltas; ToolResultEnd still carries final text/state/metadata. Complete typed final content remains in history/result, so provisional deltas are not a reconstruction of the final payload. Empty progress and ordinary tools retain aggregate final publication. Repeated call IDs do not share suppression state; inline provider results retain their separate path.

Progress callbacks are serialized for each execution and remain available through tool cleanup. After the execution scope closes, Toolkit revokes the callback before final encoding; later uses of a captured callback are no-ops. The callback grants no background-task ownership: tools acquire resources in the supplied Scope and use structured concurrency for children.

Migration: replace streamWithContext overrides and ToolExecutionUpdate values with executeScoped, invoke publish for provisional payloads and return Out directly. ToolExecutionUpdate is removed; missing, repeated or late Completed markers are no longer representable by this API. Ordinary execute/executeWithContext implementations inherit the default and need no new method. Rebuild dependent binaries. No event schema, mutable callback setter or hook fanout is added. Existing executeSafe remains a final-result operation and discards progress. Caller cancellation joins tool-owned cleanup and must preserve its complete cause; errors without interruption remain Error observations. Consumers cancelling an event stream still abandon later output. Final verification of the scoped-effect migration is tracked in the [repository tracker](../../../../../../../../../../docs/architecture/tracker.md#immediate-next-work).

## Typed execution observations

`Tool.encodeResultChecked` returns `Either[AgentError, ToolExecutionResult]`. Its default preserves custom `encodeResult` projections; `Tool.fromFunction` uses [ExactJson](../json/README.md#exact-domain-and-tool-output), and fixed wrappers forward the checked operation. Toolkit normalizes encoding failures and defects after joined cleanup while preserving interruption. Legacy direct `encodeOutput` and `encodeResult` calls are unchanged. Existing implementations need no new abstract method; rebuild dependent binaries.

`Tool.encodeResult(out)` defaults to the existing `encodeOutput(out)` as a successful text observation. Implementations may override it to return `ToolExecutionResult(payload, state)`, where `ToolResultPayload` retains ordered content blocks and metadata. Toolkit accepts only Success or Error from this projection; Running, Interrupted and Denied become descriptive Error observations. Authorization denials are produced separately by the runtime, and actual interruption remains cancellation.

ReAct supplies the original call ID, name and accepted state, then applies the payload through `ToolResultPayload.applyTo`. Tool-provided metadata cannot set serverTool, agentscope_server_tool or agentscope_suspended. Typed local content survives history persistence, result processing and ordered text/data events. ChatCompletions defaults to text-only tool output; the explicit [tool-image profile](../client/README.md#tool-image-result-projection) admits its bounded Text/Image shape. Other provider multimodal support and incremental local execution retain their separate contracts.

Migration: String `ToolExecutionResult(...)` constructors and the `output` text projection remain available. The projection joins Text blocks with newlines and omits other block types. The canonical constructor, generated copy/unapply and derived JSON shape now use `payload` and explicit `state`; persisted legacy output/state JSON requires host migration. There is no manual compatibility codec. Existing Tool implementations inherit encodeResult without an abstract-method migration.

Interrupted compound causes are refailed with `orDie`: interruption and cleanup defects survive, while typed Throwable leaves become defects to retain the UIO contract. Ordinary non-interrupted failures and defects remain Error observations.

## Invocation event capability

`Tool.executeWithContext(input, invocation)` delegates to `execute(input)` by default. Existing tools need no implementation change. The original `Toolkit.executeSafe(name, arguments)` uses `ToolInvocation.Detached`; the overload accepts an explicit host capability. These execution dependencies have no JSON codec and cannot be supplied through model arguments.

ReAct creates `Active(context, call, callOrdinal, events)` only for approved local execution, after persisting approved arguments. The zero-based tool occurrence distinguishes repeated model call IDs. Denied calls and matched provider inline results do not invoke this handler. Runtime request/reply correlation and the approved call are retained.

`ToolEventPort` exposes publication only through the runtime EventPublisher. Its scoped lease closes after execution cleanup and before result processing. Publication and closure are serialized: Published means accepted diagnostic admission, not consumer receipt; Rejected(reason) exposes the first capacity or encoding rejection, and Closed marks unavailable admission or a revoked lease. These receipts do not change completed tool observations or become tool Error results. Cancellation joins cleanup and preserves interruption and cleanup defects. Runtime admission never waits for the diagnostic reader. The lease does not own a child task, provide replay or acknowledge delivery. Trusted contextual tools remain responsible for the events they publish; the port does not authenticate arbitrary event payloads. See the [budget contract](../event/README.md#diagnostic-publication-and-budget).

- The separately reviewed [harness integration](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#scoped-child-event-forwarding) uses this capability for owned child execution and promotion. Its acceptance and bounded parity mappings remain distinct from this core contract.

## Implementation

- [PathSafety](PathSafety.scala)
- [Tool](Tool.scala)
- [ToolDef](ToolDef.scala)
- [ToolResultProcessor](ToolResultProcessor.scala)
- [ToolAuthorizer](ToolAuthorizer.scala)
- [ToolExecutionResult](ToolExecutionResult.scala)
- [Toolkit](Toolkit.scala)

See the [repository architecture](../../../../../../../../../../ARCHITECTURE.md) for integration boundaries and invariant definitions.

## Shell startup defaults

PathSafety's default sensitive filenames include `.bash_login`, `.zshenv`, `.zlogin`, `.zshenv.zwc`, `.zprofile.zwc`, `.zshrc.zwc` and `.zlogin.zwc`, in addition to the existing defaults. Existing case-insensitive segment matching also screens uppercase spellings. These explicit names cover the reproduced omissions; they do not enumerate every shell, installation-specific global path or host-selected startup mechanism. Default policy becomes stricter for these names; public signatures and codecs are unchanged. Custom sensitive-name configuration retains its existing behavior.

The [GNU Bash manual](https://www.gnu.org/s/bash/manual/bash.html) identifies `.bash_login` as a login initialization file. The [Zsh Files documentation](https://zsh.sourceforge.io/Doc/Release/Files.html) identifies `.zshenv`/`.zlogin` and explains compiled original-name-plus-`.zwc` replacements. This bounded fix adds explicit filenames rather than treating every `.zwc` or similarly prefixed file as sensitive. Ordinary near-misses `.bash_login.txt`, `.zshenv.example`, `.zlogin.notes`, `startup.zwc` and `.zshrc.zwc.backup` remain permitted by this filename screen. Traversal rejection, canonical ancestor checks and advisory command classification are unchanged; canonical screening remains non-atomic against hostile filesystem mutation.

[PathSafetySpec](../../../../../../../test/scala/com/weisgnosis/agents/core/tool/PathSafetySpec.scala) pairs these rejected startup names with ordinary permitted near-misses.

## Command screening

`isDangerousCommand` is a conservative lexical warning helper, not shell parsing or execution authorization. It normalizes input and configured fragments with Locale.ROOT, allows whitespace between fragment words, and requires lexical boundaries around alphanumeric fragment ends. It detects recursive chmod/chown, path-qualified dd, mkfs suffix commands, and the `/dev/` redirection prefix without matching dd inside address. Quoted dangerous text can still match. A false result does not establish command safety; plan admission uses its separate typed inspection and permission policy.

## Current review acceptance

See [fixed raw arguments](#fixed-raw-arguments), [admitted tool access](#admitted-tool-access) and [result-bearing streams](../agent/README.md#result-bearing-streams) for the corresponding authorization and delivery contracts.

Platform-specific symlink/junction behavior and hostile filesystem races remain outside the verified guarantees. Current command-screening evidence is recorded in the [test README](../../../../../../../test/scala/com/weisgnosis/agents/core/tool/README.md#command-screening-regressions).

## Completed result processing

ReActAgent.make and Builder.toolResultProcessor accept a ToolResultProcessor, defaulting to identity. Its legacy process callback returns ZIO[Scope, AgentError, ToolResultPayload]. The concrete processDecision default invokes it once and returns Continue. A host may select deciding for Continue/Stop; decision composition stops only that observation chain, while legacy payload-only composition still evaluates all stages. The [runtime contract](../agent/README.md#acting-stop-decisions) defines full-batch draining, failure precedence and migration. The payload contains output blocks and metadata only. applyTo preserves the original call ID, name, state, and serverTool/agentscope_server_tool/agentscope_suspended control keys. Composition applies each payload safely before passing the observation onward; host processors cannot change execution authority through these fields.

After local execution finishes, the runtime atomically appends the complete raw observation and captures its history position. It then runs processing interruptibly in a separate scope. Typed processing failures restore the original payload; defects propagate. Scope cleanup finishes before a short masked section replaces that exact observation and publishes End, plus aggregate text/data deltas only when that execution emitted no nonempty progress. Parallel calls own distinct append positions, including calls that reuse IDs; completion may arrive in a different order. Processing assumes history remains append-only during the acting phase under the run lock.

Cancellation during processing keeps the completed raw result and reconciles only unfinished executions. It does not rerun the tool or publish a false ToolResultEnd. A subsequent model request sees the committed processed payload; observers may see the raw result while processing is pending. Processor side effects already performed before failure/cancellation are not rolled back. Callbacks must not reenter a mutating agent operation that requires the held run lock.

Tool execution can return completed typed payloads. A processor can replace their output blocks and metadata before publication; the provisional execution path has the separate contract above. Empty processed output emits only End after the original Start. Matched inline provider results use this processor after raw assistant persistence, retaining their exact result ordinal. Their Start, ordered text/data deltas, and End are published only after processing and scope cleanup. Inline processing bypasses local authorization/execution and creates no Role.Tool message. Supplied history uses the separate opt-in ToolResultEvictor.preparer adapter.

Appending the optional make parameter preserves ordinary callers; positional Builder pattern matches must follow the current Builder constructor. Processor values are runtime dependencies, not serialized agent configuration.

## Admitted tool access

`AdmittedToolAccess.ReadOnly | Mutating` is transient host/runtime metadata with no codec. The additive concrete `authorize(request, admittedAccess: Option[AdmittedToolAccess])` overload delegates to the original abstract single-argument method by default. Lambda construction is unchanged; the partial-confirmation extension adds a Deferred decision codec case. The separate checkpoint-confirmation increment appends optional callOrdinal to ToolAuthorizationRequest and is accepted.

ReAct resolves the tool from the same admitted Toolkit used for the request batch and samples its actual Tool.isReadOnly accessor, including host overrides; it does not trust the definition flag or model metadata. Missing tools provide None. Sampling and overload invocation stay inside the suspended authorization decision boundary, preserving existing defect/interruption handling and ownership. Fixed preparation, approval validation and dispatch ordering are unchanged.

Custom authorizers compile without implementing the overload. To consume or forward the metadata they must override it explicitly; legacy wrappers otherwise delegate through the old method. The harness SubagentFactory composition now forwards the sample through live parent-denial checks; its scoped acceptance belongs to the [child construction contract](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#admitted-tool-plan-access). Rebuild dependent binaries; source compatibility is not a binary compatibility claim. The opt-in [harness Plan adapter](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md#dynamic-plan-admission) combines this view with existing permission policy and confirmation rather than treating read-only as an Allow decision.

## Scoped local authorization

ReActAgent.make and Builder.toolAuthorizer accept a ToolAuthorizer, defaulting explicitly to allow for compatibility. Installing a host authorizer is necessary to enforce permission policy. ToolAuthorizationRequest contains a fresh approval UUID, the originating ModelStreamContext, the call with framework-fixed arguments prepared before authorization, and an optional original tool-use ordinal. ReAct supplies that ordinal independently of repeated call IDs; legacy construction and missing JSON fields default to None. Four-field positional patterns need updating and dependent binaries must be rebuilt. The [complete-batch adapter](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#complete-batch-checkpoint-confirmations) is accepted. ToolAuthorization exposes Approved(input), Denied(reason), or Deferred(reason); the partial-confirmation extension supports partial saved-batch decisions. Exhaustive matches and decision-codec consumers must handle Deferred; identity, name, metadata, and execution authority are not replaceable decision fields. Requests and decisions have derived codecs, but serialization alone grants no execution authority.

The runtime persists the assistant message before authorization and captures its exact history position. Every pending local call passes preparation and authorization checks; unresolved provider-owned calls bypass local dispatch and authorization, and rejected preparation or signed changes do not invoke the authorizer. Matched provider inline results bypass local authorization. Each callback owns an interruptible scope; cleanup finishes before result Start and local execution. The request scope does not extend across the tool operation. Hosts must not reenter mutating agent operations while the run lock is held.

Approved JSON must have unique keys at every depth and retain all attached fixed values. A changed input is denied if either thoughtSignature or reasoningDetails metadata is present on the call or any Thinking block in its originating assistant message, including empty arrays, malformed or opaque values. Otherwise the runtime rewrites only that call's original occurrence, clears cached content, and stores its original input under agentscope_original_tool_input. Identity, other metadata, and protocol state remain unchanged. The next model request and concurrent history readers see the approved arguments before the tool executes. This provenance key records the runtime rewrite; arbitrary preexisting metadata is not authenticated evidence. No JSON Schema validation is added.

Denials never execute the tool. Typed PermissionDenied failures retain their reason while the runtime retains call identity. Non-interrupted authorizer failures and finalizer defects become Denied observations with a generic authorization-failure reason. Interrupted compound causes propagate intact with typed Throwable leaves mapped to defects; cleanup defects are not normalized into denial or discarded. This explicit fail-closed normalization prevents dangling calls without classifying host failures as provider errors. Cancellation propagates and reconciles unresolved calls as Error; completed siblings survive. Denials use the existing result processor and correlated Start/Delta/End path, so approved host processors can transform their observation content without changing Denied state.

Interruption reconciliation first consumes eligible inline observations, then matches remaining locally dispatched occurrences against Role.Tool results by ID and optional name. These pools cannot consume each other's observations. The internal Msg.mapToolUseAt helper updates by tool ordinal, preserving role/content compatibility; duplicate IDs and identical calls do not collapse into one rewrite.

Appending the optional make argument preserves ordinary calls; positional Builder matches must follow the current Builder constructor. This core extension point is implemented by the opt-in harness InteractivePermissionAuthorizer for policy checks, interactive confirmation, and atomic policy updates. The harness supplies explicit [checkpoint confirmation](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#complete-batch-checkpoint-confirmations); durable orchestration and full upstream permission event ordering are separate obligations.

The optional [interactive harness adapter](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md) owns policy lifetime and approval callbacks; the core remains independent of harness dependencies.

## Scoped local dispatch observation

Install `Toolkit.withDispatchObserver(observer)` to observe actual local dispatch, including argument validation, tool-owned cleanup and normalized output encoding. The observer receives a tool name and immutable `ToolDispatchContext`: correlation, call ID and occurrence ordinal only. It receives no model arguments, result payload or event-publication capability. Completion reports normalized Success/Error or the original interruption/defect cause; it does not grant approval or acknowledge persisted result delivery.

Observer resources are scoped around dispatch. Completion runs after tool cleanup and before the observer scope closes. Registered observers compose outermost first through `andThen`; callback defects propagate, so operational telemetry adapters must isolate their own failures. Missing names, external bindings, denied/supplied results and inline provider results do not produce local dispatch observations. Direct trusted `Tool.execute` calls bypass the Toolkit seam.

Registry updates, filtering and merges retain the receiver's observer. `overlay` borrows the other toolkit's bindings only; it does not install its observers. `withDispatchObserver` replaces the installed observer; compose explicitly when needed. Toolkit equality continues to compare registered bindings, not observer callbacks. Existing public dispatch methods remain unchanged; rebuild dependent binaries.

## Execution input schema

`Tool.inputValidationSchema` defaults to `definition.parametersSchema`. Adapters
that narrow a model-facing declaration can retain a separate full schema for
prepared execution arguments. Toolkit uses this schema both during ReAct admission
and dispatch; fixed values and authorized edits remain unchanged in history and
execution. `Tool.withFixedArguments` forwards an existing validation override.

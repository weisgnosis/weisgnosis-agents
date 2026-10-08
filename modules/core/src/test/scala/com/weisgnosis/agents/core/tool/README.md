# Typed tools and path validation: verification

Existing groups cover missing constraints and exact error paths without increasing group counts; Java null/reflection API differences remain explicit.

[ToolSchemaValidatorSpec](ToolSchemaValidatorSpec.scala) groups standard constraints/exact-number neighbors, declared Draft 4/6/7/2019-09 semantics and recursive-root policy, normalization/original-input preservation, and invalid schema/reference acquisition. Lexical 1.0/1 controls distinguish draft-specific integer node kinds; minimum-length neighbors independently verify string constraints. Reproduced failures include permissive decoder admission, tiny divisor underflow, percent-encoded pointer selection and malformed UTF-8 aliasing through reference keywords. The [current schema contract](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#runtime-schema-admission) owns final review and verification status.

## Failure fixtures

The existing ToolExecutionSpec failure group requires a RuntimeException containing InterruptedException to return an ordinary Error observation. A nested exception object is not ZIO cancellation; existing actual interruption/cleanup coverage remains separate. The existing ReActAgentToolEventSpec parallel group runs two atomic attempts of one failing-then-successful tool beside an independent success. It requires the exact three IDs, one Error/two Success observations, matching terminal event/history states and outputs, and terminal agent delivery without assuming which competing call fails.

Toolkit construction and selection follow the [checked registry identity and migration contract](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#checked-registry-identity).

These ZIO Test suites cover selected upstream scenarios and Scala regressions. They do not certify exhaustive upstream parity.

| Suite | Contract |
| --- | --- |
| [PathSafetySpec](PathSafetySpec.scala) | Sensitive paths, traversal, and advisory command fragments |
| [ToolExecutionUpdateSpec](ToolExecutionUpdateSpec.scala) | Three groups cover legacy delegation and fixed-wrapper streams, provisional/unique final output and invalid sequences, cleanup failure, full mixed causes and held cancellation cleanup. |
| [ToolExecutionSpec](ToolExecutionSpec.scala) | Typed decoding, checked factory numeric output and fixed-wrapper forwarding, duplicate-output failure, payload projection/codecs, completed-state admission and cancellation cleanup |
| [ToolInvocationSpec](ToolInvocationSpec.scala) | Approved occurrence context, legacy/denied dispatch, concurrent lease closure and actual ReAct cancellation |
| [ToolGroupsSpec](ToolGroupsSpec.scala) | Sorted/empty active and Meta-only notes, atomic checked activation, Meta/External replacement, OR membership/orphaning, exact policy-preserving selection and actual ReAct dispatch |
| [ToolFixedArgumentsSpec](ToolFixedArgumentsSpec.scala) | Checked full originals, stable order/idempotence, fixed consistency, empty scalar compatibility, typed wrapper forwarding and filtered/merged/custom Toolkit dispatch |
| [ToolDefSpec](ToolDefSpec.scala) | Legacy absent/null optional schema decoding, None omission, object/boolean AST round-trips, absent returnMode default, both completion modes and optional schemaStrictness enum round-trips/null/invalid values |
| [ToolkitSpec](ToolkitSpec.scala) | Immutable registration and binding-aware dispatch |
| [ToolBindingSpec](ToolBindingSpec.scala) | Checked external decoder dispatch, explicit legacy rejection, binding-preserving registry operations and local execution |
| [ToolResultProcessorSpec](ToolResultProcessorSpec.scala) | Payload codecs, protected control keys, suspended composition and host decision-chain stop/legacy behavior |
| [ToolAuthorizerSpec](ToolAuthorizerSpec.scala) | Decision/request codecs including missing/default and supplied call ordinals, lazy legacy/lambda delegation with absent/read-only/mutating admitted access, and error-channel separation |

The optional outputSchema codec deliberately maps Some(Json.Null) to absence on round-trip.

Actual model calls see only selected definitions; a model-invented inactive call cannot dispatch, while active and ungrouped tools execute. Removal tests explicitly show reactivation after last-membership loss, not revocation.

ToolAuthorizerSpec covers Deferred under the accepted partial-confirmation scope. The existing compatibility group verifies default overload delegation without changing request JSON; harness tests establish actual Plan integration. Runtime integration lives in the [agent suites](../agent/README.md). See the [parity audit](../../../../../../../../../../docs/architecture/test-parity.md) for remaining mappings.

## Invocation capability evidence

- Actual ReAct runs verify approved arguments persisted before contextual dispatch, distinct occurrence ordinals for repeated IDs, and revocation before result processing. Existing server-result suites retain inline bypass coverage.
- Standalone dispatch remains Detached and legacy handlers still execute. Denials invoke neither handler. Concurrent publication/closure accounts for accepted events, and cancellation waits for held tool cleanup, retains its defect and closes the port.
- Separate [harness integration evidence](../../../../../../../../../harness/src/test/scala/com/weisgnosis/agents/harness/subagent/README.md) covers child attribution, forwarding and promotion.

## Typed observation evidence

[ToolExecutionSpec](ToolExecutionSpec.scala) verifies legacy text and typed payload construction, derived codecs, Success/Error admission and rejection of tool-supplied runtime states. Held-cleanup cases cover both external cancellation and an owned child that actually interrupts: the complete cause retains cleanup defects and mixed typed-failure/interruption leaves. Legacy direct `encodeOutput` calls gain no checked numeric guarantee.

## Shell startup regressions

Two existing tables now require rejection of the seven omitted Bash/Zsh startup filenames and uppercase compiled-Zsh spelling, paired with permitted filename near-misses. The expectations derive from shell startup contracts rather than the production filename set. No shell command is executed and no new group is added.

## Command-screening regressions

Four literal counterexamples cover recursive chmod/chown, harmless `echo address`, and the actual dd executable. Their expectations are independent of the classifier's lookup table. Command strings remain data only. A compact companion table checks lexical boundaries, whitespace, path-qualified executables, suffix commands, quoted fragments, and device prefixes.

This is an advisory fragment detector. Quoted dangerous text can be flagged; a false result does not authorize shell execution. Plan-mode integration and full shell parsing are outside this scope.

Run `sbt "core / testOnly com.weisgnosis.agents.core.tool.PathSafetySpec"` for focused verification, or `sbt verify` for the repository. The [source README](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md) describes current processing and authorization boundaries.

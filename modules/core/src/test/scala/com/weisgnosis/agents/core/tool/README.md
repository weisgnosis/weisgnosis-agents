# Typed tools and path validation: verification

The [finite validator method manifest](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#pinned-tool-validator-method-evidence) records the current test-only identities and all 39 pinned method dispositions. Existing groups cover missing constraints and exact error paths without increasing group counts; Java null/reflection API differences remain explicit.

[ToolSchemaValidatorSpec](ToolSchemaValidatorSpec.scala) groups standard constraints/exact-number neighbors, declared Draft 4/6/7/2019-09 semantics and recursive-root policy, normalization/original-input preservation, and invalid schema/reference acquisition. Lexical 1.0/1 controls distinguish draft-specific integer node kinds; minimum-length neighbors independently verify string constraints. Reproduced failures include permissive decoder admission, tiny divisor underflow, percent-encoded pointer selection and malformed UTF-8 aliasing through reference keywords. The [current schema contract](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#runtime-schema-admission) owns final review and verification status.

## Failure fixtures

This test-only scope against e9158e6 adds bounded executable adaptations for ToolExecutorTest.testToolErrorWithoutInterruptSpecialCase (306) and testConcurrentToolExecutionWithErrors (364). Production, public APIs and test-group counts are unchanged; this is added contract evidence rather than a reproduced production defect.

The existing ToolExecutionSpec failure group requires a RuntimeException containing InterruptedException to return an ordinary Error observation. A nested exception object is not ZIO cancellation; existing actual interruption/cleanup coverage remains separate. The existing ReActAgentToolEventSpec parallel group runs two atomic attempts of one failing-then-successful tool beside an independent success. It requires the exact three IDs, one Error/two Success observations, matching terminal event/history states and outputs, and terminal agent delivery without assuming which competing call fails.

| Current regression file | Git blob |
| --- | --- |
| [ToolExecutionSpec.scala](ToolExecutionSpec.scala) | `ca56a5eb986bbada610c968a0b333a9f1cb53100` |
| [ReActAgentToolEventSpec.scala](../agent/ReActAgentToolEventSpec.scala) | `85fadab704bc20ba342f09ec10a1c85a1d9e91b9` |

- [x] review_fp and review_invariants approved both exact final test identities against e9158e6 with no unresolved source findings.
- [x] tool-executor-error-fixtures-focused.log passes 16 tests, zero failed/ignored, in 604 ms; formatting and fatal-warning compilation pass.
- [x] tool-executor-error-fixtures-verify.log passes formatting, fatal-warning compilation and 1,526 tests (680 core / 730 harness / 86 MCP / 22 real PostgreSQL / 8 examples), zero failures or ignored tests. Production and packaged consumer sources are unchanged from e9158e6; this verify-only run does not regenerate packages.
- [x] review_fp and review_invariants approved the final nine-file combined scope against e9158e6 with no unresolved findings; English/diff and 78-document/2,985-local-reference checks pass.

Toolkit construction and selection follow the [checked registry identity and migration contract](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#checked-registry-identity). The linked source/spec manifest owns the current migration review scope and verification; existing lifecycle and permission contracts remain in force.

- [x] Both independent reviewers approved fixed-argument preparation and runtime integration; full verification passed. The [current acceptance](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#fixed-raw-arguments) records exact scope and identities.

The [provider strictness contract](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#provider-tool-schema-strictness) records exact source/spec identities for optional true/false/omission, raw schema preservation, fixed wrappers and MCP host definitions. Existing groups are extended without increasing the count.

These ZIO Test suites cover selected upstream scenarios and Scala regressions. They do not certify exhaustive upstream parity.

| Suite | Contract |
| --- | --- |
| [PathSafetySpec](PathSafetySpec.scala) | Sensitive paths, traversal, and advisory command fragments |
| [ToolExecutionUpdateSpec](ToolExecutionUpdateSpec.scala) | Three groups cover legacy delegation and fixed-wrapper streams, provisional/unique final output and invalid sequences, cleanup failure, full mixed causes and held cancellation cleanup. Final independent reviews and full verification passed. |
| [ToolExecutionSpec](ToolExecutionSpec.scala) | Typed decoding, checked factory numeric output and fixed-wrapper forwarding, duplicate-output failure, payload projection/codecs, completed-state admission and cancellation cleanup |
| [ToolInvocationSpec](ToolInvocationSpec.scala) | Approved occurrence context, legacy/denied dispatch, concurrent lease closure and actual ReAct cancellation |
| [ToolGroupsSpec](ToolGroupsSpec.scala) | Sorted/empty active and Meta-only notes, atomic checked activation, Meta/External replacement, OR membership/orphaning, exact policy-preserving selection and actual ReAct dispatch |
| [ToolFixedArgumentsSpec](ToolFixedArgumentsSpec.scala) | Checked full originals, stable order/idempotence, fixed consistency, empty scalar compatibility, typed wrapper forwarding and filtered/merged/custom Toolkit dispatch |
| [ToolDefSpec](ToolDefSpec.scala) | Legacy absent/null optional schema decoding, None omission, object/boolean AST round-trips, absent returnMode default, both completion modes and optional schemaStrictness enum round-trips/null/invalid values |
| [ToolkitSpec](ToolkitSpec.scala) | Immutable registration and binding-aware dispatch |
| [ToolBindingSpec](ToolBindingSpec.scala) | Checked external decoder dispatch, explicit legacy rejection, binding-preserving registry operations and local execution |
| [ToolResultProcessorSpec](ToolResultProcessorSpec.scala) | Payload codecs, protected control keys, suspended composition and host decision-chain stop/legacy behavior |
| [ToolAuthorizerSpec](ToolAuthorizerSpec.scala) | Decision/request codecs including missing/default and supplied call ordinals, lazy legacy/lambda delegation with absent/read-only/mutating admitted access, and error-channel separation |

The descriptive output-schema contract and exact reviewed identities are in the [source acceptance](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#descriptive-output-schemas). Some(Json.Null) is intentionally not a presence-preserving codec value.

The [host-selected group contract](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#host-selected-tool-groups) owns current group identities and verification. Actual model calls see only selected definitions; a model-invented inactive call cannot dispatch, while active and ungrouped tools execute. Removal tests explicitly show reactivation after last-membership loss, not revocation.

The [admitted-access contract](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#admitted-tool-access) owns current authorizer identities and approval. Its decision-codec group now covers Deferred under the accepted partial-confirmation scope. The existing compatibility group verifies default overload delegation without changing request JSON; harness tests establish actual Plan integration. Runtime integration lives in the [agent suites](../agent/README.md). See the [parity audit](../../../../../../../../../../docs/architecture/test-parity.md) for remaining mappings.

## Invocation capability evidence

- [x] Actual ReAct runs verify approved arguments persisted before contextual dispatch, distinct occurrence ordinals for repeated IDs, and revocation before result processing. Existing server-result suites retain inline bypass coverage.
- [x] Standalone dispatch remains Detached and legacy handlers still execute. Denials invoke neither handler. Concurrent publication/closure accounts for accepted events, and cancellation waits for held tool cleanup, retains its defect and closes the port.
- [x] `path_review` and `current_docs` approved the exact [source/spec identities](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#invocation-event-capability); focused tests and full verification passed. This additive contract has no reproduced pre-existing behavioral defect claim.
- [x] Separate [harness integration evidence](../../../../../../../../../harness/src/test/scala/com/weisgnosis/agents/harness/subagent/README.md) covers child attribution, forwarding and promotion.

The [exact output acceptance](../../../../../../../main/scala/com/weisgnosis/agents/core/json/README.md#exact-domain-and-tool-output) records current ToolExecutionSpec and checked runtime encoding identities, final review_fp/review_invariants approval and full verification. Existing cancellation assertions remain unchanged; no numeric guarantee is added to legacy direct encodeOutput calls.

## Typed observation evidence

ToolExecutionSpec verifies legacy text construction, typed payload and derived-codec preservation, Success/Error admission and rejection of tool-supplied runtime states. Its deterministic cancellation case covers both an externally interrupted invocation with blocked cleanup and a tool joining its actually interrupted owned child. The external baseline passed in `tool-cleanup-baseline.log`. Against unchanged Toolkit blob `79be10c316f676da5d96977419e5be4caf02886f`, `tool-inner-cancellation-red.log` reproduced one failed assertion: the inner child's cleanup defect disappeared while interruption remained. The correction retains the compound cause and maps typed Throwable leaves to defects for the UIO boundary; a mixed typed-failure/interruption assertion also guards against stripping failure leaves.

- [x] Both independent reviewers approved the typed observation scope and full repository verification passed; the [source acceptance](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#current-review-acceptance) owns exact identities and verification evidence.

## Shell startup regressions

The [startup-default contract](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#shell-startup-defaults) owns the current PathSafety/source-spec identities and review gates against de8b21f. Two existing tables now require rejection of the seven omitted Bash/Zsh startup filenames and uppercase compiled-Zsh spelling, paired with permitted filename near-misses. The expectations derive from shell startup contracts rather than the production filename set. No shell command is executed and no new group is added.

path-startup-red.log records seven passing groups and one failed sensitive-path table against unchanged production, reporting the omitted names. review_fp and review_invariants approved the final source identities; corrected focused/full verification and fresh artifact/consumer checks pass. Both reviewers approved the final six-file combined scope with no findings; English/diff/local-link checks pass. Existing command-screening scope is retained separately; this is additional local INV-10 coverage rather than upstream test parity or an atomic sandbox claim.

## Command-screening regressions

Four literal counterexamples cover recursive chmod/chown, harmless `echo address`, and the actual dd executable. Their expectations are independent of the classifier's lookup table. The pre-fix run reproduced three assertion failures; the dd control passed. Command strings remain data only. A compact companion table checks lexical boundaries, whitespace, path-qualified executables, suffix commands, quoted fragments, and device prefixes.

This is an advisory fragment detector. Quoted dangerous text can be flagged; a false result does not authorize shell execution. Plan-mode integration and full shell parsing are outside this scope.

- [x] Demonstrate the defects before changing production: five existing/control cases passed and three regressions failed; `path-safety-regression-red.log` retains local evidence.
- [x] Verify the corrected classifier: eight tests passed in 136 ms with zero compiler warnings. Independent FP/ZIO and invariant reviewer `path_review` approved the source and test blobs recorded in the tool README.
- [x] Independent review and required repository checks accepted the unchanged command-screening scope; exact source/test identities remain in the source contract.

Run `sbt "core / testOnly com.weisgnosis.agents.core.tool.PathSafetySpec"` for focused verification, or `sbt verify` for the repository. Current source contracts, reviewed file identities and scoped acceptance are in the [tool README](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md). Existing processor/authorization acceptance is unaffected.

- [x] Both independent reviewers approved the [incremental contract](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#incremental-execution), and full tool-stream-verify.log verification passed. The unchanged external-cancellation regression reproduced the draft consumer defect and passes after the channel-pull correction.

The [external-binding scope](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#external-tool-bindings) is accepted within the named scope; its exact final identities have both independent source approvals and full verification; final combined documentation acceptance passed. Argument codecs validate the selected type, not arbitrary JSON Schema.

# Typed tools and path validation: verification

These ZIO Test suites cover selected upstream scenarios and Scala regressions. They do not certify exhaustive upstream parity.

| Suite | Contract |
| --- | --- |
| [PathSafetySpec](PathSafetySpec.scala) | Sensitive paths, traversal, and advisory command fragments |
| [ToolExecutionSpec](ToolExecutionSpec.scala) | Typed decoding, payload projection/codecs, completed-state admission and cancellation cleanup |
| [ToolInvocationSpec](ToolInvocationSpec.scala) | Approved occurrence context, legacy/denied dispatch, concurrent lease closure and actual ReAct cancellation |
| [ToolkitSpec](ToolkitSpec.scala) | Immutable registration and dispatch |
| [ToolResultProcessorSpec](ToolResultProcessorSpec.scala) | Payload codecs, protected control keys and suspended composition |
| [ToolAuthorizerSpec](ToolAuthorizerSpec.scala) | Decision codecs, lazy policies and error-channel separation |

Runtime integration lives in the [agent suites](../agent/README.md). See the [parity audit](../../../../../../../../../../docs/architecture/test-parity.md) for remaining mappings.

## Invocation capability evidence

- [x] Actual ReAct runs verify approved arguments persisted before contextual dispatch, distinct occurrence ordinals for repeated IDs, and revocation before result processing. Existing server-result suites retain inline bypass coverage.
- [x] Standalone dispatch remains Detached and legacy handlers still execute. Denials invoke neither handler. Concurrent publication/closure accounts for accepted events, and cancellation waits for held tool cleanup, retains its defect and closes the port.
- [x] `path_review` and `current_docs` approved the exact [source/spec identities](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#invocation-event-capability); focused tests and full verification passed. This additive contract has no reproduced pre-existing behavioral defect claim.
- [x] Separate [harness integration evidence](../../../../../../../../../harness/src/test/scala/com/weisgnosis/agents/harness/subagent/README.md) covers child attribution, forwarding and promotion.

## Typed observation evidence

ToolExecutionSpec verifies legacy text construction, typed payload and derived-codec preservation, Success/Error admission and rejection of tool-supplied runtime states. Its deterministic cancellation case covers both an externally interrupted invocation with blocked cleanup and a tool joining its actually interrupted owned child. The external baseline passed in `tool-cleanup-baseline.log`. Against unchanged Toolkit blob `79be10c316f676da5d96977419e5be4caf02886f`, `tool-inner-cancellation-red.log` reproduced one failed assertion: the inner child's cleanup defect disappeared while interruption remained. The correction retains the compound cause and maps typed Throwable leaves to defects for the UIO boundary; a mixed typed-failure/interruption assertion also guards against stripping failure leaves.

- [x] Both independent reviewers approved the typed observation scope and full repository verification passed; the [source acceptance](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md#current-review-acceptance) owns exact identities and verification evidence.

## Command-screening regressions

Four literal counterexamples cover recursive chmod/chown, harmless `echo address`, and the actual dd executable. Their expectations are independent of the classifier's lookup table. The pre-fix run reproduced three assertion failures; the dd control passed. Command strings remain data only. A compact companion table checks lexical boundaries, whitespace, path-qualified executables, suffix commands, quoted fragments, and device prefixes.

This is an advisory fragment detector. Quoted dangerous text can be flagged; a false result does not authorize shell execution. Plan-mode integration and full shell parsing are outside this scope.

- [x] Demonstrate the defects before changing production: five existing/control cases passed and three regressions failed; `path-safety-regression-red.log` retains local evidence.
- [x] Verify the corrected classifier: eight tests passed in 136 ms with zero compiler warnings. Independent FP/ZIO and invariant reviewer `path_review` approved the source and test blobs recorded in the tool README.
- [x] Independent review and required repository checks accepted the unchanged command-screening scope; exact source/test identities remain in the source contract.

Run `sbt "core / testOnly com.weisgnosis.agents.core.tool.PathSafetySpec"` for focused verification, or `sbt verify` for the repository. Current source contracts, reviewed file identities and scoped acceptance are in the [tool README](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md). Existing processor/authorization acceptance is unaffected.

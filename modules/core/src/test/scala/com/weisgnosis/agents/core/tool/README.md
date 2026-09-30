# Typed tools and path validation: verification

These ZIO Test suites cover selected upstream scenarios and Scala regressions. They do not certify exhaustive upstream parity.

| Suite | Contract |
| --- | --- |
| [PathSafetySpec](PathSafetySpec.scala) | Sensitive paths, traversal, and advisory command fragments |
| [ToolExecutionSpec](ToolExecutionSpec.scala) | Typed decoding and tool observations |
| [ToolkitSpec](ToolkitSpec.scala) | Immutable registration and dispatch |
| [ToolResultProcessorSpec](ToolResultProcessorSpec.scala) | Payload codecs, protected control keys and suspended composition |
| [ToolAuthorizerSpec](ToolAuthorizerSpec.scala) | Decision codecs, lazy policies and error-channel separation |

Runtime integration lives in the [agent suites](../agent/README.md). See the [parity audit](../../../../../../../../../../docs/architecture/test-parity.md) for remaining mappings.

## Command-screening regressions

Four literal counterexamples cover recursive chmod/chown, harmless `echo address`, and the actual dd executable. Their expectations are independent of the classifier's lookup table. The pre-fix run reproduced three assertion failures; the dd control passed. Command strings remain data only. A compact companion table checks lexical boundaries, whitespace, path-qualified executables, suffix commands, quoted fragments, and device prefixes.

This is an advisory fragment detector. Quoted dangerous text can be flagged; a false result does not authorize shell execution. Plan-mode integration and full shell parsing are outside this scope.

- [x] Demonstrate the defects before changing production: five existing/control cases passed and three regressions failed; `path-safety-regression-red.log` retains local evidence.
- [x] Verify the corrected classifier: eight tests passed in 136 ms with zero compiler warnings. Independent FP/ZIO and invariant reviewer `path_review` approved the source and test blobs recorded in the tool README.
- [x] Pass full `scalafmtAll scalafmtSbt verify` on 2026-09-30: 561 core and 609 harness tests, zero compiler warnings or failures.

Run `sbt "core / testOnly com.weisgnosis.agents.core.tool.PathSafetySpec"` for focused verification, or `sbt verify` for the repository. Current source contracts, reviewed file identities and scoped acceptance are in the [tool README](../../../../../../../main/scala/com/weisgnosis/agents/core/tool/README.md). Existing processor/authorization acceptance is unaffected.

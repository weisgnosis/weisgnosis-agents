# Immutable messages and content: verification

These ZIO Test suites cover selected upstream scenarios and Scala runtime regressions. They do not certify exhaustive upstream parity.

- [ChatUsageSpec](ChatUsageSpec.scala)
- [ContentBlockSerdeSpec](ContentBlockSerdeSpec.scala)
- [ContentBlockCodecSpec](ContentBlockCodecSpec.scala): ten concrete/base variants, recursive output, strict tags, and compatibility defaults.
- [ToolUseSpec](ToolUseSpec.scala): immutable upstream adaptations, all ToolCallState values, malformed fields, and marker precedence.
- [MsgSerdeSpec](MsgSerdeSpec.scala)
- [MsgSpec](MsgSpec.scala)
- [MsgFactorySpec](MsgFactorySpec.scala): all 26 immutable factory adaptations.
- [MsgConstructionSpec](MsgConstructionSpec.scala): construction closure, updates, equality, wire format, and effect lifecycle.
- [GenerateReasonSpec](GenerateReasonSpec.scala)
- [RoleContentValidationSpec](RoleContentValidationSpec.scala)

Run `sbt "core / test"`, or `sbt verify` for repository-wide formatting, compilation, and tests. See the [parity audit](../../../../../../../../../../docs/architecture/test-parity.md) for gaps and intentional differences.

## Decoding review acceptance at 906cc4a

- [x] `review_fp` and `review_harness` independently approved the changes to the four specs below, based on `21bc69f`, on 2026-09-29.
- [x] Verify 169 core and 40 harness tests with zero compiler warnings after adding strict malformed-field, legacy usage, Base64 wire, DataBlock, constructor, and complete role-matrix scenarios.
- [x] Classify all 119 upstream message methods with explicit coverage/adaptations or unchecked gaps in the parity audit.
- [ ] Complete remaining unchecked model scenarios before claiming exhaustive parity.

| Reviewed test source | Git blob |
| --- | --- |
| MsgSpec.scala | 35e5c41cc06464e15c0861208d176dbc266402f2 |
| MsgSerdeSpec.scala | 0e91900cd707ae8aa71e7cddd66e35db7fec7239 |
| ContentBlockSerdeSpec.scala | b7d82cb34215e81b794f2892c8ac6058beb75340 |
| RoleContentValidationSpec.scala | 6b3a0da03108224bba086e7b93a5d8b733fcd7e5 |

Role matrix tests validate dynamic construction and decoding independently of trusted factories. MsgConstructionSpec also proves unchecked construction routes fail compilation. Usage tests distinguish direct usage, valid legacy metadata, and invalid legacy data without mutating messages.

## Typed generation metadata

GenerateReason represents the 12 protocol reasons. Msg.generationReason is a pure defaulting classification: absent, malformed, or unknown metadata yields ModelStop without modifying the message. Msg.generateReason and its String setter remain the raw extension API; `empty_response` remains readable there and classifies as ModelStop. This compatibility projection must not be used as runtime completion authority.

The typed withGenerateReason overload preserves all other fields and metadata. Its codec accepts explicit uppercase protocol names and established lowercase names, and emits lowercase values to preserve existing interruption/max-iteration metadata. Java uppercase output is an intentional wire difference; mixed-case and padded names are not silently normalized.

- [x] `review_fp` and `review_harness` independently approved GenerateReason, the additive Msg methods, and GenerateReasonSpec based on `d3d003d`, on 2026-09-29, with no unresolved findings in this scope.
- [x] Cover all nine upstream GenerateReasonTest methods plus lowercase/uppercase compatibility, malformed/raw metadata, and all-field preservation regressions.
- [x] Formatting, fatal-warning compilation, and all 207 core / 40 harness tests passed.
- [ ] Close remaining DataBlock parity gaps.
- [ ] Integrate typed stop reasons with planned phase suspension and resumption; classifying metadata alone does not implement runtime production of every reason.

| Reviewed source | Git blob |
| --- | --- |
| GenerateReason.scala | e4e6dd03a73941b19557f413be343fa4643dcf33 |
| Msg.scala | 06a41eb6f9804b81250ff1a48c20a8935bc6475b |
| GenerateReasonSpec.scala | ffb0582a7bd561527473e1d6fa3b1fc3cc4e5e3f |

## Message API migration acceptance

- [x] `review_fp` and `review_harness` approved this package's changed Scala files against `9c47bf5` on 2026-09-29, with no unresolved findings in the message-boundary migration.
- [x] Repository verification passed 242 core and 40 harness tests with zero compiler warnings.

See the [validated message API and exact reviewed blobs](../../../../../../../main/scala/com/weisgnosis/agents/core/model/README.md) for source compatibility changes, scope, and evidence. Core callers preserve roles and optional fields; reasoning/structured-output filtering preserves message identity and metadata. Harness transformations preserve tool pairing, retained-user provenance, eviction fallback, and cancellation. This acceptance covers the changed files only; broader package and upstream parity gaps remain tracked.

## Content protocol verification

- [x] `review_fp` and `review_harness` approved the content protocol increment against `a701612` on 2026-09-29; no findings remain in the [recorded scope and exact source identities](../../../../../../../main/scala/com/weisgnosis/agents/core/model/README.md).
- [x] Full verification passed 272 core and 40 harness tests with zero compiler warnings.

Canonical serverTool markers preserve inline results without local dispatch, as verified in ReActAgentSpec alongside the legacy marker scenario. The model tests cover all ten content codecs and all 17 upstream ToolUse methods. ToolCallState remains protocol data; lifecycle and permission integration are still tracked work.

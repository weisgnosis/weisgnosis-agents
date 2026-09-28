# Immutable messages and content: verification

These ZIO Test suites cover selected upstream scenarios and Scala runtime regressions. They do not certify exhaustive upstream parity.

- [ChatUsageSpec](ChatUsageSpec.scala)
- [ContentBlockSerdeSpec](ContentBlockSerdeSpec.scala)
- [MsgSerdeSpec](MsgSerdeSpec.scala)
- [MsgSpec](MsgSpec.scala)
- [RoleContentValidationSpec](RoleContentValidationSpec.scala)

Run `sbt "core / test"`, or `sbt verify` for repository-wide formatting, compilation, and tests. See the [parity audit](../../../../../../../../../../docs/architecture/test-parity.md) for gaps and intentional differences.

## Current review acceptance

- [x] `review_fp` and `review_harness` independently approved the changes to the four specs below, based on `21bc69f`, on 2026-09-29.
- [x] Verify 169 core and 40 harness tests with zero compiler warnings after adding strict malformed-field, legacy usage, Base64 wire, DataBlock, constructor, and complete role-matrix scenarios.
- [x] Classify all 119 upstream message methods with explicit coverage/adaptations or unchecked gaps in the parity audit.
- [ ] Complete unchecked model scenarios and resolve raw Msg.apply/copy validation before claiming exhaustive parity.

| Reviewed test source | Git blob |
| --- | --- |
| MsgSpec.scala | 35e5c41cc06464e15c0861208d176dbc266402f2 |
| MsgSerdeSpec.scala | 0e91900cd707ae8aa71e7cddd66e35db7fec7239 |
| ContentBlockSerdeSpec.scala | b7d82cb34215e81b794f2892c8ac6058beb75340 |
| RoleContentValidationSpec.scala | 6b3a0da03108224bba086e7b93a5d8b733fcd7e5 |

Role matrix tests validate Msg.create and decoding. They do not imply that unchecked raw construction validates content. Usage tests distinguish direct usage, valid legacy metadata, and invalid legacy data without mutating messages.

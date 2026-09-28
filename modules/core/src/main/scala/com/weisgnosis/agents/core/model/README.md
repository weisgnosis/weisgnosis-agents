# Immutable messages and content

Msg, ContentBlock, Role, MediaSource, ToolResultState, and ChatUsage form the shared domain model. Constructors and JSON codecs are pure; Msg.id is optional and message.identified allocates an ID inside ZIO. ToolResultState represents Success, Error, Interrupted, Denied, and Running. ReAct interruption reconciliation emits Error observations for unfinished local calls. Content accessors are total.

## Implementation

- [JsonFields](JsonFields.scala): total optional-field decoding with explicit malformed-field failures.
- [ChatUsage](ChatUsage.scala)
- [ContentBlock](ContentBlock.scala)
- [MediaSource](MediaSource.scala)
- [Msg](Msg.scala)
- [GenerateReason](GenerateReason.scala)
- [Role](Role.scala)
- [ToolResultState](ToolResultState.scala)

See the [repository architecture](../../../../../../../../../../ARCHITECTURE.md) for integration boundaries and invariant definitions.

## Wire and construction contracts

Msg.chatUsage prefers the direct usage field, then decodes legacy `_chat_usage` metadata without mutation. Invalid legacy metadata yields None. Supplied malformed canonical optional fields fail decoding; missing or JSON-null fields retain their documented absence/default behavior. ToolResult rejects malformed state/name/metadata rather than silently defaulting a supplied invalid state to Success. Thinking rejects malformed supplied text/metadata.

Base64 sources encode `media_type`, accept legacy `mediaType`, and reject ambiguous input containing both. This changes the previous camel-case encoder output; consumers should accept the canonical field. User messages accept text, data, image, audio, and video only at Msg.create and JSON decoding boundaries, including rejection of Custom blocks. Raw Msg.apply/copy remains unchecked and requires further API design before construction-wide validation can be claimed.

## Decoding review acceptance at 906cc4a

- [x] Independent reviewers `review_fp` and `review_harness` approved the four model implementations and four affected specs based on `21bc69f`, on 2026-09-29, with no unresolved findings in this change.
- [x] Formatting, compilation with fatal warnings, and all 169 core / 40 harness tests passed after the final source changes.
- [ ] Close the explicitly unchecked construction and upstream parity gaps in the [mapping](../../../../../../../../../../docs/architecture/test-parity.md).

Exact reviewed Git blob identities (formatting included):

| Source | Git blob |
| --- | --- |
| Msg.scala | d2b9d95c31138c165765e81fa972e61872e6bdd9 |
| ContentBlock.scala | aac7a5d471ec5aec390e7d7a9b31358f0f25caa6 |
| MediaSource.scala | db0b0bf09f6d26c50f22faef8bc6376cb1f1ec74 |
| JsonFields.scala | 92bba6f226762fa2324c3211310fad6bf1c879e2 |

Acceptance is limited to the stated strict-decoding, usage compatibility, Base64, and role-validation changes; it does not certify all existing model APIs. Test identities are recorded in the [test README](../../../../../../../test/scala/com/weisgnosis/agents/core/model/README.md).

## Typed generation metadata

GenerateReason represents the 12 protocol reasons. Msg.generationReason is a pure defaulting classification: absent, malformed, or unknown metadata yields ModelStop without modifying the message. Msg.generateReason and its String setter remain the raw extension API; `empty_response` remains readable there and classifies as ModelStop. This compatibility projection must not be used as runtime completion authority.

The typed withGenerateReason overload preserves all other fields and metadata. Its codec accepts explicit uppercase protocol names and established lowercase names, and emits lowercase values to preserve existing interruption/max-iteration metadata. Java uppercase output is an intentional wire difference; mixed-case and padded names are not silently normalized.

- [x] `review_fp` and `review_harness` independently approved GenerateReason, the additive Msg methods, and GenerateReasonSpec based on `d3d003d`, on 2026-09-29, with no unresolved findings in this scope.
- [x] Cover all nine upstream GenerateReasonTest methods plus lowercase/uppercase compatibility, malformed/raw metadata, and all-field preservation regressions.
- [x] Formatting, fatal-warning compilation, and all 207 core / 40 harness tests passed.
- [ ] Close unchecked raw Msg construction/copy and remaining message factory/content parity gaps.
- [ ] Integrate typed stop reasons with planned phase suspension and resumption; classifying metadata alone does not implement runtime production of every reason.

| Reviewed source | Git blob |
| --- | --- |
| GenerateReason.scala | e4e6dd03a73941b19557f413be343fa4643dcf33 |
| Msg.scala | 06a41eb6f9804b81250ff1a48c20a8935bc6475b |
| GenerateReasonSpec.scala | ffb0582a7bd561527473e1d6fa3b1fc3cc4e5e3f |

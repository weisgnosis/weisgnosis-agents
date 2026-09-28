# Immutable messages and content

Msg, ContentBlock, Role, MediaSource, ToolResultState, and ChatUsage form the shared domain model. Constructors and JSON codecs are pure; Msg.id is optional and message.identified allocates an ID inside ZIO. ToolResultState represents Success, Error, Interrupted, Denied, and Running. ReAct interruption reconciliation emits Error observations for unfinished local calls. Content accessors are total.

## Implementation

- [JsonFields](JsonFields.scala): total optional-field decoding with explicit malformed-field failures.
- [ChatUsage](ChatUsage.scala)
- [ContentBlock](ContentBlock.scala)
- [MediaSource](MediaSource.scala)
- [Msg](Msg.scala)
- [Role](Role.scala)
- [ToolResultState](ToolResultState.scala)

See the [repository architecture](../../../../../../../../../../ARCHITECTURE.md) for integration boundaries and invariant definitions.

## Wire and construction contracts

Msg.chatUsage prefers the direct usage field, then decodes legacy `_chat_usage` metadata without mutation. Invalid legacy metadata yields None. Supplied malformed canonical optional fields fail decoding; missing or JSON-null fields retain their documented absence/default behavior. ToolResult rejects malformed state/name/metadata rather than silently defaulting a supplied invalid state to Success. Thinking rejects malformed supplied text/metadata.

Base64 sources encode `media_type`, accept legacy `mediaType`, and reject ambiguous input containing both. This changes the previous camel-case encoder output; consumers should accept the canonical field. User messages accept text, data, image, audio, and video only at Msg.create and JSON decoding boundaries, including rejection of Custom blocks. Raw Msg.apply/copy remains unchecked and requires further API design before construction-wide validation can be claimed.

## Current review acceptance

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

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

Base64 sources encode `media_type`, accept legacy `mediaType`, and reject ambiguous input containing both. This changes the previous camel-case encoder output; consumers should accept the canonical field. User messages accept text, data, image, audio, and video; system messages accept text only. Typed factories enforce these restrictions, and dynamic construction/updates and JSON decoding validate them. Msg has no unchecked public constructor, apply, copy, or Product reconstruction.

## Decoding review acceptance at 906cc4a

- [x] Independent reviewers `review_fp` and `review_harness` approved the four model implementations and four affected specs based on `21bc69f`, on 2026-09-29, with no unresolved findings in this change.
- [x] Formatting, compilation with fatal warnings, and all 169 core / 40 harness tests passed after the final source changes.
- [ ] Close the remaining upstream parity gaps in the [mapping](../../../../../../../../../../docs/architecture/test-parity.md).

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
- [ ] Close remaining content and ToolUse parity gaps.
- [ ] Integrate typed stop reasons with planned phase suspension and resumption; classifying metadata alone does not implement runtime production of every reason.

| Reviewed source | Git blob |
| --- | --- |
| GenerateReason.scala | e4e6dd03a73941b19557f413be343fa4643dcf33 |
| Msg.scala | 06a41eb6f9804b81250ff1a48c20a8935bc6475b |
| GenerateReasonSpec.scala | ffb0582a7bd561527473e1d6fa3b1fc3cc4e5e3f |

## Validated message API and migration

Msg is now a final immutable class with a private constructor, structural equality over all seven stored fields, and no public apply, copy, Product, or Mirror reconstruction. Replace raw construction with user/system/assistant/tool factories; user accepts the UserBlock union and system accepts Text blocks. For runtime-selected roles or widened content, create returns Either[AgentError, Msg]. Invalid combinations produce AgentError.InvalidMessage; decoding reports the same validation failure through JsonDecoder.

Replace content/role copies with validated withContent/withRole. Metadata, identity, name, usage, timestamp, and text updates are infallible. filterContent, mapToolUses, and mapToolResults preserve block compatibility. mapToolResultsZIO suspends callbacks, processes results in order, preserves environment and typed errors, and releases callback resources on interruption. Equality compares stored usage, not its derived legacy projection. Wire encoding retains omitted absent fields, omitted empty metadata, and an explicit empty content array.

This is a source-breaking API change: Msg.create and validateRoleContent now return AgentError instead of String, and raw case-class construction/copy/pattern extraction is removed. Existing Scala convenience names remain user/assistant/system; explicit None preserves unnamed messages. Identity and time are not allocated eagerly. The unified Msg and immutable updates intentionally replace Java subclasses/builders; this does not claim Java API equivalence.

## Message boundary review acceptance

- [x] Independent reviewers `review_fp` and `review_harness` approved the final Scala diff against `9c47bf5` on 2026-09-29 after restoring four unrelated corrupted temperature fixtures; no findings remain in this scope.
- [x] Verify nine construction regressions, all 26 MsgSubclassesTest adaptations, and existing agent/client/compactor/evictor regressions: 242 core and 40 harness tests pass with zero compiler warnings under `sbt scalafmtAll scalafmtSbt verify`.
- [x] Preserve all previous message fields during migration; compaction propagates typed reconstruction/provenance failures rather than replacing them with empty history.
- [ ] Close remaining content/ToolUse parity and runtime integration tasks in the architecture tracker.

Accepted scope: message role/content construction closure, safe immutable transformations, unchanged JSON representation, and migrated core/harness call sites. Historical acceptance entries above remain revision-specific. Exact reviewed Scala blobs for this increment:

| Reviewed file | Git blob |
| --- | --- |
| [modules/core/src/main/scala/com/weisgnosis/agents/core/agent/ReActAgent.scala](../agent/ReActAgent.scala) | 411e2179446d6e5d3001f89301c1e04e4841ea50 |
| [modules/core/src/main/scala/com/weisgnosis/agents/core/agent/StructuredOutput.scala](../agent/StructuredOutput.scala) | fe0ad8096467fe0bcf9156591427634035d12716 |
| [modules/core/src/main/scala/com/weisgnosis/agents/core/client/ReasoningModelAdapter.scala](../client/ReasoningModelAdapter.scala) | 3b56f2f1adcea13bcf582da261f38f611bca0e19 |
| [modules/core/src/main/scala/com/weisgnosis/agents/core/client/StreamAccumulator.scala](../client/StreamAccumulator.scala) | 826e43aac14fda49c472c0123934c771f5f54f03 |
| [modules/core/src/main/scala/com/weisgnosis/agents/core/error/AgentError.scala](../error/AgentError.scala) | 29580962e54a0c154f5b6d09b9cce8cdf2999b8c |
| [modules/core/src/main/scala/com/weisgnosis/agents/core/model/Msg.scala](Msg.scala) | 231673cb67d4bf3de40026ce6cadd2ea1c82101d |
| [modules/core/src/test/scala/com/weisgnosis/agents/core/agent/ReActAgentInterruptSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentInterruptSpec.scala) | 6959d0f9cb3aa85cd03012adab66c3a658d28109 |
| [modules/core/src/test/scala/com/weisgnosis/agents/core/agent/ReActAgentLifecycleSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentLifecycleSpec.scala) | 7c31e9257c7aca7b0d8740fe15e384cba924d9b4 |
| [modules/core/src/test/scala/com/weisgnosis/agents/core/agent/ReActAgentSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentSpec.scala) | 05a5cdd793796f42e3a86c2788e8b846a79717db |
| [modules/core/src/test/scala/com/weisgnosis/agents/core/agent/ReActAgentThinkingRetrySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentThinkingRetrySpec.scala) | 5d47407d0dab36cb4812c25034c7e13251e88e9d |
| [modules/core/src/test/scala/com/weisgnosis/agents/core/agent/StructuredOutputSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/StructuredOutputSpec.scala) | fbf41f2a750b00750856c66577adcc6b0cae91c2 |
| [modules/core/src/test/scala/com/weisgnosis/agents/core/client/ReasoningAdapterSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/client/ReasoningAdapterSpec.scala) | e4f3eb7ce0dc172a070e0f2b1193dbbf6bf15343 |
| [modules/core/src/test/scala/com/weisgnosis/agents/core/model/GenerateReasonSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/model/GenerateReasonSpec.scala) | 4f07c4a00ad9a4eaf142978381db4fc40c851011 |
| [modules/core/src/test/scala/com/weisgnosis/agents/core/model/MsgConstructionSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/model/MsgConstructionSpec.scala) | 710e79fac7e8a6b99e5291ce9285de775a235164 |
| [modules/core/src/test/scala/com/weisgnosis/agents/core/model/MsgFactorySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/model/MsgFactorySpec.scala) | ce51c4cc0583abbf41d8747ca5296ba0bfa95cb1 |
| [modules/core/src/test/scala/com/weisgnosis/agents/core/model/MsgSerdeSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/model/MsgSerdeSpec.scala) | 850d2580986a59d3e60c37c07661dcfa63dda3e7 |
| [modules/core/src/test/scala/com/weisgnosis/agents/core/model/MsgSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/model/MsgSpec.scala) | 0d537d7d4c910ca13f5da303463c19a9d22c430b |
| [modules/core/src/test/scala/com/weisgnosis/agents/core/model/RoleContentValidationSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/model/RoleContentValidationSpec.scala) | 9e1cfee7adf7af35964fd61a819fecbe6b444e31 |
| [modules/harness/src/main/scala/com/weisgnosis/agents/harness/eviction/ToolResultEvictor.scala](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/eviction/ToolResultEvictor.scala) | a56b742523e5e49a9394ebe181a528ef2c7c79a6 |
| [modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/ConversationCompactor.scala](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/memory/ConversationCompactor.scala) | 770a7fcc1b6ee5a5680e60f8b5322f6e53daa062 |
| [modules/harness/src/test/scala/com/weisgnosis/agents/harness/eviction/ToolResultEvictorSpec.scala](../../../../../../../../../harness/src/test/scala/com/weisgnosis/agents/harness/eviction/ToolResultEvictorSpec.scala) | c539ef70ae1e2302ec0e066831292a2b2fcae4a2 |
| [modules/harness/src/test/scala/com/weisgnosis/agents/harness/memory/ConversationCompactorSpec.scala](../../../../../../../../../harness/src/test/scala/com/weisgnosis/agents/harness/memory/ConversationCompactorSpec.scala) | 7bfd00561387b750d3ec59534ed07783c390691b |

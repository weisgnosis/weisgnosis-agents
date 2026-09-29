# Immutable messages and content

Pure domain values and codecs model messages, content, roles, media sources, usage, and protocol states. Constructors and decoders never allocate randomness, inspect time, or perform I/O. ReAct interruption reconciliation emits Error observations for unfinished local calls.

## Implementation

- [ChatUsage](ChatUsage.scala)
- [ContentBlock](ContentBlock.scala)
- [GenerateReason](GenerateReason.scala)
- [JsonFields](JsonFields.scala)
- [MediaSource](MediaSource.scala)
- [Msg](Msg.scala)
- [Role](Role.scala)
- [ToolCallState](ToolCallState.scala)
- [ToolResultState](ToolResultState.scala)

## Wire and construction contracts

Msg.chatUsage prefers the direct usage field, then decodes legacy `_chat_usage` metadata without mutation. Invalid legacy metadata yields None. Supplied malformed canonical optional fields fail decoding; missing or JSON-null fields retain their documented absence/default behavior. ToolResult rejects malformed state/name/metadata rather than silently defaulting a supplied invalid state to Success. Thinking rejects malformed supplied text/metadata.

Base64 sources encode `media_type`, accept legacy `mediaType`, and reject ambiguous input containing both. This changes the previous camel-case encoder output; consumers should accept the canonical field. User messages accept text, data, image, audio, and video; system messages accept text only. Typed factories enforce these restrictions, and dynamic construction/updates and JSON decoding validate them. Msg has no unchecked public constructor, apply, copy, or Product reconstruction.

## Typed generation metadata

GenerateReason represents the 12 protocol reasons. Msg.generationReason is a pure defaulting classification: absent, malformed, or unknown metadata yields ModelStop without modifying the message. Msg.generateReason and its String setter remain the raw extension API; `empty_response` remains readable there and classifies as ModelStop. This compatibility projection must not be used as runtime completion authority.

The typed withGenerateReason overload preserves all other fields and metadata. Its codec accepts explicit uppercase protocol names and established lowercase names, and emits lowercase values to preserve existing interruption/max-iteration metadata. Java uppercase output is an intentional wire difference; mixed-case and padded names are not silently normalized.

## Validated message API and migration

Msg is now a final immutable class with a private constructor, structural equality over all seven stored fields, and no public apply, copy, Product, or Mirror reconstruction. Replace raw construction with user/system/assistant/tool factories; user accepts the UserBlock union and system accepts Text blocks. For runtime-selected roles or widened content, create returns Either[AgentError, Msg]. Invalid combinations produce AgentError.InvalidMessage; decoding reports the same validation failure through JsonDecoder.

Replace content/role copies with validated withContent/withRole. Metadata, identity, name, usage, timestamp, and text updates are infallible. filterContent, mapToolUses, and mapToolResults preserve block compatibility. mapToolResultsZIO suspends callbacks, processes results in order, preserves environment and typed errors, and releases callback resources on interruption. Equality compares stored usage, not its derived legacy projection. Wire encoding retains omitted absent fields, omitted empty metadata, and an explicit empty content array.

This is a source-breaking API change: Msg.create and validateRoleContent now return AgentError instead of String, and raw case-class construction/copy/pattern extraction is removed. Existing Scala convenience names remain user/assistant/system; explicit None preserves unnamed messages. Identity and time are not allocated eagerly. The unified Msg and immutable updates intentionally replace Java subclasses/builders; this does not claim Java API equivalence.

## Content codecs and tool call protocol

All ten content variants encode the same object through concrete and ContentBlock codecs, including exactly one type tag. Concrete decoders also accept legacy untagged payloads; supplied tags must match. Root and nested decoding require a unique valid tag. Private payload derivation and a suspended root codec avoid encoder cycles and silent AST fallback data loss. Recursive ToolResult output uses the public guarded root codec. Legacy string/null/missing output and Thinking defaults remain supported; malformed supplied fields fail.

ToolUse appends ToolCallState (Pending, Asking, Allowed, Submitted, Finished), with a strict lowercase codec. Missing/JSON-null state defaults to Pending only at the block boundary. These values describe protocol data; the runtime still owns permission decisions and execution. Missing/JSON-null input normalizes to an empty object, including an explicitly constructed Json.Null on round-trip; all other JSON shapes remain unchanged. Optional content/metadata validation is consistent for concrete and base decoding.

Both ToolUse and ToolResult recognize canonical serverTool metadata and the existing agentscope_server_tool alias. Presence of serverTool takes precedence, including false or malformed values; only Boolean true enables the marker. withServerTool keeps the legacy key and updates an existing canonical key so the setter cannot leave conflicting values. Copy/state updates preserve all unrelated fields. Canonical inline server results bypass local dispatch and remain in the assistant message.

Compatibility changes: concrete leaf JSON now contains type; ToolUse encoding now contains state (including Pending). Existing constructor calls still compile because state is appended with a default, but positional pattern matches must include the new field. Consumers must allow the added fields. Accepted legacy decoding does not claim full Java payload/API equivalence.

## Data identity and media source boundaries

Data construction and decoding retain optional identity. Data.identified returns UIO[Data], allocating a UUID only when executed for an unidentified value; an existing ID is preserved without consuming randomness. Source and name are unchanged. This explicit effect intentionally replaces eager Java constructor allocation.

Data requires a MediaSource argument. Missing source arguments and Option values do not type-check; missing/JSON-null sources fail concrete and polymorphic decoding. This is the Scala/JSON adaptation of upstream constructor rejection, not a runtime guarantee for direct Java null references. Such references are prohibited by the engineering standard.

MediaSource.Url and Base64 now share the same tagged encoding through concrete and base types, with strict unique discriminators and no AST encoding fallback. Concrete decoders retain legacy untagged input; nested media sources require a tag. Base64 emits media_type and accepts mediaType only when the canonical field is absent; supplying both fails. Concrete source JSON now includes type, so consumers must allow this added field.

## Current acceptance and remaining work

- [x] `review_fp` and `review_harness` approved strict decoding/usage/role contracts at `906cc4a`, generation metadata at `9c47bf5`, message construction/update closure at `a701612`, and content/tool-call protocol contracts at `8b96ce3`.
- [x] Both reviewers approved the final four-file Data/media increment against `8b96ce3` on 2026-09-29, with no unresolved findings in this scope.
- [x] All 119 pinned upstream message methods have local evidence or explicitly reviewed Pure FP adaptations in the [parity mapping](../../../../../../../../../../docs/architecture/test-parity.md). This is method coverage with stated differences, not Java API/wire equivalence.
- [x] `sbt scalafmtAll scalafmtSbt verify` passed 284 core and 40 harness tests with zero compiler warnings.
- [ ] Integrate protocol stop/tool states with runtime phase suspension, permissions, and durable resumption. Metadata classification alone does not implement these behaviors.
- [ ] Complete broader core/event/harness parity and host integration tracked outside the message-method slice.

Current main-source identities are listed below; the review scopes above distinguish unchanged accepted contracts from the latest increment. Changed behavior requires renewed review. Test evidence is indexed in the [test README](../../../../../../../test/scala/com/weisgnosis/agents/core/model/README.md).

| Current source | Git blob |
| --- | --- |
| [ChatUsage.scala](ChatUsage.scala) | 90aaea1c5674391b69d05a25b64a6953c8e6813c |
| [ContentBlock.scala](ContentBlock.scala) | 9aad1167836c0e9d25a3384e0f3cb1ea59ef03e9 |
| [GenerateReason.scala](GenerateReason.scala) | e4e6dd03a73941b19557f413be343fa4643dcf33 |
| [JsonFields.scala](JsonFields.scala) | 92bba6f226762fa2324c3211310fad6bf1c879e2 |
| [MediaSource.scala](MediaSource.scala) | 8047c1845b5ec41a22ae8c1200ce2e60964a26c6 |
| [Msg.scala](Msg.scala) | 0f16756e28e6d24a24658e69769058d91979855f |
| [Role.scala](Role.scala) | 1fd28896fe37d9b7da11ceb30c922b10b7f6cca6 |
| [ToolCallState.scala](ToolCallState.scala) | d6f3636a99cff6c696e3617eef95c4bfd72d33b8 |
| [ToolResultState.scala](ToolResultState.scala) | e51c81870d3a88e90d69629ca33a0fc20608f775 |

## Strict structured JSON

Msg.getStructuredData and structured agent preparation share core.json.JsonBoundary. Text must contain one complete JSON value with unique object keys at every depth. Authoritative structured metadata is checked directly; invalid metadata fails without falling back to visible text. Captured synthetic payloads are checked before finalization. The synthetic tool also validates its entire input before extracting response, so duplicate wrapper keys or duplicates in ignored siblings cannot disappear during extraction. Valid wrapped and direct payload adaptation remains supported.

This tightens previously permissive parsing; trailing input and ambiguous fields now fail through existing typed/string error channels. It does not add JSON Schema validation. Failed structured preparation publishes no AgentResult or structured metadata; raw observations can remain in history. The shared validator performs no effects and does not depend on models, agents, or transports.

- [x] Six boundary regressions cover full text, duplicate text/metadata/captures/wrappers, metadata authority, and valid wrapped/direct values. Five real loopback HTTP tests cover native schema/thinking/usage, synthetic definition hoisting and compression, HTTP-400 fallback without duplicate input, no retry on HTTP 503, and rejection of trailing native JSON.
- [x] `review_fp` and `review_harness` approved the final shared JSON/structured HTTP scope against `9c165a7` on 2026-09-29 after the wrapper-boundary correction. Full verification passed 404 core and 40 harness tests. No findings remain; the [JSON package table](../json/README.md) records exact current identities.
- [ ] Complete hook parity and broader provider compatibility; general JSON Schema validation is outside this acceptance.

## Runtime occurrence updates

The internal mapToolUseAt operation updates one tool ordinal without changing message identity, unrelated content, or role/content compatibility. ReAct uses it to persist authorized arguments at the original assistant position before execution. Runtime cancellation consumes inline and local results separately. The [authorization scope](../tool/README.md) records current review and integration evidence.

- [x] `review_fp` and `review_harness` approved the final five-file core authorization increment against `a34201f` on 2026-09-29. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 481 core and 45 harness tests, including fifteen added authorization tests. Interactive and durable policy adapters are excluded from this acceptance. See the [authorization table](../tool/README.md) for exact identities.

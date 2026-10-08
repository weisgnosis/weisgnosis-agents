# Immutable messages and content

Pure domain values and codecs model messages, content, roles, media sources, usage, and protocol states. Constructors and decoders never allocate randomness, inspect time, or perform I/O. ReAct interruption reconciliation emits Error observations for unfinished local calls.

## Provider preview pairing

ToolHistory treats an inline Running result associated with a provider call as progress, not a completed occurrence. A later terminal result can match that occurrence within the same assistant message. Unresolved provider work is not a local execution obligation; unknown or local-only inline observations retain ordinary unmatched validation. This pairing contract does not replace previews in a provider stream or implement a Responses API adapter.

## Checked usage arithmetic

ChatUsage.validated checks all six counters are nonnegative, input plus output fits the existing Int total, and time is finite/nonnegative. totalTokens now returns Either[AgentError.InvalidMessage, Int]; combine now returns Either[AgentError.InvalidMessage, ChatUsage]. Both operands and the result are validated, with widened counter arithmetic, checked per-field bounds and checked aggregate input/output total. Invalid telemetry is rejected without saturation. Constructors/derived codecs and wire fields remain unchanged; raw decoded values still require checked use.

Migration is a source/JVM change: callers must handle typed failure instead of assuming totalTokens/combine return a value, and rebuild dependent binaries. Runtime usage accumulation validates the first observation and each combination atomically; failure retains previously accepted usage. Sanitized ModelError code zero stops before model-end publication, post-reasoning hooks or assistant persistence, without retry/fallback. Missing usage remains permitted. Event token fields remain Int.

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

Base64 sources encode `media_type`, accept legacy `mediaType`, and reject ambiguous input containing both. This changes the previous camel-case encoder output; consumers should accept the canonical field. User messages accept text, data, image, audio, video and inert peer observations; system messages accept text only. Typed factories enforce these restrictions, and dynamic construction/updates and JSON decoding validate them. Msg has no unchecked public constructor, apply, copy, or Product reconstruction.

`PeerObservation(senderId, senderName, message)` retains another runtime's exact message as nested data, never as local Assistant/Tool authority. Its `peer_observation` wire discriminator extends the Scala content algebra; older consumers must be upgraded before reading it. Sender fields are attribution, not authentication. See [passive peer context](../agent/README.md#passive-peer-context) for checked admission and request-only projection. Java DTOs intentionally do not support this Scala extension.

Metadata objects decode directly to immutable maps, preserving numeric AST leaves. Missing/null, malformed nonobject and duplicate-key behavior are unchanged. Use [ExactJson.encode](../json/README.md#exact-domain-and-tool-output) when serializing these domain values at an owned host boundary; derived .toJson output is not globally replaced.

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

MediaSource.Url carries an optional descriptive mimeType encoded as mime_type. Missing or JSON-null hints decode to None; None is omitted and duplicate/nonstring canonical hints fail. MIME support is checked by the selected consumer, not by this data constructor. Url patterns/Product now have two fields; defaulted calls remain valid, but MediaSource.url method-value arity and dependent binaries require migration.

MediaSource.Url and Base64 share the same tagged encoding through concrete and base types, with strict unique discriminators and no AST encoding fallback. Concrete decoders retain legacy untagged input; nested media sources require a tag. Base64 emits media_type and accepts mediaType only when the canonical field is absent; supplying both fails. Concrete source JSON now includes type, so consumers must allow this added field.

## Current acceptance and remaining work

- All 119 pinned upstream message methods have local evidence or explicitly reviewed Pure FP adaptations in the [parity mapping](../../../../../../../../../../docs/architecture/test-parity.md). This is method coverage with stated differences, not Java API/wire equivalence.
- Supported integration: runtime phase suspension and checkpoint authorization under the [agent contracts](../agent/README.md); metadata classification alone grants no authority. Host authentication, durable storage and idempotency remain separate.
The [tracker](../../../../../../../../../../docs/architecture/tracker.md) is the sole current completion checklist; the parity reference records bounded mappings and intentional API adaptations.

## Strict structured JSON

Msg.getStructuredData and structured agent preparation share core.json.JsonBoundary. Text must contain one complete JSON value with unique object keys at every depth. Authoritative structured metadata is checked directly; invalid metadata fails without falling back to visible text. Captured synthetic payloads are checked before finalization. The synthetic tool also validates its entire input before extracting response, so duplicate wrapper keys or duplicates in ignored siblings cannot disappear during extraction. Valid wrapped and direct payload adaptation remains supported.

This tightens previously permissive parsing; trailing input and ambiguous fields now fail through existing typed/string error channels. It does not add JSON Schema validation. Failed structured preparation publishes no AgentResult or structured metadata; raw observations can remain in history. The shared validator performs no effects and does not depend on models, agents, or transports.

- Six boundary regressions cover full text, duplicate text/metadata/captures/wrappers, metadata authority, and valid wrapped/direct values. Five real loopback HTTP tests cover native schema/thinking/usage, synthetic definition hoisting and compression, HTTP-400 fallback without duplicate input, no retry on HTTP 503, and rejection of trailing native JSON.
Structured-response parsing checks complete, unambiguous JSON; it does not evaluate arbitrary JSON Schema. Tool argument schema admission is a separate runtime boundary. Provider compatibility is limited to the explicitly selected profiles.

## Runtime occurrence updates

The internal mapToolUseAt operation updates one tool ordinal without changing message identity, unrelated content, or role/content compatibility. ReAct uses it to persist authorized arguments at the original assistant position before execution. Runtime cancellation consumes inline and local results separately. The [authorization scope](../tool/README.md) describes authorization and occurrence updates.

## Shared tool-history analysis

ToolHistory is private to the agents package hierarchy and supplies the common occurrence analysis for core rewrite validation and harness cutoff selection. It reports matched spans, pending local calls, and unmatched results. Unresolved provider calls create no local obligation or span. Inline results can consume only provider calls from their own assistant message; local results consume one matching local ID/name occurrence; separate Tool or later assistant results cannot complete an earlier provider call. Same-message spans cannot cross a message cutoff. It performs no effects or provider encoding checks.

The internal mapToolResultAt operation updates one result ordinal while preserving message identity, role, and unrelated blocks. Inline processing uses it to avoid overwriting identical same-ID observations; integration evidence belongs to the [agent scope](../agent/README.md).

Msg.hasReasoning recognizes nonempty text, nonblank signatures and nonempty opaque detail arrays; preservesToolArguments conservatively checks reserved Thinking metadata key presence, including empty arrays. These pure accessors change no JSON fields.

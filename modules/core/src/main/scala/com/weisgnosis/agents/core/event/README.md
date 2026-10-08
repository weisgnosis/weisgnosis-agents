# Typed agent event payloads

[AgentEvent](AgentEvent.scala) defines 39 immutable Scala payload variants. Runtime components publish them through scoped ZIO streams; having a type or codec does not establish an automatic producer. Canonical serialization never allocates identities or reads time.

## Invocation correlation

[InvocationContext](InvocationContext.scala) carries a typed UUID allocated by the runtime inside ZIO. A fresh invocation shares that identity across model attempts, hooks, tools and completion. Resume receives a fresh invocation identity while preserving its saved acting reply/request IDs. Sixteen contextual event variants retain this data through source tagging. Correlation grants no execution or confirmation authority.

## Incremental and accumulated thinking

ThinkingBlockDeltaEvent preserves the exact fragment and optional accumulatedThought. The built-in publisher accumulates independent prefixes by block index, resetting them for each model request. Checked reasoning-details events project admitted plaintext only; opaque encrypted/signature data does not become text. Custom publishers may omit the prefix.

The optional field has a trailing constructor default and old JSON decodes its absence as None. Positional extractors and dependent binaries require rebuilding when product signatures change. Sending every accumulated prefix can produce quadratic total text for many small fragments; no constant-memory subscriber guarantee is supplied.

## Existing event contracts

Runtime model/tool lifecycles carry [ModelStreamContext](ModelStreamContext.scala). Committed AgentResult precedes AgentEnd; pre-commit failures do not publish a finalized result. [AllToolsDenied](../agent/README.md#all-tools-denied-observation) follows complete local-batch cleanup and Deferred/Stop resolution. Its payload is observational and does not itself stop execution.

[Checkpoint permission events](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#checkpoint-permission-events) publish Ask after batch cleanup and before RequestStop; a resumed decision precedes tool Start. Opt-in [subagent milestones](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#subagent-milestones) follow explicit registration/admission and joined cleanup. Hosts provide authentication and durable execution ownership.

Opt-in phase observers can publish HintBlock through a revocable Hint-only capability; see [scoped phases](../agent/README.md#scoped-phase-observation). CompactionStart/End, AgentProgress and SubagentExposed currently have payload/codecs without an automatic producer. Model DataBlock production likewise is not established by accepting its DTO. The original design lists these DTO shapes without an automatic trigger. Pinned SubagentExposed publication requires an explicit host-exposed child ID; ordinary spawning or catalog lookup grants no exposure capability and does not synthesize that event. DataBlock has no pinned runtime producer. This distinguishes implemented publication from informational payloads; it does not invent identifiers or claim automatic compaction/progress emission.

## Optional final-answer filter

[FinalAnswerFilter.make](FinalAnswerFilter.scala) returns a checked, reusable ZPipeline with fresh state per subscription:

```scala
for
  filter <- ZIO.fromEither(FinalAnswerFilter.make())
  events <- agent.streamEvents(Msg.user("question")).via(filter).runCollect
yield events
```

The host explicitly applies this presentation filter. Text Start/Delta/End is buffered by exact optional source and model context until ModelResponseEnd. Matching ToolCallStart discards intermediate text; a tool-free round flushes before model End. Repeated starts reset only the matching buffer. AgentEnd clears abandoned buffers; EOF/failure never flushes unfinished text. Unmatched events pass through.

Positive defaults bound 4,096 buffered text events, 1,048,576 UTF-16 code units and 64 active requests per subscription. Accounting includes fragments and completed fullText even if repeated. Exceeding a bound fails with InvalidMessage; invalid configuration fails with InvalidConfiguration. Upstream causes and joined cancellation survive. Thinking, diagnostics, tools and AgentResult pass unchanged. Canonical history is unchanged; this is neither secret redaction nor an authoritative final-result selector.

## Codec and metadata contracts

Concrete/base Scala codecs encode one lowercase event discriminator. Concrete decoders also accept legacy untagged leaves; supplied tags must match. Base decoding requires a unique valid tag. Missing required fields, malformed tags and duplicate discriminators fail. Source updates retain other fields.

The four ToolResult event metadata decoders preserve numeric AST leaves directly. Missing/null metadata defaults to empty; malformed nonobjects fail and duplicate map keys retain their existing last-wins behavior. Use [ExactJson](../json/README.md#exact-domain-and-tool-output) for checked exact host serialization. Inline provider results retain complete metadata in output order. Completed and incremental local output use the existing typed events; final tool End remains authoritative. Request-side media support depends on the selected [client profile](../client/README.md), independently of event DTO support.

## Additional payloads

AgentResultEvent carries a validated Msg. DataBlock Start/Delta/End carry reply and block IDs, with string delta data. ToolResultDataDeltaEvent carries call identity/name, typed content and metadata. ExceedMaxItersEvent carries reply ID and configured/current counts. RequestStopEvent carries a reason and typed generation reason, defaulting absent/null classification to MiddlewareStopRequested. Decoding RequestStop does not cancel, suspend or resume execution. AllToolsDeniedEvent defaults an absent/null collection to empty; malformed supplied reasons/collections fail.

## Protocol identifiers and compatibility

[AgentEventType](AgentEventType.scala) is a separate 31-name uppercase identifier vocabulary with 11 aliases; lookup is pure and case-sensitive. It is not the Scala payload wire format.

The opt-in [Java wire DTO package](javawire/README.md) supplies all 31 pinned typed event envelopes and nested values against actual Jackson fixtures. Callers explicitly supply identity/time; canonical Scala JSON remains unchanged. Runtime-to-Java projection is not supplied because missing Java context cannot be invented. The deprecated coarse Event/EventType/EventSource protocol is excluded; source strings are not structured legacy attribution. See the [method mapping](../../../../../../../../../../docs/architecture/test-parity.md#event-method-mapping).

## Current acceptance

[Event suites](../../../../../../../test/scala/com/weisgnosis/agents/core/event/README.md) cover codecs, payloads, identifiers, correlation and filtering; the [Java wire suites](../../../../../../../test/scala/com/weisgnosis/agents/core/event/javawire/README.md) use pinned Jackson fixtures. Runtime ordering is exercised through actual [client](../client/README.md#runtime-event-ordering) and [agent](../agent/README.md) integration paths. This is bounded evidence, not exhaustive upstream ordering or a claim that every event has a producer. Current outstanding work belongs to the tracker; review outcomes belong to the task/PR.

## Model stream context

ModelStreamContext contains replyId, requestId and optional invocation. Legacy JSON without context/invocation remains readable; malformed supplied context fails. Runtime model boundaries and the live client supply correlation, while custom publishers own theirs. Constructors/codecs allocate no identity or time. Trailing optional fields preserve ordinary source calls but change extractor/product/JVM signatures, so rebuild dependent binaries.

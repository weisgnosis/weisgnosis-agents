# Upstream scenario coverage and remaining parity work

The engineering target is one paired Scala test for every applicable upstream behavior. Current suites cover selected scenarios and ZIO-specific regressions; exhaustive 1:1 parity has not been demonstrated. Suite names and passing counts are not evidence of complete parity.

The reference is the clean local `agentscope-java` checkout at commit `f98a685fdc4589f81e626e1686ac2c63d14a2304`, verified on 2026-09-29. Upstream Java implementation details are translated into Scala domain behavior, not copied as imperative APIs. Updating this pin requires a new inventory and review of changed scenarios.

## Progress checklist

- [x] Implement selected local suites for messages/content, events, tools, stream parsing, ReAct, structured output, compaction, eviction, and permissions.
- [x] Add deterministic ZIO regressions for cancellation, scope cleanup, storage publication, and repeated compaction.
- [x] Record the known intentional differences below.
- [x] Pin the upstream commit used for exhaustive comparison and verify its working tree is clean.
- [x] Inventory core JUnit source declarations, including plural-named suites.
- [x] Map all 18 declared methods in the three structured-output suites below.
- [x] Correct thinking/usage/history preservation and interrupted-capture defects; obtain independent final approval.
- [x] Verify the completed slice with 118 core and 40 harness tests, zero compiler warnings.
- [ ] Enumerate every upstream test method with a Scala counterpart, justified difference, or inapplicability.
- [x] Classify all 119 message/content/usage methods below; unchecked methods remain gaps.
- [x] Classify all 30 legacy/current event declarations below, including seven disabled ordering placeholders.
- [ ] Close the remaining event type, payload, and emitter parity gaps.
- [ ] Complete the tool matrix and platform-specific symlink integration coverage.
- [ ] Add live HTTP/provider transport integration tests.
- [ ] Complete ReAct and interruption scenario mappings, including lifecycle hooks not yet implemented.
- [ ] Complete the thinking-only empty-response scenario mapping.
- [x] Cover empty-input structured runs, signed thinking preservation, full reply usage, and immediate repeated calls.
- [ ] Complete the remaining structured-output capability, dynamic-schema, streaming, and hook scenarios below.
- [ ] Complete compaction/eviction mappings and document storage-backend applicability.
- [ ] Complete permission mappings and durable host integration tests.
- [ ] Close every applicable gap before declaring exhaustive 1:1 parity.

Existing suites cover selected scenarios only. No unchecked mapping task is satisfied merely by a passing test count.

## Deliberate behavioral differences

- Concurrent calls to the same ReActAgent are serialized. Upstream concurrent-call rejection tests cannot be claimed as ported unchanged.
- Immutable Scala models and typed errors replace Java builder/null-specific contracts. Each inapplicable upstream case still needs a recorded rationale in the exhaustive mapping.
- Interrupted unfinished local tools receive Error observations. Provider-hosted inline results do not create separate local tool messages.
- The current LlmClient is a port with scripted clients, not a live provider transport.

## Completion criterion

The remaining exhaustive parity audit must enumerate each upstream test method against the pinned revision with its Scala counterpart, an intentional difference, or a justified inapplicability. Unmapped tests remain pending. New implementations must add their paired scenarios and deterministic ZIO regressions before claiming parity for that component.

## Pinned core source inventory

The pinned tree contains 207 Java files under `agentscope-core/src/test/java/io/agentscope/core`. Of these, 195 contain JUnit test declarations: 2,466 `@Test`, six `@ParameterizedTest`, and one `@RepeatedTest`, totaling 2,473 annotated declarations. These are source counts, not execution totals: parameters, repetitions, nested classes, and disabled tests affect discovery and execution. Inventory scans all Java filenames, including `msg/MsgTests.java`.

| Area | Test-bearing files | Annotated declarations |
| --- | ---: | ---: |
| agent | 44 | 388 |
| message / msg | 9 | 119 |
| event | 1 | 20 |
| formatter | 2 | 53 |
| tool | 52 | 804 |
| model | 21 | 361 |
| util | 5 | 57 |
| permission | 6 | 44 |
| interruption | 2 | 22 |
| state | 12 | 86 |
| skill | 16 | 249 |
| legacy | 17 | 173 |
| credential | 2 | 7 |
| middleware | 2 | 5 |
| shutdown | 1 | 51 |
| tracing | 1 | 12 |
| root integration/version | 2 | 22 |

This is an inventory, not a classification of every method as covered or inapplicable. Broader domains such as persistent state, skills, built-in tools, middleware, and tracing remain unmapped rather than silently excluded. The message mapping below classifies all 119 methods. Event mapping and remaining structured-output capabilities retain explicit pending tasks.

## Structured-output method mapping

Source directory at the pinned revision: `agentscope-core/src/test/java/io/agentscope/core/agent`. Local evidence is in [StructuredOutputSpec](../../modules/core/src/test/scala/com/weisgnosis/agents/core/agent/StructuredOutputSpec.scala) and [ReActAgentLifecycleSpec](../../modules/core/src/test/scala/com/weisgnosis/agents/core/agent/ReActAgentLifecycleSpec.scala).

A checked method has a verified local contract or explicitly identified runtime adaptation. An unchecked method remains unimplemented or unverified. All 18 methods are classified; this does not mean all 18 are implemented.

### ReActAgentStructuredOutputTest: eight methods

- [x] `testStructuredOutputToolBased` -> `StructuredOutputSpec.testStructuredOutputToolBased`: typed data and metadata, with synthetic history removal.
- [x] `testStructuredOutputAutoFallbackToToolBased` -> lifecycle test `auto mode falls back on unsupported native formatting without duplicating input`. Adaptation: typed provider-format rejection triggers fallback; preflight model-capability flags are not implemented.
- [x] `testStructuredOutputWithoutNewMessages` -> same-named structured spec: existing history is used without adding user input, including empty messages.
- [x] `testStructuredOutputPreservesChatUsage` -> same-named structured spec: every generating-request usage field is retained. An additional multistep regression distinguishes per-request reply usage from aggregate AgentResult usage.
- [x] `testStructuredOutputPreservesThinkingBlock` -> same-named structured spec: signed thinking appears on the final reply exactly once in history.
- [x] `testConcurrencyConflictStructuredOutput` -> same-named structured spec: first invocation runs in a scoped fiber, joins, then a second invocation starts immediately; captures and toolkit definitions remain isolated.
- [x] `testConcurrencyConflictStructuredOutput_repeated` -> the same deterministic scoped-fiber regression. Adaptation: immutable per-run tool registration replaces the Reactor cleanup-race mechanism; the Java 25,000-repeat scheduler stress configuration is not reproduced.
- [ ] `testStructuredOutputNullReasoningMessage`: Java null itself is excluded by the Scala contract, but post-reasoning hook replacement/removal behavior has no current API. Do not claim hook parity from the no-null rule.

### ReActAgentStructuredOutputWithToolsTest: seven methods

- [x] `noTools_nativeSoSupported_usesNativePath` -> same-named structured spec: Auto supplies native response format without synthetic tools and preserves complete usage.
- [ ] `modelCallTextDeltaReplacement_updatesNativeStructuredOutput`: requires model-call middleware and streamed text replacement.
- [x] `noTools_nativeSoNotSupported_usesFallbackPath` -> lifecycle fallback test. Adaptation: rejection-based fallback, not preflight capability discovery.
- [ ] `hasTools_withToolsSupported_usesNativePath`: explicit Native mode exists, but Auto capability-based native selection with application tools remains absent.
- [x] `hasTools_withToolsNotSupported_usesFallbackPath` -> same-named structured spec: application and synthetic definitions coexist; no response format is installed. Current Auto always takes this route when application tools exist.
- [x] `hasTools_fallbackPath_structuredDataIsCorrect` -> the same fallback spec additionally asserts decoded value and reply metadata.
- [ ] `defaultWithToolsFollowsNativeFlag`: model-capability API and its defaulting contract remain pending.

### StructuredOutputDynamicDefineTest: three methods

- [ ] `testDynamicComplexNestedStructure`: dedicated runtime-schema/arbitrary JSON scenario remains pending; typed nested decoding alone does not establish it.
- [ ] `testDynamicComplexNestedStructureStreamingMode`: structured event streaming API remains pending.
- [x] `testDefsHoistedToRootForRefResolution` -> same-named structured spec: a real `#/$defs/Material` reference remains unchanged, definitions are rooted, and nested materials decode correctly. This is not a general JSON Schema validator.

Additional Scala regressions cover unrelated empty-message preservation, mixed ordinary call/result pairing, nonduplicated history usage, and interruption after capture. A captured value is not a successful structured response until the run is Completed. These regressions are runtime hardening, not invented upstream methods.

## Message method mapping

All 119 annotated methods in message/ and msg/ at the pinned revision are classified below. Checked methods have local behavioral evidence with the stated adaptation; unchecked methods remain unimplemented or unverified. Test source links are indexed in the [model test README](../../modules/core/src/test/scala/com/weisgnosis/agents/core/model/README.md).

### DataBlockTest: 7 methods

Explicit immutable values replace builders. Automatic Data IDs and null-source constructor rejection are not implemented as Java contracts; construction-only assertions remain unverified.

- [ ] `testBuildWithUrlSource`: pending assertion-level coverage or reviewed adaptation.
- [ ] `testBuildWithBase64Source`: pending assertion-level coverage or reviewed adaptation.
- [x] `testJsonRoundTrip` -> ContentBlockSerdeSpec, matching scenario name.
- [x] `testJsonDeserializationViaContentBlock` -> ContentBlockSerdeSpec, matching scenario name.
- [ ] `testIdAutoGenerated`: pending assertion-level coverage or reviewed adaptation.
- [x] `testNameIsOptional` -> ContentBlockSerdeSpec, matching scenario name.
- [ ] `testNullSourceRejected`: pending assertion-level coverage or reviewed adaptation.

### GenerateReasonTest: 9 methods

GenerateReasonSpec covers all 12 reasons through the nine methods below. Builders adapt to immutable typed updates, Msg.generationReason is the defaulting projection, and the raw generateReason accessor preserves extensions. The codec reads uppercase/lowercase names and writes established Scala lowercase metadata; Java uppercase output is not preserved. Classification is not runtime completion authority.

- [x] `testEnumValues` -> GenerateReasonSpec, matching scenario name with the stated immutable/wire adaptation.
- [x] `testDefaultGenerateReason` -> GenerateReasonSpec, matching scenario name with the stated immutable/wire adaptation.
- [x] `testGenerateReasonFromBuilder` -> GenerateReasonSpec, matching scenario name with the stated immutable/wire adaptation.
- [x] `testWithGenerateReason` -> GenerateReasonSpec, matching scenario name with the stated immutable/wire adaptation.
- [x] `testGenerateReasonFromStringMetadata` -> GenerateReasonSpec, matching scenario name with the stated immutable/wire adaptation.
- [x] `testInvalidGenerateReasonString` -> GenerateReasonSpec, matching scenario name with the stated immutable/wire adaptation.
- [x] `testPreserveMetadataWithGenerateReason` -> GenerateReasonSpec, matching scenario name with the stated immutable/wire adaptation.
- [x] `testAllGenerateReasonValues` -> GenerateReasonSpec, matching scenario name with the stated immutable/wire adaptation.
- [x] `testAllGenerateReasonValuesViaWith` -> GenerateReasonSpec, matching scenario name with the stated immutable/wire adaptation.

### MsgSubclassesTest: 26 methods

The unified immutable Msg replaces Java subclasses/builders. Each factory, JSON, and validation assertion still needs a reviewed adaptation; no blanket exclusion applies.

- [ ] `userMessage_text`: pending assertion-level coverage or reviewed adaptation.
- [ ] `userMessage_nameAndText`: pending assertion-level coverage or reviewed adaptation.
- [ ] `userMessage_varargsBlocks`: pending assertion-level coverage or reviewed adaptation.
- [ ] `userMessage_nameAndVarargsBlocks`: pending assertion-level coverage or reviewed adaptation.
- [ ] `userMessage_listOfBlocks`: pending assertion-level coverage or reviewed adaptation.
- [ ] `assistantMessage_text`: pending assertion-level coverage or reviewed adaptation.
- [ ] `assistantMessage_acceptsToolUseBlock`: pending assertion-level coverage or reviewed adaptation.
- [ ] `systemMessage_text`: pending assertion-level coverage or reviewed adaptation.
- [ ] `systemMessage_rejectsNonTextBlock`: pending assertion-level coverage or reviewed adaptation.
- [ ] `toolResultMessage_idNameText`: pending assertion-level coverage or reviewed adaptation.
- [ ] `toolResultMessage_varargs`: pending assertion-level coverage or reviewed adaptation.
- [ ] `toolResultMessage_list`: pending assertion-level coverage or reviewed adaptation.
- [ ] `jsonRoundTrip_userMessage`: pending assertion-level coverage or reviewed adaptation.
- [ ] `jsonRoundTrip_assistantMessage`: pending assertion-level coverage or reviewed adaptation.
- [ ] `jsonRoundTrip_systemMessage`: pending assertion-level coverage or reviewed adaptation.
- [ ] `jsonRoundTrip_toolResultMessage`: pending assertion-level coverage or reviewed adaptation.
- [ ] `legacyBuilderJson_deserializesToSubclass`: pending assertion-level coverage or reviewed adaptation.
- [ ] `userMessageBuilder_textContent`: pending assertion-level coverage or reviewed adaptation.
- [ ] `userMessageBuilder_idAndExplicitContent`: pending assertion-level coverage or reviewed adaptation.
- [ ] `assistantMessageBuilder_returnsConcreteType`: pending assertion-level coverage or reviewed adaptation.
- [ ] `assistantMessageBuilder_generateReasonGoesIntoMetadata`: pending assertion-level coverage or reviewed adaptation.
- [ ] `systemMessageBuilder_text`: pending assertion-level coverage or reviewed adaptation.
- [ ] `systemMessageBuilder_rejectsNonTextAtBuild`: pending assertion-level coverage or reviewed adaptation.
- [ ] `toolResultMessageBuilder_resultShorthandAccumulates`: pending assertion-level coverage or reviewed adaptation.
- [ ] `toolResultMessageBuilder_resultsReplacesAccumulator`: pending assertion-level coverage or reviewed adaptation.
- [ ] `subclassBuildersProduceJsonRoundTripCompatibleMessages`: pending assertion-level coverage or reviewed adaptation.

### MsgTest: 15 methods

Same-named MsgSpec tests adapt builders to immutable values. Role checks are a separate smart-constructor/decoder contract.

- [x] `testBasicMsgCreation` -> MsgSpec, matching scenario name.
- [x] `testBuilderWithTextBlock` -> MsgSpec, matching scenario name.
- [x] `testBuilderWithImageBlock` -> MsgSpec, matching scenario name.
- [x] `testBuilderWithAudioBlock` -> MsgSpec, matching scenario name.
- [x] `testBuilderWithVideoBlock` -> MsgSpec, matching scenario name.
- [x] `testBuilderWithThinkingBlock` -> MsgSpec, matching scenario name.
- [x] `testBuilderPattern` -> MsgSpec, matching scenario name.
- [x] `testGetTextContentWithSingleTextBlock` -> MsgSpec, matching scenario name.
- [x] `testGetTextContentWithMultipleTextBlocks` -> MsgSpec, matching scenario name.
- [x] `testGetTextContentWithMixedContentTypes` -> MsgSpec, matching scenario name.
- [x] `testGetTextContentWithNoTextBlocks` -> MsgSpec, matching scenario name.
- [x] `testGetTextContentWithEmptyMessage` -> MsgSpec, matching scenario name.
- [x] `testGetTextContentWithToolBlocks` -> MsgSpec, matching scenario name.
- [x] `testGetTextContentWithEmptyTextBlock` -> MsgSpec, matching scenario name.
- [x] `testTextContentConvenienceMethod` -> MsgSpec, matching scenario name.

### MsgUsageSerializationTest: 10 methods

Same-named MsgSerdeSpec tests adapt getChatUsage to pure chatUsage and Java null to None. Legacy lookup never mutates metadata or caches values.

- [x] `usageFieldRoundTripsViaJson` -> MsgSerdeSpec, matching scenario name.
- [x] `cachedTokensRoundTripViaJson` -> MsgSerdeSpec, matching scenario name.
- [x] `reasoningTokensRoundTripViaJson` -> MsgSerdeSpec, matching scenario name.
- [x] `detailedInputBreakdownRoundTripsViaJson` -> MsgSerdeSpec, matching scenario name.
- [x] `getChatUsagePreservesDetailedTokensFromMetadataMap` -> MsgSerdeSpec, matching scenario name.
- [x] `cachedTokensDefaultsToZeroForLegacyConstructor` -> MsgSerdeSpec, matching scenario name.
- [x] `usageNullByDefault` -> MsgSerdeSpec, matching scenario name.
- [x] `getChatUsagePrefersDirectField` -> MsgSerdeSpec, matching scenario name.
- [x] `getChatUsageFallsBackToMetadata` -> MsgSerdeSpec, matching scenario name.
- [x] `usageDeserializesFromJsonWithoutField` -> MsgSerdeSpec, matching scenario name.

### RoleContentValidationTest: 30 methods

Same-named RoleContentValidationSpec tests exercise Msg.create and decoding. JSON null content becomes an empty Chunk. This does not claim that raw Msg.apply/copy enforces the Java builder invariant.

- [x] `userAcceptsText` -> RoleContentValidationSpec, matching scenario name.
- [x] `userAcceptsData` -> RoleContentValidationSpec, matching scenario name.
- [x] `userAcceptsImage` -> RoleContentValidationSpec, matching scenario name.
- [x] `userAcceptsAudio` -> RoleContentValidationSpec, matching scenario name.
- [x] `userAcceptsVideo` -> RoleContentValidationSpec, matching scenario name.
- [x] `userRejectsHint` -> RoleContentValidationSpec, matching scenario name.
- [x] `userRejectsThinking` -> RoleContentValidationSpec, matching scenario name.
- [x] `userRejectsToolUse` -> RoleContentValidationSpec, matching scenario name.
- [x] `userRejectsToolResult` -> RoleContentValidationSpec, matching scenario name.
- [x] `assistantAcceptsText` -> RoleContentValidationSpec, matching scenario name.
- [x] `assistantAcceptsHint` -> RoleContentValidationSpec, matching scenario name.
- [x] `assistantAcceptsThinking` -> RoleContentValidationSpec, matching scenario name.
- [x] `assistantAcceptsData` -> RoleContentValidationSpec, matching scenario name.
- [x] `assistantAcceptsToolUse` -> RoleContentValidationSpec, matching scenario name.
- [x] `assistantAcceptsMixedReasoningTurn` -> RoleContentValidationSpec, matching scenario name.
- [x] `assistantAcceptsToolResult` -> RoleContentValidationSpec, matching scenario name.
- [x] `systemAcceptsText` -> RoleContentValidationSpec, matching scenario name.
- [x] `systemRejectsData` -> RoleContentValidationSpec, matching scenario name.
- [x] `systemRejectsImage` -> RoleContentValidationSpec, matching scenario name.
- [x] `systemRejectsHint` -> RoleContentValidationSpec, matching scenario name.
- [x] `systemRejectsThinking` -> RoleContentValidationSpec, matching scenario name.
- [x] `systemRejectsToolUse` -> RoleContentValidationSpec, matching scenario name.
- [x] `toolAcceptsToolResult` -> RoleContentValidationSpec, matching scenario name.
- [x] `toolAcceptsText` -> RoleContentValidationSpec, matching scenario name.
- [x] `toolAcceptsToolUse` -> RoleContentValidationSpec, matching scenario name.
- [x] `toolAcceptsData` -> RoleContentValidationSpec, matching scenario name.
- [x] `emptyContentAlwaysValid` -> RoleContentValidationSpec, matching scenario name.
- [x] `nullContentNormalised` -> RoleContentValidationSpec, matching scenario name.
- [x] `singleInvalidBlockRejectsMessage` -> RoleContentValidationSpec, matching scenario name.
- [x] `deserializationRunsValidator` -> RoleContentValidationSpec, matching scenario name.

### ToolResultBlockTest: 4 methods

Same-named ContentBlockSerdeSpec tests cover runtime error and server-tool markers with immutable copies.

- [x] `errorFactoryCreatesStructuredErrorState` -> ContentBlockSerdeSpec, matching scenario name.
- [x] `serverToolMarkerSurvivesJsonRoundTrip` -> ContentBlockSerdeSpec, matching scenario name.
- [x] `copyMethodsPreserveServerToolMarker` -> ContentBlockSerdeSpec, matching scenario name.
- [x] `toolCallErrorFactoryPreservesToolIdAndRuntimeErrorMarker` -> ContentBlockSerdeSpec, matching scenario name.

### ToolUseBlockTest: 17 methods

Only the three complete decoding assertions below are accepted here. Existing serialization checks omit some fields; builder, null normalization, immutability, and server-marker cases need explicit evidence.

- [ ] `testJsonSerializationWithAllFields`: pending assertion-level coverage or reviewed adaptation.
- [x] `testJsonDeserializationWithAllFields` -> ContentBlockSerdeSpec, matching scenario name.
- [x] `testJsonDeserializationWithoutContent` -> ContentBlockSerdeSpec, matching scenario name.
- [ ] `testJsonDeserializationWithoutMetadata`: pending assertion-level coverage or reviewed adaptation.
- [x] `testJsonDeserializationWithEmptyInput` -> ContentBlockSerdeSpec, matching scenario name.
- [ ] `testJsonDeserializationWithNullContent`: pending assertion-level coverage or reviewed adaptation.
- [ ] `testRoundTripSerialization`: pending assertion-level coverage or reviewed adaptation.
- [ ] `testInputMapIsUnmodifiable`: pending assertion-level coverage or reviewed adaptation.
- [ ] `testMetadataMapIsUnmodifiable`: pending assertion-level coverage or reviewed adaptation.
- [ ] `testConstructorWithThreeParameters`: pending assertion-level coverage or reviewed adaptation.
- [ ] `testConstructorWithFourParameters`: pending assertion-level coverage or reviewed adaptation.
- [ ] `testConstructorWithAllFiveParameters`: pending assertion-level coverage or reviewed adaptation.
- [ ] `testBuilderPattern`: pending assertion-level coverage or reviewed adaptation.
- [ ] `serverToolMarkerDistinguishesServerAndLocalCalls`: pending assertion-level coverage or reviewed adaptation.
- [ ] `serverToolMarkerSurvivesJsonRoundTrip`: pending assertion-level coverage or reviewed adaptation.
- [ ] `withStatePreservesServerToolMarker`: pending assertion-level coverage or reviewed adaptation.
- [ ] `testEmptyMapsForNullInputAndMetadata`: pending assertion-level coverage or reviewed adaptation.

### MsgTests: 1 method

MsgSpec test MsgTests.testConstructor preserves supplied timestamp and metadata with optional content uses a fixed supplied timestamp; no clock effect occurs in construction.

- [x] `testConstructor` -> MsgSpec, matching scenario name.

### Remaining model boundaries

- [ ] Make validation unavoidable for raw Msg construction/copy, or replace these entry points with a reviewed typed construction API. Current acceptance covers Msg.create and decoding only.
- [ ] Complete every unchecked message method before claiming exhaustive model parity.

## Event audit follow-up

The independent audit found the following gaps; completed corrections are checked below. The earlier six Scala event tests did not establish upstream parity. The upstream legacy agent/EventTest and newer event/AgentEventStreamTest are separate contracts.

- [x] Map the ten legacy EventTest methods and all 20 AgentEventStreamTest declarations, distinguishing 13 enabled tests from seven disabled ordering placeholders.
- [x] Use one canonical tagged representation for all 31 concrete/base event codecs; cross-decoding and legacy leaf decoding are tested.
- [x] Preserve immutable metadata on existing tool-result start/text-delta/end events through decoding, encoding, and repeated source tagging.
- [ ] Add the missing tool-result data-delta event and its metadata contract.
- [ ] Carry applicable tool-result metadata through runtime event emitters; current acceptance covers codecs.
- [x] Classify current event/lowercase and upstream type/uppercase wire formats as noninterchangeable; existing Scala wire tags remain canonical for compatibility.
- [x] Implement the separate AgentEventType identifier codec for all 31 canonical names and 11 aliases.
- [ ] Implement explicit field/payload adapters; identifier parsing alone cannot establish compatibility.
- [ ] Implement or explicitly classify AgentResult, DataBlockStart/Delta/End, ToolResultDataDelta, ExceedMaxIters, RequestStop, and AllToolsDenied event kinds.
- [x] Correct AgentStart/AgentEnd correlation with one runtime pair per engine attempt. ReActAgentEventSpec verifies identity, private completion, buffering order, failure, cancellation, timeout, and early consumer cleanup. Structured-call completion remains a separate pending API.
- [x] Exercise all 31 variants with repeated source tagging and verify tool-result metadata remains independent of source.

## Event method mapping

The legacy `agent/EventTest` wraps Msg in six coarse event kinds; the newer `event/AgentEventStreamTest` describes a separate event surface. Neither is identical to the current 31 Scala variants. Scala encodes `event` with lowercase names and different payload fields; upstream uses `type` and uppercase names/aliases. This slice preserves the established Scala format. Checked metadata methods below are behavioral adaptations, not cross-language wire fixtures. [AgentEventSerdeSpec](../../modules/core/src/test/scala/com/weisgnosis/agents/core/event/AgentEventSerdeSpec.scala) supplies their local evidence.

### Legacy EventTest: ten enabled methods

Each method remains pending a reviewed applicability decision or explicit adapter; replacing mutable objects with immutable values does not automatically satisfy payload or serialization assertions.

- [ ] `testEventTypeValues`: pending legacy Event/EventType compatibility classification.
- [ ] `testEventCreation`: pending legacy Event/EventType compatibility classification.
- [ ] `testEventIsLast`: pending legacy Event/EventType compatibility classification.
- [ ] `testEventGetMessageId`: pending legacy Event/EventType compatibility classification.
- [ ] `testEventToString`: pending legacy Event/EventType compatibility classification.
- [ ] `testEventWithEmptyContent`: pending legacy Event/EventType compatibility classification.
- [ ] `testEventWithMultipleContentBlocks`: pending legacy Event/EventType compatibility classification.
- [ ] `testEventDifferentTypes`: pending legacy Event/EventType compatibility classification.
- [ ] `testStreamingScenario`: pending legacy Event/EventType compatibility classification.
- [ ] `testEventJsonSerializationIsLastFieldName`: pending legacy Event/EventType compatibility classification.

### AgentEventStreamTest: 13 enabled methods

- [x] `runStartedAlias`: same-named AgentEventTypeSpec test of identifiers only; payload adaptation remains separate.
- [x] `runFinishedAlias`: same-named AgentEventTypeSpec test of identifiers only; payload adaptation remains separate.
- [x] `modelCallAliases`: same-named AgentEventTypeSpec test of identifiers only; payload adaptation remains separate.
- [x] `binaryBlockAliases`: same-named AgentEventTypeSpec test of identifiers only; payload adaptation remains separate.
- [x] `toolResultBinaryDeltaAlias`: same-named AgentEventTypeSpec test of identifiers only; payload adaptation remains separate.
- [x] `javaNativeNamesRoundTrip`: same-named AgentEventTypeSpec test of identifiers only; payload adaptation remains separate.
- [x] `fromValueRejectsUnknown`: same-named AgentEventTypeSpec test; typed AgentError.DeserializationError replaces Java IllegalArgumentException. Java exception wording is not preserved; payload adaptation remains separate.
- [x] `fromValueRejectsNull`: same-named AgentEventTypeSpec test; null/non-string JSON replaces the Java null-reference boundary.
- [x] `toolResultEndEventMetadataRoundTrip`: same-named Scala serde test; immutable metadata and current Scala wire fields.
- [x] `toolResultTextDeltaEventMetadataRoundTrip`: same-named Scala serde test; immutable metadata and current Scala wire fields.
- [ ] `toolResultDataDeltaEventMetadataRoundTrip`: pending data-delta payload and metadata behavior; its identifier is already supported.
- [x] `toolResultStartEventEmptyMetadata`: same-named Scala serde test; immutable metadata and current Scala wire fields.
- [x] `allPythonValuesResolve`: same-named AgentEventTypeSpec test of identifiers only; payload adaptation remains separate.

### AgentEventStreamTest: seven disabled ordering declarations

These upstream methods contain comments rather than executable assertions and their enclosing class is disabled. They remain explicit implementation requirements, not passing upstream evidence. The new ReActAgentEventSpec tests actual runtime boundaries and transport completion, without claiming the entire prescribed model/tool event sequence.

- [ ] `singleIterationOrder`: complete and verify the prescribed runtime sequence.
- [ ] `thinkingBeforeText`: complete and verify the prescribed runtime sequence.
- [ ] `toolCallCycle`: complete and verify the prescribed runtime sequence.
- [ ] `hitlReentry`: complete and verify the prescribed runtime sequence.
- [ ] `dataBlockEmission`: complete and verify the prescribed runtime sequence.
- [ ] `exceedMaxIters`: complete and verify the prescribed runtime sequence.
- [ ] `streamBoundaries`: complete and verify the prescribed runtime sequence.

### Remaining event payload contracts

The pinned source audit establishes these target fields. None of these missing Scala payloads or integrations is marked complete by identifier support.

| Pending variant | Required payload | Integration dependency |
| --- | --- | --- |
| AgentResult | result: Msg | Publish finalized result after structured postprocessing |
| DataBlockStart / End | replyId, blockId | Stable block correlation and provider assembly |
| DataBlockDelta | replyId, blockId, delta: String | Delta is text; it is not a ContentBlock |
| ToolResultDataDelta | replyId, toolCallId, toolCallName, data: ContentBlock, metadata | Preserve arbitrary blocks and applicable metadata |
| ExceedMaxIters | replyId, maxIters, currentIter | Runtime guard must use the correlated reply ID |
| RequestStop | reason, available typed GenerateReason; default MiddlewareStopRequested | Cooperative phase stop and durable pending-work resumption |
| AllToolsDenied | Chunk[ToolUse], empty by default | Permission/HITL integration and middleware stop decision |

Upstream base events also have identity/time, optional source, and metadata. Scala must use explicit supplied identity/time or an effectful allocator rather than copying eager UUID/clock access. Preserve immutable copies. Class presence alone is not runtime evidence: the pinned source has no production DataBlock event construction sites. A RequestStop event is distinct from fiber cancellation; supporting its type does not implement phase suspension or resumption.

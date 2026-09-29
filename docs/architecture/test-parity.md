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
- [x] Classify and cover all 119 message/content/usage methods below, retaining explicit Pure FP and wire adaptations.
- [x] Classify all 30 legacy/current event declarations below, including seven disabled ordering placeholders.
- [ ] Close the remaining event type, payload, and emitter parity gaps.
- [ ] Complete the tool matrix and platform-specific symlink integration coverage.
- [x] Add one real loopback HTTP/chunked SSE test and thirteen deterministic transport boundary/lifecycle tests.
- [x] Verify a real HTTP ReAct tool cycle and incremental correlated model events.
- [ ] Complete remaining provider scenarios and exhaustive upstream transport mapping.
- [ ] Complete ReAct and interruption scenario mappings, including lifecycle hooks not yet implemented.
- [ ] Complete the thinking-only empty-response scenario mapping.
- [x] Cover empty-input structured runs, signed thinking preservation, full reply usage, and immediate repeated calls.
- [x] Close all eighteen structured-output method mappings through verified contracts or explicit typed adaptations; broader middleware/hook behavior remains separate work.
- [ ] Complete compaction/eviction mappings and document storage-backend applicability.
- [ ] Complete permission mappings and durable host integration tests.
- [ ] Close every applicable gap before declaring exhaustive 1:1 parity.

Existing suites cover selected scenarios only. No unchecked mapping task is satisfied merely by a passing test count.

## Deliberate behavioral differences

- Concurrent calls to the same ReActAgent are serialized. Upstream concurrent-call rejection tests cannot be claimed as ported unchanged.
- Immutable Scala models and typed errors replace Java builder/null-specific contracts. Each inapplicable upstream case still needs a recorded rationale in the exhaustive mapping.
- Interrupted unfinished local tools receive Error observations. Provider-hosted inline results do not create separate local tool messages.
- LlmClient has scripted clients and ChatCompletionsClient for scoped HTTP completion with validated incremental correlated events. Provider extensions and exhaustive mapping remain pending.

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

This is an inventory, not a classification of every method as covered or inapplicable. Broader domains such as persistent state, skills, built-in tools, middleware, and tracing remain unmapped rather than silently excluded. The message mapping below classifies all 119 methods. Event mapping and broader legacy hook/middleware domains retain explicit pending tasks; all eighteen structured-output methods are mapped to verified contracts or explicit adaptations.

## Structured-output method mapping

Source directory at the pinned revision: `agentscope-core/src/test/java/io/agentscope/core/agent`. Local evidence is in [StructuredOutputSpec](../../modules/core/src/test/scala/com/weisgnosis/agents/core/agent/StructuredOutputSpec.scala) and [ReActAgentLifecycleSpec](../../modules/core/src/test/scala/com/weisgnosis/agents/core/agent/ReActAgentLifecycleSpec.scala).

A checked method has a verified local contract or explicitly identified runtime adaptation. An unchecked method remains unimplemented or unverified. All 18 methods are classified; this does not mean all 18 are implemented.

### ReActAgentStructuredOutputTest: eight methods

- [x] `testStructuredOutputToolBased` -> `StructuredOutputSpec.testStructuredOutputToolBased`: typed data and metadata, with synthetic history removal.
- [x] `testStructuredOutputAutoFallbackToToolBased` -> lifecycle test `auto mode falls back on unsupported native formatting without duplicating input`. Typed provider rejection retains fallback coverage; StructuredOutputCapabilityRoutingSpec additionally verifies preflight Unsupported selection without probing.
- [x] `testStructuredOutputWithoutNewMessages` -> same-named structured spec: existing history is used without adding user input, including empty messages.
- [x] `testStructuredOutputPreservesChatUsage` -> same-named structured spec: every generating-request usage field is retained. An additional multistep regression distinguishes per-request reply usage from aggregate AgentResult usage.
- [x] `testStructuredOutputPreservesThinkingBlock` -> same-named structured spec: signed thinking appears on the final reply exactly once in history.
- [x] `testConcurrencyConflictStructuredOutput` -> same-named structured spec: first invocation runs in a scoped fiber, joins, then a second invocation starts immediately; captures and toolkit definitions remain isolated.
- [x] `testConcurrencyConflictStructuredOutput_repeated` -> the same deterministic scoped-fiber regression. Adaptation: immutable per-run tool registration replaces the Reactor cleanup-race mechanism; the Java 25,000-repeat scheduler stress configuration is not reproduced.
- [x] `testStructuredOutputNullReasoningMessage` -> same-named ReActAgentReasoningHookSpec. Adaptation: typed Discard consumes an iteration without persisting/executing rejected synthetic calls, then a valid capture completes; invocation usage includes both observations. The pinned method asserts no NPE, not a particular nullable result. Broader legacy hook equivalence is not claimed.

### ReActAgentStructuredOutputWithToolsTest: seven methods

- [x] `noTools_nativeSoSupported_usesNativePath` -> same-named structured spec: Auto supplies native response format without synthetic tools and preserves complete usage.
- [x] `modelCallTextDeltaReplacement_updatesNativeStructuredOutput` -> same-named ChatCompletionsTransformSpec real HTTP scenario. Adaptation: scoped typed text transformation before accumulation replaces upstream event rewriting; deltas/end, structured metadata, finalized text/history, and usage agree. General model-call middleware is not claimed.
- [x] `noTools_nativeSoNotSupported_usesFallbackPath` -> same-named StructuredOutputCapabilityRoutingSpec: declared Unsupported selects synthetic without probing; lifecycle/HTTP tests separately cover rejection fallback.
- [x] `hasTools_withToolsSupported_usesNativePath` -> same-named capability routing spec: Auto Native() forwards application tools and response format without generate_response. A separate regression executes an application call before native completion.
- [x] `hasTools_withToolsNotSupported_usesFallbackPath` -> same-named structured spec: application and synthetic definitions coexist; no response format is installed. Capability routing additionally verifies this route for declared Native(NativeToolSupport.Unsupported).
- [x] `hasTools_fallbackPath_structuredDataIsCorrect` -> the same fallback spec additionally asserts decoded value and reply metadata.
- [x] `defaultWithToolsFollowsNativeFlag` -> StructuredOutputCapabilitySpec default test plus the Unsupported routing cases. Adaptation: Native() defaults to typed tool support; Unsupported structurally excludes native-with-tools instead of exposing two Boolean getters.

### StructuredOutputDynamicDefineTest: three methods

- [x] `testDynamicComplexNestedStructure` -> same-named StructuredOutputDynamicSpec test: parse the runtime schema, request synthetic output as Json, and preserve the weather object in decoded data, metadata, and committed history.
- [x] `testDynamicComplexNestedStructureStreamingMode` -> same-named StructuredOutputDynamicSpec test: streamed finalized Json metadata, committed history, exact synthetic schema, and one model request. Adaptation: typed AgentResultEvent followed by AgentEnd replaces the legacy Event/isLast envelope.
- [x] `testDefsHoistedToRootForRefResolution` -> same-named structured spec: a real `#/$defs/Material` reference remains unchanged, definitions are rooted, and nested materials decode correctly. This is not a general JSON Schema validator.

Despite the upstream method names, these two weather fixtures are flat objects. The tests preserve that contract without claiming general JSON Schema validation or legacy wire equivalence. Both inspect the actual synthetic parameters received by the model. The separate materials scenario verifies nested references.

Additional Scala regressions cover unrelated empty-message preservation, mixed ordinary call/result pairing, nonduplicated history usage, and interruption after capture. A captured value is not a successful structured response until the run is Completed. These regressions are runtime hardening, not invented upstream methods.

## Message method mapping

All 119 annotated methods in message/ and msg/ at the pinned revision are classified below. Checked methods have local behavioral evidence with the stated adaptation; unchecked methods remain unimplemented or unverified. Test source links are indexed in the [model test README](../../modules/core/src/test/scala/com/weisgnosis/agents/core/model/README.md).

### DataBlockTest: 7 methods

DataBlockSpec preserves all seven method names. Immutable values replace builders; identified allocates identity explicitly inside ZIO rather than eagerly during construction. Required MediaSource arguments and rejection of missing/JSON-null wire sources adapt upstream missing-source rejection; direct Java null constructor behavior is not implemented or claimed. Existing media/content serde scenarios remain regression coverage.

- [x] `testBuildWithUrlSource` -> DataBlockSpec, matching scenario name with the stated Pure FP adaptation.
- [x] `testBuildWithBase64Source` -> DataBlockSpec, matching scenario name with the stated Pure FP adaptation.
- [x] `testJsonRoundTrip` -> DataBlockSpec, matching scenario name with the stated Pure FP adaptation.
- [x] `testJsonDeserializationViaContentBlock` -> DataBlockSpec, matching scenario name with the stated Pure FP adaptation.
- [x] `testIdAutoGenerated` -> DataBlockSpec, matching scenario name with the stated Pure FP adaptation.
- [x] `testNameIsOptional` -> DataBlockSpec, matching scenario name with the stated Pure FP adaptation.
- [x] `testNullSourceRejected` -> DataBlockSpec, matching scenario name with the stated Pure FP adaptation.

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

The unified immutable Msg replaces Java subclasses and mutable builders. MsgFactorySpec preserves all 26 method names and verifies role, content, metadata, replacement/accumulation, and JSON behavior. Chunk replaces list/varargs overloads; withContent returns a validated Either instead of mutating a builder or throwing. Typed factories reject forbidden blocks at compile time (MsgConstructionSpec). Existing Scala convenience names remain user/assistant/system; identity and timestamp remain absent until explicitly supplied, and identified allocates identity in ZIO. Java subclass identity and eager ID/time allocation are intentionally replaced by these Pure FP contracts. Generation metadata keeps the existing lowercase wire value.

- [x] `userMessage_text` -> MsgFactorySpec, matching scenario name with the stated immutable adaptation.
- [x] `userMessage_nameAndText` -> MsgFactorySpec, matching scenario name with the stated immutable adaptation.
- [x] `userMessage_varargsBlocks` -> MsgFactorySpec, matching scenario name with the stated immutable adaptation.
- [x] `userMessage_nameAndVarargsBlocks` -> MsgFactorySpec, matching scenario name with the stated immutable adaptation.
- [x] `userMessage_listOfBlocks` -> MsgFactorySpec, matching scenario name with the stated immutable adaptation.
- [x] `assistantMessage_text` -> MsgFactorySpec, matching scenario name with the stated immutable adaptation.
- [x] `assistantMessage_acceptsToolUseBlock` -> MsgFactorySpec, matching scenario name with the stated immutable adaptation.
- [x] `systemMessage_text` -> MsgFactorySpec, matching scenario name with the stated immutable adaptation.
- [x] `systemMessage_rejectsNonTextBlock` -> MsgFactorySpec, matching scenario name with the stated immutable adaptation.
- [x] `toolResultMessage_idNameText` -> MsgFactorySpec, matching scenario name with the stated immutable adaptation.
- [x] `toolResultMessage_varargs` -> MsgFactorySpec, matching scenario name with the stated immutable adaptation.
- [x] `toolResultMessage_list` -> MsgFactorySpec, matching scenario name with the stated immutable adaptation.
- [x] `jsonRoundTrip_userMessage` -> MsgFactorySpec, matching scenario name with the stated immutable adaptation.
- [x] `jsonRoundTrip_assistantMessage` -> MsgFactorySpec, matching scenario name with the stated immutable adaptation.
- [x] `jsonRoundTrip_systemMessage` -> MsgFactorySpec, matching scenario name with the stated immutable adaptation.
- [x] `jsonRoundTrip_toolResultMessage` -> MsgFactorySpec, matching scenario name with the stated immutable adaptation.
- [x] `legacyBuilderJson_deserializesToSubclass` -> MsgFactorySpec, matching scenario name with the stated immutable adaptation.
- [x] `userMessageBuilder_textContent` -> MsgFactorySpec, matching scenario name with the stated immutable adaptation.
- [x] `userMessageBuilder_idAndExplicitContent` -> MsgFactorySpec, matching scenario name with the stated immutable adaptation.
- [x] `assistantMessageBuilder_returnsConcreteType` -> MsgFactorySpec, matching scenario name with the stated immutable adaptation.
- [x] `assistantMessageBuilder_generateReasonGoesIntoMetadata` -> MsgFactorySpec, matching scenario name with the stated immutable adaptation.
- [x] `systemMessageBuilder_text` -> MsgFactorySpec, matching scenario name with the stated immutable adaptation.
- [x] `systemMessageBuilder_rejectsNonTextAtBuild` -> MsgFactorySpec, matching scenario name with the stated immutable adaptation.
- [x] `toolResultMessageBuilder_resultShorthandAccumulates` -> MsgFactorySpec, matching scenario name with the stated immutable adaptation.
- [x] `toolResultMessageBuilder_resultsReplacesAccumulator` -> MsgFactorySpec, matching scenario name with the stated immutable adaptation.
- [x] `subclassBuildersProduceJsonRoundTripCompatibleMessages` -> MsgFactorySpec, matching scenario name with the stated immutable adaptation.

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

Same-named RoleContentValidationSpec tests exercise Msg.create and decoding. JSON null content becomes an empty Chunk. Unchecked Msg.apply/copy routes have been removed; compile-time closure and safe updates are covered by MsgConstructionSpec.

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

All 17 methods have assertion-level coverage. ContentBlockSerdeSpec covers serialization and three original decode scenarios; ToolUseSpec covers the remaining 13 methods. Immutable JSON AST/Map and named case-class arguments replace mutable maps and builders; compile-time checks reject mutation. Java null constructor arguments adapt to explicit Scala absence and missing/JSON-null fields at decoding boundaries. Any other JSON input remains supported, including pruned string arguments. Both canonical serverTool and legacy agentscope_server_tool markers are recognized with canonical presence taking precedence. ToolCallState values are protocol data; no permission or lifecycle authority is inferred from model-supplied state.

- [x] `testJsonSerializationWithAllFields` -> ContentBlockSerdeSpec, matching all upstream assertions; ContentBlockCodecSpec additionally checks identical concrete/base representation.
- [x] `testJsonDeserializationWithAllFields` -> ContentBlockSerdeSpec, matching scenario name.
- [x] `testJsonDeserializationWithoutContent` -> ContentBlockSerdeSpec, matching scenario name.
- [x] `testJsonDeserializationWithoutMetadata` -> ToolUseSpec, matching scenario name with the stated immutable adaptation.
- [x] `testJsonDeserializationWithEmptyInput` -> ContentBlockSerdeSpec, matching scenario name.
- [x] `testJsonDeserializationWithNullContent` -> ToolUseSpec, matching scenario name with the stated immutable adaptation.
- [x] `testRoundTripSerialization` -> ToolUseSpec, matching scenario name with the stated immutable adaptation.
- [x] `testInputMapIsUnmodifiable` -> ToolUseSpec, matching scenario name with the stated immutable adaptation.
- [x] `testMetadataMapIsUnmodifiable` -> ToolUseSpec, matching scenario name with the stated immutable adaptation.
- [x] `testConstructorWithThreeParameters` -> ToolUseSpec, matching scenario name with the stated immutable adaptation.
- [x] `testConstructorWithFourParameters` -> ToolUseSpec, matching scenario name with the stated immutable adaptation.
- [x] `testConstructorWithAllFiveParameters` -> ToolUseSpec, matching scenario name with the stated immutable adaptation.
- [x] `testBuilderPattern` -> ToolUseSpec, matching scenario name with the stated immutable adaptation.
- [x] `serverToolMarkerDistinguishesServerAndLocalCalls` -> ToolUseSpec, matching scenario name with the stated immutable adaptation.
- [x] `serverToolMarkerSurvivesJsonRoundTrip` -> ToolUseSpec, matching scenario name with the stated immutable adaptation.
- [x] `withStatePreservesServerToolMarker` -> ToolUseSpec, matching scenario name with the stated immutable adaptation.
- [x] `testEmptyMapsForNullInputAndMetadata` -> ToolUseSpec, matching scenario name with the stated immutable adaptation.

### MsgTests: 1 method

MsgSpec test MsgTests.testConstructor preserves supplied timestamp and metadata with optional content uses a fixed supplied timestamp; no clock effect occurs in construction.

- [x] `testConstructor` -> MsgSpec, matching scenario name.

### Remaining model boundaries

- [x] Close unchecked Msg construction/copy/Product entry points; MsgConstructionSpec verifies compiler rejection, typed dynamic validation, field preservation, wire behavior, and interruptible effectful mapping.
- [x] Cover all 119 pinned message methods with explicit local behavior or independently reviewed adaptations; Java API/wire equivalence remains outside this coverage claim.

## Event audit follow-up

The independent audit found the following gaps; completed corrections are checked below. The earlier six Scala event tests did not establish upstream parity. The upstream legacy agent/EventTest and newer event/AgentEventStreamTest are separate contracts.

- [x] Map the ten legacy EventTest methods and all 20 AgentEventStreamTest declarations, distinguishing 13 enabled tests from seven disabled ordering placeholders.
- [x] Use one canonical tagged representation for all 39 concrete/base event codecs; cross-decoding and legacy leaf decoding are tested.
- [x] Preserve immutable metadata on existing tool-result start/text-delta/end events through decoding, encoding, and repeated source tagging.
- [x] Add tool-result data-delta payload and its immutable metadata contract; AgentEventPayloadSpec verifies round-trip and repeated source tagging.
- [x] Publish ordered inline provider result text/data events with complete result metadata and correlated data reply IDs; preserve call occurrences and cancellation reconciliation.
- [ ] Extend local tool execution to structured/streaming results and propagate their metadata.
- [x] Classify current event/lowercase and upstream type/uppercase wire formats as noninterchangeable; existing Scala wire tags remain canonical for compatibility.
- [x] Implement the separate AgentEventType identifier codec for all 31 canonical names and 11 aliases.
- [ ] Implement explicit field/payload adapters; identifier parsing alone cannot establish compatibility.
- [x] Implement AgentResult, DataBlockStart/Delta/End, ToolResultDataDelta, ExceedMaxIters, RequestStop, and AllToolsDenied payloads and codecs.
- [x] Publish finalized ordinary and structured results after preparation/history commit and before AgentEnd; StructuredCompletionSpec verifies failure, cancellation, fallback, and cleanup.
- [ ] Integrate remaining runtime publication, correlated block assembly, phase stopping, and durable pending-work resumption.
- [x] Correct AgentStart/AgentEnd correlation with one runtime pair per engine attempt. ReActAgentEventSpec verifies identity, private completion, buffering order, failure, cancellation, timeout, and early consumer cleanup. StructuredCompletionSpec now verifies finalized result publication and structured streaming through the same attempt boundary.
- [x] Exercise all 39 variants with repeated source tagging and verify tool-result metadata remains independent of source.

## Event method mapping

The legacy `agent/EventTest` wraps Msg in six coarse event kinds; the newer `event/AgentEventStreamTest` describes a separate event surface. Neither is identical to the current 39 Scala variants. Scala encodes `event` with lowercase names and different payload fields; upstream uses `type` and uppercase names/aliases. This slice preserves the established Scala format. Checked metadata methods below are behavioral adaptations, not cross-language wire fixtures. [AgentEventSerdeSpec](../../modules/core/src/test/scala/com/weisgnosis/agents/core/event/AgentEventSerdeSpec.scala) supplies their local evidence.

### Legacy EventTest: ten enabled methods

The pinned upstream Event, EventType, and EventSource classes are explicitly deprecated since 2.0 in favor of typed AgentEvent. Their retained consumers include upstream harness, AGUI, A2A, chat-completions-web, and Kotlin integrations; this Scala port does not claim compatibility with those legacy consumers. This Scala core deliberately excludes the deprecated envelope instead of introducing a second public event contract without a consumer. The checklist below records completed applicability decisions, **not passing legacy tests or equivalent wire coverage**.

- [x] `testEventTypeValues`: exclude the six-value coarse enum, including ALL filtering; AgentEventType is a distinct identifier contract.
- [x] `testEventCreation`: exclude REASONING/message/isLast construction. Shared message preservation is covered by AgentEventPayloadSpec and StructuredCompletionSpec, without claiming the legacy wrapper semantics.
- [x] `testEventIsLast`: exclude the Boolean legacy accessor. Scoped completion and AgentEnd are separate runtime contracts, not a replacement for per-message isLast.
- [x] `testEventGetMessageId`: exclude the delegating getter. Msg.id and AgentResultEvent.result preserve optional identity; no eager ID allocation is added.
- [x] `testEventToString`: exclude exact Java diagnostic formatting and getter-based representations.
- [x] `testEventWithEmptyContent`: exclude the HINT wrapper and diagnostics. Msg factory/codec tests cover valid empty content independently.
- [x] `testEventWithMultipleContentBlocks`: exclude the SUMMARY wrapper. Immutable Msg content and AgentResult payload preservation have separate tests.
- [x] `testEventDifferentTypes`: exclude arbitrary wrapping in the five coarse message categories.
- [x] `testStreamingScenario`: exclude cumulative Msg snapshots sharing an ID with isLast. Actual live model streams now carry runtime reply/request correlation; cumulative snapshot compatibility remains excluded.
- [x] `testEventJsonSerializationIsLastFieldName`: exclude the legacy type/message/isLast wire format; current lowercase event codecs are not equivalent fixtures.

Unsupported legacy behavior includes StreamOptions filtering, ALL, default final-result exclusion, cumulative message snapshots, Java constructors/getters, and structured EventSource attribution (agent/session/task IDs, depth, and path). Current optional string source tagging is not an EventSource adapter. A future consumer requiring that protocol needs an explicit separately scoped adapter; no compatibility adapter is currently promised or implemented.

- [x] Implement actual model-stream reply/request correlation through the typed event API.
- [ ] Complete remaining model/tool block ordering and attribution.
- [ ] Define and integrate richer subagent source attribution with the planned supervisor.

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
- [x] `toolResultDataDeltaEventMetadataRoundTrip` -> AgentEventPayloadSpec, matching metadata/data/correlation assertions with immutable metadata and the established Scala wire representation.
- [x] `toolResultStartEventEmptyMetadata`: same-named Scala serde test; immutable metadata and current Scala wire fields.
- [x] `allPythonValuesResolve`: same-named AgentEventTypeSpec test of identifiers only; payload adaptation remains separate.

### AgentEventStreamTest: seven disabled ordering declarations

These upstream methods contain comments rather than executable assertions and their enclosing class is disabled. They remain explicit implementation requirements, not passing upstream evidence. The new ReActAgentEventSpec tests actual runtime boundaries and transport completion, without claiming the entire prescribed model/tool event sequence.

- [ ] `singleIterationOrder`: complete and verify the prescribed runtime sequence.
- [ ] `thinkingBeforeText`: complete and verify the prescribed runtime sequence.
- [ ] `toolCallCycle`: complete and verify the prescribed runtime sequence.
- [ ] `hitlReentry`: complete and verify the prescribed runtime sequence.
- [ ] `dataBlockEmission`: complete and verify the prescribed runtime sequence.
- [x] `exceedMaxIters` -> ReActAgentEmissionSpec verifies the implemented limit event before AgentResult and AgentEnd, with the same attempt replyId and completed iteration count; the upstream declaration itself remains disabled.
- [ ] `streamBoundaries`: complete and verify the prescribed runtime sequence.

### Implemented payloads and remaining runtime contracts

The eight payload classes below are implemented and verified independently of their runtime integrations. AgentEvent now has 39 Scala variants; the separate upstream identifier enum retains its 31 names. Existing lowercase event payloads remain canonical.

| Implemented variant | Payload | Runtime integration status |
| --- | --- | --- |
| AgentResult | result: Msg | Runtime publishes after preparation and history commit, before AgentEnd; pre-commit failures publish no finalized result |
| DataBlockStart / End | replyId, blockId | Stable block correlation and provider assembly |
| DataBlockDelta | replyId, blockId, delta: String | Delta is text; it is not a ContentBlock |
| ToolResultDataDelta | replyId, toolCallId, toolCallName, data: ContentBlock, metadata | Inline provider output is published in order with full metadata; local structured/streaming output remains pending |
| ExceedMaxIters | replyId, maxIters, currentIter | Runtime guard verified with correlated replyId and completed iteration count |
| RequestStop | reason, available typed GenerateReason; default MiddlewareStopRequested | Cooperative phase stop and durable pending-work resumption |
| AllToolsDenied | Chunk[ToolUse], empty by default | Permission/HITL integration and middleware stop decision |

Upstream base events also have identity/time, optional source, and metadata. This payload increment preserves immutable optional source and data-delta metadata but does not add the universal id/createdAt envelope or translate uppercase type objects. A future envelope adapter must use explicitly supplied identity/time or an effectful allocator rather than eager UUID/clock access. Class presence alone is not runtime evidence: the pinned source has no production DataBlock event construction sites. A RequestStop event is distinct from fiber cancellation; supporting its type does not implement phase suspension or resumption.

Inline event metadata intentionally preserves the complete result map; pinned upstream inline events synthesize only serverTool=true. ReActAgentEmissionSpec verifies this Scala contract, duplicate-ID occurrence handling, and local cancellation matching. It does not establish provider continuation without inline results, local structured tool streams, or complete event ordering.

## Completion parser hardening

- [x] Preserve the three original reasoning/tool/usage parsing scenarios through a typed Either result.
- [x] Add sixteen Scala protocol-boundary regressions in ReasoningAdapterValidationSpec, including required choices, strict integer/index handling, duplicate keys, provider errors, legacy function-call rejection, and mixed-choice rejection.
- [ ] Complete the method-level inventory of provider extension parser suites and transport suites; these new hardening tests are not claimed as sixteen upstream methods.
- [x] Implement indexed text/thinking assembly with signatures, typed finish validation, strict completed object arguments, preserved raw content, and interrupted sanitization; thirteen accumulator regressions cover this scope.
- [x] Validate full JSON documents and duplicate keys through JsonBoundary, with six paired tests and frame/tool suffix regressions.
- [x] Implement text/function request serialization with sixteen local regression tests, including paired histories, explicit reasoning replay, and unsupported-content rejection. These tests are not claimed as sixteen mapped upstream methods.
- [ ] Map the full upstream formatter surface, implement remaining request extensions and provider signature/tool-metadata decoding, and complete remaining provider and tool-result event integration.

The pinned extension OpenAIStreamingToolCallTest and OpenAIResponseParserTest were inspected for fragment, usage, and error semantics. They are outside the earlier core-only inventory, and their full applicability mapping remains open. The current adapter intentionally supports indexed function-call deltas and fails unsupported legacy calls instead of silently dropping them.

The pinned core ToolCallsAccumulatorTest and ReasoningContextTest were inspected for parallel calls, raw argument preservation, interruption sanitization, and latest-snapshot usage. Mandatory compatible finish markers, strict complete object arguments, and stable identities are deliberate Scala hardening. LlmChunk has raw argument fragments rather than mutable parsed-map updates. Tool metadata, parsed-map merge scenarios, richer reasoning details, and the full method-level accumulator mapping remain pending; current signature support is for ThinkingDelta, not tool signatures.

## Live model stream adaptation

- [x] Six ChatCompletionsClientSpec scenarios verify incremental publication, real HTTP tool cycles, request/reply correlation, late tool identity, incomplete/malformed input, and cancellation.
- [x] Three ModelStreamPublisherSpec scenarios verify indexed text/thinking, interleaved tool deltas, and validated complete ending batches; two ModelStreamContextSpec scenarios cover all sixteen affected event variants and legacy payloads.
- [x] Verify completed local tool results with request provenance, persistence before publication, and per-call start/delta/end ordering.
- [ ] Complete incremental/structured tool output, richer provider content, and method-level formatter/transport mapping.

The disabled upstream ordering sketches remain unchecked: they put MODEL_CALL_END before buffered content and close thinking before text starts. The implemented live contract publishes provisional deltas during the request and closes blocks only after validated completion. ModelResponseEnd follows scope cleanup. This is an explicit runtime adaptation, not exact ordering parity.

ReActAgentToolEventSpec adds six local runtime regressions for persistence, errors, empty output, parallel completion, cancellation, and reused-ID provenance. Inline result tests now assert context on every result variant. These are Scala runtime contracts, not six newly mapped upstream methods. Cancellation still reconciles unfinished history without claiming completed tool events.

## Structured HTTP and JSON boundary hardening

- [x] Verify five real loopback HTTP scenarios for native schema/thinking/usage, synthetic root definitions and compressed streaming completion, HTTP-400 fallback without duplicated input, HTTP-503 failure without retry, and strict native payload failure.
- [x] Verify six structured-boundary scenarios, including duplicate metadata, captured payloads, wrapper keys, and ignored nested fields; preserve valid direct/wrapped adaptation.
- [x] Move shared validation to core.json.JsonBoundary and add a sixth direct-AST regression without serialization overhead.
- [ ] Complete general model-call middleware and broader legacy hook behavior.

These twelve added tests are local integration/hardening evidence, not twelve newly mapped upstream methods. Four initial regressions and one additional wrapper regression failed before their respective corrections. JSON Schema validation and complete provider compatibility are not claimed. Auto fallback currently recognizes status 400/422/501, not a complete provider capability protocol.

## Explicit structured-output capability selection

- [x] Cover all declared/unknown capability routes, explicit modes, application-tool execution, rejection fallback, and preset-format handling in twelve agent tests.
- [x] Cover derived capability/config codecs, native tool defaults, invalid declarations, and provider-body exclusion in four client tests.
- [x] `review_fp` and `review_harness` approved the final five-file capability increment against `06bd2e1` on 2026-09-29 after the preset-format correction. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 420 core and 40 harness tests.

The two newly completed mapped methods are native-with-tools Auto selection and default tool support. Existing native usage evidence in StructuredOutputSpec checks every ChatUsage field, including elapsed time. Host declarations are not provider discovery. Unknown preserves previous behavior; typed post-reasoning replacement/removal and text-delta replacement adaptations are mapped below; broader middleware remains pending. General JSON Schema validation is not part of this scope.

## Visible model-text transformation

- [x] Verify four composition/error-type tests, nine live-client scenarios, and one error-codec test, including the mapped real HTTP fence-removal behavior.
- [x] `review_fp` and `review_harness` approved the final seven-file text-transformation increment against `02b4654` on 2026-09-29 after the fallback-provenance correction. No findings remain within this scope. Full formatting, fatal-warning compilation, and verification passed 434 core and 40 harness tests.
- [ ] Implement general model-call request/interception middleware and remaining legacy hook operations.

This increment closes one previously pending upstream method through a documented typed adaptation. Fourteen local tests do not represent fourteen upstream methods. The Auto retry regression failed before narrowing callback failures to TextTransformationError. Raw ModelResponseChunk payloads remain original diagnostics; visible text is transformed before runtime assembly. The provider protocol and completion checks cannot be modified through this interface.

## Post-reasoning decisions and accounting

- [x] Verify three policy/codec tests and nineteen runtime scenarios, including typed discard for the final previously pending structured-output method.
- [x] `review_fp` and `review_harness` approved the final six-file post-reasoning increment against `2a400e0` on 2026-09-29 after the legacy-usage correction. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 456 core and 40 harness tests.
- [ ] Map remaining legacy hook suites and general middleware scenarios; eighteen closed structured-output mappings do not establish those domains.

Twenty-two added local tests cover more than the one newly closed upstream mapping. Replacement cannot forge canonical or legacy usage, discard does not reset thinking retries, and all returned model observations count across fallback attempts. Synthetic residual history clears both usage representations. The legacy duplication regression failed before correction. Existing model events remain original observations, while final results/history and dispatch reflect accepted policy decisions.

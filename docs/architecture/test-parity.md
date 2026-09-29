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
| ToolResultDataDelta | replyId, toolCallId, toolCallName, data: ContentBlock, metadata | Inline provider and processed completed local output blocks are published with full metadata; native structured tool returns and incremental execution remain pending |
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

## ToolResultEvictionMiddlewareTest: seven methods

Pinned source: agentscope-harness/src/test/java/io/agentscope/harness/agent/middleware/ToolResultEvictionMiddlewareTest.java at `f98a685fdc4589f81e626e1686ac2c63d14a2304`. These mappings distinguish runtime/standalone adaptations from the upstream mutable two-view middleware contract.

- [x] `evictsImmediateReasoningInputAndAgentStateUsingDefaultLocalPath` -> same-named ToolResultEvictorIntegrationSpec disk test plus its history/model-input/event scenario. Adaptation: eviction occurs after local completion, with one committed immutable history and an explicit trusted store, rather than independently rewriting ReasoningInput and AgentState. Scala uses large_tool_results with agent/call/hash filename prefixes, not an agent subdirectory; cache metadata uses eviction_cache_key/eviction_path.
- [x] `evictsCanonicalAgentStateWhenReasoningHookRemovedLargeResult` -> same-named ToolResultEvictorHistorySpec real-disk test. Adaptation: canonical preparation/eviction commits before the separate ModelInputTransform view, and the model receives exactly the projected immutable value without removed pairs. Final canonical history retains previews and complete backing payloads. Java reference identity, orphan input, and mutable middleware ordering are not replicated.
- [x] `cachedEvictionPreservesHookRewrittenMetadataAndState` -> ToolResultEvictorSpec cached-content scenario. Adaptation: independently supplied immutable results preserve changed metadata/state across repeated content; this is not Java object-identity or middleware-view equivalence.
- [x] `realLargeOutputUsingPlaceholderPrefixIsStillEvicted` -> same-behavior ToolResultEvictorSpec regression checks that original full content reaches the store despite a placeholder-like prefix.
- [x] `writeFailureLeavesReasoningInputAndAgentStateUntouched` -> runtime persistence-failure integration and standalone fallback. Adaptation: exact raw result values remain in committed history and the next model request; Java reference identity and RuntimeContext forwarding are not replicated.
- [x] `preservesConfiguredAbsolutePathWhileTrimmingTrailingSlashes` -> same-named ResultStorageLocationSpec test verifies external canonical location, original stored content, preview paths, and repeated separators. Adaptation: storage-owned ResultStorageLocation replaces evictionPath; absolute directories must already exist under explicit host ownership. Scala retains flat agent/call/hash filenames and does not replicate Java RuntimeContext identity.
- [x] `reusedToolCallIdUpdatesOnlyLatestOccurrenceAndKeepsBothFiles` -> runtime reused-ID integration plus standalone content-key tests. Earlier previews remain unchanged and distinct content retains distinct cache entries. Actual local store publication is verified separately; path layout is intentionally different.

- [x] Verify three processor tests, seven core lifecycle scenarios, and five harness integrations (including actual disk persistence).
- [x] `review_fp` and `review_harness` approved the final eight-file local-result processing/eviction increment against `8850a60` on 2026-09-29. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 466 core and 45 harness tests.
- [x] Install opt-in supplied-history eviction through ToolResultEvictor.preparer; ToolResultEvictorHistorySpec covers local/inline input, failure, cancellation, and compaction order.

Fifteen added tests are local evidence for these mappings and hardening boundaries, not fifteen upstream methods. Remaining harness domains still require exhaustive method-level audit.

## Core authorization boundary and pending HITL mapping

The core ToolAuthorizer port provides scoped approval/denial before local dispatch. ToolAuthorizerSpec and ReActAgentToolAuthorizerSpec cover fifteen local tests. These establish runtime argument provenance, denial observations/events, interruption, signed-input protection, and occurrence pairing; they do not establish the pinned two-call confirmation protocol.

- [x] Independently review the core authorization port and fifteen local tests; verify 481 core / 45 harness tests. No two-call confirmation method is marked covered by this narrower scope.
- [ ] Map ReActAgentHitlTest.pendingToolRecoveryPreservesModifiedArgumentsFromConfirmation after durable confirmation integration; interactive argument rewriting is shared behavioral evidence only.
- [ ] Map deniedConfirmResultWithCustomReasonWritesReasonToContext, deniedConfirmResultWithoutReasonKeepsDefaultText, and deniedConfirmResultOnResumeEmitsToolResultEventTriple after durable confirmation handling; interactive denial events are shared behavioral evidence only.
- [ ] Implement and map durable pause/resume, partial confirmations, persisted reply-ID recovery, and mixed-batch permission event ordering. Core approval scopes remain within one active invocation.

## Interactive permission adaptation

The opt-in harness adapter adds sixteen tests and runs within one active invocation. Verified shared behavior is narrower than the complete ReActAgentHitlTest methods at pinned upstream `f98a685fdc4589f81e626e1686ac2c63d14a2304`:

- [x] Execute modified confirmed arguments and persist their provenance before the tool runs; InteractivePermissionIntegrationSpec also verifies one initial model request and one post-tool continuation.
- [x] Preserve custom denial reasons and emit exact correlated Start/Delta/End ordering with matching identity/text/state; no denied tool executes.
- [x] Retain the Scala default decline text, "Tool execution declined". Upstream expects "Permission denied by user"; this is an explicit text difference.
- [x] Recheck both gates against modified arguments and latest policy, merge parallel rule additions, isolate repeated call IDs, and clean cancelled waiters.
- [ ] Complete pendingToolRecoveryPreservesModifiedArgumentsFromConfirmation, deniedConfirmResultWithCustomReasonWritesReasonToContext, deniedConfirmResultWithoutReasonKeepsDefaultText, and deniedConfirmResultOnResumeEmitsToolResultEventTriple: their two-call pause/resume protocol is not implemented by this active-invocation adapter.
- [ ] Complete durable partial confirmations, persisted reply-ID recovery, automatic denied-batch ordering before approval notices, authenticated transport, and exhaustive permission mode/rule parity.

The adapter publishes a narrow ToolAuthorizationRequest callback, not upstream approval event envelopes. Confirmed policy updates are independent of later core validation/execution; tests explicitly retain rules after a signed-input denial. External gate changes and post-decision revocation are not transactional guarantees.

## Standalone compactor hardening

These eight Scala regressions cover occurrence pairing and safe argument pruning; they do not complete the upstream compaction inventory.

- [x] `review_fp` and `review_harness` approved the final two-file compactor hardening against `6ed928a` on 2026-09-29. No findings remain in this scope. Eight new regressions failed before the correction; full formatting, fatal-warning compilation, and verification now pass 481 core and 69 harness tests. That earlier acceptance covers standalone pairing and argument pruning; the integrated scope is recorded below.

## Scoped history preparation and compactor integration

- [x] `review_fp` and `review_harness` approved the final nine-file history-preparation increment against `3c6958f` on 2026-09-29. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 497 core and 73 harness tests, including twenty added tests. Acceptance covers structural rewrite validation and scoped compactor integration; semantic summary fidelity and exhaustive provider-format validation are excluded.

- [x] Verify complete pairing and system-message preservation for changed histories, identity compatibility, recovery ownership after shrinkage, preparation cleanup/failure, and shared invocation usage across retries/fallback.
- [x] Complete applicability accounting for the seven pinned ConversationCompactorTest methods and ten CompactionMiddlewareTest methods, with the explicit adaptations, divergences, and exclusion below. This does not establish literal middleware equivalence or semantic summary fidelity.

## Inline processing and cleanup failure evidence

- [x] Extend the installed result processor to matched inline provider observations, preserving local-dispatch exclusion and returning processed canonical content. ToolResultEvictorIntegrationSpec verifies actual full-payload storage and processed event/reply consistency.
- [x] Add ten inline-processing and three local processor-defect regressions. Real cooperative and external cancellation failures were reproduced before explicit scoped worker ownership corrected the loss of finalizer defects; the timeout path has its own deterministic regression.
- [x] Independently approve the final scope against `2b99226` and verify 510 core / 74 harness tests. Commit `cc8fa7b` preserves all seven identities from that review, including the evictor comment-only change. The agent README retains unchanged identities; current history-eviction identities are in the eviction README.
- [ ] Complete remaining upstream middleware method mappings; local history/preview hardening tests do not establish additional Java method parity.

- [x] `review_fp` and `review_harness` approved the final nine-file history-eviction scope against `cc8fa7b` on 2026-09-29. Twenty-two added regressions and full formatting/fatal-warning verification passed: 516 core and 90 harness tests. Two new regressions exposed and closed strict-number and Windows storage-layout gaps. No findings remain within store-backed integrity, explicit workspace trust, and validated composition; absolute storage is covered by a later scope; request-only input views are covered by a later scope.

- [x] `review_fp` and `review_harness` approved the final three-file storage-location scope against `3718e18` on 2026-09-29. Eight added tests and full formatting/fatal-warning verification passed: 516 core and 98 harness tests. No findings remain within explicit host ownership and canonical-location validation. Dynamic symlink/junction checks, hostile mutation protection, and filesystem object identity are not claimed. The absolute-storage increment mapped six methods; the subsequent model-input scope below closes the seventh.

- [x] `review_fp` and `review_harness` approved the final seven-file model-input scope against `40f6f05` on 2026-09-29. Sixteen added regressions and full formatting/fatal-warning verification passed: 529 core and 101 harness tests. No findings remain within request-only projection, structural validation, canonical-state isolation, and eviction integration. Broader legacy hook/middleware parity remains pending. All seven eviction methods now have explicit reviewed adaptations; broader compaction/memory/middleware parity remains open.

## Compaction method applicability

Pinned harness sources are `agent/memory/compaction/ConversationCompactorTest.java` and `agent/middleware/CompactionMiddlewareTest.java` at upstream `f98a685fdc4589f81e626e1686ac2c63d14a2304`. Evidence: [standalone suite](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/memory/ConversationCompactorSpec.scala) and [integration suite](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/memory/ConversationCompactorIntegrationSpec.scala). The standalone suite uses the seven exact upstream method names. Summary identity is a reserved message name, not a generated ID prefix; assistant tails isolate routing assertions from separately tested user coalescing.

- [x] `compactIfNeeded_routesPriorSummaryToSummaryInputButNotFlushOrTail`: exact prior-summary/prefix input, raw-prefix-only flush, one named replacement, and exact retained tail.
- [x] `compactIfNeeded_mergesMultiplePriorSummariesIntoOneReplacement`: exact two-summary/two-message input, ordered M1/M2 flush, and one replacement with unchanged tail.
- [x] `compactIfNeeded_summaryOnlyPrefixDoesNotBecomeEmptyHistory`: one summary input, one empty flush invocation, and replacement plus unchanged tail.
- [x] `compactIfNeeded_chainedCompactionUsesPreviousSummaryAndNewPrefix`: both exact prompts and flush sequences, prior replacement plus new prefix, and second replacement without an old-summary chain.
- [x] `compactIfNeeded_skipsFlushWhenDisabledAndStillUsesPriorSummary`: dependency adaptation; omit the flush callback to select the default no-op effect. Summary input and output remain exact. No Boolean flush mode is introduced.
- [x] `compactIfNeeded_propagatesInterruptedMemoryFlush`: structured ZIO interruption stops offload and summary. The integration suite also checks waiting flush cleanup, unchanged canonical prefix, and recovery.
- [x] `compactIfNeeded_propagatesInterruptedMessageOffload`: structured interruption after flush stops summary. Java wrapped-exception representation is adapted to the ZIO Cause channel.

Middleware decisions are intentionally classified separately:

- [x] `ordinaryCompactionFailureFallsBackToOriginalInputOnce`: deliberate divergence and effect-boundary adaptation. The `typed flush failure preserves original input without downstream fallback` integration test proves HistoryPreparationError, unchanged input, and zero downstream calls. Scala does not reproduce Java synchronous construction-error fallback.
- [x] `ordinarySummaryFailureContinuesWithCompactedInput`: deliberate divergence. `summary failures remain preparation errors and cannot trigger native fallback` proves one summary attempt, zero downstream calls, and retained original input. A failed summary never replaces history with failure text.
- [x] `interruptedSummaryCompactionPropagatesWithoutCallingNext`: same-named test proves summary self-interruption, zero requests, original prefix, and one recovery.
- [x] `asynchronousInterruptedSummaryCompactionPropagatesWithoutCallingNext`: same-named test uses a Promise entry signal, external fiber cancellation, completed cleanup, zero requests, and original prefix plus recovery.
- [x] `wrappedInterruptedCompactionPropagatesWithoutCallingNext`: representation adaptation. `mixed summary interruption and cleanup defect propagate without downstream dispatch` verifies both Cause components, zero requests, unchanged history, and no successful recovery masking the defect.
- [x] `cyclicCompactionFailureCauseFallsBackWithoutLooping`: reviewed exclusion. Scala does not traverse Throwable cause chains to recognize cancellation; it consumes structured Cause. Java cycle detection has no corresponding mechanism here, and its ordinary-failure fallback is intentionally absent. This is an applicability decision, not a passed equivalent test.
- [x] `interruptedDownstreamPropagatesWithoutRetryingNext`: same-named test proves successful preparation commits before exactly one interrupted model call, preserving the prepared prefix and one recovery.
- [x] `concurrentSessionInterruptDoesNotAffectOtherSession`: ownership adaptation. The named integration test shares one compactor/client between two independently owned agents, interrupts one waiting summarizer, and proves the other completes with isolated history and exact dispatch counts.
- [x] `modelStreamInterruptProducesOneCallAndInterruptedTerminalReason`: same-named test bypasses compaction below the trigger, interrupts a waiting model, and verifies one call, no summaries, and interrupted result metadata.
- [x] `interruptingOneSessionDoesNotCancelConcurrentSession`: ownership adaptation. Two agents share a waiting client and compactor; cancelling one leaves the other able to finish, with exactly one request per history and both cleanups complete. One Scala agent owns one history and serializes runs; this does not claim Java's concurrent multi-session API on one agent.

- [x] `review_fp` and `review_harness` approved the two test-file diff against `52da827` on 2026-09-29. Thirteen additional cases and strengthened existing assertions produce 39 compactor tests. Full formatting and fatal-warning verification passed 529 core / 114 harness tests. No production changes or unresolved findings in this scope; [current identities](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md) bind the approval.
- [ ] Complete the remaining memory-service and broader middleware inventories. The 17 decisions above cover only the two named upstream suites.

## Daily-memory extraction applicability

Pinned `agent/memory/MemoryFlushManagerTest.java` has two test methods. [MemoryFlushManagerSpec](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/memory/MemoryFlushManagerSpec.scala) uses their exact names:

- [x] `flushMemories_skipsCompactionSummariesWhenRealMessagesRemain`: exactly two extraction messages, genuine conversation included, summary excluded, and NO_REPLY produces no append.
- [x] `flushMemories_summaryOnlyInputDoesNotCallModel`: no extraction, storage reads, or append for an internal-only window.

The new implementation deliberately propagates typed context-read errors instead of substituting empty context. A single Clock instant and explicit zone replace independent date samples. Scala coalesced-user provenance is validated and expanded before summary exclusion. Storage is an injected daily-only capability; these method mappings establish extraction behavior, not filesystem parity. The three integration tests establish the actual compactor/runtime boundary.

Upstream `MemoryFlushManagerOffloadTest.java` exercises a deprecated delegate to SessionTranscriptWriter. Its observation behaviors are covered by SessionTranscriptWriterSpec and local reopen evidence by LocalTranscriptStoreSpec, with explicit storage-layout/wire adaptations. Transcripts retain summaries even though daily extraction excludes them:

- [x] `offloadMessages_skipsAlreadyPersistedPrefix_andKeepsChain`: local adaptation; four persisted structural keys, overlapping windows, exact parent continuity and reopen. UUID identities and one versioned local file replace Java IDs/layout.
- [x] `offloadMessages_nullAndBlankIds_stillAppendButDoNotBreakStableIds`: Option/blank adaptation; six persisted observations across reopen, anonymous replay, stable-key deduplication and valid linkage.
- [x] `offloadMessages_persistsCompactionSummariesAndDedupesStableIds`: named summary projection has exact keys/texts; local reopen fixture preserves summary/user content and stable deduplication.
- [x] `review_fp` and `review_harness` approved the seven-file daily-memory extraction increment against `738b216` on 2026-09-29. No findings remain within the injected-store capability, scoped client extraction, provenance/date handling, one-manager serialization, and compactor integration. Full formatting/fatal-warning verification passed 529 core / 136 harness tests, including 22 new tests. Filesystem persistence, cross-manager/process guarantees, consolidation, watermarks, and transcript offloading are excluded.

## Local daily-memory backend hardening

- [x] `review_fp` and `review_harness` approved the final seven-file local daily-memory backend against `d1a4cf2` on 2026-09-29. Nineteen added tests exposed and corrected pending cancellation being reported as success after admitted work. Full formatting/fatal-warning verification passed 530 core / 154 harness tests. Acceptance covers cooperative ownership, scoped cleanup, day-derived paths, atomic replacement, and typed storage errors on the tested Windows filesystem. Crash durability, exactly-once delivery, hostile mutation protection, dynamic symlink/junction behavior, and automatic stale-lease recovery are excluded.

- [x] Verify 12 real filesystem cases, six scoped ownership cases, and one new core error-codec case. Source identities are recorded in the [memory contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md).
- [ ] Verify cross-process contention, dynamic symlink/junction scenarios, and future recovery tooling separately. These regressions add Scala backend guarantees; they do not close the three pending transcript methods or future consolidation mappings.

## Consolidation authority and remaining filesystem parity

The new ConsolidationStore/MemoryConsolidator contracts deliberately strengthen upstream authority: output budget is checked after extraction, and replacement content and acknowledged inputs commit atomically against a never-reused revision. Upstream uses a prompt-only limit, separate MEMORY.md/watermark writes, and versioned CAS on the watermark entry alone. The process-local MemoryRepository proves the stronger contract; it does not implement the upstream filesystem or distributed-store API.

- [x] `review_fp` and `review_harness` approved the final six-file consolidation increment against `46c6174` on 2026-09-29. Twenty-one new tests and full formatting/fatal-warning verification passed 530 core / 175 harness tests. No findings remain within process-local atomic content/progress commits, separate writer capabilities, scoped extraction, configured-counter budget enforcement, and conflict handling. Filesystem transactions, persistent watermarks, crash recovery, and the five upstream filesystem mappings remain outside this scope.

Pinned `agent/memory/MemoryConsolidatorFilesystemTest.java` contains five tests. The local backend supplies three explicit adaptations below; the remote scenarios remain pending. These are not timestamp-wire equivalence claims:

- [x] `readWatermark_returnsEpochWhenStateAbsent`: reviewed local adaptation in LocalMemoryRepositorySpec; missing checkpoint leaves every daily input pending and creates no curated/checkpoint payload. No Instant.EPOCH semantics.
- [ ] `watermark_roundTripThroughFilesystem`: injected backend timestamp round trip.
- [ ] `watermark_doesNotCreateLocalFile`: selected remote authority with no local fallback write.
- [x] `stateFileRelPath_matchesConstant`: reviewed local adaptation in LocalMemoryRepositorySpec; exact memory/.consolidation_state location with versioned JSON contents.
- [x] `watermark_localFallback_whenNoFilesystem`: reviewed local adaptation in LocalMemoryRepositorySpec; explicitly selected local repository persists checkpoint and progress across reopen, without automatic fallback or timestamp semantics.

The 21 new Scala tests verify atomic content/progress, conflict preservation, deterministic inputs, configured-counter budget enforcement, writer capabilities, and failure/cancellation cleanup. They are not substitutes for these five persistence mappings.

- [x] `review_fp` and `review_harness` approved the final nine-file persistence increment against `e4bd6b0` on 2026-09-29. No findings remain within cooperative local ownership, strict journal validation, recovery before API operations, conditional content/progress publication, and persisted generation tracking. Full `sbt scalafmtAll scalafmtSbt verify` passed 530 core / 209 harness tests, including 34 new persistence tests, with zero compiler warnings. Acceptance covers constructed interrupted-publication states on the tested Windows filesystem; live crash/fault-injection, fsync durability, remote storage, and hostile mutation are excluded.

## Transcript observation applicability

Pinned SessionTranscriptWriterTest has four methods; MemoryFlushManagerOffloadTest has the three delegates listed above. The first Scala increment provides process-local observation evidence. LocalTranscriptStore now adds strict versioned JSONL persistence; Java wire/layout equivalence is not claimed.

- [x] `deriveEntries_emitsStructuredToolUseAndResult_withPairing`: exact assistant text, call IDs/names/input, result text/state/types, and structural occurrence keys. UUID entry identity replaces concatenated Java IDs explicitly.
- [x] `deriveEntries_placeholderForImageOnlyMessage`: an empty-text image placeholder; additional empty-message evidence is included.
- [x] Implement observation-level behavior from `appendMessages_isIdempotent_andKeepsChain`: overlapping batches deduplicate and actual parent UUIDs form one continuous chain. Local persistence/reopen evidence now completes the adaptation below.
- [x] Implement observation-level behavior from `offloadMessages_skipsAlreadyPersistedPrefix_andKeepsChain`: four exact stable keys and continuous parents after overlapping batches.
- [x] Implement observation-level behavior from `offloadMessages_nullAndBlankIds_stillAppendButDoNotBreakStableIds`: Option/blank adaptation, six exact payloads, stable-key deduplication, and valid parent chain; no Java-null API.
- [x] Implement observation-level behavior from `offloadMessages_persistsCompactionSummariesAndDedupesStableIds`: exact summary/user keys and texts are retained once.
- [x] Complete four local append/offload adaptations with real line counts, overlap/reopen, anonymous replay, retained content, and parent continuity. LocalTranscriptStoreSpec and named SessionTranscriptWriterSpec fixtures jointly supply evidence. The host selects one existing directory containing transcript.jsonl; Java concatenated IDs, agents/session paths, SessionEntry wire format, dual-file/index behavior, and remote mirroring are deliberately excluded.
- [ ] `appendMessages_withTranscriptStore_writesImmutableSegments`: immutable storage segments remain unimplemented; no mirror, timing, or delivery claim is inferred.

Additional Scala tests cover duplicates within a batch, repeated tool-call IDs, delimiter-safe structural keys, first-observation retention, isolated stores, recording-time laziness, complete collision rollback, concurrent overlapping batches, exact preview boundaries, non-text results, and observable storage failure/cancellation. The upstream writer assigns parents before filtering and synthesizes colliding same-ID tool keys; the store-owned transition avoids those defects. Upstream file/mirror/index failures can be swallowed; the Scala boundary preserves typed failure. Compactor offload is installed explicitly and does not imply complete turn-boundary recording.

- [x] The local persistence increment against `e4dfc67` was approved by `review_fp` and `review_harness`; 27 new tests and full verification passed 530 core / 258 harness tests. Unicode preview splitting was reproduced and fixed in both input/output paths; exact prefixes and reopened equality are checked.

## Transcript completion boundary hardening

The pinned upstream has no TranscriptMiddlewareTest counterpart. TranscriptMiddleware.onAgent appends on normal completion and swallows append failures; failure/cancellation skip that append. The Scala observer deliberately covers terminal failure/cancellation, requires scoped observation, and exposes typed errors to direct callers and drained streams. Early consumers do not receive subsequent terminal errors. This is an explicit runtime hardening adaptation, not a claim of one-to-one middleware method parity.

- [x] Verify 22 core port/runtime cases and six harness completion cases, including local reopen, recovery persistence, identity policy and compactor prefix offload.
- [x] Obtain independent FP/invariant approval against `0349b1a`; full verification passed 552 core / 264 harness tests.
- [x] Map and implement local memory flush/consolidation triggers, isolation, throttling and asynchronous lifecycle adaptations below.
- [ ] Complete remote trigger/isolation combinations and retention lifecycle behavior.
- [ ] Implement remote immutable transcript segments and session indexes.

Exact reviewed identities are in the [core agent contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md).

## Memory trigger admission applicability

The pinned gate and middleware suites are adapted to MemoryTriggerGateSpec and the typed policy/interval suites. Tests use an explicitly shared in-memory authority instead of Java static maps; the host binds that authority to one memory owner. Structural keys preserve operation/scope separation without delimiter concatenation. User identity remains user-or-anonymous rather than adopting filesystem-specific session fallback. Admission is not scheduler or execution-lock evidence.

PeriodicGateTest:

- [x] localGate_firstClaimWinsThenThrottles: first admission at clock origin, immediate rejection, exact renewal.
- [x] localGate_differentNamesAreIndependent: independent structural user/session keys and operation keys replace arbitrary string names.
- [x] localGate_releasesAfterGap: just-before/exact expiry replaces Thread.sleep timing.
- [ ] storeBackedGate_firstClaimWinsThenThrottles: distributed/storage-backed gate remains unimplemented.
- [ ] storeBackedGate_sharedAcrossInstances: shared local capability does not establish remote CAS behavior.

MemoryFlushMiddlewareTriggerTest:

- [x] alwaysMode_returnsTrueOnEveryCall: repeated Admitted decisions without reservation allocation.
- [x] neverMode_returnsFalseOnEveryCall: repeated Disabled decisions without reservation allocation.
- [x] throttledMode_firstCallWinsThenBackOff: immediate first admission followed by suppression.
- [x] throttledMode_smallGapEventuallyReleases: deterministic exact-expiry renewal.
- [x] throttledMode_zeroGapNormalisesToAlways: smart-constructor normalization and repeated admission.
- [x] throttledMode_perUserIsolation_userBNotBlockedByUserA: distinct user slots.
- [x] throttledMode_anonymousCallersShareOneSlot: absent/empty/blank optional identity adaptation; Java null fixtures are not reproduced.
- [x] sessionScope_timerKeyUsesSessionId: typed Session identity keys; no public string-key API.
- [x] sessionScope_sameUserDifferentSessionsAreIndependent: distinct session identities remain independent; unrelated user identity is absent from that ADT case.
- [x] agentScope_timerKeyIsConstant: constant Agent slot in one explicit owner.
- [x] agentScope_allCallersShareOneThrottleSlot: repeated Agent admissions share the same reservation.
- [x] globalScope_allCallersShareOneThrottleSlot: constant Global slot in one explicit owner.
- [x] userScope_timerKeyUsesUserId: typed User identity and anonymous normalization.
- [x] throttledMode_crossInstanceSharesOneThrottleSlot: reconstructed callers reuse the same injected authority.
- [x] throttledMode_crossInstance_differentUsersAreIndependent: shared authority retains separate user slots.
- [x] cleanupStaleEntries_removesOldEntries: stale elapsed reservations are removed.
- [x] cleanupStaleEntries_preservesRecentEntries: recent and exact-retention reservations remain.

MemoryMaintenanceMiddlewareStaleEntryTest:

- [x] cleanupStaleEntries_removesOldEntries: shared cleanup removes stale elapsed reservations.
- [x] cleanupStaleEntries_preservesRecentEntries: shared cleanup preserves recent claims.
- [x] cleanupStaleEntries_onlyRemovesStaleEntries: one old and one recent key verify mixed-state cleanup. These state-machine adaptations do not imply a maintenance middleware scheduler.

MemoryMiddlewareThrottleIsolationTest:

- [x] flushAndMaintenanceHaveIndependentWindows, local admission-only adaptation: all eight scope/order combinations are verified with typed operation keys.
- [x] MemoryLifecycleIsolationSpec now verifies all eight local middleware reconstruction and background consolidator assertions with real schedulers/consolidation, 1-hour Flush versus 24-hour Maintenance, both orders, four scopes, exact extraction/report counts, committed curated state and queue quiescence. Rebuilt adapters share the original gate.
- [ ] Its eight store-backed combinations remain pending with remote coordination.

MemoryConfigTest trigger behavior:

- [x] flushTrigger_alwaysAndNeverAreSingletons: Scala enum cases provide stable value alternatives; Java object-reference assertions are excluded.
- [x] flushTrigger_throttledZeroDurationCollapsesToAlways: typed constructor normalization.
- [x] flushTrigger_throttledPositiveDurationKeepsThrottledMode: validated finite duration is retained.
- [x] flushTrigger_rejectsNullAndNegativeDuration: negative/infinite/bounds rejection is verified; Java null acceptance/rejection paths are explicitly excluded from this pure Scala API adaptation.
- [ ] Map the remaining six full MemoryConfig default/builder/prompt scenarios; this increment is not a complete unified memory configuration port.

Additional hardening covers owner separation, exact clock origin, structural identifier collisions, strict numeric decoding, simultaneous claims, policy reset, signed elapsed wraparound and backward test-clock movement. Cleanup deliberately differs from upstream age-only eviction: an active interval survives shorter retention. Fixed policies require a new gate to change intervals, deliberately resetting windows. The gate samples the monotonic clock inside its serialized transition to avoid installing timestamps captured before competing claims.

- [x] Implement the four async-flush methods and the applicable async-maintenance gate behavior through owned runtime schedulers below.
- [ ] Complete remote gates, filesystem retention and broader middleware parity.

- [x] `review_fp` and `review_harness` approved the final six-file memory-trigger increment against `73cd376` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 552 core / 287 harness tests, including 23 new cases, with zero compiler warnings. No findings remain within typed finite intervals, strict numeric codecs, immutable policies, explicit shared ownership, atomic monotonic admission and active-window-safe cleanup. Scheduling, execution locks, remote authority and full memory configuration parity remain outside this scope.

## Background memory queue foundations

MemoryTaskQueueSpec and MemoryTaskQueueLifecycleSpec now establish generic host-owned scheduling mechanics. The queue foundations alone do not establish real middleware behavior. Current flush integration closes the four adapted methods below; consolidation scheduling now supplies the maintenance gate adaptation.

- [x] Verify nonblocking submission, one running job per queue owner, pending-only conversation coalescing, separate pending conversations, replacement retaining FIFO position and execution-time state reads.
- [x] Verify bounded capacity, explicit receipt outcomes, self-interruption/defect progression, cleanup before subsequent execution and cancellation-safe host scope ownership.
- [x] Reproduce and correct shutdown blocking after masked queue acquisition.
- [x] MemoryFlushMiddlewareAsyncFlushTest.onCompleteFiresBeforeAsyncFlushCompletes: same-named scheduler test verifies a complete ReAct stream with one result/end, nonempty real extractor input, retained ticket, and no daily write until blocked extraction is released.
- [x] MemoryFlushMiddlewareAsyncFlushTest.flushesForSameIsolationKeyRunSerially: same-named scheduler test verifies one extractor call before release, two after draining, and maximum concurrent extraction exactly one.
- [x] MemoryFlushMiddlewareAsyncFlushTest.backToBackSameSessionFlushesCoalesceIntoOnePendingTask: same-named test verifies exactly two real extractions, one superseded ticket, and all three turns in the latest completed snapshot.
- [x] MemoryFlushMiddlewareAsyncFlushTest.sameUserDifferentSessionsEachKeepTheirPendingFlush: same-named test uses distinct scheduler/conversation capabilities sharing one owner and verifies three extractions, one superseded ticket and preserved replacement position.
- [x] MemoryMaintenanceMiddlewareAsyncGateTest.gateClaimRunsOffTheCompletingThread: MemoryConsolidationSchedulerSpec blocks the actual Maintenance claim with a Promise, proves the real agent invocation completes before release, then verifies the retained receipt. This is a ZIO lifecycle adaptation, not a Reactor scheduler/thread-name claim.
- [x] MemoryMaintenanceMiddlewareAsyncGateTest.completingThreadIsNotBoundedElastic: the Reactor-specific fixture assertion is explicitly inapplicable to ZIO. The blocked-gate runtime test above replaces its intent with deterministic invocation/worker separation; no thread-name equivalence is claimed.

Java owns detached process-global subscriptions, waits only a bounded interval during shutdown and swallows task errors. The Scala queue deliberately binds workers to a host Scope, exposes complete task Exit values, bounds pending slots, and waits for owned cleanup. Closing cancels queued work; callers can stop producers and awaitIdle before closure when graceful draining is required. Opaque conversation tokens replace Java object-reference-plus-user/session keys and must be retained by the host. Fresh scopes/tokens do not implicitly share state.

- [x] `review_fp` and `review_harness` approved the final five-file scoped queue increment against `ad12c7b` on 2026-09-29. Twenty new tests reproduced and corrected masked-acquisition shutdown blocking; full `sbt scalafmtAll scalafmtSbt verify` passed 552 core / 307 harness tests with zero compiler warnings. No findings remain within bounded pending work, FIFO coalescing, runtime ticket outcomes, host-owned worker/task scopes and cancellation-safe cleanup. Real memory hooks, trigger/extractor integration, durable tasks and distributed scheduling remain outside this scope.

## Completion-triggered flush adaptations

The four MemoryFlushMiddlewareAsyncFlushTest methods above now have actual ReAct-to-extractor evidence in MemoryFlushSchedulerSpec. Conversation capabilities replace Java agent-object/user/session coalescing keys. The Scala scheduler prepares immutable completed snapshots before admission, unlike upstream live-history reads at execution. The reset-history regression establishes that deliberate distinction. A newer eligible pending snapshot replaces an older one, while empty/internal-only snapshots cannot displace useful work or consume a throttle interval.

The core observer hands accepted tickets to an explicit host callback. Tests retain them beyond invocation Scope closure and verify background gate errors separately from successful agent completion. Registration failure after admission is not rollback. Cooperative successful recovery outcomes remain eligible; failed/external-cancelled execution exits skip scheduling. Local reopen and awaited compactor flush add persistence/prefix-preservation evidence beyond the four upstream methods.

- [x] Verify 17 new flush integration cases and retain the existing 14 direct manager cases after shared preparation.
- [x] Integrate owned consolidation scheduling, classify both asynchronous-gate methods, and verify the eight local consolidator/rebuilt-adapter combinations.
- [x] Implement daily maintenance expiry through acknowledged archival and explicit local transcript retirement below.
- [ ] Implement automatic session-expiry policy and remote coordination.
- [ ] Provide host operational receipt monitoring and durable/distributed scheduling where required; an injected callback alone does not establish that deployment behavior.

- [x] `review_fp` and `review_harness` approved the final four-file flush-scheduler increment against `1c8e3ee` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 552 core / 324 harness tests, including 17 new integration cases, with zero compiler warnings. No findings remain within shared pure preparation, successful-completion queue integration, immutable snapshots, worker-side triggers, ticket handoff, local reopen and awaited compactor flushing. Maintenance scheduling, automatic receipt monitoring, live-history reads and stronger durability remain outside this scope.

## Owned consolidation and observer composition

The seven-file increment against `3e817e3` adds 25 tests: three core observer-composition cases and 22 harness scheduler/lifecycle/isolation/composition cases. The source identities are recorded in the core agent and memory package READMEs. MemoryConsolidationScheduler reports every returned result after cleanup, retains gate/report failures in receipts, and coalesces pending work through a shared owner adapter. Scope closure interrupts reporting after a possible commit; local reopen confirms a failed report cannot erase persisted memory.

A test-scope ownership review finding was corrected with acquireRelease so early failures cannot leak the nested worker. FIFO replacement is deliberately preserved: composing flush then maintenance observers orders admission but does not ensure a newer flush runs before replaced maintenance. The regression proves consolidation sees earlier committed facts while later flushed data remains pending. Strict execution dependencies, transactional reporting and automatic receipt monitoring are not supplied.

- [x] Exercise all four consolidation outcomes, gate suppression/failure, no-input throttle consumption, coalescing, successful-completion hooks, failed/cancelled exits and closed/full/registration failures.
- [x] Verify local persisted commit after report failure and owner reopen.
- [ ] Implement automatic session pruning, eight remote matrix combinations and the remaining six full MemoryConfig scenarios; daily cutoff/maintenance policy is now implemented below.

- [x] `review_fp` and `review_harness` approved the final seven-file consolidation/composition increment against `3e817e3` on 2026-09-29 after correcting nested test-scope ownership. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 346 harness tests, including 25 new cases, with zero compiler warnings. No findings remain within separately scoped observer composition, owned consolidation/result reporting, eight local throttle integration combinations, FIFO replacement and post-commit cancellation/failure. Retention, remote authority, strict cross-job dependencies and transactional reporting are excluded.

## Daily retention hardening

Pinned MemoryMaintenanceMiddleware.expireDailyFiles archives old daily files before consolidation without acknowledgement checks. DailyMemoryRetention deliberately requires exact acknowledged content and an explicit exclusive date cutoff; newer or unconfirmed input stays active. Versioned date/content archives replace upstream single-name overwrite behavior. This is a source-contract adaptation, not a claim that a named upstream archive test exists.

- [x] Add twelve shared in-memory/local storage cases plus two fingerprint/codec cases and ten local publication/ownership cases.
- [x] Reproduce and fix unpaired-UTF-16 digest aliasing; reuse the strict fingerprint boundary in consolidation.
- [x] Exercise stable partial states after archive publication and checkpoint advancement; verify same-content recreation and old-revision conflicts.
- [x] Wire explicit daily retention policy into one owned maintenance job, with consolidation-first hardening and 21 new scenarios. This adds source-contract adaptations, not a named upstream retention test mapping.
- [ ] Implement automatic session pruning, remote retention and timestamp migration separately.

No live crash or mid-operation I/O fault injection is claimed. Archive copy publication can precede failed checkpoint advancement, and a successfully advanced checkpoint can precede incomplete source removal. Surviving daily content then remains pending; reporting failure does not imply rollback.

- [x] `review_fp` and `review_harness` approved the final seven-file daily-retention increment against `b2ab23e` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 370 harness tests, including 24 new cases, with zero compiler warnings. No findings remain within typed archive identity, exact acknowledged eligibility, atomic in-memory retirement, verified local copy/checkpoint/removal ordering, conservative partial progress and strict shared fingerprinting. Automatic policy, session pruning, crash durability and hostile concurrent filesystem mutation are excluded.

## Daily policy and the remaining configuration assertions

The 21 new policy/service/scheduler tests verify one cutoff before side effects, zone and range boundaries, deferred/cancelled consolidation, one gate reservation, owner coalescing, post-commit failures and aggregate reporting. They preserve the existing consolidation-only adaptations. Upstream archives before consolidation and suppresses some failures; Scala deliberately consolidates first, requires exact acknowledgement and exposes failure in task receipts.

The six remaining MemoryConfigTest methods stay individually visible:

- [ ] defaults_matchHistoricalBehaviour: daily retention 90 is now verified, alongside existing 4,000-token/30-minute/Always defaults; session 180 and complete prompt/default selection remain pending.
- [ ] builder_overridesAreCarried: immutable daily policy overrides are verified; complete cross-configuration assertions, custom prompts and session settings remain pending.
- [ ] builder_consolidationPromptMustHaveTwoIntPlaceholders: implement or explicitly adapt the two token/character budget placeholders through a total typed renderer; no unchecked format-string port.
- [ ] builder_rejectsInvalidNumericArgs: positive daily days and token bounds are verified; session-day validation remains pending.
- [ ] builder_rejectsNullDurationsAndTrigger: existing trigger validation covers negative durations; classify the whole configuration adaptation with Java-null paths excluded once the unified surface exists.
- [ ] builder_isReusable_andEachBuildIsIndependent: immutable daily policy replacement is verified; prompt/token independence across complete configurations remains pending.

KeepAll is an explicit Scala archival policy, not upstream null/sentinel compatibility. Both public schedulers share the Maintenance window, so installing the combined scheduler replaces the consolidation-only adapter for an owner requiring retention. No full MemoryConfig parity or session-pruning claim is made.

- [x] `review_fp` and `review_harness` approved the final eight-file maintenance increment against `b3d2723` on 2026-09-29, including corrected consolidation-only ScalaDoc. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 391 harness tests, including 21 new cases, with zero compiler warnings. No findings remain within validated calendar policy, repository-bound sequential maintenance, typed aggregate outcomes, one owned gate/job and preserved consolidation-only behavior. Session pruning, unified configuration, cross-phase atomicity and durable reporting are excluded.

## Explicit transcript retirement adaptation

Pinned MemoryMaintenanceMiddleware.pruneOldSessions scans session log names, compares modification timestamps and suppresses per-file failures. LocalTranscriptRetention instead accepts one explicit directory, acquires the writer lease, validates the complete versioned log and uses maximum metadata/recorded activity before removing its fixed payload. This is a deliberately hardened source-contract adaptation, not Java layout equivalence or a newly mapped named pruning test.

- [x] Verify thirteen local cases covering cutoff/activity, missing/empty data, corruption preservation, owner refusal, unrelated files, generation reset and concurrent attempts.
- [ ] Add session enumeration, the 180-day policy and owned maintenance integration.
- [ ] Implement remote pruning and platform-specific hostile-mutation/crash validation if those guarantees are required.

Cancellation-after-admission uses the previously reviewed WorkspaceLease contract; interruption may follow completed deletion. No mid-delete fault injection is claimed. A successful prune discards persisted keys and parent linkage, so hosts must retire the logical session rather than assume continuation.

- [x] `review_fp` and `review_harness` approved the final three-file transcript-pruning increment against `9ddee3a` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 404 harness tests, including thirteen new cases, with zero compiler warnings. No findings remain within explicit local targets, shared writer ownership, strict log validation, observation-aware activity, fixed-payload deletion and generation retirement. Session discovery, automatic expiry, remote pruning and crash/hostile-mutation guarantees are excluded.

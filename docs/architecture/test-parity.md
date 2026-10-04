# Upstream scenario coverage and remaining parity work

The engineering target is one paired Scala test for every applicable upstream behavior. Current suites cover selected scenarios and ZIO-specific regressions; exhaustive 1:1 parity has not been demonstrated. Suite names and passing counts are not evidence of complete parity.

The reference is the clean local `agentscope-java` checkout at commit `f98a685fdc4589f81e626e1686ac2c63d14a2304`, verified on 2026-09-29. Upstream Java implementation details are translated into Scala domain behavior, not copied as imperative APIs. Updating this pin requires a new inventory and review of changed scenarios.

## Typed completed tool observations

The current core prerequisite exposes Tool.encodeResult, payload/state ToolExecutionResult and runtime-protected result publication. Existing String tools retain Success by default; only Success/Error may be projected by tools. ToolExecutionSpec covers payload/codecs, unsupported states and cancellation cleanup; the agent integration checks ordered typed history/events and protected metadata. These are local extension contracts, not completion of the upstream MCP adapter or tool matrix. ChatCompletions still rejects non-text tool output.

The unchanged Toolkit blob `79be10c316f676da5d96977419e5be4caf02886f` lost a cleanup defect when a tool joined its actually interrupted owned child: `tool-inner-cancellation-red.log` records the failed defect-preservation assertion. The external-cancellation baseline passed. Re-failing the compound cause preserves interruption and defects while mapping typed Throwable leaves to defects for the UIO boundary.

- [x] Both independent reviewers approved the final scope and full repository verification passed; exact identities belong to the [tool acceptance](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#current-review-acceptance).
- [x] Both independent reviewers approved manual MCP Toolkit binding with explicit host authority and preserved protocol content; full verification passed and provider multimodal projection remains separate.

## Progress checklist

- [x] Close the reproduced PathSafety command-classification gaps with independently approved [advisory lexical screening regressions](../../modules/core/src/test/scala/com/weisgnosis/agents/core/tool/README.md#command-screening-regressions). This is additional local contract coverage, not a new upstream mapping or shell-authorization guarantee.

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
- [x] Support [typed final results and incremental local tool progress with reviewed metadata propagation](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#incremental-execution).
- [x] Classify current event/lowercase and upstream type/uppercase wire formats as noninterchangeable; existing Scala wire tags remain canonical for compatibility.
- [x] Implement the separate AgentEventType identifier codec for all 31 canonical names and 11 aliases.
- [ ] Implement explicit field/payload adapters; identifier parsing alone cannot establish compatibility.
- [x] Implement AgentResult, DataBlockStart/Delta/End, ToolResultDataDelta, ExceedMaxIters, RequestStop, and AllToolsDenied payloads and codecs.
- [x] Publish finalized ordinary and structured results after preparation/history commit and before AgentEnd; StructuredCompletionSpec verifies failure, cancellation, fallback, and cleanup.
- [x] Integrate reviewed host acting-stop publication after admitted observations drain.
- [ ] Integrate other remaining runtime publication and durable pending-work resumption.
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
- [x] Verify [bounded runtime ordering adaptations](#agenteventstreamtest-seven-disabled-ordering-declarations) for current text/thinking/tool cycles and checkpoint decisions.
- [ ] Define remaining model data-block production and richer attribution contracts.
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

These pinned `agentscope-core/src/test/java/io/agentscope/core/event/AgentEventStreamTest.java` methods contain comments rather than executable assertions and their enclosing class is disabled. The checked items below are deliberate Scala contract adaptations, not passing upstream tests or literal sequence parity. [Current ordering acceptance](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#runtime-event-ordering) records exact reviewed specs and verification.

- [x] `singleIterationOrder` (245): ChatCompletionsClientSpec asserts the exact held-stream lifecycle projection, excluding raw frame events. Incremental blocks precede ModelResponseEnd and AgentResult precedes AgentEnd, intentionally differing from the comment placing model completion before content.
- [x] `thinkingBeforeText` (258): the same held stream verifies ThinkingStart/Delta before TextStart/Delta for ordered input, then both End events at completion. Independent indexed thinking/text may overlap; universal ThinkingEnd-before-TextStart is intentionally not promised.
- [x] `toolCallCycle` (266): the actual HTTP Allowed cycle asserts incremental tool-call events before model completion, complete local result Start/Delta/End before the second model request, and final text/result/end order. This adapts the disabled comment rather than moving live deltas after response completion.
- [x] `hitlReentry` (281): PartialConfirmationSpec verifies Ask after admitted-result drain and before RequestStop, stable request identities across repeated deferral, and correlated Decision before result Start. Explicit checkpoint/resume invocations replace one paused sink-backed stream; generic decisions carry no human-envelope provenance.
- [ ] `dataBlockEmission` (293): define a concrete model-media producer and delta encoding before implementing DataBlock Start/Delta/End publication. Existing tool-result data events and request-media projection do not close this declaration.
- [x] `exceedMaxIters` -> ReActAgentEmissionSpec verifies the implemented limit event before AgentResult and AgentEnd, with the same attempt replyId and completed iteration count; the upstream declaration itself remains disabled.
- [x] `streamBoundaries` (309): ReActAgentEventSpec checks the first AgentStart, one correlated boundary pair and last AgentEnd on fully drained success/buffering/failure/self-interruption/cooperative-interruption/timeout paths. Early consumer termination joins cleanup but is excluded from tail-delivery guarantees.

### FinalAnswerFilterMiddleware

Pinned `agentscope-core/src/test/java/io/agentscope/core/middleware/FinalAnswerFilterMiddlewareTest.java` has four executable methods. [FinalAnswerFilterSpec](../../modules/core/src/test/scala/com/weisgnosis/agents/core/event/FinalAnswerFilterSpec.scala) supplies bounded adaptations through an opt-in subscription-local pipeline rather than a Java middleware chain. Exact source plus full request context replaces reply-only mutable state; global limits and abandoned-request cleanup are explicit Scala contracts. [Acceptance](../../modules/core/src/main/scala/com/weisgnosis/agents/core/event/README.md#optional-final-answer-filter) records both reviews and verification.

- [x] `finalRoundEmitsBufferedTextBeforeModelCallEnd`: exact correlated text Start/Delta/End flush before the matching model End.
- [x] `intermediateRoundSuppressesTextWhenToolCallIsObserved`: buffered and later text suppress after a matching ToolCallStart; other request/source text remains independent.
- [x] `nonTextEventsAreForwarded`: exact Thinking, raw frame, tool-call and result passthrough; this is not secret redaction.
- [x] `stateIsolatedAcrossSubscriptions`: reuse of the same pipeline starts fresh state; no suppressed state leaks into a later subscription.
- [x] `ReActAgentMiddlewareIntegrationTest.finalAnswerFilterSuppressesIntermediateReactRoundText` (247): the actual HTTP two-request cycle emits only final text with both model Ends, one tool execution and unchanged intermediate history/next request. This closes the bounded filtering behavior, not Java middleware registration or general chain parity.

### Implemented payloads and remaining runtime contracts

The eight payload classes below are implemented and verified independently of their runtime integrations. AgentEvent now has 39 Scala variants; the separate upstream identifier enum retains its 31 names. Existing lowercase event payloads remain canonical.

| Implemented variant | Payload | Runtime integration status |
| --- | --- | --- |
| AgentResult | result: Msg | Runtime publishes after preparation and history commit, before AgentEnd; pre-commit failures publish no finalized result |
| DataBlockStart / End | replyId, blockId | Payload/codec support; model producer and runtime assembly remain unimplemented |
| DataBlockDelta | replyId, blockId, delta: String | Delta is text; it is not a ContentBlock |
| ToolResultDataDelta | replyId, toolCallId, toolCallName, data: ContentBlock, metadata | Inline provider and completed typed local output blocks are published with full metadata; provisional local payloads are published with protected metadata; provider multimodal serialization remains pending |
| ExceedMaxIters | replyId, maxIters, currentIter | Runtime guard verified with correlated replyId and completed iteration count |
| RequestStop | reason, available typed GenerateReason; default MiddlewareStopRequested | Reviewed host acting-stop publication; durable pending-work resumption remains open |
| AllToolsDenied | Chunk[ToolUse], empty by default | Complete-local-batch observation after cleanup and Deferred/Stop priority; no automatic stop or human provenance |

Upstream base events also have identity/time, optional source, and metadata. This payload increment preserves immutable optional source and data-delta metadata but does not add the universal id/createdAt envelope or translate uppercase type objects. A future envelope adapter must use explicitly supplied identity/time or an effectful allocator rather than eager UUID/clock access. Class presence alone is not runtime evidence: the pinned source has no production DataBlock event construction sites. A RequestStop event is distinct from fiber cancellation; supporting its type does not implement phase suspension or resumption.

- [x] AllToolsDenied publication adapts pinned ReActAgent.java acting logic around lines 2923 and emitAllToolsDeniedThroughMiddleware around 4225. The Scala runtime verifies every original local occurrence after joined cleanup, including restored partial outcomes, and publishes persisted calls before continuing reasoning. Deferred/Stop decisions retain priority. This is a source-anchored adaptation, not a newly closed named test method or Java middleware stop contract. Both reviews and full verification are recorded in the [runtime acceptance](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#all-tools-denied-observation).
- [x] ReActAgentServerToolTest.testServerToolWithoutResultContinuesLoop (204) and testMixedToolsOnlyExecuteLocalTools (254) have bounded runtime adaptations: unresolved provider calls continue without local authorization/dispatch/results, while same-ID/name local siblings execute once. Repeated provider pauses, provider-only checkpoint restoration and joined mixed cancellation supplement these mappings. ToolHistory excludes provider calls from local pairing and rejects separate/later provider-result matching; MessageUtils.pendingToolUseIds (177-194) is the source reference for local-only recovery. Both reviews and full verification are recorded in [provider ownership](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#provider-owned-pending-calls). No provider wire protocol or cross-assistant result support is claimed.

Inline event metadata intentionally preserves the complete result map; pinned upstream inline events synthesize only serverTool=true. ReActAgentEmissionSpec verifies this Scala contract, duplicate-ID occurrence handling, and local cancellation matching. Provider continuation without inline results is covered separately by the ownership fixtures above. This emission fixture does not establish local structured tool streams or complete event ordering.

## Completion parser hardening

- [x] Preserve the three original reasoning/tool/usage parsing scenarios through a typed Either result.
- [x] Add sixteen Scala protocol-boundary regressions in ReasoningAdapterValidationSpec, including required choices, strict integer/index handling, duplicate keys, provider errors, legacy function-call rejection, and mixed-choice rejection.
- [ ] Complete the method-level inventory of provider extension parser suites and transport suites; these new hardening tests are not claimed as sixteen upstream methods.
- [x] Implement indexed text/thinking assembly with signatures, typed finish validation, strict completed object arguments, preserved raw content, and interrupted sanitization; thirteen accumulator regressions cover this scope.
- [x] Validate full JSON documents and duplicate keys through JsonBoundary, with six paired tests and frame/tool suffix regressions.
- [x] Implement text/function request serialization with sixteen local regression tests, including paired histories, explicit reasoning replay, and unsupported-content rejection. These tests are not claimed as sixteen mapped upstream methods.
- [ ] Map the full upstream formatter surface, implement remaining request extensions and other provider signature/tool-metadata decoding, and complete remaining provider and tool-result event integration.

The pinned extension OpenAIStreamingToolCallTest and OpenAIResponseParserTest were inspected for fragment, usage, and error semantics. They are outside the earlier core-only inventory, and their full applicability mapping remains open. The current adapter intentionally supports indexed function-call deltas and fails unsupported legacy calls instead of silently dropping them.

The pinned core ToolCallsAccumulatorTest and ReasoningContextTest were inspected for parallel calls, raw argument preservation, interruption sanitization, and latest-snapshot usage. Mandatory compatible finish markers, strict complete object arguments, and stable identities are deliberate Scala hardening. LlmChunk has raw argument fragments rather than mutable parsed-map updates. Selected Google tool-call signatures now have the bounded mappings below. Parsed-map merge scenarios, other reasoning-detail variants and the full method-level accumulator mapping remain pending; the selected text/summary scope has the bounded mappings below.

### User-image request projection

Both final reviews and full verification passed. ChatCompletionsRequestSpec supplies two grouped tables for four bounded OpenAIMessageConverterTest adaptations:

- [x] testImageBlockWithValidURLSource (line 117): ordered text and HTTPS image parts with the exact URL retained.
- [x] testImageBlockWithValidBase64Source (line 147): text plus explicit image data URL; the Scala table additionally covers all four supported media types.
- [x] testImageBlockWithURLSource (line 251): image-only HTTP user content uses an image_url array.
- [x] testMultimodalMixedContent (line 315): ordered mixed text/image parts, without broad audio/video support.

Source: pinned agentscope-extensions-model-openai/src/test/java/io/agentscope/extensions/model/openai/formatter/OpenAIMessageConverterTest.java. These are pure provider-wire adaptations, not a mutable converter API or complete formatter-suite parity. The stricter Scala boundary rejects malformed URLs/Base64 and unsupported roles instead of fetching resources, guessing a media type or substituting text. No image decoding, animation/size validation or live-provider interoperability is claimed. Exact identities and evidence are in the [request contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#user-image-projection).

### Host-selected tool choice

- [x] OpenAIChatFormatterTest.testApplyToolChoice (201), testApplyToolChoiceAuto (215), testApplyToolChoiceNone (228) and testApplyToolChoiceRequired (722) have bounded request-table adaptations with exact provider JSON. testNoToolChoiceWithoutTools (736) and testNoToolChoiceWithEmptyTools (747) map to the empty Chunk representation. Specific additionally requires an advertised valid function name; Required rejects an empty set.
- [x] testDefaultAutoForNullToolChoice (762) is an intentional difference: absent Scala configuration omits the field even with tools. Actual HTTP and controlled failover/structured checks separately establish runtime authority and per-target retention; they do not claim provider compliance or whole formatter parity. Both source reviewers approved the [exact five-file scope](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#host-selected-tool-choice); full tool-choice-verify.log verification passed.

### User inline-audio request projection

The existing request tables add the following bounded mappings from the same pinned OpenAIMessageConverterTest.java. Both final reviews and full inline-audio-verify.log verification passed.

- [x] testAudioBlockWithValidBase64Source (line 180): user role, ordered text/audio parts and exact Base64 data are projected as input_audio. Scala additionally asserts exact wav/mp3 format values and mixed image/audio ordering.
- [x] testBase64AudioBlockConversion (line 292): an audio-only user message becomes a nonempty multipart array with literal data. Neither the Java fixture nor these tables validate audio bytes.

Audio URL text substitution in testAudioBlockWithURLSource is intentionally rejected. OpenAIConverterUtilsTest's audio/mp3 alias, opus/flac and unknown/null format fallbacks are not adopted: only exact audio/wav and audio/mpeg map to the official wav/mp3 wire formats. Syntax checks, unsupported-role rejection and whole-request failure are local strict policies. There is no audio-output, file/network acquisition or full formatter-suite parity claim. Current identities belong to the [request contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#user-image-projection).

### User inline Data projection

The existing request tables provide bounded adaptations from pinned `agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-openai/src/test/java/io/agentscope/extensions/model/openai/formatter/OpenAIDataBlockConverterTest.java`:

- [x] `testDataBlockBase64Image` (90): explicit inline image Data becomes an image_url data URL; Scala verifies all four accepted image MIME types and exact mixed-part ordering.
- [x] `testDataBlockBase64Audio` (126): explicit inline audio Data becomes input_audio. The Java fixture uses audio/mp3; Scala deliberately retains its existing exact audio/wav and audio/mpeg mapping instead of adopting that alias.

The [request contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#user-image-projection) records current identities, both independent approvals and verification. Existing positive/negative tables also verify identity omission and whole-request rejection. URL extension/MIME inference, video, placeholder fallback and silent dropping are not adopted; no new provider protocol or media decoding is claimed.

Explicit image-URL MIME hints extend that same request boundary. Pinned URLSource.java declares optional mime_type and a no-hint constructor. Existing Scala source/request tables supply these bounded mappings; both final reviews and full mime-hint-verify.log verification passed:

- [x] `testDataBlockMimeTypeHint` (161): an extensionless Data URL with an explicit image hint becomes an image_url request part, preserving URL spelling and omitting the hint and local identity.
- [x] `testResolveMimeTypeHintOverExtension` (213): bounded request-level adaptation of hint authority over a misleading .mp3 path. The Java method tests MediaUtils directly; Scala does not implement its extension inference or claim utility-API parity.

Missing hints, aliases, other media, non-HTTP(S) URLs and unsupported roles reject the whole request. Explicit Image blocks retain their hint-independent behavior. No URL acquisition or byte/MIME verification is introduced.

### Bounded streaming HTTP-status retries

Both final reviews and full http-retry-verify.log verification passed. Bounded mappings use pinned `agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-openai/src/test/java/io/agentscope/extensions/model/openai/OpenAIChatModelStreamingRetryTest.java`:

- [x] streaming429ExhaustsRetriesAndReissuesEachRequest (78): the status table verifies finite total attempts, fresh Client.streaming calls and the exact final rejection/payload. Controlled transport evidence supplements the real-success fixture; it does not reproduce Java's exception wrapper.
- [x] streaming429RetriesThenSucceeds (93): actual HTTP 429 then successful SSE produces one completed logical model result; Scala also checks body/auth stability, one projection and one request context.
- [x] streaming400DoesNotRetry (120): the real HTTP 400 row terminates after exactly one request.

JdkHttpTransport/OkHttpTransport-specific retry methods and nonStreaming429Retries are not established by the scoped zio-http streaming adapter. Fixed delay differs from Java's configured initial/max backoff; error-code strings, network failures and public ModelError values do not acquire retry authority. Current evidence belongs to the [client contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#bounded-http-status-retries).

### Selected tool-call signature mappings

Both final reviews and full signature-mcp-verify.log verification passed for the [current client scope](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#tool-call-signatures).

- [x] ToolCallsAccumulatorTest.testAccumulateMetadata: preserve an opaque signature while argument fragments arrive, using immutable string metadata instead of Java byte arrays/maps.
- [x] ToolCallsAccumulatorTest.testAccumulateWithoutMetadata: unsigned calls remain unsigned.
- [x] ToolCallsAccumulatorTest.testParallelToolCallsWithMetadata: stream-index isolation prevents signature propagation to other calls.
- [x] OpenAIThoughtSignatureTest.testToolUseWithoutMetadata: unsigned request calls carry no signature extension.
- [ ] OpenAIThoughtSignatureTest.testTypedMetadataAttachedDirectly and testToolMetadataRestoredAfterRoundTrip: tool-signature portions are covered by typed metadata, JSON round-trip and request replay; their Thinking fixtures use type=signature, outside the selected encrypted/text/summary profiles, so whole-method parity is not established.
- [ ] Adapt testThinkingDetailsRestoredAfterRoundTrip only after supporting its type=signature fixture explicitly; preservation of reasoning.encrypted/text/summary does not close that method.

OpenAIThoughtSignatureTest's function.thoughtSignature DTO differs from the selected official Google extra_content.google.thought_signature wire format. testByteArraySignatureConverted has no direct Scala representation: already-opaque strings are preserved. testUnusableSignatureIgnored and testUnconvertibleThinkingDetailsSkipped are not adopted as silent-dropping policies; malformed recognized signature metadata fails closed. The real HTTP fixture is a local integration oracle, not an external Google server test. Server-tool authority, arbitrary metadata and thinking signatures are outside this selected format.

### Selected reasoning details

The following method mappings use the pinned Java revision above. Formatter source: `agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-openai/src/test/java/io/agentscope/extensions/model/openai/formatter/StreamingReasoningDetailsReplayTest.java`; accumulator source: `agentscope-core/src/test/java/io/agentscope/core/agent/accumulator/ThinkingAccumulatorReasoningDetailsTest.java`.

- [x] StreamingReasoningDetailsReplayTest.preservesDistinctReasoningBlocksAcrossStreamingChunks (line 33): the bounded adaptation preserves distinct encrypted objects and their opaque data/IDs in received order through decoding, accumulation and request replay. EncryptedReasoningDetailSpec's ordered-delta group and the actual HTTP cycle exercise this path with immutable JSON values instead of mutable Java DTOs; repeated indexes do not merge objects.
- [ ] ThinkingAccumulatorReasoningDetailsTest.preservesDetailsAcrossChunksWithoutChangingOtherMetadataSemantics (line 80): ordered detail concatenation is adapted, but the Java method also concatenates plaintext and applies last-write-wins to arbitrary provider metadata. The selected checked-object projection concatenates plaintext but neither accepts arbitrary detail strings nor implements that metadata merge, so whole-method parity remains open.
- [ ] ThinkingAccumulatorReasoningDetailsTest.keepsBuiltListsIndependentFromLaterWritesAndReset (line 114): immutable accumulator/Chunk/JSON values provide the corresponding snapshot boundary by construction. The Java method mutates caller and returned lists, resets the same accumulator, and expects null when empty. Those mutation/reset/null APIs and assertions are not reproduced; the current tests establish ordered immutable outputs, not whole-method parity.

- [x] The explicit OpenRouterEncryptedDetails profile preserves checked encrypted objects in received order and replays all historical assistant arrays. Two checked-detail groups cover strict shapes, optional/null fields, repeated indexes, explicit empty arrays, interrupted preservation and incompatible-policy rejection.
- [x] Extended runtime/pruning tables and one real HTTP cycle verify opaque-only retry, unchanged protected sibling arguments, denial before fixed/approved rewriting, historical wire replay and no fabricated Thinking events. The [canonical acceptance](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#encrypted-reasoning) records current identities and scoped verification.
- [x] Both final reviews and full reasoning-details-verify.log verification passed for selected OpenRouterDetails. Two pure groups and the existing HTTP table exercise strict encrypted/text/summary objects, details-authoritative scalar handling, exact arrays, all-history replay and matching canonical/event plaintext.
- [x] OpenAIResponseParserTest.testChunkWithReasoningDetails (line 744): bounded adaptation of an actual reasoning.text chunk into Thinking text, exercised by ReasoningDetailSpec and the HTTP cycle.
- [ ] OpenAIResponseParserTest.testParseReasoningTextType (391), testParseReasoningSummaryType (421), and testCombineMultipleReasoningTypes (451): plaintext/ordered combination has a streaming adaptation, but these Java methods parse non-streaming choice.message. Their summary fixture uses text; the selected profile follows the official summary field. Whole-method parity remains open.
- [ ] Other signature representations and live-provider interoperability remain unfinished. The mixed type=signature methods above stay open; strict malformed-detail failure intentionally differs from testUnconvertibleThinkingDetailsSkipped.

The parser methods are in the pinned `agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-openai/src/test/java/io/agentscope/extensions/model/openai/formatter/OpenAIResponseParserTest.java`. MinimaxDoubledSummaryReplayTest.summaryEventMessageClearedByTransportLayer (91), reasoningAndToolArgsNotDoubled (106), and chatCompletionOnlyStreamPreservesMessage (136) concern non-chunk summary-envelope suppression or full-message streams. Details-authoritative delta handling does not establish those contracts. No detail ID/index or encrypted data is inferred into a tool-call signature.

## Scoped model-failover applicability

Both final reviews and full model-failover-verify.log verification passed for the bounded scope. Pinned sources are `agentscope-core/src/test/java/io/agentscope/core/agent/ReActAgentTest.java` and `ReActAgentFailoverBoundaryTest.java`.

- [x] ReActAgentTest.testFallbackModel (562): bounded adaptation of one primary model invocation followed by one fallback result after its HTTP retry budget. ModelFailoverSpec verifies exact request/model ordering and canonical output. Java accepts an arbitrary mock failure; Scala deliberately requires private adapter evidence of an eligible clean pre-response HTTP rejection.
- [ ] ReActAgentFailoverBoundaryTest.fallsBackForFailureBeforeFirstResponse (42): only the pre-response selection boundary is applicable. Synchronous throws, arbitrary reactive failures and failover-listener notification are not the selected Scala contract, so whole-method parity remains open.
- [x] ReActAgentFailoverBoundaryTest.doesNotSwitchAfterPrimaryEmitsAResponse (85): the bounded partial-SSE row verifies terminal failure and zero target calls after successful-response output. The Java listener-empty assertion has no Scala counterpart; no listener API is implemented.
- [ ] FailoverListener notification/error-isolation, Java builder-copy/config serialization and general model-routing parity remain separate.

The [runtime contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#scoped-model-failover) distinguishes fresh target request identity/projection from canonical preparation, preserves the active schema/tool snapshot, and records the actual compound Auto-fallback regression. Native-to-synthetic fallback is not authority to select another model.

## Host acting-stop applicability

The pinned source `agentscope-core/src/test/java/io/agentscope/core/agent/ReActAgentReturnDirectTest.java` grounds the following bounded mappings. Both final return-direct reviews and full return-direct-resume-verify.log verification passed; exact identities are in the [current runtime acceptance](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#host-selected-return-direct-completion).

- [x] Bounded return-direct mappings: `singleReturnDirectToolBypassesModelAndReturnsOnce` (294), `nonReturnDirectToolContinuesLoopToSummarize` (341), `mixedBatchKeepsRealResultAndDoesNotReturnDirect` (359), `allReturnDirectToolsAggregateInExecutionOrder` (405), `failingReturnDirectToolDoesNotReturnDirect` (580), `deniedReturnDirectToolDoesNotReturnDirect` (598), `nonTextOutputIsForwardedVerbatim` (623), `emptyOutputIsPaddedWithPlaceholder` (644), `partiallyEmptyBatchOmitsPlaceholder` (661) and `parallelSkewPreservesDeclarationOrder` (1087) have bounded Scala adaptations. The fixture forces reversed processing and repeated IDs, retains processed image/text order and tests zero blocks separately from empty Text.
- [x] `hitlStopTakesPriorityOverReturnDirect` (454): its actual Java assertion is post-acting Stop over Direct, adapted by the Scala Stop row; the method name does not establish human-envelope parity.
- [ ] `earlierPostActingStopWinsOverReturnDirect` (486): existing acting-stop tests establish original-ordinal Stop selection and the direct fixture establishes Stop precedence/batch draining, but Java returns the stopping ToolResult message while Scala returns the tagged owning assistant and Stopped state. Whole-method parity is not claimed.
- [x] `externalResumeWithNonSuccessResultDoesNotShortCircuit` (852), Error branch only: a restored checkpoint with supplied Error retains processed observations and makes exactly one following model request, with no local execution. Java's supplied Denied branch is intentionally not adopted; denial remains runtime/authorization authority.
- [x] `externalResumeWithMixedBatchDoesNotShortCircuit` (882): restored successful supplied results with mixed admitted Direct/Continue modes retain their processed observations in history and the following model request without local replay. Explicit checked ordinal results replace Java external-result message envelopes. Both rows extend the existing table, with no new group or production change.

`sequenceIsCompleteAndFullResultAppearsOnlyInClosingAssistant` (309), `externalResumeShortCircuitsReturnDirect` (776), `externalResumeNormalizesPlaceholderState` (819) and `externalAndInternalPathsAreIsomorphic` (939) include Java placeholder-history behavior. Scala intentionally retains full processed Tool observations and appends presentation output. `postActingRewriteDoesNotAffectReturnDirectResult` (718) intentionally differs: processed redaction is authoritative. Java annotation APIs, error-text heuristics and extra closing-text/external-result envelopes (`internalReturnDirectEmitsClosingTextEventsBeforeAgentResult`, 1000; `externalResumeClosingEventsCorrelateToSuspendedReplyId`, 1033) are not implemented by this scope. Native/synthetic capture, cleanup and interruption tests are additional Scala contracts, not whole-suite parity.

`agentscope-core/src/test/java/io/agentscope/core/legacy/hook/HookStopAgentTest.java` PostActingEventTests.testStopAgentSetsFlag/testDefaultNotStopped exercise mutable flag APIs. The local typed Continue/Stop decision is an intentional immutable replacement, with no mutable flag/reset claim. Its post-reasoning pending-call resume tests remain separate. Local decision composition intentionally skips later processors for the stopping observation; all admitted observations still run. Cancellation with a cleanup defect is a ZIO lifecycle contract, not Java hook parity.

## Live model stream adaptation

- [x] ChatCompletionsClientSpec scenarios verify incremental publication, real HTTP tool cycles, request/reply correlation, late tool identity, incomplete/malformed input, and cancellation.
- [x] Three ModelStreamPublisherSpec scenarios verify indexed text/thinking, interleaved tool deltas, and validated complete ending batches; two ModelStreamContextSpec scenarios cover all sixteen affected event variants and legacy payloads.
- [x] Verify completed local tool results with request provenance, persistence before publication, and per-call start/delta/end ordering.
- [x] Independently review and verify provisional local updates with exactly one authoritative final result and scoped cancellation.
- [ ] Complete richer provider content and method-level formatter/transport mapping.

The disabled upstream ordering sketches remain unchecked: they put MODEL_CALL_END before buffered content and close thinking before text starts. The implemented live contract publishes provisional deltas during the request and closes blocks only after validated completion. ModelResponseEnd follows scope cleanup. This is an explicit runtime adaptation, not exact ordering parity.

ReActAgentToolEventSpec adds six local runtime regressions for persistence, errors, empty output, parallel completion, cancellation, and reused-ID provenance. Inline result tests now assert context on every result variant. These are Scala runtime contracts, not six newly mapped upstream methods. Cancellation still reconciles unfinished history without claiming completed tool events.

## Structured HTTP and JSON boundary hardening

- [x] Verify five real loopback HTTP scenarios for native schema/thinking/usage, synthetic root definitions and compressed streaming completion, HTTP-400 fallback without duplicated input, default-policy HTTP-503 failure without retry, and strict native payload failure.
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
- [x] Both reviewers approved bounded selected-request projection through ModelInputTransform, and full verification passed: immutable target/tool context, transient history/settings, scoped cleanup and unchanged dispatch authority. This is source-contract evidence, not a newly closed whole Java middleware method.
- [ ] Implement remaining general model-call interception and broader legacy hook operations.

This increment closes one previously pending upstream method through a documented typed adaptation. Fourteen local tests do not represent fourteen upstream methods. The Auto retry regression failed before narrowing callback failures to TextTransformationError. Raw ModelResponseChunk payloads remain original diagnostics; visible text is transformed before runtime assembly. The provider protocol and completion checks cannot be modified through this interface.

## Post-reasoning decisions and accounting

- [x] Verify three policy/codec tests and nineteen runtime scenarios, including typed discard for the final previously pending structured-output method.
- [x] `review_fp` and `review_harness` approved the final six-file post-reasoning increment against `2a400e0` on 2026-09-29 after the legacy-usage correction. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 456 core and 40 harness tests.
- [ ] Map remaining legacy hook suites and general middleware scenarios; eighteen closed structured-output mappings do not establish those domains.

Twenty-two added local tests cover more than the one newly closed upstream mapping. Replacement cannot forge canonical or legacy usage, discard does not reset thinking retries, and all returned model observations count across fallback attempts. Synthetic residual history clears both usage representations. The legacy duplication regression failed before correction. Existing model events remain original observations, while final results/history and dispatch reflect accepted policy decisions.

## Before-acting suspension: bounded mappings

Pinned source: `agentscope-core/src/test/java/io/agentscope/core/legacy/hook/HookStopAgentTest.java` at `f98a685fdc4589f81e626e1686ac2c63d14a2304`. Both final reviews and `acting-resume-verify.log` passed; [exact identities and regressions](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#before-acting-suspension-and-restoration) establish full-batch resume and checked restoration, not durable approval integration.

- [x] `testPostReasoningStopReturnsToolUseMsg` (line 182) and `testStopDoesNotExecuteSubsequentTools` (line 211): return the saved assistant tool calls without beginning local execution. ReActAgentResumeSpec verifies zero execution, processing and authorization before suspension. The adaptation uses a typed suspension value, not mutable stop flags.
- [x] `testResumeWithNoArgsAfterPostReasoningStop` (line 246): resume the saved batch before a follow-up model request. ReActAgentResumeSpec additionally verifies exact request counts, original/restored mixed batches and inline-only completion. The Java fixture asserts execution and a final reply without tool calls; it does not explicitly assert request count or exact assistant equality.
- [x] `testPartialToolResultsExecution` (line 541): ReActAgentSuppliedResultSpec verifies live/restored partial and all-supplied batches, unchanged original calls, no local execution or authorization for supplied occurrences, and one follow-up model request. Checked original-ordinal observations replace Java Tool-message injection; completed inline results remain separate. Human partial confirmations are excluded. The [seven-file acceptance](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#supplied-results-for-suspended-batches) records exact identities and full verification.

## Restored checkpoint confirmations: bounded mappings

Pinned `agentscope-core/src/test/java/io/agentscope/core/agent/ReActAgentHitlTest.java` supplies the following bounded mappings. Both final reviews and `confirmed-batch-verify.log` passed; exact identities and the stale-policy regression are in the [batch contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#complete-batch-checkpoint-confirmations).

- [x] `pendingToolRecoveryPreservesModifiedArgumentsFromConfirmation` (640): ConfirmedBatchAuthorizerSpec verifies actual modified input and exact persisted call inputs after restored sequential/parallel execution, without re-requesting saved tool calls. Complete original-ordinal confirmations and explicit host restoration replace Java confirmation messages and recovery flags; partial approvals are excluded.
- [x] `deniedConfirmResultWithCustomReasonWritesReasonToContext` (838) and `deniedConfirmResultWithoutReasonKeepsDefaultText` (909): ConfirmedBatchAuthorizerSpec restores a checkpoint and directly checks Denied history with exact custom/default text, no local execution and one follow-up model request. The established local default is "Tool execution declined", intentionally different from Java's "Permission denied by user".
- [x] `deniedConfirmResultOnResumeEmitsToolResultEventTriple` (870): the same resumed-stream table asserts exact Start/Delta/End order, original model context, call identity, denial text and Denied state. The [three-file stream acceptance](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#result-bearing-streams) records final reviews and full verification. Explicit core restore and streamResumeEvents replace Java confirmation-message injection; approval envelope events are not claimed.
- [x] `confirmResultsMayCoverSomeAskingToolCalls` (511) and `pendingToolRecoveryReasksForUnconfirmedAskingTools` (675): PartialConfirmationSpec exercises successive restored subsets with only unresolved original ordinals exposed and no fabricated observation for deferred calls. Its exact Tool-message counts, repeated-ID execution and single final model continuation strengthen those assertions. Typed Suspended/AwaitingApproval data replaces Java mutable ASKING flags and recovery configuration. Both final independent reviews and full verification passed; no mutable ASKING-state or permission-envelope parity is claimed.

- [x] `askingToolPausesFirstCallAndExecutesOnConfirmedSecondCall` (291): CheckpointPermissionIntegrationSpec checks policy-driven PermissionAsking, returned original-ordinal pending data, serialized restoration and confirmed execution with only one follow-up model request. Immutable PendingActing data replaces mutable ASKING flags and confirmation-message injection; both final reviews and full verification passed.
- [x] `allowingToolBypassesHitlEntirely` (815): the same Allow row checks no PermissionAsk/RequestStop events and successful End events for allowed tools, alongside a denied sibling. This is a bounded behavioral adaptation; native Java approval envelopes are absent. Both final reviews and full verification passed; exact identities are in the [policy contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#nonblocking-checkpoint-policy).

- [x] `askingToolEmitsRequireUserConfirmAndRequestStopEvents` (337): PartialConfirmationSpec checks ordinal-ordered generic Ask records before PermissionAsking RequestStop. Request UUIDs map to pending original ordinals; this does not reproduce the Java bundled ToolUse envelope. Both final reviews and full verification passed; the [event contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#checkpoint-permission-events) records exact identities.
- [x] `confirmedResumeEmitsUserConfirmResultEventCorrelatedToRequireEvent` (360): the restored multi-step table checks matching request UUIDs and resolved decisions before each tool Start. These are runtime authorization decisions, not assertions of human confirmation; Java replyId fields are not added to the generic event payload. Both final reviews and full verification passed; the [event contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#checkpoint-permission-events) records exact identities.
- [x] `mixedBatchEmitsToolResultEventsForAutoDeniedToolsBeforeConfirmationRequest` (465): the joined phase table checks Denied Start/Delta/End before Ask and RequestStop, with no asks on failed cleanup or cancellation. Supplied observations fabricate no authorization decision. Both final reviews and full verification passed; the [event contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#checkpoint-permission-events) records exact identities.

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
- [x] Map ReActAgentHitlTest.pendingToolRecoveryPreservesModifiedArgumentsFromConfirmation through the bounded restored-batch evidence above; host persistence remains external.
- [x] Map the three denial history/event methods through the direct restored-stream evidence above; host persistence remains external.
- [x] Complete final acceptance of the partial-confirmation mappings above.
- [x] Accept the bounded generic mixed-batch event correlation above; Java human-envelope compatibility remains an intentional difference. Full-batch core restoration retains reply identity; interactive approval scopes still remain within one active invocation.

## Interactive permission adaptation

The opt-in harness adapter adds sixteen tests and runs within one active invocation. Verified shared behavior is narrower than the complete ReActAgentHitlTest methods at pinned upstream `f98a685fdc4589f81e626e1686ac2c63d14a2304`:

- [x] Execute modified confirmed arguments and persist their provenance before the tool runs; InteractivePermissionIntegrationSpec also verifies one initial model request and one post-tool continuation.
- [x] Preserve custom denial reasons and emit exact correlated Start/Delta/End ordering with matching identity/text/state; no denied tool executes.
- [x] Retain the Scala default decline text, "Tool execution declined". Upstream expects "Permission denied by user"; this is an explicit text difference.
- [x] Recheck both gates against modified arguments and latest policy, merge parallel rule additions, isolate repeated call IDs, and clean cancelled waiters.
- [x] The separate restored checkpoint adapter and resumed-stream tests establish bounded mappings for the three denial history/event methods above; this interactive adapter still operates within one invocation.
- [ ] Implement automatic denied-batch ordering before approval notices, authenticated transport, durable host persistence and exhaustive permission mode/rule parity. Core checkpoint restoration already retains the saved reply context.

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

## Explicit timestamp-watermark migration adaptation

Pinned MemoryConsolidator.readWatermarkFromFile parses stripped timestamp text and writeWatermark writes Instant.toString. The explicit local migration accepts that canonical representation but deliberately replaces it with empty fingerprint acknowledgements. It does not retain timestamp-based skip authority, infer already-consolidated daily inputs or silently recover a pending journal. Ordinary repository reads remain strict.

- [x] Three grouped LocalMemoryWatermarkMigrationSpec contracts establish content preservation and actual subsequent consolidation, strict no-op/rejection behavior and journal/lease exclusion. Both reviewers approved the [two current identities](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md#explicit-watermark-migration), and full verification passed. Existing local filesystem mappings remain scoped as before; `watermark_roundTripThroughFilesystem` and `watermark_doesNotCreateLocalFile` remain unchecked because this feature supplies no injected remote authority.

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
- [x] Map the six remaining MemoryConfig default/builder/prompt methods through the explicit adaptations below. Model selection and Java builder/wire compatibility are not claimed.

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
- [x] Implement bounded host-controlled local transcript discovery for existing maintenance; source-level adaptation and limits are recorded below.
- [ ] Implement the eight remote matrix combinations under a concrete remote storage/ownership contract; local discovery does not establish remote parity.

- [x] `review_fp` and `review_harness` approved the final seven-file consolidation/composition increment against `3e817e3` on 2026-09-29 after correcting nested test-scope ownership. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 346 harness tests, including 25 new cases, with zero compiler warnings. No findings remain within separately scoped observer composition, owned consolidation/result reporting, eight local throttle integration combinations, FIFO replacement and post-commit cancellation/failure. Retention, remote authority, strict cross-job dependencies and transactional reporting are excluded.

## Daily retention hardening

Pinned MemoryMaintenanceMiddleware.expireDailyFiles archives old daily files before consolidation without acknowledgement checks. DailyMemoryRetention deliberately requires exact acknowledged content and an explicit exclusive date cutoff; newer or unconfirmed input stays active. Versioned date/content archives replace upstream single-name overwrite behavior. This is a source-contract adaptation, not a claim that a named upstream archive test exists.

- [x] Add twelve shared in-memory/local storage cases plus two fingerprint/codec cases and ten local publication/ownership cases.
- [x] Reproduce and fix unpaired-UTF-16 digest aliasing; reuse the strict fingerprint boundary in consolidation.
- [x] Exercise stable partial states after archive publication and checkpoint advancement; verify same-content recreation and old-revision conflicts.
- [x] Wire explicit daily retention policy into one owned maintenance job, with consolidation-first hardening and 21 new scenarios. This adds source-contract adaptations, not a named upstream retention test mapping.
- [ ] Complete automatic scheduling and remote retention; bounded local discovery is accepted and explicit timestamp migration is reviewed separately below.

No live crash or mid-operation I/O fault injection is claimed. Archive copy publication can precede failed checkpoint advancement, and a successfully advanced checkpoint can precede incomplete source removal. Surviving daily content then remains pending; reporting failure does not imply rollback.

- [x] `review_fp` and `review_harness` approved the final seven-file daily-retention increment against `b2ab23e` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 370 harness tests, including 24 new cases, with zero compiler warnings. No findings remain within typed archive identity, exact acknowledged eligibility, atomic in-memory retirement, verified local copy/checkpoint/removal ordering, conservative partial progress and strict shared fingerprinting. Automatic policy, session pruning, crash durability and hostile concurrent filesystem mutation are excluded.

## Daily policy and configuration method adaptations

The 21 new policy/service/scheduler tests verify one cutoff before side effects, zone and range boundaries, deferred/cancelled consolidation, one gate reservation, owner coalescing, post-commit failures and aggregate reporting. They preserve the existing consolidation-only adaptations. Upstream archives before consolidation and suppresses some failures; Scala deliberately consolidates first, requires exact acknowledgement and exposes failure in task receipts.

All six formerly pending MemoryConfigTest methods now have explicit adaptations in MemoryConfigSpec, supported by MemoryConfigIntegrationSpec, MemoryTokenBudgetSpec and ConsolidationPromptSpec:

- [x] defaults_matchHistoricalBehaviour: MemoryConfigSpec verifies None/default prompt selection, 4,000 tokens, 30-minute maintenance, Always flush and 90/180-day retention, including omitted-field JSON defaults. Java-null default selection is replaced by Option.
- [x] builder_overridesAreCarried: MemoryConfigSpec verifies every custom field and codec round trip; MemoryConfigIntegrationSpec observes custom extraction prompts, token budget, actual daily archival, transcript cutoff and independent trigger windows.
- [x] builder_consolidationPromptMustHaveTwoIntPlaceholders: ConsolidationPrompt has exactly two fixed budget insertion positions between three required literal segments. Prompt tests verify insertion order and literal percent/braces preservation. Java printf strings and rejection of 0/1/3 placeholders are deliberately replaced by the typed shape; arbitrary strings are not accepted as templates.
- [x] builder_rejectsInvalidNumericArgs: typed constructors and codecs reject invalid budgets and daily/session days; the existing 4,000-token hard ceiling is retained as stricter runtime policy. Legacy Int factories use the same budget validation.
- [x] builder_rejectsNullDurationsAndTrigger: typed trigger construction rejects negative/infinite durations and normalizes zero to Always; Java-null inputs are outside the supported Pure Scala contract. No runtime null-acceptance guarantee is claimed.
- [x] builder_isReusable_andEachBuildIsIndependent: immutable config copy preserves the entire earlier value while independently replacing prompt, budget, triggers and retention; changed policies do not mutate an existing gate or its windows.

KeepAll is an explicit Scala archival policy, not upstream null/sentinel compatibility. Both public schedulers share the Maintenance window, so installing the combined scheduler replaces the consolidation-only adapter for an owner requiring retention. All ten named MemoryConfigTest methods are classified through the four trigger adaptations and six configuration adaptations. This does not reproduce the Java builder, printf language, wire format or model-registry override API.

- [x] `review_fp` and `review_harness` approved the final eight-file maintenance increment against `b3d2723` on 2026-09-29, including corrected consolidation-only ScalaDoc. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 391 harness tests, including 21 new cases, with zero compiler warnings. No findings remain within validated calendar policy, repository-bound sequential maintenance, typed aggregate outcomes, one owned gate/job and preserved consolidation-only behavior. Session pruning, unified configuration, cross-phase atomicity and durable reporting are excluded.

## Explicit transcript retirement adaptation

Pinned MemoryMaintenanceMiddleware.pruneOldSessions scans session log names, compares modification timestamps and suppresses per-file failures. LocalTranscriptRetention instead accepts one explicit directory, acquires the writer lease, validates the complete versioned log and uses maximum metadata/recorded activity before removing its fixed payload. This is a deliberately hardened source-contract adaptation, not Java layout equivalence or a newly mapped named pruning test.

- [x] Verify thirteen local cases covering cutoff/activity, missing/empty data, corruption preservation, owner refusal, unrelated files, generation reset and concurrent attempts.
- [x] Add explicit 180-day policy and KeepAll semantics with eight new cases.
- [x] Add explicit target batches and owned maintenance integration with 22 additional regressions.
- [ ] Implement remote pruning and platform-specific hostile-mutation/crash validation if those guarantees are required.

Cancellation-after-admission uses the previously reviewed WorkspaceLease contract; interruption may follow completed deletion. No mid-delete fault injection is claimed. A successful prune discards persisted keys and parent linkage, so hosts must retire the logical session rather than assume continuation.

- [x] `review_fp` and `review_harness` approved the final three-file transcript-pruning increment against `9ddee3a` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 404 harness tests, including thirteen new cases, with zero compiler warnings. No findings remain within explicit local targets, shared writer ownership, strict log validation, observation-aware activity, fixed-payload deletion and generation retirement. Session discovery, automatic expiry, remote pruning and crash/hostile-mutation guarantees are excluded.

## Transcript policy evidence

Eight new tests verify the shared 180-day default, positive day decoding, exact fixed-day arithmetic, nanosecond preservation, range rejection, disabled/evaluated outcomes and one clock sample before ownership. These supplied partial default and numeric-validation evidence; the six complete method adaptations are now documented above. KeepAll is a typed Scala extension and the existing explicit-cutoff API is unchanged.

- [x] Verify disabled policy does not read Clock or access even an untrusted/missing target.
- [x] Verify enabled underflow fails before ownership and cutoff equality retains data.
- [x] Integrate explicit target batches into one existing maintenance admission, without a competing session-only throttle.

- [x] `review_fp` and `review_harness` approved the final five-file transcript-policy increment against `f1a005e` on 2026-09-29. Full `sbt scalafmtAll scalafmtSbt verify` passed 555 core / 412 harness tests, including eight new cases, with zero compiler warnings. No findings remain within explicit 180-day policy, strict shared day validation, safe Instant arithmetic, one pre-ownership clock sample and complete KeepAll bypass. Batch/scheduler integration and complete upstream configuration parity are excluded.

## Explicit batch maintenance evidence

- [x] Add three target, seven batch, eight coordinator and four scheduler cases, including real local partial persistence and actual ReAct completion observers.
- [x] Verify one prepared transcript cutoff, exact target ordering, complete cause preservation, independent transcript work after all returned memory outcomes and one existing Maintenance admission.
- [x] Complete the six MemoryConfig method adaptations above in the later immutable-configuration increment; the batch increment itself adds source-contract evidence only.

Selected capabilities intentionally replace broad unbounded directory scanning and swallowed per-file failures. Opt-in bounded local discovery is described below; cross-phase atomicity and exactly-once reporting are not claimed. The canonical identities and scope are in the [memory contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md#selected-transcript-maintenance).

## Bounded local transcript discovery evidence

Pinned `agentscope-harness/src/main/java/io/agentscope/harness/agent/middleware/MemoryMaintenanceMiddleware.java`, `pruneOldSessions` lines 255-282, supplies source-level intent: glob session logs, compare modification timestamps and suppress per-file failures. No matching named Java test is claimed. The Scala adaptation selects sorted immediate child directories containing transcript.jsonl under explicit HostControlled trust, with entry/target bounds. Existing pruning retains cooperative writer ownership, strict body validation, observation-aware recency and propagated failures. Java layout, mtime-only deletion and swallowed failures are intentional differences.

- [x] Three LocalTranscriptDiscoverySpec groups cover fresh ordered coordinator selection, structural rejection before memory, and later lease/body failure with observable earlier progress. A new batch group covers checked snapshots and joined cancellation with an exact cleanup defect; KeepAll also proves discovery and Clock are bypassed. Both reviewers approved the [four current identities](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/transcript/README.md#bounded-local-transcript-discovery), and full verification passed.
- [ ] Remote enumeration/pruning, symlink/junction platform evidence and hostile-filesystem guarantees remain separate. Local capabilities do not define a remote conditional-publication or recovery protocol.

## Configuration integration evidence

- [x] Verify six explicit MemoryConfig method adaptations, supported by three budget, three prompt and five end-to-end component configuration cases.
- [x] Preserve default prompt wording and token rejection before memory/content acknowledgement changes for both consolidation consumers.
- [x] Verify explicit gate/batch wiring and immutable configuration changes without mutating existing reservations.
- [x] Primary/dedicated memory-model selection is available through MemoryExtractor.fromClient and explicit extractor injection. MemoryExtractorSpec lines 33-70 verifies exact selected input/config and scoped ownership. Pinned MemoryConfig.java169-176/247-260 and HarnessAgent.java2575-2602 select a live override or primary for flush/consolidation; Scala deliberately performs that assembly in the host. None of the ten MemoryConfigTest methods tests model selection, so this is source-contract adaptation, not a new whole-method mapping.
- [ ] String-ID model registry resolution remains unimplemented and conditional on a separately specified host contract; live clients do not belong in serialized MemoryConfig.

All four earlier trigger adaptations remain required. This closes the named configuration suite classification, not Java API/wire equivalence or exhaustive harness parity. Current source identities and review evidence are in the [memory contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md#immutable-memory-configuration).

## Plan-mode inventory

Pinned upstream has fourteen methods across four suites: fourteen bounded adaptations. Static-shell safety checks support runtime admission but do not independently complete these methods. Upstream additionalAllowed permits shell tools without command inspection and adds only a prompt reminder; that bypass is intentionally not adopted. Spawn/send names must not be admitted before child restrictions exist.

- [x] `PlanModeMiddlewareTest.deniesMutatingToolsButAllowsReadOnlyAndPlanTools`: PlanPermissionIntegrationSpec verifies read/limited-plan pass-through and persisted Start/Delta/End denied observations for mutation and unclassified send; a separate RequestExecution case forces confirmation. Adaptation: exact host classifications replace implicit tool-name exemptions, and runtime preserves original occurrence order rather than upstream denied-first partitioning.
- [x] `PlanModeMiddlewareTest.passesThroughWhenPlanModeInactive`: PlanPermissionGateSpec and real ReAct integration verify Build abstention and policy-approved mutation.
- [x] `PlanModeMiddlewareTest.systemPromptBannerOnlyWhenActive`: PlanContextInputSpec verifies active planning context with configured location and unchanged canonical System/history; approved Build has distinct context. Adaptation: request-only User projection replaces Java System-prompt mutation.
- [x] `PlanModeMiddlewareTest.buildModeHintSurfacesPlanFilePath`: PlanContextSpec and real request integration retain recorded location in approved Build. Adaptation: exact concrete-draft host review is required; upstream enter-then-unconditional-exit without writing is not adopted. Approval concerns captured content, not current contents at the path.
- [x] `PlanModeMiddlewareTest.buildModeNoHintWhenNoPlanFile`: PlanPromptSpec and PlanInputTransformSpec verify fresh Build has no hint and preserves the exact supplied history. Explicit adaptation: no in-memory draft provenance replaces the absent file reference; System messages are not modified.
- [x] `PlanModeMiddlewareTest.allowsSubagentToolsInPlanMode`: PlanPermissionIntegrationSpec invokes supported agent_spawn/agent_send and agent_task/agent_cancel under explicit parent Plan classifications; child read succeeds and mutation is denied. Java agent_list/task_output/task_list aliases and implicit name exemptions are not implemented.
- [x] `PlanModeMiddlewareTest.skillLoadToolIsReadOnlyAndPermittedInPlanMode`: bounded adaptation through PlanPermissionIntegrationSpec with the actual installed loader, successful activation and subsequent read admission/mutation denial. Explicit host classifications and policy Deny still win; actual Tool accessors override conflicting definition flags, and model metadata cannot grant read access. The upstream exact three-event assertion is supported by existing ordinary denial-event coverage, not repeated in this skill scenario.
- [x] `PlanModeManagerTest.enterAndExitTogglePersistedFlag`: PlanLocationIntegrationSpec verifies location retention across entry, exact publication, approved exit and re-entry. Adaptation: explicit host-selected canonical destination replaces implicit plans-directory discovery, and approval is version-bound. Reopen restores only unapproved content; the Java test does not prove restart durability.
- [x] `PlanModeManagerTest.customPlanDirNormalised`: PlanDirectorySpec and PlanWorkspaceStoreSpec normalize /docs/plans/ to docs/plans, resolve beneath an explicit canonical workspace and publish actual PLAN.md content at that destination. Adaptation: a validated logical descriptor rejects traversal, empty internal segments, unsafe/native syntax and reserved device names; the host must supply existing directories. Native open(Path) semantics remain unchanged. This is not hostile-filesystem isolation.
- [x] `PlanModeManagerTest.writePlanRecordsPathAndDoesNotThrow`: PlanLocationIntegrationSpec verifies actual on-disk exact draft text and matching canonical descriptive destination. Typed failures remain observable rather than being hidden by a no-throw assertion.
- [x] `PlanModeSubagentPropagationTest.declaredSubagent_inheritsPlanModeManagerMiddlewareAndConfiguration`: SubagentPlanInstallationSpec runs the actual declared factory with child-local enter/write/exit, exact stored draft review, configured location/request-only context and inspected-read versus mutation admission. Explicit host dependencies replace automatic Java manager/middleware installation; no unrestricted shell bypass or physical-file claim.
- [x] `PlanModeSubagentPropagationTest.generalPurposeSubagent_inheritsPlanModeManagerMiddlewareAndConfiguration`: The same actual-runtime table verifies the general-purpose preset through the opt-in installation path, preserving child-local review, request-only context and explicit host classification precedence.
- [x] `AgentSpawnToolPlanModeTest.persistentSpawnReuse_reappliesParentPlanModeBeforeReturningOrInvoking`: Named-session integration retains one identity, initialization and history across parent Build/Plan transitions. Live ancestor OR admission replaces Java persistent-key re-spawn and copied mode flags; this is a lifecycle adaptation, not that API.
- [x] `AgentSpawnToolPlanModeTest.agentSend_reappliesParentPlanModeBeforeInvokingExistingChild`: Actual named agent_send reuses the child across parent/child mode changes. Child review cannot lift ancestor Plan; parent Build cannot lift child-owned Plan, and normal mutation resumes only when both permit it.

- [x] Verify host-installed inherited plan admission in [PlanInheritedPermissionSpec](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/plan/PlanInheritedPermissionSpec.scala): actual configured children obey parent Plan restrictions, keep independent exact-draft review under parent Build, and recheck live parent activation and modified arguments after confirmation. This remains supporting gate evidence; the separate child-installation and named-send fixtures above close the bounded mappings without Java middleware/state-copy parity.

The manager's persisted-flag test observes state fields, not save/reload durability. Plan directory slash normalization is not canonical path safety. Confirmed exit requires exact-ticket authenticated host review; generic RequestExecution Ask and independent policy gates remain separate admission decisions. Live inherited admission applies to reused children. Parent Build removes only the ancestor restriction; a child in its own Plan remains restricted until its own review. Current static grammar acceptance is recorded in the [plan contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md).


## Plan permission integration evidence

- [x] Verify fourteen new cases covering typed modes, trusted classifications, gate priority, strict envelopes, cleanup and real runtime admission.
- [x] Reevaluate current mode and modified authoritative input after confirmation; reject replacement mutations and extra execution fields.
- [x] Force Ask for RequestExecution without implementing or claiming a mode transition.
- [x] Verify admitted execution can finish after a mode change while the next call is denied.
- [x] Complete the five bounded child/control adaptations above through explicit installation and actual named-session evidence.
- [ ] Integrate authenticated host UI and durable approval restoration.

Admission includes skill-loader and child-control adaptations; all fourteen inventory methods have bounded mappings, while Java API/middleware parity, authenticated UI and durable approval restoration remain excluded. Safe static commands abstain; they do not acquire path/process authority or bypass ordinary permission policy. Current identities are in the [plan contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md#current-admission-identities).


## In-memory plan-session evidence

- [x] Adapt live Build/Plan transitions through a separate host review capability and use the same session mode for the installed permission gate.
- [x] Require a concrete exact draft; edits/re-entry/new requests invalidate old approvals and atomically settle superseded waiters.
- [x] Verify foreign/stale/replayed tickets, concurrent resolution, edit-versus-approval ordering and scope/cancellation ownership.
- [x] Implement safe standalone files and stored-session publication as recorded below.
- [x] Adapt manager path-recording/retention through explicit canonical location context and exact host approval.
- [ ] Implement separately specified approval restoration; typed workspace-relative directory selection is available.
- [x] Implement truthful request-only prompt projection from one snapshot.
- [x] Implement the model-facing review tool adapter.
- [ ] Integrate authenticated host UI transport.

This deliberately hardens upstream unconditional exit and unversioned approval. The eighteen added tests establish in-memory state and capability ownership, not a file or approval-delivery protocol. Current identities are in the [session contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md#current-session-identities).


## Plan projection evidence

- [x] Add thirteen render/transform/real-runtime cases; preserve System messages, canonical history, complete tool pairs and original final-user fields.
- [x] Sample one snapshot per request; prove mode changes during reasoning still use live gate authority.
- [x] Adapt fresh Build no-hint behavior, with explicit configured/observed location provenance in the manager and prompt mappings above.
- [x] Verify opt-in child installation and live retained-session restrictions; all fourteen inventory methods have bounded adaptations.

Request-only User context intentionally replaces upstream System-prompt mutation. JSON-quoted drafts remain data rather than a security or authorization boundary. Current identities and acceptance are in the [plan contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md#current-projection-identities).

- [x] Add fifteen standalone plan-store hardening cases for content/Unicode, ownership, atomic replacement and typed error provenance. This storage increment alone closes no additional whole method; location integration below now supplies four more adaptations.
- [x] Integrate persisted draft publication with session generations, with fourteen new failure/cancellation and local-reopen cases.
- [x] Adapt model-facing enter/write/exit behavior with strict inputs and mandatory exact-ticket host review; fifteen new control/delivery/runtime cases.
- [x] Add explicit configured/observed location provenance and four manager/prompt method adaptations without claiming continuous file existence.

PlanModeTools has no separately named pinned test suite in this inventory. PlanToolsSpec and PlanToolsIntegrationSpec verify stricter input, scoped review and typed outcomes without claiming additional whole-method parity.

- [x] Independently review location/context behavior, including the reproduced supplementary Unicode FORMAT validation defect.

- [x] Independently review twelve directory/ownership cases, including a reproduced boundary-control defect; adapt customPlanDirNormalised with stricter logical-directory validation. Current plan inventory: fourteen bounded adaptations.


## Subagent supervision inventory

Pinned upstream f98a685fdc4589f81e626e1686ac2c63d14a2304 contains the following related lifecycle methods. TaskSupervisorSpec and TaskSupervisorAgentSpec establish scoped execution ownership only. They do not invoke agent_spawn, create task IDs/list entries, forward child events or persist adopted tasks. The configured factory and registry add the bounded adaptations explicitly marked complete below. External future adoption, Java session/class shapes and durable repository behavior are not implied.

- [x] Verify eighteen local supervision cases, including real ReAct cancellation and same-execution continuation.
- [x] AgentSpawnToolPromotionTest.innerTimeoutPromotesToAdoptedTaskRunSpec: bounded in-process adaptation. [SubagentToolsSpec](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/subagent/SubagentToolsSpec.scala), `promotion retains one execution and inspection and cancellation never call the model again`, verifies actual agent_spawn returns the retained ID after 30 seconds, survives its caller Scope and supports inspection/cancellation without restarting. [TaskSupervisorAgentSpec](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/subagent/TaskSupervisorAgentSpec.scala), `promoted child completes the same tool and next model turn after parent closure`, separately covers successful continuation. External future adoption, Java result formatting and durable storage are excluded.
- [x] AgentSpawnToolOrphanCancelTest.orphanAgentInterruptedOnParentCancel: bounded scoped-cancellation adaptation. The actual tool test `interrupting the active spawn tool waits for child cleanup and preserves caller interruption` verifies caller cancellation waits for cleanup and leaves an interrupted retained result; the existing supervisor/agent test verifies actual ReAct history reconciliation. Promise barriers replace upstream wall-clock polling and file writes; remote orphan recovery is excluded.
- [x] Both reviewers approved the bounded AgentSpawnToolCancelEndEventTest.parentCancel_emitsAgentEndEvent adaptation, and full verification passed: actual parent.runForwarding cancellation keeps an independent active relay alive through joined child cleanup, with one task-source Start/End pair, matching reply IDs and child End before parent End. This replaces the Java retained emitter with an explicit scoped capability, not a guarantee to a cancelled public stream consumer; such consumers abandon their tail.
- [x] AgentSpawnToolCancelEndEventTest.normalCompletion_emitsPairedEvents: the nested SubagentForwardingSpec scenario observes exactly one Start/End pair per execution with matching nonempty reply IDs. Runtime UUID sources intentionally replace declaration-name paths; no synthetic duplicate bookends are emitted.
- [x] `AgentSpawnToolForceSyncTest.helpers`: bounded typed-policy adaptation in SubagentExecutionPolicySpec. Positive host budgets through ten minutes and integer-nanosecond codecs replace ambient Boolean/string parsing, invalid-override fallback and clamping. Model-selected timeouts are not exposed.
- [x] `AgentSpawnToolForceSyncTest.zeroTimeoutCoercedToSync`: SubagentToolsSpec invokes actual fresh spawn and retained-session send under an explicit synchronous policy and observes completed results without promotion. Java timeout_seconds=0 coercion and absence of a separate background repository are intentional differences; the existing Scala registry retains each execution.
- [x] `AgentSpawnToolForceSyncTest.timeoutHardFailsWithoutPromotion`: the same runtime table verifies timeout Error with no task ID/promotion, one execution, held cleanup joining and the retained full Exit. Additional caller/admission cancellation cases preserve exact cleanup defects and reject pre-admission cancellation without a record. The [policy acceptance](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#host-execution-policy) records final identities and the reproduced admitted-ID handoff regression. These are bounded adaptations, not literal Java helper/repository parity.
- [x] AdoptedTaskRunSpecTest.adoptedFuture_completesSuccessfully: bounded local execution-record adaptation in [SubagentTaskRegistrySpec](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/subagent/SubagentTaskRegistrySpec.scala), `concurrent admission bounds retained results and executes fresh configured children` and `snapshots retain typed failures, defects and interruption as complete exits`. Awaited success remains inspectable as a full Exit; scoped execution replaces external CompletableFuture adoption and file-backed persistence.
- [x] AdoptedTaskRunSpecTest.adoptedFuture_failsTransitionsToFailed: the full-Exit snapshot scenario preserves typed failure and cleanup defects in a retained terminal record. Scala uses Completed(Exit.Failure) rather than a separate Java FAILED enum; durable result persistence is excluded.
- [x] AdoptedTaskRunSpecTest.adoptedFuture_appearsInTaskList: bounded registry-membership adaptation in the concurrent-admission and shutdown scenarios. Listed IDs match admitted executions, with record capacity and registry-local lookup; session-addressed remote repositories are excluded.
- [ ] AdoptedTaskRunSpecTest.adoptedFuture_alreadyCompleted: fast local completion is supported, but importing an already completed external task before registration is not an available API.
- [x] Both reviewers approved the bounded adaptation of AdoptedTaskRunSpecTest.adoptedFuture_eligibleForPushDelivery (pinned method lines 175-206): completed task ID/status/result is discoverable for host delivery. SubagentTaskRegistrySpec additionally verifies submission order, joined failure/cleanup causes, explicit acknowledgement without capacity release, host delivery cancellation and post-close access. The Scala registry uses supervisor-owned tasks instead of adopted CompletableFuture values; actual network push and event replay are not claimed. Full verification passed; exact current identities belong to the [inbox contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#completion-delivery-inbox).
- [x] ManagedSubagentBoundaryTest.declarationFiltersAutoRegisteredToolsAsWellAsInheritedTools: bounded adaptation in [SubagentFactorySpec](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/subagent/SubagentFactorySpec.scala), `the model sees final filtering of inherited and automatic tools, with monotone factory narrowing`. Actual supervised children advertise the final inherited/registered selection; excluded hallucinated calls produce Error observations without executing the tool. Host callbacks replace upstream automatic platform registration and declarative parsing.
- [x] ManagedSubagentBoundaryTest.childCannotReenableParentDeniedTool: bounded static exact-name adaptation in the same table-driven child execution test. Child selection cannot restore parent-denied tools; derived factory boundaries remain narrowed and parent toolkits remain unchanged. Argument-sensitive PermissionEngine inheritance is outside this mapping.

- [x] Verify bounded supporting adaptations of AgentSpawnToolPermissionTest in [SubagentFactorySpec](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/subagent/SubagentFactorySpec.scala): actual children reject matching parent denials, preserve child Allow/Ask/Deny, recheck changed policy and confirmed arguments, and retain cancellation cleanup. Parent modes, external gates, working directories and Java harness/session/class wiring are excluded.
- [ ] Complete the remaining applicability/integration mapping for AgentSpawnToolPermissionTest.harnessChildReceivesParentDenyInActualExecutionSlot and directReactChildKeepsItsOwnPermissionContextAndAddsOnlyParentDeny; the callback-based adaptations above do not establish whole-method Java lifecycle parity.
- [x] Intentionally exclude AgentSpawnToolPermissionTest.declarationCanDisableParentDenyInheritance from the safe declaration path: declaration data cannot disable ancestor Deny inheritance. Raw host factories remain separately trusted capabilities.
- [ ] AgentSpawnToolPermissionTest.persistentReuseAndAgentSendAddCurrentParentDenyWithoutDuplicates: host-held reuse now preserves live injected denials, and fixed/named-session agent_send is available, but per-call manager overrides and historical denial accumulation remain pending; live policy replacement is a different contract.

- [x] Verify the Scala retained-resource contract in [TaskResourceSpec](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/subagent/TaskResourceSpec.scala): five grouped cases cover typed resources across turns, competing closure with shared cleanup defects, failed/self-interrupted initialization, caller/root shutdown during initialization, and nested running/queued/promoted cleanup before release. The unchanged-defect regressions recorded finalizers receiving success instead of initialization failure (`resource-exit-red.log`) and rival closers returning success before cleanup and losing its defect (`resource-close-red.log`). This primitive closes no additional whole upstream method.

- [x] Verify bounded ordinary-run reuse in [SubagentSessionSpec](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/subagent/SubagentSessionSpec.scala): five actual-runtime scenarios cover one depth-three filtered runtime/resource lifetime with separate execution IDs; serialization and queued cancellation without history changes; live denial/Plan checks without approval transfer; promoted/queued/descendant cleanup before client release; and invalid capacity, closed ownership and failed initialization. Two additional session groups verify host-only resume through installed partial confirmation authority or supplied observations; invalid/no-pending/closed outcomes, queued cancellation, joined failing cleanup, original-context forwarded terminal events and subsequent fresh sends. [Current acceptance](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#host-only-session-resume) records exact identities. The additional [checkpoint-import tests](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#host-checkpoint-import) cover JSON partial/supplied restoration under current parent denial, fresh lineage, no execution/records before resume and full joined failure cleanup. Both reviewers approved these two groups and full `child-restore-verify.log` verification passed. These are bounded Scala integration additions, not newly closed Java methods or durable-key restoration. These supporting contracts close no additional whole upstream method; durable manager keys, per-call manager overrides and historical denial accumulation remain excluded.

- [x] Verify the session-bound tool adapter in two [SubagentToolsSpec](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/subagent/SubagentToolsSpec.scala) integration scenarios: strict prompt-only sends retain one runtime with isolated task lookup; promotion, queued cancellation, joined cleanup and later reuse preserve the session contract. No session_id selection or additional whole upstream method is claimed.

- [x] Verify host session membership in four grouped [SubagentSessionDirectorySpec](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/subagent/SubagentSessionDirectorySpec.scala) scenarios: rejected-registration ownership, collision/alias/directory isolation, independent retained histories, external owner closure, joined removal and parallel shutdown with cleanup-defect replay. This supporting contract adds no declaration/key reuse or whole upstream method mapping.

- [x] Verify directory-bound tools in three [SubagentDirectoryToolsSpec](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/subagent/SubagentDirectoryToolsSpec.scala) scenarios: isolated retained histories and task IDs, strict inputs and authority confinement, selected identity on success/failure, and promoted cancellation concurrent with membership removal. This supports session-selected send behavior in `AgentSpawnToolKeyTest.runtimeContextManagerIsUsedForSpawnAndSend`; declaration lookup, manager overrides, deterministic reuse keys and restoration remain unimplemented, so the whole method remains open.

The [current acceptance record](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#verification-and-acceptance) records exact source/test identities and review scopes for `path_review` and `current_docs`, including both approvals of resource ownership, child sessions, host session membership, fixed-session and directory-routed tool adapters, and policy-aware construction. The [plan contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md) records inherited-plan evidence; the [tracker](tracker.md#current-acceptance) records current full verification. Registry regressions cover capacity, collision rejection, completed-record retirement, caller leases, shutdown and closed-caller admission.

- [x] Verify explicit gate composition and authorizer views in three grouped PermissionGateSpec/InteractivePermissionAuthorizerSpec scenarios: optional-vote precedence, ordered scoped cleanup, failure/cancellation and preserved confirmation authority with modified-input rechecks. This prerequisite closes no additional whole upstream method and does not install child policy automatically.

- [x] Verify opt-in policy-aware construction in three [SubagentConstructionSpec](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/subagent/SubagentConstructionSpec.scala) scenarios: nested static/argument restrictions with factory derivatives and a permitted near-miss; live reused denials and independent ancestor/child Plan checks with modified confirmation; isolated supplied per-build permissions and joined initialization/filtering cleanup. This earlier construction evidence does not itself install tools/context or directories. The separate opt-in installation completes the bounded Plan mappings; Java sticky mode mutation and historical denial accumulation are not reproduced.

The fourteen Plan inventory methods now have bounded adaptations; full Java API/framework parity is not claimed. Parent Build must not manufacture child approval. Explicitly installed gates remain effective across host-held session turns and session-bound sends. Local declaration loading and finite host-installed reusable-session catalogs are available. Post-promotion/remote event delivery and durable host recovery remain separate.

### Inline subagent declarations

- [x] Four [SubagentDeclarationSpec](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/subagent/SubagentDeclarationSpec.scala) groups verify pure validation/lazy compilation and codec migration, parsed skill selection reaching actual dispatch, actual runtime configuration and final inherited/registered filtering, and retained-session live Deny/Plan with independent child review. `registry_contract` and `current_docs` approved the exact source/spec identities in the [subagent acceptance](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#verification-and-acceptance); full verification passed.
- [x] Adapt model/temperature inheritance and explicit iteration overrides from `SubagentDeclarationPhaseATest.builderDefaults_areInheritSemantics` and `builder_setsHyperparams`. The Scala default inherits parent iterations instead of Java's fixed ten; variant and deprecated builder aliases are excluded from this bounded API, so the whole methods remain unclosed.
- [x] Support the available Scala execution configuration from `HarnessAgentTest.declaredSubagent_inheritsExecutionConfigsFromParent`: timeout, tool concurrency and thinking retry settings remain inherited, and an actual run demonstrates iteration selection. Java's separate model/tool retry configurations and isolated workspace construction are not represented; this is not a whole-method mapping.
- [x] Extend `ManagedSubagentBoundaryTest.declarationFiltersAutoRegisteredToolsAsWellAsInheritedTools` support through actual declarations and the general-purpose preset. Existing construction tests retain nested ancestor restrictions and cancellation coverage rather than duplicating that matrix.
- [ ] Complete automatic session installation and full Plan tool/prompt installation. `PlanModeSubagentPropagationTest` whole-method entries remain open; current tests establish live policy composition and independent review only.

### Inline declaration document parsing

- [x] Three [SubagentDeclarationParserSpec](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/subagent/SubagentDeclarationParserSpec.scala) groups verify the bounded Markdown/YAML profile including checked topP aliases, exact body preservation, null/empty selection distinctions, typed rejection and UTF-16 limits. Both reviewers approved the [exact parser/build/spec identities](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#inline-declaration-parsing); the false-delimiter and CRLF assertions failed before correction, and full verification passes.
- [x] Adapt `SubagentDeclarationPhaseATest.loader_prefersStepsOverDeprecatedMaxIters` and `loader_fallsBackToMaxItersWhenStepsAbsent`: valid steps takes precedence, absent/plain-null steps uses maxIters, and both provided aliases must validate.
- [x] Support name, temperature and iteration assertions from `loader_parsesAllHyperparams` and `loader_omittedHyperparams_areNullForInheritance`. Omitted iterations inherit the Scala parent rather than Java's fixed ten; variant remains unsupported, so these whole methods remain open.
- [x] Intentionally differ from `loader_malformedNumeric_isTreatedAsUnset`: malformed present numbers fail instead of silently inheriting. Explicit empty tools selects none instead of Java's empty-to-inherit conversion.
- [x] SubagentDeclarationPhaseATest.loader_acceptsCamelCaseTopP (102) maps to the existing parser table; snake-case top_p and actual child inheritance/override are also verified. builder_setsHyperparams (48), loader_parsesAllHyperparams (80) and loader_omittedHyperparams_areNullForInheritance (148) remain partial because variant and fixed-ten defaults differ. loader_malformedNumeric_isTreatedAsUnset (165) is intentionally rejected rather than silently inherited; dual alias presence also rejects, even null/null.
- [x] OpenAIChatFormatterTest.testApplyOptions (138) has a bounded topP/frequencyPenalty/presencePenalty request-field adaptation through existing request/failover tables. GenerateOptions (658-685) documents the penalty range; Scala additionally enforces finite values in [-2, 2], rather than relying on the Java builder's unchecked assignment. Seed remains unsupported, so the whole method is not closed. Both reviewers and full verification are recorded in [optional penalties](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#optional-frequency-and-presence-penalties).
- [x] ReActAgentGenerateOptionsTest.testAllParametersPassedToModel (309) has a bounded penalty-propagation adaptation through selected-target request projection and serialized controlled-driver requests. This covers the two optional penalties without claiming Java GenerateOptions merge rules, ExecutionConfig behavior or whole-suite parity.
- [ ] Complete applicable `HarnessAgentTest.agentSpecLoader_markdownDeclaration_isolatedWithWorkspacePath`, `agentSpecLoader_markdownDeclaration_sharedMode_noPath_inlineBody` only after their workspace contracts exist. Current parsing does not close these multi-field methods, filesystem namespace loading or full YAML compatibility.

- [x] Adapt `HarnessAgentTest.skillsAllowlist_declarationWithSkills_setsSkillFilterOnChild`, `agentSpecLoader_markdownDeclaration_parsesSkillsAllowlist` and `agentSpecLoader_markdownDeclaration_noSkills_returnsEmptyList` through typed declaration selection, independent tools/skills parser tables and actual runtime dispatch. Missing/plain-null skills inherit the captured parent catalogue; explicit empty selection grants no skills. `skillsAllowlist_emptyList_defaultsToAll` and `skillsAllowlist_nullSkills_defaultsToEmptyList` are intentional typed-model differences: there is no null-valued Scala selection, and Only(empty) is not inheritance. Unknown selected IDs fail composition before callbacks. Both reviewers approved the [four-file scope](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#inline-declaration-parsing); full verification passed.

### Local declaration discovery/loading

- [x] Three [LocalSubagentDeclarationsSpec](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/subagent/LocalSubagentDeclarationsSpec.scala) groups verify cold, sorted nonrecursive discovery and filename identity; explicit trust/path/whole-batch rejection; all-entry and selected-document limits, strict UTF-8, byte/parser bounds and resource release. Both reviewers approved the [exact source/spec identities](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#local-declaration-loading), and full verification passed.
- [x] Adapt the pinned `AgentSpecLoader.loadFromDirectory` and `loadFromFile` source behavior for sorted direct lowercase .md entries and filename-derived names. No standalone matched upstream test method is claimed closed. Scala intentionally fails missing directories and the whole batch on selected-file errors instead of Java's empty/skip behavior, and rejects selected directories/symlinks.
- [ ] Verify runtime symlink rejection and held-I/O cancellation cleanup. Automatic execution policy blocked symlink preflight; this is not evidence of OS inability. Masked operations and scoped closure are source-reviewed, with no cancellation deadline or atomic sandbox claim.
- [ ] Complete workspace and filesystem-namespace contracts separately; selected declaration skills are implemented. No full loader parity is claimed.

- [x] Independently review child admitted-access propagation: existing SubagentFactorySpec confirmation cases retain exact runtime evidence through nested parent-denial wrappers, and one SubagentConstructionSpec group verifies opt-in fallback, legacy maps, live Plan and explicit host/parent denial in a retained actual child. This is a Scala typed-capability integration; declared/general-purpose Plan tool/context installation has separate accepted evidence above. The [scoped acceptance](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#admitted-tool-plan-access) records all four identities and full verification.

- [x] Independently review explicit child skill snapshots: SkillCatalogSpec adapts the finite-set membership behavior of `SkillFilterTest.testNone` and `testOnly`; SubagentConstructionSpec verifies actual child/grandchild narrowing, empty selection, schema/dispatch, fresh activation and retained fixed-policy/static boundaries. This adapts the filtering intent of `HarnessAgentBuilderSupport` with checked immutable subsets; unknown descendant IDs fail rather than being silently removed by intersection. Declaration fields/parsing are covered by the separate declaration mapping above. AllAvailable expresses inheritance and Only(empty) expresses no skills, unlike the Java declaration empty-list default. General filters/overlays and workspace discovery remain separate. The [six-file acceptance](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#child-skill-snapshots) records final review and verification.

### Named factory catalog

- [x] Three [SubagentCatalogSpec](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/subagent/SubagentCatalogSpec.scala) groups verify actual parent selection of fresh declared children, strict inputs/unknown names, per-entry retention and task isolation, depth-error identity, promotion and parallel joined owner cleanup. Both independent reviewers approved the [exact catalog/tools/spec identities](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#verification-and-acceptance); the missing-identity assertion failed before correction and full verification now passes.
- [x] Adapt named selection and unknown-agent rejection from `AgentSpawnToolKeyTest.runtimeContextManagerIsUsedForSpawnAndSend`. The catalog captures a fixed host parent and factories; it does not implement per-call manager overrides, primary mode, deterministic reuse keys or restoration, so the whole method remains open.
- [x] Adapt `SubagentModeHiddenTest.renderSubagentSection_programmaticEntriesAlwaysShown` through catalog names/descriptions in tool definitions. All supplied entries are visible; no system-prompt renderer or hidden/primary mode is implemented.
- [ ] Complete the applicability mapping for `DefaultAgentManagerRuntimeContextTest.createAgentIfPresent_forwardsParentRuntimeContext` and `createAgent_forwardsParentRuntimeContext`: Scala carries a captured lineage/policy capability rather than forwarding the Java RuntimeContext object. Null-context fallback is inapplicable to the non-null typed API.
- [ ] Complete post-promotion/remote event delivery and durable recovery under separate contracts.

### Named reusable-session catalog

- [x] Three [SubagentSessionCatalogSpec](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/subagent/SubagentSessionCatalogSpec.scala) groups verify eager finite host installation, stable per-name runtime/history, distinct-name/catalog isolation, unchanged descendant depth, partial compound-failure/cancellation rollback and joined removal/aggregate cleanup. One additional [TaskResourceSpec](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/subagent/TaskResourceSpec.scala) group verifies public ownRetained lifetime and exact closure-cause replay. Both reviewers approved the [exact identities](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#named-session-catalog); full verification passes.
- [x] Reproduce duplicate cleanup causes in `subagent-session-catalog-red.log`: two groups passed and aggregate/caller closure failed exact single-defect equality. The same catalog spec passes after removing redundant automatic ownership edges, without changing public caller-leased ownership or applying Cause filtering.
- [x] Supply bounded supporting evidence for reusable runtime/history behavior within `AgentSpawnToolKeyTest.runtimeContextManagerIsUsedForSpawnAndSend`. This whole method remains open: per-call manager selection, repository restoration and its background-send adapter are not implemented by this host API.
- [x] Three [SubagentSessionCatalogToolsSpec](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/subagent/SubagentSessionCatalogToolsSpec.scala) groups extend the bounded `runtimeContextManagerIsUsedForSpawnAndSend` adaptation with named send/task/cancel routing, actual retained history, strict name/task isolation and promotion/cancellation. Both reviewers approved the [exact identities](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#named-session-tools), and full verification passes. Per-call manager selection, restoration and Java repository adoption remain outside this whole method.
- [ ] Assess remaining key/restore requirements separately. Java deterministic-key methods (`sameInputs_produceSameHash`, `hashIs12HexChars`, parent/agent/label differences and null fallbacks) are not equivalent to process-local host names and random directory IDs; no whole-method completion is claimed.
- [ ] Define dynamic labels, durable reuse keys/restoration and post-promotion event delivery separately; terminal outcomes use the completion inbox.

### Result-bearing event streams

- [x] Three [AgentRunUpdateSpec](../../modules/core/src/test/scala/com/weisgnosis/agents/core/agent/AgentRunUpdateSpec.scala) groups verify one complete ordinary or structured result after events and scoped cleanup, preserved Interrupted state/recovery, exact mixed terminal causes, and joined early/external cancellation. Both independent reviewers approved the [exact identities](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#result-bearing-streams), and full verification passed. This is a Scala execution-envelope extension, not a new AgentEvent wire variant or an upstream whole-method completion.
- [x] [ToolInvocationSpec](../../modules/core/src/test/scala/com/weisgnosis/agents/core/tool/ToolInvocationSpec.scala) verifies the explicit scoped event capability, approved occurrence identity, default/denied dispatch and joined cancellation. Both reviewers approved the [exact identities](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#invocation-event-capability); full verification passed. This is the Scala capability boundary for the upstream invocation emitter, not completion of child-forwarding methods.
- [x] [ReActAgentForwardingSpec](../../modules/core/src/test/scala/com/weisgnosis/agents/core/agent/ReActAgentForwardingSpec.scala) verifies direct core forwarding with complete results, ordered nested sources, full causes and joined cleanup. The unchanged end-last assertion exposed competing subscription consumers in `local-skill-verify.log`; [AgentEventForwarderSpec](../../modules/core/src/test/scala/com/weisgnosis/agents/core/agent/AgentEventForwarderSpec.scala) adds three grouped lifecycle regressions for the shared single-consumer correction, without claiming additional upstream method completion. Both reviewers approved the [exact identities](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#direct-event-forwarding), and full verification passed. Harness installation has the separate integration evidence below.
- [x] [SubagentForwardingSpec](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/subagent/SubagentForwardingSpec.scala) provides bounded adaptations of `HarnessAgentSubagentStreamEventsTest.streamEvents_childEventsForwardedWithSource`, `streamEvents_childAgentStartAndEndEmittedWithSource`, `streamEvents_eventOrdering_childBetweenParentStartAndEnd` and `streamEvents_parentEventsHaveNullSource`. Actual fixed/catalog nested execution retains parent source=None and ordered child bookends, with runtime task UUIDs replacing declaration-name paths. Both reviewers approved the [exact identities](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#verification-and-acceptance), and full verification passed.
- [x] Additional Scala groups verify same-execution completion after promotion and distinct fixed/directory session-send leases, with retained cleanup causes during cancellation/removal.
- [ ] Complete the applicability mapping for `streamEvents_childEventsForwardedWithSource_underAsyncToolMiddleware`: explicit scoped capabilities replace Reactor-context propagation, but that middleware is not implemented. Remote forwarding, durable replay and post-promotion event delivery remain separate.

## Incremental local tool output

- [x] Both independent reviewers approved the [typed stream contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#incremental-execution) at its six exact identities, and full tool-stream-verify.log verification passed. The unchanged external-cancellation regression reproduced a new-draft consumer defect; the scoped channel-pull correction preserves the complete cause.
- [x] Bounded `ToolEmitterIntegrationTest.testToolEmitterWithHook` adaptation: provisional content reaches existing runtime events with call identity while an independently typed final value becomes model history only after EOF and cleanup. Immutable scoped streams replace mutable emitter injection and Hook APIs.
- [x] Bounded `ToolEmitterIntegrationTest.testToolWithoutEmitter` adaptation: the default stream executes the legacy contextual method once and retains ordinary final publication without provisional output.
- [x] Bounded `ToolEmitterIntegrationTest.testMultipleToolsWithEmitters` adaptation: actual parallel calls retain their own deltas and final values; repeated IDs do not share final-delta suppression. The Scala runtime test uses two streaming tools plus an ordinary sibling rather than sequential Java callback collection.
- [x] `ReActAgentToolkitConcurrencyTest.concurrentCallsDoNotCrossTalkChunkCallbacks` (pinned core/tool test, lines 308-347): a bounded adaptation shares one Tool/Toolkit between two independently owned Scala agents, holds both streams concurrently and checks exact isolated deltas, final observations, histories and distinct request/reply contexts. Java uses concurrent sessions on one agent; that API is not claimed. Both reviewers approved the [current fixture](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#shared-toolkit-destination-isolation), and full verification passed.
- [ ] Public callback/Hook fanout and callback-failure isolation in `testReActAgentPreservesUserChunkCallback`, `testInternalChunkCallbackFailureDoesNotBlockUserCallback`, `testUserChunkCallbackFailureDoesNotInterruptToolExecution` and `testInternalChunkCallbackFailureDoesNotInterruptToolExecution` are outside the typed-stream API; whole emitter-suite parity is not claimed.

## MCP protocol and connection boundaries

- [x] Verify direct host replacement after real SDK session expiry and real Netty same-send POST-body truncation recovery in the two fixtures below. Deliberate SDK differences are no automatic cursorless reconnect, no unowned subscription, no in-place session mutation and no POST replay; only captured-session 404 produces typed expiry, not SDK 400 handling. Host replacement remains explicit and serialized. These differences do not close whole upstream transport/recovery parity. Pinned SDK 0.17.2 source evidence is HttpClientStreamableHttpTransport.java lines 166/263-264/474 (cursorless startup/subscription), 215-220 (session replacement), 349-357/567-579 (400 expiry), and LifecycleInitializer.java 252-261 (independent reinitialization subscription). These are source comparisons, not matched Scala runtime tests.
- [x] Both reviewers approved typed captured-session expiry and explicit host-driven fresh registration; full verification passed. Controlled HTTP status/cause assertions and one actual McpClient/manager controlled-peer group establish the bounded contract, with real expired-server manager interoperability covered by the separate SDK fixture below. This is a selected-protocol/lifecycle adaptation, not whole upstream recovery-method parity: POST/continuation/listener 404 must differ from stateless HTTP rejection and DELETE AlreadyExpired; manager removal/registration does not replay failed calls or retarget old bindings. Automatic/durable recovery remains separate.
- [x] Eighteen [McpStreamableHttpTransportSpec](../../modules/mcp/src/test/scala/com/weisgnosis/agents/mcp/transport/McpStreamableHttpTransportSpec.scala) groups verify selected 2025-06-18 POST, explicit GET with bounded resumption, bounded clean-EOF/classified-loss continuation and explicit DELETE. Evidence includes real loopback initialization/control traffic, JSON listing/calls, exact UTF-8 cursor bytes, session isolation, strict responses and bounded ingress; readiness, admission and shared outcomes remain owned through cleanup. Actual interruption tests preserve full causes and distinguish leader/follower cancellation. DELETE covers authenticated status outcomes after client closure, no-session behavior and owner sealing. The [transport acceptance](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/transport/README.md#verification-and-acceptance) records exact hashes, independent approvals and reproduced terminal-cause, initialization, acquisition and session-drift regressions. Two groups verify direct typed closure recovery, committed/reset cursors, shared budgets and nonretryable full causes through held cleanup. Two listener groups verify cursor-based restarts, compatibility, reply rejection and joined cleanup/cancellation. Retry evidence in this suite uses controlled drivers; its opted-in loopback proves single-GET routing only. The separate Netty integration below verifies one actual POST-body recovery. Existing policies exclude generic I/O body failures; separate explicit body-I/O policies are covered below. These are specification-derived tests: the pinned Java HTTP builder delegates to SDK 0.17.2 and its test asserts non-null construction. No whole Java transport method or full conformance is closed.
- [x] [McpJavaSdkInteropSpec](../../modules/mcp/src/test/scala/com/weisgnosis/agents/mcp/transport/McpJavaSdkInteropSpec.scala) supplies three independent HTTP groups against unchanged SDK 0.17.2 on Tomcat 11.0.18: initialization, listing, tool success/error versus RPC error, independent DELETE of the live session, exact typed expiry after one POST/404 without replay, direct host replacement with fresh initialization/discovery, old binding isolation and final client closure/DELETE plus fixture cleanup. A second group verifies actual manager remove/register ordering under held cleanup, fresh discovery/selection and a distinct session, plus stale-snapshot zero-HTTP behavior. A third group verifies two real GET listener rounds: SDK keepalive and list-change delivery, joined local cancellation, a successful intervening POST and explicit same-session cursorless re-listening. FIFO draining excludes prior-round observation reuse; exact GET records preserve session and omit Last-Event-ID. All groups use the unchanged SDK; no whole upstream wrapper method is closed. Remote-work cancellation, cursor replay, suppressed SDK shutdown failures and failed-startup schedules remain excluded. Both reviewers approved the current fixture and full sdk-listener-verify.log verification passed.
- [x] Both reviewers approved opt-in exact-class, no-cause IOException recovery from raw SSE bodies only, and full verification passed. Two controlled-driver groups plus extended exclusions cover budget/cursor/session preservation, acquisition rejection and joined complete cleanup causes. This specification-derived adaptation keeps original requests/sessions/cursors and bounded GET budgets; no POST replay or acquisition/JSON/ack recovery is claimed. The separate real Netty fixture below supplements these controlled-driver cases without replacing their exclusion and client-cleanup evidence.

- [x] [McpHttpRecoveryIntegrationSpec](../../modules/mcp/src/test/scala/com/weisgnosis/agents/mcp/transport/McpHttpRecoveryIntegrationSpec.scala) supplies one real zio-http/Netty check: a chunked-body probe verifies exact cause-free IOException classification; a POST SSE notification commits a cursor before truncation, followed by one GET with the original session, authentication, version and reply ID. Recorded POSTs exclude replay. Its cleanup marker belongs to the server response; controlled fixtures separately prove client scope cleanup and full-cause exclusions. This covers same-send raw-transport recovery, not listener reconnection or all failure schedules. Both independent reviewers approved the exact [interop identities](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/transport/README.md#verification-and-acceptance), and full verification passed. Production behavior is unchanged.

The HTTP prerequisite is covered by five [McpHttpFramingSpec](../../modules/mcp/src/test/scala/com/weisgnosis/agents/mcp/transport/McpHttpFramingSpec.scala) groups derived from SSE line/event rules and local strict-UTF-8, byte-budget and ZIO lifecycle contracts. JSON is one checked envelope at EOF. `sseEvents` emits completed-block snapshots including absent data and response-local IDs; cases cover literal field names, reset/NUL/Unicode/inheritance, unfinished EOF, budgets and demand. Existing `sse` remains the message-only projection. This additive feature has no defective-implementation RED claim. These tests exercise no HTTP networking and close no upstream HTTP wrapper or builder method.

- [x] `path_review` and `current_docs` approved the exact HTTP framing source/spec identities in the [transport acceptance](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/transport/README.md#verification-and-acceptance); full verification passed with zero compiler warnings or failures; current totals are in the tracker.

AgentScope Java delegates JSON-RPC encoding to MCP SDK 0.17.2 at the pinned upstream revision. Its async/sync wrapper and builder tests cover SDK delegation, not the raw wire boundary. The [protocol suite](../../modules/mcp/src/test/scala/com/weisgnosis/agents/mcp/protocol/README.md) adds specification-derived coverage; it does not close those upstream wrapper methods.

- [x] Preserve string versus integer correlation, notification omission, object results and absent versus explicit-null error data.
- [x] Reject ambiguous message variants, invalid optional fields, unknown envelope/error fields, duplicate keys and trailing documents; validate constructed outbound ASTs.
- [x] Reproduce unbounded integer conversion and incompatible serialization with compact `1e100` fixtures before fixing them. `mcp-integer-bound-red.log` records five failed rejection assertions against the unchanged implementation. Boundary tests cover the shared parser's signed 256-bit range without allocating pathological integers.
- [x] Four grouped initialization tests cover literal 2025-06-18 request/notification payloads, absent/empty tools capability and extension preservation, malformed known fields/duplicates and unsupported versions. Other capability schemas remain uninterpreted data. These pure helpers do not close upstream initialize methods, which also manage connections and lifecycle.
- [x] Four grouped tool-payload tests cover literal list/call requests, ordered discovery with retained schema/annotation/metadata extensions, malformed known fields and recursive duplicate rejection. Duplicate tool names are retained rather than registered or silently replaced. JSON Schema evaluation, permission authority and network pagination are excluded.
- [x] Four grouped call-result tests cover all five content variants and embedded text/blob overlap, typed tool outcomes and independent metadata levels, malformed fields and compact numeric bounds, and recursive duplicates. URI/base64/timestamp format interpretation and JSON Schema evaluation are excluded; malformed supplied body members are rejected even when another union branch could accept the payload.
- [ ] Complete upstream client-wrapper method mappings beyond local initialized behavior. `McpSyncClientWrapperTest.testListTools_Success`, `testCallTool_Success` and `testCallTool_ReturnsError` have corresponding one-page list/call/result behavior in McpClient tests, but whole SDK delegation, builders and broader transport compatibility are not closed.
- [x] Five request-registry groups cover atomic abandonment observers and exact callback-cause preservation, out-of-order correlation and remote error data, capacity and scoped release, actual waiting-fiber cancellation versus completed replies, and owner/terminal closure. Live failed request scopes preserve compound domain causes and project foreign typed failures without losing defects or interruption. This ZIO ownership foundation does not close upstream connection methods or establish transport cleanup.

- [x] Eight [McpConnectionSpec](../../modules/mcp/src/test/scala/com/weisgnosis/agents/mcp/client/McpConnectionSpec.scala) groups pass against an in-memory borrowed port: checked out-of-order RPC, isolated send failures, bounded operations and server-control replies, directional notification handling, caller/terminal cleanup, and shared close with cleanup-defect replay. Both reviewers approved the final bounded scope and full verification passed; exact identities and evidence are in the [client acceptance](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/client/README.md#verification-and-acceptance). These tests do not implement a concrete transport or close upstream initialization/discovery methods.
- [x] Optional outbound cancellation has specification-derived coverage under the selected 2025-06-18 contract: the new connection group verifies initialize/unissued/replied exclusions, bounded dropping and independently owned writer cleanup; the expanded HTTP group verifies notification ID/session header, exact cleanup defect and pre-header suppression. Both reviewers approved the [exact client/transport identities](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/client/README.md#outbound-cancellation-contract), and full verification passes. This adds no whole upstream cancellation-method or external-server interoperability claim.
- [x] `mcp-terminal-dispatch-red.log` records duplicated terminal defects and a post-terminal send count of 1 instead of 0 against unchanged connection blob `1217d4467307297381bed0e01804ca898a85c8d5`. The corrected focused suite passes. Closed-parent startup is fixed and contract-covered, but that scheduling hazard was not reproduced in the red run.
- [x] Four [McpStdioFramingSpec](../../modules/mcp/src/test/scala/com/weisgnosis/agents/mcp/transport/McpStdioFramingSpec.scala) groups cover fragmented UTF-8 and multiple LF/CRLF messages, exact byte budgets, malformed/unterminated frames, full upstream causes and cancellation cleanup, and checked outbound framing.
- [x] Three [McpStdioTransportSpec](../../modules/mcp/src/test/scala/com/weisgnosis/agents/mcp/transport/McpStdioTransportSpec.scala) groups exercise real direct child processes: concurrent complete frames, ordered initialized-client list/call exchange and scoped reaping, single-reader admission and reusable outbound validation, malformed stdout/EOF, blocked-read cancellation, harmless queued-write cancellation and terminal partial-write cancellation. These local tests do not close whole upstream SDK transport or wrapper methods.
- [x] Independent framing/stdio reviewers approved the final identities; full verification passed. The [transport contract](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/transport/README.md) records reviewers, hashes and evidence. Cleanup sequencing was hardened by inspection; no reproduced failure is claimed for that correction.
- [x] Six [McpClientSpec](../../modules/mcp/src/test/scala/com/weisgnosis/agents/mcp/client/McpClientSpec.scala) groups exercise exact ordered initialization, failed initialization cleanup and invalid deadlines, tools capability enforcement, remote protocol/tool failure distinction, and joined initialization/call deadlines or caller cancellation. The existing connection group checks builder rejection and ID substitution before send; the core error suite covers derived absent/null/object payload presence. Bounded traversal preserves ordered duplicates and opaque cursors, rejects excess limits and cycles before additional requests, and returns no partial catalogue after later-page failure or cancellation. `path_review` and `current_docs` approved the final scope, and full verification passed; exact identities are in the [client acceptance](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/client/README.md#verification-and-acceptance).
- [x] Accept three [McpToolSpec](../../modules/mcp/src/test/scala/com/weisgnosis/agents/mcp/tool/McpToolSpec.scala) groups after both final independent approvals and full verification: fixed routing/host authority with typed content, invalid arguments and RPC/tool-error distinctions, and joined cancellation with cleanup defects. This adapts fixed invocation from `McpTool.callAsync` and conversion from `McpContentConverter.convertCallToolResult`; it preserves error content, audio, resources and structured metadata that upstream reduces or omits. Remote readOnlyHint never becomes local authority. Exact identities belong to the [tool acceptance](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/tool/README.md#verification-and-acceptance).
- [x] Accept bounded manager registration, selection and removal adaptations after final independent approval from `path_review` and `current_docs` and full verification. Five grouped [McpClientManagerSpec](../../modules/mcp/src/test/scala/com/weisgnosis/agents/mcp/client/McpClientManagerSpec.scala) tests include concurrent all-or-nothing publication, retained removal capacity, externally initialized-client closure and the reproduced compound-cause correction. The [client contract](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/client/README.md#verification-and-acceptance) records exact identities and evidence. This closes no whole manager parity claim.
- [x] Provide bounded adaptations of `McpClientManager.registerMcpClient` and `shouldRegisterTool` through discovery and explicit host selection; local aliases express prefixes while preserving remote names. Relevant pinned references are `testShouldRegisterTool_BothListsSpecified_EnableToolsTakesPrecedence`, `McpNamespacedToolTest.registrationKeepsSameNamedToolsFromDifferentServers` and `callsOriginalNameWithNamespacedModelName`. The selector API intentionally does not reproduce the mutable Java overloads, enable-over-disable precedence or empty-enable-list semantics.
- [x] Three [McpToolMetadataSpec](../../modules/mcp/src/test/scala/com/weisgnosis/agents/mcp/tool/McpToolMetadataSpec.scala) groups adapt `McpTool.callAsync/buildMetaMap/extractMcpMeta`: effectful explicit host projection, restricted runtime occurrence identity, omission and cleanup. `path_review` and `current_docs` approved the current preset/binding identities with no findings; full `mcp-tool-presets-verify.log` verification passed. Exact identities belong to the [tool](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/tool/README.md#verification-and-acceptance) and [client](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/client/README.md#verification-and-acceptance) acceptance.
- [x] `McpToolMetaExtractionTest.carriesCallIdentityOutsideArgumentsWithoutMutatingUserMetadata` maps to actual registered ReAct calls: active call ID overwrites the compatibility key, host JSON stays unchanged, and model `_meta` remains an argument. `testMetaExtracted_FromRuntimeContext` is adapted to an explicit effectful resolver, not a type-indexed runtime bag. Restricted identity excludes arguments and event capabilities.
- [x] `testMetaExtracted_ContextIsNull_EmptyMetaSent`, `testMetaExtracted_ContextWithNoMcpMeta_EmptyMetaSent` and `testMetaExtracted_McpMetaIsEmpty_EmptyMetaSent` are represented by Detached plus an enabled empty object. Scala has no Java-null context path. `McpToolMetaPropagationTest.toolDisabledOmitsMetaEntirely`, `wrapperDisabledAtCallTimeOmitsMetaEvenWhenToolAllows`, `wrapperToggledAtRuntimeTakesEffectImmediately` and `propagatesUserMetaWithoutToolUseBlock` are adapted by fixed omission, live mode changes without re-registration, and detached host metadata. A suspended resolver establishes invocation-start snapshot semantics; late mode changes do not revoke it.
- [x] Intentional adaptations: `propagatesMetaByDefault` becomes opt-in binding Omit; `reEnablingToolRestoresPropagation` becomes a newly selected immutable binding policy. McpMeta defensive copying/mutability contracts use immutable JSON AST instead of arbitrary Java objects. Explicit client metadata bypasses the bound-tool restriction. Additive manager selection is one coherent ToolDef/policy value; custom managers have a typed unsupported fallback without acquisition, and a later selector failure publishes no partial set.
- [x] Adapt `removeMcpClient`, `getMcpClientNames` and `Toolkit.closeMcpClients` through joined owned cleanup, Ready-only names and immutable snapshots that borrow their original clients. `McpNamespacedToolTest.copiedToolkitDoesNotOwnConnectionsAndOwnerClosesOnce` is the ownership reference. Retained pending/removing capacity and atomic collision rejection are local contracts; there is no partial callback publication, silent same-name replacement or raw `getMcpClient` lookup API.
- [x] Two [McpToolPresetsSpec](../../modules/mcp/src/test/scala/com/weisgnosis/agents/mcp/tool/McpToolPresetsSpec.scala) groups and the existing metadata integration adapt `McpToolTest.testCallAsync_MergesPresetArguments`, `testCallAsync_PresetArgsOnly`, `testMergeArguments_BothEmpty`, `testMergeArguments_InputOverridesPreset` and `testMergeArguments_PresetOnly_NoInput`: checked immutable defaults, shallow supplied-input precedence, empty input and literal null/nested replacement. Validation precedes any overwrite, including branches that would disappear.
- [x] `testConvertMcpSchemaToParameters_WithExcludeParams`, `testConvertMcpSchemaToParameters_WithExcludeParams_EmptyExcludes`, `testConvertMcpSchemaToParameters_WithExcludeParams_NonExistentParams`, `testConvertMcpSchemaToParameters_WithAdditionalProperties`, `testConvertMcpSchemaToParameters_WithBothDefsAndDefinitions` and `testConvertMcpSchemaToParameters_DefsWithExcludeParams` map to checked root properties/required exclusion with retained field order, absence, definitions and extensions. Intentional strictness: require object root/type and valid properties/required shapes; no null-schema/type fallback or reconstruction. References and broader constraints remain untouched, not evaluated.
- [x] `McpClientManagerTest.testRegisterMcpClient_WithPresetParametersMapping` and `testRegisterMcpClient_WithPresetParametersKeySetExclusion` adapt to a checked selected binding consumed atomically. The actual ReAct test proves authorization sees model input, history contains approved rewritten input, and remote arguments add defaults while approved values win. Unlike upstream manager ToolExecutor's preset-wins stage, this API consistently uses input-wins defaults; presets are not hidden permission constraints. Both reviewers approved the current source/test identities and full verification passed; the tool/client acceptance records evidence.
- [x] Both independent reviewers approved the core fixed-argument foundation and full verification passed: fixed-wins raw preparation before authorization, approved consistency, wrapper/filter preservation and cancellation-cause preservation. Exact current scope is in the [tool contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#fixed-raw-arguments).
- [x] Both independent reviewers approved bounded MCP installation of the outer `ToolExecutor` fixed-wins contract, and full verification passed. `ToolExecutorTest.shouldApplyPresetParametersAfterExplicitInput` maps to fixed tenant overriding model input before authorization, retaining permitted nonfixed approved edits and overriding an overlapping default. `shouldUseOnlyPresetParametersWhenInputsAbsent` maps to explicit empty raw object dispatch with fixed-only arguments. `shouldExecuteWhenRegisteredMetadataIsAbsent` is an intentional structural difference: policy is attached to the Tool value, with an empty default, rather than a separate registration metadata lookup; no Java subclass parity is claimed. Original defaults-only ReAct coverage remains unchanged. Malformed checked fixed selection rolls back the entire registration; changed fixed approval yields Denied without remote invocation or metadata resolution. Exact scope belongs to the [MCP fixed contract](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/tool/README.md#fixed-arguments).
- [x] `McpClientManagerTest.testRegisterMcpClient_PreservesOutputSchemaInRegisteredTool` adapts to explicit host selection of ToolDef.outputSchema. Actual registered ReAct calls retain the selected AST through preset projection and both model requests; manager assertions cover deliberate discard and override. ToolDefSpec covers legacy/optional codec semantics, and ChatCompletionsRequestSpec proves omission from the provider request. This follows upstream AgentTool#getOutputSchema as descriptive metadata; no result validation or automatic schema adoption is implemented. Both reviewers approved the [core and current integration identities](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#descriptive-output-schemas), and full verification passed.
- [x] Both reviewers approved and full verification passed for the host-selected group foundation: `ToolGroupManagerTest.testCreateToolGroupWithAllParameters`, `testCreateDuplicateToolGroup`, `testUpdateToolGroupsActivate`, `testUpdateToolGroupsDeactivate`, and `testUpdatePreventsDuplicatesInActiveGroups` map to checked immutable declarations/atomic activation. `testSetActiveGroups`, `testSetActiveGroupsDeactivatesOthers`, `testReplaceMetaActiveGroups` and `testReplaceMetaActiveGroupsPreservesExternal` map to full versus Meta-only replacement. `testIsActiveToolUngrouped`, `testIsActiveToolInactive`, `testAddToolToGroup`, `testRemoveToolFromGroup`, `testRemoveToolGroups` and `testRemoveNonexistentToolGroup` map to derived OR membership, exact Toolkit selection and last-membership reporting. The actual ReAct fixture verifies both hidden definitions and blocked hidden dispatch. Unknown activation and membership groups intentionally fail instead of permissive/partial Java behavior; immutable values replace mutable-copy/builder/null semantics. [Exact group acceptance](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#host-selected-tool-groups) does not claim full manager parity.
- [x] Both reviewers approved pure group notes and their runtime integration, and full verification passed. `ToolGroupManagerTest.testGetActivatedNotesEmpty` and `testGetActivatedNotesWithGroups` map to sorted host-facing activeNotes across both scopes, with explicit empty handling. `testGetNotesOnlyShowsMetaGroups` maps to active/inactive metaNotes that exclude External names and descriptions. One new table-driven group covers empty, mixed-scope and blank-description values; existing runtime assertions verify request descriptions share the selected tool snapshot and reset output notes come from its atomic update result. MetaToolFactory description replacement, reset naming and description behavior are bounded source/test references; exact Java prose and missing-context mutation are not claimed. Skill binding reminders have separate acceptance below. Current identities and verification are in the [group foundation](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#host-selected-tool-groups) and [runtime contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#run-owned-tool-group-activation).
- [x] Both reviewers approved the bounded preloaded catalogue and runtime skill control, and full verification passed. `SkillRuntimeIntegrationTest.testMultipleSkillsActivationSequence` maps to union of explicit Meta targets across successful loads with shared defaults, next-request visibility and fresh-run reset. `testCompleteSkillActivationFlow` and `testSkillWithActualToolsIntegration` are only partial activation/execution references: generated-group registration, prompt-hook injection and repeated counter execution remain outside this scope; whole-method parity is not established. Three catalogue groups and three added runtime groups cover checked resources, codec reentry, explicit target validation, errors/denials, hidden collisions, fallback and content-only Auto routing. The [catalogue](../../modules/core/src/main/scala/com/weisgnosis/agents/core/skill/README.md) and [runtime](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#preloaded-skills) own exact acceptance.
- [x] Both reviewers approved actual harness Plan admission of the runtime skill loader, host/policy denial and confirmation rechecks. See the [Plan mapping](#plan-mode-inventory) and [current identities](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md#current-admission-identities).
- [x] `SkillToolGroupTest.testDescriptionIncludesSkillReminder` and `testCopyDoesNotDuplicateReminder` have bounded adaptations through pure catalogue bindings and existing actual runtime scenarios: deterministic grouped/quoted reminders, unchanged raw declarations, stable reconstruction, single text across requests/fallback, and successful explicit reset with current notes. Java subclass/copy APIs and exact prose are not reproduced. Both reviewers approved the [catalogue](../../modules/core/src/main/scala/com/weisgnosis/agents/core/skill/README.md) and [runtime](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#preloaded-skills) identities; full verification passed.
- [x] Both reviewers approved the bounded inline SKILL.md profile and full verification passed. Three [parser table groups](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/skill/README.md) adapt required name/description/content validation, including `SkillUtilTest.testCreateFromRejectsNumericRequiredMetadata`, and selected string/body/framing behaviors from `MarkdownSkillParserTest.testParseWithValidFrontmatter`, `testParseQuotedValues`, `testParseWithDifferentLineEndings`, `testParseBlockStyleModifiers` and `testFrontmatterSizeLimit`. These are partial references: the accepted required-field baseline rejects malformed input without fallback, accepts LF/CRLF only, uses explicit UTF-16 bounds, and preserves body text. No whole generic parser suite, source-derived IDs, generation or filesystem behavior is claimed; exact scope and identities belong to the [parser contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/skill/README.md).
- [x] Both reviewers approved [LocalSkill.load](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/skill/README.md#selected-local-loading), and full verification passed. Three grouped tests partially adapt `SkillFileSystemHelperTest.testLoadSkill_ByMetadataName`, `testValidateAndResolvePath_PathTraversal` and `shouldLoadNormalResourceFiles`: the declared name survives a differently named directory, traversal fails and selected normal text resources load. Unlike `SkillFileSystemHelper.loadSkillFromDirectory`, Scala requires explicit resource selection and fails the entire load on a selected missing, unreadable, oversized or invalid-UTF-8 file. Recursive discovery, binary conversion, skip-unreadable behavior, source-derived IDs, caches, writes and platform symlink/junction validation are not claimed.
- [x] Both reviewers approved bounded metadata preservation and full verification passed. Bounded mappings: `MarkdownSkillParserTest.testParseAndIgnoreComplexMetadata` (despite its name, it asserts retained lists, nested maps and allowed-tools); the parsing/order portion of `testPreserveMetadataOrder`; required-string checks in `testRejectNonStringRequiredMetadataValues` and `SkillUtilTest.testCreateFromRejectsNumericRequiredMetadata`; and the extra-field portion of `SkillUtilTest.testCreateFromEdgeCases`. Required name/description remain typed fields outside the extras object. Ordered JSON trees and inert authority-looking keys do not imply Java metadata lookup/source APIs, Markdown generation, full YAML or whole-method parity.
- [x] Both reviewers approved bounded [LocalSkillRepository discovery](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/skill/README.md#local-repository-discovery) and full verification passed. Three grouped tests provide bounded method adaptations: SkillFileSystemHelperTest.testGetAllSkills, testGetAllSkillNames_FromMetadata and testGetAllSkillNames_IgnoreInvalidDirs cover enumeration, declared identity and absent-document skipping; FileSystemSkillRepositoryTest.testConstructor_BaseDirIsFile and testConstructor_EmptyBaseDir cover root outcomes, adapted to cold typed effects. Missing-root failure follows source inspection; the new suite uses missing paths only to prove pre-I/O trust/limit rejection, so testConstructor_NonExistentBaseDir is not a matched runtime assertion. Scala counts all entries, sorts filename processing, rejects unsafe candidates and duplicate IDs, and fails the whole batch on invalid selected input rather than logging/skipping it. Source IDs, lazy resources/originDir, caches, writes and whole repository parity are excluded.
- [x] Both independent reviewers approved bounded eager TextTree resources; full verification passed. Two new LocalSkillRepositorySpec groups adapt the resource-only assertions of FileSystemSkillRepositoryLazyTest.eagerLoadsResourcesAndOriginDir (lines 67-80), SkillFileSystemHelperTest.shouldLoadNormalResourceFiles (379-391), and nested/text readback in FileSystemSkillRepositoryTest.testSave_WithResources (253-268). Origin stamping and saving are not implemented. Unlike Java's default eager mode, discovery is explicit and bounded; invalid UTF-8 and inaccessible/unsafe entries fail the whole snapshot rather than converting binary data or silently skipping failures. Benign dot entries are retained, an intentional difference from shouldFilterDotFilesAndDirectories; OS-hidden filtering is not implemented. No binary, lazy disk fallback, source-ID/cache/write or whole-repository parity is claimed.
- [x] Both independent reviewers approved exact deferred-resource capabilities and full verification passed. SkillLibrarySpec verifies exact cold bindings, monotone selection and full scoped cleanup; real runtime, selected local-reader and nested-child fixtures prove authorized fresh reads before activation. This is a bounded capability adaptation, not FileSystemSkillRepositoryLazyTest.lazySkipsResourcesAndKeepsOriginDir: no originDir is stamped and repository discovery does not infer readers. Automatic disk fallback, source IDs, mtime/size caching and repository writes remain excluded.
- [x] Adapt SkillHookTest.testInjectPromptForRegisteredSkill, testInjectPromptForRegisteredSkills, testHandleEmptySkillPromptGracefully, testInjectSkillPromptAtFirst and testMergeSkillPromptIntoExistingSystemMessage through pure selected catalogue rendering and actual request-only runtime projection. SkillCatalogSpec adds one escaping/selection/exclusion group; ReActAgentToolGroupsSpec adds one first-system/prepend/empty/history-isolation table and extends actual repeated-request/native-fallback assertions. Both reviewers approved the [four current identities](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#request-only-skill-catalogue); full verification passed. The pinned tests assert a transient systemMessage plus unchanged input order; Scala merges/prepends in the model-request projection without persisting it. testHookPriority's numeric constant is intentionally replaced by explicit pipeline ordering, not a general hook framework. Exact Java prose and full hook-suite parity are excluded.
- [ ] Implement other repository variants and durable activation separately.
- [x] Both reviewers approved bounded MCP group installation and full verification passed. `McpClientManagerTest.testRegisterMcpClient_PreservesOutputSchemaInRegisteredTool` asserts callback group forwarding, not manager-owned group lifecycle. `ToolkitTest.testAuthorizedToolCallSucceeds`, `testUnauthorizedToolCallShouldBeRejected` and `testToolGroupDeactivationPreventsExecution` provide grouped-dispatch references. Our actual manager/ReAct fixture checks Meta activation, External isolation and fixed-policy preservation. Before-acquisition group validation follows upstream source, not an explicit pinned test. Empty host declarations, checked aliases, atomic Ready-only toolkit/membership snapshots and removal preserving declarations are intentional stronger adaptations; no mutable callback registry or live agent refresh is claimed. Five manager groups reuse existing ownership fixtures; [current acceptance](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/client/README.md#grouped-installation) records both exact identities and verification.
- [x] Both reviewers approved run-owned activation and full verification passed: `MetaToolFactoryTest.testMetaToolCallAsyncActivateGroups`, `testMetaToolCallAsyncWithToolsInGroup`, `testMetaToolCallAsyncEmptyListDeactivatesAll` and `testMetaToolReplacesActiveGroups` map to next-request replacement with a fixed admitted batch. `testMetaToolCallAsyncMissingParameter`, `testMetaToolCallAsyncInvalidGroupName` and `testMetaToolCallAsyncPartialInvalidGroups` map to strict input and atomic rejection. `testMetaToolIgnoresExternalGroups`, `testMetaToolRejectsExternalGroupInInput`, `testMetaToolParametersOnlyListsMetaGroups` and `testMetaToolReplacementPreservesExternalDuringEmptyActivation` map to Meta-only schema, snapshot-consistent descriptions and preserved External activation. The activation groups additionally establish ordinary authorization, fresh invocation/cancellation isolation, fallback state preservation and runtime-control collisions. Immutable configuration plus invocation-local ownership replaces shared mutable/context lookup paths; Java missing-context mutation, toolkit copying, skill notes and exact prose are not claimed. [Current source/spec acceptance](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#run-owned-tool-group-activation) owns evidence.
- [ ] Ambient context bags and McpMeta merge helpers remain separate. String-keyed metadata override maps (`testRegisterMcpClient_PerToolOverrideWinsOverRegistrationDefault`, `testRegisterMcpClient_UnknownPerToolOverrideNameFailsRegistration`, `testRegisterMcpClient_OverrideForFilteredOutToolFailsRegistration`) are replaced by selected binding values, not claimed as those map-validation methods. Remote readOnlyHint remains data rather than local authority.
- [ ] Extend interoperability beyond the accepted direct SDK replacement and Netty POST-body case where required, including cursor-based listener replay and remote-work cancellation. Caching and schema evaluation remain separate; automatic cursorless reconnect, unowned subscriptions, in-place session mutation and POST replay are intentional exclusions.

The [2026-07-28 specification](https://modelcontextprotocol.io/specification/2026-07-28/basic) defines the common envelope. Strict unknown-field rejection and bounded integers are explicit local constraints. Method-level result tags, capabilities, request correlation state and version compatibility remain outside this codec's acceptance.

- [x] Verify runtime-owned lineage with six capability cases, two actual nested ReAct cases and one error-codec case. Depth-four rejection occurs before UUID/callback effects; cancellation reconciles every running level. These test-fixture spawn calls do not close production AgentSpawnTool mappings.

- [x] Both reviewers approved the MCP connection reservation/terminal correction; full signature-mcp-verify.log verification passed. The unchanged HTTP expiry assertion exposed an additional registry-closed failure in tool-signature-verify.log; exact evidence and current identities are in the [client acceptance](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/client/README.md#verification-and-acceptance). This is a Scala ownership regression, not another mapped upstream method.

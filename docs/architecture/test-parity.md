# Upstream scenario coverage and remaining parity work

This document is the upstream behavior reference, not a second delivery backlog. The [completion checklist](tracker.md) owns current remaining library outcomes. Legacy checkboxes below preserve mappings and evidence; reconcile substantive promised gaps with that checklist instead of treating declaration totals as a delivery target.

Cover every applicable promised upstream behavior with executable evidence, allowing several cases in a concise table-driven test. Current suites cover selected scenarios and ZIO-specific regressions; exhaustive parity has not been demonstrated. Passing totals and suite names are not proof of complete parity.

The reference is the clean local `agentscope-java` checkout at commit `f98a685fdc4589f81e626e1686ac2c63d14a2304`, verified on 2026-09-29. Upstream Java implementation details are translated into Scala domain behavior, not copied as imperative APIs. Updating this pin requires a new inventory and review of changed scenarios.

## Toolkit binding identity

- [x] Reproduce the raw Toolkit key/name mismatch against unchanged code: approval of allowed dispatched forbidden in toolkit-identity-red.log (3 passes/1 failure). This is a Scala registry defect, not an additional upstream method mapping.
- [x] Obtain final FP/ZIO/invariant approval and full verification for the [private registry and 55-file source/spec migration](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#checked-registry-identity). One regression group adds malformed rejection and permitted canonical execution; existing merge cases distinguish explicit replacement from collision rejection.

## Cross-module host integration

- [x] Independently review and verify the [HostAssembly and offline smoke admission](../../modules/examples/README.md#verification-and-acceptance) examples. Two composed lifecycle/tool groups and one offline admission group exercise Scala host integration; they do not close additional upstream Java test methods.
- [x] Independently review and verify the [lifecycle logging example](../../modules/examples/README.md#lifecycle-logging): two bounded logging/result/cancellation groups; no additional upstream methods are closed.
- [x] Independently review and verify the [external packaged API consumer](../../verification/artifact-consumer/README.md); its selected public-extension checks establish current artifact consumption, not additional upstream method parity or backward binary compatibility.
- [x] Validate one authorized selected live Chat Completions endpoint through fully consumed SSE, one successful tool invocation and authoritative Completed reply; the [live evidence](../../modules/examples/README.md#live-endpoint-verification) does not establish broader provider parity.

## Typed completed tool observations

The [exact-output regression scope](../../modules/core/src/main/scala/com/weisgnosis/agents/core/json/README.md#exact-domain-and-tool-output) preserves nested numeric provenance, tool observations and opt-in checkpoint/event JSON, including direct metadata decoding. These reproduce dependency-boundary defects; they do not close additional upstream methods or promise all custom codecs are repaired.

The current core prerequisite exposes Tool.encodeResult, payload/state ToolExecutionResult and runtime-protected result publication. Existing String tools retain Success by default; only Success/Error may be projected by tools. ToolExecutionSpec covers payload/codecs, unsupported states and cancellation cleanup; the agent integration checks ordered typed history/events and protected metadata. These are local extension contracts, not completion of the upstream MCP adapter or tool matrix. ChatCompletions defaults to rejecting non-text tool output; the bounded explicit [tool-image projection](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#tool-image-result-projection) has final scoped acceptance.

The unchanged Toolkit blob `79be10c316f676da5d96977419e5be4caf02886f` lost a cleanup defect when a tool joined its actually interrupted owned child: `tool-inner-cancellation-red.log` records the failed defect-preservation assertion. The external-cancellation baseline passed. Re-failing the compound cause preserves interruption and defects while mapping typed Throwable leaves to defects for the UIO boundary.

- [x] Both independent reviewers approved the final scope and full repository verification passed; exact identities belong to the [tool acceptance](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#current-review-acceptance).
- [x] Both independent reviewers approved manual MCP Toolkit binding with explicit host authority and preserved protocol content; full verification passed and provider multimodal projection remains separate.

## Progress checklist

- [x] Close the reproduced PathSafety command-classification gaps with independently approved [advisory lexical screening regressions](../../modules/core/src/test/scala/com/weisgnosis/agents/core/tool/README.md#command-screening-regressions). This is additional local contract coverage, not a new upstream mapping or shell-authorization guarantee.
- [x] Accept the reproduced [shell startup-default omissions](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#shell-startup-defaults) with final independent source/documentation approval and passing focused/full/artifact verification. Existing filename tables pair explicit Bash/Zsh startup names with permitted near-misses; this is additional local INV-10 coverage, not an upstream method mapping.

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
- [x] Reproduce and correct incomplete core SSE frame dispatch at EOF, including truncated DONE accepted as completion. The [scoped contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#sse-frame-completion) records exact independent review identities and RED/GREEN evidence. This is a protocol regression derived from the EOF contract, not a new upstream whole-method mapping. OpenAIStreamTerminationTest.streamCompletesOnDoneSentinelWithoutWaitingForConnectionClose (line 91) maps to the existing transport open-tail/cleanup test; it does not test incomplete EOF framing.
- [ ] Complete remaining provider scenarios and exhaustive upstream transport mapping.
- [ ] Complete ReAct and interruption scenario mappings, including lifecycle hooks not yet implemented.
- [x] Classify the five pinned thinking-only empty-response methods under the bounded test contract below; exact Java assertion equivalence and broader lifecycle parity are not implied.
- [x] Cover empty-input structured runs, signed thinking preservation, full reply usage, and immediate repeated calls.
- [x] Close all eighteen structured-output method mappings through verified contracts or explicit typed adaptations; broader middleware/hook behavior remains separate work.
- [x] Classify all 24 methods in the pinned compaction/eviction suites: seven ConversationCompactorTest, ten CompactionMiddlewareTest and seven ToolResultEvictionMiddlewareTest methods, through the bounded mappings and explicit differences below.
- [x] Document injected-store and trusted local/absolute storage applicability in the [compactor](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md#failure-policy-and-session-ownership) and [eviction contracts](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/eviction/README.md).
- [ ] Validate platform-specific symlink/junction behavior, hostile-workspace isolation and crash durability separately; finite method classification does not establish these guarantees.
- [ ] Complete remaining broader middleware/memory-service applicability; the three named suites do not exhaust those domains.
- [x] review_fp and review_invariants approved this four-document umbrella reconciliation against 75265e0; no source/test acceptance is widened.
- [ ] Complete permission mappings and durable host integration tests.
- [ ] Close every applicable gap before declaring exhaustive 1:1 parity.

Existing suites cover selected scenarios only. No unchecked mapping task is satisfied merely by a passing test count.

## Deliberate behavioral differences

- Concurrent calls to the same ReActAgent are serialized. Upstream concurrent-call rejection tests cannot be claimed as ported unchanged.
- Immutable Scala models and typed errors replace Java builder/null-specific contracts. Each inapplicable upstream case still needs a recorded rationale in the exhaustive mapping.
- Interrupted unfinished local tools receive Error observations. Provider-hosted inline results do not create separate local tool messages.
- LlmClient has scripted clients and ChatCompletionsClient for scoped HTTP completion with validated incremental correlated events. Provider extensions and exhaustive mapping remain pending.

- [x] Both reviewers approved the bounded OpenAIMessageConverterTest.testToolMessageWithMultimodalResult (887) source adaptation; full RC1/archive/fresh-consumer checks pass. Final combined documentation approval passed in the linked tool-image scope. Explicit successful Text/Image tool projection preserves original role/ID/order; testToolMessageWithoutResultBlock (916) invented-ID fallback remains intentionally excluded.

## Thinking-only empty-response methods

Pinned ReActAgentEmptyResponseRetryTest methods map to the existing five-group Scala suite under the [exact reviewed test identity](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#thinking-only-retry-contract):

- [x] retriesThinkingOnlyFinalResponse (71): model output now depends on the last synthetic reminder; two iterations and exactly one correctly named/typed reminder are asserted.
- [x] loopIsBoundedByMaxIters (99): bounded intent is adapted with independent retry/iteration budgets, exact calls/reminders and max_iterations. Java's extra summarizing request is intentionally omitted, so its whole assertion sequence is not reproduced.
- [x] blankTextTriggersRetry (120): reasoning with blank visible text produces one reminder and a second turn.
- [x] toolCallResponsesAreNotRetried (135): reasoning plus a tool call enters acting without a synthetic reminder.
- [x] textResponsesAreNotRetried (151): visible text completes in one iteration without a reminder.
- [x] Full verification passed under the linked test-only contract; no production change or reproduced-defect claim is made.
- [x] Both reviewers approved final combined documentation in the linked test-only contract.

## Thinking and invocation observations

The following finite classifications describe thinking and bounded invocation observations against the pinned revision. The [current invocation manifest](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#invocation-identity-and-prepared-execution) owns exact source and combined documentation approval against db80dc0; full verification, archive admission and fresh standalone consumption pass.

- [x] ReActAgentThinkingCumulativeTest.testThinkingBlockCumulativeMode (65): ModelStreamPublisherSpec and ChatCompletionsTransformSpec assert all three exact prefixes through indexed publisher state and the public ReAct stream. ThinkingBlockDeltaEvent.accumulatedThought is an event-stream adaptation, not the upstream middleware API. Both source reviews approve the [six-file contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/event/README.md#incremental-and-accumulated-thinking); final combined documentation acceptance passed against 4cc1225 for six unchanged Scala identities and fourteen Markdown files, approved by review_fp and review_invariants.
- [x] ReActAgentThinkingCumulativeTest.testThinkingBlockIncrementalMode (113): the same existing groups assert all three original ordered fragments, independent interleaved indices, empty fragments and unchanged block ends. Codec/source-tagging and opaque-only exclusion remain explicit assertions; no extra mechanical groups were introduced.
- [x] Implement the bounded observation in ReActAgentRunIdVisibilityTest.plainCallMiddlewareSeesStableDistinctRunIds (99): typed InvocationContext is stable across applicable model/history/post-reasoning/tool/completion callbacks, Auto attempts and failover; separate admitted runs and original/restored resumes are fresh. InvocationContextSpec, existing contextual preparer/forwarding/result-stream groups and codec tables provide evidence. Same-agent serialization is an explicit adaptation of upstream concurrent sessions; replyId/requestId retain separate purposes.
- [x] Implement the bounded observation in ReActAgentRunIdVisibilityTest.preparedCallMiddlewareRunIdEqualsHandleRunId (137): effectful prepareRun adapts Java prepareCall with a preexposed typed context and lazy one-shot ordinary execution. PreparedInvocationSpec compares reserved identity to runtime model/completion identity and checks overlapping double execution, queued cancellation isolation and joined cleanup. The handle is consumed before queue admission; it is not a cold reusable stream.
- [x] review_fp and review_invariants approve the current fourteen-file source scope against db80dc0; focused integration verification passes 31 core and 19 harness groups.
- [x] Full verification passes 1,583 tests and four RC1 packages; candidate archive admission passes under the linked current manifest.
- [x] Fresh standalone artifact consumption passes; its unchanged probe does not independently cover the new prepared API.
- [x] review_fp and review_invariants approved the final combined invocation scope against db80dc0: fourteen unchanged Scala identities and fourteen Markdown files, as recorded in the linked invocation manifest.
- [ ] Classify and implement the remaining applicable AgentRun lifecycle, status, cancellation, stream-handle and additional preparation APIs; the ordinary prepared effect does not claim them.

The [tracker](tracker.md#immediate-next-work) retains broader lifecycle and host obligations. Invocation UUIDs provide correlation, not durable execution or confirmation authority. The draft's integration RED and narrow saved-request binding correction are recorded in the current manifest; previous baseline contracts are not relabeled defective.

## Interrupted tool-call methods

The three pinned ReActAgentInterruptToolCallTest methods have bounded mappings to existing verified suites. No source change or new test group is needed for this finite classification:

- [x] interruptAfterToolUseSynthesizesErrorResultAndKeepsContextConsistent (152): [ReActAgentInterruptSpec](../../modules/core/src/test/scala/com/weisgnosis/agents/core/agent/ReActAgentInterruptSpec.scala) checks cooperative/fiber interruption, matching Error observations and one recovery assistant. Scala interrupts actual owned execution; Java triggers middleware immediately after model output and before acting. This is semantic adaptation, not identical middleware scheduling.
- [x] resumedRunAfterInterruptRepliesNormally (203): [ReActAgentLifecycleSpec](../../modules/core/src/test/scala/com/weisgnosis/agents/core/agent/ReActAgentLifecycleSpec.scala) interrupts and then continues on the same agent; it also verifies completed sibling results and joined cleanup survive.
- [x] interruptPersistsOnceWithReconciledToolResult (228): [ReActAgentCompletionInterruptSpec](../../modules/core/src/test/scala/com/weisgnosis/agents/core/agent/ReActAgentCompletionInterruptSpec.scala) observes exactly one reconciled canonical completion after cleanup for both cooperative and fiber cancellation. TurnCompletionObserver provides one observation attempt; Java StateStore.saveIfVersionCalls == 1 is not an equivalent durable transaction/acknowledgement guarantee. Durable host persistence remains open.
- [x] review_fp and review_invariants approved these three classifications in the final sixteen-file scope against 63776a7; broader ReAct/hook applicability remains unfinished.

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

## Selected request-parameter mappings

- [x] OpenAIChatFormatterTest.testApplyOptions (138): existing Scala request/failover tables adapt temperature, token limit, topP, penalties and exact signed seed. testApplyOptionsWithNull (165) is represented by immutable defaults and optional values; Java's two-options fallback merge is intentionally not reproduced.
- [x] testApplyToolChoice (201), Auto (215), None (228), Required (722), testNoToolChoiceWithoutTools (736) and WithEmptyTools (747): checked choices and empty-tool cases have existing request-table coverage. testDefaultAutoForNullToolChoice (762) is an explicit difference: Scala None omits tool_choice; explicit Auto emits auto with tools.
- [x] testSeedWithinRange (674), AtMaxValue (687), ExceedingRange (701): existing signed-Long table covers exact boundary serialization; Java integer narrowing is intentionally excluded.
- [x] testApplyResponseFormat (551): existing object pass-through now explicitly checks json_object alongside native json_schema and omission. WithClass (571) reflection/class identity is excluded; compile-time codecs and supplied schemas replace it.
- [x] testApplyReasoningEffortFromField (404), WithDefault (416), FromDefaultOnly (431): bounded wire-value adaptation through typed Low/Medium/High and optional target configuration/projection. The [reasoning-effort contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#optional-reasoning-effort) records exact review and verification. Java's mutable field/default merge is not claimed.
- [x] Adapt optional per-tool strict generation from testApplyToolsWithStrict (348) and omission in WithNullStrict (368) through host-selected Strict/Relaxed metadata and unchanged schemas. Existing tables also cover explicit false, codecs, fixed wrappers, MCP bindings and exact target/native/synthetic tool membership. Java compatibility-formatter supportsStrict suppression is not reproduced; hosts select compatible endpoints. Exact identities and verification belong to the [strictness contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#provider-tool-schema-strictness).
- [x] Verify bounded checked thinking-budget adaptations for testApplyThinkingBudget (444), testApplyThinkingBudgetFromDefault (457), testApplyThinkingBudgetOverride (470) and testThinkingBudgetAbsent (484), through the [selected contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#optional-thinking-budget). Final source/full/preparation checks pass; fresh selected-API consumption passes; final combined documentation approval passed. Extra-body override method 497 remains excluded. Arbitrary extra-body map storage/merging and nullable mutation helpers at 388, 513, 607, 624 and 647 remain outside the typed request API; this bounded inventory does not claim their adaptation or full formatter parity.

## Stop-sequence request adaptation

- [x] Adapt pinned OpenAIChatFormatterTest.testApplyStopParameter (530) through typed LlmConfig.stopSequences and a canonical stop array containing END and STOP. Exact strings/order are preserved; the Scala contract also checks nonempty configuration arrays, absence/null, per-target projection replacement/clearing, retries and native/synthetic formatting. These additional checks do not represent additional Java test methods. Final source identities and verification belong to the [stop-sequence contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#optional-stop-sequences).
- [ ] Complete the remaining formatter/parser inventory and applicable provider behavior; this mapping does not establish exhaustive parity or live endpoint support.

## Completion parser hardening

- [x] Bounded streamed-usage adaptation of pinned OpenAIResponseParserTest.testParseDeepSeekPromptCacheHitTokens (1134): 100 input / 50 output / 60 direct cached tokens. OpenAIResponseParser.getSafeCachedTokens (86-94) supplies direct-before-nested precedence, including zero; getSafeCacheCreationTokens (104-110) supplies nested cache_write_tokens. Existing Scala parser tables add null fallback and strict validation; the HTTP scenario checks canonical history. The [cache-usage contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#cache-usage-decoding) records exact review and RED/GREEN evidence. This does not implement the Java full-message envelope or close full formatter parity.


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

The [request contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#user-image-projection) records current identities, both independent approvals and verification. Existing positive/negative tables also verify identity omission and whole-request rejection. URL extension/MIME inference, video Data inference, placeholder fallback and silent dropping are not adopted; no new provider protocol or media decoding is claimed.

Explicit image-URL MIME hints extend that same request boundary. Pinned URLSource.java declares optional mime_type and a no-hint constructor. Existing Scala source/request tables supply these bounded mappings; both final reviews and full mime-hint-verify.log verification passed:

- [x] `testDataBlockMimeTypeHint` (161): an extensionless Data URL with an explicit image hint becomes an image_url request part, preserving URL spelling and omitting the hint and local identity.
- [x] `testResolveMimeTypeHintOverExtension` (213): bounded request-level adaptation of hint authority over a misleading .mp3 path. The Java method tests MediaUtils directly; Scala does not implement its extension inference or claim utility-API parity.

Missing hints, aliases, other media, non-HTTP(S) URLs and unsupported roles reject the whole request. Explicit Image blocks retain their hint-independent behavior. No URL acquisition or byte/MIME verification is introduced.

### User Video URL request projection

Pinned OpenAIVideoSupportTest.java in the OpenAI formatter test package provides selected URL and inline video adaptations in the existing ChatCompletionsRequestSpec tables. The host must explicitly select a video_url-compatible endpoint; no public-provider or universal protocol support is inferred.

- [x] testConvertVideoBlockWithUrlSource (154): user video-only content becomes one video_url part with original HTTP(S) URL and no local identity/MIME hint.
- [x] testConvertMixedTextAndVideoContent (219): ordered user text/image/video parts retain exact values; other supported user-media ordering remains unchanged.
- [x] Both reviewers approved the two final source/spec identities; full verification and archive inspection pass through the [two-file contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#user-video-url-projection).
- [x] Fresh four-artifact standalone consumption passes without a live video-endpoint claim.
- [x] review_fp and review_invariants approved final Video URL documentation/English/link closure against 1227ce5 and the two unchanged identities.
- [x] Both reviewers approved the exact video/webm and video/mp4 source/spec scope; final focused/full/package/preparation verification passes. The finite ten-method applicability classification is recorded below; fresh consumer verification passes; final documentation approval belongs to the [current contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#inline-video-projection).

| Pinned method and declaration line | Bounded adaptation or explicit difference |
| --- | --- |
| testConvertVideoUrlSource (90) | Pure request serialization retains the original valid HTTP(S) URL; no separate Java converter utility API is cloned. |
| testConvertVideoBase64Source (98) | Explicit video/mp4 becomes the exact data URL; strict MIME/Base64 validation bounds the adaptation. |
| testConvertVideoNullSource (106) | Scala Video requires a MediaSource; the nullable Java constructor/exception contract is excluded. |
| testConvertVideoEmptyUrl (114) | Empty URL rejects with typed InvalidMessage rather than an unchecked Java exception. |
| testCreateVideoUrlContentPart (128) | Request AST has video_url and the exact URL; mutable DTO construction is excluded. |
| testCreateVideoUrlContentPartWithDataUri (138) | Explicit video/mp4 Base64 produces the exact data URL; an arbitrary prebuilt data-URL DTO API is not reproduced. |
| testConvertVideoBlockWithUrlSource (154) | Existing accepted user Video URL projection. |
| testConvertVideoBlockWithBase64Source (180; fixture 181) | Explicit video/webm Base64 produces the fixture's exact data URL. |
| testConvertVideoBlockWithNullSource (201) | Nullable source silent-skipping without an exception is excluded by the typed strict source contract. |
| testConvertMixedTextAndVideoContent (219) | Existing ordered mixed user-media projection, extended by inline video table rows. |

- [ ] Other inline video MIME types, Data video inference, broader provider contracts and live compatible-endpoint interoperability remain unfinished; the finite ten-method inventory above is fully classified.

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
- [ ] OpenAIThoughtSignatureTest.testTypedMetadataAttachedDirectly (line 78) and testToolMetadataRestoredAfterRoundTrip (line 165): the new explicit [opaque signature profile](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#opaque-signature-details) covers their legacy Thinking-detail preservation within the new checked profile. Whole-method parity remains unclaimed because Java function.thoughtSignature differs from the selected Google extra_content wire format.
- [x] OpenAIThoughtSignatureTest.testThinkingDetailsRestoredAfterRoundTrip (line 196): the bounded OpaqueSignatureDetails adaptation preserves raw type=signature/data objects through Msg round-trip and historical request replay. ReasoningDetailSpec asserts literal ordered arrays/data and explicit empty arrays in both encoders; opaque-signature-focused.log passes with both source reviewers approving the exact four-file scope. Existing profiles are not broadened; full RC1/artifact/consumer verification passes; final combined documentation approval passed in the linked contract.

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
- [x] Bounded OpenAIResponseParserTest.testParseReasoningTextType (391), testParseReasoningSummaryType (421) and testCombineMultipleReasoningTypes (451) adaptations have final independent source approval/full verification: existing full-message HTTP-client/ReAct tables now project reasoning.text, legacy reasoning.summary.text and ordered mixed plaintext while retaining exact thinking metadata. Streaming tables cover current/legacy/equal-dual aliases and strict conflicts. The [three-file contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#summary-text-alias) owns final source/full/artifact evidence; final documentation approval passed and no complete parser-suite claim is made.
- [ ] Other signature representations and live-provider interoperability remain unfinished. The mixed type=signature methods above stay open; strict malformed-detail failure intentionally differs from testUnconvertibleThinkingDetailsSkipped.

The parser methods are in the pinned `agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-openai/src/test/java/io/agentscope/extensions/model/openai/formatter/OpenAIResponseParserTest.java`. The [ordinary JSON HTTP contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#ordinary-json-completions) provides selected full-message parsing; legacy summary-detail input has the separately gated alias adaptation above.

- [x] Bounded source adaptation of pinned OpenAIClient ordinary request construction (286-299): the explicit encodeNonStreaming path sends stream=false and omits streaming-only options; default SSE remains unchanged. This is a source-contract mapping, not an invented upstream test method.
- [x] Bounded adaptations of OpenAIResponseParserTest.testParseResponseWithReasoningContent (67) and testParseResponseWithToolCalls (223): ChatCompletionsNonStreamingSpec exercises ordinary full-message reasoning and complete tools through actual HTTP/ReAct turns. The selected assistant/choice/finish/schema checks are stricter than permissive DTO conversion; no whole parser-suite parity is claimed; the legacy summary representation has the separately gated adaptation above. Final identities, both reviews and full verification belong to the linked ordinary JSON contract.

- [x] OpenAIMessageReasoningFieldTest.testDeserializeVllmStreamingDelta (100) and testDeserializeVllmStreamingChunk (147), in the pinned openai/dto test package: bounded streaming adaptation of reasoning as a scalar reasoning alias. Existing parser/detail/HTTP tables verify exact thinking preservation for delta and opted-in full-message SSE. The [alias contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#scalar-reasoning-aliases) records current identities and intentional differences: immutable chunk decoding replaces mutable Java DTOs, equal strings coalesce, conflicting strings fail, and explicit details remain authoritative. This does not claim non-streaming DTO serialization or whole-suite parity. No detail ID/index or encrypted data is inferred into a tool-call signature.

The pinned `MinimaxDoubledSummaryReplayTest.java` is in the parent openai test package. These are bounded adaptations through an explicitly selected SSE envelope profile, not wholesale provider compatibility or Java DTO mutation parity:

- [x] summaryEventMessageClearedByTransportLayer (91): full-summary semantic output is suppressed after a delta, while validated usage and a compatible finish are preserved. The immutable decoder emits selected chunks rather than clearing a mutable message.
- [x] reasoningAndToolArgsNotDoubled (106): pure selected-detail rows and client integration assert one copy of reasoning, text and complete arguments; summary values do not reach semantic events or text transformations.
- [x] chatCompletionOnlyStreamPreservesMessage (136): full-only SSE text and complete function messages are materialized before any delta. A preceding usage-only frame does not erase the full message; a role-only delta deliberately establishes stream authority.
- [ ] Validate live provider interoperability and remaining non-streaming/message-format methods independently; the default remains indexed deltas, and other reasoning-detail representations remain outside the selected profiles.

The [envelope contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#completion-envelope-profiles) records exact identities, stricter validation, terminal/DONE requirements, factory migration and independent acceptance.

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

- [x] AgentStreamingTest.testReActAgentStreamCompletesWhenModelReturnsEmptyText (256): the strengthened existing [successful-stream group](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#empty-text-stream-completion) requires EOF completion and exact Completed/runtime boundary results for empty nonreasoning text, retaining same-agent reuse. Both independent source/documentation approvals and focused/full checks pass. Legacy first REASONING/isLast envelope and exact two-second timing are not adopted; no other streaming method is classified by this scope.

- [x] ChatCompletionsClientSpec scenarios verify incremental publication, real HTTP tool cycles, request/reply correlation, late tool identity, incomplete/malformed input, and cancellation.
- [x] Three ModelStreamPublisherSpec scenarios verify indexed text/thinking, interleaved tool deltas, and validated complete ending batches; two ModelStreamContextSpec scenarios cover all sixteen affected event variants and legacy payloads.
- [x] Verify completed local tool results with request provenance, persistence before publication, and per-call start/delta/end ordering.
- [x] Independently review and verify provisional local updates with exactly one authoritative final result and scoped cancellation.
- [ ] Complete richer provider content and method-level formatter/transport mapping.

Bounded adaptations of the disabled upstream ordering sketches are classified above; literal sequence parity is intentionally excluded: they put MODEL_CALL_END before buffered content and close thinking before text starts. The implemented live contract publishes provisional deltas during the request and closes blocks only after validated completion. ModelResponseEnd follows scope cleanup. This is an explicit runtime adaptation, not exact ordering parity.

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

- [x] ReActAgentMiddlewareIntegrationTest.transformedTextIsUsedForPartialMessageWhenModelCallIsInterrupted (425): the strengthened existing cancellation group reproduces missing transformed partial history on unchanged 23a3bec. The [request-local progress correction](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#interrupted-transformed-progress) has final source approval and transformed-partial-green.log verification of the bounded transformed-history intent and cancellation/failure boundaries; full/artifact checks pass; final combined documentation approval passed. Generic middleware/event rewriting and live-provider behavior are not inferred.

## Post-reasoning decisions and accounting

- [x] Verify three policy/codec tests and nineteen runtime scenarios, including typed discard for the final previously pending structured-output method.
- [x] `review_fp` and `review_harness` approved the final six-file post-reasoning increment against `2a400e0` on 2026-09-29 after the legacy-usage correction. No findings remain in this scope. Full formatting, fatal-warning compilation, and verification passed 456 core and 40 harness tests.
- [x] ReActAgentMiddlewareIntegrationTest.serializeOnKeyGateReleasedAfterMiddlewareError (474): the strengthened existing [hook-error reuse group](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#hook-error-gate-reuse) targets the same-agent gate-release intent after a typed post-reasoning failure, with cleanup/call counts and second structured success. Both source reviewers approved the exact test identity and hook-reuse-focused.log passes; full verification and package/preparation checks pass; final combined documentation approval passed. Java AgentStart/onion middleware, unchecked exceptions and Reactor subscription behavior remain deliberate API differences; this is not whole middleware parity.
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
- [x] Verify bounded mixedBatchEmitsToolResultEventsForAutoDeniedToolsBeforeConfirmationRequest (465) classification through the strengthened PartialConfirmationSpec oracle: exact denied Start/Delta/End phase/call-ID sequence, one Denied end state and one RequestStop, End before Ask before Stop, one Ask, no early Ask and failure/cancellation suppression. Both reviewers approved the final test identity and focused/full verification passed; final combined documentation review passed. This does not install the same batch ordering in the active interactive adapter.
- [x] Complete final independent acceptance of active interactive-adapter initial stable policy-denial ordering through the [batch preflight contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/permission/README.md#interactive-batch-preflight). Runtime preflight prepares each occurrence once, joins stable policy-denial/preparation-error processing before publishing any approval, and reruns normal live authorization for Continue. New existing-suite tables cover Ask-first sequential/parallel batches, repeated IDs, publisher-time persisted history and released processing, defect/cancellation suppression and fresh gate denial. interactive-batch-order-red.log records the unchanged c507dad new-contract failure; final focused checks pass. This does not establish that later dynamic denials precede earlier asks or reproduce Java human envelopes.
- [ ] Complete authenticated transport, durable host persistence and exhaustive permission mode/rule parity. Core checkpoint restoration already retains saved reply context.

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

Pinned `agent/memory/MemoryConsolidatorFilesystemTest.java` contains five tests. The local backend supplies three explicit adaptations below; the two exact remote timestamp APIs are intentional differences, classified separately from aggregate progress persistence. These are not timestamp-wire equivalence claims:

- [x] `readWatermark_returnsEpochWhenStateAbsent`: reviewed local adaptation in LocalMemoryRepositorySpec; missing checkpoint leaves every daily input pending and creates no curated/checkpoint payload. No Instant.EPOCH semantics.
- [x] Classify `watermark_roundTripThroughFilesystem` (75): exact injected-backend timestamp round-trip API is intentionally excluded. [VersionedMemoryRepository](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/VersionedMemoryRepository.scala) atomically persists curated content and fingerprint acknowledgements instead; [VersionedMemoryRepositorySpec](../../modules/storage-postgresql/src/test/scala/com/weisgnosis/agents/storage/postgresql/VersionedMemoryRepositorySpec.scala) verifies commit, stale-snapshot rejection and reopened content/progress. This is not Instant wire equivalence.
- [x] Classify `watermark_doesNotCreateLocalFile` (95): exact timestamp-file method is intentionally excluded. The explicitly selected versioned repository accepts only a store/address and has no local fallback capability. PostgreSQL persistence evidence above does not assert a local-file sentinel or close hostile-filesystem guarantees.
- [x] `stateFileRelPath_matchesConstant`: reviewed local adaptation in LocalMemoryRepositorySpec; exact memory/.consolidation_state location with versioned JSON contents.
- [x] `watermark_localFallback_whenNoFilesystem`: reviewed local adaptation in LocalMemoryRepositorySpec; explicitly selected local repository persists checkpoint and progress across reopen, without automatic fallback or timestamp semantics.

The 21 new Scala tests verify atomic content/progress, conflict preservation, deterministic inputs, configured-counter budget enforcement, writer capabilities, and failure/cancellation cleanup. They are not substitutes for these five persistence mappings.

- [x] `review_fp` and `review_harness` approved the final nine-file persistence increment against `e4bd6b0` on 2026-09-29. No findings remain within cooperative local ownership, strict journal validation, recovery before API operations, conditional content/progress publication, and persisted generation tracking. Full `sbt scalafmtAll scalafmtSbt verify` passed 530 core / 209 harness tests, including 34 new persistence tests, with zero compiler warnings. Acceptance covers constructed interrupted-publication states on the tested Windows filesystem; live crash/fault-injection, fsync durability, remote storage, and hostile mutation are excluded.

## Explicit timestamp-watermark migration adaptation

Pinned MemoryConsolidator.readWatermarkFromFile parses stripped timestamp text and writeWatermark writes Instant.toString. The explicit local migration accepts that canonical representation but deliberately replaces it with empty fingerprint acknowledgements. It does not retain timestamp-based skip authority, infer already-consolidated daily inputs or silently recover a pending journal. Ordinary repository reads remain strict.

- [x] Three grouped LocalMemoryWatermarkMigrationSpec contracts establish content preservation and actual subsequent consolidation, strict no-op/rejection behavior and journal/lease exclusion. Both reviewers approved the [two current identities](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md#explicit-watermark-migration), and full verification passed. Existing local filesystem mappings remain scoped as before; the remote timestamp methods are classified above as intentional differences, not implemented by this local migration.

## Transcript observation applicability

- [x] Existing structured/transcript regression rows preserve exact nested numeric values in synthetic text, local JSONL rows and preview lengths after reopening. The [owned-output acceptance](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/transcript/README.md#exact-owned-numeric-output) records the reproduced failures and final verification; this closes no additional Java method.


Pinned SessionTranscriptWriterTest has four methods; MemoryFlushManagerOffloadTest has the three delegates listed above. The first Scala increment provides process-local observation evidence. LocalTranscriptStore now adds strict versioned JSONL persistence; Java wire/layout equivalence is not claimed.

- [x] `deriveEntries_emitsStructuredToolUseAndResult_withPairing`: exact assistant text, call IDs/names/input, result text/state/types, and structural occurrence keys. UUID entry identity replaces concatenated Java IDs explicitly.
- [x] `deriveEntries_placeholderForImageOnlyMessage`: an empty-text image placeholder; additional empty-message evidence is included.
- [x] Implement observation-level behavior from `appendMessages_isIdempotent_andKeepsChain`: overlapping batches deduplicate and actual parent UUIDs form one continuous chain. Local persistence/reopen evidence now completes the adaptation below.
- [x] Implement observation-level behavior from `offloadMessages_skipsAlreadyPersistedPrefix_andKeepsChain`: four exact stable keys and continuous parents after overlapping batches.
- [x] Implement observation-level behavior from `offloadMessages_nullAndBlankIds_stillAppendButDoNotBreakStableIds`: Option/blank adaptation, six exact payloads, stable-key deduplication, and valid parent chain; no Java-null API.
- [x] Implement observation-level behavior from `offloadMessages_persistsCompactionSummariesAndDedupesStableIds`: exact summary/user keys and texts are retained once.
- [x] Complete four local append/offload adaptations with real line counts, overlap/reopen, anonymous replay, retained content, and parent continuity. LocalTranscriptStoreSpec and named SessionTranscriptWriterSpec fixtures jointly supply evidence. The host selects one existing directory containing transcript.jsonl; Java concatenated IDs, agents/session paths, SessionEntry wire format, dual-file/index behavior, and remote mirroring are deliberately excluded.
- [x] `appendMessages_withTranscriptStore_writesImmutableSegments` (152): bounded immutable publication/reconstruction intent is adapted by the new real-PostgreSQL segment suite; final source/full/artifact verification and combined documentation approval passed. Java writer mirror/async timing/tenant-session layout are not replicated, so whole-method equivalence is not claimed.

Additional Scala tests cover duplicates within a batch, repeated tool-call IDs, delimiter-safe structural keys, first-observation retention, isolated stores, recording-time laziness, complete collision rollback, concurrent overlapping batches, exact preview boundaries, non-text results, and observable storage failure/cancellation. The upstream writer assigns parents before filtering and synthesizes colliding same-ID tool keys; the store-owned transition avoids those defects. Upstream file/mirror/index failures can be swallowed; the Scala boundary preserves typed failure. Compactor offload is installed explicitly and does not imply complete turn-boundary recording.

- [x] The local persistence increment against `e4dfc67` was approved by `review_fp` and `review_harness`; 27 new tests and full verification passed 530 core / 258 harness tests. Unicode preview splitting was reproduced and fixed in both input/output paths; exact prefixes and reopened equality are checked.

## Transcript completion boundary hardening

The pinned upstream has no TranscriptMiddlewareTest counterpart. TranscriptMiddleware.onAgent appends on normal completion and swallows append failures; failure/cancellation skip that append. The Scala observer deliberately covers terminal failure/cancellation, requires scoped observation, and exposes typed errors to direct callers and drained streams. Early consumers do not receive subsequent terminal errors. This is an explicit runtime hardening adaptation, not a claim of one-to-one middleware method parity.

- [x] Verify 22 core port/runtime cases and six harness completion cases, including local reopen, recovery persistence, identity policy and compactor prefix offload.
- [x] Obtain independent FP/invariant approval against `0349b1a`; full verification passed 552 core / 264 harness tests.
- [x] Map and implement local memory flush/consolidation triggers, isolation, throttling and asynchronous lifecycle adaptations below.
- [x] Verify the [persistent gate and bounded remote trigger/isolation matrix](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md#remote-middleware-throttle-integration) within their accepted contracts.
- [ ] Complete broader remote middleware and physical retention lifecycle behavior.
- [x] Complete bounded segment adapter acceptance.
- [x] Complete bounded explicit catalog documentation acceptance below.
- [ ] Complete broader/global discovery separately.

Exact reviewed identities are in the [core agent contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md).

## Memory trigger admission applicability

The pinned gate and middleware suites are adapted to MemoryTriggerGateSpec and the typed policy/interval suites. Tests use an explicitly shared in-memory authority instead of Java static maps; the host binds that authority to one memory owner. Structural keys preserve operation/scope separation without delimiter concatenation. User identity remains user-or-anonymous rather than adopting filesystem-specific session fallback. Admission is not scheduler or execution-lock evidence.

PeriodicGateTest:

- [x] localGate_firstClaimWinsThenThrottles: first admission at clock origin, immediate rejection, exact renewal.
- [x] localGate_differentNamesAreIndependent: independent structural user/session keys and operation keys replace arbitrary string names.
- [x] localGate_releasesAfterGap: just-before/exact expiry replaces Thread.sleep timing.
- [x] storeBackedGate_firstClaimWinsThenThrottles (66): the versioned gate real-PostgreSQL adaptation has final source approval/full/artifact verification; final combined documentation approval passed.
- [x] storeBackedGate_sharedAcrossInstances (76): independently constructed gates share an actual PostgreSQL CAS address, with final source/full/artifact verification; final combined documentation approval passed in the [gate manifest](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md#versioned-memory-trigger-gate). Trusted clock and lifetime limits remain explicit; this is admission, not an execution lock.

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
- [x] Verify the bounded store-backed adaptation: one real-PostgreSQL group runs the same four-scope/two-order oracle with independent backend/reopened gate bindings and persisted curated checks. Distinct row addresses do not prove cross-scope collisions; the [three-file contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md#remote-middleware-throttle-integration) owns completed source/full/preparation/consumer evidence; final combined documentation acceptance passed.

MemoryConfigTest trigger behavior:

- [x] flushTrigger_alwaysAndNeverAreSingletons: Scala enum cases provide stable value alternatives; Java object-reference assertions are excluded.
- [x] flushTrigger_throttledZeroDurationCollapsesToAlways: typed constructor normalization.
- [x] flushTrigger_throttledPositiveDurationKeepsThrottledMode: validated finite duration is retained.
- [x] flushTrigger_rejectsNullAndNegativeDuration: negative/infinite/bounds rejection is verified; Java null acceptance/rejection paths are explicitly excluded from this pure Scala API adaptation.
- [x] Map the six remaining MemoryConfig default/builder/prompt methods through the explicit adaptations below. Model selection and Java builder/wire compatibility are not claimed.

Additional hardening covers owner separation, exact clock origin, structural identifier collisions, strict numeric decoding, simultaneous claims, policy reset, signed elapsed wraparound and backward test-clock movement. Cleanup deliberately differs from upstream age-only eviction: an active interval survives shorter retention. Fixed policies require a new gate to change intervals, deliberately resetting windows. The gate samples the monotonic clock inside its serialized transition to avoid installing timestamps captured before competing claims.

- [x] Implement the four async-flush methods and the applicable async-maintenance gate behavior through owned runtime schedulers below.
- [x] Complete the separately reviewed [persistent remote gate](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md#versioned-memory-trigger-gate).
- [ ] Complete broader filesystem/remote retention and middleware parity; accepted gate/matrix scopes do not close these mappings.

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
- [x] Verify the bounded eight-combination remote throttle adaptation above.
- [x] Complete fresh selected-API consumer verification without fixture dependency leakage.
- [x] review_fp and review_invariants approved final combined documentation for that bounded scope against 5caa18f.
- [ ] Complete broader remote maintenance/retention and ownership mappings separately.

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
- [x] `PlanModeSubagentPropagationTest.declaredSubagent_inheritsPlanModeManagerMiddlewareAndConfiguration` (53): SubagentPlanInstallationSpec runs the actual declared factory with child-local enter/write/exit, exact stored draft review, configured location/request-only context and inspected-read versus mutation admission. Explicit host dependencies replace automatic Java manager/middleware installation; no unrestricted shell bypass or physical-file claim.
- [x] `PlanModeSubagentPropagationTest.generalPurposeSubagent_inheritsPlanModeManagerMiddlewareAndConfiguration` (82): The same actual-runtime table verifies the general-purpose preset through the opt-in installation path, preserving child-local review, request-only context and explicit host classification precedence.
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
- [x] Classify AdoptedTaskRunSpecTest.adoptedFuture_alreadyCompleted (131-150) as excluded external completed CompletableFuture import. The Scala registry accepts supervised child submissions; SubagentTaskRegistrySpec at 208/294 observes locally completed Exits, not external adoption.
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
- [x] Explicit declared/general-purpose Plan tool and request-context installation adapts both pinned PlanModeSubagentPropagationTest methods (53/82) within the [child installation contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#child-plan-installation). SubagentConstruction installs all three controls and inherited request projection; SubagentPlanInstallationSpec's actual child runs assert configured location/banner, child-local draft review and denied mutation. These bounded adaptations do not reproduce automatic Java HarnessAgent middleware/manager identity, mutable RuntimeContext, unrestricted allowShellInPlanMode, physical-file absence or the exact three-event denial assertion in those methods. Explicit host installation is the intended API difference, not an unimplemented automatic factory API.
- [x] review_fp and review_invariants approved the four-file two-method reconciliation against fbce6de, with no findings. English/diff and local-link checks pass; source/tests are unchanged and existing 1,522-test verification remains applicable.

### Inline declaration document parsing

- [x] Three [SubagentDeclarationParserSpec](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/subagent/SubagentDeclarationParserSpec.scala) groups verify the bounded Markdown/YAML profile including checked topP aliases, exact body preservation, null/empty selection distinctions, typed rejection and UTF-16 limits. Both reviewers approved the [exact parser/build/spec identities](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#inline-declaration-parsing); the false-delimiter and CRLF assertions failed before correction, and full verification passes.
- [x] Adapt `SubagentDeclarationPhaseATest.loader_prefersStepsOverDeprecatedMaxIters` and `loader_fallsBackToMaxItersWhenStepsAbsent`: valid steps takes precedence, absent/plain-null steps uses maxIters, and both provided aliases must validate.
- [x] Support name, temperature and iteration assertions from `loader_parsesAllHyperparams` and `loader_omittedHyperparams_areNullForInheritance`. Omitted iterations inherit the Scala parent rather than Java's fixed ten; variant remains unsupported, so these whole methods remain open.
- [x] Intentionally differ from `loader_malformedNumeric_isTreatedAsUnset`: malformed present numbers fail instead of silently inheriting. Explicit empty tools selects none instead of Java's empty-to-inherit conversion.
- [x] SubagentDeclarationPhaseATest.loader_acceptsCamelCaseTopP (102) maps to the existing parser table; snake-case top_p and actual child inheritance/override are also verified. builder_setsHyperparams (48), loader_parsesAllHyperparams (80) and loader_omittedHyperparams_areNullForInheritance (148) remain partial because variant and fixed-ten defaults differ. loader_malformedNumeric_isTreatedAsUnset (165) is intentionally rejected rather than silently inherited; dual alias presence also rejects, even null/null.
- [x] OpenAIChatFormatterTest.testApplyOptions (138) has a bounded topP/frequencyPenalty/presencePenalty request-field adaptation through existing request/failover tables. GenerateOptions (658-685) documents the penalty range; Scala additionally enforces finite values in [-2, 2], rather than relying on the Java builder's unchecked assignment. The seed extension also preserves the exact signed Long rather than applying Java formatter intValue narrowing; this is a bounded field-serialization adaptation, not full options/default-merging parity. Both reviewers and full verification are recorded in [seed and settings](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#optional-seed-and-exact-request-rendering).
- [x] Close the bounded OpenAIChatModelTest.testEnableParallelToolCalls (602-661, extensions/model/openai) adaptation with final independent review and verification. Existing ChatCompletionsRequestSpec and ModelFailoverSpec tables exercise omitted/enabled/disabled provider fields and per-target preservation; ChatCompletionsClientSpec uses real HTTP with false plus two returned calls to prove authorization remains authoritative. GenerateOptions enableParallelToolCalls and OpenAIChatFormatter map an optional preference to the wire; Scala uses ToolCallParallelism rather than a raw Boolean. This does not claim whole-model suite parity or provider compliance. Current scope is in the [parallel-call contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/client/README.md#optional-parallel-tool-call-steering).
- [x] GenerateOptionsTest.testBuilderWithSeed (239) asserts that the builder retains 12345L. Scala adapts that value-retention contract through existing seed configuration codec/request tables (including 42 and both Long endpoints); it does not reproduce the Java builder API or claim whole-suite parity.
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
- [x] Classify DefaultAgentManagerRuntimeContextTest.createAgentIfPresent_forwardsParentRuntimeContext (44) and createAgent_forwardsParentRuntimeContext (63): Java reference-identity assertions differ from captured Scala lineage/policy capabilities, supported by SubagentSpawnerSpec at 46-117 and inherited-policy SubagentConstructionSpec. This does not forward arbitrary session/user context. Null-context substitution (82) is excluded.
- [ ] Complete post-promotion/remote event delivery and durable recovery under separate contracts.

### Named reusable-session catalog

- [x] Three [SubagentSessionCatalogSpec](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/subagent/SubagentSessionCatalogSpec.scala) groups verify eager finite host installation, stable per-name runtime/history, distinct-name/catalog isolation, unchanged descendant depth, partial compound-failure/cancellation rollback and joined removal/aggregate cleanup. One additional [TaskResourceSpec](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/subagent/TaskResourceSpec.scala) group verifies public ownRetained lifetime and exact closure-cause replay. Both reviewers approved the [exact identities](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#named-session-catalog); full verification passes.
- [x] Reproduce duplicate cleanup causes in `subagent-session-catalog-red.log`: two groups passed and aggregate/caller closure failed exact single-defect equality. The same catalog spec passes after removing redundant automatic ownership edges, without changing public caller-leased ownership or applying Cause filtering.
- [x] Supply bounded supporting evidence for reusable runtime/history behavior within `AgentSpawnToolKeyTest.runtimeContextManagerIsUsedForSpawnAndSend`. This whole method remains open: per-call manager selection, repository restoration and its background-send adapter are not implemented by this host API.
- [x] Three [SubagentSessionCatalogToolsSpec](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/subagent/SubagentSessionCatalogToolsSpec.scala) groups extend the bounded `runtimeContextManagerIsUsedForSpawnAndSend` adaptation with named send/task/cancel routing, actual retained history, strict name/task isolation and promotion/cancellation. Both reviewers approved the [exact identities](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#named-session-tools), and full verification passes. Per-call manager selection, restoration and Java repository adoption remain outside this whole method.
- [x] Classify AgentSpawnToolKeyTest deterministic-key methods (234-278): exact 12-hex hash, parent/agent/label and null protocol are excluded from process-local host names/random directory identities. Pure hashing is possible; the difference is the selected identity contract, not a Pure FP restriction.
- [ ] Define dynamic labels, durable reuse keys/restoration and post-promotion event delivery separately; terminal outcomes use the completion inbox.

### Result-bearing event streams

- [x] Three [AgentRunUpdateSpec](../../modules/core/src/test/scala/com/weisgnosis/agents/core/agent/AgentRunUpdateSpec.scala) groups verify one complete ordinary or structured result after events and scoped cleanup, preserved Interrupted state/recovery, exact mixed terminal causes, and joined early/external cancellation. Both independent reviewers approved the [exact identities](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#result-bearing-streams), and full verification passed. This is a Scala execution-envelope extension, not a new AgentEvent wire variant or an upstream whole-method completion.
- [x] [ToolInvocationSpec](../../modules/core/src/test/scala/com/weisgnosis/agents/core/tool/ToolInvocationSpec.scala) verifies the explicit scoped event capability, approved occurrence identity, default/denied dispatch and joined cancellation. Both reviewers approved the [exact identities](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#invocation-event-capability); full verification passed. This is the Scala capability boundary for the upstream invocation emitter, not completion of child-forwarding methods.
- [x] [ReActAgentForwardingSpec](../../modules/core/src/test/scala/com/weisgnosis/agents/core/agent/ReActAgentForwardingSpec.scala) verifies direct core forwarding with complete results, ordered nested sources, full causes and joined cleanup. The unchanged end-last assertion exposed competing subscription consumers in `local-skill-verify.log`; [AgentEventForwarderSpec](../../modules/core/src/test/scala/com/weisgnosis/agents/core/agent/AgentEventForwarderSpec.scala) adds three grouped lifecycle regressions for the shared single-consumer correction, without claiming additional upstream method completion. Both reviewers approved the [exact identities](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#direct-event-forwarding), and full verification passed. Harness installation has the separate integration evidence below.
- [x] [SubagentForwardingSpec](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/subagent/SubagentForwardingSpec.scala) provides bounded adaptations of `HarnessAgentSubagentStreamEventsTest.streamEvents_childEventsForwardedWithSource`, `streamEvents_childAgentStartAndEndEmittedWithSource`, `streamEvents_eventOrdering_childBetweenParentStartAndEnd` and `streamEvents_parentEventsHaveNullSource`. Actual fixed/catalog nested execution retains parent source=None and ordered child bookends, with runtime task UUIDs replacing declaration-name paths. Both reviewers approved the [exact identities](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#verification-and-acceptance), and full verification passed.
- [x] Additional Scala groups verify same-execution completion after promotion and distinct fixed/directory session-send leases, with retained cleanup causes during cancellation/removal.
- [x] Classify HarnessAgentSubagentStreamEventsTest.streamEvents_childEventsForwardedWithSource_underAsyncToolMiddleware (216), including Reactor-context loss at 246-248: explicit scoped capabilities replace Reactor context. SubagentForwardingSpec at 77/143/251/320 supports nested identities, cancellation, promotion and sends; no Reactor-context API or whole-method assertion equivalence is claimed.
- [ ] Define post-lease/remote event forwarding and durable replay separately.

## Incremental local tool output

- [x] Both independent reviewers approved the [typed stream contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#incremental-execution) at its six exact identities, and full tool-stream-verify.log verification passed. The unchanged external-cancellation regression reproduced a new-draft consumer defect; the scoped channel-pull correction preserves the complete cause.
- [x] Bounded `ToolEmitterIntegrationTest.testToolEmitterWithHook` adaptation: provisional content reaches existing runtime events with call identity while an independently typed final value becomes model history only after EOF and cleanup. Immutable scoped streams replace mutable emitter injection and Hook APIs.
- [x] Bounded `ToolEmitterIntegrationTest.testToolWithoutEmitter` adaptation: the default stream executes the legacy contextual method once and retains ordinary final publication without provisional output.
- [x] Bounded `ToolEmitterIntegrationTest.testMultipleToolsWithEmitters` adaptation: actual parallel calls retain their own deltas and final values; repeated IDs do not share final-delta suppression. The Scala runtime test uses two streaming tools plus an ordinary sibling rather than sequential Java callback collection.
- [x] `ReActAgentToolkitConcurrencyTest.concurrentCallsDoNotCrossTalkChunkCallbacks` (pinned core/tool test, lines 308-347): a bounded adaptation shares one Tool/Toolkit between two independently owned Scala agents, holds both streams concurrently and checks exact isolated deltas, final observations, histories and distinct request/reply contexts. Java uses concurrent sessions on one agent; that API is not claimed. Both reviewers approved the [current fixture](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#shared-toolkit-destination-isolation), and full verification passed.
- [x] Classify ToolEmitterIntegrationTest callback methods (234/281/308/330): mutable callback setters/fanout and swallowed callback exceptions are excluded by the typed-stream contract. Callback-isolation machinery is not a required missing API; whole emitter-suite parity remains unclaimed.

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
- [x] Classify all sixteen pinned McpSyncClientWrapperTest methods below as bounded adaptations or explicit API differences; this does not establish whole-wrapper assertion equivalence. The explicit catalogue adaptation below now supplies selected caching semantics.

Evidence: [McpClientSpec](../../modules/mcp/src/test/scala/com/weisgnosis/agents/mcp/client/McpClientSpec.scala), [McpConnectionSpec](../../modules/mcp/src/test/scala/com/weisgnosis/agents/mcp/client/McpConnectionSpec.scala), [McpToolsSpec](../../modules/mcp/src/test/scala/com/weisgnosis/agents/mcp/protocol/McpToolsSpec.scala), [McpStdioTransportSpec](../../modules/mcp/src/test/scala/com/weisgnosis/agents/mcp/transport/McpStdioTransportSpec.scala) and [McpJavaSdkInteropSpec](../../modules/mcp/src/test/scala/com/weisgnosis/agents/mcp/transport/McpJavaSdkInteropSpec.scala).

| Pinned method (line) | Current applicability and evidence |
| --- | --- |
| testConstructor (52) | Private constructor with public scoped McpClient.open replaces an uninitialized named SDK wrapper; construction/flag assertions differ. |
| testInitialize_Success (59) | McpClientSpec verifies handshake/publication; explicit open then acquireToolCatalogue supplies bounded complete caching. Java combines these effects in initialize; Scala open alone remains handshake-only. |
| testInitialize_AlreadyInitialized (106) | No repeated handshake API; repeated acquireToolCatalogue reuses the first complete snapshot without another list request. Reopening still creates a fresh connection. |
| testInitialize_Failure (132) | McpClientSpec at 233 verifies failed initialization joins connection cleanup; Java exception identity/mutable flag differs. |
| testListTools_Success (145) | McpClientSpec and stdio/Java-SDK interoperability fixtures adapt initialized listing outcomes; Mockito delegate identity differs. |
| testListTools_NotInitialized (169) | Unrepresentable through the private constructor and checked scoped open. |
| testCallTool_Success (177) | Client/stdio/interoperability fixtures adapt successful calls; SDK invocation identity differs. |
| testCallTool_ReturnsError (201) | McpClientSpec distinguishes returned tool errors from protocol failures. |
| testCallTool_NotInitialized (220) | Unrepresentable through the private constructor and checked scoped open. |
| testCallTool_ThrowsException (230) | McpConnectionSpec at 183/278 preserves typed send failure/full reader cause; exact Java thrown-message wrapping differs. |
| testGetCachedTool (246) | Effectful cachedTool returns the retained original definition or None. The existing catalogue group verifies present/missing/multiple lookup and that fresh discovery does not refresh it; direct mutable-map/null API shapes differ. |
| testClose_Success (258) | Owned cleanup/process reaping is covered in stdio tests; explicit/natural client close clears the catalogue. Borrowed transports remain host-owned; SDK/mutable flag assertions differ. |
| testClose_GracefulCloseFails (272) | No SDK fallback/swallow policy; McpConnectionSpec at 382 preserves and replays cleanup defects. |
| testClose_NullClient (287) | Nullable client state is excluded by the typed construction boundary. |
| testClose_MultipleCallsSafe (296) | McpConnectionSpec at 382-440 verifies concurrent shared cleanup/same cause and automatic Scope closure; mutable flags differ. |
| testCallTool_WithNullArguments (309) | McpToolsSpec at 32 distinguishes Option.None omission from an empty object; McpStdioTransportSpec at 137-145 verifies an omitted-argument call. Java null itself is excluded. |

The seven pinned McpAsyncClientWrapperTest methods have the same bounded applicability: testConstructor (51) replaces an uninitialized wrapper with scoped open; testInitialize_Success (58) maps handshake then explicit bounded catalogue acquisition; testInitialize_AlreadyInitialized (105) maps first-success catalogue reuse without repeated listing; testListTools_Success (131) and testCallTool_Success (155) map initialized outcomes; testGetCachedTool (180) maps effectful original-definition/None lookup; testClose_Success (192) maps joined cleanup and catalogue clearing. Java initializes/discovers in one method; Scala hosts compose open and acquireToolCatalogue explicitly. Reactor/SDK delegation and mutable flags are not reproduced. No automatic cache refresh or notification invalidation is inferred. The [catalogue manifest](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/client/README.md#connection-local-original-definition-catalogue) owns current source review and verification gates.

The eleven pinned McpClientWrapperTest methods exercise a test subclass with mutable initialization/cache fields. Their bounded classifications use the client/connection/stdio evidence linked above have final independent classification approval within the thirteen-file scope against 0661e31:

| Pinned base-wrapper method (line) | Current applicability and evidence |
| --- | --- |
| testGetName (41) | Java wrapper-local name differs from explicit host manager registration identity; no wrapper getName API is claimed. |
| testInitializedFlag_DefaultFalse (46) | An uninitialized public wrapper/flag is excluded by McpClient's private constructor and public scoped open. |
| testInitializedFlag_AfterInitialize (51) | McpClientSpec at 160 verifies successful handshake before publication; mutable flag assertions differ. |
| testCachedTools_EmptyByDefault (57) | cachedTools and cachedTool before acquisition return an empty Chunk/None without network access; mutable-map exposure is not reproduced. |
| testGetCachedTool_NonExistent (63) | cachedTool returns None for a missing name before/after acquisition, without network access. |
| testGetCachedTool_Exists (68) | Explicit acquisition retains original McpToolDefinition fields; cachedTool returns the matching complete definition. Direct mutable-map insertion remains a Java fixture detail. |
| testCachedTools_MultipleTools (88) | Bounded paginated acquisition retains multiple original definitions and ordered duplicates; named lookup uses the last duplicate. Notification invalidation is not established by these methods. |
| testConcurrentAccess_CachedTools (118) | The Java body inserts/reads one entry without concurrent tasks. Scala lookup supplies that behavior and a separate Promise-coordinated group additionally checks two acquisition fibers perform one traversal. |
| testClose (136) | McpConnectionSpec and stdio process reaping provide bounded joined cleanup; catalogue tests verify explicit/natural clearing and closed admission without new network work. |
| testClose_MultipleCallsSafe (147) | Shared/repeated owned cleanup and identical failure replay are covered by McpConnectionSpec at 382-440; Java flag assertions differ. |
| testInitialize_Idempotent (159) | Repeated explicit catalogue acquisition reuses first successful discovery, including empty snapshots. The initialized connection itself cannot be reinitialized; reopening remains a fresh acquisition. |

- [x] review_fp and review_invariants approved all eleven base-wrapper applicability classifications against 0661e31. The current explicit catalogue adaptation now supplies selected original-definition caching; full wrapper equivalence and notification invalidation are not established.

- [x] review_fp and review_invariants approved the seven async-wrapper classifications and bounded child-environment adaptation against 0661e31, after final source/full/artifact verification.
- [x] Adapt McpClientBuilderTest.testStdioTransport_WithEnvironmentVariables (74) through immutable child-specific environment overrides, actual subprocess observation and unchanged-parent/reaping checks. This is new configuration support, not evidence of an existing runtime defect.
- [x] Independently review and verify the bounded McpClientBuilderTest.testTimeout (141) and testInitializationTimeout (149) adaptations through [checked independent deadlines](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/client/README.md#independent-deadlines), retaining the uniform overload. Existing executable fixtures cover handshake notification, request/page budgets and joined cleanup beyond the Java setter-only assertions.
- [x] review_fp and review_invariants approved final documentation for the eleven-file independent-deadline scope against a13b71e, including the three exact source/spec/consumer identities.
### Finite core MCP builder applicability

Pinned McpClientBuilderTest contains exactly 120 declared methods. Each declaration appears once below, with its method declaration line (not annotation line). Grouped decisions classify source-supported configuration, typed adaptations and API/profile differences; classification is not execution coverage or whole-builder assertion equivalence. Category counts: named wrapper 4, stdio 5, legacy SSE 4, Streamable HTTP 4, headers 7, deadlines 3, fluent assembly 3, construction 9, metadata 2, endpoint helper 25, query assembly 16, host HTTP client 11, elicitation 7, dynamic customizer 8 and versions 12.

| Category | Exact upstream declarations (line) | Bounded applicability |
| --- | --- | --- |
| Named wrapper | `testCreate_WithValidName` (37); `testCreate_WithNullName` (43); `testCreate_WithEmptyName` (48); `testCreate_WithWhitespaceName` (53) | Java named/uninitialized wrapper and null/blank constructor shapes differ from private initialized client plus checked manager names. |
| Stdio | `testStdioTransport_BasicCommand` (58); `testStdioTransport_WithNoArgs` (67); `testStdioTransport_WithEnvironmentVariables` (74); `testStdioTransport_EmptyArgs` (345) | Direct process command/arguments and immutable child overrides are supported; real-process tests replace Java builder-only assertions. |
| Stdio | `testStdioTransport_WithEmptyEnv` (352) | Same category decision above. |
| Legacy SSE | `testSseTransport` (87); `testBuildAsync_WithSseTransport` (265); `testBuildSync_WithSseTransport` (307); `testSseTransport_WithCompleteConfiguration` (421) | Legacy two-endpoint SSE is unsupported; selected Streamable HTTP GET/SSE is a different transport contract. |
| Streamable HTTP | `testStreamableHttpTransport` (95); `testBuildAsync_WithStreamableHttpTransport` (279); `testBuildSync_WithStreamableHttpTransport` (319); `testStreamableHttpTransport_WithCompleteConfiguration` (439) | Supported through explicit borrowed Client/endpoint configuration and actual protocol tests; no synchronous wrapper clone. |
| Headers | `testHeader_OnHttpTransport` (104); `testHeader_OnStdioTransport` (115); `testHeaders_MultipleHeaders` (126); `testHeaders_OnStdioTransport` (361) | Static HTTP headers are supported; mutable replacement/addition, null and ignored-stdio setter shapes are not Scala APIs. |
| Headers | `testHeaders_OverwritePreviousHeaders` (375); `testHeader_AddMultipleTimes` (392); `testHeader_WithNullKey` (851) | Same category decision above. |
| Deadlines | `testTimeout` (141); `testInitializationTimeout` (149); `testDefaultTimeouts` (456) | Checked separate initialization/request budgets and uniform default overload are accepted; Java default values/field shapes are not copied. |
| Fluent assembly | `testFluentApi_ChainedCalls` (157); `testComplexConfiguration` (404); `testFluentApi_AllFeaturesCombined` (1212) | Composition uses explicit immutable arguments and scoped effects; chained Java builders do not define additional runtime guarantees. |
| Construction | `testBuildAsync_WithoutTransport` (169); `testBuildSync_WithoutTransport` (179); `testBuildAsync_ReturnsWrapper` (189); `testBuildSync_ReturnsWrapper` (201) | Scoped initialization creates owned connections; unconfigured/null builder states and sync/async wrapper identity APIs differ. No Java builder reuse API. |
| Construction | `testBuildAsync_WithStdioTransport` (252); `testBuildSync_WithStdioTransport` (293); `testMultipleBuilds_FromSameBuilder` (331); `testBuildAsync_CreatesNewInstanceEachTime` (466) | Same category decision above. |
| Construction | `testBuildSync_CreatesNewInstanceEachTime` (480) | Same category decision above. |
| Metadata | `testPropagateMeta_DefaultsToTrue` (214); `testPropagateMeta_DisabledAppliedToBuiltWrappers` (232) | Live Allow/Omit restriction is supported; per-binding Omit default and explicit host resolver differ from automatic context propagation. |
| Endpoint helper | `testExtractEndpoint_WithSingleQueryParameter` (526); `testExtractEndpoint_WithMultipleQueryParameters` (533); `testExtractEndpoint_WithoutQueryParameters` (544); `testExtractEndpoint_WithRootPath` (551) | Host supplies the complete checked URL; Java private split/merge/re-encoding/default-path helper and reflection assertions are not replicated. Scala rejects fragments. |
| Endpoint helper | `testExtractEndpoint_WithEncodedQueryParameters` (558); `testExtractEndpoint_WithComplexPath` (569); `testExtractEndpoint_WithFragment_ShouldIgnore` (576); `testExtractEndpoint_NoAdditionalParams` (722) | Same category decision above. |
| Endpoint helper | `testExtractEndpoint_OnlyAdditionalParams` (728); `testExtractEndpoint_MergeWithUrlParams` (737); `testExtractEndpoint_AdditionalParamOverridesUrlParam` (752); `testExtractEndpoint_MultipleAdditionalParams` (764) | Same category decision above. |
| Endpoint helper | `testExtractEndpoint_UrlEncodesSpecialChars` (777); `testExtractEndpoint_ComplexPathWithMergedParams` (790); `testExtractEndpoint_WithEmptyPath_ShouldDefaultToSlash` (886); `testExtractEndpoint_WithOnlyHost_ShouldDefaultToSlash` (895) | Same category decision above. |
| Endpoint helper | `testExtractEndpoint_WithOnlyHostAndQueryParams` (903); `testExtractEndpoint_InvalidUrl_ShouldThrowIllegalArgumentException` (913); `testExtractEndpoint_WithEmptyQueryValue` (944); `testExtractEndpoint_WithParameterWithoutValue` (952) | Same category decision above. |
| Endpoint helper | `testExtractEndpoint_WithConsecutiveAmpersands` (960); `testExtractEndpoint_WithSpecialCharactersInValue` (969); `testExtractEndpoint_WithChineseCharacters` (984); `testExtractEndpoint_PreservesPathStructure` (996) | Same category decision above. |
| Endpoint helper | `testExtractEndpoint_WithTrailingSlash` (1004) | Same category decision above. |
| Query assembly | `testQueryParam_OnHttpTransport` (586); `testQueryParam_OnStdioTransport` (597); `testQueryParams_MultipleParams` (608); `testQueryParams_OverwritePreviousParams` (623) | Complete endpoint query is supported; mutable extra-query maps, overwrite setters and null/ignored-stdio shapes differ. No claim of identical URL normalization. |
| Query assembly | `testQueryParam_AddMultipleTimes` (640); `testQueryParam_WithHeadersCombined` (652); `testQueryParams_OnStdioTransport` (665); `testBuildAsync_WithQueryParams` (679) | Same category decision above. |
| Query assembly | `testBuildSync_WithQueryParams` (692); `testFluentApi_WithQueryParamsAndHeaders` (704); `testQueryParam_WithNullKey` (805); `testQueryParam_WithNullValue` (817) | Same category decision above. |
| Query assembly | `testQueryParam_WithBothNull` (828); `testQueryParams_WithNullMap` (840); `testQueryParam_NullOnStdioTransport_ShouldNotThrow` (861); `testQueryParams_NullOnStdioTransport_ShouldNotThrow` (873) | Same category decision above. |
| Host HTTP client | `testCustomizeSseClient_WithValidCustomizer` (1014); `testCustomizeSseClient_MultipleCustomizations` (1030); `testCustomizeSseClient_OnStdioTransport_ShouldBeIgnored` (1051); `testCustomizeSseClient_OnStreamableHttpTransport_ShouldBeIgnored` (1067) | Host supplies configured Client; Java HttpClient builder callbacks, HTTP2 setters and ignored-other-transport callbacks are not library APIs. |
| Host HTTP client | `testCustomizeStreamableHttpClient_WithValidCustomizer` (1084); `testCustomizeStreamableHttpClient_MultipleCustomizations` (1100); `testCustomizeStreamableHttpClient_OnStdioTransport_ShouldBeIgnored` (1120); `testCustomizeStreamableHttpClient_OnSseTransport_ShouldBeIgnored` (1137) | Same category decision above. |
| Host HTTP client | `testCustomizeSseClient_WithHttp2` (1154); `testCustomizeStreamableHttpClient_WithHttp2` (1168); `testCompleteConfiguration_WithClientCustomization` (1182) | Same category decision above. |
| Optional elicitation | `testAsyncElicitation_WithHandler` (1238); `testSyncElicitation_WithHandler` (1252); `testAsyncElicitation_WithoutHandler` (1266); `testSyncElicitation_WithoutHandler` (1277) | Unsupported optional capability/handlers; no-handler construction differs from advertising and serving elicitation. Remains open. |
| Optional elicitation | `testAsyncElicitation_FluentApi` (1288); `testSyncElicitation_FluentApi` (1303); `testElicitation_BothHandlersSet` (1318) | Same category decision above. |
| Dynamic customizer | `testHttpRequestCustomizer_OnSseTransport` (1341); `testHttpRequestCustomizer_OnStreamableHttpTransport` (1356); `testHttpRequestCustomizer_OnStdioTransport_ShouldBeIgnored` (1372); `testHttpRequestCustomizer_CombinedWithHeaders` (1387) | No built-in per-request MCP callback; supplied Client is the host customization boundary. Setter-only Java tests do not prove dynamic refresh. Remains open. |
| Dynamic customizer | `testHttpRequestCustomizer_CombinedWithAllFeatures` (1403); `testHttpRequestCustomizer_FluentApiWithBothTransports` (1422); `testHttpRequestCustomizer_MultipleCalls_LastOneWins` (1438); `testHttpRequestCustomizer_WithNullCustomizer` (1457) | Same category decision above. |
| Optional versions | `testProtocolVersions_SingleVersion` (1471); `testProtocolVersions_MultipleVersions` (1481); `testProtocolVersions_NullThrows` (1491); `testProtocolVersions_EmptyThrows` (1501) | Only exact 2025-06-18 is implemented; arbitrary negotiation lists remain open. Java null filtering and reflective builder-field checks are not negotiated-wire evidence. |
| Optional versions | `testProtocolVersions_BuildAsyncWithVersions` (1511); `testProtocolVersions_BuildSyncWithVersions` (1523); `testProtocolVersions_WithSseTransport` (1535); `testProtocolVersions_WithStreamableHttpTransport` (1545) | Same category decision above. |
| Optional versions | `testProtocolVersions_OverrideTransportIsApplied` (1556); `testProtocolVersions_WithoutSettingUsesDefault` (1575); `testProtocolVersions_NullElementsAreFiltered` (1589); `testProtocolVersions_AllNullElementsThrows` (1608) | Same category decision above. |

Source evidence: McpClientBuilder.java280-331 installs static headers/query setters;736-791 implements the private endpoint helper;266/878-879 installs the per-request SDK customizer;424/460/516-526/582-590 registers and advertises elicitation;378/506-508/621 delegates version lists. Scala [transport](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/transport/McpStreamableHttpTransport.scala) accepts the host Client/complete endpoint/checked headers; [initialization](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/protocol/McpInitialization.scala) advertises empty optional capabilities and validates the exact selected version; [connection](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/client/McpConnection.scala) answers ping and rejects unsupported server methods. Existing actual initialization/list/call, HTTP header/session and direct-process tests substantiate the linked bounded contracts; Java builder-only assertions do not establish behavior absent from those tests.

- [x] review_fp and review_invariants approved all 120 applicability decisions against be458fe under the [current documentation scope](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/client/README.md#builder-applicability-inventory).
- [ ] Define optional elicitation handler/capability ownership, cancellation and response validation before implementation.
- [ ] Define additional protocol-version profiles and actual negotiation/lifecycle evidence before admitting arbitrary version lists.
- [ ] Assess explicit dynamic per-request customization only if the supplied host Client boundary is insufficient; current setter fixtures establish no executed refresh contract.


- [ ] Complete remaining discovery-cache, schema and broader transport contracts; classified wrapper methods do not establish whole-wrapper assertion equivalence.
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
### ToolExecutor finite applicability inventory

Pinned ToolExecutorTest contains twelve methods. The table classifies bounded adaptations and intentional differences, rather than claiming twelve executed equivalents. The new [external binding scope](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#external-tool-bindings) has both final source approvals; final documentation acceptance passed.

| Pinned method and declaration line | Current applicability |
| --- | --- |
| shouldExecuteToolsInParallel (65) | Actual ReAct parallel execution has ReActAgentToolEventSpec361 evidence; Java executor/Mono shape differs. |
| shouldReturnErrorWhenToolThrows (107) | ToolExecutionSpec135 and ReActAgentToolEventSpec320 cover typed/defect Error observations; literal Java exception text is not the contract. |
| shouldReturnErrorWhenToolCompletesEmpty (132) | ToolExecutionUpdateSpec119 rejects empty and progress-only streams without Completed. |
| shouldReturnSuspendedResultForExternalTools (179) | Accepted bounded external binding/runtime adaptation preserves original occurrence identity without a local handler. |
| shouldValidateExternalToolInputBeforeSuspension (216) | Accepted bounded codec validation before authorization/suspension; no arbitrary JSON Schema validator is claimed. |
| shouldRejectInactiveGroupedExternalToolsBeforeSuspension (260) | Accepted bounded actual-runtime group rejection before external waiting; immutable group filtering replaces Java registration flags. |
| testToolErrorWithoutInterruptSpecialCase (306) | Accepted bounded fixture: nested InterruptedException inside an ordinary RuntimeException remains an Error observation; actual ZIO interruption is separate. |
| testConcurrentToolExecutionWithErrors (364) | Accepted bounded fixture: two atomic failing-then-successful calls plus independent success require exact three-call event/history observations without winner-order assumptions. |
| shouldApplyPresetParametersAfterExplicitInput (451) | ToolFixedArgumentsSpec28/117 and the MCP fixed-argument contract cover fixed-wins raw dispatch. |
| shouldUseOnlyPresetParametersWhenInputsAbsent (497) | Explicit empty object plus fixed-only dispatch is supported; nullable/absent Java input API differs. |
| shouldExecuteWhenRegisteredMetadataIsAbsent (527) | Policies travel with bindings; there is no separate nullable registration-metadata lookup. |
| testErrorMessageFormat (586) | Descriptive typed observations intentionally do not adopt Java's exact Error: prefix. |

- [x] review_fp and external_invariant_review approved this twelve-method classification and the bounded three-method external adaptation within the final 24-file scope against db39101; exact source/test verification passes. The two additional failure fixtures have their separate accepted scope below.
- [x] Accept the two bounded nested-error/mixed-concurrency adaptations after final review and verification in the [failure-fixture scope](../../modules/core/src/test/scala/com/weisgnosis/agents/core/tool/README.md#failure-fixtures); this does not claim exact Java scheduling or error formatting.

- [x] Both independent reviewers approved bounded MCP installation of the outer `ToolExecutor` fixed-wins contract, and full verification passed. `ToolExecutorTest.shouldApplyPresetParametersAfterExplicitInput` maps to fixed tenant overriding model input before authorization, retaining permitted nonfixed approved edits and overriding an overlapping default. `shouldUseOnlyPresetParametersWhenInputsAbsent` maps to explicit empty raw object dispatch with fixed-only arguments. `shouldExecuteWhenRegisteredMetadataIsAbsent` is an intentional structural difference: policy is attached to the Tool value, with an empty default, rather than a separate registration metadata lookup; no Java subclass parity is claimed. Original defaults-only ReAct coverage remains unchanged. Malformed checked fixed selection rolls back the entire registration; changed fixed approval yields Denied without remote invocation or metadata resolution. Exact scope belongs to the [MCP fixed contract](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/tool/README.md#fixed-arguments).
- [x] `McpClientManagerTest.testRegisterMcpClient_PreservesOutputSchemaInRegisteredTool` adapts to explicit host selection of ToolDef.outputSchema. Actual registered ReAct calls retain the selected AST through preset projection and both model requests; manager assertions cover deliberate discard and override. ToolDefSpec covers legacy/optional codec semantics, and ChatCompletionsRequestSpec proves omission from the provider request. This follows upstream AgentTool#getOutputSchema as descriptive metadata; no result validation or automatic schema adoption is implemented. Both reviewers approved the [core and current integration identities](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#descriptive-output-schemas), and full verification passed.
- [x] Both reviewers approved and full verification passed for the host-selected group foundation: `ToolGroupManagerTest.testCreateToolGroupWithAllParameters`, `testCreateDuplicateToolGroup`, `testUpdateToolGroupsActivate`, `testUpdateToolGroupsDeactivate`, and `testUpdatePreventsDuplicatesInActiveGroups` map to checked immutable declarations/atomic activation. `testSetActiveGroups`, `testSetActiveGroupsDeactivatesOthers`, `testReplaceMetaActiveGroups` and `testReplaceMetaActiveGroupsPreservesExternal` map to full versus Meta-only replacement. `testIsActiveToolUngrouped`, `testIsActiveToolInactive`, `testAddToolToGroup`, `testRemoveToolFromGroup`, `testRemoveToolGroups` and `testRemoveNonexistentToolGroup` map to derived OR membership, exact Toolkit selection and last-membership reporting. The actual ReAct fixture verifies both hidden definitions and blocked hidden dispatch. Unknown activation and membership groups intentionally fail instead of permissive/partial Java behavior; immutable values replace mutable-copy/builder/null semantics. [Exact group acceptance](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#host-selected-tool-groups) does not claim full manager parity.
- [x] Both reviewers approved pure group notes and their runtime integration, and full verification passed. `ToolGroupManagerTest.testGetActivatedNotesEmpty` and `testGetActivatedNotesWithGroups` map to sorted host-facing activeNotes across both scopes, with explicit empty handling. `testGetNotesOnlyShowsMetaGroups` maps to active/inactive metaNotes that exclude External names and descriptions. One new table-driven group covers empty, mixed-scope and blank-description values; existing runtime assertions verify request descriptions share the selected tool snapshot and reset output notes come from its atomic update result. MetaToolFactory description replacement, reset naming and description behavior are bounded source/test references; exact Java prose and missing-context mutation are not claimed. Skill binding reminders have separate acceptance below. Current identities and verification are in the [group foundation](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#host-selected-tool-groups) and [runtime contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#run-owned-tool-group-activation).
- [x] Both reviewers approved the bounded preloaded catalogue and runtime skill control, and full verification passed. `SkillRuntimeIntegrationTest.testMultipleSkillsActivationSequence` maps to union of explicit Meta targets across successful loads with shared defaults, next-request visibility and fresh-run reset. `testCompleteSkillActivationFlow` and `testSkillWithActualToolsIntegration` are only partial activation/execution references: generated-group registration, prompt-hook injection and repeated counter execution remain outside this scope; whole-method parity is not established. Three catalogue groups and three added runtime groups cover checked resources, codec reentry, explicit target validation, errors/denials, hidden collisions, fallback and content-only Auto routing. The [catalogue](../../modules/core/src/main/scala/com/weisgnosis/agents/core/skill/README.md) and [runtime](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#preloaded-skills) own exact acceptance.
- [x] Both reviewers approved actual harness Plan admission of the runtime skill loader, host/policy denial and confirmation rechecks. See the [Plan mapping](#plan-mode-inventory) and [current identities](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md#current-admission-identities).
- [x] `SkillToolGroupTest.testDescriptionIncludesSkillReminder` and `testCopyDoesNotDuplicateReminder` have bounded adaptations through pure catalogue bindings and existing actual runtime scenarios: deterministic grouped/quoted reminders, unchanged raw declarations, stable reconstruction, single text across requests/fallback, and successful explicit reset with current notes. Java subclass/copy APIs and exact prose are not reproduced. Both reviewers approved the [catalogue](../../modules/core/src/main/scala/com/weisgnosis/agents/core/skill/README.md) and [runtime](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#preloaded-skills) identities; full verification passed.
- [x] Both reviewers approved the bounded inline SKILL.md profile and full verification passed. Three [parser table groups](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/skill/README.md) adapt required name/description/content validation, including `SkillUtilTest.testCreateFromRejectsNumericRequiredMetadata`, and selected string/body/framing behaviors from `MarkdownSkillParserTest.testParseWithValidFrontmatter`, `testParseQuotedValues`, `testParseWithDifferentLineEndings`, `testParseBlockStyleModifiers` and `testFrontmatterSizeLimit`. These are partial references: the required-field contract rejects malformed input without fallback, uses explicit UTF-16 bounds and preserves body text. Current BOM framing additionally accepts LF/CRLF/CR and one initial U+FEFF under its separate finite manifest. No whole generic parser suite, source-derived IDs, generation or filesystem behavior is claimed; exact scope and identities belong to the [parser contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/skill/README.md).
- [x] Both reviewers approved [LocalSkill.load](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/skill/README.md#selected-local-loading), and full verification passed. Three grouped tests partially adapt `SkillFileSystemHelperTest.testLoadSkill_ByMetadataName`, `testValidateAndResolvePath_PathTraversal` and `shouldLoadNormalResourceFiles`: the declared name survives a differently named directory, traversal fails and selected normal text resources load. Unlike `SkillFileSystemHelper.loadSkillFromDirectory`, Scala requires explicit resource selection and fails the entire load on a selected missing, unreadable, oversized or invalid-UTF-8 file. Recursive discovery, binary conversion, skip-unreadable behavior, source-derived IDs, caches, writes and platform symlink/junction validation are not claimed.
- [x] Both reviewers approved bounded metadata preservation and full verification passed. Bounded mappings: `MarkdownSkillParserTest.testParseAndIgnoreComplexMetadata` (despite its name, it asserts retained lists, nested maps and allowed-tools); the parsing/order portion of `testPreserveMetadataOrder`; required-string checks in `testRejectNonStringRequiredMetadataValues` and `SkillUtilTest.testCreateFromRejectsNumericRequiredMetadata`; and the extra-field portion of `SkillUtilTest.testCreateFromEdgeCases`. Required name/description remain typed fields outside the extras object. Ordered JSON trees and inert authority-looking keys do not imply Java metadata lookup/source APIs, Markdown generation, full YAML or whole-method parity.
- [x] Both reviewers approved bounded [LocalSkillRepository discovery](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/skill/README.md#local-repository-discovery) and full verification passed. Three grouped tests provide bounded method adaptations: SkillFileSystemHelperTest.testGetAllSkills, testGetAllSkillNames_FromMetadata and testGetAllSkillNames_IgnoreInvalidDirs cover enumeration, declared identity and absent-document skipping; FileSystemSkillRepositoryTest.testConstructor_BaseDirIsFile and testConstructor_EmptyBaseDir cover root outcomes, adapted to cold typed effects. Missing-root failure follows source inspection; the new suite uses missing paths only to prove pre-I/O trust/limit rejection, so testConstructor_NonExistentBaseDir is not a matched runtime assertion. Scala counts all entries, sorts filename processing, rejects unsafe candidates and duplicate IDs, and fails the whole batch on invalid selected input rather than logging/skipping it. Source IDs, lazy resources/originDir, caches, writes and whole repository parity are excluded.
- [x] Both independent reviewers approved bounded eager TextTree resources; full verification passed. Two new LocalSkillRepositorySpec groups adapt the resource-only assertions of FileSystemSkillRepositoryLazyTest.eagerLoadsResourcesAndOriginDir (lines 67-80), SkillFileSystemHelperTest.shouldLoadNormalResourceFiles (379-391), and nested/text readback in FileSystemSkillRepositoryTest.testSave_WithResources (253-268). Origin stamping and saving are not implemented. Unlike Java's default eager mode, discovery is explicit and bounded; invalid UTF-8 and inaccessible/unsafe entries fail the whole snapshot rather than converting binary data or silently skipping failures. Benign dot entries are retained, an intentional difference from shouldFilterDotFilesAndDirectories; OS-hidden filtering is not implemented. No binary, lazy disk fallback, source-ID/cache/write or whole-repository parity is claimed.
- [x] Both independent reviewers approved exact deferred-resource capabilities and full verification passed. SkillLibrarySpec verifies exact cold bindings, monotone selection and full scoped cleanup; real runtime, selected local-reader and nested-child fixtures prove authorized fresh reads before activation. This is a bounded capability adaptation, not FileSystemSkillRepositoryLazyTest.lazySkipsResourcesAndKeepsOriginDir: no originDir is stamped and repository discovery does not infer readers. Automatic disk fallback, source IDs, mtime/size caching and repository writes remain excluded.
- [x] Adapt SkillHookTest.testInjectPromptForRegisteredSkill, testInjectPromptForRegisteredSkills, testHandleEmptySkillPromptGracefully, testInjectSkillPromptAtFirst and testMergeSkillPromptIntoExistingSystemMessage through pure selected catalogue rendering and actual request-only runtime projection. SkillCatalogSpec adds one escaping/selection/exclusion group; ReActAgentToolGroupsSpec adds one first-system/prepend/empty/history-isolation table and extends actual repeated-request/native-fallback assertions. Both reviewers approved the [four current identities](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#request-only-skill-catalogue); full verification passed. The pinned tests assert a transient systemMessage plus unchanged input order; Scala merges/prepends in the model-request projection without persisting it. testHookPriority's numeric constant is intentionally replaced by explicit pipeline ordering, not a general hook framework. Exact Java prose and full hook-suite parity are excluded.
- [ ] Implement other repository variants and durable activation separately.
- [x] Both reviewers approved bounded MCP group installation and full verification passed. `McpClientManagerTest.testRegisterMcpClient_PreservesOutputSchemaInRegisteredTool` asserts callback group forwarding, not manager-owned group lifecycle. `ToolkitTest.testAuthorizedToolCallSucceeds`, `testUnauthorizedToolCallShouldBeRejected` and `testToolGroupDeactivationPreventsExecution` provide grouped-dispatch references. Our actual manager/ReAct fixture checks Meta activation, External isolation and fixed-policy preservation. Before-acquisition group validation follows upstream source, not an explicit pinned test. Empty host declarations, checked aliases, atomic Ready-only toolkit/membership snapshots and removal preserving declarations are intentional stronger adaptations; no mutable callback registry or live agent refresh is claimed. Five manager groups reuse existing ownership fixtures; [current acceptance](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/client/README.md#grouped-installation) records both exact identities and verification.
- [x] Both reviewers approved run-owned activation and full verification passed: `MetaToolFactoryTest.testMetaToolCallAsyncActivateGroups`, `testMetaToolCallAsyncWithToolsInGroup`, `testMetaToolCallAsyncEmptyListDeactivatesAll` and `testMetaToolReplacesActiveGroups` map to next-request replacement with a fixed admitted batch. `testMetaToolCallAsyncMissingParameter`, `testMetaToolCallAsyncInvalidGroupName` and `testMetaToolCallAsyncPartialInvalidGroups` map to strict input and atomic rejection. `testMetaToolIgnoresExternalGroups`, `testMetaToolRejectsExternalGroupInInput`, `testMetaToolParametersOnlyListsMetaGroups` and `testMetaToolReplacementPreservesExternalDuringEmptyActivation` map to Meta-only schema, snapshot-consistent descriptions and preserved External activation. The activation groups additionally establish ordinary authorization, fresh invocation/cancellation isolation, fallback state preservation and runtime-control collisions. Immutable configuration plus invocation-local ownership replaces shared mutable/context lookup paths; Java missing-context mutation, toolkit copying, skill notes and exact prose are not claimed. [Current source/spec acceptance](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#run-owned-tool-group-activation) owns evidence.
- [ ] Ambient context bags remain separate; the checked ordered merge adaptation below has final scoped acceptance. String-keyed metadata override maps (`testRegisterMcpClient_PerToolOverrideWinsOverRegistrationDefault`, `testRegisterMcpClient_UnknownPerToolOverrideNameFailsRegistration`, `testRegisterMcpClient_OverrideForFilteredOutToolFailsRegistration`) are replaced by selected binding values, not claimed as those map-validation methods. Remote readOnlyHint remains data rather than local authority.
- [ ] Extend interoperability beyond the accepted direct SDK replacement and Netty POST-body case where required, including cursor-based listener replay and remote-work cancellation. Caching and schema evaluation remain separate; automatic cursorless reconnect, unowned subscriptions, in-place session mutation and POST replay are intentional exclusions.

The [2026-07-28 specification](https://modelcontextprotocol.io/specification/2026-07-28/basic) defines the common envelope. Strict unknown-field rejection and bounded integers are explicit local constraints. Method-level result tags, capabilities, request correlation state and version compatibility remain outside this codec's acceptance.

- [x] Verify runtime-owned lineage with six capability cases, two actual nested ReAct cases and one error-codec case. Depth-four rejection occurs before UUID/callback effects; cancellation reconciles every running level. These test-fixture spawn calls do not close production AgentSpawnTool mappings.

- [x] Both reviewers approved the MCP connection reservation/terminal correction; full signature-mcp-verify.log verification passed. The unchanged HTTP expiry assertion exposed an additional registry-closed failure in tool-signature-verify.log; exact evidence and current identities are in the [client acceptance](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/client/README.md#verification-and-acceptance). This is a Scala ownership regression, not another mapped upstream method.

### Checked ordered MCP metadata merge

Pinned agentscope-core/src/main/java/io/agentscope/core/tool/mcp/McpMeta.java defines merge at line 96. McpMetaTest.java supplies the selected pure adaptations below; the existing resolver integration verifies explicit host composition and final runtime call-ID precedence separately.

- [x] testMerge_SingleMeta (101), testMerge_MultipleMetas (109), testMerge_LastWinsOnDuplicateKey (119), testMerge_SkipsEmptyMetas (136) and testMerge_EmptyArray (159): the single McpMetadataSpec table covers empty/single/multiple sources and ordered top-level precedence, plus exact AST values and strict invalid-source rejection.
- [x] Both reviewers approved all three final source/spec identities; full verification and four archive/POM checks pass through the [ordered merge contract](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/tool/README.md#ordered-metadata-merge).
- [x] Fresh four-artifact standalone consumption passes using existing selected APIs without calling the merge helper.
- [x] Both reviewers approved final merge documentation/English/link closure against c4ec4c3 and the three unchanged identities.
- [ ] Define ambient context/schema/cache integration separately if applicable.

Java testMerge_SkipsNulls (128), testMerge_AllNulls (145) and testMerge_NullArray (152) are intentionally excluded: typed Chunk[Json.Obj] has no nullable input branch. Mutable constructor/defensive-copy/accessor/equality tests do not establish additional merge behavior.

## Versioned JSON publication and PostgreSQL subset

The pinned BaseStore contract is in agentscope-harness/agent/filesystem/remote/store; PostgresBaseStore and PostgresBaseStoreTest are in agentscope-extensions-postgresql. The Scala port deliberately exposes only read and compareAndSet. Checked namespace identity, positive typed revisions, exact JSON TEXT storage and explicit host-owned schema/DataSource boundaries replace Java generic map/builder/null APIs.

- [x] Verify the bounded BaseStoreContractTest.putIfVersion_successAndConflict (70) and putIfVersionZero_createIfAbsent (86) adaptations through real PostgreSQL one-winner races. ExpectedRevision.Absent replaces Java version zero; revisions remain positive and never wrap.
- [x] Verify bounded PostgresBaseStoreTest.getReturnsItem (178) and getReturnsNullWhenNotFound (193) adaptations through Option reads and exact numeric/namespace round-trips across independent real connections. Java uses mocked JDBC; Scala's PostgreSQL 16 fixture is separate integration evidence, not reproduction of those mocks.
- [x] Verify bounded PostgresBaseStoreTest.putIfVersionInsertDuplicateReturnsFalse (261), putIfVersionUpdateSucceeds (298) and putIfVersionUpdateMismatchReturnsFalse (306) behavior through real create/update races and exact-revision reads. ConditionalWrite.Conflict replaces Boolean false; SQL exception-mocking branches are not claimed reproduced.
- [x] Record final independent source/build/consumer-identity approval and full real-fixture verification in the [backend contract](../../modules/storage-postgresql/src/main/scala/com/weisgnosis/agents/storage/postgresql/README.md#verification-and-acceptance).
- [ ] Complete broader/global transcript discovery, remote pruning/layout migration and checkpoint runtime/host installation, segmented memory layouts/migration and their recovery/ownership mappings separately; the selected versioned memory adaptation is recorded below. BaseStore unconditional put, delete, search, configurable SQL/schema/table behavior and whole-suite parity are not implemented by this prerequisite.

## Versioned remote memory adaptation

The private version-1 memory aggregate is a stronger atomic domain adaptation over reviewed BaseStore read/CAS semantics: curated memory, daily ledgers, acknowledgements and acknowledged archives publish under one revision. It does not reproduce upstream timestamp-only watermark persistence, independent MEMORY.md writes, remote filesystem layout or generic mutable-map APIs.

- [x] Three real PostgreSQL VersionedMemoryRepositorySpec groups verify independent repository reopen/isolation, competing append preservation, stale consolidation rejection, atomic retention races, strict aggregate schemas/fingerprints, actual conflict retries and ambiguous acknowledgement without replay. Existing memory suites retain shared in-memory behavior without accessor-only additions; focused evidence and final identities belong to the [remote-memory contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/memory/README.md#versioned-remote-memory).
- [x] Obtain final FP/ZIO/invariant source approval and full-build/package verification for this selected remote-memory scope. No additional whole upstream method is closed merely by these integration groups.
- [x] Fresh versioned-memory-artifact-consumer.log passes standalone formatting, clean compilation and execution against four actual packaged module hashes; existing selected APIs are exercised without constructing the new repository.
- [x] review_fp and review_invariants approved final documentation for this selected remote-memory adaptation before bbadf13.
- [ ] Complete broader/global transcript discovery, remote pruning/layout migration, authenticated checkpoint runtime/host installation, remote filesystem applicability separately; the two legacy remote timestamp methods are classified above as intentional differences, and authenticated/exactly-once host claims remain external.

## Versioned remote transcript adaptation

The new TranscriptStore composition reuses the existing observation/key/chain rules while publishing a single strict versioned aggregate through read/CAS. Upstream session-file and immutable-segment formats, append/delete/list/compaction APIs and complete upstream methods are not reproduced by this adaptation. Existing named local mappings remain unchanged; the new real-PostgreSQL scenarios establish only this selected remote contract.

- [x] Verify independent append/reopen/namespace behavior, first-observation retention, anonymous entries and exact linked returns against actual PostgreSQL.
- [x] Verify definite-conflict retries with fixed candidate IDs/time and rebased parents, bounded exhaustion, strict aggregate rejection and uncertain-acknowledgement propagation.
- [x] Both reviewers approved the three final source identities; full 1,492-test verification, four-artifact inspection and fresh standalone consumption pass through the [canonical transcript manifest](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/transcript/README.md#versioned-remote-transcript).
- [x] review_fp and review_invariants approved final transcript documentation/English/link closure before b7488cc.
- [x] Complete bounded segmented adapter acceptance below.
- [ ] Define broader/global discovery, automatic writer integration, compaction/pruning and reclamation ownership/recovery contracts.

## Conditional acting-checkpoint persistence

The selected storage adaptation persists one strictly validated suspended AgentResult and returns its publication revision. save performs one host-expected CAS; it is not an exclusive execution claim or upstream workflow/database port. Runtime restore still validates current bindings and iteration limits. Existing checkpoint lifecycle mappings remain unchanged; no whole upstream checkpoint method is newly closed by this adapter.

- [x] Verify real PostgreSQL publication/reopen and resumed execution through current host bindings.
- [x] Verify strict persisted-state rejection, conditional conflicts and uncertain acknowledgements without automatic retry.
- [x] Both reviewers approved the seven final source/spec identities; full verification, archive inspection and fresh artifact consumption pass through the [checkpoint manifest](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/storage/README.md#versioned-acting-checkpoints).
- [x] review_fp and review_invariants approved final checkpoint documentation/English/link closure against b7488cc and the seven unchanged identities.
- [ ] Define remaining authenticated delivery/exclusive execution at the host boundary and remaining remote transcript discovery/pruning separately; segmented adapter documentation acceptance is tracked below.

## Immutable transcript segment adaptation

Pinned agentscope-harness/agent/transcript/ObjectStoreTranscriptStore.appendSegment (82) and listSegments (91) provide immutable publication/reconstruction intent. The Scala adapter publishes checked JSON segments under a CAS manifest and reconstructs only that manifest's bounded complete chain. It supplies no Java sequence filenames, tenant/session path derivation, asynchronous mirror or glob/list API. VersionedTranscriptStore remains the separate whole-aggregate adapter.

- [x] Verify actual PostgreSQL immutable new-only publication, complete chain reopening and stable-key deduplication, with final independent source/full/artifact acceptance in the [two-file contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/transcript/README.md#immutable-transcript-segments).
- [x] Verify definite manifest conflict/reparenting, invisible orphan preparation, malformed/cyclic/missing chain rejection, prospective capacity and ambiguous acknowledgement limits.
- [x] review_fp and review_invariants approved final combined documentation/English/link closure for this bounded adaptation.
- [ ] Define broader/global discovery, automatic writer integration, orphan reclamation, compaction/pruning/deletion and layout migration separately; no whole upstream transcript-store suite parity is claimed.

## Explicit transcript session catalog adaptation

Pinned SessionTranscriptWriter.java documents sessions.json updates at line 84 and calls WorkspaceManager.updateSessionIndex at line 144; the latter method is in agent/workspace/WorkspaceManager.java at line 406. These are source-only index intent references. The explicit Scala catalog binds sorted checked descriptors at one host address, without Java filesystem metadata/layout, automatic writer installation or global enumeration. No whole Java method or test mapping is newly closed.

- [x] Verify independent real PostgreSQL registration/reopen/order/isolation, exact-existing no-ops, identity/layout/subtree conflicts and capacity through the [three-file catalog contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/transcript/README.md#explicit-transcript-session-catalog).
- [x] Verify strict persisted schema, bounded definite-conflict attempts and uncertain acknowledgement/cancellation; preserve shared segmented-store behavior.
- [x] Obtain final source/full/artifact verification for the three reviewed identities.
- [x] review_fp and review_invariants approved final combined documentation for the three unchanged catalog/shared-helper/spec identities.
- [ ] Define removal/pruning/migration and any broader discovery or automatic writer integration separately.

## Registered transcript example composition

The unpublished [HostAssembly registered selection](../../modules/examples/README.md#registered-transcript-assembly) composes existing catalog/aggregate/segment capabilities before MCP acquisition. Its actual-ReAct table uses Ref-backed conditional storage for both layouts; it does not establish PostgreSQL or provider interoperability. This is a Scala host-composition contract, not newly completed Java method parity. Upstream type=signature DTOs remain unsupported under the explicit selected reasoning/signature profile difference; this scope adds no signature shapes.

- [x] Both source reviewers approved the two example identities; full verification and archive preparation passed.
- [x] Complete fresh selected-API consumer verification; the smoke does not select Registered.
- [x] review_fp and review_invariants approved final combined documentation for the unchanged two registered example identities against 1f4581b.
- [ ] Assess remaining provider reasoning/signature method gaps independently.

## Custom-client turn trust boundary

The [runtime contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#model-turn-trust-boundary) validates assistant role and TurnOutput classification after client cleanup and before runtime completion/persistence. Its grouped malformed/valid fixture table reproduces a Scala extension-boundary defect; no Java method mapping or new signature representation is closed.

- [x] Reproduce invalid output acceptance against the unchanged runtime with concrete history/hook/success assertions.
- [x] Both independent reviewers approved the final two identities; final full verification covers all eight malformed/two valid rows and archive staging passes.
- [x] Complete fresh selected-API consumer verification, including the existing custom LlmClient smoke.
- [x] review_fp and review_invariants approved final combined documentation for the two unchanged runtime trust-boundary identities against b70aa32.

## Audited public client example

Pinned agentscope-examples/documentation/ModelCallMiddlewareExample.AuditingMiddleware.onModelCall (149-185) supplies one-call delegation/timing/outcome source intent. The [unpublished Scala wrapper](../../modules/examples/README.md#audited-public-model-call) observes a complete client exit after owned request cleanup, preserving the distinction between Returned and runtime completion. Global counters/raw logging, Java chains/client substitution and private capability-based failover forwarding are excluded. This source-intent adaptation closes no general middleware suite or Java test method.

- [x] Both reviewers approved the final two identities and focused two-group contracts.
- [x] Full 1,512-test verification and archive/source preparation passed.
- [x] Fresh standalone selected-API consumer verification passed; it does not exercise unpublished AuditedLlmClient.
- [x] review_fp and review_invariants approved final combined documentation for the two unchanged audited-client example identities against 15047a8.

## Checked telemetry and admitted-work cancellation

The [usage arithmetic/runtime boundary](../../modules/core/src/main/scala/com/weisgnosis/agents/core/model/README.md#checked-usage-arithmetic) and [lease failure/cancellation correction](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/storage/README.md#admitted-work-failure-and-cancellation) address two reproduced Scala contract defects from a bounded source sample. Typed totalTokens/combine migration preserves wire fields; lifecycle correction preserves failure plus pending interruption. Neither closes an additional Java method or proves exhaustive code quality.

- [x] Record concrete RED assertions on unchanged arithmetic/runtime and lease implementations.
- [x] Complete final source approval, focused/full verification and archive preparation for both corrections.
- [x] source-sample-fixes-consumer.log passed standalone formatting, clean compilation and forked execution against four fresh module hashes, exercising checked usage validation/combination/total and overflow rejection.
- [x] Final combined documentation approval passed for checked usage/caller migration and lease cancellation against c198f98, with unchanged reviewed identities.

## Legacy transport and task-list scope

Both independent reviewers approved the seventeen source/spec identities against ce55421. Full verification passes 1,552 tests and fresh four-module packaged consumption. Final combined documentation approval passed against ce55421; the following checkboxes close only the named finite source adaptations.

- [x] Accept McpClientBuilder.sseTransport (pinned source line 158) through the scoped legacy transport and unchanged Java SDK servlet interoperability. customizeSseClient (line 180) is adapted to an explicitly injected and host-owned zio-http Client. Java buildAsync/buildSync wrappers become one scoped ZIO effect; no blocking/reactive wrapper API is required.
- [x] Accept the original older-revision/legacy SSE capability through endpoint-event routing, inbound message framing, checked same-origin POST targets, joined owned cleanup and explicitly selected lifecycle versions. See the [candidate manifest](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/transport/README.md#legacy-http-sse-and-explicit-revision-selection).
- [x] Classify the twelve pinned protocolVersions builder tests through the [supported-version method inventory](#supported-version-builder-methods): explicit proposed version plus checked supported membership preserves wire selection; Java null/reflection and wrapper identities are explicit adaptations. No ranked retry algorithm exists in the pinned SDK.
- [x] Accept TaskTool.taskList (pinned source line 175) as bounded enumeration of one host-selected registry, including filtering and fresh-parent-history discovery. The [task-list manifest](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#model-facing-task-enumeration) owns exact source/spec identities and intentional input-policy differences.
- [ ] Complete the original host workspace task-restoration obligation represented by WorkspaceTaskRepositoryTest. In-memory retained registry discovery does not establish persistence across process restart. The terminal-store subset and current opt-in registry terminal-publication candidate are recorded below; whole durable methods remain open.
## MCP resource next-request boundary

- [x] Reproduce a successful embedded-text MCP tool followed by failed next-model-request serialization against unchanged 6477b3b. Converter-only mocks had not exercised this formatter boundary; target/mcp-resource-request-red.log records the concrete second-request assertion failure.
- [x] Implement and independently source-review the [explicit request projection](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/tool/README.md#request-only-custom-content-projection), preserving original canonical Custom data and exact literal payload descriptions. Both reviewers approve three Scala identities; five focused groups pass.
- [x] Full/database/artifact/consumer checks pass for this boundary, with 1,655 full-run tests and four packages.
- [x] review_fp and review_invariants approve final combined documentation in the eleven-file scope against 6477b3b for this boundary. The core TextOnly default remains; hosts explicitly install the adapter, and typed image/audio limits are unchanged.

## Supported-version builder methods

The pinned [Java SDK lifecycle initializer](https://github.com/modelcontextprotocol/java-sdk/blob/v0.17.2/mcp-core/src/main/java/io/modelcontextprotocol/client/LifecycleInitializer.java) proposes the last configured version, accepts a server selection contained in the configured list and then sends initialized. It does not probe or retry versions by rank. Scala expresses those observable choices as an explicit proposed `version` and checked `McpSupportedVersions`; translating a Java list uses its last element and its membership set. This classification was independently checked by review_fp and review_invariants against SDK 0.17.2 source. review_fp and review_invariants approve final combined documentation in the 23-file scope against a5da755.

The following finite inventory refers to `McpClientBuilderTest.java` at pinned AgentScope revision `f98a685fdc4589f81e626e1686ac2c63d14a2304`. [McpVersionSessionSpec](../../modules/mcp/src/test/scala/com/weisgnosis/agents/mcp/transport/McpVersionSessionSpec.scala), [McpSupportedVersionsSpec](../../modules/mcp/src/test/scala/com/weisgnosis/agents/mcp/protocol/McpSupportedVersionsSpec.scala) and [McpProtocolVersionSpec](../../modules/mcp/src/test/scala/com/weisgnosis/agents/mcp/protocol/McpProtocolVersionSpec.scala) supply executable membership, validation and negotiated-framing evidence. Checked items classify the named assertions; they do not claim identical Java builder APIs.

- [x] 1471, SingleVersion: explicit proposal with a singleton supported set.
- [x] 1481, MultipleVersions: explicit proposal with checked alternative membership; list storage is an API adaptation.
- [x] 1491, NullThrows: Java-null varargs are inapplicable to the typed immutable Scala input; no literal null-test equivalence is claimed.
- [x] 1501, EmptyThrows: `McpSupportedVersions.make(Set.empty)` rejects empty membership with a typed configuration error.
- [x] 1511, BuildAsyncWithVersions: one scoped effectful client replaces the reactive wrapper identity.
- [x] 1523, BuildSyncWithVersions: the same scoped client replaces the blocking wrapper identity; no blocking facade is required.
- [x] 1535, WithSseTransport: legacy SSE accepts an explicit compatible proposal and supported alternatives under the selected transport contract.
- [x] 1545, WithStreamableHttpTransport: checked March/June profiles are supported; unsupported November selection is rejected deliberately, beyond Java's construction-only assertion.
- [x] 1556, OverrideTransportIsApplied: Java reflection over stored list positions becomes explicit proposal and membership configuration; private list identity is not a wire contract.
- [x] 1575, WithoutSettingUsesDefault: absent alternatives mean the exact proposed singleton. Scala June defaults for stdio/Streamable HTTP/custom transport and November for legacy SSE are explicit profile adaptations, not historical Java default equivalence.
- [x] 1589, NullElementsAreFiltered: Java-null elements and reflected list shape are inapplicable typed-input assertions; the retained last proposal and supported membership determine observable negotiation.
- [x] 1608, AllNullElementsThrows: Java's all-null array has no typed Scala equivalent; checked empty membership is rejected.

## Original-plan disposition

The publication audit on 2026-10-08 compares the initial tiered plan at `6fe93a5` and its audited correction at `c9adcb4` with current source contracts. The selected RC checklist is not a replacement for the original transfer scope. The original plan remains controlling. The checklist below records missing promised implementation, not options to waive or narrow its scope. The [controlling tracker](tracker.md#remaining-library-completion-work) owns the current remaining outcomes.

- [x] Implement core domain/runtime, typed tool dispatch, interruption recovery, compaction/eviction, permission, Plan and subagent capabilities within the linked method-level mappings and explicit ownership adaptations. This is component availability, not whole-suite or whole-port parity.
- [x] Implement scoped MCP tool discovery, typed call observations and explicit Toolkit installation under the selected 2025-06-18 profile; the [client contract](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/client/README.md) records actual lifecycle and ownership limits.
- [x] Both reviewers approved final combined documentation for the implemented Draft 2020-12 schema-admission increment; independent source reviews, full verification and fresh artifact consumption pass. The [current schema contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#runtime-schema-admission) owns exact behavior, migration and regression evidence.
- [x] Implement declared Draft 4, 6, 7 and 2019-09 alongside default Draft 2020-12, including exact numeric checks, dialect-specific integer/keyword semantics and bounded local references. Both source reviewers approved the [dialect manifest](../../modules/core/src/main/scala/com/weisgnosis/agents/core/tool/README.md#declared-standard-schema-dialects); full verification and fresh packaged consumption pass.
- [x] Classify all 39 pinned ToolValidator methods with finite behavioral evidence and explicit typed null/reflection/API adaptations below; full verification and final combined documentation acceptance pass against 55df42a.
- [x] Both reviewers approved final combined documentation for original HLD section 3.3 resource APIs. Full verification and fresh artifact consumption pass. The ten MCP resource source/test files have both independent source approvals; the current [resource contract](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/client/README.md#resource-client-contract) records exact identities and bounded evidence.
- [x] Implement embedded resource projection for pinned McpContentConverterTest text (311), image (326), binary MIME/URI (339), absent MIME (357) and image variants (470). McpToolSpec uses one grouped table to check visible text/image/binary descriptions plus retained Custom payloads and aligned remote metadata. Missing MIME uses an English unknown-MIME description instead of Java null. Both source reviewers approved the [projection manifest](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/tool/README.md#embedded-resource-projection-parity); reproduced RED, full verification and fresh packaged consumption pass.
- [x] Implement bounded revision-aware inbound batches required by the [March 2025 transport specification](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports), with exact grouped replies, atomic frame admission, invalid-member handling and joined cleanup. Both reviewers approved the [fourteen-file packet manifest](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/transport/README.md#march-2025-batch-packets); full verification passes. June/November remain singular. The pinned Java SDK object-only parser does not provide batch interoperability evidence; controlled HTTP and a real subprocess do. No client multi-request API or batch cursor replay is claimed.
- [x] Fresh archive admission and standalone four-module consumption pass for the batch/method-evidence increment.
- [x] review_fp and review_invariants approved the final combined 35-file scope against 55df42a, with sixteen unchanged source/spec identities and nineteen Markdown files; no unresolved findings remain in this scope.
- [x] Implement and independently review the original older MCP revision/legacy SSE capability through the scoped transport and explicit lifecycle profiles. Full verification and fresh packaged consumption pass; the finite adaptation and supported-version builder classifications are recorded above.
- [x] Implement and independently review bounded model-facing background task listing over one selected registry. Full verification and fresh packaged consumption pass; durable workspace restoration remains an original host obligation.
- [ ] Complete the remaining applicable upstream method inventories and substantive behavior gaps. Later API exclusions need behavioral justification; Pure FP does not inherently prohibit immutable caches, schema evaluation or equivalent semantic capabilities.
The historical Phase 6 WeisGnosis OS cutover (provider installation, legacy glue replacement and Temporal Activity end-to-end tests) is a separate product task. The user explicitly excluded it from completion of this library on 2026-10-08; library API and integration documentation remain required.
- [x] review_fp and review_invariants approved this original-plan classification and the compiled publication guide in the final 19-file scope against 24a9980. Approval records the gaps; it does not approve removal of promised capabilities.

Existing deliberate differences retain their method-specific rationale. Counts elsewhere apply only to named finite inventories; no exhaustive count or completion percentage is inferred for the entire upstream repository. Upstream pin changes require a separately reviewed delta inventory.

## Runtime schema admission: finite evidence

All 39 methods in pinned ToolValidatorTest.java are classified below. The current two-spec increment adds missing cases to existing groups without changing production code or increasing test-group counts. This is a finite behavioral mapping, not an exhaustive JSON Schema conformance claim. Toolkit admission remains distinct from provider strictness and typed decoding.

| Pinned methods (source lines) | Executable evidence or explicit API adaptation |
| --- | --- |
| testRequireNonBlankToolName (46) | ChatCompletionsRequestSpec rejects empty/whitespace call names and empty IDs with exact InvalidMessage expectations; valid named calls succeed. Java's nullable call-ID acceptance and Boolean/logging helper are replaced by checked provider encoding. |
| testNullSchema (94) | An absent Java Map is replaced by ToolDef's explicit empty-object default. This is an API adaptation, not acceptance of Json.Null as a schema. |
| testEmptyInputWithEmptySchema (103), testEmptySchema (118) | ToolSchemaValidatorSpec accepts an empty object against an empty properties object, and arbitrary object input against {}. |
| testNullInputNoRequired (128), testNullInputWithRequiredFields (183) | A null Java input String is unrepresentable in the typed JSON AST API. Object-schema rejection of JSON literal null is related semantic coverage, not the identical Java fixture. |
| testMissingRequiredField (147), testAllRequiredFieldsPresent (164) | Required-field omission fails; an object with both name/age succeeds. |
| testStringTypeWithNumber (203), testIntegerTypeWithString (221), testCorrectTypes (238), testBooleanType (260) | Existing constraint table covers string/integer/number/boolean semantics with valid and invalid neighbors. The combined Java object is adapted to primitive rows; no coercion is permitted. |
| testErrorIncludesFieldPath (292) | The normalization/original-input group requires the typed failure to include BOTH /name and /address/city. |
| testValidEnumValue (325), testInvalidEnumValue (348) | Declared enum accepts red and rejects blue. |
| testBelowMinimum (376), testAboveMaximum (393), testWithinRange (410) | One bounded integer interval has below/inside/above rows. Exact large/tiny-number controls remain additional runtime hardening. |
| testBelowMinLength (434), testAboveMaxLength (451), testWithinLengthRange (468) | Short/long strings fail; the existing valid abc row satisfies the same length bounds with its pattern. |
| testPatternMismatch (487), testPatternMatch (510) | Valid abc and invalid a1 distinguish the declared alphabetic pattern. |
| testValidArrayItems (538), testInvalidArrayItemType (557) | Integer [1,2] succeeds; [1,2,"three"] fails. Uniqueness has its separate invalid neighbor. |
| testNestedObjectValidation (581) | Nested address/city succeeds; a nested missing required city fails. |
| testRootDefsRefResolution (621), testNestedDefsRefFailsWithoutFix (664) | A request/materialList/items reference resolves against root $defs; the paired misplaced-definition schema fails. Correctly qualified nested definitions are not prohibited. |
| testGeneratedBeanSchema_MissingOptionalField (716), testGeneratedBeanSchema_ExplicitNullOptionalField (727), testGeneratedBeanSchema_ExplicitNullRequiredField (738), testGeneratedBeanSchema_UnknownNullFieldStillFails (749) | Explicit schemas/compile-time codecs replace reflection-generated mutable beans. Optional omission/null pass; required null fails; unknown null remains subject to additionalProperties:false. Actual Toolkit execution receives the unchanged original optional-null AST. |
| testNullAssistantMsg (775) | The nullable standalone helper is replaced by typed messages/history; no Java exception/no-op API identity is claimed. |
| testNoToolUseBlocks (781) | Ordinary text-only request serialization succeeds without tool observations. |
| testToolUseWithoutInput (793), testToolUseWithEmptyInput (813) | The typed history representation collapses absent/null Java lists into explicit collections. An unresolved assistant call fails checked request encoding. |
| testMatchingToolResults (833), testMissingToolResults (869) | Complete grouped results succeed; the invalid-history table directly rejects two distinct calls followed by only one matching result. |
| testToolResultsAcrossMultipleMessages (908) | Separate parallel results in completion order succeed, including later ID reuse. Provider encoding pairs IDs; stronger occurrence/name-sensitive ToolHistory tests do not imply name-sensitive provider validation. |

- [x] Classify all 39 pinned methods into finite executable adaptations and explicit null/reflection/error-API differences; no method is silently omitted.
- [x] review_fp and review_invariants approved the two exact test identities against 55df42a; schema-method-inventory-focused-final.log passes 28 groups in 778 ms with zero failures/ignored.
- [x] Full required verification, fresh archive admission and standalone consumption pass for this test-only mapping increment.
- [x] review_fp and review_invariants approved the final combined 35-file scope against 55df42a, with sixteen unchanged source/spec identities and nineteen Markdown files; no unresolved findings remain in this scope.

ToolExecutor's external-admission ordering retains its separate ReActAgentExternalToolSpec invalid initial/rewrite and issued legacy-wait evidence. Schema-invalid but decoder-valid arguments cannot become new host dispatch; authenticated previously issued legacy work can finish without reinterpretation.

MCP resource list/templates/read/subscribe/unsubscribe and typed change notifications implement original HLD 3.3 plus the selected MCP resource wire contract. Pinned McpClientWrapper exposes initialize/listTools/callTool/getCachedTool/close, with no resource-server methods; resource APIs are not fabricated wrapper method parity. Actual Java SDK HTTP interop verifies listing, reading, empty templates and list_changed. Controlled wire fixtures verify subscriptions/updated events and cancellation; no live external resource server is claimed.

## Remaining original library obligations: source inventory

The [tracker effort table](tracker.md#remaining-library-completion-work) separates these identified scopes from unclassified library behavior. Product cutover is a separate task. Estimates are provisional aggregate engineering effort including review/verification; persistence tasks overlap and must not be summed into a project forecast.

- [x] Implement and source-review connection-local original-definition catalogue acquisition/cache/lookup/close; full/artifact verification passes.
- [x] review_fp and review_invariants approved final combined documentation for the sixteen-file scope against 63776a7; broader original obligations remain open. McpAsyncClientWrapperTest.testInitialize_Success (58), testInitialize_AlreadyInitialized (105), testGetCachedTool (180) and corresponding sync methods (59/106/246) require more than handshake/publication. Generic wrapper methods (57-118) and testClose (136) add empty/present/multiple lookup and close-time clearing. Pinned async wrapper initialization publishes cachedTools at lines80-88; fresh listTools (105-111) does not update that snapshot. Existing immutable Toolkit registration already implements the distinct discovery-to-execution bridge. No automatic invalidation/refresh scheduler is inferred.
- [x] Implement explicitly configured MCP revision alternatives selected by the server. Pinned builder documentation (361-377) and tests (1471-1615) retain multiple supported revisions. McpSupportedVersionsSpec, McpVersionSessionSpec and the existing three transport suites verify checked membership, correlated lifecycle selection, immediate framing and selected headers. Both source reviewers approve the [eighteen-file scope](../../modules/mcp/src/main/scala/com/weisgnosis/agents/mcp/transport/README.md#supported-server-revision-agreement); final combined documentation acceptance passed against 1e18d44 for eighteen unchanged Scala files and fifteen Markdown files, approved by review_fp and review_invariants. Preference ordering and reconnect are not inferred from these builder assertions.
- [x] Classify all 25 pinned WorkspaceTaskRepositoryTest methods under the [finite task-repository inventory](#workspace-task-repository-method-inventory): eight bounded observable adaptations, one partial method and sixteen open methods. Explicit immutable observations, owner-selected partitions and independent adapter reopen replace mutable handles, session-string routing and synthetic completed futures; actual process restart and whole-class parity are not claimed.
- [x] review_fp and review_invariants approve the exact five-file terminal source scope against c99596b.
- [x] Full verification and archive admission pass for the [five-file terminal storage scope](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#durable-terminal-observations), including all 23 actual PostgreSQL groups.
- [x] Fresh standalone artifact consumption passes under the linked manifest; the unchanged probe does not independently exercise the terminal API.
- [x] Obtain final combined documentation acceptance for the bounded terminal-store scope against c99596b.
- [x] Implement and independently source-review the opt-in automatic registry terminal-publication increment and existing agent_task/task_list reopened terminal read/filter views under the [seven-file manifest](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#registry-terminal-publication). Focused lifecycle and actual PostgreSQL/ReAct verification pass, and both final source reviews approve the exact accepted scope. Full verification, archive admission and fresh standalone consumption pass; review_fp and review_invariants approve final combined documentation against `5f75a0a` for eighteen files (seven unchanged Scala identities and eleven Markdown files), with no unresolved findings.
- [x] review_fp and review_invariants approve final combined documentation for the registry publication increment against `5f75a0a`: eighteen files, seven unchanged Scala identities and eleven Markdown files, with no unresolved findings.
- [ ] Complete the remaining whole-method workspace contracts: running records, original agent/session metadata and filter semantics, executable live/session restoration, remote-owner cancellation and durable running-barrier restoration, the [remaining task-detail metadata contract](#task-output-source-obligations) and remote notification/recovery integration. Terminal observations never fabricate live handles or execute suspended checkpoints.
- [ ] Implement remaining remote wakeup ownership, task-detail applicability and durable running-barrier restoration separately. Current-owner methods882/923 are source-approved under the [completion notification contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#completion-notifications); review_fp and review_invariants approve the final combined scope against ff4566e: eight unchanged Scala identities and fourteen Markdown files, with no unresolved findings. The [current-owner cancellation adaptation](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#durable-cancellation-intent) owns the separately source-approved finite test-263 contract and its final combined documentation approval. The [durable acknowledgement ledger](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#durable-terminal-acknowledgement) implements explicit first-stamp receipts and uncertainty propagation; the [remaining task-detail metadata contract](#task-output-source-obligations) and durable running-barrier restoration, durable wakeup replay and remote-owner cancellation/recovery remain open.
- [ ] Decompose reusable-session restoration from AgentSpawnToolKeyTest runtimeContextManagerIsUsedForSpawnAndSend (52), deterministic key methods (234/248/255/262) and persistSession cases (288/299). Stable names alone do not install or exclusively restore a session. Current-owner heartbeat methods478/534 have their explicitly reviewed refresh contract below. The [host-driven recovery contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#host-driven-task-recovery) supplies six bounded local recovery/gate adaptations under explicit host authority and scheduling; persisted remote exclusion687 and local cross-session discovery789/812/821 are implemented within their linked contracts, and no library daemon is installed.
- [x] Preserve the pinned descriptive output-schema behavior: McpClientManagerTest.testRegisterMcpClient_PreservesOutputSchemaInRegisteredTool (352) has existing registered ReAct evidence above. Upstream stores/exposes the schema without evaluating output results; automatic result validation is not a missing assertion from that method.
- [x] Adapt selected metadata extraction/merge, both propagation gates, live restriction and runtime call-ID overlay under existing scoped source approvals. McpToolMetaExtractionTest (118-235) and McpToolMetaPropagationTest (127-251) are covered by explicit effectful resolver/binding/client contracts. Java ambient-map API identity is not a separate behavior requirement; remaining unclassified methods are not closed here.

## Upstream delta checked on 2026-10-08

The local upstream checkout and existing method mappings remain pinned to `f98a685fdc4589f81e626e1686ac2c63d14a2304`. The latest successful fetch of `origin/main` on 2026-10-08 found `768d9613dd8ea2108b5b53111f8d1762e2599565`, 33 commits ahead: the previously inspected 27 plus six additional commits below. No checkout or parity repin occurred. The [current comparison](https://github.com/agentscope-ai/agentscope-java/compare/f98a685fdc4589f81e626e1686ac2c63d14a2304...768d9613dd8ea2108b5b53111f8d1762e2599565) bounds this delta. Newly fetched tag `v2.0.4` resolves to `0b6743b263ba8e39eb417e35048026f1e0196548`; that release-tip commit is not an ancestor of the fetched main, so release and main are distinct comparison targets. Tag `v2.0.3` predates the pinned baseline.

The first table records the previously inspected 27 commits through fbbc2b63. The additional six are listed separately with unresolved applicability explicit. Inspection/classification is not implementation or executable parity. Differences outside existing Scala profiles are not silently accepted exclusions from the original transfer plan.

| Commits | Count | Source change and current applicability |
| --- | --- | --- |
| `9c8b10d4`, `b68a8b0a` | 2 | JDBC schema creation/validation and skill repository consolidation. Scala's selected PostgreSQL CAS backend neither verifies an existing schema nor implements a JDBC skill repository; assess retained persistence scope separately. |
| `3a6baf6c`, `88553194`, `01da9e6f` | 3 | JDBC documentation, agent coding instructions and message JavaDoc. Documentation/process changes are not new runtime transfer evidence. |
| `52570626` | 1 | Sandbox edit command fixes generated Python escaping. No corresponding generated Python edit adapter exists in the selected Scala filesystem contracts. |
| `325f33d6` | 1 | Optional keyword matching modes for memory/session search. No equivalent model-facing search-mode capability is implemented; applicability remains to be decided. |
| `9343ff0a` | 1 | DeepSeek formatter preserves every assistant's reasoning and backfills missing traces with empty reasoning_content. Scala's explicit selected replay policies do not supply this vendor-specific backfill; latest-assistant replay is a different profile, not equivalent coverage. |
| `14608668` | 1 | OpenAI provider accepts OPENAI_BASE_URL. Scala receives an explicit endpoint in LlmConfig; the host may read environment configuration. No automatic environment-alias behavior is claimed. |
| `9b611928` | 1 | Caller-owned OpenTelemetry SDK injection. The Scala logging example uses existing ZIO logging and host export; no equivalent OpenTelemetry middleware/SDK binding is implemented. |
| `a5be7610` | 1 | Judge extension probability rounding tolerance. No corresponding judge client exists in the selected library. |
| `3478329b` | 1 | DashScope reasoning-effort wire projection. Scala's checked Chat Completions preference does not implement this separate provider protocol. |
| `addf82b8`, `e9721285` | 2 | Declared middleware participation and agent-state-ready callbacks. Existing explicit Scala ports replace some middleware uses; full participation/state-ready behavior needs a separate applicability assessment, not an automatic middleware-chain clone. |
| `c8ea6029` | 1 | DeepSeek model context-window registry update. Scala has no automatic model registry/context-window inference to update. |
| `2c503d88`, `fbbc2b63` | 2 | Optional leading UTF-8 BOM in shared skill/declaration parsing and workspace examples. Both current Scala domain parsers and their local loaders implement single initial BOM and LF/CRLF/CR while preserving interior data and original budgets; source reviews and focused/full/artifact verification pass. Domain parsers retain required-declaration failures instead of generic raw-content fallback. SkillManageTool create/edit wrappers and future repin method applicability remain separate. |
| `91550713` | 1 | Gemini media URL extension parsing. Scala does not infer MIME from URL extensions or implement the Gemini formatter; no equivalent provider coverage is claimed. |
| `860b9287` | 1 | Streaming transport retry classification and retry suppression after nonempty response content. Scala's strict pre-success-response retry/failover boundary is different and does not permit replay after successful-body admission; do not widen retries implicitly. |
| `a3798a8f` | 1 | Anthropic responses tolerate missing message IDs. No Anthropic Messages parser is implemented by the selected Chat Completions client. |
| `d5cc463e`, `b4dda79c`, `9172dc5d`, `68f25d27`, `9e43ed83` | 5 | Anthropic, Guava, Bailian, OpenTelemetry and OpenAI dependency updates. These are not Scala dependency requirements or behavioral parity evidence; no automatic bump is justified. |
| `c0d69deb` | 1 | MiniMax formatter honors disabled tool choice. Scala explicitly serializes ToolChoice.None but has no MiniMax-specific tools-removal formatter; assess endpoint requirements before claiming equivalence. |
| `e05d45e0` | 1 | Harness builder inherits state-save ConflictPolicy. Scala uses explicit CAS expectations/conflict results and host execution ownership, not an automatic last-writer-wins agent-state policy. |

The six additional commits since fbbc2b63 require the following bounded disposition:

| Commit | Observed change and current status |
| --- | --- |
| `bb0d0a78` | Release notes/metadata and 2.0.5-SNAPSHOT version preparation. This is not a behavioral port; the separate v2.0.4 release tip requires its own comparison before claiming release alignment. |
| `c95eb1f1` | ToolEmitter exposes its associated tool-call ID. Scala contextual tool authors already receive ToolInvocation.Active.call.id, with typed Detached absence and an occurrence ordinal. This is a source-level counterpart, not merely event tagging; changed upstream test mapping remains to be reconciled. |
| `a2cbea5c` | Large Harness/context and Service/session redesign, including journal/session APIs, request projection and execution identity. The whole commit changes 1,918 files; its core/harness behavior and changed tests have not been exhaustively classified. A focused outcome inventory is required; Service/JEV infrastructure is not automatically library scope. |
| `b4ee8e32` | OpenAI Official Spring Boot auto-configuration/starter. Spring wiring is Java-specific; this does not implement or certify an equivalent Scala provider adapter. |
| `a1f0b45f` | OpenAI Responses server tools, deferred loading and provider-item replay; shared core also changes partial Running versus terminal server-tool handling. The current Scala Chat Completions profile is not Responses support. Review the shared completion semantics separately from provider expansion; applicability and executable equivalence remain open. |
| `768d9613` | JDBC skill/resource tables and repository operations gain namespace isolation. Scala has no equivalent JDBC skill repository; existing storage addresses are not evidence of this API. Reconcile with retained storage obligations before deciding scope. |

- [x] Refresh remote main/tags and record the exact six additional commits without changing the pinned working tree.
- [ ] Classify the additional core/harness changes and applicable changed tests, including server-tool terminal handling, tool-visible correlation and session/request projection. Preserve original promised behavior; record any Java/host-specific adaptation explicitly.
- [ ] Compare the separate v2.0.4 release tip before claiming alignment with that release. New release/main commit counts do not prove transferred behavior.

The finite BOM method mapping is against change 2c503d88, not a repin of the existing method inventory:

| Upstream method | Current executable mapping or intentional domain adaptation |
| --- | --- |
| MarkdownSkillParserTest.testParseWithLeadingBom | SkillMarkdownParserSpec existing table covers LF/CRLF/CR, exact interior metadata/body BOM and whitespace preservation. |
| MarkdownSkillParserTest.testParseBomWithoutFrontmatter | Both checked declaration parsers require frontmatter and reject missing declarations as InvalidConfiguration. Raw Markdown passthrough is a generic-parser API difference, not newly implemented behavior. |
| FileSystemSkillRepositoryTest.testLoadSkillWithUtf8Bom | LocalSkillRepositorySpec existing actual UTF-8 fixture covers discovered declared ID and exact body; original byte bounds remain enforced. |
| SubagentDeclarationPhaseATest.loader_parsesUtf8BomBeforeFrontmatter | SubagentDeclarationParserSpec and LocalSubagentDeclarationsSpec existing cases cover initial BOM and exact declaration/body loading. Body whitespace is preserved instead of Java trim. |


- [x] Inspect and account for all 27 fetched commits while preserving the existing pin and original-plan checklist.
- [x] review_fp and review_invariants approved this finite source-applicability classification in the final 19-file scope against 24a9980; every commit is accounted for, with no repin or new executable parity claim.
- [x] Implement the applicable leading-BOM skill/declaration framing change with existing parser/local-file groups and independent source reviews. The [six-file manifest](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/skill/README.md#utf-8-bom-frontmatter-compatibility) owns exact identities and final verification gates.
- [x] Complete full verification and fresh artifact consumption for the BOM increment.
- [x] review_fp and review_invariants approved the final combined twenty-file BOM scope against 7692d70; no whole upstream repin or SkillManageTool API transfer is claimed.
- [ ] Decide retained applicability of the remaining new behavior: search modes, provider-specific replay/formatting, middleware lifecycle, tracing and persistence configuration. No unimplemented capability is closed by classifying its commit.
- [ ] Review changed upstream test methods before any future repin or broader parity claim; the current pinned method counts do not include these additions.

## Durable terminal delivery acknowledgement

Pinned WorkspaceTaskRepositoryDeliveryTest is mapped to the [five-file acknowledgement contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#durable-terminal-acknowledgement). Stored terminal observations stay immutable; first host acknowledgement time and pending exclusion share one conditional aggregate. Schema-1 data reads as pending without invented dates. Paired store contracts and independent PostgreSQL adapters cover acknowledged state surviving reopen/concurrent publication and uncertain writes. Both source reviews and corrected focused verification pass; full verification, four RC1 packages and archive preparation pass; fresh standalone consumption also passes; review_fp and review_invariants approve final combined documentation against `1925481` for sixteen files (five unchanged Scala identities and eleven Markdown files), with no unresolved findings.

- [x] findPendingDeliveries_includesOnlyTerminalUndelivered: pending selects only stored terminal records without an acknowledgement stamp; no live Exit is fabricated.
- [x] markDelivered_isIdempotent: one first timestamp survives repeat acknowledgement and same-ID concurrent acknowledgement.
- [x] findPendingDeliveries_excludesAlreadyDelivered: acknowledged records leave the durable pending snapshot.
- [x] isDelivered_reflectsStamp: typed acknowledgement distinguishes absent record, Pending and Acknowledged, including through a fresh adapter.
- [x] markDelivered_missingRecord_isNoop: Missing inserts no record or acknowledgement.
- [x] findPendingDeliveries_emptySession_returnsEmpty: an empty host-selected partition returns no pending observations.
- [ ] findPendingDeliveries_orderedByLastUpdatedAtAscending: current aggregate publication order does not restore completion timestamps; schema-1 dates are not invented.
- [x] heartbeat_doesNotClobberDeliveredAt (138): pinned Java uses a terminal read/touch/write proxy and asserts the original delivery stamp survives. The [explicit heartbeat adaptation](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#explicit-owner-heartbeat) preserves exact terminal acknowledgement without a terminal touch/write; the actual PostgreSQL group checks the complete versioned snapshot after refresh, while the store conflict table preserves a concurrent terminal+ACK winner. Both six-source approvals and full verification pass; review_fp and review_invariants approve the final combined scope against 3b31430: six unchanged Scala identities and fourteen Markdown files, with no unresolved findings. No scheduler or wider orphan-sweeper mapping is implied.
- [x] Independently source-review the five-file acknowledgement scope and run its corrected focused harness/actual PostgreSQL contracts.
- [x] Pass final full verification, four RC1 packages and archive admission for these exact sources.
- [x] Pass fresh standalone consumption for these exact sources in target/durable-ack-final-consumer.log; its unchanged probe does not independently exercise acknowledgement.
- [x] review_fp and review_invariants approve final combined documentation against `1925481` for sixteen files (five unchanged Scala identities and eleven Markdown files), with no unresolved findings.
- [x] Integrate bounded exact reasoning reminder acknowledgement under the [current reminder contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#automatic-reasoning-reminders).
- [x] Implement and source-review explicitly bound agent_task consumption under the [current inspection contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#bound-task-inspection-consumption); focused/full and artifact checks pass.
- [ ] Complete the [remaining task-detail metadata contract](#task-output-source-obligations) and remote wakeup ownership separately; the local inspection/wait contracts have bounded accepted scopes and supply no exactly-once delivery claim.

## Explicit success-only terminal delivery

The [three-file explicit delivery seam](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#explicit-terminal-batch-delivery) adapts the delivery-before-ack ordering used by pinned SubagentsMiddleware and its SubagentDeliveryTest. It delivers at most ten immutable observations, joins callback-scope cleanup and then acknowledges exactly those IDs. Paired contracts retain partial/uncertain write behavior and duplicate-consumer semantics; real PostgreSQL verifies explicit delivery through a fresh durable registry. Both source reviews and corrected focused checks pass. This does not close whole upstream middleware/tool consumption methods merely by supplying a host callback.

- [x] Implement explicit bounded delivery before acknowledgement, skipping empty snapshots and rejecting selected duplicate IDs.
- [x] Preserve callback/cleanup failure and cancellation without acknowledgement; close/join callback resources before the first acknowledgement.
- [x] Retain earlier stamps after later uncertain failure, accept AlreadyAcknowledged and fail Missing without fabricated delivery success.
- [x] Independently review the three exact source identities and run paired harness/actual PostgreSQL contracts.
- [x] Pass final full verification, four packages, archive admission and fresh standalone consumption in target/durable-delivery-final-verify/preparation/consumer.log; the unchanged standalone probe does not independently exercise delivery.
- [x] review_fp and review_invariants approve final combined documentation against `bad689e` for fourteen files (three unchanged Scala identities and eleven Markdown files), with no unresolved findings.
- [x] Integrate actual reasoning reminder persistence and exact accepted-response acknowledgement under the [current reminder contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#automatic-reasoning-reminders). The explicit worker attribute is implemented and source-reviewed under the [worker identity contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#worker-identity); full/artifact checks pass and review_fp and review_invariants approve the final combined scope against 7bff28a: ten unchanged Scala identities and fourteen Markdown files, no unresolved findings.
- [ ] Complete the [remaining task-detail metadata contract](#task-output-source-obligations) and durable remote wakeup semantics. Current-owner completion/suppression is supplied by the notification contract; the separate inspection/wait helper does not itself install notifications.

## Workspace task repository method inventory

Pinned `WorkspaceTaskRepositoryTest` contains 25 executable test methods. review_fp independently checked their actual assertions against the accepted [registry publication](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#registry-terminal-publication), [acknowledgement](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#durable-terminal-acknowledgement), SubagentTaskObservationSpec and actual PostgreSQL/ReAct evidence. Twenty-four bounded adaptations, including persisted-origin exclusion687, are accepted with both final combined approvals and passing full/database/artifact/consumer checks. Task-file routing857 is the one unimplemented method. These adaptations preserve the listed observable behavior through immutable observations and explicit owner partitions. Option/Missing replaces null, and fresh adapters/registries demonstrate reopen rather than a separate restarted JVM. This is not acceptance of Java API shapes or the whole repository class.

- [x] putTask_writesRecordToWorkspace (103): execute the configured child and persist COMPLETED with exact text.
- [x] putTask_writesFailedOnException (136): persist FAILED with the execution failure message.
- [x] getTask_fallsBackToWorkspaceAfterLocalTasksCleared (178): a fresh registry reads persisted completion and result without replay.
- [x] getTask_returnsNullWhenNothingFound (210): an absent observation returns Option/Missing.
- [x] listTasks_sessionIsolation (220): host-selected partitions isolate exact task IDs.
- [x] listTasks_readsFromWorkspaceAfterCompaction (317): persisted task IDs remain discoverable through a fresh binding.
- [x] listTasks_terminalStatusNotOverridden (360): persisted terminal truth wins a live Running snapshot.
- [x] listTasks_withFilter_crossNode (388): fresh model-facing listing filters exact completed and failed IDs.
- [x] workspaceManager_taskRecordRoundTrip (444), state/result/count subset: retained Running (456-457), exact terminal result (461-462) and mixed enumeration count (466) are verified through the [stored view contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#stored-task-views). Both independent source reviews and public harness/actual fresh-adapter PostgreSQL checks pass, as does full verification. Both independent reviewers approve the final combined scope under the linked contract. These checks do not assert agent/session metadata fields or timestamps.
- [x] workspaceManager_taskRecordRoundTrip (469), physical workspace JSON: the [local JSON integration](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/storage/README.md#local-versioned-json) creates agents/parent/tasks/sess-rt.json and checks mixed Pending/Running/Completed/Failed records, exact results/counts, acknowledgement and fresh-scope reopen without execution replay. Both independent reviewers approve eight Scala identities against 73b0fb1; focused/full/artifact/archive checks pass. review_fp and review_invariants approve the final combined scope against 73b0fb1: eight unchanged Scala identities and twenty-two Markdown files, with no unresolved findings. This is the physical assertion and associated immutable-state adaptation, not Java envelope/API equivalence or acceptance of unasserted metadata.
- [x] cancelTask_writesCancelRequestedToWorkspace (263): the [current-owner cancellation adaptation](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#durable-cancellation-intent) preserves intent-before-signal and exact admitted identity. Unlike the pinned premature CANCELLED assertion, Pending/Running remains nonterminal until actual child cleanup joins and truthful terminal publication is confirmed. Focused/actual PostgreSQL and post-confirmation caller-interruption evidence pass; both final source reviewers approve ten identities against e7978ab, and full/artifact/archive/consumer verification passes. Final review_fp and review_invariants approve the final combined scope against e7978ab: ten unchanged Scala identities and fourteen Markdown files, with no unresolved findings. No listener, remote signal, recovery or whole-class acceptance is claimed.
- [x] heartbeat_refreshesLastUpdatedAt (478): the [explicit heartbeat contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#explicit-owner-heartbeat) refreshes actual retained local Running admissions, changing only lastUpdatedAt. Exact controlled-clock and independent PostgreSQL reads pass; both final six-source reviewers approve. Scheduling is host-driven and failures are observable; no daemon is introduced. Full verification passes 1,642 tests and four packages; archive checks pass. Fresh consumer passes; review_fp and review_invariants approve the final combined scope against 3b31430: six unchanged Scala identities and fourteen Markdown files, with no unresolved findings.
- [x] heartbeat_skipsCompletedTasks (534): joined actual local tasks are skipped, and store CAS guards terminal or cancellation winners. Raw versioned snapshots prove no payload/revision write; counted clock assertions prove no refresh clock sample for ineligible records. Both six-source approvals and focused checks pass; full verification passes 1,642 tests and four packages; archive checks pass. Fresh consumer passes; review_fp and review_invariants approve the final combined scope against 3b31430: six unchanged Scala identities and fourteen Markdown files, with no unresolved findings.
- [x] sweepOrphanedTasks_marksStaleRunningAsFailed (578): the [host-driven recovery contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#host-driven-task-recovery) reconciles genuine Running data at the exact stale boundary to typed Failed("executor lost") only under explicit host authority and unchanged whole-record CAS; fresh near-miss data remains unchanged.
- [x] sweepOrphanedTasks_skipsLiveTasks (614): [host-driven recovery contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#host-driven-task-recovery) skips every retained local ID before authority acquisition and rechecks under the admission gate before CAS. Actual running, paused, joined/held-publisher and concurrent admission cases preserve owned work.
- [x] sweepOrphanedTasks_skipsTerminalTasks (665): [host-driven recovery contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#host-driven-task-recovery) excludes terminal candidates and returns Changed for terminal/ACK winners. Exact full-record and independent PostgreSQL payload/revision snapshots remain unchanged.
- [x] sweepOrphanedTasks_skipsRemoteTasks (687): [persisted origin](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#persisted-execution-origin) excludes AgentProtocol observations before local recovery authority; local permits/start/heartbeat also reject them. Both reviewers approve twelve Scala identities and full/database/artifact/consumer checks pass; both reviewers approve final combined acceptance against a5da755. This is persisted producer/exclusion behavior, not endpoint polling.
- [x] sweepGate_claimsBeforeSweep (716): [host-driven recovery contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#host-driven-task-recovery) claims once before task-store scanning and publication-clock sampling. An injected scheduling-domain gate replaces the Java task-sweep name; no scheduler is installed.
- [x] sweepGate_deniedClaimSkipsSweep (734): [host-driven recovery contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#host-driven-task-recovery) returns Throttled without task-store scans/writes or terminal-clock sampling when the claim is denied; the gate may use its own monotonic scheduling clock.
- [x] sweepGate_localGateThrottlesSecondSweep (761): [host-driven recovery contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#host-driven-task-recovery) preserves first admission/immediate denial and additionally proves just-before-gap denial, exact-gap re-admission and backward-time refusal. Defaults retain the pinned 10-minute stale timeout and 5-minute sweep interval.
- [x] listAllTaskRecords_returnsAcrossAllSessions (789): bounded read-only cross-session discovery is source-approved with full/artifact checks passed under the [local task workspace](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#local-task-workspace); session identities remain separate. Final combined approval by both reviewers is recorded in the linked contract.
- [x] listAllTaskRecords_emptyForUnknownAgent (812): source-approved and full/artifact verified; absent validated agent/tasks directories return empty without creating ownership or directories. Final combined approval by both reviewers is recorded under the local workspace contract.
- [x] listAllTaskRecords_skipsOldFiles (821): source-approved and full/artifact verified actual file-mtime filtering excludes strictly older files and includes the cutoff. Old corrupt payloads skip decoding after safe-path checks. Final combined approval by both reviewers is recorded under the local workspace contract.
- [ ] remoteFilesystemSpec_includesTasksRoute (857): composite remote task-route behavior is absent; PostgreSQL storage is not this filesystem route.
- [x] cancellationListenersObservePersistedTerminalState (882): the [completion notification contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#completion-notifications) observes exact durable terminal truth after joined owned cancellation cleanup; both eight-Scala source reviews and full verification pass. This deliberately avoids premature CANCELLED; review_fp and review_invariants approve the final combined scope against ff4566e: eight unchanged Scala identities and fourteen Markdown files, with no unresolved findings.
- [x] executionOwnerCanSuppressWakeupWithoutLosingDurableDelivery (923): same-registry suppressed and notified submissions retain persisted pending output without ACK. The unsuppressed callback observes the confirmed record through an independent PostgreSQL adapter; reopening does not replay it. Both eight-Scala source reviews and full verification pass; review_fp and review_invariants approve the final combined scope against ff4566e: eight unchanged Scala identities and fourteen Markdown files, with no unresolved findings. No durable external delivery claim is inferred.

Both independent reviewers approve the six recovery Scala identities against b4e3b37; 28 harness and two actual PostgreSQL focused groups pass. Full verification passes 1,651 tests and four packages; archive admission and fresh standalone consumption pass. review_fp and review_invariants approve the final combined scope against b4e3b37: six unchanged Scala identities and fourteen Markdown files, with no unresolved findings. The current [origin contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#persisted-execution-origin) implements remote687 exclusion; the [local workspace contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#local-task-workspace) implements discovery789/812/821. These contracts do not provide remote execution or executable session hydration.

TaskRecord's broader identity, session, time, transport, confirmation and remote-event metadata remain separate applicability obligations. They must not be retroactively attributed to assertions absent from a listed test. No automatic heartbeat or sweeper is introduced by this inventory; host-driven operations must retain the required behavior and scope ownership.

## Accepted-response boundary for subagent delivery

The [accepted-response observer](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#accepted-model-response-observation) supplies the missing per-model-response lifecycle seam for original reasoning reminder delivery. Its paired three-group suite checks actual selected/projected input, accepted canonical history, discard/replace/thinking behavior, suspension/resume, no-fallback ordinary failure and joined cancellation with exact cleanup defects. Both independent source reviews, 57 focused core contracts, full verification, four RC1 packages, archive preparation and fresh external consumption pass; review_fp and review_invariants approve the final combined scope against 44c2ded: three unchanged Scala files and ten Markdown files.

- [x] Implement and independently review the runtime seam with persisted assistant state before observation and local execution afterwards.
- [x] Implement harness reminder rendering/capture and acknowledge only selected exact IDs present in the winning request and accepted canonical history; see the [current reminder contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#automatic-reasoning-reminders).
- [x] Classify all nine actual SubagentDeliveryTest methods below, with nine bounded behavioral adaptations; the worker attribute uses explicit checked host identity rather than an inferred label, with source/full/artifact checks passed. review_fp and review_invariants approve the final combined scope against 7bff28a: ten unchanged Scala identities and fourteen Markdown files, no unresolved findings.

## Explicit terminal binding prerequisite

- [x] Implement and independently source-review exact borrowed terminal-store propagation through public catalogs and ordinary/restored/retained sessions under the [binding contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#terminal-store-bindings).
- [x] Verify one public-factory table plus the existing restored-session fixture; 20 focused groups pass. These are Scala integration prerequisites and close no additional whole Java method.
- [x] Pass full/artifact/archive/external-consumer checks for the six-source binding increment; the external probe does not independently exercise bindings.
- [x] review_fp and review_invariants approve the final combined scope against ed6d6b4: six unchanged Scala identities and thirteen Markdown files, no unresolved findings.
- [x] Implement and independently source-review explicit routed persisted-result acknowledgement with captured target authority; a persisted observation does not prove delivery, execution ownership or runtime restoration.

## Async result wait method inventory

All 18 executable methods in pinned WaitAsyncResultsToolTest have bounded behavioral mappings below. The [local barrier contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#local-task-wait-barriers), [scoped inbox/session contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#scoped-inbox-and-shared-wait-budget), paired specifications and actual PostgreSQL consumption provide the evidence. Checked UUID selections, a 128-member cap, fixed local membership, joined publication, explicit scoped dependencies and post-persistence acknowledgement are adaptations, not identical Java APIs or whole-class parity. review_fp and review_invariants approve the ten exact source identities against d38b376. Focused/full verification, four packages, archive admission and fresh standalone consumption pass; review_fp and review_invariants also approve the final combined scope against d38b376: ten unchanged Scala identities and sixteen Markdown files, with no unresolved findings. The canonical acceptance owns exact identities and evidence limits.

- [x] timeoutClampedToMax (58): cap a requested 600-second wait at 120 seconds, verified through actual tool execution and deterministic TestClock.
- [x] nullTimeoutUsesDefault (74): the upstream method actually supplies 1, not null; actual tool execution times out at one second. The separate omitted/nonpositive normalization contract uses the source-declared 60-second default.
- [x] consecutiveEmptyWaitsRejected (88): two admitted empty timeouts exhaust the same scoped budget across inbox and barrier modes; the third blocking request returns BudgetExhausted with conditional nonblocking alternatives.
- [x] budgetExhaustedButResultsArrivedRecovers (114): an actual pending inbox overrides exhaustion and resets the budget without fabricating a completed barrier or acknowledging tasks.
- [x] counterResetsOnSuccess (142): arrival resets the next empty timeout to count one; ready Completed/Empty barriers reset the same session budget.
- [x] noSessionReturnsError (166): pure barrier-only factories reject no-selection waiting; scoped factories explicitly install the checked service. No nullable context or hidden session map is introduced.
- [x] timeoutMessageDoesNotEncourageRetry (174): exhaustion suggests inspecting task_list/agent_task only if the host installed them; the wait-only toolkit does not imply those capabilities exist.
- [x] allTasksTerminalReturnsImmediately (185): status-only readiness returns AllTerminal for completed and failed workers with an empty inbox without joining a pending publication receipt or granting acknowledgement.
- [x] nonTerminalTasksProcedsToWait (208): running registry work or an explicit typed external-work probe enters bounded waiting; timeout leaves borrowed work alive.
- [x] taskIdsWaitUntilAllListedTasksTerminal (221): the paired suite waits for every selected member and preserves explicit order; actual bound-tool and PostgreSQL rows embed ordered terminal previews.
- [x] taskIdsTimeoutWhenAnyListedTaskStillRunning (255): actual wait_async_results reports initial/remaining IDs while the unfinished task remains Running; component caller cancellation also preserves its worker.
- [x] taskIdsRejectsUnknownTaskIds (275): a selected running-plus-unknown set fails admission before waiting, and the actual tool returns Error naming the missing UUID.
- [x] waitAllWaitsForCurrentNonTerminalSnapshot (300): actual wait_all captures the initially locally running handle before a held read; later submission is excluded from waiting and the exact returned JSON.
- [x] waitAllEmptySnapshotReturnsImmediately (331): empty local capture and actual wait_all return Empty without touching an unrelated blocked backend list. The unchanged defective draft failed this oracle before correction.
- [x] barrierEmbedsResultsAndMarksDelivered (345): actual bound ReAct wait and real PostgreSQL consume exactly their ordered Failed/Completed projections after final persistence. Redaction/prior failure leave records pending; a later uncertain acknowledgement preserves committed stamps without retry. This strengthens upstream pre-return best-effort acknowledgement.
- [x] barrierModeRequiresTaskRepository (360): Tasks mode with no registry fails explicitly before taking task authority.
- [x] nullTaskRepositoryFallsThrough (370): an explicit inbox-only service without a registry waits for arrival and cannot infer no-work from a missing dependency.
- [x] emptyRepositoryDoesNotClaimWorkIsRunningOrCompleted (510): an idle external probe and empty registry return NoTasks; a latched arrival during the probe wins even if the host drains it.

TaskTool.taskOutput (129-139) acknowledges before formatting/return and suppresses acknowledgement exceptions. Ordinary task/wait factories observe without acknowledgement; the explicitly bound [result integration](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#bound-task-inspection-consumption) consumes only unchanged final persisted projections. Local wait handles survive registry forgetting, and selected terminal-only records can reopen without live capabilities. Reopened running barriers still require durable admitted/running identity. Legacy inbox-any uses an explicit arrival/inbox dependency and never substitutes pending terminal records for arbitrary messages. The host owns payloads and atomic presence updates; the scoped signal owns arrival observation only. No durable session restoration or cross-process running authority follows from these wait mappings.

## Reasoning reminder method inventory

Pinned SubagentDeliveryTest has nine executable methods. The [middleware contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#automatic-reasoning-reminders) and its three grouped contracts provide current evidence. Exact output is retained in the host store; reminders escape untrusted content and expose bounded previews. The generic cap/overflow test uses twelve records and a remainder of two rather than Java's fifteen/five fixture. These adaptations do not claim identical Java API strings or a whole middleware-class port.

- [x] reminder_singleCompleted_rendersTaskResultBlock (135): framing/count/task_result/output and agent="worker" are verified through explicit checked host identity. Existing None records omit the attribute; XML metacharacters are escaped in actual model requests. PostgreSQL preserves the exact identity through publication, reopen and acknowledgement. UUID task IDs replace arbitrary strings; no worker identity is inferred from them.
- [x] reminder_failedRendersTaskErrorBlock (149): error state/task_error retains the failure message.
- [x] reminder_cancelledRendersCancelledLine (160): pure cancellation renders cancelled without claiming a successful result.
- [x] reminder_capsAndSummarisesOverflow (170): one reminder contains at most ten task blocks and the exact snapshot remainder.
- [x] reminder_hasSyntheticMetadata (184): User/system carries synthetic=true and reminder_kind=task_delivery.
- [x] onReasoning_writesDeliveryToAgentStateAndMarksAfterCompletion (198): canonical and actual request contain the same exact runtime reminder; acknowledgement follows an accepted response. Real reopened PostgreSQL bindings additionally verify pending data before acceptance and the retained first stamp.
- [x] onReasoning_doesNotReDeliver (238): unchanged canonical history retains one reminder and completed acknowledgement prevents a fresh capture on the next invocation.
- [x] onReasoning_multiDeliveryAggregatedToSingleMessage (273): one reminder aggregates the selected completed/failed/cancelled records; only selected IDs are acknowledged.
- [x] deliveryCapDoesNotAcknowledgeUnseenResultsAndWorksWithoutDeclaredSubagents (303): twelve pending records consume ten then two without a required subagent declaration catalogue.

The middleware additionally verifies exact-content tampering with retained metadata, projected-away reminders, discard/model/prior-observer failure, accepted thinking-only, partial uncertain acknowledgement, suspension/resume, cancellation and closure joining all three prior callback cleanups. These safety regressions are not additional upstream methods. Both independent source reviews, focused/full verification and artifact/consumer checks pass; review_fp and review_invariants approve the final combined scope against a33488e: four unchanged Scala identities and fourteen Markdown files, no unresolved findings.

## Persisted local observation prerequisite

The [local result observer contract](../../modules/core/src/main/scala/com/weisgnosis/agents/core/agent/README.md#persisted-local-tool-observation) adds the required post-persistence seam for future task-tool consumption. Its three grouped integration contracts cover approved occurrence identity, original/final snapshots, composed cleanup, return-direct, typed/defect failure, joined sibling cleanup, cancellation and excluded observation origins. They add no claimed whole Java method mapping. The separately bound inspection integration below uses this seam. Whole TaskTool/routing coverage remains open; the core port alone closes no wait methods. The separately verified local barrier and scoped inbox/session contracts above supply eighteen bounded mappings.

- [x] review_fp and review_invariants approve the three formatted Scala identities against cb320c9; corrected focused verification passes 23/0/0. Exact identities and evidence limits belong to the linked contract.
- [x] Pass full verification, four packages, archive preparation and fresh external consumption for these exact source identities.
- [x] review_fp and review_invariants approve the final combined scope against cb320c9: three unchanged Scala identities and ten Markdown files, no unresolved findings.
- [x] Implement and source-review explicitly bound inspection through the [current harness contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#bound-task-inspection-consumption); focused/full/artifact checks pass.
- [x] Integrate complete local wait-result acknowledgement with unchanged final projection and genuine runtime execution provenance.
- [x] Implement and independently source-review explicit session/catalog/session-catalog/directory acknowledgement contexts and selected fixed-session waits. The [routed contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#routed-task-result-consumption) owns exact identities and remaining final gates; this does not close the full taskOutput contract.

## Bound inspection consumption integration

The source-reviewed [bound inspection adapter](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#bound-task-inspection-consumption) connects trusted agent_task execution to required post-persistence observation. This adapts the consumption intent of pinned TaskTool.taskOutput while strengthening its pre-return acknowledgement/exception suppression: final runtime persistence precedes a typed acknowledgement attempt. Ordinary factories remain observational. Explicit middleware-bound session/catalog/session-catalog/directory factories have separately reviewed routed consumption; their linked final gates govern acceptance. Inspection alone supplies no wait-method evidence; the separate barrier and scoped inbox/session contracts above own the eighteen bounded wait mappings. No whole TaskTool class or exactly-once claim follows from it.

- [x] Implement exact trusted occurrence capture and unchanged final projection checks.
- [x] Verify actual runtime/real PostgreSQL consumption and failure/cancellation/closure behavior; exact evidence and inspection limits are in the linked contract.
- [x] review_fp and review_invariants approve the exact four Scala identities against ee87c13, manifest bd40999ade08311fae1567535f7baeb23a742792cdb273fb02f3a40ba25f8925; focused/full verification passes.
- [x] Pass four RC1 packages, archive preparation and fresh external consumption for these exact sources.
- [x] review_fp and review_invariants approve the final combined scope against ee87c13: four unchanged Scala identities and sixteen Markdown files, no unresolved findings. Manual English review, changed UTF-8/script/diff checks and 79-document/3,406-local-reference validation pass.
- [x] Integrate complete local wait-result consumption within the explicitly bound middleware toolkit.
- [x] Implement and independently source-review explicit fixed-session/catalog/session-catalog/directory routing and selected fixed-session waits under the [routed contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#routed-task-result-consumption). The separately reviewed single-task blocking/full-output and metadata contracts are below; the [stored-state scope](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#stored-task-views) has separate final gates.

## Stored task views

- [x] Reproduce the original stored-only Pending/Running visibility gap against unchanged 0bbae03 using public registry and task_list observations; target/stored-task-views-gap-red.log records only Terminal where all three records are expected.
- [x] Reproduce the draft terminal-only custom-store read-boundary regression through observe and actual agent_task before correction; target/stored-task-views-compatibility-red.log records incorrectly successful output instead of the exact backend error.
- [x] Implement/source-review complete mixed-state views, selected nonlocal rejection, task_output NotLocallyWaitable/no-ACK and legacy unresolved inbox behavior; focused/full verification passes.
- [x] review_fp and review_invariants approve the final combined scope against 0bbae03: twelve unchanged Scala identities and fourteen Markdown files, with no unresolved findings.

Pinned WorkspaceTaskRepository get/list fallback paths (515-553 and syntheticTask around 898) reconstruct incomplete futures or remote polling. Scala deliberately exposes stored status without an executable capability; waiting remains local-only. This explicit lifecycle adaptation neither claims remote completion polling nor waives the original restoration/owner-recovery obligations.

## Task output source obligations

The pinned harness TaskTool.java is implementation evidence; no paired TaskToolTest.java was found in the inspected pinned test inventory. These source obligations are not invented test-method mappings. Current post-persistence acknowledgement strengthens the upstream consumption boundary but does not establish whole-method parity.

- [x] Adapt taskOutput lines 75-124 under the independently source-reviewed [observational task_output contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#full-task-output-retrieval): blocking defaults to true, the default timeout is 30,000 milliseconds, and supplied timeout is clamped to 0-600,000 milliseconds. A zero budget observes immediately. Existing agent_task remains nonblocking; wait_async_results has a different multi-task contract and timeout range.
- [x] Preserve timeout ownership from BackgroundTask.waitForCompletion lines 134-150 under the same source-reviewed retrieval contract: timeout leaves the child running, while interruption joins only resources owned by the caller.
- [x] Return exact full typed terminal output from task_output through ordinary runtime processing. The new actual-parent fixture receives complete Unicode output before its installed processor changes final history; the existing 4,000-character agent_task preview remains a separate bounded observation. Existing eviction integration retains its own accepted contract; this fixture does not independently exercise disk offload.
- [x] Adapt formatTaskDetail identity/creation projection and taskOutput.updateLastCheckedAt through the [eight-source metadata contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#task-output-creation-and-check-metadata). Optional checked worker identity and original createdAt are typed JSON fields rather than the upstream text labels; pre-wait check updates preserve captured authority. Legacy dates remain absent. Both source reviews and focused checks pass; final delivery gates belong to the linked contract.
- [x] Reproduce loss of the explicitly supplied ToolExecutionError direct cause against unchanged bfe12fb for both Failed and Interrupted; target/typed-task-failure-red.log preserves the exact public wire expectations.
- [x] Implement and independently source-review formatTaskDetail failure/direct-cause presentation (238-249) through separate typed failure fields, strict legacy/current storage and full public output under the [typed failure contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#typed-task-failure-details). Both source reviews and focused/full verification pass; review_fp and review_invariants approve the final combined scope against bfe12fb (thirteen unchanged Scala identities and fourteen Markdown files), with no unresolved findings. The finite contract preserves supplied domain messages/direct causes and defect presence; it does not reconstruct arbitrary Throwable internals or establish whole TaskTool parity.
- [x] Obtain independent FP/ZIO and invariant source approval and proportionate focused coverage for observational retrieval; exact identities belong to the linked contract.
- [x] review_fp and review_invariants approve final combined documentation for the observational adapter against b70336f: four unchanged Scala identities and twelve Markdown files, with full/artifact/external-consumer verification.
- [x] Implement and independently source-review explicit bounded full-output persisted-result acknowledgement under the [bound-tool contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#bound-full-task-output-consumption), with measured receipt limits and no automatic registration.
- [x] review_fp and review_invariants approve the final combined scope against 584ecf1: five unchanged Scala identities and fourteen Markdown files, no unresolved findings. Source/full/artifact/external-consumer checks pass in the linked contract. Capacity denial and changed-output exclusion are explicit typed adaptations; no universal delivered/exactly-once claim follows.
- [x] Implement and independently source-review actual pre-start durable admission with schema-4 Pending/Running observations, preserved original creation metadata and no execution on uncertain receipt under the [ten-source contract](../../modules/harness/src/main/scala/com/weisgnosis/agents/harness/subagent/README.md#durable-task-admission). Focused harness and actual PostgreSQL verification pass; full/artifact/archive/external-consumer checks pass; review_fp and review_invariants approve the final combined scope against f87ed98: ten unchanged Scala identities and fourteen Markdown files, with no unresolved findings.
- [x] Integrate original creation/check metadata into local and stored-terminal task_output with stable captured observation authority; both final source reviews and focused harness/PostgreSQL verification pass. Stored-only Pending/Running querying remains separate. Pinned TaskTool updates in-memory lastCheckedAt before blocking and renders created_at; durable checked-time storage is an explicit stronger adaptation, not an existing upstream persistence assertion.

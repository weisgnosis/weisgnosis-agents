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
- [ ] Complete message/content/usage and event-by-event mappings.
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

This is an inventory, not a classification of every method as covered or inapplicable. Broader domains such as persistent state, skills, built-in tools, middleware, and tracing remain unmapped rather than silently excluded. The next inventory task is the method-level message/event mapping, while remaining structured-output capabilities retain explicit pending tasks below.

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

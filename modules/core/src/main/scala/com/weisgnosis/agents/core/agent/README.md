# ReAct execution and structured output

ReActAgent retains history and serializes runs. Model requests receive their own Scope, closed before the next request. Assistant tool calls are persisted before dispatch. Completed observations survive cancellation; unfinished local occurrences receive Error observations followed by one recovery message for the run. Thinking-only responses trigger bounded synthetic reminders. AgentConfig uses ToolExecution and a five-minute timeout; AgentResult uses AgentRunState and optional ChatUsage.

- [AgentConfig](AgentConfig.scala)
- [AgentResult](AgentResult.scala)
- [ReActAgent](ReActAgent.scala)
- [StructuredOutput](StructuredOutput.scala)

## Runtime events and result pairing

One correlated AgentStart/AgentEnd pair surrounds each engine attempt. End follows loop termination/reconciliation, including failure or interruption. Successful preparation commits history and publishes one runtime AgentResultEvent before End. Ordinary cooperative interruption publishes its recovery reply; failed structured preparation publishes no finalized result. Streaming uses a private Take queue carrying the original exit, joins the claimed event prefix, then drains the buffered suffix before termination. A provider-published AgentEnd cannot terminate delivery. Early consumer completion closes owned producer/model scopes. Publishers must remain within those scopes; the unbounded hub/queue do not provide backpressure.

After all configured iterations complete without a final response, the runtime emits exactly one ExceedMaxItersEvent with the attempt's replyId, configured maximum, and completed iteration count before the normal end. Completion on the last permitted iteration does not emit a limit event. Unlike the pinned upstream guard, this event never intentionally uses an empty replyId.

Completed provider-hosted inline calls bypass local execution and do not create Role.Tool messages. Each inline observation is consumed once by call ID and occurrence, retaining pending call order. Local calls sharing a server ID remain executable. For each inline result, publication is Start, one TextDelta or DataDelta per output block in original order, then End. Empty output emits only boundaries; state and full result metadata are preserved. Data deltas carry the correlated replyId. Full metadata propagation is an intentional Scala difference: pinned upstream inline events synthesize only the server-tool marker.

Cancellation reconciliation also consumes completed local observations by occurrence, matching a provided tool name as well as ID. A completed result cannot suppress every unfinished same-ID call, and parallel completion of a different named tool does not satisfy a pending call. Nameless observations match by ID/occurrence. Completed observations remain unchanged.

## Structured output

StructuredOutput supports native formatting and synthetic generate_response with root definition hoisting. Synthetic output completes only after acting finishes in the Completed state, without another model turn. Ordinary tool pairs and unrelated empty history remain. Final replies retain signed thinking exactly once and generating-request usage; AgentResult usage aggregates the run. Incomplete runs fail with AgentError.Interrupted and retain recovery history even after payload capture.

runStructured and streamStructuredEvents share the same preparation and commit path. Structured payload resolution selects existing metadata or parses visible text exactly once; that AST supplies both decoding and committed metadata. Preparation remains interruptible and timeout-bounded. A short masked section commits finalized history and publishes AgentResultEvent before AgentEnd. Cancellation after this commit may follow an already published result. Auto fallback retains two attempt boundary pairs, with no result from the failed native attempt; the run lock covers both attempts. Input event payloads and ToolCallState metadata do not grant runtime permission or stop/resume authority.

## Current acceptance

- [x] `review_fp` and `review_harness` approved structured preservation at `21bc69f`, lifecycle delivery at `62478ce`, validated-message migration at `a701612`, and canonical server markers at `8b96ce3`.
- [x] Both reviewers approved runtime emission/occurrence matching at `5451e03` and the final four-file structured completion increment against `5451e03` on 2026-09-29, with no unresolved findings in scope.
- [x] Nine new ReActAgentEmissionSpec regressions cover limits, mixed/empty inline output, metadata, repeated server IDs, local/server collisions, and sequential/parallel cancellation. Four initial emission tests failed before the fix.
- [x] Full formatting, fatal-warning compilation, and verification passed 311 core and 40 harness tests.
- [x] Publish finalized runtime results and expose structured event streaming; nine StructuredCompletionSpec regressions cover preparation, metadata preservation, failure, cancellation, fallback, and consumer cleanup. The native metadata-loss regression failed before correction.
- [ ] Complete hooks/model-capability parity and explicit event wire adapters.
- [ ] Integrate provider continuation without inline results, local structured tool streams/metadata, and complete model/tool event ordering.
- [ ] Integrate permissions, phase stopping, durable pending work, and harness middleware.

The [parity mapping](../../../../../../../../../../docs/architecture/test-parity.md) distinguishes tested behavior from pending upstream scenarios. The [model contracts](../model/README.md) document source and wire migration. Acceptance is limited to named scopes and revisions; changes require renewed independent review.

Exact reviewed structured completion increment:

| File | Git blob |
| --- | --- |
| [ReActAgent.scala](ReActAgent.scala) | 5a573be836df4efa949b8a07f5a18371be2f8e4d |
| [StructuredOutput.scala](StructuredOutput.scala) | b149f43ad3c02f86931336b975a90c5cba1a6347 |
| [ReActAgentEmissionSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentEmissionSpec.scala) | 53b32191a7d4977b6e28a3bee8d639667bec9ef1 |
| [StructuredCompletionSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/StructuredCompletionSpec.scala) | 8968d4aa477bb64cea74e257a3e3b28976677afb |

- [x] `review_fp` and `review_harness` approved the two-scenario [dynamic JSON spec](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/StructuredOutputDynamicSpec.scala) against `3963104` on 2026-09-29, with no findings. Exact reviewed Git blob: `9f4a3360e8064a9e5ae72d5bb88588b1997ef2e9`. Verification passed 311 core and 40 harness tests; general JSON Schema validation and legacy event envelopes are outside this scope.

# ReAct execution and structured output

ReActAgent retains history and serializes runs. Model requests receive their own Scope, closed before the next request. Assistant tool calls are persisted before dispatch. Completed observations survive cancellation; unfinished local occurrences receive Error observations followed by one recovery message for the run. Thinking-only responses trigger bounded synthetic reminders. AgentConfig uses ToolExecution and a five-minute timeout; AgentResult uses AgentRunState and optional ChatUsage.

- [AgentConfig](AgentConfig.scala)
- [AgentResult](AgentResult.scala)
- [ReActAgent](ReActAgent.scala)
- [StructuredOutput](StructuredOutput.scala)

## Runtime events and result pairing

One correlated AgentStart/AgentEnd pair surrounds each engine attempt. End follows loop termination/reconciliation, including failure or interruption. Streaming uses a private Take queue carrying the original exit, joins the claimed event prefix, then drains the buffered suffix before termination. A provider-published AgentEnd cannot terminate delivery. Early consumer completion closes owned producer/model scopes. Publishers must remain within those scopes; the unbounded hub/queue do not provide backpressure.

After all configured iterations complete without a final response, the runtime emits exactly one ExceedMaxItersEvent with the attempt's replyId, configured maximum, and completed iteration count before the normal end. Completion on the last permitted iteration does not emit a limit event. Unlike the pinned upstream guard, this event never intentionally uses an empty replyId.

Completed provider-hosted inline calls bypass local execution and do not create Role.Tool messages. Each inline observation is consumed once by call ID and occurrence, retaining pending call order. Local calls sharing a server ID remain executable. For each inline result, publication is Start, one TextDelta or DataDelta per output block in original order, then End. Empty output emits only boundaries; state and full result metadata are preserved. Data deltas carry the correlated replyId. Full metadata propagation is an intentional Scala difference: pinned upstream inline events synthesize only the server-tool marker.

Cancellation reconciliation also consumes completed local observations by occurrence, matching a provided tool name as well as ID. A completed result cannot suppress every unfinished same-ID call, and parallel completion of a different named tool does not satisfy a pending call. Nameless observations match by ID/occurrence. Completed observations remain unchanged.

## Structured output

StructuredOutput supports native formatting and synthetic generate_response with root definition hoisting. Synthetic output completes only after acting finishes in the Completed state, without another model turn. Ordinary tool pairs and unrelated empty history remain. Final replies retain signed thinking exactly once and generating-request usage; AgentResult usage aggregates the run. Incomplete runs fail with AgentError.Interrupted and retain recovery history even after payload capture.

The event boundary currently describes an engine attempt, not the entire structured-output API. Auto fallback can make two attempts, and structured decoding/history replacement follows the attempt boundary. Publishing finalized AgentResult events and structured streaming remain pending. Input event payloads and ToolCallState metadata do not grant runtime permission or stop/resume authority.

## Current acceptance

- [x] `review_fp` and `review_harness` approved structured preservation at `21bc69f`, lifecycle delivery at `62478ce`, validated-message migration at `a701612`, and canonical server markers at `8b96ce3`.
- [x] Both reviewers approved the final two-file runtime-emission/occurrence-matching increment against `9768064` on 2026-09-29, with no unresolved findings in scope.
- [x] Nine new ReActAgentEmissionSpec regressions cover limits, mixed/empty inline output, metadata, repeated server IDs, local/server collisions, and sequential/parallel cancellation. Four initial emission tests failed before the fix.
- [x] Full formatting, fatal-warning compilation, and verification passed 300 core and 40 harness tests.
- [ ] Complete finalized result publication, structured streaming, hooks/model-capability parity, and explicit event wire adapters.
- [ ] Integrate provider continuation without inline results, local structured tool streams/metadata, and complete model/tool event ordering.
- [ ] Integrate permissions, phase stopping, durable pending work, and harness middleware.

The [parity mapping](../../../../../../../../../../docs/architecture/test-parity.md) distinguishes tested behavior from pending upstream scenarios. The [model contracts](../model/README.md) document source and wire migration. Acceptance is limited to named scopes and revisions; changes require renewed independent review.

Exact reviewed runtime-emission increment:

| File | Git blob |
| --- | --- |
| [ReActAgent.scala](ReActAgent.scala) | 05e5a06d066972db6928b2622c5983f488601227 |
| [ReActAgentEmissionSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/ReActAgentEmissionSpec.scala) | 8949f752d488bc38dbeeb90cddf1bda771bb5a45 |

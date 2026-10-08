# Persistent-agent message sharing

`MsgHub.make(Chunk(alice, bob))` installs scoped automatic subscriptions between existing `ReActAgent` runtimes. Each agent retains its own private history and policy. Install `ModelInputTransform.peerAware` on participants to make received observations usable in subsequent model requests. See the complete offline [conversation example](../../../../../../../../../examples/src/main/scala/com/weisgnosis/agents/examples/MsgHubConversation.scala).

The hub starts no agent turn, model request, scheduler or background worker. The host chooses who speaks and when. Observing a reply never broadcasts it again. Closing the owning scope removes this hub's subscriptions and joins admitted deliveries; borrowed agents remain usable.

## Membership and delivery

Creation requires 1–64 distinct participants. `add` is idempotent for current members and does not replay history or announcements. `remove` clears that member's inbound and outbound relationships for this hub. `participants` reads a snapshot, not an execution lease. An agent supports at most 32 hub subscriptions.

`HubMode.Automatic` publishes final responses to current peers, excluding the sender. `HubMode.Manual` disables automatic publication. `setMode` changes future admissions. `broadcast(sender, messages)` explicitly delivers to all current members, including the sender; the sender must be a member. `announce(messages)` delivers host announcements with the hub's identity, without granting System authority in recipients.

Delivery follows membership insertion order. Each overlapping hub is independent and may deliver the same message again; message IDs are not deduplication keys. No shared transcript, history backfill or exactly-once delivery is supplied. Membership and delivery operations reject contention instead of creating an unbounded waiting queue. Active or suspended recipients reject observation without changing their state.

## Execution and publication

Final publication occurs after the sender's execution permit and owned execution cleanup are released, before its invocation returns. Ordinary, structured, prepared, streamed and resumed invocations share this boundary. Completed responses and cooperative interruption recovery messages are published. Suspended and Stopped results, failed invocations and genuinely cancelled fibers do not publish a final response.

`AgentResult.peerDeliveries` reports admitted hub/recipient outcomes separately from `AgentRunState`. A missing recipient identifies hub-level rejection. A busy recipient does not change Completed into failure or trigger another model/tool call. Manual delivery returns the same receipt type. Already delivered observations remain committed when another recipient rejects. Defects and interruption propagate; abandoning delivery can leave committed observations without returned receipts. Hosts must not retry an uncertain invocation or broadcast automatically.

Lifecycle events and completion observers describe execution before this delivery step. A terminal result-bearing stream value and prepared termination include the returned receipts; event messages are not delivery acknowledgements. The new default-empty result field and `peer_observation` content discriminator require rebuilding/upgrading consumers; no frozen binary compatibility is claimed.

`AgentResult` now has a sixth field, `peerDeliveries`. Constructor calls can omit its default, but positional extractors/Product consumers must handle six fields and JVM consumers must rebuild. Older JSON without the field decodes to an empty receipt collection. Older content decoders reject `peer_observation`; upgrade them before exchanging peer history.

## Stored observations and verification

Peer messages are retained as inert typed User observations, preserving original content and sender attribution through canonical history/snapshots. Attribution is not authentication. Local call/result occurrence matching remains separate. The [runtime guide](../agent/README.md#passive-peer-context) explains bounds, request projection, media and transcript/memory limits.

[MsgHubSpec](../../../../../../../test/scala/com/weisgnosis/agents/core/agent/MsgHubSpec.scala) covers public membership/delivery and invocation paths. Focused compilation, public hub scenarios and actual loopback peer projection pass. The runnable conversation, consolidated verification and packaged-consumer checks also pass. The Java v1.0.12 comparison and deliberate stronger cleanup are tracked in the [parity reference](../../../../../../../../../../docs/architecture/test-parity.md#framework-comparison-and-original-provider-scope).

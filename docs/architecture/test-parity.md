# Upstream scenario coverage and remaining parity work

The engineering target is one paired Scala test for every applicable upstream behavior. Current suites cover selected scenarios and ZIO-specific regressions; exhaustive 1:1 parity has not been demonstrated. Suite names and passing counts are not evidence of complete parity.

The review reference is the local `agentscope-java` checkout. Upstream Java implementation details are translated into Scala domain behavior, not copied as imperative APIs.

## Progress checklist

- [x] Implement selected local suites for messages/content, events, tools, stream parsing, ReAct, structured output, compaction, eviction, and permissions.
- [x] Add deterministic ZIO regressions for cancellation, scope cleanup, storage publication, and repeated compaction.
- [x] Record the known intentional differences below.
- [ ] Pin the upstream commit used for exhaustive comparison. This is the next task.
- [ ] Enumerate every upstream test method with a Scala counterpart, justified difference, or inapplicability.
- [ ] Complete message/content/usage and event-by-event mappings.
- [ ] Complete the tool matrix and platform-specific symlink integration coverage.
- [ ] Add live HTTP/provider transport integration tests.
- [ ] Complete ReAct and interruption scenario mappings, including lifecycle hooks not yet implemented.
- [ ] Complete the thinking-only empty-response scenario mapping.
- [ ] Cover explicit empty-input structured runs, dedicated thinking preservation, and remaining repetition cases.
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

A future exhaustive parity audit must pin an upstream revision and enumerate each upstream test method with its Scala counterpart, an intentional difference, or a justified inapplicability. Unmapped tests remain pending. New implementations must add their paired scenarios and deterministic ZIO regressions before claiming parity for that component.

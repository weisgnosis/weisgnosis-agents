# Upstream scenario coverage and remaining parity work

The engineering target is one paired Scala test for every applicable upstream behavior. Current suites cover selected scenarios and ZIO-specific regressions; exhaustive 1:1 parity has not been demonstrated. Suite names and passing counts are not evidence of complete parity.

The review reference is the local `agentscope-java` checkout. Upstream Java implementation details are translated into Scala domain behavior, not copied as imperative APIs.

| Upstream area | Current Scala coverage | Remaining audit work |
| --- | --- | --- |
| Messages and content | Constructors, role/content handling, JSON codecs and usage | Enumerate every upstream case and document intentional representation differences |
| Events | Event codecs, source tagging and stream order | Complete event-by-event scenario mapping |
| Tools | Registration, decoding, successful/error observations, defects and path checks | Full upstream tool matrix and platform-specific symlink integration |
| Model streams | SSE fragments, indexed parallel calls, incomplete JSON, reasoning adaptation | Live HTTP/provider transport tests |
| ReActAgentTest / server tools | Text/thinking, local tools, multiple calls, errors, iteration limits, inline provider results, events | Full method-level mapping; concurrent-run behavior differs intentionally |
| Interruption tests | Cooperative interruption, fiber cancellation, completed-result preservation, timeout and request-scope cleanup | Map all upstream lifecycle hooks; the hook subsystem is not implemented |
| Empty-response retry tests | Synthetic reminders and bounded thinking-only retries | Exhaustive upstream case mapping |
| Structured output tests | Native format and fallback, synthetic capture, root definitions, decoding, mixed-call preservation, accumulated usage | Explicit empty-input structured run, dedicated thinking-preservation coverage and remaining upstream repetition cases |
| Compaction and eviction | Pair-safe cutoff, repeated IDs, summary provenance, below-trigger preservation, previews, collisions and concurrent publication | Full upstream scenario mapping; hostile-workspace secure backend |
| Permission gates | Priority, promises, serialized resume and modified-input deny recheck | End-to-end durable host integration and full upstream mapping |

## Deliberate behavioral differences

- Concurrent calls to the same ReActAgent are serialized. Upstream concurrent-call rejection tests cannot be claimed as ported unchanged.
- Immutable Scala models and typed errors replace Java builder/null-specific contracts. Each inapplicable upstream case still needs a recorded rationale in the exhaustive mapping.
- Interrupted unfinished local tools receive Error observations. Provider-hosted inline results do not create separate local tool messages.
- The current LlmClient is a port with scripted clients, not a live provider transport.

## Completion criterion

A future exhaustive parity audit must pin an upstream revision and enumerate each upstream test method with its Scala counterpart, an intentional difference, or a justified inapplicability. Unmapped tests remain pending. New implementations must add their paired scenarios and deterministic ZIO regressions before claiming parity for that component.

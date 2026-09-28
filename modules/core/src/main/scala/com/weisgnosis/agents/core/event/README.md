# Typed agent events

AgentEvent defines 31 event variants with JSON codecs and source tagging through withSource. Runtime publication uses ZIO Hub; serialization does not allocate IDs or read the clock.

## Implementation

- [AgentEvent](AgentEvent.scala)

See the [repository architecture](../../../../../../../../../../ARCHITECTURE.md) for integration boundaries and invariant definitions.

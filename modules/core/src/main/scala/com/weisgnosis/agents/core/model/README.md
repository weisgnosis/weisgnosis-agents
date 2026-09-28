# Immutable messages and content

Msg, ContentBlock, Role, MediaSource, ToolResultState, and ChatUsage form the shared domain model. Constructors and JSON codecs are pure; Msg.id is optional and message.identified allocates an ID inside ZIO. ToolResultState represents Success, Error, Interrupted, Denied, and Running. ReAct interruption reconciliation emits Error observations for unfinished local calls. Content accessors are total.

## Implementation

- [ChatUsage](ChatUsage.scala)
- [ContentBlock](ContentBlock.scala)
- [MediaSource](MediaSource.scala)
- [Msg](Msg.scala)
- [Role](Role.scala)
- [ToolResultState](ToolResultState.scala)

See the [repository architecture](../../../../../../../../../../ARCHITECTURE.md) for integration boundaries and invariant definitions.

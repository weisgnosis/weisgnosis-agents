# harness: source package index

- [eviction](eviction/README.md)
- [memory](memory/README.md)
- [permission](permission/README.md)
- [plan](plan/README.md)
- [subagent](subagent/README.md)

Package READMEs own current behavior, verification scope, and acceptance status. Planned packages are explicitly marked and are not accepted implementations.

ToolResultEvictor.processor adapts completed local and matched inline provider observations into ReAct through the core processing port. InteractivePermissionAuthorizer installs scoped in-memory policy/confirmation. ConversationCompactor.preparer installs validated history preparation, and ToolResultEvictor.preparer installs supplied-history eviction. See the [eviction contract](eviction/README.md).

ResultStorageLocation adds existing absolute-directory selection while retaining the default workspace cache. HostControlled ownership extends to the selected directory and its ancestors. See the [eviction contract](eviction/README.md).

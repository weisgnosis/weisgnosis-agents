# Large tool result offloading

ToolResultEvictor offloads text exceeding 80,000 characters with 2,000-character head and tail previews, a content-addressed key, and read_file instructions. Excluded tools bypass eviction. Failed persistence preserves the original observation; cancellation propagates. ToolResultStore.local(workspace, WorkspaceTrust.HostControlled) requires OS protection against untrusted mutation of the directory and its ancestors. WorkspaceTrust.Untrusted is rejected. A fully written temporary file is published using an atomic hard link, requiring filesystem hard-link support. Cleanup failures are logged without overriding persistence success. This backend is not a sandbox for attacker-writable workspaces.

## Implementation

- [EvictionConfig](EvictionConfig.scala)
- [ToolResultEvictor](ToolResultEvictor.scala)
- [ToolResultStore](ToolResultStore.scala)

See the [repository architecture](../../../../../../../../../../ARCHITECTURE.md) for integration boundaries and invariant definitions.

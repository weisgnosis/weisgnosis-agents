# Large tool result offloading

ToolResultEvictor offloads text exceeding 80,000 characters with 2,000-character head and tail previews, a content-addressed key, and read_file instructions. Excluded tools bypass eviction. Failed persistence preserves the original observation; cancellation propagates. ToolResultStore.local(workspace, WorkspaceTrust.HostControlled) requires OS protection against untrusted mutation of the directory and its ancestors. WorkspaceTrust.Untrusted is rejected. A fully written temporary file is published using an atomic hard link, requiring filesystem hard-link support. Cleanup failures are logged without overriding persistence success. This backend is not a sandbox for attacker-writable workspaces.

## Implementation

- [EvictionConfig](EvictionConfig.scala)
- [ToolResultEvictor](ToolResultEvictor.scala)
- [ToolResultStore](ToolResultStore.scala)

See the [repository architecture](../../../../../../../../../../ARCHITECTURE.md) for integration boundaries and invariant definitions.

## Current review acceptance

- [x] Reviewed revision: `fb80428`; accepted files: ToolResultStore.scala and ToolResultStoreSpec.scala.
- [x] Independent final approval: `review_fp`, reconfirmed for unchanged code on 2026-09-29 after corrections and cross-review.
- [x] No unresolved findings in the accepted scope: Atomic completed-file publication, explicit workspace trust, and cleanup failure handling.
- [x] Verification at the reviewed revision: `sbt verify`, 108 core and 40 harness tests, no failed tests or compiler warnings.

This is the accepted reference behavior for the scope above. HostControlled ownership and hard-link support are required. Hostile concurrent filesystem mutation, crash durability, and exhaustive upstream parity are outside this acceptance. Changes to this behavior require renewed independent review and an updated acceptance entry.

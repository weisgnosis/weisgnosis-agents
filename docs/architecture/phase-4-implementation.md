# Phase 4 implementation notes

## Context compaction

`ConversationCompactor` accepts an effectful summarizer, a pure `TokenCounter`, and optional flush/offload callbacks. The default counter estimates serialized message size at four characters per token; hosts can inject a model tokenizer. Summary failures remain typed failures and cancellation propagates through every hook.

Boundary selection uses a token-window binary search, retains the configured recent message count, and preserves assistant calls with their results. Reused call IDs are paired by occurrence. Orphaned tool results move into the summarized prefix. Prior summaries are included in summarization but excluded from flushing and retained history. System messages are preserved. Consecutive user turns are coalesced after summary insertion. The coalesced message records retained-user provenance so subsequent compaction can flush original user content without flushing summary text. Histories below the configured trigger are returned unchanged.

Old tool output and arguments are pruned before summarization, leaving the protected tail unchanged. MemoryFlushManager and MemoryConsolidator are not implemented in this delivery.

## Tool result eviction

`ToolResultEvictor` offloads text exceeding 80,000 characters, retaining 2,000 characters at each end and a read_file instruction. Configured file and memory tools are excluded. Content hashes distinguish reused call IDs with different output. Rewritten metadata, result states, and non-text blocks survive eviction. Failed persistence preserves the complete original observation; interruption remains cancellation.

`ToolResultStore.local(workspace, WorkspaceTrust.HostControlled)` resolves the workspace and output directory canonically. The explicit trust argument requires OS protection against untrusted mutation of the workspace, storage directory, and their ancestors; `WorkspaceTrust.Untrusted` is rejected. Canonical checks are defense in depth, not an atomic sandbox for attacker-writable directories.

The store writes a scoped temporary file and publishes completed content through an atomic hard link. Cooperative writers across separate store instances cannot expose partially written results. Existing files must be regular files in the expected directory with identical content. Hard-link support is required; unsupported filesystems produce a typed persistence failure, allowing eviction to retain the original result. Cleanup failures are logged without overriding persistence success. This is atomic publication, not a crash-durability guarantee. Filesystem effects run through ZIO.attemptBlocking. Privileged symbolic-link creation was unavailable on the Windows verification host.

## Permission decisions and confirmation

`PermissionEngine` is immutable. Independent policy, tool, and interceptor decisions combine with Deny > Ask > Passthrough > Allow. Unknown tools default to Ask. Restricted rules inspect an explicit argument path (command by default); unrelated JSON strings cannot authorize a call.

`PermissionRequests` registers a Promise before publishing an interactive request. Duplicate IDs fail, confirmations complete once, and interruption removes the pending entry. `PendingApproval` and `ConfirmResult` are serializable values for durable hosts. `PermissionEngine.resume` returns the updated policy and an approved or denied call, applying modified input without another model request. Deny rules are checked again against modified input.

Hosts must persist pending state before ending an activity, validate request identity, rerun tool-specific safety checks on modified input, and commit execution checkpoints according to their workflow's idempotency contract. This component provides decisions and suspension data; it does not install middleware into ReActAgent or supply a durable database.

## Core API hardening

- Message constructors and JSON decoding are now pure. `Msg.id` is optional, preserving externally supplied IDs; `message.identified` allocates an ID within ZIO when required.
- Toolkit registration, withTool, and merging return Either[AgentError, Toolkit]. Duplicate names are typed errors, and execution preserves existential input/output types without casts. Tool defects become error observations; interruption still cancels execution.
- PathSafety.isDangerousPath and expandTilde return effects. Parent traversal is rejected before normalization. Canonicalization resolves the nearest existing ancestor for new paths and reports failures instead of allowing them silently.
- Agent construction rejects invalid bounds and a missing model client. Agent history is exposed as an effect through `history`. AgentResult uses mutually exclusive AgentRunState cases instead of independent suspension/interruption flags.
- Synthetic structured output completes after the acting phase finishes in the Completed state with a captured generate_response value, without an extra model turn. Compression removes synthetic blocks while retaining ordinary calls and results in mixed turns. AgentResult.usage combines model turns; reply.usage retains the generating request without duplicating history metrics. Thinking blocks and unrelated empty messages are preserved. Interrupted structured runs fail before publishing captured data.

Structured completion resolves one payload AST for decoding and metadata, prepares within the attempt timeout, then commits history and publishes AgentResult before AgentEnd. Direct and structured streaming APIs share this path; Auto fallback retains two attempt boundary pairs. Pre-commit failures publish no finalized result.

## Verification

Run `sbt scalafmtAll scalafmtSbt`, then `sbt verify`. The Scala suites use local scripted clients, ZIO Test services, and temporary filesystem resources. They do not exercise a live provider endpoint. The configured formatter plugin is required for the repository verification commands.

The current implementation passed 434 core and 40 harness tests, with no failures or compiler warnings. The cleanup-failure regression intentionally emits a runtime warning. Current review acceptance is maintained in the [core README](../../modules/core/README.md) and [harness README](../../modules/harness/README.md), with package-level scope details. Resolved review reports are removed from the active tree; their history remains in Git. Remaining provider/event integration, exhaustive upstream parity, and host integration remain tracked work.

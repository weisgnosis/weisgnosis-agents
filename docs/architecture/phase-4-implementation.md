# Phase 4 implementation notes

## Context compaction

`ConversationCompactor` accepts an effectful summarizer, a pure `TokenCounter`, and optional flush/offload callbacks. The default counter estimates serialized message size at four characters per token; hosts can inject a model tokenizer. Summary failures remain typed failures and cancellation propagates through every hook.

Boundary selection uses a token-window binary search, retains the configured recent message count, and preserves assistant calls with their results. Reused call IDs are paired by occurrence. Orphaned tool results move into the summarized prefix. Prior summaries are included in summarization but excluded from flushing and retained history. System messages are preserved. Consecutive user turns are coalesced after summary insertion.

Old tool output and arguments are pruned before summarization, leaving the protected tail unchanged. MemoryFlushManager and MemoryConsolidator are not implemented in this delivery.

## Tool result eviction

`ToolResultEvictor` offloads text exceeding 80,000 characters, retaining 2,000 characters at each end and a read_file instruction. Configured file and memory tools are excluded. Content hashes distinguish reused call IDs with different output. Rewritten metadata, result states, and non-text blocks survive eviction. Failed persistence preserves the complete original observation; interruption remains cancellation.

`ToolResultStore.local(workspace)` resolves the workspace and output directory canonically. It rejects sensitive paths, traversal keys, redirected directories, and unsafe existing files. Files use exclusive creation, and reusing an existing content-addressed file requires identical content. Filesystem operations are suspended with ZIO.attemptBlocking. Privileged symbolic-link creation was unavailable on the Windows verification host; tests cover canonical local storage, sensitive directories, invalid keys, and content collisions.

## Permission decisions and confirmation

`PermissionEngine` is immutable. Independent policy, tool, and interceptor decisions combine with Deny > Ask > Passthrough > Allow. Unknown tools default to Ask. Restricted rules inspect an explicit argument path (command by default); unrelated JSON strings cannot authorize a call.

`PermissionRequests` registers a Promise before publishing an interactive request. Duplicate IDs fail, confirmations complete once, and interruption removes the pending entry. `PendingApproval` and `ConfirmResult` are serializable values for durable hosts. `PermissionEngine.resume` returns the updated policy and an approved or denied call, applying modified input without another model request. Deny rules are checked again against modified input.

Hosts must persist pending state before ending an activity, validate request identity, rerun tool-specific safety checks on modified input, and commit execution checkpoints according to their workflow's idempotency contract. This component provides decisions and suspension data; it does not install middleware into ReActAgent or supply a durable database.

## Core API hardening

- Message constructors and JSON decoding are now pure. `Msg.id` is optional, preserving externally supplied IDs; `message.identified` allocates an ID within ZIO when required.
- Toolkit registration, withTool, and merging return Either[AgentError, Toolkit]. Duplicate names are typed errors, and execution preserves existential input/output types without casts. Tool defects become error observations; interruption still cancels execution.
- PathSafety.isDangerousPath and expandTilde return effects. Canonicalization resolves the nearest existing ancestor for new paths and reports failures instead of allowing them silently.
- Agent construction rejects invalid bounds and a missing model client. Agent history is exposed as an effect through `history`. AgentResult uses mutually exclusive AgentRunState cases instead of independent suspension/interruption flags.
- Synthetic structured output completes when generate_response succeeds. Compression removes synthetic blocks while retaining ordinary calls and results in mixed turns. Usage combines model turns.

## Verification

Run `sbt scalafmtAll scalafmtSbt`, `sbt compile`, and `sbt test`. The Scala suites use local scripted clients, ZIO Test services, and temporary filesystem resources. They do not exercise a live provider endpoint. The configured formatter plugin is required for the repository verification commands.

Verified on Microsoft OpenJDK 21 with Scala 3.9.0: 103 core tests and 33 harness tests passed, with zero failures, ignored tests, or compiler warnings. The unavailable proxy was bypassed for dependency resolution using command-local JVM nonProxyHosts settings; repository proxy configuration was not changed.

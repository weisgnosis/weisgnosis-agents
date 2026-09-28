# Harness permission verification

PermissionGateSpec covers every dual-gate priority pair, deny/ask precedence, argument matching, and safe defaults. PermissionPromiseSpec covers publication ordering, duplicate IDs, one-shot completion, and cancellation cleanup. DurableResumeSpec covers serializable suspension, modified input, immutable rule updates, rejection, and deny rechecking. Upstream reference: PermissionEngineTest and HITL resume scenarios; durable persistence is supplied by the host.

Run `sbt "harness / test"`.

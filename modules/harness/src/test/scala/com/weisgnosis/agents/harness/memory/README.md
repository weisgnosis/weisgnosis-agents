# Harness memory verification

ConversationCompactorSpec covers safe boundaries, repeated IDs, summary replacement, user coalescing, protected-tail pruning, and hook failure/cancellation. TokenCounterSpec covers additive estimates. Upstream reference: ConversationCompactorTest; memory flush callbacks are tested as integration seams.

Run `sbt "harness / test"`.

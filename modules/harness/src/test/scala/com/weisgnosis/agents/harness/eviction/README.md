# Harness eviction verification

ToolResultEvictorSpec covers threshold boundaries, exclusions, previews, repeated IDs, metadata/state preservation, write failures, placeholder-like real output, and interruption. ToolResultStoreSpec verifies canonical local writes, sensitive paths, traversal rejection, and content collisions. Upstream reference: ToolResultEvictionMiddlewareTest; middleware state installation remains a host responsibility.

Run `sbt "harness / test"`.

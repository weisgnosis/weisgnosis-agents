# Typed domain failures

[AgentError.scala](AgentError.scala) defines the sealed domain failure hierarchy, including configuration, serialization, permission, model, timeout, and tool errors. Domain effects expose these failures in the ZIO error channel. Tool execution errors become model observations at the toolkit boundary; cancellation remains cancellation.

The dedicated exhaustive upstream error-scenario mapping remains tracked with the wider core parity audit. No new independent error-package acceptance is claimed by a documentation-only update.

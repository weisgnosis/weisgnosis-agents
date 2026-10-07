# Example integration checks

HostAssemblySpec contains two independent integration groups: selected MCP execution with authorization/history preparation/eviction/retrieval/transcript composition, and failed or cancelled assembly with joined cleanup while the caller scope remains open. The MCP peer is a deterministic in-process protocol fixture; it does not contact a provider or execute processes.

LiveProviderSmokeSpec verifies the disabled runnable path and explicit configuration using TestSystem; it never enables external requests.

The fixture approval is explicit test data, not a suggested host authorization policy. No upstream parity claim applies to this new integration example. review_fp and review_invariants approved this scope with no findings; the [module acceptance](../../../../../../../README.md#verification-and-acceptance) records final identities and full verification. Live-provider validation remains separate.
